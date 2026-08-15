package com.github.AaronAA0721.villageragent.ai.memory;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.math.BlockPos;

/**
 * A remembered entity (mob / animal / player / dropped item) seen near the villager.
 *
 * <p>Note: entities move, so {@link #globalPos} is only accurate at {@link #seenTick}.
 * Consumers should treat stale observations as "I once saw an X around here" and,
 * if they need a precise current position, re-scan with the frustum view recorder.
 */
public class EntityObservation {
    public String entityId;        // e.g. "minecraft:creeper" / "minecraft:cow"
    public EntityCategory category; // quick filter: hostile / animal / villager / player / item
    public BlockPos globalPos;     // world position at the moment of sighting
    public long seenTick;          // game time when seen (freshness check)

    public EntityObservation(String entityId, EntityCategory category, BlockPos globalPos, long seenTick) {
        this.entityId = entityId;
        this.category = category;
        this.globalPos = globalPos;
        this.seenTick = seenTick;
    }

    public CompoundNBT writeNBT() {
        CompoundNBT n = new CompoundNBT();
        n.putString("id", entityId);
        n.putString("cat", category.name());
        n.putLong("pos", globalPos.asLong());
        n.putLong("tick", seenTick);
        return n;
    }

    public static EntityObservation readNBT(CompoundNBT n) {
        return new EntityObservation(
                n.getString("id"),
                EntityCategory.valueOf(n.getString("cat")),
                BlockPos.of(n.getLong("pos")),
                n.getLong("tick"));
    }
}
