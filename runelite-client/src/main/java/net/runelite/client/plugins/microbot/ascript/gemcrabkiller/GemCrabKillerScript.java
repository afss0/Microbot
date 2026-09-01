package net.runelite.client.plugins.microbot.ascript.gemcrabkiller;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.ascript.AScriptConfig;
import net.runelite.client.plugins.microbot.ascript.ScriptType;
import net.runelite.client.plugins.microbot.ascript.util.AScriptBank;
import net.runelite.client.plugins.microbot.ascript.util.AScriptNotify;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.time.Instant;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Gem Crab Killer sub-script — stateless helper called by AScript's CRAFTING state.
 * <p>
 * Adapted from chsami/Microbot-Hub's GemCrabKillerPlugin.
 * The entire combat loop runs inside {@link #doFight} per tick.
 */
@Slf4j
public class GemCrabKillerScript {

    /** Module phase — exposed to AScript orchestrator. */
    public enum Phase { NONE, GEM_CRAB_KILLER }

    // ── Constants ──────────────────────────────────────────────

    private static final int CAVE_ENTRANCE_ID = 57631;
    private static final int CRAB_NPC_ID = 14779;
    private static final int CRAB_NPC_DEAD_ID = 14780;
    private static final WorldPoint CLOSEST_CRAB_LOCATION_TO_BANK = new WorldPoint(1274, 3168, 0);

    // ── Internal state ─────────────────────────────────────────

    private enum InternalState { WALKING, FIGHTING, BANKING, WAITING }

    private InternalState internalState = InternalState.WALKING;
    private boolean exitRequested = false;
    private boolean hasLooted = false;
    private Instant waitingTimeStart = null;
    private int consecutiveFightFailures = 0;
    private static final int MAX_CONSECUTIVE_FIGHT_FAILURES = 10;

    /** Total crab kills this session. Accessible from AScript for overlay display. */
    @lombok.Getter
    private int totalCrabKills = 0;

    /** Session start time for XP tracking. */
    @lombok.Getter
    private Instant sessionStartTime = null;

    // ── Module API (called by AScript orchestrator) ────────────

    public Phase resolvePhase(AScriptConfig config) {
        if (config == null || config.scriptSelection() != ScriptType.GEM_CRAB_KILLER) {
            return Phase.NONE;
        }
        return Phase.GEM_CRAB_KILLER;
    }

    public String validateSelection(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE && config.scriptSelection() == ScriptType.GEM_CRAB_KILLER) {
            return "no gem crab killer activity selected";
        }
        return null;
    }

    public boolean needsBank(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;

        // Bank when: no food + low HP, or inventory full after looting
        boolean noFood = Rs2Inventory.getInventoryFood().isEmpty();
        double healthPercentage = Rs2Player.getHealthPercentage();

        if (noFood && healthPercentage < 25.0) return true;
        if (Rs2Inventory.isFull()) return true;

        return false;
    }

    public boolean isBankMissingMaterials(AScriptConfig config, Phase phase) {
        if (phase == Phase.NONE || !Microbot.isLoggedIn()) return false;
        if (!needsBank(config, phase)) return false;

        // Gem crab banks at a distant location — can't check bank contents
        // from the cave. The consecutive bank failure counter in AScript will
        // stop the script after MAX_BANK_FAILURES if the bank truly has no food.
        return false;
    }

    public String describeMissing(AScriptConfig config, Phase phase) {
        return "no food in bank";
    }

    public boolean doBank(AScriptConfig config, Phase phase) {
        if (!Microbot.isLoggedIn()) return false;

        Microbot.status = "WALKING TO BANK";
        Rs2Walker.walkTo(BankLocation.TAL_TEKLAN.getWorldPoint());

        Microbot.status = "OPENING BANK";
        Rs2Bank.openBank();
        if (!sleepUntil(Rs2Bank::isOpen, Rs2Random.logNormalBounded(8000, 15000))) {
            return false;
        }

        Microbot.status = "DEPOSITING";
        AScriptBank.depositAll();

        // Verify inventory has food after banking
        sleepUntil(() -> !Rs2Inventory.getInventoryFood().isEmpty(),
                Rs2Random.logNormalBounded(3000, 5000));

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.logNormalBounded(2000, 4000));

        internalState = InternalState.WALKING;
        return !Rs2Inventory.getInventoryFood().isEmpty();
    }

    /**
     * One tick of the combat loop. Called by AScript's CRAFTING phase dispatch.
     */
    public void doFight(AScriptConfig config, Phase phase) {
        if (exitRequested) return;
        if (!Microbot.isLoggedIn()) return;

        try {
            switch (internalState) {
                case WALKING:
                    handleWalking();
                    break;
                case FIGHTING:
                    handlePotions(config);
                    handleSafety(config);
                    handleFighting(config);
                    break;
                case BANKING:
                    doBank(config, phase);
                    break;
                case WAITING:
                    handleWaiting();
                    break;
            }

            // Track consecutive failures — stop if stuck
            Rs2NpcModel npc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest();
            Rs2NpcModel deadNpc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_DEAD_ID).nearest();
            if (npc == null && deadNpc == null && internalState == InternalState.WALKING) {
                consecutiveFightFailures++;
                if (consecutiveFightFailures >= MAX_CONSECUTIVE_FIGHT_FAILURES) {
                    exitRequested = true;
                    Microbot.status = "STOPPED — no crabs found";
                    AScriptNotify.notify("Gem Crab Killer Stopped",
                            "No crabs found after " + MAX_CONSECUTIVE_FIGHT_FAILURES + " attempts");
                    Microbot.getConfigManager().setConfiguration(
                            AScriptConfig.GROUP, "scriptSelection", ScriptType.NONE);
                }
            } else {
                consecutiveFightFailures = 0;
            }
        } catch (Exception ex) {
            log.error("[GemCrabKiller] Error in fight loop", ex);
        }
    }

    public void resetExitFlag() {
        exitRequested = false;
        consecutiveFightFailures = 0;
        hasLooted = false;
        waitingTimeStart = null;
        totalCrabKills = 0;
        sessionStartTime = null;
        internalState = InternalState.WALKING;
    }

    // ── Internal handlers ──────────────────────────────────────

    private void handleWalking() {
        if (sessionStartTime == null) sessionStartTime = Instant.now();

        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), Rs2Random.logNormalBounded(2000, 4000));
        }

        // Check for nearby crab
        Rs2NpcModel npc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest();
        if (npc != null) {
            internalState = InternalState.FIGHTING;
            consecutiveFightFailures = 0;
            return;
        }

        // Check for cave entrance
        Rs2TileObjectModel caveEntrance = Microbot.getRs2TileObjectCache()
                .query().withId(CAVE_ENTRANCE_ID).nearest();
        if (caveEntrance != null) {
            var composition = caveEntrance.getObjectComposition();
            if (composition != null
                    && java.util.Arrays.stream(composition.getActions())
                    .anyMatch("Crawl-through"::equals)) {
                Microbot.getRs2TileObjectCache().query().withId(CAVE_ENTRANCE_ID)
                        .interact("Crawl-through");
                sleepUntil(
                        () -> Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest() != null,
                        Rs2Random.logNormalBounded(4000, 7000));
                if (Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest() != null) {
                    internalState = InternalState.FIGHTING;
                    consecutiveFightFailures = 0;
                }
                return;
            }
        }

        // Walk to known crab location
        Microbot.status = "WALKING TO CRABS";
        Rs2Walker.walkTo(CLOSEST_CRAB_LOCATION_TO_BANK);
    }

    private void handleFighting(AScriptConfig config) {
        Rs2NpcModel deadNpc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_DEAD_ID).nearest();
        Rs2NpcModel npc = Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest();

        // Dead crab — loot and exit
        if (deadNpc != null) {
            totalCrabKills++;
            if (config.gemCrabLootCrab()
                    && Rs2Inventory.hasItem(" pickaxe", false)
                    && !hasLooted) {
                Microbot.status = "MINING LOOT";
                deadNpc.click("Mine");
                Rs2Inventory.waitForInventoryChanges(Rs2Random.logNormalBounded(2000, 4000));
                sleep(Rs2Random.logNormalBounded(3000, 5000));
                hasLooted = true;
                if (Rs2Inventory.isFull()) {
                    internalState = InternalState.BANKING;
                    return;
                }
            }

            // Exit cave
            Microbot.getRs2TileObjectCache().query().withId(CAVE_ENTRANCE_ID)
                    .interact("Crawl-through");
            internalState = InternalState.WAITING;
            waitingTimeStart = Instant.now();
            return;
        } else {
            hasLooted = false;
        }

        // No crab at all — go find one
        if (npc == null) {
            internalState = InternalState.WALKING;
            return;
        }

        // Attack if not in combat
        if (!Rs2Player.isInCombat()) {
            Microbot.status = "FIGHTING";
            npc.click("Attack");
        } else {
            waitingTimeStart = null;
        }
    }

    private void handleWaiting() {
        if (waitingTimeStart == null) {
            waitingTimeStart = Instant.now();
        }

        // New crab appeared
        if (Microbot.getRs2NpcCache().query().withId(CRAB_NPC_ID).nearest() != null) {
            internalState = InternalState.FIGHTING;
            waitingTimeStart = null;
            return;
        }

        // Timeout — go find next crab
        long waitDuration = Rs2Random.logNormalBounded(12000, 18000);
        if (Instant.now().isAfter(waitingTimeStart.plusMillis(waitDuration))) {
            waitingTimeStart = null;
            internalState = InternalState.WALKING;
        }
    }

    private void handleSafety(AScriptConfig config) {
        if (config.gemCrabDharokMode()) {
            int currentHP = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);

            // Lower HP with locator orb or rock cake
            if (currentHP > 10) {
                if (Rs2Inventory.hasItem(ItemID.LOCATOR_ORB)) {
                    Rs2Inventory.interact(ItemID.LOCATOR_ORB, "feel");
                } else if (Rs2Inventory.hasItem(ItemID.DWARVEN_ROCK_CAKE_7510)) {
                    Rs2Inventory.interact(ItemID.DWARVEN_ROCK_CAKE_7510, "guzzle");
                }
            }

            // Emergency eat
            if (currentHP <= 2) {
                Rs2Player.eatAt(100);
            }

            // Rapid heal prayer flick
            int prayerLevel = Rs2Player.getRealSkillLevel(Skill.PRAYER);
            if (prayerLevel >= 25) {
                if (Rs2Random.diceFractional(0.98)) {
                    Rs2Prayer.toggle(Rs2PrayerEnum.RAPID_HEAL, true);
                    sleep(300, 600);
                    Rs2Prayer.toggle(Rs2PrayerEnum.RAPID_HEAL, false);
                }
            }
        } else {
            // Normal mode — eat at 50%
            Rs2Player.eatAt(50);
        }

        // Check if out of food and low HP — need to bank
        boolean hasFood = !Rs2Inventory.getInventoryFood().isEmpty();
        double healthPercentage = Rs2Player.getHealthPercentage();
        if (!hasFood && healthPercentage < 25.0) {
            internalState = InternalState.BANKING;
        }
    }

    private void handlePotions(AScriptConfig config) {
        if (!config.gemCrabUseOffensivePotions()) return;
        if (!Rs2Combat.inCombat()) return;

        if (Rs2Player.drinkCombatPotionAt(Skill.RANGED, false)) {
            Rs2Player.waitForAnimation();
        }
        if (Rs2Player.drinkCombatPotionAt(Skill.MAGIC, false)) {
            Rs2Player.waitForAnimation();
        }
        if (Rs2Player.drinkCombatPotionAt(Skill.STRENGTH)) {
            Rs2Player.waitForAnimation();
        }
        if (Rs2Player.drinkCombatPotionAt(Skill.ATTACK)) {
            Rs2Player.waitForAnimation();
        }
        if (Rs2Player.drinkCombatPotionAt(Skill.DEFENCE)) {
            Rs2Player.waitForAnimation();
        }
    }
}
