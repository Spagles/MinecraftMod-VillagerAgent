package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.monster.*;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ToolItem;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Handles combat actions for villagers: detecting hostile mobs and attacking them.
 *
 * <p>Villagers will:
 * <ul>
 *   <li>Scan for hostile mobs within {@link #SCAN_RANGE} blocks</li>
 *   <li>Walk toward the nearest threat</li>
 *   <li>Attack when within {@link #ATTACK_RANGE_SQ} (melee range)</li>
 *   <li>Deal damage based on held weapon (or bare-hand)</li>
 *   <li>Play attack sounds and swing arm animation</li>
 * </ul>
 */
public class CombatAction {
    private static final Logger LOGGER = LogManager.getLogger();

    /** How far the villager can detect hostile mobs (blocks). */
    public static final double SCAN_RANGE = 6.0;

    /** Melee attack range squared. ~2.25 = 1.5 blocks. */
    public static final double ATTACK_RANGE_SQ = 2.25;

    /** Minimum ticks between attacks (10 ticks = 0.5 seconds, like player). */
    public static final int ATTACK_COOLDOWN_TICKS = 10;

    /** If the villager can't reach the target in this many ticks, give up. */
    public static final int CHASE_TIMEOUT_TICKS = 200; // ~10 seconds

    /** Base damage with bare hands. */
    private static final float BASE_DAMAGE = 1.0F;

    // ---------------------------------------------------------------
    //  Target scanning
    // ---------------------------------------------------------------

    /**
     * Find the nearest hostile mob within scan range.
     * Also considers entities that recently attacked the villager (revenge target).
     */
    public static LivingEntity findNearestThreat(VillagerEntity villager, ServerWorld world) {
        AxisAlignedBB scanBox = villager.getBoundingBox().inflate(SCAN_RANGE);
        List<LivingEntity> entities = world.getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> e.isAlive() && isHostile(e, villager));

        if (entities.isEmpty()) return null;

        // Sort by distance, pick closest
        entities.sort(Comparator.comparingDouble(villager::distanceToSqr));
        return entities.get(0);
    }

    /**
     * Check whether an entity is considered hostile to the villager.
     * Includes all vanilla hostile mobs that target villagers.
     */
    public static boolean isHostile(LivingEntity entity, VillagerEntity villager) {
        if (entity == villager) return false;
        if (!entity.isAlive()) return false;

        // Standard hostile mobs
        if (entity instanceof ZombieEntity) return true;
        if (entity instanceof SkeletonEntity) return true;
        if (entity instanceof VindicatorEntity) return true;
        if (entity instanceof EvokerEntity) return true;
        if (entity instanceof PillagerEntity) return true;
        if (entity instanceof RavagerEntity) return true;
        if (entity instanceof VexEntity) return true;
        if (entity instanceof WitchEntity) return true;
        if (entity instanceof CreeperEntity) return false; // Don't melee creepers!

        // Revenge: if something attacked us, fight back (except players and iron golems)
        LivingEntity lastHurt = villager.getLastHurtByMob();
        if (lastHurt != null && lastHurt.equals(entity)
                && !(entity instanceof IronGolemEntity)
                && !(entity instanceof VillagerEntity)) {
            return true;
        }

        return false;
    }

    // ---------------------------------------------------------------
    //  Attack execution
    // ---------------------------------------------------------------

    /**
     * Perform a melee attack on the target entity.
     * Calculates damage from held weapon, swings arm, plays sound.
     *
     * @return true if the attack was performed
     */
    public static boolean attackEntity(VillagerEntity villager, LivingEntity target,
                                        VillagerAgentData agent) {
        if (target == null || !target.isAlive()) return false;

        double distSq = villager.distanceToSqr(target);
        if (distSq > ATTACK_RANGE_SQ) return false;

        // Calculate damage and equip best weapon visually
        float damage = findBestWeaponDamage(agent, villager);

        // Look at target
        villager.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // Deal damage
        target.hurt(DamageSource.mobAttack(villager), damage);

        // Swing arm animation
        villager.swing(Hand.MAIN_HAND);

        // Play attack sound
        villager.level.playSound(null,
                villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.PLAYER_ATTACK_STRONG, SoundCategory.NEUTRAL,
                1.0F, 1.0F);

        // Knockback
        double kbX = target.getX() - villager.getX();
        double kbZ = target.getZ() - villager.getZ();
        double kbLen = Math.sqrt(kbX * kbX + kbZ * kbZ);
        if (kbLen > 0) {
            target.push(kbX / kbLen * 0.4, 0.1, kbZ / kbLen * 0.4);
        }

        LOGGER.debug("{} attacked {} for {} damage",
                agent.getName(), target.getType().getRegistryName(), damage);
        return true;
    }

    /**
     * Scan the entire inventory for the best weapon (highest damage) and return its damage.
     * Considers swords, axes, and other tool items (pickaxes, shovels, hoes).
     * Also equips the best weapon in the villager's main hand so it is visually displayed.
     */
    private static float calculateDamage(VillagerAgentData agent) {
        return findBestWeaponDamage(agent, null);
    }

    /**
     * Scan the entire inventory for the best weapon. If a villager entity is provided,
     * equip the best weapon in its main hand for visual display.
     *
     * @return the damage value of the best weapon, or BASE_DAMAGE if no weapon found
     */
    public static float findBestWeaponDamage(VillagerAgentData agent, VillagerEntity villager) {
        float bestDamage = BASE_DAMAGE;
        ItemStack bestWeapon = ItemStack.EMPTY;

        AgentInventory inv = agent.getInventory();
        for (int i = 0; i < inv.getItems().size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            float dmg = 0;
            if (stack.getItem() instanceof SwordItem) {
                dmg = ((SwordItem) stack.getItem()).getDamage() + 1.0F;
            } else if (stack.getItem() instanceof AxeItem) {
                dmg = ((AxeItem) stack.getItem()).getAttackDamage() + 1.0F;
            } else if (stack.getItem() instanceof ToolItem) {
                // Pickaxes, shovels, hoes — lower priority but still better than fists
                dmg = ((ToolItem) stack.getItem()).getAttackDamage() + 1.0F;
            }

            if (dmg > bestDamage) {
                bestDamage = dmg;
                bestWeapon = stack;
            }
        }

        // Equip the best weapon visually in the villager's main hand
        if (villager != null) {
            villager.setItemSlot(EquipmentSlotType.MAINHAND, bestWeapon.isEmpty() ? ItemStack.EMPTY : bestWeapon.copy());
        }

        return bestDamage;
    }

    /**
     * Get the UUID of a target entity (for tracking across ticks).
     */
    public static UUID getTargetId(LivingEntity target) {
        return target != null ? target.getUUID() : null;
    }
}
