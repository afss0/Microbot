package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.skills.fletching.Rs2Fletching;
import net.runelite.client.plugins.microbot.util.skills.fletching.data.FletchingArrow;
import net.runelite.client.plugins.microbot.util.skills.fletching.data.FletchingBolt;
import net.runelite.client.plugins.microbot.util.skills.fletching.data.FletchingDart;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;

/**
 * Fletching sub-script — stateless helper called by AScript's FLETCHING / BANKING states.
 * <p>
 * Adapted from upstream AutoBankStander's FletchingProcessor.
 */
@Slf4j
public class FletchingScript {

    private long lastAfkTime;
    /** Set once per doCraft() call; used by all craft methods for weather-modulated timing. */
    private double weatherMultiplier = 1.0;
    /** Prevent repeated exit attempts / Discord spam. */
    private boolean exitRequested = false;
    /** Track consecutive craft failures to trigger exit after persistent failures. */
    private int consecutiveCraftFailures = 0;
    private static final int MAX_CONSECUTIVE_CRAFT_FAILURES = 3;

    public enum Phase {
        NONE, DARTS, BOLTS, ARROWS, BOWS
    }

    /** Reset exit flag when script is re-enabled. Call from AScript on state enter. */
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveCraftFailures = 0;
    }

    // ── Phase resolution ────────────────────────────────────────

    public Phase resolvePhase(AScriptConfig config) {
        if (config == null
                || config.scriptSelection() != ScriptType.FLETCHING
                || config.fletchingActivity() == null) return Phase.NONE;
        switch (config.fletchingActivity()) {
            case DARTS:  return Phase.DARTS;
            case BOLTS:  return Phase.BOLTS;
            case ARROWS: return Phase.ARROWS;
            case BOWS:   return Phase.BOWS;
            default:     return Phase.NONE;
        }
    }

    // ── Selection validation ────────────────────────────────────

    /**
     * Returns an error description when the configured activity has an invalid
     * sub-selection (e.g. DARTS without a dart type), or null when valid.
     */
    public String validateSelection(AScriptConfig config, Phase phase) {
        // Fletching selecionado mas nenhuma atividade escolhida -> erro visível
        if (phase == Phase.NONE && config.scriptSelection() == ScriptType.FLETCHING)
            return "no fletching activity selected";
        // FletchingDart, FletchingBolt, FletchingArrow have no NONE — any value is valid
        switch (phase) {
            case DARTS:
            case BOLTS:
            case ARROWS:
                return null;
            case BOWS:
                if (config.fletchingBowType() == null || config.fletchingBowType() == FletchingBowType.NONE)
                    return "no bow type selected";
                return null;
            default:
                return null;
        }
    }

    // ── Bank check ──────────────────────────────────────────────

    public boolean needsBank(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        switch (phase) {
            case DARTS:  return needsBankDarts(config);
            case BOLTS:  return needsBankBolts(config);
            case ARROWS: return needsBankArrows(config);
            case BOWS:   return needsBankBows(config);
            default:     return false;
        }
    }

    private boolean needsBankDarts(AScriptConfig config) {
        FletchingDart dart = config.fletchingDartType();
        return !Rs2Inventory.hasItem(dart.getDartTipName()) || !Rs2Inventory.hasItem("feather");
    }

    private boolean needsBankBolts(AScriptConfig config) {
        FletchingBolt bolt = config.fletchingBoltType();
        return !Rs2Inventory.hasItem(bolt.getUnfinishedBoltName()) || !Rs2Inventory.hasItem("feather");
    }

    private boolean needsBankArrows(AScriptConfig config) {
        FletchingArrow arrow = config.fletchingArrowType();
        return !Rs2Inventory.hasItem(arrow.getHeadlessArrowName()) || !Rs2Inventory.hasItem(arrow.getArrowTipName());
    }

    private boolean needsBankBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        if (bow == FletchingBowType.NONE) return false;
        return !Rs2Inventory.hasItem(bow.getUnstrungName()) || !Rs2Inventory.hasItem("bow string");
    }

    // ── Bank-level check (both bank AND inventory missing) ──────

    /**
     * Returns true when materials are missing from BOTH the bank
     * and inventory — i.e. there is nothing to work with.
     */
    public boolean isBankMissingMaterials(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config, phase)) return false;
        switch (phase) {
            case DARTS:  return bankMissingDarts(config);
            case BOLTS:  return bankMissingBolts(config);
            case ARROWS: return bankMissingArrows(config);
            case BOWS:   return bankMissingBows(config);
            default:     return false;
        }
    }

    private boolean bankMissingDarts(AScriptConfig config) {
        FletchingDart dart = config.fletchingDartType();
        if (!Rs2Inventory.hasItem(dart.getDartTipName()) && !Rs2Bank.hasItem(dart.getDartTipName())) return true;
        return !Rs2Inventory.hasItem("feather") && !Rs2Bank.hasItem("feather");
    }

    private boolean bankMissingBolts(AScriptConfig config) {
        FletchingBolt bolt = config.fletchingBoltType();
        if (!Rs2Inventory.hasItem(bolt.getUnfinishedBoltName()) && !Rs2Bank.hasItem(bolt.getUnfinishedBoltName())) return true;
        return !Rs2Inventory.hasItem("feather") && !Rs2Bank.hasItem("feather");
    }

    private boolean bankMissingArrows(AScriptConfig config) {
        FletchingArrow arrow = config.fletchingArrowType();
        if (!Rs2Inventory.hasItem(arrow.getHeadlessArrowName()) && !Rs2Bank.hasItem(arrow.getHeadlessArrowName())) return true;
        return !Rs2Inventory.hasItem(arrow.getArrowTipName()) && !Rs2Bank.hasItem(arrow.getArrowTipName());
    }

    private boolean bankMissingBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        if (bow == FletchingBowType.NONE) return false;
        if (!Rs2Inventory.hasItem(bow.getUnstrungName()) && !Rs2Bank.hasItem(bow.getUnstrungName())) return true;
        return !Rs2Inventory.hasItem("bow string") && !Rs2Bank.hasItem("bow string");
    }

    // ── Missing-materials description ────────────────────────────

    public String describeMissing(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE) return "";
        switch (phase) {
            case DARTS:  return describeMissingDarts(config);
            case BOLTS:  return describeMissingBolts(config);
            case ARROWS: return describeMissingArrows(config);
            case BOWS:   return describeMissingBows(config);
            default:     return "";
        }
    }

    private String describeMissingDarts(AScriptConfig config) {
        FletchingDart dart = config.fletchingDartType();
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(dart.getDartTipName())) sb.append(dart.getDartTipName()).append(", ");
        if (!Rs2Inventory.hasItem("feather")) sb.append("feather, ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingBolts(AScriptConfig config) {
        FletchingBolt bolt = config.fletchingBoltType();
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(bolt.getUnfinishedBoltName())) sb.append(bolt.getUnfinishedBoltName()).append(", ");
        if (!Rs2Inventory.hasItem("feather")) sb.append("feather, ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingArrows(AScriptConfig config) {
        FletchingArrow arrow = config.fletchingArrowType();
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(arrow.getHeadlessArrowName())) sb.append(arrow.getHeadlessArrowName()).append(", ");
        if (!Rs2Inventory.hasItem(arrow.getArrowTipName())) sb.append(arrow.getArrowTipName()).append(", ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    private String describeMissingBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        if (bow == FletchingBowType.NONE) return "";
        StringBuilder sb = new StringBuilder();
        if (!Rs2Inventory.hasItem(bow.getUnstrungName())) sb.append(bow.getUnstrungName()).append(", ");
        if (!Rs2Inventory.hasItem("bow string")) sb.append("bow string, ");
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    // ── Banking actions ─────────────────────────────────────────

    public boolean doBank(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return false;

        Microbot.status = "BANKING";

        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            if (!Rs2Bank.isOpen()) return false;
        }

        boolean success = false;
        switch (phase) {
            case DARTS:  success = bankDarts(config); break;
            case BOLTS:  success = bankBolts(config); break;
            case ARROWS: success = bankArrows(config); break;
            case BOWS:   success = bankBows(config); break;
            default: break;
        }

        return success;
    }

    /**
     * Deposit all inventory items via the toolbar button (not grid).
     * Fletching has no tools to protect — blanket deposit is safe.
     * Returns false if deposit timed out.
     */
    private boolean depositAll() {
        return AScriptBank.depositAndWaitEmpty();
    }

    private boolean bankDarts(AScriptConfig config) {
        FletchingDart dart = config.fletchingDartType();

        // Deposit via toolbar button (safe on grid-silent machines)
        if (!depositAll()) return false;
        sleepUntil(() -> Rs2Inventory.isEmpty(), 3000);

        // Ensure ONE knife locked in inventory (shared tool across fletching activities)
        if (!AScriptBank.ensureToolLocked("knife")) {
            AScriptNotify.notify("Banking Failed", "No knife in bank");
            return false;
        }

        // Withdraw dart tips — verify withdrawal
        if (Rs2Bank.hasItem(dart.getDartTipName())) {
            Rs2Bank.withdrawAll(true, dart.getDartTipName());
            if (!sleepUntil(() -> Rs2Inventory.hasItem(dart.getDartTipName()), 3000)) {
                log.warn("[FletchingScript] Failed to withdraw {}", dart.getDartTipName());
                return false;
            }
        } else {
            AScriptNotify.notify("Banking Failed", "No " + dart.getDartTipName() + " in bank");
            return false;
        }

        // Withdraw feathers — verify withdrawal
        if (Rs2Bank.hasItem("feather")) {
            Rs2Bank.withdrawAll(true, "feather");
            if (!sleepUntil(() -> Rs2Inventory.hasItem("feather"), 3000)) {
                log.warn("[FletchingScript] Failed to withdraw feather");
                return false;
            }
        } else {
            AScriptNotify.notify("Banking Failed", "No feathers in bank");
            return false;
        }

        return true;
    }

    private boolean bankBolts(AScriptConfig config) {
        FletchingBolt bolt = config.fletchingBoltType();

        // Deposit via toolbar button
        if (!depositAll()) return false;
        sleepUntil(() -> Rs2Inventory.isEmpty(), 3000);

        // Ensure ONE knife locked in inventory (shared tool across fletching activities)
        if (!AScriptBank.ensureToolLocked("knife")) {
            AScriptNotify.notify("Banking Failed", "No knife in bank");
            return false;
        }

        // Withdraw unfinished bolts — verify withdrawal
        if (Rs2Bank.hasItem(bolt.getUnfinishedBoltName())) {
            Rs2Bank.withdrawAll(true, bolt.getUnfinishedBoltName());
            if (!sleepUntil(() -> Rs2Inventory.hasItem(bolt.getUnfinishedBoltName()), 3000)) {
                log.warn("[FletchingScript] Failed to withdraw {}", bolt.getUnfinishedBoltName());
                return false;
            }
        } else {
            AScriptNotify.notify("Banking Failed", "No " + bolt.getUnfinishedBoltName() + " in bank");
            return false;
        }

        // Withdraw feathers — verify withdrawal
        if (Rs2Bank.hasItem("feather")) {
            Rs2Bank.withdrawAll(true, "feather");
            if (!sleepUntil(() -> Rs2Inventory.hasItem("feather"), 3000)) {
                log.warn("[FletchingScript] Failed to withdraw feather");
                return false;
            }
        } else {
            AScriptNotify.notify("Banking Failed", "No feathers in bank");
            return false;
        }

        return true;
    }

    private boolean bankArrows(AScriptConfig config) {
        FletchingArrow arrow = config.fletchingArrowType();

        // Deposit via toolbar button
        if (!depositAll()) return false;
        sleepUntil(() -> Rs2Inventory.isEmpty(), 3000);

        // Ensure ONE knife locked in inventory (shared tool across fletching activities)
        if (!AScriptBank.ensureToolLocked("knife")) {
            AScriptNotify.notify("Banking Failed", "No knife in bank");
            return false;
        }

        // Withdraw headless arrows — verify withdrawal
        if (Rs2Bank.hasItem(arrow.getHeadlessArrowName())) {
            Rs2Bank.withdrawAll(true, arrow.getHeadlessArrowName());
            if (!sleepUntil(() -> Rs2Inventory.hasItem(arrow.getHeadlessArrowName()), 3000)) {
                log.warn("[FletchingScript] Failed to withdraw {}", arrow.getHeadlessArrowName());
                return false;
            }
        } else {
            AScriptNotify.notify("Banking Failed", "No " + arrow.getHeadlessArrowName() + " in bank");
            return false;
        }

        // Withdraw arrow tips — verify withdrawal
        if (Rs2Bank.hasItem(arrow.getArrowTipName())) {
            Rs2Bank.withdrawAll(true, arrow.getArrowTipName());
            if (!sleepUntil(() -> Rs2Inventory.hasItem(arrow.getArrowTipName()), 3000)) {
                log.warn("[FletchingScript] Failed to withdraw {}", arrow.getArrowTipName());
                return false;
            }
        } else {
            AScriptNotify.notify("Banking Failed", "No " + arrow.getArrowTipName() + " in bank");
            return false;
        }

        return true;
    }

    private boolean bankBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        if (bow == FletchingBowType.NONE) return false;

        // Deposit via toolbar button
        if (!depositAll()) return false;
        sleepUntil(() -> Rs2Inventory.isEmpty(), 3000);

        // Ensure ONE knife locked in inventory (shared tool across fletching activities)
        if (!AScriptBank.ensureToolLocked("knife")) {
            AScriptNotify.notify("Banking Failed", "No knife in bank");
            return false;
        }

        // Withdraw unstrung bow — verify withdrawal
        if (Rs2Bank.hasItem(bow.getUnstrungName())) {
            Rs2Bank.withdrawAll(true, bow.getUnstrungName());
            if (!sleepUntil(() -> Rs2Inventory.hasItem(bow.getUnstrungName()), 3000)) {
                log.warn("[FletchingScript] Failed to withdraw {}", bow.getUnstrungName());
                return false;
            }
        } else {
            AScriptNotify.notify("Banking Failed", "No " + bow.getUnstrungName() + " in bank");
            return false;
        }

        // Withdraw bow string — verify withdrawal
        if (Rs2Bank.hasItem("bow string")) {
            Rs2Bank.withdrawAll(true, "bow string");
            if (!sleepUntil(() -> Rs2Inventory.hasItem("bow string"), 3000)) {
                log.warn("[FletchingScript] Failed to withdraw bow string");
                return false;
            }
        } else {
            AScriptNotify.notify("Banking Failed", "No bow string in bank");
            return false;
        }

        return true;
    }

    // ── Crafting actions ────────────────────────────────────────

    public void doCraft(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return;
        if (exitRequested) return;

        // Ensure fresh weather data (cached for 30 min, safe to call every tick)
        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        boolean success = false;
        switch (phase) {
            case DARTS:  success = craftDarts(config); break;
            case BOLTS:  success = craftBolts(config); break;
            case ARROWS: success = craftArrows(config); break;
            case BOWS:   success = craftBows(config); break;
            default: break;
        }

        // Track consecutive failures — exit after persistent failures
        if (success) {
            consecutiveCraftFailures = 0;
        } else {
            consecutiveCraftFailures++;
            if (consecutiveCraftFailures >= MAX_CONSECUTIVE_CRAFT_FAILURES) {
                exitRequested = true;
                Microbot.status = "STOPPED — persistent craft failures";
                log.warn("[FletchingScript] {} consecutive craft failures, stopping", consecutiveCraftFailures);
                AScriptNotify.notify("aScript Stopped", "Fletching: " + consecutiveCraftFailures
                        + " consecutive craft failures");
                Microbot.getConfigManager().setConfiguration(
                        AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
            }
        }

        // Randomized delay between crafts (not fixed sleep)
        sleep(Rs2Random.logNormalBounded(800, 1600));

        // Random AFK — log-normal distribution, weather-modulated
        if (config.fletchingAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3000, 120000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            sleep(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    private boolean craftDarts(AScriptConfig config) {
        FletchingDart dart = config.fletchingDartType();
        Microbot.status = "MAKING " + dart.getFinishedDartName().toUpperCase();

        boolean success = Rs2Fletching.makeDarts(dart.getDartTipName(), "All");
        if (!success) {
            log.warn("[FletchingScript] makeDarts failed for {}", dart.getDartTipName());
        }

        Microbot.status = "IDLE";
        return success;
    }

    private boolean craftBolts(AScriptConfig config) {
        FletchingBolt bolt = config.fletchingBoltType();
        Microbot.status = "MAKING " + bolt.getFinishedBoltName().toUpperCase();

        boolean success = Rs2Fletching.makeBolts(bolt.getUnfinishedBoltName(), "All");
        if (!success) {
            log.warn("[FletchingScript] makeBolts failed for {}", bolt.getUnfinishedBoltName());
        }

        Microbot.status = "IDLE";
        return success;
    }

    private boolean craftArrows(AScriptConfig config) {
        FletchingArrow arrow = config.fletchingArrowType();
        Microbot.status = "MAKING " + arrow.getFinishedArrowName().toUpperCase();

        boolean success = Rs2Fletching.makeArrows(arrow.getArrowTipName(), "All");
        if (!success) {
            log.warn("[FletchingScript] makeArrows failed for {}", arrow.getArrowTipName());
        }

        Microbot.status = "IDLE";
        return success;
    }

    private boolean craftBows(AScriptConfig config) {
        FletchingBowType bow = config.fletchingBowType();
        Microbot.status = "STRINGING " + bow.getLabel().toUpperCase();

        // stringBows takes the bow name without (u), e.g. "oak shortbow"
        String bowName = bow.getUnstrungName().replace(" (u)", "");
        boolean success = Rs2Fletching.stringBows(bowName);
        if (!success) {
            log.warn("[FletchingScript] stringBows failed for {}", bowName);
        }

        Microbot.status = "IDLE";
        return success;
    }

}
