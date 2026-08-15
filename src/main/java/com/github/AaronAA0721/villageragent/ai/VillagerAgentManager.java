package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.block.Block;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages all AI villager agents in the world
 */
public class VillagerAgentManager {
    private static final Random RANDOM = new Random();

    /**
     * Chance per tick that an idle farmer glances around for farming work.
     * Farming ticks run every tick (not gated by AGENT_THINK_INTERVAL),
     * so 1/200 ≈ once every 10 seconds on average.
     */
    private static final double FARMING_SCAN_CHANCE = 0.005;

    /**
     * How often (in ticks) the farming state machine runs for active farmers.
     * Walking/acting checks run on this interval for responsiveness.
     * Idle scanning uses its own random chance per tick.
     */
    private static final int FARMING_TICK_INTERVAL = 3;

    /**
     * Cooldown (in ticks) after the villager finishes a farming session before
     * it starts scanning for new work again.  200-400 ticks ≈ 10-20 seconds.
     */
    private static final int FARMING_COOLDOWN_MIN_TICKS = 200;
    private static final int FARMING_COOLDOWN_MAX_TICKS = 400;

    /**
     * How often (in ticks) the combat state machine runs.
     * Every 2 ticks for responsive chasing/attacking.
     */
    private static final int COMBAT_TICK_INTERVAL = 2;

    /**
     * How often (in ticks) idle villagers scan for threats.
     * Every 10 ticks ≈ 0.5 seconds.
     */
    private static final int COMBAT_SCAN_INTERVAL = 10;

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<UUID, VillagerAgentData> agents = new ConcurrentHashMap<>();

    /**
     * Get or create agent data for a villager
     */
    public static VillagerAgentData getOrCreateAgent(VillagerEntity villager) {
        UUID id = villager.getUUID();
        return agents.computeIfAbsent(id, uuid -> {
            LOGGER.info("Creating new AI agent for villager: " + uuid);
            return new VillagerAgentData(uuid, true);
        });
    }

    /**
     * Get agent data if it exists
     */
    public static VillagerAgentData getAgent(UUID villagerId) {
        return agents.get(villagerId);
    }

    /**
     * Add an agent directly (used when loading from saved data)
     */
    public static void addAgent(UUID villagerId, VillagerAgentData agent) {
        agents.put(villagerId, agent);
        LOGGER.info("Added AI agent: " + villagerId);
    }

    /**
     * Remove agent data (when villager dies or is removed)
     */
    public static void removeAgent(UUID villagerId) {
        agents.remove(villagerId);
        LOGGER.info("Removed AI agent: " + villagerId);
    }

    /**
     * Update all agents in the world (slow tick — goals, restocking, etc.)
     */
    public static void tickAgents(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;

        // Drive the shared, per-dimension building index: drain up to FLOODS_PER_TICK
        // queued bed flood-fills. Beds were queued by ChunkEvent.Load / block-place events,
        // so this just trickles the discovery work across ticks — no periodic world sweep.
        if (world instanceof ServerWorld) {
            WorldStructureIndex.instance(world).processPending(world, WorldStructureIndex.FLOODS_PER_TICK);
        }

        long currentTime = world.getGameTime();
        int thinkInterval = ModConfig.AGENT_THINK_INTERVAL.get();

        for (Map.Entry<UUID, VillagerAgentData> entry : agents.entrySet()) {
            VillagerAgentData agent = entry.getValue();

            // Only update periodically to avoid performance issues
            if (currentTime - agent.getLastThinkTime() >= thinkInterval) {
                VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
                if (villager != null) {
                    agent.setLastThinkTime(currentTime);
                    updateAgent(world, villager, agent);
                }
            }
        }
    }

    /**
     * Fast tick for farming — runs every tick so walking/acting is responsive.
     * Separated from the slow AI think loop to avoid 100-tick delays between
     * walk checks and action execution.
     */
    public static void tickFarming(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return;
        if (!(world instanceof ServerWorld)) return;

        long currentTime = world.getGameTime();
        ServerWorld serverWorld = (ServerWorld) world;

        for (VillagerAgentData agent : agents.values()) {
            String profession = agent.getProfession();
            if (profession == null || !profession.equalsIgnoreCase("farmer")) continue;

            // Active farming (walking/acting) runs every FARMING_TICK_INTERVAL ticks
            // Idle scanning runs every tick but with a low random chance
            boolean hasActiveAction = agent.getCurrentAction() != null && isFarmingAction(agent.getCurrentAction());
            boolean inFarmingState = agent.isInFarmingState();
            boolean onCooldown = agent.isOnFarmingCooldown();

            if (hasActiveAction || inFarmingState || onCooldown) {
                // Active farming — run every few ticks for responsiveness
                if (currentTime % FARMING_TICK_INTERVAL != 0) continue;
            }
            // else: idle — runs every tick, gated by FARMING_SCAN_CHANCE inside performFarmerActions

            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            performFarmerActions(villager, serverWorld, agent);
        }
    }

    // ===================================================================
    //  Combat tick — all villagers scan for and fight hostile mobs
    // ===================================================================

    /**
     * Fast tick for combat — runs every tick so chasing/attacking is responsive.
     * All villagers (not just farmers) will defend themselves against hostile mobs.
     */
    public static void tickCombat(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return;
        if (!(world instanceof ServerWorld)) return;

        long currentTime = world.getGameTime();
        ServerWorld serverWorld = (ServerWorld) world;

        for (VillagerAgentData agent : agents.values()) {
            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            // Tick attack cooldown once per tick in the villager's active world
            agent.tickAttackCooldown();

            // Check if this villager is already in a combat action
            VillagerAction current = agent.getCurrentAction();
            boolean inCombat = current != null && current.getActionType() == VillagerAction.ActionType.ATTACK;

            if (inCombat) {
                // Active combat — run every COMBAT_TICK_INTERVAL ticks
                if (currentTime % COMBAT_TICK_INTERVAL != 0) continue;
            } else {
                // Idle scan — run every COMBAT_SCAN_INTERVAL ticks
                if (currentTime % COMBAT_SCAN_INTERVAL != 0) continue;
            }

            performCombatActions(villager, serverWorld, agent);
        }
    }

