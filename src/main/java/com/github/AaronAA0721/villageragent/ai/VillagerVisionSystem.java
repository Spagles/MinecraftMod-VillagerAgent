package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.ai.memory.BlockObservation;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkFeature;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkMemory;
import com.github.AaronAA0721.villageragent.ai.memory.ChunkTag;
import com.github.AaronAA0721.villageragent.ai.memory.EntityCategory;
import com.github.AaronAA0721.villageragent.ai.memory.EntityObservation;
import com.github.AaronAA0721.villageragent.ai.vision.ChunkContentSampler;
import com.github.AaronAA0721.villageragent.ai.vision.DetailedViewRecorder;
import com.github.AaronAA0721.villageragent.ai.vision.FrustumCuller;
import com.github.AaronAA0721.villageragent.ai.world.BuildingRecord;
import com.github.AaronAA0721.villageragent.ai.world.WorldStructureIndex;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Vision system for villagers — builds a natural-language environment summary
 * for injection into LLM prompts.
 *
 * <p>The summary is now assembled in three layers (design doc §6 + §3 / §4 / §5.6):
 * <ul>
 *   <li><b>Far</b> — chunk memory: what the villager generally knows about the area
 *       (forest / farmland / water / village / ore / lava), built by {@link ChunkContentSampler}.</li>
 *   <li><b>Mid</b> — buildings from the shared {@link WorldStructureIndex}
 *       (village houses, player-built houses, and cave houses).</li>
 *   <li><b>Near</b> — what is directly in the villager's view cone right now
 *       (notable blocks + entities), via {@link FrustumCuller}.</li>
 * </ul>
 *
 * <p>This replaces the old random-sampling feature detection (countNearbyLogs /
 * detectCave / detectWater) with deterministic, cached memory lookups.
 */
public class VillagerVisionSystem {
    private static final Logger LOGGER = LogManager.getLogger();

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Build a rich environment summary for LLM injection.
     *
     * @param villager The villager entity.
     * @param world    The server world.
     * @param agent    The agent's data (for chunk memory). May be null for legacy callers.
     * @return A multi-sentence environment description, never null.
     */
    public static String buildEnvironmentSummary(LivingEntity villager, ServerWorld world,
                                                 VillagerAgentData agent) {
        BlockPos pos = villager.blockPosition();
        StringBuilder sb = new StringBuilder();

        // ── Time / Weather / Biome ──
        sb.append(getTimeDescription(world)).append(" ");
        sb.append(getWeatherDescription(world, villager)).append(" ");
        sb.append(getBiomeDescription(world, pos)).append(" ");

        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        // ── Far layer: chunk memory summary (replaces random sampling) ──
        if (agent != null) {
            agent.updateChunkMemory(villager, world, cx, cz);
            ChunkMemory cm = agent.getChunkMemory(cx, cz);
            if (cm != null) appendChunkSummary(sb, cm);
        }

        // ── Mid layer: buildings from the shared index (incl. cave houses) ──
        List<BuildingRecord> buildings = WorldStructureIndex.instance(world).queryNear(pos, 3);
        if (!buildings.isEmpty()) {
            sb.append("Nearby buildings: ");
            int lim = Math.min(buildings.size(), 4);
            for (int i = 0; i < lim; i++) {
                BuildingRecord r = buildings.get(i);
                sb.append(r.coarseType).append(" (bed at ")
                        .append(r.seedBed.getX()).append(",").append(r.seedBed.getZ()).append(")");
                if (i < lim - 1) sb.append(", ");
            }
            sb.append(". ");
        }

        // ── Near layer: what is directly in view (frustum, live scan) ──
        appendFrustumView(sb, villager, world);

        // ── Exact position ──
        sb.append(String.format("Your current position is X=%d, Y=%d, Z=%d. ", pos.getX(), pos.getY(), pos.getZ()));
        sb.append(String.format("You are in chunk (%d, %d). ", cx, cz));

        // ── Chunk context ──
        sb.append(getInSightChunksDescription(cx, cz, villager.yRot, agent));

        if (agent != null && !agent.getVisitedChunks().isEmpty()) {
            sb.append(getKnownChunksDescription(agent.getVisitedChunks(), cx, cz));
        }

        return sb.toString().trim();
    }

    /**
     * Overload without agent — used by callers that have not yet migrated.
     * Chunk memory and buildings will be omitted.
     */
    public static String buildEnvironmentSummary(LivingEntity villager, ServerWorld world) {
        return buildEnvironmentSummary(villager, world, null);
    }

