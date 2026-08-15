package com.github.AaronAA0721.villageragent.ai.memory;

/**
 * High-level content tags for a chunk, stored as a bitmask inside {@link ChunkMemory}.
 *
 * <p>This is the "label" layer the chunk memory is built around: instead of caching the
 * individual blocks a villager walked past, a chunk is reduced to a handful of bits
 * ("forest", "farmland", "has ore"...). The whole tag set costs 4 bytes.
 *
 * <p>Ordinals are used for the bitmask and are persisted — <b>append new tags at the end</b>,
 * never reorder.
 */
public enum ChunkTag {
    KNOWN,        // seen from a neighbouring chunk, never actually sampled
    FOREST,
    FARMLAND,
    WATER,
    VILLAGE,
    ORE,
    DANGER_LAVA,
    HOSTILES,     // hostile mobs were around when last visited
    ANIMALS;

    private static final ChunkTag[] VALUES = values();

    public int bit() {
        return 1 << ordinal();
    }

    /** Lower-case label used in LLM text and legacy NBT ("danger_lava", "forest", ...). */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ChunkTag byLabel(String label) {
        for (ChunkTag t : VALUES) if (t.label().equals(label)) return t;
        return null;
    }
}