    /**
     * Combat state machine for a single villager.
     *
     * <p>States:
     * <ol>
     *   <li><b>Chasing</b> — walking toward a hostile target. Re-issue nav, check range.</li>
     *   <li><b>Attacking</b> — in melee range, deal damage on cooldown.</li>
     *   <li><b>Idle</b> — scan for nearby threats.</li>
     * </ol>
     */
    private static void performCombatActions(VillagerEntity villager, ServerWorld world, VillagerAgentData agent) {
        VillagerAction current = agent.getCurrentAction();

        // ── 1. Already in a combat action ──
        if (current != null && current.getActionType() == VillagerAction.ActionType.ATTACK) {
            continueCombatAction(villager, world, agent, current);
            return;
        }

        // ── 2. Don't interrupt ANY active action with combat scanning ──
        // Only scan for threats when the villager is truly idle (no current action).
        // This prevents combat from overriding farming, crafting, gathering, etc.
        if (current != null) return;

        // ── 3. Idle — scan for threats ──
        LivingEntity threat = CombatAction.findNearestThreat(villager, world);
        if (threat != null) {
            startCombatAction(villager, agent, threat);
        }
    }

    /**
     * Start chasing a hostile target.
     */
    private static void startCombatAction(VillagerEntity villager, VillagerAgentData agent, LivingEntity target) {
        VillagerAction action = new VillagerAction(VillagerAction.ActionType.ATTACK,
                "Attacking " + target.getType().getRegistryName());
        action.setTargetEntityId(target.getUUID());
        action.setPhase(VillagerAction.ActionPhase.WALKING);
        agent.setCurrentAction(action);
        agent.setCurrentActivity("fighting");

        // Equip best weapon from inventory for damage and visual display
        CombatAction.findBestWeaponDamage(agent, villager);

        // Start walking toward the target (0.4 = reasonable combat speed,
        // faster than normal wander ~0.35 but not too fast)
        villager.getNavigation().moveTo(target, 0.4);

        agent.addMemory("Engaging hostile: " + target.getType().getRegistryName());
        LOGGER.debug("{} engaging {}", agent.getName(), target.getType().getRegistryName());
    }

    /**
     * Continue an in-progress combat action: chase, attack, or disengage.
     */
    private static void continueCombatAction(VillagerEntity villager, ServerWorld world,
                                              VillagerAgentData agent, VillagerAction action) {
        UUID targetId = action.getTargetEntityId();
        if (targetId == null) {
            disengageCombat(villager, agent);
            return;
        }

        // Find the target entity
        Entity rawTarget = world.getEntity(targetId);
        if (!(rawTarget instanceof LivingEntity) || !rawTarget.isAlive()) {
            // Target dead or gone — disengage
            disengageCombat(villager, agent);
            LOGGER.debug("{} target eliminated or lost", agent.getName());
            return;
        }

        LivingEntity target = (LivingEntity) rawTarget;
        double distSq = villager.distanceToSqr(target);

        // Check if target moved out of scan range — disengage
        if (distSq > CombatAction.SCAN_RANGE * CombatAction.SCAN_RANGE * 1.5) {
            disengageCombat(villager, agent);
            LOGGER.debug("{} lost sight of target, disengaging", agent.getName());
            return;
        }

        // In attack range — attack!
        if (distSq <= CombatAction.ATTACK_RANGE_SQ) {
            action.setPhase(VillagerAction.ActionPhase.ACTING);
            if (!agent.isOnAttackCooldown()) {
                CombatAction.attackEntity(villager, target, agent);
                agent.setAttackCooldownTicks(CombatAction.ATTACK_COOLDOWN_TICKS);
            }
            // Stay in combat action — keep attacking until target dies or flees
            return;
        }

        // Still chasing — check if stuck
        action.incrementStuckTicks();
        if (action.getStuckTicks() > CombatAction.CHASE_TIMEOUT_TICKS) {
            disengageCombat(villager, agent);
            LOGGER.debug("{} gave up chasing target (stuck)", agent.getName());
            return;
        }

        // Re-issue navigation toward moving target
        action.setPhase(VillagerAction.ActionPhase.WALKING);
        villager.getNavigation().moveTo(target, 0.4);
    }

    /**
     * Clean up combat state: clear the action and reset activity.
     * Does NOT unequip the weapon — villagers keep holding whatever they had.
     * Equipment is only changed when starting a new contextual action
     * (e.g. equip hoe for farming, empty hand for talking).
     */
    private static void disengageCombat(VillagerEntity villager, VillagerAgentData agent) {
        agent.setCurrentAction(null);
        agent.setCurrentActivity("idle");
    }

    // ===================================================================
    //  Building tick — placing & breaking single blocks, and driving BuildJobs
    // ===================================================================

    /**
     * Fast tick for world building. Runs every tick so placing/breaking is responsive and so a
     * multi-block {@link BuildJob} makes steady progress (one block per {@code BUILD_BLOCK_INTERVAL}
     * ticks). Never interrupts farming/combat/etc. — it only owns the villager when the current
     * action is one of PLACE / BREAK / BUILD, or when a BuildJob is active.
     */
    public static void tickBuilding(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_WORLD_INTERACTION.get()) return;
        if (!ModConfig.ENABLE_BUILDING.get()) return;
        if (!(world instanceof ServerWorld)) return;

        ServerWorld serverWorld = (ServerWorld) world;
        long currentTime = world.getGameTime();