    // -----------------------------------------------------------------------
    //  Far layer: chunk-memory summary
    // -----------------------------------------------------------------------

    private static void appendChunkSummary(StringBuilder sb, ChunkMemory cm) {
        List<String> parts = new ArrayList<>();
        if (cm.has(ChunkTag.FOREST))      parts.add("a forest");
        if (cm.has(ChunkTag.FARMLAND))    parts.add("farmland");
        if (cm.has(ChunkTag.WATER))       parts.add("water");
        if (cm.has(ChunkTag.VILLAGE))     parts.add("a village");
        if (cm.has(ChunkTag.ORE))         parts.add("exposed ore");
        if (cm.has(ChunkTag.DANGER_LAVA)) parts.add("lava (danger)");
        if (!parts.isEmpty()) {
            sb.append("The area around you is ").append(String.join(", ", parts)).append(". ");
        }

        // Recall the features memorised for this chunk (one anchor position each)
        if (cm.has(ChunkFeature.CRAFTING_TABLE)) sb.append("There is a crafting table nearby. ");
        if (cm.has(ChunkFeature.CHEST) || cm.has(ChunkFeature.BARREL)) {
            sb.append("There are storage containers nearby. ");
        }
        if (cm.has(ChunkFeature.BED)) sb.append("There is a bed nearby. ");

        // Recall entities seen when entering (counts only — positions would be stale)
        if (cm.entityCount(EntityCategory.HOSTILE) > 0) {
            sb.append("You remember seeing hostile creatures around here. ");
        }
        if (cm.entityCount(EntityCategory.ANIMAL) > 0) {
            sb.append("There were animals nearby earlier. ");
        }
    }

    // -----------------------------------------------------------------------
    //  Near layer: frustum view (what is in front of the villager right now)
    // -----------------------------------------------------------------------

    private static void appendFrustumView(StringBuilder sb, LivingEntity villager, ServerWorld world) {
        // Live frustum scan — what is actually in front of the villager right now.
        DetailedViewRecorder.ViewSnapshot snap = DetailedViewRecorder.record(villager, world);

        // Notable blocks within the view cone (live scan)
        if (!snap.blocks.isEmpty()) {
            List<String> seen = new ArrayList<>();
            for (BlockObservation b : snap.blocks) {
                if (seen.size() >= 4) break;
                String n = humanBlockName(b.blockId);
                if (b.note != null && !b.note.isEmpty()) n += " (" + b.note + ")";
                seen.add(n);
            }
            sb.append("In front of you, you can see ").append(String.join(", ", seen)).append(". ");
        }

        // Entities within the view cone (live scan)
        int hostiles = 0, animals = 0, villagers = 0, players = 0;
        for (EntityObservation e : snap.entities) {
            switch (e.category) {
                case HOSTILE:  hostiles++;  break;
                case ANIMAL:   animals++;   break;
                case VILLAGER: villagers++; break;
                case PLAYER:   players++;   break;
                default: break;
            }
        }
        if (hostiles > 0)  sb.append("A hostile creature is right in front of you! ");
        if (animals > 0)   sb.append("There are animals in your view. ");
        if (villagers > 0) sb.append("Another villager is nearby in front of you. ");
        if (players > 0)   sb.append("The player is in your view. ");
    }

    /** Best-effort human name for a registry id like "minecraft:crafting_table". */
    private static String humanBlockName(String id) {
        String name = id.replace("minecraft:", "").replace('_', ' ');
        return name;
    }

    // -----------------------------------------------------------------------
    //  Time and weather helpers
    // -----------------------------------------------------------------------

    private static String getTimeDescription(ServerWorld world) {
        long time = world.getDayTime() % 24000L;
        String phase;
        if (time < 1000)       phase = "early morning";
        else if (time < 6000)  phase = "daytime";
        else if (time < 12000) phase = "afternoon";
        else if (time < 13000) phase = "sunset";
        else if (time < 18000) phase = "nighttime";
        else                   phase = "deep night";
        return String.format("It is %s (time %d/24000 in the day cycle).", phase, time);
    }

    private static String getWeatherDescription(ServerWorld world, LivingEntity villager) {
        if (world.isThundering()) {
            return "There is a thunderstorm.";
        } else if (world.isRaining()) {
            if (world.isRainingAt(villager.blockPosition())) {
                return "It is raining and " + villager.getName().getString() + " is getting wet.";
            }
            return "It is raining outside.";
        }
        return "The weather is clear.";
    }

