package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.block.Block;
import net.minecraft.tags.FluidTags;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Computes a placement order + a legal standing cell for every block of a structure so that,
 * when the villager places blocks in that order, each block is reachable within 1 block.
 *
 * <p><b>Key idea (shell BFS).</b> Minecraft blocks don't need support, so a block is placeable
 * the moment (a) it has a 26-neighbour that is already solid — a world block or an earlier-placed
 * block (its scaffold), and (b) there is an air standing cell within 1 block of it whose floor is
 * solid. Expanding a flood fill from blocks that touch the existing world guarantees every placed
 * block chains back to the ground, so the villager can always walk to a legal standing cell.
 *
 * <p>{@link #validateStructure} adds static checks (overlap with the existing world, enough blocks
 * in inventory, 26-connectivity) on top of the order pass, producing a report the LLM can fix.
 */
public class BuildOrderPlanner {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final List<BlockPos> NEIGHBOURS = new ArrayList<>();
    static {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0)
                        NEIGHBOURS.add(new BlockPos(dx, dy, dz));
    }

    /** One block of a structure, relative to the anchor. */
    public static class BlockSpec {
        public final int x, y, z;
        public final Block block;
        public BlockSpec(int x, int y, int z, Block block) {
            this.x = x; this.y = y; this.z = z; this.block = block;
        }
    }

    /** A structure: a name + an ordered list of relative blocks. */
    public static class Structure {
        public String name = "structure";
        public String anchorMode = "RELATIVE";  // "RELATIVE" (offset from villager) or "ABSOLUTE"
        public int anchorX = 0, anchorY = 0, anchorZ = 0; // offset (RELATIVE) or world pos (ABSOLUTE)
        public final List<BlockSpec> blocks = new ArrayList<>();
    }

    /** One resolved placement step in world coordinates. */
    public static class Step {
        public final BlockPos target;  // world position of the block
        public final BlockPos stand;   // world position the villager must occupy (feet)
        public final Block block;
        public Step(BlockPos target, BlockPos stand, Block block) {
            this.target = target; this.stand = stand; this.block = block;
        }
    }

    /** Result of validation + ordering. */
    public static class ValidationResult {
        public boolean ok = true;
        public final List<String> errors = new ArrayList<>();
        public final List<BlockPos> unreachable = new ArrayList<>();
        public final List<Step> steps = new ArrayList<>();
    }

    /**
     * Validate a structure and compute its build order.
     *
     * @param world    the server world (used for overlap + reachability checks)
     * @param basePos  world position of the structure's relative origin (0,0,0)
     * @param struct   the structure (relative coords)
     * @param agent    the villager (its inventory is checked for materials)
     */
    public static ValidationResult validateStructure(ServerWorld world, BlockPos basePos,
                                                      Structure struct, VillagerAgentData agent) {
        ValidationResult r = new ValidationResult();

        // ── Resolve relative coords → world coords ──
        Map<BlockPos, Block> toPlace = new LinkedHashMap<>();
        Set<BlockPos> toPlaceKeys = new HashSet<>();
        for (BlockSpec s : struct.blocks) {
            BlockPos wp = basePos.offset(s.x, s.y, s.z);
            if (toPlace.containsKey(wp)) continue; // duplicate cell — keep first
            toPlace.put(wp, s.block);
            toPlaceKeys.add(wp);
        }

        if (toPlace.isEmpty()) {
            r.ok = false;
            r.errors.add("Structure contains no blocks.");
            return r;
        }

        // ── Static check 1: overlap with existing world ──
        for (Map.Entry<BlockPos, Block> e : toPlace.entrySet()) {
            BlockPos wp = e.getKey();
            if (!world.isEmptyBlock(wp)) {
                r.ok = false;
                r.errors.add("Block at " + wp + " overlaps existing "
                        + world.getBlockState(wp).getBlock().getRegistryName() + " — pick a clear spot.");
            }
        }

        // ── Static check 2: enough materials in inventory ──
        Map<Block, Integer> needed = new HashMap<>();
        for (Block b : toPlace.values()) needed.merge(b, 1, Integer::sum);
        for (Map.Entry<Block, Integer> e : needed.entrySet()) {
            int have = agent.getInventory().countItem(new ItemStack(e.getKey()));
            if (have < e.getValue()) {
                r.ok = false;
                r.errors.add("Missing " + (e.getValue() - have) + "x "
                        + e.getKey().getRegistryName() + " in inventory.");
            }
        }

        // ── Order + reachability (shell BFS) ──
        computeOrder(world, basePos, toPlace, toPlaceKeys, r);

        if (!r.unreachable.isEmpty()) {
            r.ok = false;
            StringBuilder sb = new StringBuilder("Cannot reach ");
            sb.append(r.unreachable.size()).append(" block(s): ");
            int shown = 0;
            for (BlockPos u : r.unreachable) {
                if (shown++ >= 5) { sb.append("… "); break; }
                sb.append(u).append(" ");
            }
            sb.append("(structure must connect to the ground / an existing block and not be a floating island).");
            r.errors.add(sb.toString());
        }

        return r;
    }

    private static void computeOrder(ServerWorld world, BlockPos basePos,
                                     Map<BlockPos, Block> toPlace, Set<BlockPos> toPlaceKeys,
                                     ValidationResult r) {
        Set<BlockPos> placed = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();

        // Seeds: blocks touching a real (non-structure) world block → attached to the world.
        for (BlockPos wp : toPlace.keySet()) {
            if (hasWorldSolidNeighbor(world, wp, toPlaceKeys)) frontier.add(wp);
        }

        // Nothing touches the world → the whole thing floats → unreachable.
        if (frontier.isEmpty()) {
            r.unreachable.addAll(toPlace.keySet());
            return;
        }

        while (!frontier.isEmpty()) {
            BlockPos t = pickBest(frontier, basePos);
            frontier.remove(t);
            if (placed.contains(t)) continue;

            BlockPos stand = findStandableFor(world, t, placed, toPlaceKeys);
            if (stand == null) {
                r.unreachable.add(t);
                continue;
            }
            placed.add(t);
            r.steps.add(new Step(t, stand, toPlace.get(t)));

            // Discover 26-neighbours that are structure blocks with a solid neighbour.
            for (BlockPos off : NEIGHBOURS) {
                BlockPos n = t.offset(off.getX(), off.getY(), off.getZ());
                if (!toPlaceKeys.contains(n) || placed.contains(n) || frontier.contains(n)) continue;
                if (hasSolidNeighbor(world, n, placed, toPlaceKeys)) frontier.add(n);
            }
        }

        // Any block never placed → unreachable (disconnected island)
        for (BlockPos wp : toPlace.keySet()) {
            if (!placed.contains(wp)) r.unreachable.add(wp);
        }
    }

    /** Choose the frontier block lowest in Y, then closest to the anchor — physically stable first. */
    private static BlockPos pickBest(Deque<BlockPos> frontier, BlockPos base) {
        BlockPos best = null;
        int bestY = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : frontier) {
            double d = p.distSqr(base);
            if (p.getY() < bestY || (p.getY() == bestY && d < bestDist)) {
                best = p; bestY = p.getY(); bestDist = d;
            }
        }
        return best;
    }

    /** A structure block has a solid neighbour if any 26-cell is world-solid or already placed. */
    private static boolean hasSolidNeighbor(ServerWorld world, BlockPos cell,
                                             Set<BlockPos> placed, Set<BlockPos> toPlaceKeys) {
        for (BlockPos off : NEIGHBOURS) {
            BlockPos n = cell.offset(off.getX(), off.getY(), off.getZ());
            if (placed.contains(n)) return true;
            if (!world.isEmptyBlock(n) && !toPlaceKeys.contains(n)) return true;
        }
        return false;
    }

    /** Seed test: does the cell touch a real world block (not a structure block)? */
    private static boolean hasWorldSolidNeighbor(ServerWorld world, BlockPos cell, Set<BlockPos> toPlaceKeys) {
        for (BlockPos off : NEIGHBOURS) {
            BlockPos n = cell.offset(off.getX(), off.getY(), off.getZ());
            if (!world.isEmptyBlock(n) && !toPlaceKeys.contains(n)) return true;
        }
        return false;
    }

    /**
     * Find a standing cell for {@code t} that is legal given the blocks placed so far.
     * The standing cell must be air (and never a structure block), have an air head cell,
     * a solid floor (world block or an already-placed block), and be dry.
     */
    private static BlockPos findStandableFor(ServerWorld world, BlockPos t,
                                             Set<BlockPos> placed, Set<BlockPos> toPlaceKeys) {
        for (BlockPos off : NEIGHBOURS) {
            BlockPos a = t.offset(off.getX(), off.getY(), off.getZ());
            if (standableForPlanner(world, a, placed, toPlaceKeys)) return a;
        }
        return null;
    }

    private static boolean standableForPlanner(ServerWorld world, BlockPos a,
                                               Set<BlockPos> placed, Set<BlockPos> toPlaceKeys) {
        if (placed.contains(a)) return false;                 // our own block — solid
        if (toPlaceKeys.contains(a)) return false;            // a block will occupy this — can't stand
        if (!world.isEmptyBlock(a)) return false;             // a real world block occupies it
        // Head room
        BlockPos head = a.above();
        if (placed.contains(head) || toPlaceKeys.contains(head) || !world.isEmptyBlock(head)) return false;
        // Floor
        BlockPos floor = a.below();
        boolean floorOk = placed.contains(floor)
                || (!world.isEmptyBlock(floor) && !toPlaceKeys.contains(floor));
        if (!floorOk) return false;
        // Dry
        if (world.getFluidState(a).is(FluidTags.WATER) || world.getFluidState(a).is(FluidTags.LAVA)) return false;
        if (world.getFluidState(head).is(FluidTags.WATER) || world.getFluidState(head).is(FluidTags.LAVA)) return false;
        return true;
    }
}
