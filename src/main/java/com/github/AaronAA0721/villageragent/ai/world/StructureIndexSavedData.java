package com.github.AaronAA0721.villageragent.ai.world;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.world.storage.WorldSavedData;

/**
 * Per-dimension {@link WorldSavedData} that owns and persists the shared
 * {@link WorldStructureIndex}.
 *
 * <p>It is created via {@link WorldStructureIndex#instance(World)} using the
 * {@code Supplier} overload of {@code DimensionSavedDataManager.computeIfAbsent}.
 * Forge hands us the constructed instance and then calls {@link #load(CompoundNBT)}
 * to hydrate it from disk (when a save file exists) — so a world reload never
 * re-floods anything; the buildings (plus the claimed / rejected / pending bed
 * caches) come straight back from NBT.
 */
public class StructureIndexSavedData extends WorldSavedData {
    public static final String DATA_NAME = "villageragent_structures";

    private final WorldStructureIndex index;

    public StructureIndexSavedData(WorldStructureIndex index) {
        super(DATA_NAME);
        this.index = index;
        index.attach(this);
    }

    @Override
    public CompoundNBT save(CompoundNBT compound) {
        return index.writeNBT(compound);
    }

    @Override
    public void load(CompoundNBT compound) {
        index.loadNBT(compound);
    }
}
