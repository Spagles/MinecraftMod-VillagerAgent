package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.block.Block;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A multi-block building task. Holds the anchor, the pre-computed ordered list of placement
 * steps (each with its target + legal standing cell), and a cursor marking the next block to
 * place. Serialised into the villager's NBT so an in-progress build survives a server restart.
 */
public class BuildJob {
    private UUID jobId;
    private String name;
    private BlockPos anchor;
    private final List<BuildOrderPlanner.Step> steps = new ArrayList<>();
    private int cursor;           // index of the next step to place

    public BuildJob() {}

    public BuildJob(String name, BlockPos anchor, List<BuildOrderPlanner.Step> steps) {
        this.jobId = UUID.randomUUID();
        this.name = name;
        this.anchor = anchor;
        this.steps.addAll(steps);
        this.cursor = 0;
    }

    public UUID getJobId() { return jobId; }
    public String getName() { return name; }
    public BlockPos getAnchor() { return anchor; }
    public List<BuildOrderPlanner.Step> getSteps() { return steps; }
    public int getCursor() { return cursor; }
    public void setCursor(int cursor) { this.cursor = cursor; }
    public int getTotal() { return steps.size(); }
    public boolean isComplete() { return cursor >= steps.size(); }

    /** The next step to place, or null if the job is finished. */
    public BuildOrderPlanner.Step currentStep() {
        return cursor < steps.size() ? steps.get(cursor) : null;
    }

    /**
     * Skip over steps whose target already holds the intended block (e.g. blocks that were
     * placed before a restart). Called after loading from NBT so we don't re-walk/re-place.
     */
    public void reconcileWithWorld(ServerWorld world) {
        while (cursor < steps.size()) {
            BuildOrderPlanner.Step st = steps.get(cursor);
            if (world.getBlockState(st.target).getBlock() == st.block) {
                cursor++;
            } else {
                break;
            }
        }
    }

    public CompoundNBT writeNBT() {
        CompoundNBT n = new CompoundNBT();
        n.putString("jobId", jobId.toString());
        n.putString("name", name);
        n.putLong("anchor", anchor.asLong());
        n.putInt("cursor", cursor);
        ListNBT list = new ListNBT();
        for (BuildOrderPlanner.Step st : steps) {
            CompoundNBT c = new CompoundNBT();
            c.putLong("target", st.target.asLong());
            c.putLong("stand", st.stand.asLong());
            c.putString("block", st.block.getRegistryName().toString());
            list.add(c);
        }
        n.put("steps", list);
        return n;
    }

    public static BuildJob readNBT(CompoundNBT n) {
        BuildJob j = new BuildJob();
        try {
            j.jobId = UUID.fromString(n.getString("jobId"));
        } catch (Exception ignore) {
            j.jobId = UUID.randomUUID();
        }
        j.name = n.getString("name");
        j.anchor = BlockPos.of(n.getLong("anchor"));
        j.cursor = n.getInt("cursor");
        ListNBT list = n.getList("steps", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT c = list.getCompound(i);
            BlockPos target = BlockPos.of(c.getLong("target"));
            BlockPos stand = BlockPos.of(c.getLong("stand"));
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(c.getString("block")));
            j.steps.add(new BuildOrderPlanner.Step(target, stand, block));
        }
        return j;
    }
}
