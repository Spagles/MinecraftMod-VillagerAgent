package com.github.AaronAA0721.villageragent.ai.world;

import com.github.AaronAA0721.villageragent.ai.vision.BuildingLocator;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.server.ServerWorld;

import java.util.*;

/**
 * Shared, per-dimension spatial index of buildings.
 *
 * <p>Computed once and queried by every villager — no villager ever scans the world itself.
 *
 * <p><b>Seeding is incremental and event-driven</b> (no periodic world sweep):
 * <ul>
 *   <li>{@link #indexChunk} on chunk load — bed positions come straight from the chunk's
 *       block-entity table, so discovery is free;</li>
 *   <li>{@link #offerBed} when a bed is placed, or when a villager's chunk sampler notices one;</li>
 *   <li>{@link #processPending} runs at most a couple of flood fills per tick from a queue.</li>
 * </ul>
 * Each bed is flooded <b>once</b>: it then lives in {@code claimedBeds} (it belongs to a
 * building) or {@code rejectedBeds} (negative cache — a bed out in the open). Block edits inside
 * a known building, or next to a rejected bed, invalidate just that neighbourhood.
 *
 * <p>The whole index (records + both bed caches) is persisted through
 * {@link StructureIndexSavedData}, so a restart does not re-flood anything.
 */
public class WorldStructureIndex {

    /** Flood fills allowed per world tick. Each one is a ~50x25x50 box scan. */
    public static final int FLOODS_PER_TICK = 1;

    private static final Map<World, WorldStructureIndex> INSTANCES = new WeakHashMap<>();

    public static WorldStructureIndex instance(World world) {
        synchronized (INSTANCES) {
            WorldStructureIndex existing = INSTANCES.get(world);
            if (existing != null) return existing;

            final WorldStructureIndex created = new WorldStructureIndex();
            INSTANCES.put(world, created);
            if (world instanceof ServerWorld) {
                // Attaches + loads persisted state (or creates a fresh save entry).
                ((ServerWorld) world).getDataStorage().computeIfAbsent(
                        () -> new StructureIndexSavedData(created), StructureIndexSavedData.DATA_NAME);
            }
            return created;
        }
    }

    private final Map<Long, BuildingRecord> byId = new HashMap<>();
    private final Map<Long, List<BuildingRecord>> byChunk = new HashMap<>();

    /** Beds that already belong to a building — never flooded again. */
    private final Set<Long> claimedBeds = new HashSet<>();
    /** Beds that were flooded and turned out not to be in a house (negative cache). */
    private final Set<Long> rejectedBeds = new HashSet<>();
    /** Beds waiting for a flood fill. */
    private final Deque<Long> pending = new ArrayDeque<>();
    private final Set<Long> pendingSet = new HashSet<>();

    private StructureIndexSavedData savedData;

    /** Called by {@link StructureIndexSavedData} so mutations can mark the save dirty. */
    void attach(StructureIndexSavedData data) {
        this.savedData = data;
    }

