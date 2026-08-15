package com.github.AaronAA0721.villageragent.ai.vision;

import com.github.AaronAA0721.villageragent.ai.memory.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Samples a chunk's contents when a villager first enters it.
 *
 * <p>Strategy (design doc §3.2): scan a thin vertical slab — the whole 16x16 horizontal
 * plane but only Y in [entryY-3, entryY+4] (8 layers around standing height). The scan
 * itself touches 2048 blocks, but <b>nothing per-block is retained</b>: results collapse
 * immediately into
 * <ul>
 *   <li>coarse {@link BlockCategory} counters,</li>
 *   <li>a {@link ChunkFeature} bitmask + one representative position per feature,</li>
 *   <li>{@link ChunkTag} labels derived from the counters,</li>
 *   <li>entity counts per category (positions are not kept — they go stale instantly).</li>
 * </ul>
 * A sampled chunk therefore costs ~100–200 bytes in memory, no matter whether it is empty
 * plains or a 256-block farm.
 *
 * <p>The scan reuses a single {@link BlockPos.Mutable}, so it allocates nothing per block.
 */
public final class ChunkContentSampler {

    private ChunkContentSampler() {}

    public static ChunkMemory sample(LivingEntity villager, World world, int cx, int cz) {
        ChunkMemory cm = new ChunkMemory(net.minecraft.util.math.ChunkPos.asLong(cx, cz));
        cm.lastVisitedTick = world.getGameTime();

        BlockPos vp = villager.blockPosition();
        int yLo = Math.max(0, vp.getY() - 3);
        int yHi = Math.min(255, vp.getY() + 4);
        int x0 = cx << 4;
        int z0 = cz << 4;

        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = yLo; y <= yHi; y++) {
                    p.set(x0 + lx, y, z0 + lz);
                    BlockState st = world.getBlockState(p);
                    if (st.getMaterial() == Material.AIR) continue;

                    BlockCategory cat = classify(st);
                    if (cat != null) cm.addCount(cat, 1);

                    // Feature bits: only the first sighting stores a position, the rest are free.
                    ChunkFeature f = featureOf(st);
                    if (f != null && !cm.has(f)) cm.recordFeature(f, lx, y, lz);
                }
            }
        }

        deriveTags(cm);
        sampleEntities(villager, world, cm);
        computeSaliency(cm);
        return cm;
    }

    // ── Block classification ──────────────────────────────────────────────

    /** Map a BlockState to a coarse category, or null for air/ignored blocks. */
    static BlockCategory classify(BlockState s) {
        if (s.getMaterial() == Material.AIR) return null;

        if (s.is(BlockTags.LOGS))            return BlockCategory.WOOD_LOG;
        if (s.is(BlockTags.LEAVES))          return BlockCategory.LEAVES;
        if (s.getBlock() == Blocks.DIRT
                || s.getBlock() == Blocks.COARSE_DIRT
                || s.getBlock() == Blocks.PODZOL
                || s.getBlock() == Blocks.GRASS_BLOCK
                || s.getBlock() == Blocks.GRASS_PATH) return BlockCategory.DIRT;
        if (s.getBlock() == Blocks.SAND
                || s.getBlock() == Blocks.SANDSTONE
                || s.getBlock() == Blocks.RED_SAND) return BlockCategory.SAND;
        if (oreFeature(s.getBlock()) != null) return BlockCategory.ORE;
        if (s.getFluidState().is(FluidTags.WATER)) return BlockCategory.WATER;
        if (s.getBlock() == Blocks.LAVA
                || s.getFluidState().is(FluidTags.LAVA)) return BlockCategory.OTHER; // lava tracked as a feature
        if (s.getBlock() == Blocks.COBBLESTONE
                || s.getBlock() == Blocks.STONE
                || s.is(BlockTags.STONE_BRICKS)) return BlockCategory.STONE;
        if (s.getBlock() == Blocks.FARMLAND || s.is(BlockTags.CROPS)) return BlockCategory.CROP;
        if (isBuildingBlock(s))              return BlockCategory.BUILDING;
        return BlockCategory.OTHER;
    }

    /** The point-of-interest kind this block represents, or null if it is not noteworthy. */
    static ChunkFeature featureOf(BlockState s) {
        Block b = s.getBlock();

        ChunkFeature ore = oreFeature(b);
        if (ore != null) return ore;

        if (b == Blocks.CRAFTING_TABLE) return ChunkFeature.CRAFTING_TABLE;
        if (b == Blocks.FURNACE)        return ChunkFeature.FURNACE;
        if (b == Blocks.CHEST)          return ChunkFeature.CHEST;
        if (b == Blocks.BARREL)         return ChunkFeature.BARREL;
        if (s.is(BlockTags.BEDS))       return ChunkFeature.BED;
        if (s.is(BlockTags.WOODEN_DOORS)) return ChunkFeature.DOOR;
        if (s.is(BlockTags.CROPS))      return ChunkFeature.CROP;
        if (b == Blocks.FARMLAND)       return ChunkFeature.FARMLAND;
        if (s.getFluidState().is(FluidTags.LAVA)) return ChunkFeature.LAVA;
        if (s.getFluidState().is(FluidTags.WATER)) return ChunkFeature.WATER;
        return null;
    }

    private static ChunkFeature oreFeature(Block b) {
        if (b == Blocks.COAL_ORE)     return ChunkFeature.ORE_COAL;
        if (b == Blocks.IRON_ORE)     return ChunkFeature.ORE_IRON;
        if (b == Blocks.GOLD_ORE)     return ChunkFeature.ORE_GOLD;
        if (b == Blocks.DIAMOND_ORE)  return ChunkFeature.ORE_DIAMOND;
        if (b == Blocks.EMERALD_ORE)  return ChunkFeature.ORE_EMERALD;
        if (b == Blocks.REDSTONE_ORE) return ChunkFeature.ORE_REDSTONE;
        if (b == Blocks.LAPIS_ORE)    return ChunkFeature.ORE_LAPIS;
        return null; // COPPER_ORE does not exist in 1.16.5
    }

    /** Artificial blocks that, when numerous, suggest a building / village. */
    private static boolean isBuildingBlock(BlockState s) {
        if (s.is(BlockTags.PLANKS)) return true;
        if (s.is(BlockTags.STONE_BRICKS)) return true;
        if (s.is(BlockTags.WALLS)) return true;
        Block b = s.getBlock();
        return b == Blocks.GLASS || b == Blocks.GLASS_PANE;
    }

    // ── Tag derivation ────────────────────────────────────────────────────

    static void deriveTags(ChunkMemory cm) {
        int wood  = cm.count(BlockCategory.WOOD_LOG) + cm.count(BlockCategory.LEAVES);
        int crop  = cm.count(BlockCategory.CROP);
        int water = cm.count(BlockCategory.WATER);
        int build = cm.count(BlockCategory.BUILDING);

        if (wood  > 30) cm.add(ChunkTag.FOREST);
        if (crop  > 20) cm.add(ChunkTag.FARMLAND);
        if (water >  0) cm.add(ChunkTag.WATER);
        if (build > 40) cm.add(ChunkTag.VILLAGE);
        if (cm.hasAnyOre()) cm.add(ChunkTag.ORE);
        if (cm.has(ChunkFeature.LAVA)) cm.add(ChunkTag.DANGER_LAVA);
    }

    // ── Entity memory (counts only, no positions) ─────────────────────────

    static void sampleEntities(LivingEntity v, World w, ChunkMemory cm) {
        cm.clearEntities();
        cm.entitySeenTick = w.getGameTime();
        AxisAlignedBB box = new AxisAlignedBB(v.blockPosition()).inflate(24); // includes neighbouring chunks
        for (Entity e : w.getEntities(v, box)) { // excludes the villager itself
            cm.addEntity(categorize(e));
        }
        if (cm.entityCount(EntityCategory.HOSTILE) > 0) cm.add(ChunkTag.HOSTILES);
        if (cm.entityCount(EntityCategory.ANIMAL) > 0)  cm.add(ChunkTag.ANIMALS);
    }

    public static EntityCategory categorize(Entity e) {
        if (e instanceof ItemEntity)     return EntityCategory.ITEM;
        if (e instanceof PlayerEntity)   return EntityCategory.PLAYER;
        if (e instanceof VillagerEntity) return EntityCategory.VILLAGER;
        EntityClassification ec = e.getType().getCategory();
        if (ec == EntityClassification.MONSTER) return EntityCategory.HOSTILE;
        if (ec == EntityClassification.CREATURE
                || ec == EntityClassification.WATER_CREATURE
                || ec == EntityClassification.WATER_AMBIENT
                || ec == EntityClassification.AMBIENT) return EntityCategory.ANIMAL;
        return EntityCategory.OTHER;
    }

    // ── Saliency ──────────────────────────────────────────────────────────

    private static void computeSaliency(ChunkMemory cm) {
        float s = 0f;
        if (cm.has(ChunkTag.VILLAGE))     s += 0.5f;
        if (cm.has(ChunkTag.FARMLAND))    s += 0.2f;
        if (cm.has(ChunkTag.ORE))         s += 0.3f;
        if (cm.has(ChunkTag.FOREST))      s += 0.1f;
        if (cm.has(ChunkTag.DANGER_LAVA)) s += 0.15f;
        if (cm.getFeatureFlags() != 0)    s += 0.1f;
        cm.saliency = Math.min(1f, s);
    }
}