    // -----------------------------------------------------------------------
    //  Biome helper
    // -----------------------------------------------------------------------

    private static String getBiomeDescription(ServerWorld world, BlockPos pos) {
        Biome biome = world.getBiome(pos);
        Biome.Category cat = biome.getBiomeCategory();
        String biomeName = world.registryAccess()
                .registryOrThrow(net.minecraft.util.registry.Registry.BIOME_REGISTRY)
                .getKey(biome) != null
                ? world.registryAccess()
                       .registryOrThrow(net.minecraft.util.registry.Registry.BIOME_REGISTRY)
                       .getKey(biome).toString()
                : cat.name().toLowerCase();
        String desc;
        switch (cat) {
            case FOREST:        desc = "a forest"; break;
            case PLAINS:        desc = "open plains"; break;
            case DESERT:        desc = "dry sandy desert"; break;
            case EXTREME_HILLS: desc = "mountainous terrain"; break;
            case OCEAN:         desc = "the ocean"; break;
            case RIVER:         desc = "a river valley"; break;
            case BEACH:         desc = "a beach"; break;
            case SWAMP:         desc = "a swamp"; break;
            case TAIGA:         desc = "a cold taiga with spruce trees"; break;
            case JUNGLE:        desc = "a dense jungle"; break;
            case NETHER:        desc = "the Nether (hellish dimension)"; break;
            case THEEND:        desc = "the End dimension"; break;
            case SAVANNA:       desc = "a warm savanna"; break;
            case ICY:           desc = "a frozen icy landscape"; break;
            case MUSHROOM:      desc = "a mushroom island with giant mushrooms"; break;
            default:            desc = "an unknown biome type"; break;
        }
        return String.format("The biome is %s (%s).", desc, biomeName);
    }

    // -----------------------------------------------------------------------
    //  Chunk sight helpers
    // -----------------------------------------------------------------------

    /**
     * Direction table — 8 compass directions mapped from yRot ranges.
     * In Minecraft: yRot 0 = South (+Z), 90 = West (-X), ±180 = North (-Z), -90 = East (+X).
     */
    private static final int[][] DIR_DX_DZ = {
        { 0, -1}, { 1, -1}, { 1,  0}, { 1,  1},
        { 0,  1}, {-1,  1}, {-1,  0}, {-1, -1}
    };
    private static final String[] DIR_NAMES = {"North","NE","East","SE","South","SW","West","NW"};

    private static int getDirectionIndex(float yRot) {
        float angle = ((yRot + 180f) % 360f + 360f) % 360f;
        int idx = (int) ((angle + 22.5f) / 45f) % 8;
        return idx;
    }

    private static String getInSightChunksDescription(int cx, int cz, float yRot,
                                                      VillagerAgentData agent) {
        int dir = getDirectionIndex(yRot);
        int[] primary  = DIR_DX_DZ[dir];
        int[] leftDiag = DIR_DX_DZ[(dir + 7) % 8];
        int[] rightDiag= DIR_DX_DZ[(dir + 1) % 8];

        int[][] sightOffsets = {
            {0, 0},
            {primary[0],   primary[1]},
            {leftDiag[0],  leftDiag[1]},
            {rightDiag[0], rightDiag[1]}
        };

        Set<Long> known = (agent != null) ? agent.getVisitedChunks() : java.util.Collections.emptySet();
        StringBuilder sb = new StringBuilder("Chunks in sight (facing " + DIR_NAMES[dir] + "): ");
        for (int i = 0; i < sightOffsets.length; i++) {
            int scx = cx + sightOffsets[i][0];
            int scz = cz + sightOffsets[i][1];
            long key = ChunkPos.asLong(scx, scz);
            String tag = known.contains(key) ? "known" : "unknown";
            sb.append(String.format("(%d,%d)[%s]", scx, scz, tag));
            if (i < sightOffsets.length - 1) sb.append(", ");
        }
        sb.append(". ");
        return sb.toString();
    }

    private static String getKnownChunksDescription(Set<Long> visitedChunks, int curCx, int curCz) {
        List<String> tokens = new ArrayList<>(visitedChunks.size());
        for (long key : visitedChunks) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            tokens.add("(" + x + "," + z + ")");
        }
        return "All chunks in your memory (" + tokens.size() + " total): "
                + String.join(" ", tokens) + ". ";
    }
}