    private void dirty() {
        if (savedData != null) savedData.setDirty();
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public synchronized BuildingRecord getAt(BlockPos p) {
        List<BuildingRecord> list = byChunk.get(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
        if (list == null) return null;
        for (BuildingRecord r : list) if (r.contains(p)) return r;
        return null;
    }

    /** All buildings whose bounding box intersects the chunk-radius around a center. */
    public synchronized List<BuildingRecord> queryNear(BlockPos center, int rChunks) {
        List<BuildingRecord> out = new ArrayList<>();
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        Set<Long> seen = new HashSet<>();
        for (int dx = -rChunks; dx <= rChunks; dx++) {
            for (int dz = -rChunks; dz <= rChunks; dz++) {
                List<BuildingRecord> list = byChunk.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (list == null) continue;
                for (BuildingRecord r : list) {
                    if (seen.add(r.id)) out.add(r);
                }
            }
        }
        return out;
    }

    public synchronized int buildingCount() { return byId.size(); }
    public synchronized int pendingCount()  { return pending.size(); }
    public synchronized int rejectedCount() { return rejectedBeds.size(); }

    // ── Mutation ──────────────────────────────────────────────────────────

    public synchronized void add(BuildingRecord r) {
        byId.put(r.id, r);
        for (long ck : chunksOf(r)) {
            byChunk.computeIfAbsent(ck, k -> new ArrayList<>()).add(r);
        }
        claimedBeds.add(r.seedBed.asLong());
        // Any other queued bed inside this cavity belongs to the same building.
        AxisAlignedBB box = new AxisAlignedBB(r.boundsMin, r.boundsMax.offset(1, 1, 1));
        Iterator<Long> it = pending.iterator();
        while (it.hasNext()) {
            long k = it.next();
            BlockPos bp = BlockPos.of(k);
            if (box.contains(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5)) {
                it.remove();
                pendingSet.remove(k);
                claimedBeds.add(k);
                r.addBed(bp); // bind this extra bed to the same house
            }
        }
        dirty();
    }

    public synchronized void remove(long id) {
        BuildingRecord r = byId.remove(id);
        if (r == null) return;
        for (long ck : chunksOf(r)) {
            List<BuildingRecord> list = byChunk.get(ck);
            if (list != null) {
                list.removeIf(b -> b.id == id);
                if (list.isEmpty()) byChunk.remove(ck);
            }
        }
        claimedBeds.remove(r.seedBed.asLong());
        dirty();
    }

    /**
     * Invalidate everything overlapping a region (a player edited the world there):
     * affected buildings are dropped and their beds re-queued, and rejected beds nearby get a
     * second chance (the edit may have just closed the last hole).
     */
    public synchronized void markDirty(AxisAlignedBB region) {
        List<BuildingRecord> hit = new ArrayList<>();
        for (BuildingRecord r : byId.values()) {
            if (new AxisAlignedBB(r.boundsMin, r.boundsMax).intersects(region)) hit.add(r);
        }
        for (BuildingRecord r : hit) {
            remove(r.id);
            enqueue(r.seedBed.asLong());
        }
        Iterator<Long> it = rejectedBeds.iterator();
        List<Long> revive = new ArrayList<>();
        while (it.hasNext()) {
            long k = it.next();
            BlockPos bp = BlockPos.of(k);
            if (region.inflate(BuildingLocator.SCAN_RADIUS).contains(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5)) {
                it.remove();
                revive.add(k);
            }
        }
        for (long k : revive) enqueue(k);
        if (!hit.isEmpty() || !revive.isEmpty()) dirty();
    }

    /** A single block changed — invalidate only what could be affected. */
    public synchronized void onBlockChanged(BlockPos pos) {
        BuildingRecord r = getAt(pos);
        if (r != null) {
            remove(r.id);
            enqueue(r.seedBed.asLong());
            return;
        }
        // Not inside a known building: a nearby rejected bed may now be enclosed.
        Iterator<Long> it = rejectedBeds.iterator();
        List<Long> revive = new ArrayList<>();
        while (it.hasNext()) {
            long k = it.next();
            BlockPos bp = BlockPos.of(k);
            if (bp.distSqr(pos) <= 12 * 12) { it.remove(); revive.add(k); }
        }
        for (long k : revive) enqueue(k);
        if (!revive.isEmpty()) dirty();
    }

    /** A bed was destroyed: remove it from its building (and the building if it was the last bed). */
    public synchronized void onBedRemoved(BlockPos bed) {
        long k = bed.asLong();
        // Find the building that lists this bed (covers seed + extra beds alike).
        BuildingRecord hit = null;
        for (BuildingRecord r : byId.values()) {
            if (r.hasBed(k)) { hit = r; break; }
        }
        if (hit != null) {
            hit.removeBed(bed);
            claimedBeds.remove(k);
            if (hit.beds.isEmpty()) {
                remove(hit.id); // last bed gone → whole building vanishes
            } else if (hit.seedBed.asLong() == k) {
                // promote a surviving bed to seed so the record stays anchored to a live bed
                hit.seedBed = hit.beds.get(0).immutable();
                claimedBeds.add(hit.seedBed.asLong());
                dirty();
            } else {
                dirty();
            }
            return;
        }
        // Not part of any known building: just clear the caches.
        claimedBeds.remove(k);
        rejectedBeds.remove(k);
        if (pendingSet.remove(k)) pending.remove(k);
        dirty();
    }

    // ── Seeding (event-driven) ────────────────────────────────────────────

    /**
     * Queue every not-yet-resolved bed in a freshly loaded chunk. Beds are read from the
     * chunk's block-entity table, so this touches no blocks.
     */
    public void indexChunk(IChunk chunk) {
        for (BlockPos bed : BuildingLocator.bedsInChunk(chunk)) offerBed(bed);
    }

    /** Offer a bed for analysis. No-op if it is already claimed, rejected or queued. */
    public synchronized void offerBed(BlockPos bed) {
        enqueue(bed.asLong());
    }

    private void enqueue(long key) {
        if (claimedBeds.contains(key) || rejectedBeds.contains(key) || pendingSet.contains(key)) return;
        pendingSet.add(key);
        pending.add(key);
    }

    /**
     * Run up to {@code budget} flood fills from the queue. Called once per world tick, so the
     * cost of discovering a whole village is spread over a few seconds instead of stalling
     * the server in one 1M-block sweep.
     */
    public void processPending(World world, int budget) {
        for (int i = 0; i < budget; i++) {
            long key;
            synchronized (this) {
                if (pending.isEmpty()) return;
                key = pending.poll();
                pendingSet.remove(key);
            }
            BlockPos bed = BlockPos.of(key);

            // The surrounding box must be loaded, otherwise the scan would force chunk loads.
            // (V_UP is the larger vertical half-extent; V_BELOW is tiny, so V_UP covers it.)
            // Note: IWorldReader.hasChunksAt is @Deprecated in 1.16.5 with no non-deprecated
            // overload, so we replicate its semantics directly via getChunkNow (returns null if
            // the column is not yet loaded, without forcing a load).
            int r = BuildingLocator.SCAN_RADIUS, v = BuildingLocator.V_UP;
            int x0 = bed.getX() - r, x1 = bed.getX() + r;
            int z0 = bed.getZ() - r, z1 = bed.getZ() + r;
            boolean chunksLoaded = true;
            outer:
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                    if (world.getChunkSource().getChunkNow(cx, cz) == null) {
                        chunksLoaded = false;
                        break outer;
                    }
                }
            }
            if (!chunksLoaded) {
                continue; // dropped from the queue; re-offered on the next chunk load / sampling
            }
            if (!world.getBlockState(bed).is(BlockTags.BEDS)) {
                synchronized (this) { onBedRemovedInternal(key); }
                continue;
            }
            synchronized (this) {
                BuildingRecord existing = getAt(bed);
                if (claimedBeds.contains(key) || existing != null) {
                    // A second bed found inside an already-known house is bound to that same
                    // building (e.g. a side-room bed, or a second bed in one house) instead of
                    // being silently dropped after a separate flood fill.
                    if (existing != null && existing.addBed(bed)) dirty();
                    claimedBeds.add(key);
                    continue;
                }
            }

            BuildingRecord record = BuildingLocator.locateBed(world, bed);
            synchronized (this) {
                if (record != null) {
                    add(record);
                } else {
                    rejectedBeds.add(key);
                    dirty();
                }
            }
        }
    }