        for (VillagerAgentData agent : agents.values()) {
            VillagerAction cur = agent.getCurrentAction();

            boolean ourAction = cur != null && (cur.getActionType() == VillagerAction.ActionType.PLACE
                    || cur.getActionType() == VillagerAction.ActionType.BREAK
                    || cur.getActionType() == VillagerAction.ActionType.BUILD);
            // A different system (farming/combat/activity) owns the villager right now — leave it.
            if (cur != null && !ourAction) continue;

            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            if (ourAction) {
                BlockInteractionAction.StepResult res;
                if (cur.getActionType() == VillagerAction.ActionType.PLACE) {
                    res = BlockInteractionAction.tickPlace(villager, serverWorld, agent, cur);
                } else if (cur.getActionType() == VillagerAction.ActionType.BREAK) {
                    res = BlockInteractionAction.tickBreak(villager, serverWorld, agent, cur);
                } else {
                    res = BlockInteractionAction.StepResult.DONE; // BUILD marker — clear it
                }
                if (res == BlockInteractionAction.StepResult.DONE
                        || res == BlockInteractionAction.StepResult.ABANDONED) {
                    if (res == BlockInteractionAction.StepResult.ABANDONED) {
                        LOGGER.debug(agent.getName() + " abandoned block step at "
                                + cur.getTargetBlockPos());
                    }
                    advanceBuildCursor(agent, cur);
                    agent.setCurrentAction(null);
                }
                continue;
            }

            // No active world-action: drive the next BuildJob step (throttled).
            BuildJob job = agent.getBuildJob();
            if (job == null) continue;

            if (job.isComplete()) {
                finishBuildJob(agent, job);
                continue;
            }
            // Throttle: only begin a fresh block every BUILD_BLOCK_INTERVAL ticks.
            if (currentTime % ModConfig.BUILD_BLOCK_INTERVAL.get() != 0) continue;

            job.reconcileWithWorld(serverWorld);
            if (job.isComplete()) {
                finishBuildJob(agent, job);
                continue;
            }

            BuildOrderPlanner.Step step = job.currentStep();
            if (step == null) continue;

            VillagerAction act = new VillagerAction(VillagerAction.ActionType.PLACE,
                    "Placing block for " + job.getName());
            act.setTargetBlockPos(step.target);
            act.setPlaceBlock(step.block);
            act.setStandCell(step.stand);
            act.setJobCursor(job.getCursor());
            act.setPhase(VillagerAction.ActionPhase.WALKING);
            act.resetStuckTicks();
            agent.setCurrentAction(act);
            agent.setCurrentActivity("building");
        }
    }

    /** Advance the BuildJob cursor past the just-finished (or skipped) step. */
    private static void advanceBuildCursor(VillagerAgentData agent, VillagerAction cur) {
        BuildJob job = agent.getBuildJob();
        if (job == null) return;
        int idx = cur.getJobCursor();
        if (idx >= 0 && idx == job.getCursor()) {
            job.setCursor(idx + 1);
        }
    }

    /** The BuildJob is done — record it and clear it. */
    private static void finishBuildJob(VillagerAgentData agent, BuildJob job) {
        agent.addMemory("Finished building " + job.getName() + " (" + job.getTotal() + " blocks)");
        LOGGER.info(agent.getName() + " finished building '" + job.getName() + "'");
        agent.setCurrentActivity("idle");
        agent.setBuildJob(null);
    }

    // ── Command-facing request helpers (also usable by a future LLM action parser) ──

    /** Queue a single-block PLACE for the villager (walks to a legal standing cell first). */
    public static void requestPlace(VillagerAgentData agent, VillagerEntity villager,
                                     ServerWorld world, BlockPos target, Block block) {
        BlockPos stand = BlockInteractionAction.findStandable(world, target);
        VillagerAction act = new VillagerAction(VillagerAction.ActionType.PLACE,
                "Placing " + block.getRegistryName() + " at " + target);
        act.setTargetBlockPos(target);
        act.setPlaceBlock(block);
        act.setStandCell(stand);
        act.setPhase(VillagerAction.ActionPhase.WALKING);
        act.resetStuckTicks();
        agent.setCurrentAction(act);
        agent.setCurrentActivity("placing");
    }

    /** Queue a single-block BREAK for the villager (walks to a legal standing cell first). */
    public static void requestBreak(VillagerAgentData agent, VillagerEntity villager,
                                     ServerWorld world, BlockPos target) {
        BlockPos stand = BlockInteractionAction.findStandable(world, target);
        VillagerAction act = new VillagerAction(VillagerAction.ActionType.BREAK,
                "Breaking block at " + target);
        act.setTargetBlockPos(target);
        act.setStandCell(stand);
        act.setPhase(VillagerAction.ActionPhase.WALKING);
        act.resetStuckTicks();
        agent.setCurrentAction(act);
        agent.setCurrentActivity("breaking");
    }

    /** Ask the LLM to design and build a structure near the villager. */
    public static void requestBuild(VillagerAgentData agent, VillagerEntity villager,
                                     ServerWorld world, String goalText) {
        StructureBuilder.requestStructure(agent, villager, world, goalText);
    }


    /** How often (in ticks) to automatically refresh the environment snapshot. ~1 Minecraft minute. */
    private static final int ENV_REFRESH_INTERVAL = 1200;

    /**
     * Update a single agent's AI (slow tick — goals, restocking, etc.)
     * Farming and combat are handled separately by tickFarming()/tickCombat().
     */
    private static void updateAgent(World world, VillagerEntity villager, VillagerAgentData agent) {
        if (villager == null) {
            return; // Villager not loaded or doesn't exist
        }

        // Daily schedule planner — generates plan at dawn, applies current task, evening reflection
        if (world instanceof ServerWorld) {
            ServerWorld sw = (ServerWorld) world;

            if (ModConfig.ENABLE_DAILY_SCHEDULE.get()) {
                VillagerSchedulePlanner.tick(sw, villager, agent);
            }

            // Autonomous environment refresh (keeps snapshot current for planning and chat)
            long gameTime = world.getGameTime();
            // Always update chunk memory on every slow tick (cheap — just a Set.contains check)
            BlockPos vPos = villager.blockPosition();
            agent.updateChunkMemory(villager, world, vPos.getX() >> 4, vPos.getZ() >> 4);

            if (gameTime - agent.getLastEnvRefreshTick() >= ENV_REFRESH_INTERVAL) {
                agent.setLastEnvRefreshTick(gameTime);
                String envSummary = VillagerVisionSystem.buildEnvironmentSummary(villager, sw, agent);
                agent.setEnvironmentSummary(envSummary);
            }

            // Activity system — translates scheduledActivity into actual movement
            VillagerActivitySystem.tick(sw, villager, agent);

            // Needs system — hunger decay, eating, fatigue
            VillagerNeedsSystem.tick(sw, agent);
        }

        // Check if villager is at their job block for restocking
        checkJobBlockRestock(villager, agent);

        // Process current goals
        processGoals(villager, agent);

        // Decide on new actions based on AI
        if (agent.getGoals().isEmpty() || shouldGenerateNewGoals(agent)) {
            generateNewGoals(villager, agent);
        }
    }

    /**
     * Social tick — scan for nearby villager pairs and trigger LLM-generated conversations.
     * Called every world tick but internally gated to every 200 ticks.
     */
    public static void tickSocial(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_VILLAGER_SOCIAL.get()) return;
        if (!(world instanceof ServerWorld)) return;
        VillagerSocialSystem.tickSocial((ServerWorld) world);
    }

    // ── Spontaneous thought bubbles ──────────────────────────────────────────

    /** How far a player must be (squared) to trigger a thought bubble. */
    private static final double THOUGHT_PLAYER_RANGE_SQ = 400.0; // 20 blocks

    /** Minimum ticks between two thought bubbles for the same villager (~5 MC minutes). */
    private static final long THOUGHT_COOLDOWN_TICKS = 6_000L;

    /** How often (ticks) we scan for villagers that should emit a thought. */
    private static final int THOUGHT_SCAN_INTERVAL = 40;

    /**
     * Thought tick — periodically emits an LLM-generated inner thought as a nearby-player
     * bubble. This ambient feature is OFF by default (see {@link ModConfig#ENABLE_VILLAGER_THOUGHTS});
     * the underlying generation API ({@link #requestThought}) remains available for other
     * features (e.g. a future "mind-reader" item that reveals a villager's thoughts to the
     * holder only, instead of broadcasting to everyone nearby).
     */
    public static void tickThoughts(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_VILLAGER_THOUGHTS.get()) return;
        if (!(world instanceof ServerWorld)) return;

        long gameTime = world.getGameTime();
        if (gameTime % THOUGHT_SCAN_INTERVAL != 0) return;

        ServerWorld serverWorld = (ServerWorld) world;

        for (VillagerAgentData agent : agents.values()) {
            // Per-villager cooldown
            if (gameTime - agent.getLastThoughtTick() < THOUGHT_COOLDOWN_TICKS) continue;

            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            // Check whether any player is nearby
            boolean playerNearby = false;
            for (ServerPlayerEntity player : serverWorld.getServer().getPlayerList().getPlayers()) {
                if (player.distanceToSqr(villager) <= THOUGHT_PLAYER_RANGE_SQ) {
                    playerNearby = true;
                    break;
                }
            }
            if (!playerNearby) continue;

            // Mark the tick immediately to prevent concurrent calls
            agent.setLastThoughtTick(gameTime);

            final VillagerEntity finalVillager = villager;
            requestThought(agent, thought -> {
                // Broadcast to nearby players
                StringTextComponent msg = new StringTextComponent(
                        "§7[" + agent.getName() + " thinks] §o" + thought);
                for (ServerPlayerEntity player : serverWorld.getServer().getPlayerList().getPlayers()) {
                    if (player.distanceToSqr(finalVillager) <= THOUGHT_PLAYER_RANGE_SQ) {
                        player.sendMessage(msg, Util.NIL_UUID);
                    }
                }

                // Store as a memory so it influences future behaviour
                agent.addMemory("I thought: " + thought);
                LOGGER.debug("{} emitted thought: {}", agent.getName(), thought);
            });
        }
    }

    /**
     * Core inner-thought generation API. Builds a personality/mood/context-aware prompt and
     * asks the LLM for a single first-person thought, delivered asynchronously to
     * {@code onThought}. This method performs no broadcast of its own — the caller decides
     * what to do with the result.
     *
     * <p>Used by {@link #tickThoughts} for the ambient bubble. Intended for any future feature
     * that wants to read a villager's mind (e.g. a "mind-reader" item that shows the thought
     * only to the player holding it, rather than to everyone nearby).
     *
     * @param agent     the villager whose thoughts to generate
     * @param onThought callback receiving the trimmed thought text (empty responses are ignored)
     */
    public static void requestThought(VillagerAgentData agent, Consumer<String> onThought) {
        String env  = agent.getEnvironmentSummary();
        String recentMem = agent.getMemories().isEmpty() ? "nothing in particular"
                : agent.getMemories().get(agent.getMemories().size() - 1);
        String activity = agent.getCurrentActivity() != null ? agent.getCurrentActivity() : "idle";
        String needsMood = VillagerNeedsSystem.buildNeedsDescription(agent);

        String sysPrompt = "You are a Minecraft villager. Write a single short internal thought "
                + "(1 sentence, first person) that reflects what you are feeling or thinking "
                + "right now. Be specific, in-character, and vivid. No quotation marks, no labels.";
        String userPrompt = "Name: " + agent.getName()
                + "\nProfession: " + agent.getProfession()
                + "\nPersonality: " + agent.getPersonality()
                + "\nMood: " + agent.getMood().name().toLowerCase()
                + (needsMood.isEmpty() ? "" : " (" + needsMood + ")")
                + "\nCurrent activity: " + activity
                + (env != null ? "\nEnvironment: " + env : "")
                + "\nMost recent memory: " + recentMem
                + "\nWrite your thought:";

        LLMService.queryLLM(sysPrompt, userPrompt).thenAccept(thought -> {
            if (thought == null || thought.trim().isEmpty()) return;
            onThought.accept(thought.trim());
        }).exceptionally(e -> {
            LOGGER.warn("Thought LLM call failed for {}: {}", agent.getName(), e.getMessage());
            return null;
        });
    }

    // ── Player proximity greeting ─────────────────────────────────────────────

    /** Player must be within this range (squared) to trigger a greeting (8 blocks). */
    private static final double GREETING_RANGE_SQ = 64.0;

    /** Minimum ticks between greetings to the same player (~2 MC minutes = 2400 ticks). */
    private static final long GREETING_COOLDOWN_TICKS = 2_400L;

    /** Radius (squared) within which we count "conversable targets" (other villagers + players)
     *  to dilute the greeting chance — roughly one chunk of vicinity (16 blocks). */
    private static final double GREETING_AWARENESS_SQ = 16.0 * 16.0;

    /**
     * Greeting tick — when a player walks within 8 blocks of a villager (after the
     * per-player cooldown has expired), the villager MAY emit a short context-aware greeting
     * in chat. Greeting is probabilistic, not guaranteed:
     *  - base chance ({@code greeting_base_probability}) when few other conversable targets exist;
     *  - diluted by the number of nearby villagers/players (a lone player gets the full base,
     *    but a crowded village rarely singles you out — so villagers "talk among themselves");
     *  - decays per prior meeting with this player (repeat greetings get rarer).
     * The scan itself runs at {@code greeting_scan_interval} ticks.
     */
    public static void tickGreeting(World world) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) return;
        if (!ModConfig.ENABLE_VILLAGER_CHAT.get()) return;
        if (!(world instanceof ServerWorld)) return;

        long gameTime = world.getGameTime();
        if (gameTime % ModConfig.GREETING_SCAN_INTERVAL.get() != 0) return;

        ServerWorld serverWorld = (ServerWorld) world;

        for (VillagerAgentData agent : agents.values()) {
            VillagerEntity villager = findVillagerEntity(world, agent.getVillagerId());
            if (villager == null) continue;

            for (ServerPlayerEntity player : serverWorld.getServer().getPlayerList().getPlayers()) {
                if (player.distanceToSqr(villager) > GREETING_RANGE_SQ) continue;

                long lastGreeted = agent.getLastGreetedPlayer()
                        .getOrDefault(player.getUUID(), -GREETING_COOLDOWN_TICKS - 1L);
                if (gameTime - lastGreeted < GREETING_COOLDOWN_TICKS) continue;

                // ── Probability gate ──
                int targets = countConversableTargets(serverWorld, villager, agent);
                int meets   = agent.getGreetCount().getOrDefault(player.getUUID(), 0);
                double p = ModConfig.GREETING_BASE_PROBABILITY.get();
                p = p / (1.0 + (targets - 1) * ModConfig.GREETING_TARGET_DILUTION.get());
                p *= Math.pow(ModConfig.GREETING_FAMILIARITY_DECAY.get(), meets);
                p = Math.min(0.85, Math.max(0.03, p));
                if (RANDOM.nextDouble() >= p) {
                    // Skip this time — leave the cooldown untouched so it re-rolls on the next scan.
                    continue;
                }

                // Mark immediately to prevent concurrent calls + record familiarity
                agent.getLastGreetedPlayer().put(player.getUUID(), gameTime);
                agent.getGreetCount().put(player.getUUID(), meets + 1);

                // Snapshot context for the async call
                final String pName    = player.getName().getString();
                final String env      = agent.getEnvironmentSummary();
                final String activity = agent.getCurrentActivity() != null
                        ? agent.getCurrentActivity() : "idle";
                final String needsMood = VillagerNeedsSystem.buildNeedsDescription(agent);

                String sysPrompt = "You are a Minecraft villager. When a player walks up to you, "
                        + "greet them with a single short sentence (10–20 words), in character, "
                        + "reflecting what you are doing and how you feel. No quotation marks.";
                String userPrompt = "Name: " + agent.getName()
                        + "\nProfession: " + agent.getProfession()
                        + "\nPersonality: " + agent.getPersonality()
                        + "\nMood: " + agent.getMood().name().toLowerCase()
                        + (needsMood.isEmpty() ? "" : " (" + needsMood + ")")
                        + "\nCurrent activity: " + activity
                        + (env != null ? "\nEnvironment: " + env : "")
                        + "\nA player named " + pName + " just walked up to you. Greet them:";

                final VillagerEntity finalVillager = villager;
                LLMService.queryLLM(sysPrompt, userPrompt).thenAccept(greeting -> {
                    if (greeting == null || greeting.trim().isEmpty()) return;
                    String trimmed = greeting.trim();

                    // Broadcast in gold to nearby players as spoken dialogue
                    StringTextComponent msg = new StringTextComponent(
                            "§e" + agent.getName() + ": §f" + trimmed);
                    for (ServerPlayerEntity nearby : serverWorld.getServer().getPlayerList().getPlayers()) {
                        if (nearby.distanceToSqr(finalVillager) <= THOUGHT_PLAYER_RANGE_SQ) {
                            nearby.sendMessage(msg, net.minecraft.util.Util.NIL_UUID);
                        }
                    }

                    agent.addMemory("Greeted player " + pName + ": " + trimmed);
                    LOGGER.debug("{} greeted {}: {}", agent.getName(), pName, trimmed);
                }).exceptionally(e -> {
                    LOGGER.warn("Greeting LLM call failed for {}: {}", agent.getName(), e.getMessage());
                    return null;
                });

                break; // one greeting per villager per scan tick (serve one player at a time)
            }
        }
    }

    /**
     * Count nearby "conversable targets" around a villager — other agent villagers plus all
     * players within {@link #GREETING_AWARENESS_SQ}. Used to dilute the greeting probability so a
     * villager in a crowd is less likely to single out the player (and a lone player gets the full base).
     */
    private static int countConversableTargets(ServerWorld world, VillagerEntity villager, VillagerAgentData self) {
        int n = 0;
        for (ServerPlayerEntity p : world.getServer().getPlayerList().getPlayers()) {
            if (p.distanceToSqr(villager) <= GREETING_AWARENESS_SQ) n++;
        }
        for (VillagerAgentData other : agents.values()) {
            if (other == self) continue;
            VillagerEntity ve = findVillagerEntity(world, other.getVillagerId());
            if (ve != null && ve.distanceToSqr(villager) <= GREETING_AWARENESS_SQ) n++;
        }
        return n;
    }

    /**
     * Farmer-specific automatic actions — state machine that runs every few ticks.
     *
     * States:
     * 1. **Walking** — an action is in progress (walking to a block). Continue it.
     * 2. **Cooldown** — the villager just finished a farming session and is resting.
     *    Tick down the cooldown; do nothing else.
     * 3. **Farming** — the villager is actively working an area. Scan 360° for the
     *    next block to harvest/plant. If nothing left → exit + start cooldown.
     * 4. **Idle** — the villager is wandering. Every ~10 s on average (random roll)
     *    it glances at its forward cone. If it spots work → enter farming state.
     */
    private static void performFarmerActions(VillagerEntity villager, ServerWorld world, VillagerAgentData agent) {
        // ── 1. Walking to a target block — continue the action ──
        VillagerAction current = agent.getCurrentAction();
        if (current != null && isFarmingAction(current)) {
            continueFarmingAction(villager, world, agent, current);
            return;
        }

        // ── 2. Cooldown after a farming session ──
        if (agent.isOnFarmingCooldown()) {
            agent.tickFarmingCooldown(FARMING_TICK_INTERVAL);
            return; // resting — do nothing
        }

        // ── 3. In farming state — scan 360° for the next reachable block ──
        if (agent.isInFarmingState()) {
            BlockPos villagerPos = villager.blockPosition();

            // Priority 1: harvest mature crops (try nearest reachable)
            BlockPos cropTarget = findFirstReachable(villager,
                    FarmingAction.findMatureCropsSorted(world, villagerPos));
            if (cropTarget != null) {
                startFarmingAction(villager, agent, VillagerAction.ActionType.HARVEST,
                        "Harvesting area", cropTarget);
                return;
            }

            // Priority 2: plant seeds on empty farmland
            if (FarmingAction.hasSeeds(agent)) {
                BlockPos farmlandTarget = findFirstReachable(villager,
                        FarmingAction.findEmptyFarmlandSorted(world, villagerPos));
                if (farmlandTarget != null) {
                    startFarmingAction(villager, agent, VillagerAction.ActionType.GROW,
                            "Planting area", farmlandTarget);
                    return;
                }
            }

            // Nothing reachable — exit farming state, start cooldown
            exitFarmingState(agent);
            return;
        }

        // ── 4. Idle — random chance to glance at forward cone ──
        if (RANDOM.nextDouble() > FARMING_SCAN_CHANCE) {
            return; // not looking this tick
        }

        BlockPos villagerPos = villager.blockPosition();
        float headYaw = villager.yHeadRot;

        // Check forward cone for anything to do
        BlockPos cropTarget = FarmingAction.findNearestMatureCrop(world, villagerPos, headYaw);
        if (cropTarget != null) {
            enterFarmingState(agent);
            startFarmingAction(villager, agent, VillagerAction.ActionType.HARVEST,
                    "Noticed crops — starting harvest", cropTarget);
            return;
        }

        if (FarmingAction.hasSeeds(agent)) {
            BlockPos farmlandTarget = FarmingAction.findNearestEmptyFarmland(world, villagerPos, headYaw);
            if (farmlandTarget != null) {
                enterFarmingState(agent);
                startFarmingAction(villager, agent, VillagerAction.ActionType.GROW,
                        "Noticed farmland — starting planting", farmlandTarget);
                return;
            }
        }
    }

    /** Enter farming state — the villager commits to working the area. */
    private static void enterFarmingState(VillagerAgentData agent) {
        agent.setInFarmingState(true);
        agent.setCurrentActivity("farming");
        LOGGER.debug(agent.getName() + " entered farming state");
    }

    /** Exit farming state and start a cooldown before the next scan cycle. */
    private static void exitFarmingState(VillagerAgentData agent) {
        agent.setInFarmingState(false);
        int cooldown = FARMING_COOLDOWN_MIN_TICKS
                + RANDOM.nextInt(FARMING_COOLDOWN_MAX_TICKS - FARMING_COOLDOWN_MIN_TICKS + 1);
        agent.setFarmingCooldownTicks(cooldown);
        agent.setCurrentActivity("idle");
        LOGGER.debug(agent.getName() + " finished farming — cooldown " + cooldown + " ticks");
    }

    /**
     * Given a list of candidate BlockPos (sorted nearest-first), return the first
     * one the villager can actually path to, or null if none are reachable.
     * Uses Minecraft's built-in A* pathfinder — cheap for short distances.
     */
    private static BlockPos findFirstReachable(VillagerEntity villager, List<BlockPos> candidates) {
        for (BlockPos pos : candidates) {
            Path path = villager.getNavigation().createPath(pos, 1);
            if (path != null) {
                return pos;
            }
        }
        return null;
    }

    private static boolean isFarmingAction(VillagerAction action) {
        return action.getActionType() == VillagerAction.ActionType.HARVEST
            || action.getActionType() == VillagerAction.ActionType.GROW;
    }

    /**
     * Create a new farming action, set the target block, and start walking.
     * Also equips a hoe from inventory if available.
     */
    private static void startFarmingAction(VillagerEntity villager, VillagerAgentData agent,
                                            VillagerAction.ActionType type, String desc, BlockPos target) {
        VillagerAction action = new VillagerAction(type, desc);
        action.setTargetBlockPos(target);
        action.setPhase(VillagerAction.ActionPhase.WALKING);
        agent.setCurrentAction(action);

        // Keep "farming" as the activity while in farming state
        if (!agent.isInFarmingState()) {
            agent.setCurrentActivity(type == VillagerAction.ActionType.HARVEST ? "harvesting" : "planting");
        }

        // Equip a hoe from inventory for visual display
        equipHoe(villager, agent);

        // Tell the villager to walk toward the target block
        villager.getNavigation().moveTo(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);

        LOGGER.debug(agent.getName() + " [" + desc + "] — walking to " + target);
    }

    /**
     * Find a hoe in the agent's inventory and equip it in the villager's main hand.
     */
    private static void equipHoe(VillagerEntity villager, VillagerAgentData agent) {
        AgentInventory inv = agent.getInventory();
        for (int i = 0; i < inv.getItems().size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof HoeItem) {
                villager.setItemSlot(EquipmentSlotType.MAINHAND, stack.copy());
                return;
            }
        }
    }

    /**
     * Clear the villager's main hand (e.g. when talking to a player).
     */
    public static void equipEmptyHand(VillagerEntity villager) {
        villager.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
    }

    /**
     * Continue an in-progress farming action: check distance, act or keep walking.
     *
     * Vanilla villager brain activities (WORK, IDLE, MEET, etc.) continuously
     * issue their own navigation commands that override ours.  To prevent the
     * villager from wandering away mid-farm we:
     *   1. Stop the current vanilla navigation path every call.
     *   2. Re-issue our own moveTo every call (every FARMING_TICK_INTERVAL ticks).
     */
    private static void continueFarmingAction(VillagerEntity villager, ServerWorld world,
                                               VillagerAgentData agent, VillagerAction action) {
        // ── Handle WAITING phase (post-harvest, waiting for seeds) ──
        if (action.getPhase() == VillagerAction.ActionPhase.WAITING) {
            handlePendingReplant(villager, world, agent, action);
            return;
        }

        BlockPos target = action.getTargetBlockPos();
        if (target == null) {
            agent.setCurrentAction(null);
            return;
        }

        BlockPos villagerPos = villager.blockPosition();
        double distSq = villagerPos.distSqr(target);

        // Check if we've arrived (within 1 block)
        if (distSq <= FarmingAction.INTERACT_RANGE_SQ) {
            action.setPhase(VillagerAction.ActionPhase.ACTING);
            performFarmingActionAtBlock(villager, world, agent, action, target);
            // If the action transitioned to WAITING (harvest → pending replant),
            // don't clear it — let the waiting handler run on subsequent ticks.
            if (action.getPhase() != VillagerAction.ActionPhase.WAITING) {
                agent.setCurrentAction(null);
            }
            return;
        }

        // Still walking — check if stuck
        action.incrementStuckTicks();
        if (action.getStuckTicks() > FarmingAction.STUCK_TIMEOUT_TICKS) {
            LOGGER.debug(agent.getName() + " gave up reaching " + target + " (stuck)");
            agent.setCurrentAction(null);
            if (!agent.isInFarmingState()) {
                agent.setCurrentActivity("idle");
            }
            return;
        }

        // Re-issue navigation command periodically in case path was interrupted.
        // Vanilla brain is suppressed by VillagerEntityMixin while the agent is active,
        // so we only need to re-issue occasionally for normal pathfinding recovery.
        if (action.getStuckTicks() % 20 == 0) {
            villager.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
        }
    }

    /**
     * The villager has arrived at the target block — perform the actual action.
     */
    private static void performFarmingActionAtBlock(VillagerEntity villager, ServerWorld world,
                                                     VillagerAgentData agent, VillagerAction action,
                                                     BlockPos target) {
        switch (action.getActionType()) {
            case HARVEST:
                // Verify the crop is still there and mature
                if (FarmingAction.isMatureCrop(world, target)) {
                    // Remember which crop was here before harvesting
                    net.minecraft.block.Block harvestedCrop = FarmingAction.harvestBlockAt(villager, world, agent, target);
                    if (harvestedCrop != null) {
                        BlockPos farmlandPos = target.below();
                        // Try to replant immediately if we already have the seed
                        if (FarmingAction.plantSpecificCropAt(villager, world, agent, farmlandPos, harvestedCrop)) {
                            // Replanted same crop right away — done
                        } else if (FarmingAction.isEmptyFarmland(world, farmlandPos)) {
                            // Don't have the seed yet — wait for item pickup
                            action.setPendingReplant(harvestedCrop, farmlandPos);
                            action.setPhase(VillagerAction.ActionPhase.WAITING);
                            LOGGER.debug(agent.getName() + " harvested, waiting for seeds to replant "
                                    + harvestedCrop.getRegistryName());
                        }
                    }
                } else {
                    LOGGER.debug(agent.getName() + " arrived but crop at " + target + " is gone");
                }
                break;
            case GROW:
                // Plant on empty farmland: prefer same crop as adjacent blocks, else random
                if (FarmingAction.isEmptyFarmland(world, target)) {
                    FarmingAction.plantSmartAt(villager, world, agent, target);
                } else {
                    LOGGER.debug(agent.getName() + " arrived but farmland at " + target + " is occupied");
                }
                break;
            default:
                break;
        }
    }

    /**
     * Maximum ticks to wait for seeds after harvesting before giving up.
     * At FARMING_TICK_INTERVAL=3, 20 checks ≈ 60 ticks ≈ 3 seconds.
     * Items should be attracted and picked up well within this time.
     */
    private static final int REPLANT_WAIT_MAX_TICKS = 20;

    /**
     * Handle the WAITING phase after a harvest: wait for the villager to pick up
     * the dropped seeds, then replant the same crop. If seeds don't arrive in time,
     * fall back to smart planting (match neighbors or random).
     */
    private static void handlePendingReplant(VillagerEntity villager, ServerWorld world,
                                              VillagerAgentData agent, VillagerAction action) {
        net.minecraft.block.Block crop = action.getPendingReplantCrop();
        BlockPos farmlandPos = action.getPendingReplantPos();

        if (crop == null || farmlandPos == null) {
            // No pending replant — just finish
            agent.setCurrentAction(null);
            return;
        }

        // Check if the farmland is still empty (another villager might have planted)
        if (!FarmingAction.isEmptyFarmland(world, farmlandPos)) {
            agent.setCurrentAction(null);
            return;
        }

        action.incrementPendingReplantWaitTicks();

        // Try to replant the same crop
        if (FarmingAction.plantSpecificCropAt(villager, world, agent, farmlandPos, crop)) {
            // Successfully replanted the same crop — done
            agent.setCurrentAction(null);
            return;
        }

        // Seeds haven't arrived yet — check if we've waited long enough
        if (action.getPendingReplantWaitTicks() >= REPLANT_WAIT_MAX_TICKS) {
            // Timeout — fall back to smart planting (match neighbors or random)
            LOGGER.debug(agent.getName() + " timed out waiting for " + crop.getRegistryName()
                    + " seeds, falling back to smart plant");
            FarmingAction.plantSmartAt(villager, world, agent, farmlandPos);
            agent.setCurrentAction(null);
        }
        // else: keep waiting — will be called again next FARMING_TICK_INTERVAL
    }

    /**
     * Check if villager is at their job block and should restock
     * Mimics vanilla Minecraft behavior where villagers restock at their workstation
     */
    private static void checkJobBlockRestock(VillagerEntity villager, VillagerAgentData agent) {
        // Get the villager's job site from their brain memory
        Optional<GlobalPos> jobSiteOptional = villager.getBrain()
            .getMemory(MemoryModuleType.JOB_SITE);

        if (!jobSiteOptional.isPresent()) {
            return; // No job site assigned
        }

        GlobalPos jobSite = jobSiteOptional.get();
        BlockPos jobBlockPos = jobSite.pos();
        BlockPos villagerPos = villager.blockPosition();

        // Check if villager is within 2 blocks of their job site
        double distance = villagerPos.distSqr(jobBlockPos);
        if (distance <= 4.0) { // 2 blocks squared
            // Check if enough time has passed since last restock (once per Minecraft day = 24000 ticks)
            long currentTime = villager.level.getGameTime();
            long lastRestockTime = agent.getLastRestockTime();

            // Restock once per Minecraft day (24000 ticks)
            if (currentTime - lastRestockTime >= 24000) {
                agent.restockAtJobBlock();
                agent.setLastRestockTime(currentTime);
                LOGGER.info("Villager " + agent.getName() + " restocked at job block");
            }
        }
    }

    /**
     * Find a villager entity in the world by UUID
     */
    private static VillagerEntity findVillagerEntity(World world, UUID villagerId) {
        // In Minecraft 1.16.5, we need to use ServerWorld to get entities
        if (world instanceof ServerWorld) {
            ServerWorld serverWorld = (ServerWorld) world;
            Entity entity = serverWorld.getEntity(villagerId);
            if (entity instanceof VillagerEntity) {
                return (VillagerEntity) entity;
            }
        }
        return null;
    }

    /**
     * Process the agent's current goals
     */
    private static void processGoals(VillagerEntity villager, VillagerAgentData agent) {
        List<AgentGoal> goals = agent.getGoals();
        if (goals.isEmpty()) return;

        // Sort by priority (highest first)
        goals.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

        AgentGoal currentGoal = goals.get(0);

        // Execute goal based on type
        switch (currentGoal.getGoalType()) {
            case "gather":
                executeGatherGoal(villager, agent, currentGoal);
                break;
            case "craft":
                executeCraftGoal(villager, agent, currentGoal);
                break;
            case "trade":
                executeTradeGoal(villager, agent, currentGoal);
                break;
            case "socialize":
                executeSocializeGoal(villager, agent, currentGoal);
                break;
            default:
                LOGGER.warn("Unknown goal type: " + currentGoal.getGoalType());
        }

        // Remove completed goals
        goals.removeIf(AgentGoal::isCompleted);
    }

    private static void executeGatherGoal(VillagerEntity villager, VillagerAgentData agent, AgentGoal goal) {
        // For farmer villagers gathering crops, the farming walk-then-act system
        // handles this automatically via performFarmerActions. Just log intent.
        agent.addMemory("Trying to gather " + goal.getTargetItem());
    }

    private static void executeCraftGoal(VillagerEntity villager, VillagerAgentData agent, AgentGoal goal) {
        // TODO: Implementation for crafting items
        agent.addMemory("Tried to craft " + goal.getTargetItem());
    }

    private static void executeTradeGoal(VillagerEntity villager, VillagerAgentData agent, AgentGoal goal) {
        // TODO: Implementation for trading with players or other villagers
        agent.addMemory("Looking for trading opportunities");
    }

    private static void executeSocializeGoal(VillagerEntity villager, VillagerAgentData agent, AgentGoal goal) {
        // TODO: Implementation for villager-to-villager interaction
        agent.addMemory("Socializing with other villagers");
    }

    /**
     * Check if we should generate new goals for this agent
     */
    private static boolean shouldGenerateNewGoals(VillagerAgentData agent) {
        List<AgentGoal> goals = agent.getGoals();
        if (goals.isEmpty()) return true;

        // Generate new goals if current goals are old (5 minutes)
        long currentTime = System.currentTimeMillis();
        for (AgentGoal goal : goals) {
            if (currentTime - goal.getCreatedTime() > 300000) {
                return true;
            }
        }

        return false;
    }

    /**
     * Generate new goals for the agent using simple logic
     * TODO: Integrate with LLM for more intelligent goal generation
     */
    private static void generateNewGoals(VillagerEntity villager, VillagerAgentData agent) {
        // For now, generate simple random goals
        // Later this will use LLM to generate contextual, personality-driven goals

        Random random = new Random();
        String[] goalTypes = {"gather", "craft", "trade", "socialize"};
        String[] gatherItems = {"wheat", "carrots", "potatoes", "wood", "stone"};
        String[] craftItems = {"bread", "tools", "armor"};

        String goalType = goalTypes[random.nextInt(goalTypes.length)];
        AgentGoal newGoal;

        switch (goalType) {
            case "gather":
                String item = gatherItems[random.nextInt(gatherItems.length)];
                newGoal = new AgentGoal("gather", "Gather " + item, random.nextInt(5) + 3);
                newGoal.setTargetItem(item);
                newGoal.setTargetQuantity(random.nextInt(10) + 5);
                break;
            case "craft":
                String craftItem = craftItems[random.nextInt(craftItems.length)];
                newGoal = new AgentGoal("craft", "Craft " + craftItem, random.nextInt(5) + 3);
                newGoal.setTargetItem(craftItem);
                break;
            case "trade":
                newGoal = new AgentGoal("trade", "Look for trading opportunities", random.nextInt(5) + 3);
                break;
            case "socialize":
                newGoal = new AgentGoal("socialize", "Talk with other villagers", random.nextInt(3) + 1);
                break;
            default:
                return;
        }

        agent.getGoals().add(newGoal);
        agent.addMemory("New goal: " + newGoal.getDescription());
        LOGGER.debug("Generated new goal for " + agent.getName() + ": " + newGoal.getDescription());
    }

    /**
     * Get all agents (for debugging/admin purposes)
     */
    public static Collection<VillagerAgentData> getAllAgents() {
        return agents.values();
    }

    /** Find the agent whose villager is closest to a world position (for command targeting). */
    public static VillagerAgentData getNearestAgent(World world, double x, double y, double z) {
        VillagerAgentData best = null;
        double bestD = Double.MAX_VALUE;
        for (VillagerAgentData a : agents.values()) {
            VillagerEntity v = findVillagerEntity(world, a.getVillagerId());
            if (v == null || !v.isAlive()) continue;
            double dx = v.getX() - x, dy = v.getY() - y, dz = v.getZ() - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    /**
     * Get the number of active agents
     */
    public static int getAgentCount() {
        return agents.size();
    }
}

