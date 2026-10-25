package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptFletching;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptPick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.ArrayList;
import java.util.List;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * AutoFletching module.
 * <p>
 * Every material/product is resolved by <b>id</b> (gameval {@code ItemID} constants); display
 * names are only used in status/stop messages and for the production-interface row match, where
 * the real name comes from {@code ItemComposition.getMembersName()} at runtime.
 * <p>
 * Activities: darts, bolts, gem-tipped bolts, arrows, bow stringing, unstrung-bow carving,
 * arrow shafts, crossbow stocks and shields. The four log activities (carving, shafts, stocks,
 * shields) share one knife + logs recipe and differ only in the product, level, logs consumed
 * per action (2 for shields) and products created per action (15–105 for shafts).
 * <p>
 * Larger batches are tracked across ticks: a batch is started once and then polled by material
 * count, so a stack that takes longer than a single click cycle is not mistaken for a failed
 * dispatch.
 */
@Slf4j
public class FletchingScript implements AModule {

    private static final int MAX_CONSECUTIVE_CRAFT_FAILURES = 3;
    /** No material progress for this long while a batch is in flight = stalled batch. */
    private static final long BATCH_STALL_MS = 12_000;
    /** Confirmation window after the production click before the batch is counted as failed. */
    private static final int BATCH_START_CONFIRM_MS = 5_000;

    private Phase currentPhase = Phase.NONE;
    private long lastAfkTime;
    /** Set once per doAction() call; used by all craft methods for weather-modulated timing. */
    private double weatherMultiplier = 1.0;
    /** Prevent repeated exit attempts / Discord spam. */
    private boolean exitRequested = false;
    /** Track consecutive craft failures to trigger exit after persistent failures. */
    private int consecutiveCraftFailures = 0;

    // ── In-flight batch state (cleared by resetExitFlag/finish/fail) ──
    private boolean batchInFlight;
    private int batchMaterialId;
    private int batchMaterialPerAction;
    private int batchMaterialCount;
    private long batchLastProgressAt;
    private int batchProductId;
    private String batchProductionAction;

    public enum Phase {
        NONE, DARTS, BOLTS, BOLT_TIPS, ARROWS, BOWS, UNSTRUNG_BOWS, ARROW_SHAFTS, STOCKS, SHIELDS
    }