    private void onBedRemovedInternal(long key) {
        BuildingRecord hit = null;
        for (BuildingRecord r : byId.values()) {
            if (r.hasBed(key)) { hit = r; break; }
        }
        if (hit != null) {
            hit.removeBed(BlockPos.of(key));
            claimedBeds.remove(key);
            if (hit.beds.isEmpty()) remove(hit.id);
            else if (hit.seedBed.asLong() == key) {
                hit.seedBed = hit.beds.get(0).immutable();
                claimedBeds.add(hit.seedBed.asLong());
            }
            dirty();
            return;
        }
        claimedBeds.remove(key);
        rejectedBeds.remove(key);
    }

    private static List<Long> chunksOf(BuildingRecord r) {
        List<Long> out = new ArrayList<>();
        int x0 = r.boundsMin.getX() >> 4, x1 = r.boundsMax.getX() >> 4;
        int z0 = r.boundsMin.getZ() >> 4, z1 = r.boundsMax.getZ() >> 4;
        for (int cx = x0; cx <= x1; cx++)
            for (int cz = z0; cz <= z1; cz++) out.add(ChunkPos.asLong(cx, cz));
        return out;
    }

    // ── Persistence ───────────────────────────────────────────────────────

    public synchronized CompoundNBT writeNBT(CompoundNBT n) {
        ListNBT list = new ListNBT();
        for (BuildingRecord r : byId.values()) list.add(r.writeNBT());
        n.put("buildings", list);
        n.putLongArray("claimed", toArray(claimedBeds));
        n.putLongArray("rejected", toArray(rejectedBeds));
        n.putLongArray("pending", toArray(pendingSet));
        return n;
    }

    public synchronized void loadNBT(CompoundNBT n) {
        byId.clear();
        byChunk.clear();
        claimedBeds.clear();
        rejectedBeds.clear();
        pending.clear();
        pendingSet.clear();

        ListNBT list = n.getList("buildings", 10);
        for (int i = 0; i < list.size(); i++) {
            BuildingRecord r = BuildingRecord.readNBT(list.getCompound(i));
            byId.put(r.id, r);
            for (long ck : chunksOf(r)) byChunk.computeIfAbsent(ck, k -> new ArrayList<>()).add(r);
            claimedBeds.add(r.seedBed.asLong());
        }
        for (long k : n.getLongArray("claimed")) claimedBeds.add(k);
        for (long k : n.getLongArray("rejected")) rejectedBeds.add(k);
        for (long k : n.getLongArray("pending")) {
            if (claimedBeds.contains(k) || rejectedBeds.contains(k)) continue;
            if (pendingSet.add(k)) pending.add(k);
        }
    }

    private static long[] toArray(Set<Long> set) {
        long[] out = new long[set.size()];
        int i = 0;
        for (long v : set) out[i++] = v;
        return out;
    }
}
