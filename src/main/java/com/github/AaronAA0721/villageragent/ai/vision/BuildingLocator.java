package com.github.AaronAA0721.villageragent.ai.vision;

import com.github.AaronAA0721.villageragent.ai.world.BuildingRecord;
import net.minecraft.block.BlockState;
import net.minecraft.block.material.Material;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage-1 building locator (design doc: building_detection_implementation.md, §2).
 *
 * <p>Locates a building from a single bed using a <b>distance-field + synchronized
 * two-category geodesic watershed</b> room segmentation. This is what makes
 * "a bed in a small room of a big house" still resolve to the whole house, and — unlike
 * the old "first-neighbour-wins" / max-D-priority flood — keeps the exterior from
 * absorbing near-door interior cells.
 *
 * <p>Algorithm (all steps run on a 0.5-block sub-lattice, {@link #SUBDIV}=2):
 * <ol>
 *   <li><b>Step 0 — overhead-cover pre-filter.</b> A bed (or either of its two halves) with
 *       open sky directly above it is rejected immediately (cheap early-out).</li>
 *   <li><b>Step 1 — per-block solidity.</b> A block is a wall unless it is air, a liquid, or a
 *       passable plant (see {@link #NON_SOLID_MATERIALS}). The two bed halves are <i>not</i>
 *       solid — they are furniture, not walls.</li>
 *   <li><b>Step 2 — sub-lattice.</b> Expand each block into {@link #SUBDIV}³ sub-voxels.</li>
 *   <li><b>Step 3 — 3D connected-air flood.</b> Seed the flood from the bed's own sub-voxels
 *       (we mark every sub-voxel of both bed squares as "bed" first), so the bed is the root of
 *       the enclosed air component {@code inC}.</li>
 *   <li><b>Step 4 — 3D distance field.</b> Multi-source BFS from all solid cells; each air cell's
 *       value is its <i>graph (geodesic) distance to the nearest wall</i>. Because the bed cells
 *       are non-solid, the bed is <b>not</b> a distance source — the distance field ignores the
 *       bed (otherwise the bed would punch a false near-wall dip into the middle of the room).</li>
 *   <li><b>Step 5 — big-air signal (region-grown).</b> Seeds = cells that are open to sky
 *       (<i>skyOpen</i>) or have a long air run in some axis (<i>longRun</i>). The seed set then
 *       <i>grows</i>: any air cell whose 6 neighbours are ≥4/6 big-air becomes big-air too,
 *       iterated to convergence. This closes small gaps so the exterior forms one coherent region.</li>
 *   <li><b>Step 6 — synchronized two-category watershed.</b> Regional maxima of the distance field
 *       are the seeds. Each seed is <i>interior</i> (its basin would be an enclosed room) or
 *       <i>exterior/atmospheric</i> (open to sky / big-air). The exterior front is seeded not from
 *       the far-away max-D cell but from the atmospheric cell whose wall-distance equals
 *       max( interior seed D ), so the two fronts meet exactly at the door/window. A multi-source
 *       FIFO BFS then expands <b>every</b> seed one ring per iteration (all start at distance 0),
 *       so when two different seeds' frontiers touch they are at equal geodesic distance → that
 *       meeting cell is a watershed boundary. Boundary between an exterior-set cell and a
 *       non-atmospheric-set cell = the house/atmosphere interface; boundary between two interior
 *       seeds = an interior door/window.</li>
 *   <li><b>Step 7 — union.</b> All cells reached by an interior seed (and not a boundary) are
 *       rooms; their union's AABB is the house. A bed in a side room still yields the entire
 *       enclosed house because every enclosed basin is an interior seed.</li>
 *   <li><b>Step 8 — expand AABB + classify.</b> Expand by one block in ±x/±y/±z (wall/floor/ceiling
 *       shell), clamp to the scan box; classify cave vs normal house.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> a house taller than the scan box is clipped; a truly vast enclosed
 * hall may still trip the big-air signal. Beds are never searched by brute force: they are handed
 * in by {@link #bedsInChunk} (chunk block-entity table — free) or by block-place events.
 */
public final class BuildingLocator {

    public static final int SCAN_RADIUS = 24;   // horizontal half-extent of the search box (house footprint)
    public static final int V_BELOW = 2;        // layers scanned BELOW the bed (basements)
    public static final int V_UP = 16;          // layers scanned ABOVE the bed (raised back up: 3D flood handles multi-floor)
    public static final int SUBDIV = 2;         // lattice samples per block axis → 0.5-block resolution (8 sub-voxels / block)
    public static final int AIR_RUN = 12;       // a cell is "long-run air" if it has ≥AIR_RUN air cells in BOTH dirs of an axis.
                                                  // Raised 4→12: with 4 (2 blocks) a normal room read as ~100% big air and was dropped
                                                  // as "open atmosphere"; 12 (6 blocks) only flags genuinely open space, so rooms survive.
    public static final double BIG_AIR_FRACTION = 0.85; // a blob whose atmospheric fraction exceeds this is "open atmosphere" (Step 7)
    public static final int MIN_ROOM = 8;       // fewer than this many room blocks ⇒ not a house

    /**
     * Blocks that are NOT solid even though they are neither air nor liquid.
     * Grass-like plant blocks (tall grass, fern, sapling, flowers, dead bush, sweet-berry bush,
     * …) must not act as walls/roofs — a lawn inside a room or a hedge around a house should not
     * split or seal the detected cavity. Add more materials/blocks here as needed.
     *
     * <p>Note: leaves ({@code Material.LEAVES}) are intentionally still walls — only the
     * plant-family materials listed here are passable. Change this if you want foliage to
     * pass through too.
     */
    private static final Set<Material> NON_SOLID_MATERIALS = new HashSet<>(Arrays.asList(
            Material.PLANT, Material.REPLACEABLE_PLANT
    ));

    private BuildingLocator() {}

    /**
     * Bed positions inside a chunk, taken from the chunk's block-entity table.
     * Beds are block entities in 1.16.5 (dye colour), so this costs no block scanning at all —
     * it replaces the old brute-force ~1M {@code getBlockState} sweep.
     */
    public static List<BlockPos> bedsInChunk(IChunk chunk) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : chunk.getBlockEntitiesPos()) {
            BlockState st = chunk.getBlockState(p);
            if (st.is(BlockTags.BEDS)) out.add(p.immutable());
        }
        return out;
    }

    /**
     * Collect the full bed: a bed occupies two horizontally-adjacent blocks (head + foot).
     * Given one half, the other half is any horizontally-adjacent block that is also a bed.
     */
    private static List<BlockPos> collectBedBlocks(World world, BlockPos bed) {
        List<BlockPos> beds = new ArrayList<>();
        beds.add(bed.immutable());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = bed.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            if (world.getBlockState(n).is(BlockTags.BEDS)) beds.add(n.immutable());
        }
        return beds;
    }

    /**
     * Pre-filter (Step 0): at least one of the bed's squares must have a sheltering block
     * somewhere above it — a roof. A bed with open sky overhead is out in the open and not
     * worth a full flood-fill. Scans upward up to {@link #V_UP} layers; returns true as soon as
     * a roof block is found. Glass counts as a roof; a submerged bed (water above) does not.
     */
    private static boolean hasOverheadCover(World world, List<BlockPos> beds) {
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (BlockPos bed : beds) {
            for (int dy = 1; dy <= V_UP; dy++) {
                p.set(bed.getX(), bed.getY() + dy, bed.getZ());
                if (isRoof(world.getBlockState(p))) return true;
            }
        }
        return false;
    }

    /**
     * A block is a "wall" (occludes the air flood) if it is non-air, non-liquid, and not in the
     * passable-plant table {@link #NON_SOLID_MATERIALS}. So grass/tall-grass/saplings/flowers
     * pass through; glass/leaves/stone/wood still block. Fluids (water/lava) pass through.
     */
    private static boolean isWall(BlockState st) {
        Material m = st.getMaterial();
        if (m == Material.AIR || m.isLiquid()) return false;
        return !NON_SOLID_MATERIALS.contains(m);
    }

    /** A block shelters a bed (acts as a roof) iff it counts as a wall (see {@link #isWall}). */
    private static boolean isRoof(BlockState st) {
        return isWall(st);
    }

    /**
     * Analyze the air cavity around a single bed (which may be one of two halves). Returns null
     * if it is not an enclosed house.
     */
    public static BuildingRecord locateBed(World world, BlockPos bed) {
        // Step 0: reject beds with no roof above them (open air) before any scanning.
        List<BlockPos> bedBlocks = collectBedBlocks(world, bed);
        if (!hasOverheadCover(world, bedBlocks)) return null;

        int R = SCAN_RADIUS;
        int xMin = bed.getX() - R, xMax = bed.getX() + R;
        int yMin = Math.max(0, bed.getY() - V_BELOW), yMax = Math.min(255, bed.getY() + V_UP);
        int zMin = bed.getZ() - R, zMax = bed.getZ() + R;

        int SX = xMax - xMin + 1, SY = yMax - yMin + 1, SZ = zMax - zMin + 1;
        if (SX <= 0 || SY <= 0 || SZ <= 0) return null;

        // Step 1: per-block solidity. Bed blocks are furniture, not walls.
        boolean[][][] blockSolid = new boolean[SX][SY][SZ];
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int lx = 0; lx < SX; lx++) {
            for (int ly = 0; ly < SY; ly++) {
                for (int lz = 0; lz < SZ; lz++) {
                    p.set(xMin + lx, yMin + ly, zMin + lz);
                    boolean isBed = false;
                    for (BlockPos bp : bedBlocks) {
                        if (bp.getX() == p.getX() && bp.getY() == p.getY() && bp.getZ() == p.getZ()) {
                            isBed = true; break;
                        }
                    }
                    blockSolid[lx][ly][lz] = isBed ? false : isWall(world.getBlockState(p));
                }
            }
        }

        // Step 2: sub-lattice (SUBDIV samples per block axis)
        int LX = SX * SUBDIV, LY = SY * SUBDIV, LZ = SZ * SUBDIV;
        int LYZ = LY * LZ;
        int N = LX * LY * LZ;
        boolean[] solid = new boolean[N];
        for (int i = 0; i < LX; i++) {
            for (int j = 0; j < LY; j++) {
                for (int k = 0; k < LZ; k++) {
                    solid[(i * LY + j) * LZ + k] = blockSolid[i / SUBDIV][j / SUBDIV][k / SUBDIV];
                }
            }
        }

        // Bed mask on the sub-lattice: every sub-voxel of every bed square is marked "bed".
        // Bed cells are forced non-solid so the distance field (Step 4) ignores them as walls.
        boolean[] bedMask = new boolean[N];
        for (BlockPos bp : bedBlocks) {
            int bx0 = (bp.getX() - xMin) * SUBDIV;
            int byH = (bp.getY() - yMin) * SUBDIV;
            int bz0 = (bp.getZ() - zMin) * SUBDIV;
            for (int di = 0; di < SUBDIV; di++) {
                for (int dj = 0; dj < SUBDIV; dj++) {
                    for (int dk = 0; dk < SUBDIV; dk++) {
                        int idx = ((bx0 + di) * LY + (byH + dj)) * LZ + (bz0 + dk);
                        bedMask[idx] = true;
                        solid[idx] = false;
                    }
                }
            }
        }

        // Step 3: flood the connected air component, seeded from the bed's own sub-voxels.
        boolean[] inC = new boolean[N];
        Deque<Integer> q = new ArrayDeque<>();
        for (int c = 0; c < N; c++) {
            if (bedMask[c] && !solid[c]) { inC[c] = true; q.add(c); }
        }
        if (q.isEmpty()) {
            // Bed fully buried in solid — nothing to find.
            return null;
        }
        while (!q.isEmpty()) {
            int cur = q.poll();
            for (int n : neighbors(cur, LX, LY, LZ, LYZ)) {
                if (n < 0) continue;
                if (!solid[n] && !inC[n]) { inC[n] = true; q.add(n); }
            }
        }

        // Step 4: 3D distance field (multi-source BFS from solids). Each air cell's value is its
        // geodesic distance (in sub-voxel steps) to the nearest wall. The bed is non-solid, so it
        // contributes no distance source — the field ignores the bed.
        int[] D = new int[N];
        Arrays.fill(D, -1);
        Deque<Integer> dq = new ArrayDeque<>();
        for (int i = 0; i < N; i++) {
            if (solid[i]) { D[i] = 0; dq.add(i); }
        }
        while (!dq.isEmpty()) {
            int cur = dq.poll();
            int d = D[cur];
            for (int n : neighbors(cur, LX, LY, LZ, LYZ)) {
                if (n < 0) continue;
                if (D[n] == -1) { D[n] = d + 1; dq.add(n); }
            }
        }

        // Step 5: big-air signal, region-grown.
        // Seeds: open to sky, or a long air run in some axis.
        boolean[] skyOpen = new boolean[N];
        for (int i = 0; i < LX; i++) {
            for (int k = 0; k < LZ; k++) {
                boolean above = true;
                for (int j = LY - 1; j >= 0; j--) {
                    int idx = (i * LY + j) * LZ + k;
                    if (solid[idx]) { above = false; skyOpen[idx] = false; }
                    else skyOpen[idx] = above && inC[idx];
                }
            }
        }
        boolean[] longRun = computeBigAir(inC, solid, LX, LY, LZ, LYZ);
        boolean[] bigAir = new boolean[N];
        for (int i = 0; i < N; i++) bigAir[i] = inC[i] && (skyOpen[i] || longRun[i]);
        // Grow: a non-big-air air cell whose 6 air neighbours are ≥4/6 big-air becomes big-air.
        growBigAir(inC, bigAir, LX, LY, LZ, LYZ);

        // Step 6: synchronized two-category geodesic watershed.
        // Label every cell by the seed that reaches it first; meeting cells at equal geodesic
        // distance become watershed boundaries (house/atmosphere interface, or interior door/window).
        int[] label = new int[N];
        Arrays.fill(label, -1);
        int[] dist = new int[N];
        Arrays.fill(dist, -1);
        boolean[] boundary = new boolean[N];

        // 6a: gather regional-maximum plateaus of D; tag each as interior or exterior (atmospheric).
        List<List<Integer>> plateaus = new ArrayList<>();
        List<Boolean> plateauExterior = new ArrayList<>();
        boolean[] visitedMax = new boolean[N];
        for (int cur = 0; cur < N; cur++) {
            if (!inC[cur] || visitedMax[cur]) continue;
            boolean isMax = true;
            for (int n : neighbors(cur, LX, LY, LZ, LYZ)) {
                if (n >= 0 && inC[n] && D[n] > D[cur]) { isMax = false; break; }
            }
            if (!isMax) continue;
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(cur); visitedMax[cur] = true;
            List<Integer> comp = new ArrayList<>();
            while (!stack.isEmpty()) {
                int c = stack.pop(); comp.add(c);
                for (int n : neighbors(c, LX, LY, LZ, LYZ)) {
                    if (n >= 0 && inC[n] && !visitedMax[n] && D[n] == D[cur]) {
                        visitedMax[n] = true; stack.push(n);
                    }
                }
            }
            boolean atmospheric = false;
            for (int c : comp) {
                if (skyOpen[c] || bigAir[c]) { atmospheric = true; break; }
            }
            plateaus.add(comp);
            plateauExterior.add(atmospheric);
        }

        // 6b: pick the exterior front. Interior seeds are real; the exterior front is seeded from
        // the atmospheric cell(s) whose wall-distance equals max(interior seed D), so the two
        // fronts meet at the door/window instead of the exterior front starting far away.
        int maxInteriorD = 0;
        boolean hasInterior = false;
        for (int pi = 0; pi < plateaus.size(); pi++) {
            if (!plateauExterior.get(pi)) {
                hasInterior = true;
                for (int c : plateaus.get(pi)) maxInteriorD = Math.max(maxInteriorD, D[c]);
            }
        }

        Deque<Integer> seedQ = new ArrayDeque<>();
        int labelId = 0;
        int exteriorLabel = -1;
        // Seed every interior plateau with its own label (so interior-interior meetings are caught).
        for (int pi = 0; pi < plateaus.size(); pi++) {
            if (plateauExterior.get(pi)) continue;
            int lid = labelId++;
            for (int c : plateaus.get(pi)) {
                label[c] = lid; dist[c] = 0; seedQ.add(c);
            }
        }
        if (hasInterior) {
            // Exterior front: atmospheric cells whose wall-distance equals max(interior seed D), so
            // the two fronts meet at the door/window instead of the exterior front starting far out.
            exteriorLabel = labelId++;
            int exteriorSeeded = seedExteriorFront(maxInteriorD, inC, skyOpen, bigAir, D, label, dist, seedQ, exteriorLabel, N);
            if (exteriorSeeded == 0) {
                // No atmospheric cell sits exactly at D == maxInteriorD. The exterior front must
                // start NEAR the doorway, not far out in open space: a far seed would let its front
                // flood inward through the opening and swallow the room. Prefer the largest D
                // strictly below maxInteriorD (scan downward) — those cells are just outside the
                // wall, so the meeting point stays at the opening. If none exists below at all (no
                // atmospheric cell with D <= maxInteriorD — extremely rare), scan upward from
                // maxInteriorD until a D with atmospheric cells exists and seed from that ring.
                int maxDAtmo = 0;
                for (int c = 0; c < N; c++) if (inC[c] && (skyOpen[c] || bigAir[c])) maxDAtmo = Math.max(maxDAtmo, D[c]);
                int d = maxInteriorD - 1;
                while (d >= 1) {
                    if (seedExteriorFront(d, inC, skyOpen, bigAir, D, label, dist, seedQ, exteriorLabel, N) > 0) break;
                    d--;
                }
                if (d < 1) {
                    for (d = maxInteriorD + 1; d <= maxDAtmo; d++) {
                        if (seedExteriorFront(d, inC, skyOpen, bigAir, D, label, dist, seedQ, exteriorLabel, N) > 0) break;
                    }
                }
            }
        } else {
            // No enclosed region at all — fall back to seeding every atmospheric plateau as exterior.
            exteriorLabel = labelId++;
            for (int pi = 0; pi < plateaus.size(); pi++) {
                if (!plateauExterior.get(pi)) continue;
                for (int c : plateaus.get(pi)) {
                    if (label[c] == -1) { label[c] = exteriorLabel; dist[c] = 0; seedQ.add(c); }
                }
            }
        }

        // 6c: synchronized FIFO multi-source BFS. Every seed starts at distance 0 and expands one
        // ring per iteration, so when two different seeds' frontiers touch, both are at the same
        // geodesic distance from their seeds → that meeting cell is a boundary.
        while (!seedQ.isEmpty()) {
            int cur = seedQ.poll();
            int cl = label[cur];
            int cd = dist[cur];
            for (int n : neighbors(cur, LX, LY, LZ, LYZ)) {
                if (n < 0 || !inC[n]) continue;
                if (label[n] == -1) {
                    label[n] = cl; dist[n] = cd + 1; seedQ.add(n);
                } else if (label[n] != cl && dist[n] >= cd) {
                    // Two different seeds reach n at the same (or n already at this) level → boundary.
                    boundary[n] = true;
                }
            }
        }

        // Step 7: decide which blob is the room by atmospheric proportion (robust to any slip in the
        // seed category). The blob with the LOW atmospheric fraction is the enclosed room; the HIGH
        // one is open atmosphere. Boundary cells are excluded from both blobs.
        int interiorCells = 0, interiorAtmo = 0;
        int exteriorCells = 0, exteriorAtmo = 0;
        for (int i = 0; i < N; i++) {
            if (!inC[i] || boundary[i]) continue;
            boolean atmo = skyOpen[i] || bigAir[i];
            if (label[i] == exteriorLabel)      { exteriorCells++; if (atmo) exteriorAtmo++; }
            else if (label[i] >= 0)             { interiorCells++; if (atmo) interiorAtmo++; }
        }
        double interiorFrac = interiorCells == 0 ? 1.0 : (double) interiorAtmo / interiorCells;
        double exteriorFrac = exteriorCells == 0 ? 1.0 : (double) exteriorAtmo / exteriorCells;
        boolean interiorIsRoom;
        if      (interiorFrac < BIG_AIR_FRACTION && exteriorFrac >= BIG_AIR_FRACTION) interiorIsRoom = true;
        else if (exteriorFrac < BIG_AIR_FRACTION && interiorFrac >= BIG_AIR_FRACTION) interiorIsRoom = false;
        else interiorIsRoom = interiorFrac <= exteriorFrac; // ambiguous: lower-atmosphere blob wins; tie → interior

        // Union the room blob (boundary & non-room cells excluded) into the room AABB.
        boolean[][][] roomBlock = new boolean[SX][SY][SZ];
        int minBX = Integer.MAX_VALUE, minBY = Integer.MAX_VALUE, minBZ = Integer.MAX_VALUE;
        int maxBX = Integer.MIN_VALUE, maxBY = Integer.MIN_VALUE, maxBZ = Integer.MIN_VALUE;
        int minWX = Integer.MAX_VALUE, minWY = Integer.MAX_VALUE, minWZ = Integer.MAX_VALUE;
        int maxWX = Integer.MIN_VALUE, maxWY = Integer.MIN_VALUE, maxWZ = Integer.MIN_VALUE;
        int roomBlocks = 0;
        int maxRoomD = 0;
        for (int i = 0; i < N; i++) {
            if (!inC[i] || boundary[i]) continue;
            boolean isRoom = interiorIsRoom
                    ? (label[i] >= 0 && label[i] != exteriorLabel)
                    : (label[i] == exteriorLabel);
            if (!isRoom) continue;
            int ci = i / LYZ, cj = (i / LZ) % LY, ck = i % LZ;
            int bx = ci / SUBDIV, by = cj / SUBDIV, bz = ck / SUBDIV;
            if (!roomBlock[bx][by][bz]) {
                roomBlock[bx][by][bz] = true;
                roomBlocks++;
                minBX = Math.min(minBX, bx); maxBX = Math.max(maxBX, bx);
                minBY = Math.min(minBY, by); maxBY = Math.max(maxBY, by);
                minBZ = Math.min(minBZ, bz); maxBZ = Math.max(maxBZ, bz);
                int wx = xMin + bx, wy = yMin + by, wz = zMin + bz;
                minWX = Math.min(minWX, wx); maxWX = Math.max(maxWX, wx);
                minWY = Math.min(minWY, wy); maxWY = Math.max(maxWY, wy);
                minWZ = Math.min(minWZ, wz); maxWZ = Math.max(maxWZ, wz);
            }
            if (D[i] > maxRoomD) maxRoomD = D[i];
        }
        if (roomBlocks < MIN_ROOM) return null;

        // Step 8: cave vs normal house from the room AABB border blocks.
        String type = classifyType(blockSolid, roomBlock, SX, SY, SZ, minBX, minBY, minBZ, maxBX, maxBY, maxBZ);

        // Expand the AABB by one block in ±x / ±y / ±z so the house shell (walls, floor & ceiling)
        // is enclosed. Clamp to the scan box so we never claim blocks we never sampled.
        int exMinX = Math.max(xMin, minWX - 1), exMaxX = Math.min(xMax, maxWX + 1);
        int exMinY = Math.max(yMin, minWY - 1), exMaxY = Math.min(yMax, maxWY + 1);
        int exMinZ = Math.max(zMin, minWZ - 1), exMaxZ = Math.min(zMax, maxWZ + 1);

        long id = bed.asLong() & 0x7FFFFFFFFFFFFFFFL; // unique per bed block position
        BuildingRecord record = new BuildingRecord(id, bed.immutable(),
                new BlockPos(exMinX, exMinY, exMinZ), new BlockPos(exMaxX, exMaxY, exMaxZ),
                (float) maxRoomD / SUBDIV, roomBlocks, type);

        // ── Debug: one wireframe marker per distance-field seed (regional-maximum plateau) ──
        // Each plateau is a watershed seed; we drop a marker at its centroid (in sub-grid → world
        // coords) so the debug overlay can show where the algorithm thinks room-centres / open-air
        // centres are. interior = non-atmosphere plateau (room-candidate seed); otherwise atmosphere.
        List<BuildingRecord.DebugSeed> seeds = new ArrayList<>();
        for (int pi = 0; pi < plateaus.size(); pi++) {
            List<Integer> comp = plateaus.get(pi);
            long sCi = 0, sCj = 0, sCk = 0;
            for (int c : comp) { sCi += c / LYZ; sCj += (c / LZ) % LY; sCk += c % LZ; }
            int n = comp.size();
            int sci = (int) (sCi / n), scj = (int) (sCj / n), sck = (int) (sCk / n);
            int wx = (int) Math.floor(xMin + (sci + 0.5) / SUBDIV);
            int wy = (int) Math.floor(yMin + (scj + 0.5) / SUBDIV);
            int wz = (int) Math.floor(zMin + (sck + 0.5) / SUBDIV);
            seeds.add(new BuildingRecord.DebugSeed(new BlockPos(wx, wy, wz), !plateauExterior.get(pi)));
        }
        record.debugSeeds = seeds;

        return record;
    }

    /** 6-neighbour indices of a lattice cell (or -1 if out of range). */
    private static int[] neighbors(int cur, int LX, int LY, int LZ, int LYZ) {
        int ck = cur % LZ;
        int cj = (cur / LZ) % LY;
        int ci = cur / LYZ;
        int[] r = new int[6];
        r[0] = ck < LZ - 1 ? cur + 1 : -1;
        r[1] = ck > 0 ? cur - 1 : -1;
        r[2] = cj < LY - 1 ? cur + LZ : -1;
        r[3] = cj > 0 ? cur - LZ : -1;
        r[4] = ci < LX - 1 ? cur + LYZ : -1;
        r[5] = ci > 0 ? cur - LYZ : -1;
        return r;
    }

    /**
     * Seed the exterior front from every in-C cell whose wall-distance equals {@code targetD} and
     * that is atmospheric (open-to-sky or big-air). Returns how many cells were seeded. Cells
     * already labelled are skipped so repeated ring scans never double-seed.
     */
    private static int seedExteriorFront(int targetD, boolean[] inC, boolean[] skyOpen, boolean[] bigAir,
                                         int[] D, int[] label, int[] dist, Deque<Integer> seedQ,
                                         int exteriorLabel, int N) {
        int seeded = 0;
        for (int c = 0; c < N; c++) {
            if (inC[c] && (skyOpen[c] || bigAir[c]) && D[c] == targetD && label[c] == -1) {
                label[c] = exteriorLabel; dist[c] = 0; seedQ.add(c); seeded++;
            }
        }
        return seeded;
    }

    /**
     * Grow the big-air region (Step 5). Seed set is already in {@code bigAir}; any inC air cell
     * that is not yet big-air but has ≥4 of its 6 air neighbours already big-air becomes big-air.
     * Repeat until no change (monotonic, so it converges). {@code bigAir} is mutated in place.
     */
    private static void growBigAir(boolean[] inC, boolean[] bigAir, int LX, int LY, int LZ, int LYZ) {
        boolean changed = true;
        while (changed) {
            changed = false;
            boolean[] snap = bigAir.clone(); // stable view for this pass
            for (int i = 0; i < inC.length; i++) {
                if (!inC[i] || snap[i]) continue;
                int cnt = 0;
                for (int n : neighbors(i, LX, LY, LZ, LYZ)) {
                    if (n >= 0 && inC[n] && snap[n]) cnt++;
                }
                if (cnt >= 4) { bigAir[i] = true; changed = true; }
            }
        }
    }

    /**
     * A cell is a "long-run air" seed if it has a run (≥ {@link #AIR_RUN}) of air in BOTH
     * directions of at least one axis (X/Y/Z). Used as the supplemental atmosphere signal.
     */
    private static boolean[] computeBigAir(boolean[] inC, boolean[] solid, int LX, int LY, int LZ, int LYZ) {
        boolean[] out = new boolean[inC.length];
        // X axis
        for (int j = 0; j < LY; j++) {
            for (int k = 0; k < LZ; k++) {
                int[] fwd = new int[LX], bwd = new int[LX];
                for (int i = 0; i < LX; i++) { int idx = (i * LY + j) * LZ + k; fwd[i] = inC[idx] ? (i > 0 ? fwd[i - 1] + 1 : 1) : 0; }
                for (int i = LX - 1; i >= 0; i--) { int idx = (i * LY + j) * LZ + k; bwd[i] = inC[idx] ? (i < LX - 1 ? bwd[i + 1] + 1 : 1) : 0; }
                for (int i = 0; i < LX; i++) { int idx = (i * LY + j) * LZ + k; if (inC[idx] && fwd[i] >= AIR_RUN && bwd[i] >= AIR_RUN) out[idx] = true; }
            }
        }
        // Y axis (vertical)
        for (int i = 0; i < LX; i++) {
            for (int k = 0; k < LZ; k++) {
                int[] fwd = new int[LY], bwd = new int[LY];
                for (int j = 0; j < LY; j++) { int idx = (i * LY + j) * LZ + k; fwd[j] = inC[idx] ? (j > 0 ? fwd[j - 1] + 1 : 1) : 0; }
                for (int j = LY - 1; j >= 0; j--) { int idx = (i * LY + j) * LZ + k; bwd[j] = inC[idx] ? (j < LY - 1 ? bwd[j + 1] + 1 : 1) : 0; }
                for (int j = 0; j < LY; j++) { int idx = (i * LY + j) * LZ + k; if (inC[idx] && fwd[j] >= AIR_RUN && bwd[j] >= AIR_RUN) out[idx] = true; }
            }
        }
        // Z axis
        for (int i = 0; i < LX; i++) {
            for (int j = 0; j < LY; j++) {
                int[] fwd = new int[LZ], bwd = new int[LZ];
                for (int k = 0; k < LZ; k++) { int idx = (i * LY + j) * LZ + k; fwd[k] = inC[idx] ? (k > 0 ? fwd[k - 1] + 1 : 1) : 0; }
                for (int k = LZ - 1; k >= 0; k--) { int idx = (i * LY + j) * LZ + k; bwd[k] = inC[idx] ? (k < LZ - 1 ? bwd[k + 1] + 1 : 1) : 0; }
                for (int k = 0; k < LZ; k++) { int idx = (i * LY + j) * LZ + k; if (inC[idx] && fwd[k] >= AIR_RUN && bwd[k] >= AIR_RUN) out[idx] = true; }
            }
        }
        return out;
    }

    /** Room shell stone-likeness: >50% of the solids touching room air are stone-like ⇒ cave house. */
    private static String classifyType(boolean[][][] blockSolid, boolean[][][] roomBlock,
                                        int SX, int SY, int SZ,
                                        int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int stone = 0, total = 0;
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x < 0 || x >= SX || y < 0 || y >= SY || z < 0 || z >= SZ) continue;
                    if (!roomBlock[x][y][z]) continue; // only room-air blocks
                    for (int[] d : dirs) {
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        if (nx < 0 || nx >= SX || ny < 0 || ny >= SY || nz < 0 || nz >= SZ) continue;
                        if (!blockSolid[nx][ny][nz]) continue; // touching solid only
                        total++;
                        if (isStoneLike(nx, ny, nz, blockSolid)) stone++;
                    }
                }
            }
        }
        return (total > 0 && stone * 2 >= total) ? "cave_house" : "house";
    }

    /** Cheap stone-like heuristic: a solid block is "stone-like" if it sits below the room's
     *  mid-height OR is surrounded by other solids (cave walls are embedded in stone). */
    private static boolean isStoneLike(int x, int y, int z, boolean[][][] blockSolid) {
        int SX = blockSolid.length, SY = blockSolid[0].length, SZ = blockSolid[0][0].length;
        int solidNeighbours = 0;
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx < 0 || nx >= SX || ny < 0 || ny >= SY || nz < 0 || nz >= SZ) continue;
            if (blockSolid[nx][ny][nz]) solidNeighbours++;
        }
        // embedded in rock (≥5 of 6 neighbours solid) ⇒ stone-like
        return solidNeighbours >= 5;
    }
}