    /** Reset state when the module is re-enabled. Called by AScript on the NONE → active edge. */
    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveCraftFailures = 0;
        clearBatch();
    }

    // ── Phase resolution ────────────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.FLETCHING
                || config.fletchingActivity() == null) {
            currentPhase = Phase.NONE;
            return;
        }
        switch (config.fletchingActivity()) {
            case DARTS: currentPhase = Phase.DARTS; return;
            case BOLTS: currentPhase = Phase.BOLTS; return;
            case BOLT_TIPS: currentPhase = Phase.BOLT_TIPS; return;
            case ARROWS: currentPhase = Phase.ARROWS; return;
            case BOWS: currentPhase = Phase.BOWS; return;
            case UNSTRUNG_BOWS: currentPhase = Phase.UNSTRUNG_BOWS; return;
            case ARROW_SHAFTS: currentPhase = Phase.ARROW_SHAFTS; return;
            case STOCKS: currentPhase = Phase.STOCKS; return;
            case SHIELDS: currentPhase = Phase.SHIELDS; return;
            default: currentPhase = Phase.NONE;
        }
    }

    // ── Selection validation ────────────────────────────────────

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.FLETCHING) {
            return "no fletching activity selected";
        }
        switch (currentPhase) {
            case DARTS: {
                FletchingDartType dart = config.fletchingDartType();
                if (dart == null) return "no dart type selected";
                return levelError(dart.getLabel(), dart.getLevelRequirement());
            }
            case BOLTS: {
                FletchingBoltType bolt = config.fletchingBoltType();
                if (bolt == null) return "no bolt type selected";
                return levelError(bolt.getLabel(), bolt.getLevelRequirement());
            }
            case BOLT_TIPS: {
                FletchingBoltTipType tip = config.fletchingBoltTipType();
                if (tip == null) return "no gem-tipped bolt type selected";
                return levelError(tip.getLabel(), tip.getLevelRequirement());
            }
            case ARROWS: {
                FletchingArrowType arrow = config.fletchingArrowType();
                if (arrow == null) return "no arrow type selected";
                return levelError(arrow.getLabel(), arrow.getLevelRequirement());
            }
            case BOWS:
            case UNSTRUNG_BOWS: {
                FletchingBowType bow = config.fletchingBowType();
                if (bow == null || bow == FletchingBowType.NONE) return "no bow type selected";
                return levelError(bow.getLabel(), bow.getLevelRequirement());
            }
            case ARROW_SHAFTS: {
                FletchingShaftType shaft = config.fletchingShaftType();
                if (shaft == null) return "no arrow shaft log type selected";
                return levelError(shaft.getLabel(), shaft.getLevelRequirement());
            }
            case STOCKS: {
                FletchingStockType stock = config.fletchingStockType();
                if (stock == null) return "no stock type selected";
                return levelError(stock.getLabel(), stock.getLevelRequirement());
            }
            case SHIELDS: {
                FletchingShieldType shield = config.fletchingShieldType();
                if (shield == null) return "no shield type selected";
                return levelError(shield.getLabel(), shield.getLevelRequirement());
            }
            default:
                return null;
        }
    }

    /** False while the Fletching level still reads 0 (skill data loading / timed-out read). */
    private boolean fletchingLevelLoaded() {
        return Rs2Player.getRealSkillLevel(Skill.FLETCHING) > 0;
    }

    private String levelError(String label, int requiredLevel) {
        if (!fletchingLevelLoaded() || requiredLevel <= 0) return null;
        return Rs2Player.getRealSkillLevel(Skill.FLETCHING) >= requiredLevel
                ? null : "need level " + requiredLevel + " Fletching for " + label;
    }

    // ── Bank check ──────────────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn() || batchInFlight) return false;
        switch (currentPhase) {
            case DARTS: return needsBankDarts(config);
            case BOLTS: return needsBankBolts(config);
            case BOLT_TIPS: return needsBankBoltTips(config);
            case ARROWS: return needsBankArrows(config);
            case BOWS: return needsBankBows(config);
            case UNSTRUNG_BOWS:
            case ARROW_SHAFTS:
            case STOCKS:
            case SHIELDS:
                return needsBankLogs(config);
            default: return false;
        }
    }

    private boolean needsBankDarts(AScriptConfig config) {
        FletchingDartType dart = config.fletchingDartType();
        return dart == null
                || !AScriptFletching.hasUnnoted(dart.getMaterialId())
                || !AScriptFletching.hasUnnoted(ItemID.FEATHER);
    }

    private boolean needsBankBolts(AScriptConfig config) {
        FletchingBoltType bolt = config.fletchingBoltType();
        return bolt == null
                || !AScriptFletching.hasUnnoted(bolt.getUnfinishedId())
                || !AScriptFletching.hasUnnoted(ItemID.FEATHER);
    }

    private boolean needsBankBoltTips(AScriptConfig config) {
        FletchingBoltTipType tip = config.fletchingBoltTipType();
        return tip == null
                || AScriptFletching.inventoryQuantity(tip.getBoltId()) < FletchingBoltTipType.MATERIALS_PER_ACTION
                || AScriptFletching.inventoryQuantity(tip.getTipId()) < FletchingBoltTipType.MATERIALS_PER_ACTION;
    }

    private boolean needsBankArrows(AScriptConfig config) {
        FletchingArrowType arrow = config.fletchingArrowType();
        return arrow == null
                || !AScriptFletching.hasUnnoted(FletchingArrowType.HEADLESS_ARROW_ID)
                || !AScriptFletching.hasUnnoted(arrow.getTipId());
    }

    private boolean needsBankBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        return bow == null || bow == FletchingBowType.NONE
                || !AScriptFletching.hasUnnoted(bow.getUnstrungId())
                || !AScriptFletching.hasUnnoted(ItemID.BOW_STRING);
    }

    private boolean needsBankLogs(AScriptConfig config) {
        LogFletchProduct product = logProduct(config);
        if (product == null || !product.isConfigured()) return false;
        return !AScriptFletching.hasKnife()
                || AScriptFletching.inventoryQuantity(product.logId()) < product.logsPerAction();
    }

    // ── Bank-level check (inventory + bank combined) ─────────────

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn() || batchInFlight) return false;
        switch (currentPhase) {
            case DARTS: return bankMissingDarts(config);
            case BOLTS: return bankMissingBolts(config);
            case BOLT_TIPS: return bankMissingBoltTips(config);
            case ARROWS: return bankMissingArrows(config);
            case BOWS: return bankMissingBows(config);
            case UNSTRUNG_BOWS:
            case ARROW_SHAFTS:
            case STOCKS:
            case SHIELDS:
                return bankMissingLogs(config);
            default: return false;
        }
    }

    private boolean bankMissingDarts(AScriptConfig config) {
        FletchingDartType dart = config.fletchingDartType();
        if (dart == null) return true;
        return !AScriptFletching.combinedAtLeast(dart.getMaterialId(), 1)
                || !AScriptFletching.combinedAtLeast(ItemID.FEATHER, 1);
    }

    private boolean bankMissingBolts(AScriptConfig config) {
        FletchingBoltType bolt = config.fletchingBoltType();
        if (bolt == null) return true;
        return !AScriptFletching.combinedAtLeast(bolt.getUnfinishedId(), 1)
                || !AScriptFletching.combinedAtLeast(ItemID.FEATHER, 1);
    }

    private boolean bankMissingBoltTips(AScriptConfig config) {
        FletchingBoltTipType tip = config.fletchingBoltTipType();
        if (tip == null) return true;
        int amount = FletchingBoltTipType.MATERIALS_PER_ACTION;
        return !AScriptFletching.combinedAtLeast(tip.getBoltId(), amount)
                || !AScriptFletching.combinedAtLeast(tip.getTipId(), amount);
    }

    private boolean bankMissingArrows(AScriptConfig config) {
        FletchingArrowType arrow = config.fletchingArrowType();
        if (arrow == null) return true;
        return !AScriptFletching.combinedAtLeast(FletchingArrowType.HEADLESS_ARROW_ID, 1)
                || !AScriptFletching.combinedAtLeast(arrow.getTipId(), 1);
    }

    private boolean bankMissingBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        if (bow == null || bow == FletchingBowType.NONE) return true;
        return !AScriptFletching.combinedAtLeast(bow.getUnstrungId(), 1)
                || !AScriptFletching.combinedAtLeast(ItemID.BOW_STRING, 1);
    }

    private boolean bankMissingLogs(AScriptConfig config) {
        LogFletchProduct product = logProduct(config);
        if (product == null || !product.isConfigured()) return true;
        if (!AScriptFletching.hasKnife() && !AScriptFletching.bankHasKnife()) return true;
        return !AScriptFletching.combinedAtLeast(product.logId(), product.logsPerAction());
    }

    // ── Missing-materials description ────────────────────────────

    @Override
    public String describeMissing(AScriptConfig config) {
        if (currentPhase == Phase.NONE) return "";
        List<String> missing = new ArrayList<>();
        switch (currentPhase) {
            case DARTS: {
                FletchingDartType dart = config.fletchingDartType();
                if (dart != null) {
                    addMissing(missing, dart.getMaterialId(), 1, dart.getMaterialName());
                }
                addMissing(missing, ItemID.FEATHER, 1, "Feather");
                break;
            }
            case BOLTS: {
                FletchingBoltType bolt = config.fletchingBoltType();
                if (bolt != null) {
                    addMissing(missing, bolt.getUnfinishedId(), 1, bolt.getUnfinishedName());
                }
                addMissing(missing, ItemID.FEATHER, 1, "Feather");
                break;
            }
            case BOLT_TIPS: {
                FletchingBoltTipType tip = config.fletchingBoltTipType();
                if (tip != null) {
                    addMissing(missing, tip.getBoltId(), FletchingBoltTipType.MATERIALS_PER_ACTION, tip.getBoltName());
                    addMissing(missing, tip.getTipId(), FletchingBoltTipType.MATERIALS_PER_ACTION, tip.getTipName());
                }
                break;
            }
            case ARROWS: {
                FletchingArrowType arrow = config.fletchingArrowType();
                addMissing(missing, FletchingArrowType.HEADLESS_ARROW_ID, 1, "Headless arrow");
                if (arrow != null) {
                    addMissing(missing, arrow.getTipId(), 1, arrow.getTipName());
                }
                break;
            }
            case BOWS: {
                FletchingBowType bow = config.fletchingBowType();
                if (bow != null && bow != FletchingBowType.NONE) {
                    addMissing(missing, bow.getUnstrungId(), 1, bow.getUnstrungName());
                }
                addMissing(missing, ItemID.BOW_STRING, 1, "Bow string");
                break;
            }
            case UNSTRUNG_BOWS:
            case ARROW_SHAFTS:
            case STOCKS:
            case SHIELDS: {
                LogFletchProduct product = logProduct(config);
                if (product != null && product.isConfigured()) {
                    if (!AScriptFletching.hasKnife() && !AScriptFletching.bankHasKnife()) {
                        missing.add("Knife");
                    }
                    addMissing(missing, product.logId(), product.logsPerAction(),
                            AScriptFletching.itemName(product.logId(), "log"));
                }
                break;
            }
            default:
                break;
        }
        return String.join(", ", missing);
    }

    private void addMissing(List<String> missing, int itemId, int amount, String fallback) {
        if (!AScriptFletching.combinedAtLeast(itemId, amount)) {
            missing.add(AScriptFletching.itemName(itemId, fallback) + " x" + amount);
        }
    }

    // ── Banking actions ─────────────────────────────────────────

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING";

        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) return false;
        if (!Rs2Bank.isOpen()) return false;

        switch (currentPhase) {
            case DARTS: return bankDarts(config);
            case BOLTS: return bankBolts(config);
            case BOLT_TIPS: return bankBoltTips(config);
            case ARROWS: return bankArrows(config);
            case BOWS: return bankBows(config);
            case UNSTRUNG_BOWS:
            case ARROW_SHAFTS:
            case STOCKS:
            case SHIELDS:
                return bankLogs(config);
            default:
                return false;
        }
    }

    private boolean bankDarts(AScriptConfig config) {
        FletchingDartType dart = config.fletchingDartType();
        if (dart == null) return false;
        if (!AScriptBank.depositAndWaitEmpty()) return false;
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(dart.getMaterialId()))) {
            log.warn("[Fletching] Could not withdraw {}", dart.getMaterialName());
            return false;
        }
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(ItemID.FEATHER))) {
            log.warn("[Fletching] Could not withdraw feathers");
            return false;
        }
        return true;
    }

    private boolean bankBolts(AScriptConfig config) {
        FletchingBoltType bolt = config.fletchingBoltType();
        if (bolt == null) return false;
        if (!AScriptBank.depositAndWaitEmpty()) return false;
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(bolt.getUnfinishedId()))) {
            log.warn("[Fletching] Could not withdraw {}", bolt.getUnfinishedName());
            return false;
        }
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(ItemID.FEATHER))) {
            log.warn("[Fletching] Could not withdraw feathers");
            return false;
        }
        return true;
    }

    private boolean bankBoltTips(AScriptConfig config) {
        FletchingBoltTipType tip = config.fletchingBoltTipType();
        if (tip == null) return false;
        if (!AScriptBank.depositAndWaitEmpty()) return false;
        if (!AScriptBank.ensureBankOpen()) return false;

        int amount = FletchingBoltTipType.MATERIALS_PER_ACTION;
        int bolts = AScriptBank.bankQuantity(String.valueOf(tip.getBoltId()));
        int tips = AScriptBank.bankQuantity(String.valueOf(tip.getTipId()));
        if (bolts < amount || tips < amount) {
            log.warn("[Fletching] No {} + {} batch: {} bolts, {} tips (need {} each)",
                    tip.getBoltName(), tip.getTipName(), bolts, tips, amount);
            Microbot.status = "No " + tip.getBoltName() + " / " + tip.getTipName() + " batch in bank";
            return false;
        }

        if (!AScriptBank.withdrawVerified(String.valueOf(tip.getBoltId()))) return false;
        if (!AScriptBank.ensureBankOpen()) return false;
        return AScriptBank.withdrawVerified(String.valueOf(tip.getTipId()));
    }

    private boolean bankArrows(AScriptConfig config) {
        FletchingArrowType arrow = config.fletchingArrowType();
        if (arrow == null) return false;
        if (!AScriptBank.depositAndWaitEmpty()) return false;
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(FletchingArrowType.HEADLESS_ARROW_ID))) {
            log.warn("[Fletching] Could not withdraw headless arrows");
            return false;
        }
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(arrow.getTipId()))) {
            log.warn("[Fletching] Could not withdraw {}", arrow.getTipName());
            return false;
        }
        return true;
    }

    private boolean bankBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        if (bow == null || bow == FletchingBowType.NONE) return false;
        if (!AScriptBank.depositAndWaitEmpty()) return false;

        // Bow string is not stackable (14 strings occupy 14 slots), so both sides are slot-bound.
        int freeSlots = AScriptBank.freeSlots();
        int unstrung = AScriptBank.bankQuantity(String.valueOf(bow.getUnstrungId()));
        int strings = AScriptBank.bankQuantity(String.valueOf(ItemID.BOW_STRING));
        int batch = Math.min(freeSlots / 2, Math.min(unstrung, strings));
        if (batch < 1) {
            log.warn("[Fletching] No stringing batch for {} — {} free slot(s), {} unstrung, {} string",
                    bow.getUnstrungName(), freeSlots, unstrung, strings);
            Microbot.status = "No " + bow.getUnstrungName() + " / bow string pair in bank";
            return false;
        }

        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(bow.getUnstrungId()), batch)) {
            log.warn("[Fletching] Could not withdraw {} x{} — retrying next cycle", bow.getUnstrungName(), batch);
            return false;
        }
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.withdrawVerified(String.valueOf(ItemID.BOW_STRING), batch)) {
            log.warn("[Fletching] Could not withdraw bow string x{} — retrying next cycle", batch);
            return false;
        }
        return true;
    }

    private boolean bankLogs(AScriptConfig config) {
        LogFletchProduct product = logProduct(config);
        if (product == null || !product.isConfigured()) return false;

        // The knife is a tool: hold it as a locked slot so the blanket deposit keeps it.
        if (!AScriptBank.ensureToolLocked("knife")) return false;
        if (!AScriptBank.ensureBankOpen()) return false;
        if (!AScriptBank.depositAndWaitEmpty()) return false;
        if (!AScriptBank.ensureBankOpen()) return false;

        int perAction = product.logsPerAction();
        int freeSlots = AScriptBank.freeSlots();
        int available = AScriptBank.bankQuantity(String.valueOf(product.logId()));
        int batch = Math.min(freeSlots / perAction, available / perAction) * perAction;
        if (batch < perAction) {
            log.warn("[Fletching] No {} batch for {} — {} free slot(s), {} in bank",
                    product.label(), AScriptFletching.itemName(product.logId(), "logs"), freeSlots, available);
            Microbot.status = "No " + AScriptFletching.itemName(product.logId(), "logs") + " in bank";
            return false;
        }

        if (!AScriptBank.withdrawVerified(String.valueOf(product.logId()), batch)) {
            log.warn("[Fletching] Could not withdraw {} x{} — retrying next cycle",
                    AScriptFletching.itemName(product.logId(), "logs"), batch);
            return false;
        }
        return true;
    }

    // ── Crafting actions ────────────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        if (batchInFlight) {
            continueBatch(config);
            return;
        }

        boolean success;
        switch (currentPhase) {
            case DARTS: success = craftDarts(config); break;
            case BOLTS: success = craftBolts(config); break;
            case BOLT_TIPS: success = craftBoltTips(config); break;
            case ARROWS: success = craftArrows(config); break;
            case BOWS: success = craftBows(config); break;
            case UNSTRUNG_BOWS:
            case ARROW_SHAFTS:
            case STOCKS:
            case SHIELDS:
                success = craftLogs(config);
                break;
            default:
                return;
        }

        if (success) {
            consecutiveCraftFailures = 0;
        } else {
            registerFailure("could not start " + currentPhase + " batch");
        }
    }

    private boolean craftDarts(AScriptConfig config) {
        FletchingDartType dart = config.fletchingDartType();
        if (dart == null) return false;
        Microbot.status = "MAKING " + dart.getLabel().toUpperCase();
        return startRecipe(dart.getMaterialId(), ItemID.FEATHER,
                dart.getMaterialId(), 1, dart.getProductId(), null);
    }

    private boolean craftBolts(AScriptConfig config) {
        FletchingBoltType bolt = config.fletchingBoltType();
        if (bolt == null) return false;
        Microbot.status = "MAKING " + bolt.getLabel().toUpperCase();
        return startRecipe(bolt.getUnfinishedId(), ItemID.FEATHER,
                bolt.getUnfinishedId(), 1, bolt.getFinishedId(), null);
    }

    private boolean craftBoltTips(AScriptConfig config) {
        FletchingBoltTipType tip = config.fletchingBoltTipType();
        if (tip == null) return false;
        Microbot.status = "MAKING " + tip.getLabel().toUpperCase();
        return startRecipe(tip.getBoltId(), tip.getTipId(),
                tip.getBoltId(), FletchingBoltTipType.MATERIALS_PER_ACTION, tip.getProductId(), null);
    }

    private boolean craftArrows(AScriptConfig config) {
        FletchingArrowType arrow = config.fletchingArrowType();
        if (arrow == null) return false;
        Microbot.status = "MAKING " + arrow.getLabel().toUpperCase();
        return startRecipe(FletchingArrowType.HEADLESS_ARROW_ID, arrow.getTipId(),
                FletchingArrowType.HEADLESS_ARROW_ID, 1, arrow.getFinishedId(), null);
    }

    private boolean craftBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        if (bow == null || bow == FletchingBowType.NONE) return false;
        Microbot.status = "STRINGING " + bow.getLabel().toUpperCase();
        // The production row is identified by its "String" action; the tracked material is the
        // unstrung bow (one per action).
        return startRecipe(ItemID.BOW_STRING, bow.getUnstrungId(),
                bow.getUnstrungId(), 1, 0, "String");
    }

    private boolean craftLogs(AScriptConfig config) {
        LogFletchProduct product = logProduct(config);
        if (product == null || !product.isConfigured()) return false;
        int knifeId = AScriptFletching.heldKnifeId();
        if (knifeId < 0) return false;
        Microbot.status = "MAKING " + product.label().toUpperCase();
        return startRecipe(knifeId, product.logId(),
                product.logId(), product.logsPerAction(), product.productId(), null);
    }

    /**
     * One item-on-item recipe + production-interface click.
     *
     * @param selectedId         item clicked first (the "use" item)
     * @param targetId           item clicked second (the target)
     * @param trackedMaterialId  material whose count tracks the batch
     * @param materialPerAction  units of {@code trackedMaterialId} consumed per action
     * @param productId          product id for the production row (0 when {@code productionAction} is used)
     * @param productionAction   action text for the production row (bows use "String")
     */
    private boolean startRecipe(int selectedId, int targetId, int trackedMaterialId,
                                int materialPerAction, int productId, String productionAction) {
        int materialBefore = AScriptFletching.inventoryQuantity(trackedMaterialId);
        if (materialBefore < materialPerAction) {
            log.warn("[Fletching] Not enough material {} for a batch: {} < {}",
                    trackedMaterialId, materialBefore, materialPerAction);
            return false;
        }

        // A previous click can leave the production interface open (the first action may not have
        // been observed in time). Re-select the item recipe then; clicking the product row again is
        // the retry, not using the items behind an open interface.
        if (Rs2Widget.isProductionWidgetOpen()) {
            Rs2Widget.enableQuantityOption("All");
            if (!Rs2Widget.isProductionWidgetOpen()) {
                return beginBatch(trackedMaterialId, materialPerAction, materialBefore, productId, productionAction);
            }
            return clickProductionAndBegin(trackedMaterialId, materialPerAction, materialBefore,
                    productId, productionAction);
        }

        Rs2ItemModel selected = AScriptPick.pick(selectedId);
        Rs2ItemModel target = AScriptPick.pick(targetId);
        if (selected == null || target == null) {
            log.warn("[Fletching] Missing recipe item: selected={} target={}", selectedId, targetId);
            return false;
        }

        if (!AScriptClick.useItemOnItem(selected, target)) {
            log.warn("[Fletching] Could not use item {} on {}", selectedId, targetId);
            return false;
        }

        if (!sleepUntil(Rs2Widget::isProductionWidgetOpen, 5_000)) {
            log.warn("[Fletching] Production interface did not open for {} + {}", selectedId, targetId);
            return false;
        }

        materialBefore = AScriptFletching.inventoryQuantity(trackedMaterialId);
        if (materialBefore < materialPerAction) {
            log.warn("[Fletching] Not enough material {} for a batch: {} < {}",
                    trackedMaterialId, materialBefore, materialPerAction);
            return false;
        }

        // "All" is the quantity option; some single-product prompts start production immediately
        // when it is clicked, others need the product row afterwards.
        Rs2Widget.enableQuantityOption("All");
        if (!Rs2Widget.isProductionWidgetOpen()) {
            return beginBatch(trackedMaterialId, materialPerAction, materialBefore, productId, productionAction);
        }
        return clickProductionAndBegin(trackedMaterialId, materialPerAction, materialBefore,
                productId, productionAction);
    }

    private boolean clickProductionAndBegin(int trackedMaterialId, int materialPerAction,
                                            int materialBefore, int productId, String productionAction) {
        boolean clicked = productionAction != null
                ? FletchingProductionHelper.clickProductionAction(productionAction)
                : FletchingProductionHelper.clickProductionProduct(productId);
        if (!clicked) {
            log.warn("[Fletching] Could not click production row ({}, product id {})",
                    productionAction != null ? productionAction : "by id", productId);
            return false;
        }

        if (!beginBatch(trackedMaterialId, materialPerAction, materialBefore, productId, productionAction)) {
            return false;
        }

        // The click returning true only means the press was dispatched. Confirm the batch actually
        // started before the next tick treats the material count as an in-flight batch. An interface
        // that is still open after the window is not a failure: the batch may be running slowly, and
        // continueBatch() will re-click the row after its stall window.
        boolean started = sleepUntil(
                () -> AScriptFletching.inventoryQuantity(trackedMaterialId) < materialBefore
                        || !Rs2Widget.isProductionWidgetOpen(),
                BATCH_START_CONFIRM_MS);
        if (!started && !Rs2Widget.isProductionWidgetOpen()) {
            log.warn("[Fletching] Production did not start for {} (still {} in inventory)",
                    trackedMaterialId, AScriptFletching.inventoryQuantity(trackedMaterialId));
            clearBatch();
            return false;
        }
        return true;
    }

    private boolean beginBatch(int materialId, int perAction, int countBefore,
                               int productId, String productionAction) {
        if (countBefore < perAction) return false;
        batchInFlight = true;
        batchMaterialId = materialId;
        batchMaterialPerAction = perAction;
        batchMaterialCount = countBefore;
        batchProductId = productId;
        batchProductionAction = productionAction;
        batchLastProgressAt = System.currentTimeMillis();
        return true;
    }

    private void continueBatch(AScriptConfig config) {
        int now = AScriptFletching.inventoryQuantity(batchMaterialId);
        long nowMs = System.currentTimeMillis();

        if (now < batchMaterialCount) {
            batchMaterialCount = now;
            batchLastProgressAt = nowMs;
            consecutiveCraftFailures = 0;
            if (now < batchMaterialPerAction) {
                finishBatch(config);
            } else {
                Microbot.status = "FLETCHING — " + now + " " + materialLabel() + " left";
                AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(400, 900, weatherMultiplier));
            }
            return;
        }

        if (now < batchMaterialPerAction) {
            finishBatch(config);
            return;
        }

        if (nowMs - batchLastProgressAt > BATCH_STALL_MS) {
            if (Rs2Widget.isProductionWidgetOpen()) {
                boolean reclicked = batchProductionAction != null
                        ? FletchingProductionHelper.clickProductionAction(batchProductionAction)
                        : FletchingProductionHelper.clickProductionProduct(batchProductId);
                if (reclicked) {
                    batchLastProgressAt = nowMs;
                    return;
                }
            }
            clearBatch();
            registerFailure("batch stalled with " + now + " " + materialLabel() + " left");
            return;
        }

        Microbot.status = "FLETCHING — " + now + " " + materialLabel() + " left";
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(400, 900, weatherMultiplier));
    }

    private void finishBatch(AScriptConfig config) {
        clearBatch();
        Microbot.status = "IDLE";
        consecutiveCraftFailures = 0;

        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(300, 700, weatherMultiplier));
        maybeAfk(config);
    }

    private void maybeAfk(AScriptConfig config) {
        if (config == null || !config.fletchingAfk()
                || System.currentTimeMillis() - lastAfkTime <= 5_000) {
            return;
        }
        // 10% chance of long AFK (6-120s), 90% short AFK (3-20s)
        int afkMs = Rs2Random.diceFractional(0.10)
                ? Rs2Random.logNormalBounded(6_000, 120_000, weatherMultiplier)
                : Rs2Random.logNormalBounded(3_000, 20_000, weatherMultiplier);
        Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
        AScriptSleep.sleepInterruptibly(afkMs);
        lastAfkTime = System.currentTimeMillis();
    }

    private void registerFailure(String reason) {
        consecutiveCraftFailures++;
        log.warn("[Fletching] Attempt failed ({}/{}): {}",
                consecutiveCraftFailures, MAX_CONSECUTIVE_CRAFT_FAILURES, reason);
        if (consecutiveCraftFailures >= MAX_CONSECUTIVE_CRAFT_FAILURES) {
            exitRequested = true;
            clearBatch();
            Microbot.status = "STOPPED — " + reason;
            AScriptNotify.notify("aScript Stopped", "Fletching: " + consecutiveCraftFailures
                    + " consecutive craft failures — " + reason);
            Microbot.getConfigManager().setConfiguration(
                    AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            return;
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1_100, weatherMultiplier));
    }

    private void clearBatch() {
        batchInFlight = false;
        batchMaterialId = 0;
        batchMaterialPerAction = 0;
        batchMaterialCount = 0;
        batchProductId = 0;
        batchProductionAction = null;
        batchLastProgressAt = 0;
    }

    private String materialLabel() {
        return AScriptFletching.itemName(batchMaterialId, "materials");
    }

    private LogFletchProduct logProduct(AScriptConfig config) {
        switch (currentPhase) {
            case UNSTRUNG_BOWS: return config.fletchingBowType();
            case ARROW_SHAFTS: return config.fletchingShaftType();
            case STOCKS: return config.fletchingStockType();
            case SHIELDS: return config.fletchingShieldType();
            default: return null;
        }
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }
}
