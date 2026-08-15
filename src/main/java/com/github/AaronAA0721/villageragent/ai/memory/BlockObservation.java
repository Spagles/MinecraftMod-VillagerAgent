package com.github.AaronAA0721.villageragent.ai.memory;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.math.BlockPos;

/**
 * A "notable" block the villager chose to remember from a chunk.
 *
 * <p>Only a small allow-list of blocks are stored (workbenches, furnaces, chests,
 * beds, doors, crops, ore, lava) so {@code notableBlocks} stays tiny.
 *
 * <p>{@link #localPos} is relative to the owning chunk's origin (x in 0..15,
 * z in 0..15) to save memory; use {@link #toGlobal(long)} to recover the world
 * coordinate given the chunk key ({@code ChunkPos.asLong}).
 */
public class BlockObservation {
    public BlockPos localPos;     // relative to chunk origin (0..15, y, 0..15)
    public String blockId;        // e.g. "minecraft:crafting_table"
    public String note;           // optional, e.g. "crops ready to harvest", "chest"

    public BlockObservation(BlockPos localPos, String blockId) {
        this(localPos, blockId, null);
    }

    public BlockObservation(BlockPos localPos, String blockId, String note) {
        this.localPos = localPos;
        this.blockId = blockId;
        this.note = note;
    }

    /** Reconstruct the global BlockPos from a chunk key + this observation's local pos. */
    public BlockPos toGlobal(long chunkKey) {
        // ChunkPos.asLong packs as (long) z << 32 | ((long) x & 0xFFFFFFFFL)
        int x = (int) (chunkKey & 0xFFFFFFFFL);
        int z = (int) (chunkKey >>> 32);
        return new BlockPos(x * 16 + localPos.getX(), localPos.getY(), z * 16 + localPos.getZ());
    }

    public CompoundNBT writeNBT() {
        CompoundNBT n = new CompoundNBT();
        n.putInt("lx", localPos.getX());
        n.putInt("ly", localPos.getY());
        n.putInt("lz", localPos.getZ());
        n.putString("id", blockId);
        if (note != null) n.putString("note", note);
        return n;
    }

    public static BlockObservation readNBT(CompoundNBT n) {
        BlockPos p = new BlockPos(n.getInt("lx"), n.getInt("ly"), n.getInt("lz"));
        BlockObservation o = new BlockObservation(p, n.getString("id"),
                n.contains("note") ? n.getString("note") : null);
        return o;
    }
}
