package com.github.AaronAA0721.villageragent.mixin;

import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into VillagerEntity to suppress vanilla brain AI when our agent system
 * is actively controlling the villager.
 *
 * <p>{@code customServerAiStep()} is the method called every server tick that:
 * <ol>
 *   <li>Ticks the Brain (runs all behavior tasks for CORE, WORK, IDLE, MEET, REST, etc.)</li>
 *   <li>Updates the schedule (switches activities based on time of day)</li>
 *   <li>Runs vanilla farmer harvesting, villager socializing, pathfinding to beds/job sites, etc.</li>
 * </ol>
 *
 * <p>When our agent has an active task (farming, crafting, socializing, etc.) we cancel
 * this method entirely so vanilla behaviors don't fight with our navigation and actions.
 * When the agent is truly idle, we let vanilla run so the villager wanders, sleeps, and
 * behaves naturally.
 *
 * <p>As we implement more custom systems (sleeping, socializing, combat), we can expand
 * the "agent is active" condition until we eventually always suppress vanilla.
 *
 * <h3>Vanilla activities suppressed when active:</h3>
 * <ul>
 *   <li><b>CORE</b> — MoveToTargetSink, LookAtTargetSink, AcquirePoi, ValidateNearbyPoi</li>
 *   <li><b>WORK</b> — StrollAroundPoi, StrollToPoi, WorkAtPoi, HarvestFarmland, UseBonemeal</li>
 *   <li><b>IDLE</b> — StrollAroundPoi, SetWalkTargetFromBlockMemory, InteractWith, DoNothing</li>
 *   <li><b>MEET</b> — StrollToPoiList, SocializeAtBell, InteractWith (gossip)</li>
 *   <li><b>REST</b> — SetWalkTargetFromBlockMemory (walk to bed), SleepInBed, InsideBrownianWalk</li>
 *   <li><b>PANIC</b> — SetWalkTargetAwayFrom (flee from threats)</li>
 *   <li><b>PRE_RAID/RAID/HIDE</b> — raid-related hiding and celebration</li>
 * </ul>
 */
@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {

    /**
     * Intercept the vanilla brain tick. If our agent system is actively controlling
     * this villager, cancel the entire method so vanilla AI doesn't interfere.
     */
    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void villageragent$onCustomServerAiStep(CallbackInfo ci) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) {
            return; // Mod disabled — let vanilla run
        }

        VillagerEntity self = (VillagerEntity) (Object) this;
        VillagerAgentData agent = VillagerAgentManager.getAgent(self.getUUID());

        if (agent == null) {
            return; // No agent data — let vanilla run
        }

        // Suppress vanilla brain when our agent is actively doing something.
        // This prevents vanilla WORK/IDLE/MEET/REST activities from issuing
        // competing navigation commands.
        if (isAgentActive(agent)) {
            ci.cancel();
        }
    }

    /**
     * Determine whether our agent system is actively controlling this villager.
     * When true, vanilla brain is suppressed.
     *
     * <p>Currently active when:
     * <ul>
     *   <li>The agent has a current action (farming, crafting, walking to target, etc.)</li>
     *   <li>The agent is in farming state (scanning for crops, between individual harvests)</li>
     *   <li>The agent is on farming cooldown (brief rest between farming sessions)</li>
     * </ul>
     *
     * <p>As more systems are added (socializing, trading, sleeping), expand this method.
     */
    private static boolean isAgentActive(VillagerAgentData agent) {
        // Has an in-progress action (walking to crop, harvesting, planting, crafting, etc.)
        if (agent.getCurrentAction() != null) {
            return true;
        }

        // In farming state — actively scanning for next crop/farmland
        if (agent.isInFarmingState()) {
            return true;
        }

        // On farming cooldown — brief rest, don't let vanilla yank the villager away
        if (agent.isOnFarmingCooldown()) {
            return true;
        }

        return false;
    }

    /**
     * Prevent vanilla from picking up items into the hidden 8-slot vanilla inventory
     * when our agent system is active.
     *
     * <p>Without this, vanilla {@code VillagerEntity.pickUpItem()} (called from
     * {@code MobEntity.serverAiStep()}, which is separate from {@code customServerAiStep()})
     * grabs crop drops (wheat, seeds, carrots, potatoes, etc.) into the vanilla inventory.
     * Those items become invisible to our {@code AgentInventory} — they appear "lost".
     *
     * <p>By returning false here, all item pickup goes through our
     * {@code ItemAttractionSystem} into the {@code AgentInventory} instead.
     */
    @Inject(method = "wantsToPickUp", at = @At("HEAD"), cancellable = true)
    private void villageragent$onWantsToPickUp(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!ModConfig.ENABLE_AI_AGENTS.get()) {
            return;
        }

        VillagerEntity self = (VillagerEntity) (Object) this;
        VillagerAgentData agent = VillagerAgentManager.getAgent(self.getUUID());

        if (agent != null) {
            // Always block vanilla pickup for managed villagers.
            // Our ItemAttractionSystem handles all item collection.
            cir.setReturnValue(false);
        }
    }
}

