package net.runelite.client.plugins.microbot.ascript.mining;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.ascript.AModule;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.motherloadmine.Pickaxe;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptClick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptDrop;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.ascript.util.AScriptPick;
import net.runelite.client.plugins.microbot.ascript.util.AScriptSleep;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static net.runelite.api.gameval.AnimationID.*;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Mining sub-script — mines a configured ore type around the activation spot
 * (or travels to a known mine in Progressive mode), with Drop (powermine) or
 * Bank ore handling. Migration of the hub Auto Mining plugin mapped onto the
 * AModule contract: banking collapses into {@link #needsBank}/{@link #doBank}
 * (the orchestrator opens the bank; the module walks only when no bank is in
 * sight), and the mine loop is tick-driven — the mining animation is the
 * in-flight signal and product-count increases are the batch tracker, so
 * pause/resume, rock depletion and restarts are self-healing.
 */
@Slf4j
public class MiningScript implements AModule {

    /** Failed attempts in a row before the module stops and notifies. */
    private static final int MAX_ACTION_FAILURES = 3;
    /** Render-wait steps for the rock click (clickObject's bounded hull poll). */
    private static final int ROCK_RENDER_WAIT_STEPS = 2;
    /** No minable rock in range for this long → stop loudly (rocks respawn fast). */
    private static final long NO_ROCK_STOP_MS = 90_000L;
    /** No position change while travelling for this long → stop loudly (cannonball watchdog). */
    private static final long TRAVEL_STAGNATION_MS = 120_000L;
    /** Pickaxe spec: bounded activation attempts per energy cycle (BVF harpoon-spec shape). */
    private static final int PICKAXE_SPEC_MAX_ATTEMPTS = 3;
    /** [GEM] Shilo Village underground gem mine region (hub constant 11410). */
    private static final int GEM_MINE_UNDERGROUND_REGION = 11410;
    /** [BASALT] Snowflake (the Weiss basalt-noting NPC) — hub coordinate; verify live. */
    private static final WorldPoint SNOWFLAKE_SPOT = new WorldPoint(2872, 3935, 0);

    /**
     * Every mining animation in gameval (all pickaxe tiers and variants: base, _WALL,
     * _NOREACHFORWARD, league/trailblazer/gilded/3A/demonic-pact/gauntlet, plus the
     * HET / ZALCANO / SANG families — harmless if never seen, correct if they are).
     * Completeness check:
     *   grep -o 'HUMAN_MINING_[A-Z0-9_]*\|SANG_MINING_WALL_[A-Z0-9_]*' \
     *     runelite-api/src/main/java/net/runelite/api/gameval/AnimationID.java | sort -u
     */
    private static final Set<Integer> MINING_ANIMATION_IDS = Set.of(
            // 3A
            HUMAN_MINING_3A_PICKAXE, HUMAN_MINING_3A_PICKAXE_NOREACHFORWARD, HUMAN_MINING_3A_PICKAXE_WALL,
            // adamant
            HUMAN_MINING_ADAMANT_PICKAXE, HUMAN_MINING_ADAMANT_PICKAXE_NOREACHFORWARD, HUMAN_MINING_ADAMANT_PICKAXE_WALL,
            // black
            HUMAN_MINING_BLACK_PICKAXE, HUMAN_MINING_BLACK_PICKAXE_NOREACHFORWARD, HUMAN_MINING_BLACK_PICKAXE_WALL,
            // bronze
            HUMAN_MINING_BRONZE_PICKAXE, HUMAN_MINING_BRONZE_PICKAXE_NOREACHFORWARD, HUMAN_MINING_BRONZE_PICKAXE_WALL,
            // crystal
            HUMAN_MINING_CRYSTAL_PICKAXE, HUMAN_MINING_CRYSTAL_PICKAXE_NOREACHFORWARD, HUMAN_MINING_CRYSTAL_PICKAXE_WALL,
            // dragon (+pretty variants)
            HUMAN_MINING_DRAGON_PICKAXE, HUMAN_MINING_DRAGON_PICKAXE_NOREACHFORWARD, HUMAN_MINING_DRAGON_PICKAXE_WALL,
            HUMAN_MINING_DRAGON_PICKAXE_PRETTY, HUMAN_MINING_DRAGON_PICKAXE_PRETTY_NOREACHFORWARD,
            HUMAN_MINING_DRAGON_PICKAXE_PRETTY_WALL,
            // gilded
            HUMAN_MINING_GILDED_PICKAXE, HUMAN_MINING_GILDED_PICKAXE_NOREACHFORWARD, HUMAN_MINING_GILDED_PICKAXE_WALL,
            // infernal
            HUMAN_MINING_INFERNAL_PICKAXE, HUMAN_MINING_INFERNAL_PICKAXE_NOREACHFORWARD, HUMAN_MINING_INFERNAL_PICKAXE_WALL,
            // iron
            HUMAN_MINING_IRON_PICKAXE, HUMAN_MINING_IRON_PICKAXE_NOREACHFORWARD, HUMAN_MINING_IRON_PICKAXE_WALL,
            // mithril
            HUMAN_MINING_MITHRIL_PICKAXE, HUMAN_MINING_MITHRIL_PICKAXE_NOREACHFORWARD, HUMAN_MINING_MITHRIL_PICKAXE_WALL,
            // rune
            HUMAN_MINING_RUNE_PICKAXE, HUMAN_MINING_RUNE_PICKAXE_NOREACHFORWARD, HUMAN_MINING_RUNE_PICKAXE_WALL,
            // steel
            HUMAN_MINING_STEEL_PICKAXE, HUMAN_MINING_STEEL_PICKAXE_NOREACHFORWARD, HUMAN_MINING_STEEL_PICKAXE_WALL,
            // league / trailblazer variants
            HUMAN_MINING_LEAGUE_TRAILBLAZER_PICKAXE, HUMAN_MINING_LEAGUE_TRAILBLAZER_PICKAXE_NOREACHFORWARD,
            HUMAN_MINING_LEAGUE_TRAILBLAZER_PICKAXE_WALL,
            HUMAN_MINING_TRAILBLAZER_PICKAXE, HUMAN_MINING_TRAILBLAZER_PICKAXE_NOREACHFORWARD,
            HUMAN_MINING_TRAILBLAZER_PICKAXE_WALL,
            HUMAN_MINING_TRAILBLAZER_PICKAXE_NO_INFERNAL, HUMAN_MINING_TRAILBLAZER_PICKAXE_NO_INFERNAL_NOREACHFORWARD,
            HUMAN_MINING_TRAILBLAZER_PICKAXE_NO_INFERNAL_WALL,
            HUMAN_MINING_TRAILBLAZER_RELOADED_PICKAXE, HUMAN_MINING_TRAILBLAZER_RELOADED_PICKAXE_NOREACHFORWARD,
            HUMAN_MINING_TRAILBLAZER_RELOADED_PICKAXE_WALL,
            HUMAN_MINING_TRAILBLAZER_RELOADED_PICKAXE_NO_INFERNAL,
            HUMAN_MINING_TRAILBLAZER_RELOADED_PICKAXE_NO_INFERNAL_NOREACHFORWARD,
            HUMAN_MINING_TRAILBLAZER_RELOADED_PICKAXE_NO_INFERNAL_WALL,
            // misc
            HUMAN_MINING_DEMONIC_PACT, HUMAN_MINING_GAUNTLET_PICKAXE_HM,
            // HET family
            HUMAN_MINING_HET_3A_PICKAXE, HUMAN_MINING_HET_ADAMANT_PICKAXE, HUMAN_MINING_HET_BLACK_PICKAXE,
            HUMAN_MINING_HET_BRONZE_PICKAXE, HUMAN_MINING_HET_CRYSTAL_PICKAXE, HUMAN_MINING_HET_DRAGON_PICKAXE,
            HUMAN_MINING_HET_DRAGON_PICKAXE_PRETTY, HUMAN_MINING_HET_GILDED_PICKAXE, HUMAN_MINING_HET_INFERNAL_PICKAXE,
            HUMAN_MINING_HET_IRON_PICKAXE, HUMAN_MINING_HET_LEAGUE_TRAILBLAZER_PICKAXE, HUMAN_MINING_HET_MITHRIL_PICKAXE,
            HUMAN_MINING_HET_RUNE_PICKAXE, HUMAN_MINING_HET_STEEL_PICKAXE, HUMAN_MINING_HET_TRAILBLAZER_PICKAXE,
            HUMAN_MINING_HET_TRAILBLAZER_PICKAXE_NO_INFERNAL, HUMAN_MINING_HET_TRAILBLAZER_RELOADED_PICKAXE,
            HUMAN_MINING_HET_TRAILBLAZER_RELOADED_PICKAXE_NO_INFERNAL, HUMAN_MINING_HET_ZALCANO_PICKAXE,
            // ZALCANO family
            HUMAN_MINING_ZALCANO_3A_PICKAXE, HUMAN_MINING_ZALCANO_ADAMANT_PICKAXE, HUMAN_MINING_ZALCANO_BLACK_PICKAXE,
            HUMAN_MINING_ZALCANO_BRONZE_PICKAXE, HUMAN_MINING_ZALCANO_CRYSTAL_PICKAXE, HUMAN_MINING_ZALCANO_DRAGON_PICKAXE,
            HUMAN_MINING_ZALCANO_DRAGON_PICKAXE_PRETTY, HUMAN_MINING_ZALCANO_GILDED_PICKAXE,
            HUMAN_MINING_ZALCANO_INFERNAL_PICKAXE, HUMAN_MINING_ZALCANO_IRON_PICKAXE,
            HUMAN_MINING_ZALCANO_LEAGUE_TRAILBLAZER_PICKAXE, HUMAN_MINING_ZALCANO_MITHRIL_PICKAXE,
            HUMAN_MINING_ZALCANO_PICKAXE, HUMAN_MINING_ZALCANO_PICKAXE_NOREACHFORWARD,
            HUMAN_MINING_ZALCANO_PICKAXE_WALL, HUMAN_MINING_ZALCANO_RUNE_PICKAXE, HUMAN_MINING_ZALCANO_STEEL_PICKAXE,
            HUMAN_MINING_ZALCANO_TRAILBLAZER_PICKAXE, HUMAN_MINING_ZALCANO_TRAILBLAZER_PICKAXE_NO_INFERNAL,
            HUMAN_MINING_ZALCANO_TRAILBLAZER_RELOADED_PICKAXE,
            HUMAN_MINING_ZALCANO_TRAILBLAZER_RELOADED_PICKAXE_NO_INFERNAL, HUMAN_MINING_ZALCANO_ZALCANO_PICKAXE,
            // SANG family
            SANG_MINING_WALL_3A_PICKAXE, SANG_MINING_WALL_ADAMANT_PICKAXE, SANG_MINING_WALL_BLACK_PICKAXE,
            SANG_MINING_WALL_BRONZE_PICKAXE, SANG_MINING_WALL_CRYSTAL_PICKAXE, SANG_MINING_WALL_DRAGON_PICKAXE,
            SANG_MINING_WALL_DRAGON_PICKAXE_PRETTY, SANG_MINING_WALL_GILDED_PICKAXE, SANG_MINING_WALL_INFERNAL_PICKAXE,
            SANG_MINING_WALL_IRON_PICKAXE, SANG_MINING_WALL_LEAGUE_TRAILBLAZER_PICKAXE, SANG_MINING_WALL_MITHRIL_PICKAXE,
            SANG_MINING_WALL_RUNE_PICKAXE, SANG_MINING_WALL_STEEL_PICKAXE, SANG_MINING_WALL_TRAILBLAZER_PICKAXE,
            SANG_MINING_WALL_TRAILBLAZER_PICKAXE_NO_INFERNAL, SANG_MINING_WALL_TRAILBLAZER_RELOADED_PICKAXE,
            SANG_MINING_WALL_TRAILBLAZER_RELOADED_PICKAXE_NO_INFERNAL, SANG_MINING_WALL_ZALCANO_PICKAXE);

    /**
     * Spec pickaxes (the special attack boosts Mining). Verify names + ornament
     * variants at implementation:
     *   grep -nE "DRAGON_PICKAXE(_OR|_CR)? = |CRYSTAL_PICKAXE = |INFERNAL_PICKAXE = " \
     *     runelite-api/src/main/java/net/runelite/api/gameval/ItemID.java
     * (gameval verified 2026-10-18: DRAGON_PICKAXE=11920, INFERNAL_PICKAXE=13243, CRYSTAL_PICKAXE=23680.)
     */
    private static final Set<Integer> SPEC_PICKAXE_IDS = Set.of(
            ItemID.DRAGON_PICKAXE,
            ItemID.INFERNAL_PICKAXE,
            ItemID.CRYSTAL_PICKAXE);

    /** [PROGRESSIVE] Ore ladder (hub parity — the ores the hub's progressive mode covers). */
    private static final List<MiningOre> PROGRESSIVE_ORES = List.of(
            MiningOre.TIN, MiningOre.IRON, MiningOre.COAL, MiningOre.GOLD,
            MiningOre.MITHRIL, MiningOre.ADAMANTITE, MiningOre.RUNITE);

    private Phase currentPhase = Phase.NONE;
    private boolean exitRequested = false;
    private int consecutiveFailures = 0;
    private double weatherMultiplier = 1.0;
    /** Where the module started mining: the search centre and the return point after trips. */
    private WorldPoint anchor = null;
    /** [PROGRESSIVE] Ore resolved for this activation (null until arrival at the location). */
    private MiningOre activeOre = null;
    /** Product count observed last tick (-1 = not tracked yet). */
    private int lastOreCount = -1;
    /** When the first tick saw no minable rock (0 = a rock is available). */
    private long noRockSinceMs = 0L;
    /** Timestamp of the last random AFK — minimum 5 s between AFKs. */
    private long lastAfkTime = 0L;
    /** Set once the batch-end break ran; cleared when product is back in hand. */
    private boolean postBatchBreakDone = false;
    /** True once a batch has been worked since the last bank/trip (break gating). */
    private boolean batchRanSinceBank = false;
    /** DROP mode: a drop phase is running (entered when full; ends when the count hits 0). */
    private boolean dropping = false;
    /** Travel watchdog: last observed position and when it last changed. */
    private WorldPoint lastTravelPos = null;
    private long lastTravelMoveAt = 0L;
    /** Pickaxe spec: one activation per full-energy cycle (BVF harpoon-spec shape). */
    private boolean pickaxeSpecDone = false;
    private int pickaxeSpecAttempts = 0;
    private long pickaxeSpecDeadlineMs = 0L;

    public enum Phase { NONE, MINING }

    // ── Lifecycle ─────────────────────────────────────────

    @Override
    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFailures = 0;
        anchor = null;
        activeOre = null;
        lastOreCount = -1;
        noRockSinceMs = 0L;
        lastAfkTime = 0L;
        postBatchBreakDone = false;
        batchRanSinceBank = false;
        dropping = false;
        lastTravelPos = null;
        lastTravelMoveAt = 0L;
        pickaxeSpecDone = false;
        pickaxeSpecAttempts = 0;
        pickaxeSpecDeadlineMs = 0L;
    }

    // ── Phase resolution ──────────────────────────────────

    @Override
    public void resolvePhase(AScriptConfig config) {
        if (config == null || config.scriptSelection() != ScriptType.MINING) {
            currentPhase = Phase.NONE;
            return;
        }
        boolean usable = config.miningProgressive()
                || (config.miningOre() != null && config.miningOre() != MiningOre.NONE);
        currentPhase = usable ? Phase.MINING : Phase.NONE;
    }

    @Override
    public boolean isActive() {
        return currentPhase != Phase.NONE;
    }

    // ── Selection validation ──────────────────────────────

    @Override
    public String validateSelection(AScriptConfig config) {
        if (currentPhase == Phase.NONE && config.scriptSelection() == ScriptType.MINING) {
            return "no ore selected";
        }
        if (currentPhase == Phase.MINING && !config.miningProgressive()) {
            // Capability guard: the level reads 0 while skill data is still loading —
            // treat that as "retry", not a stop.
            MiningOre ore = config.miningOre();
            int level = Rs2Player.getRealSkillLevel(Skill.MINING);
            if (level > 0 && level < ore.getMiningLevel()) {
                return "need level " + ore.getMiningLevel() + " Mining for "
                        + ore.getDisplayName() + " (current: " + level + ")";
            }
        }
        return null;
    }

    // ── Banking needs ─────────────────────────────────────

    @Override
    public boolean needsBank(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        MiningOre ore = currentOre(config);
        if (ore == null || ore == MiningOre.NONE) return false;

        // No pickaxe anywhere — it may be sitting in the bank.
        if (!hasPickaxe()) return true;

        // [GEM] At the gem mine the deposit box is module-owned — never the orchestrator's bank.
        if (atGemMineDeposit(config)) return false;
        // [BASALT] The Snowflake noting trip is module-owned (no bank near Weiss).
        if (atBasaltNoteTrip(config)) return false;

        if (config.miningAction() == MiningAction.BANK && Rs2Inventory.isFull()) {
            // A batch that actually ran earns the bank-closed break first (the yield);
            // a fresh full inventory banks immediately.
            return !batchRanSinceBank || postBatchBreakDone;
        }
        return false;
    }

    /**
     * The bank phase gate (cannonball pattern): readiness is judged on the bank OBJECT
     * being loaded and on camera, never on a distance to a coordinate.
     */
    @Override
    public boolean readyForBank(AScriptConfig config) {
        return AScriptBank.bankVisible();
    }

    @Override
    public boolean isBankMissingMaterials(AScriptConfig config) {
        if (currentPhase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config)) return false;
        // The only material this module needs is a pickaxe — missing from inventory,
        // equipment AND bank.
        return !hasPickaxe() && !pickaxeInBank();
    }

    @Override
    public String describeMissing(AScriptConfig config) {
        return "mining: no pickaxe (not worn, not in inventory, not in bank)";
    }

    // ── Banking ───────────────────────────────────────────

    @Override
    public boolean doBank(AScriptConfig config) {
        if (!Microbot.isLoggedIn()) return false;
        Microbot.status = "BANKING — Mining";

        // 0. A pickaxe carried in the inventory is equipped BEFORE the bank opens: the
        //    bank window swallows equip clicks, and a player already holding the tool must
        //    never be sent back for a bank copy. Explicit wear, verified.
        if (!wearingAnyPickaxe()) {
            Pickaxe held = bestPickaxeInInventory();
            if (held != null) {
                if (Rs2Bank.isOpen()) {
                    Rs2Bank.closeBank();
                    sleepUntil(() -> !Rs2Bank.isOpen(),
                            Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));
                }
                Rs2Inventory.wield(held.getItemId());
                if (!sleepUntil(MiningScript::wearingAnyPickaxe,
                        Rs2Random.logNormalBounded(2000, 3500, weatherMultiplier))) {
                    log.warn("[Mining] held pickaxe wear did not land");
                    return false;
                }
            }
        }

        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            return false;
        }

        // 1. Pickaxe: keep the worn one; otherwise take the best tier in the bank and
        //    equip it. An equip that fails leaves it in the inventory — lock the slot so
        //    the blanket deposit cannot send it back. ONE bank snapshot for the pick
        //    (never a per-tier scan through the retrying reads).
        if (!wearingAnyPickaxe()) {
            Pickaxe best = bestPickaxeInBank();
            if (best == null) {
                AScriptNotify.notify("Banking Failed", "No pickaxe in bank");
                return false;
            }
            Rs2Bank.withdrawAndEquip(best.getItemId());
            if (!sleepUntil(MiningScript::wearingAnyPickaxe,
                    Rs2Random.logNormalBounded(2000, 3500, weatherMultiplier))) {
                if (Rs2Inventory.hasItem(best.getItemId())) {
                    AScriptBank.ensureToolLocked(String.valueOf(best.getItemId()));
                } else {
                    log.warn("[Mining] pickaxe withdraw/equip did not land");
                    return false;
                }
            }
        }

        // 2. Deposit everything non-locked via the toolbar button.
        if (!AScriptBank.depositAndWaitEmpty()) {
            log.warn("[Mining] deposit failed");
            return false;
        }

        // The wear must survive the blanket deposit: if the lock was released, the
        // pickaxe goes to the bank and the next mining tick finds no tool.
        if (!wearingAnyPickaxe()) {
            log.warn("[Mining] pickaxe not worn after the deposit");
            return false;
        }

        // 3. [CLAY] Bracelet of clay: withdraw AFTER the deposit so the deposit can never
        //    take it back; the wear step (bank closed) lives in doAction. Failure here is
        //    non-fatal for the trip — the next cycle retries.
        if (config.miningClayBracelet()
                && currentOre(config) == MiningOre.CLAY
                && !Rs2Equipment.isWearing(ItemID.JEWL_BRACELET_OF_CLAY)
                && !Rs2Inventory.hasItem(ItemID.JEWL_BRACELET_OF_CLAY)) {
            if (!AScriptBank.withdrawVerified(String.valueOf(ItemID.JEWL_BRACELET_OF_CLAY))) {
                log.warn("[Mining] bracelet of clay withdraw failed — retrying next cycle");
            }
        }

        batchRanSinceBank = false;
        postBatchBreakDone = false;
        lastOreCount = -1;
        return true;
    }

    // ── Action (mine) ─────────────────────────────────────

    @Override
    public void doAction(AScriptConfig config) {
        if (!Microbot.isLoggedIn() || exitRequested) return;
        MiningOre ore = currentOre(config);
        if (ore == null || ore == MiningOre.NONE) return;

        WeatherModulation.ensureFresh();
        weatherMultiplier = 1.0 / WeatherModulation.combinedSpeedFactor();

        // Level-up and other continue dialogues — click through; mining resumes after.
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }

        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return;

        if (anchor == null) {
            // [PROGRESSIVE] Pick ore + location and travel there before the loop below
            // (the anchor is the location's tile once reached).
            if (config.miningProgressive()) {
                resolveProgressiveTarget(config);
                return;
            }
            anchor = player;
        }

        // [HOP] Crowded spot (opt-in).
        maybeHop(config);

        // [GEM] Bank-free deposit-box cycle at the underground gem mine.
        if (atGemMineDeposit(config)) {
            runGemDepositCycle(config);
            return;
        }

        // [BASALT] Bank-free Snowflake noting trip at the Weiss mine.
        if (atBasaltNoteTrip(config)) {
            noteBasalt(config);
            return;
        }

        // DROP mode: one drop PHASE per fill — entered when the inventory fills, and it
        // runs until the product count hits 0. The plain "drop while full" shape stops as
        // soon as a slice frees a slot, so the count would never reach 0 and the batch-end
        // break would never fire in steady state (the Woodcutting review finding; the
        // latch is its recommended fix). The phase latch is what makes the batch end
        // observable, and it is what earns the break below.
        if (config.miningAction() == MiningAction.DROP) {
            if (dropping) {
                if (countProduct(ore) == 0) {
                    dropping = false;
                    postBatchBreak(config);
                    return;
                }
                dropSlice(ore);
                return;
            }
            if (Rs2Inventory.isFull()) {
                if (countProduct(ore) == 0) {
                    // Full of something else: dropSlice would wait on a count that can never
                    // shrink and stop the module with "mining drop did not land".
                    fail("inventory is full but holds no " + ore.getDisplayName());
                    return;
                }
                dropping = true;
                dropSlice(ore);
                return;
            }
        }

        // Bank needed but not in sight: walk to it (both modes — the missing-pickaxe
        // trip is mode-independent). This branch MUST run before the full-inventory
        // break below (the break stays bank-closed via the needsBank yield).
        if (needsBank(config) && !AScriptBank.bankVisible()) {
            var bank = Rs2Bank.getNearestBank();
            if (bank == null) {
                fail("no reachable bank found");
                return;
            }
            // A bank object already loaded in the scene is TURNED TO, not walked at: the
            // walker cannot target an off-camera bank reliably, and a camera turn does not
            // move the player (cannonball approachBank pattern). Travel continues only
            // while no bank object is loaded.
            TileObject bankObject = AScriptBank.findBankObject();
            if (bankObject != null) {
                Microbot.status = "TURNING TO BANK";
                Rs2Camera.turnTo(bankObject);
                return;
            }
            travelTo(bank.getWorldPoint());
            return;
        }

        // BANK mode, full inventory: take the post-batch break (bank still closed); the
        // orchestrator banks on a later tick via the needsBank yield above.
        if (config.miningAction() == MiningAction.BANK && Rs2Inventory.isFull()) {
            if (batchRanSinceBank) postBatchBreak(config);
            else postBatchBreakDone = true;
            return;
        }

        // [CLAY] Wear step — only with the bank CLOSED (with the bank open an inventory
        // left-click is a deposit, never a Wear).
        if (config.miningClayBracelet()
                && currentOre(config) == MiningOre.CLAY
                && !Rs2Equipment.isWearing(ItemID.JEWL_BRACELET_OF_CLAY)
                && Rs2Inventory.hasItem(ItemID.JEWL_BRACELET_OF_CLAY)) {
            if (Rs2Bank.isOpen()) return; // wait for the orchestrator to close the bank
            Rs2ItemModel bracelet = AScriptPick.pick(ItemID.JEWL_BRACELET_OF_CLAY);
            Rectangle bounds = bracelet == null ? null : Rs2Inventory.itemBounds(bracelet);
            if (bounds == null) {
                fail("no clickable bracelet slot");
                return;
            }
            Microbot.getMouse().click(bounds);
            if (sleepUntil(() -> Rs2Equipment.isWearing(ItemID.JEWL_BRACELET_OF_CLAY),
                    Rs2Random.logNormalBounded(1500, 3000, weatherMultiplier))) {
                consecutiveFailures = 0;
            } else {
                fail("bracelet equip did not land");
            }
            return;
        }

        // Drifted away from the mining spot (bank trip, misclick): walk back first.
        if (player.distanceTo(anchor) > config.miningRadius()) {
            travelTo(anchor);
            return;
        }
        lastTravelPos = null; // arrived — the next trip gets a fresh watchdog

        // Pickaxe special attack — one activation per full-energy cycle.
        maybePickaxeSpec(config);

        int count = countProduct(ore);
        // Arm only with product in hand (the -1 baseline: `count > lastOreCount` alone
        // would arm the tracker at 0).
        if (count > 0 && count > lastOreCount) {
            lastOreCount = count;
            batchRanSinceBank = true;    // a batch is being worked
            postBatchBreakDone = false;  // product in hand — the next batch end is a new one
        }

        if (isMining()) {
            Microbot.status = "MINING — " + ore.getDisplayName()
                    + (count > 0 ? " (" + count + ")" : "");
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(600, 1200, weatherMultiplier));
            return;
        }

        Rs2TileObjectModel rock = findRock(ore, anchor, config.miningRadius());
        if (rock == null) {
            if (noRockSinceMs == 0L) noRockSinceMs = System.currentTimeMillis();
            if (System.currentTimeMillis() - noRockSinceMs > NO_ROCK_STOP_MS) {
                fail("no minable " + ore.getRockName() + " near the mining spot");
                return;
            }
            Microbot.status = "MINING — waiting for a rock";
            AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
            return;
        }
        noRockSinceMs = 0L;

        // Click the rock with the client-mouse object recipe (camera first; the real
        // clickbox; the canvas-sized fallback is never clicked).
        Microbot.status = "MINING — mining " + ore.getRockName();
        AScriptClick.Target target = AScriptClick.clickObject(rock, ROCK_RENDER_WAIT_STEPS);
        if (target.cameraTurned()) {
            return; // retry next tick — a camera turn is not a failure
        }
        if (!target.clicked()) {
            fail("rock click did not land (not rendered)");
            return;
        }

        // Confirm the mining started: the mining animation, or the first ore arriving.
        int before = count;
        if (!sleepUntil(() -> isMining() || countProduct(ore) > before,
                Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier))) {
            fail("click did not start mining");
            return;
        }
        consecutiveFailures = 0;
        lastOreCount = Math.max(lastOreCount, countProduct(ore));
        batchRanSinceBank = true;
    }

    // ── Internal helpers ──────────────────────────────────

    /** Ore in effect: the progressive pick once resolved, else the configured ore. */
    private MiningOre currentOre(AScriptConfig config) {
        if (config.miningProgressive()) {
            return activeOre != null ? activeOre : bestProgressiveOre();
        }
        return config.miningOre();
    }

    /** [PROGRESSIVE] Best hub-ladder ore at the real Mining level (0 while data loads → null). */
    private static MiningOre bestProgressiveOre() {
        int level = Rs2Player.getRealSkillLevel(Skill.MINING);
        if (level <= 0) return null;
        MiningOre best = MiningOre.TIN;
        for (MiningOre ore : PROGRESSIVE_ORES) {
            if (level >= ore.getMiningLevel()) best = ore;
        }
        return best;
    }

    /** [PROGRESSIVE] Resolve ore + location and travel; on arrival the anchor is the site tile. */
    private void resolveProgressiveTarget(AScriptConfig config) {
        MiningOre ore = bestProgressiveOre();
        if (ore == null) return; // skill data still loading — retry next tick
        MiningLocation loc = MiningLocations.getBestAccessibleLocation(ore);
        if (loc == null) {
            fail("no accessible " + ore.getDisplayName() + " mine for your account");
            return;
        }
        WorldPoint target = loc.getWorldPoint();
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return;
        if (player.distanceTo(target) <= 3) {
            anchor = target;
            activeOre = ore;
            consecutiveFailures = 0;
            Microbot.status = "MINING — arrived at " + loc.getName();
            return;
        }
        Microbot.status = "MINING — travelling to " + loc.getName();
        travelTo(target);
    }

    /** [HOP] Hop when at least {@code miningMaxPlayers} other players are mining nearby. */
    private void maybeHop(AScriptConfig config) {
        if (config.miningMaxPlayers() <= 0) return;
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null) return;
        long mining = Microbot.getRs2PlayerCache().query()
                .where(p -> p.getPlayer() != Microbot.getClient().getLocalPlayer()
                        && p.getWorldLocation() != null
                        && p.getWorldLocation().distanceTo(player) <= config.miningRadius()
                        && MINING_ANIMATION_IDS.contains(p.getAnimation()))
                .toListOnClientThread()
                .size();
        if (mining < config.miningMaxPlayers()) return;

        Microbot.status = "MINING — too many players, hopping world";
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(2000, 4000, weatherMultiplier));
        int world = Login.getRandomWorld(Rs2Player.isMember());
        if (world <= 0 || !Microbot.hopToWorld(world)) {
            fail("could not hop worlds");
            return;
        }
        consecutiveFailures = 0;
        lastTravelPos = null;
    }

    /** [GEM] True at the underground gem mine with a full inventory in Bank mode. */
    private boolean atGemMineDeposit(AScriptConfig config) {
        if (config.miningAction() != MiningAction.BANK
                || currentOre(config) != MiningOre.GEM
                || !Rs2Inventory.isFull()) {
            return false;
        }
        WorldPoint player = Rs2Player.getWorldLocation();
        return player != null && player.getRegionID() == GEM_MINE_UNDERGROUND_REGION;
    }

    /**
     * [GEM] Deposit-box cycle (MLM shape — the deposit box is module-owned; slot locks
     * do not apply there). Empties an open gem bag first (client-mouse default op),
     * deposits everything except the bag, closes the box, then runs the bank-free
     * post-batch break — the trip ends here.
     */
    private void runGemDepositCycle(AScriptConfig config) {
        Microbot.status = "MINING — depositing at the gem mine";
        if (!Rs2DepositBox.isOpen() && !Rs2DepositBox.openDepositBox()) {
            fail("gem deposit box did not open");
            return;
        }
        Rs2ItemModel openBag = AScriptPick.pick(ItemID.GEM_BAG_OPEN);
        if (openBag != null) {
            Rectangle bounds = Rs2Inventory.itemBounds(openBag);
            if (bounds != null) {
                int before = countProduct(MiningOre.GEM);
                Microbot.getMouse().click(bounds); // default op on the open bag: Empty
                if (!sleepUntil(() -> countProduct(MiningOre.GEM) > before,
                        Rs2Random.logNormalBounded(1200, 2500, weatherMultiplier))) {
                    fail("gem bag empty did not land");
                    return;
                }
            }
        }
        // Keep the bag AND every pickaxe id: the deposit box would otherwise take a held
        // pickaxe and the next mining tick would have no tool.
        if (!Rs2DepositBox.depositAllExcept(gemDepositKeepIds())) {
            fail("gem deposit box deposit failed");
            return;
        }
        Rs2DepositBox.closeDepositBox();
        consecutiveFailures = 0;
        batchRanSinceBank = false;
        lastOreCount = -1;
        postBatchBreak(config);
    }

    /** [BASALT] True at Weiss with a full inventory in Bank mode AND unnoted basalt to note. */
    private boolean atBasaltNoteTrip(AScriptConfig config) {
        return config.miningAction() == MiningAction.BANK
                && currentOre(config) == MiningOre.BASALT
                && Rs2Inventory.isFull()
                && hasUnnotedBasalt();
    }

    /**
     * [BASALT] True when the inventory holds at least one UNNOTED basalt. A full inventory
     * of something else must not enter the noting trip: noteBasalt() has nothing to use on
     * Snowflake and would return silently on every tick.
     */
    private static boolean hasUnnotedBasalt() {
        return Rs2Inventory.getList(i -> i.getId() == MiningOre.BASALT.getProductIds()[0])
                .stream().anyMatch(i -> !i.isNoted());
    }

    /**
     * [BASALT] Weiss basalt cannot be banked nearby — the hub notes it at Snowflake.
     * Verified flow: find the NPC (three id variants) → travel to the Snowflake spot
     * when not in scene → item-on-NPC recipe → confirm the basalt turned into
     * banknotes → break → walk back to the anchor. Noted basalt stacks in one slot,
     * so mining resumes with free slots.
     */
    private void noteBasalt(AScriptConfig config) {
        var snowflake = Microbot.getRs2NpcCache().query()
                .withIds(NpcID.MY2ARM_SNOWFLAKE, NpcID.MY2ARM_SNOWFLAKE_OUTSIDE, NpcID.MY2ARM_SNOWFLAKE_POSTQUEST)
                .nearestOnClientThread();
        if (snowflake == null) {
            Microbot.status = "MINING — travelling to Snowflake";
            travelTo(SNOWFLAKE_SPOT);
            return;
        }
        Rs2ItemModel basalt = AScriptPick.pick(MiningOre.BASALT.getProductIds()[0]);
        if (basalt == null) {
            return; // nothing to note (dropped by hand?) — retry next tick
        }
        if (!AScriptClick.useItemOnNpc(basalt, snowflake.getNpc())) {
            fail("basalt use-on-Snowflake click did not land");
            return;
        }
        if (!sleepUntil(MiningScript::basaltNoted,
                Rs2Random.logNormalBounded(2000, 3500, weatherMultiplier))) {
            fail("basalt note did not land");
            return;
        }
        consecutiveFailures = 0;
        batchRanSinceBank = false;
        lastOreCount = -1;
        postBatchBreak(config);
        travelTo(anchor);
    }

    /**
     * [BASALT] True when every basalt held is in banknote form. A noted item carries the
     * NOTED id (the unnoted one moves to {@code getUnNotedId()}), so filtering on the
     * unnoted id alone found nothing and the confirmation never fired.
     */
    private static boolean basaltNoted() {
        int unnotedId = MiningOre.BASALT.getProductIds()[0];
        List<Rs2ItemModel> basalt = Rs2Inventory.getList(
                i -> i.getId() == unnotedId || i.getUnNotedId() == unnotedId);
        return !basalt.isEmpty() && basalt.stream().allMatch(Rs2ItemModel::isNoted);
    }

    /**
     * One sliced drop pass over the mined product ({@link AScriptDrop}): a rolled 5–9 slots per
     * tick with log-normal pacing and a count verification. The shared helper owns the route,
     * slice and verify; this module's {@code dropping} latch owns "keep going until the product
     * count is 0" and the batch-end break.
     */
    private void dropSlice(MiningOre ore) {
        AScriptDrop.Result result = AScriptDrop.drop(
                item -> ore.isProduct(item.getId()),
                "MINING — dropping " + ore.getDisplayName(), weatherMultiplier);
        if (result.getOutcome() == AScriptDrop.Outcome.NO_PROGRESS) {
            fail("mining drop did not land");
        } else if (result.getOutcome() == AScriptDrop.Outcome.DROPPED) {
            consecutiveFailures = 0;
        }
    }

    /** Total held quantity of this ore's product(s). */
    private static int countProduct(MiningOre ore) {
        int total = 0;
        for (int id : ore.getProductIds()) {
            total += Rs2Inventory.itemQuantity(id);
        }
        return total;
    }

    /**
     * Nearest rock of the configured type within {@code radius} of the anchor. The
     * composition action filter ("Mine") is the discriminator — it excludes scenery
     * that shares the name — and the exact-name check keeps "tin rocks" from
     * resolving "Rocks" (the depleted/empty state) or another ore's rock.
     */
    private Rs2TileObjectModel findRock(MiningOre ore, WorldPoint center, int radius) {
        return Microbot.getRs2TileObjectCache().query()
                .within(center, radius)
                .where(x -> {
                    var comp = x.getObjectComposition();
                    if (comp == null || !Rs2GameObject.hasAction(comp, "Mine")) return false;
                    String name = comp.getName();
                    return name != null && name.equalsIgnoreCase(ore.getRockName());
                })
                .nearestOnClientThread();
    }

    /**
     * True while the player plays a mining animation (the in-flight signal). The
     * holding animation repeats while the rock has ore; when the rock depletes the
     * animation stops and the next tick re-resolves the nearest remaining rock.
     */
    private static boolean isMining() {
        return Rs2Player.isAnimating(1800)
                && MINING_ANIMATION_IDS.contains(Rs2Player.getAnimation());
    }

    /** Any level-usable pickaxe worn or in the inventory. */
    private static boolean hasPickaxe() {
        int level = Rs2Player.getRealSkillLevel(Skill.MINING);
        for (Pickaxe p : Pickaxe.values()) {
            if (level >= p.getMiningLevel()
                    && (Rs2Equipment.isWearing(p.getItemId()) || Rs2Inventory.hasItem(p.getItemId()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean wearingAnyPickaxe() {
        for (Pickaxe p : Pickaxe.values()) {
            if (Rs2Equipment.isWearing(p.getItemId())) return true;
        }
        return false;
    }

    /** Any pickaxe present in the bank (ONE snapshot pass — never per-tier retrying reads). */
    private static boolean pickaxeInBank() {
        return bestPickaxeInBank() != null;
    }

    /**
     * Best pickaxe in the bank: highest tier present, from ONE {@code Rs2Bank.getAll()}
     * snapshot (the JewelEnchant staff-scan lesson: a per-tier scan through the retrying
     * reads pays a 2-tick retry per miss and stalls the step silently).
     */
    private static Pickaxe bestPickaxeInBank() {
        int level = Rs2Player.getRealSkillLevel(Skill.MINING);
        Set<Integer> bankIds = Rs2Bank.getAll()
                .map(Rs2ItemModel::getId)
                .collect(Collectors.toSet());
        Pickaxe best = null;
        for (Pickaxe p : Pickaxe.values()) {
            if (bankIds.contains(p.getItemId()) && level >= p.getMiningLevel()) {
                best = p;
            }
        }
        return best;
    }

    /**
     * Best pickaxe the player is CARRYING (inventory only). The held-tool path equips this
     * before the bank opens; a worn pickaxe is handled by {@link #wearingAnyPickaxe()}.
     */
    private static Pickaxe bestPickaxeInInventory() {
        int level = Rs2Player.getRealSkillLevel(Skill.MINING);
        Pickaxe best = null;
        for (Pickaxe p : Pickaxe.values()) {
            if (Rs2Inventory.hasItem(p.getItemId()) && p.isUsableAt(level)) {
                best = p;
            }
        }
        return best;
    }

    /**
     * Ids the gem-mine deposit box must never take: the open bag and every pickaxe id.
     */
    private static Integer[] gemDepositKeepIds() {
        List<Integer> keep = new ArrayList<>();
        keep.add(ItemID.GEM_BAG_OPEN);
        for (Pickaxe p : Pickaxe.values()) {
            keep.add(p.getItemId());
        }
        return keep.toArray(new Integer[0]);
    }

    /**
     * Walk to {@code target} with a stagnation watchdog: no position change at all for
     * {@link #TRAVEL_STAGNATION_MS} stops the module loudly. Progress = "the player
     * moved" — a teleport can move you farther first, so distance never proves progress.
     */
    private void travelTo(WorldPoint target) {
        Microbot.status = "MINING — travelling";
        WorldPoint now = Rs2Player.getWorldLocation();
        if (now == null) return;
        if (lastTravelPos == null || !now.equals(lastTravelPos)) {
            lastTravelPos = now;
            lastTravelMoveAt = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastTravelMoveAt > TRAVEL_STAGNATION_MS) {
            exitRequested = true;
            stopWithMessage("could not reach the destination (no movement for "
                    + (TRAVEL_STAGNATION_MS / 1000) + "s)");
            return;
        }
        Rs2Walker.walkTo(target, 4);
    }

    /** True when a spec pickaxe is worn. */
    private static boolean specPickaxeWorn() {
        for (int id : SPEC_PICKAXE_IDS) {
            if (Rs2Equipment.isWearing(id)) return true;
        }
        return false;
    }

    /**
     * Pickaxe special attack — one activation per full-energy cycle, mirroring the BVF
     * dragon-harpoon / Woodcutting axe-spec block shape-for-shape: the latch applies
     * only after the spec state VERIFIES as active; an unconfirmed dispatch
     * re-attempts (bounded) instead of burning the cycle, and the latch resets
     * whenever the energy cycle is not full (i.e. right after the spec is consumed).
     */
    private void maybePickaxeSpec(AScriptConfig config) {
        if (!config.miningPickaxeSpec()) return;

        boolean wearingSpec = specPickaxeWorn();
        int energy = Rs2Combat.getSpecEnergy();

        if (!wearingSpec || energy < 1000) {
            pickaxeSpecDone = false;
            pickaxeSpecAttempts = 0;
            pickaxeSpecDeadlineMs = 0L;
            return;
        }
        if (pickaxeSpecDone || Rs2Combat.getSpecState()) return;

        long now = System.currentTimeMillis();
        if (pickaxeSpecDeadlineMs == 0L) {
            pickaxeSpecDeadlineMs = now + Rs2Random.logNormalBounded(5_000, 90_000, weatherMultiplier);
            return;
        }
        if (now < pickaxeSpecDeadlineMs) return;

        if (Rs2Combat.setSpecState(true)
                && sleepUntil(Rs2Combat::getSpecState,
                        (int) Rs2Random.logNormalBounded(1500, 2500, weatherMultiplier))) {
            pickaxeSpecDone = true;
            pickaxeSpecDeadlineMs = 0L;
            return;
        }

        pickaxeSpecAttempts++;
        log.debug("[Mining] Pickaxe spec activation not confirmed ({}/{})",
                pickaxeSpecAttempts, PICKAXE_SPEC_MAX_ATTEMPTS);
        if (pickaxeSpecAttempts >= PICKAXE_SPEC_MAX_ATTEMPTS) {
            pickaxeSpecDone = true; // give up until the energy cycle changes (restart re-arms)
        } else {
            pickaxeSpecDeadlineMs = now + Rs2Random.logNormalBounded(20_000, 45_000, weatherMultiplier);
        }
    }

    /**
     * Post-batch break — the house randomness pattern: a short randomized pause after
     * every batch, then the optional random AFK (log-normal 3–120 s, weather-modulated,
     * interruptible so blocking events still fire). Runs once per batch; clears
     * {@code batchRanSinceBank} and resets the count baseline so the next batch's
     * counts re-arm the tracker.
     */
    private void postBatchBreak(AScriptConfig config) {
        if (postBatchBreakDone) return;
        postBatchBreakDone = true;
        batchRanSinceBank = false;
        lastOreCount = -1;
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(800, 1600, weatherMultiplier));
        if (config.miningAfk() && System.currentTimeMillis() - lastAfkTime > 5_000) {
            int afkMs = Rs2Random.logNormalBounded(3000, 120_000, weatherMultiplier);
            Microbot.status = "AFK (" + (afkMs / 1000) + "s)";
            AScriptSleep.sleepInterruptibly(afkMs);
            lastAfkTime = System.currentTimeMillis();
        }
    }

    /**
     * Register a failed attempt: log it, back off, and stop the module (with a Discord
     * notification) after {@link #MAX_ACTION_FAILURES} in a row.
     */
    private void fail(String reason) {
        consecutiveFailures++;
        log.warn("[Mining] Attempt failed ({}/{}): {}",
                consecutiveFailures, MAX_ACTION_FAILURES, reason);
        if (consecutiveFailures >= MAX_ACTION_FAILURES) {
            exitRequested = true;
            stopWithMessage(reason);
        }
        AScriptSleep.sleepInterruptibly(Rs2Random.logNormalBounded(500, 1100, weatherMultiplier));
    }

    /**
     * Stop the module: status, log, Discord notification and config reset. Every stop
     * path goes through here so a self-stop is never silent.
     */
    private void stopWithMessage(String message) {
        Microbot.status = "STOPPED — " + message;
        log.warn("[Mining] Stopping: {}", message);
        AScriptNotify.notify("aScript Stopped — Mining", message);
        Microbot.getConfigManager().setConfiguration(AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
    }
}
