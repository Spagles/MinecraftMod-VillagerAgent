package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * LLM-facing entry point for large-scale building.
 *
 * <p>The LLM is asked to return a strict JSON "structure" (an anchor + a list of relative
 * blocks). We parse it, validate it with {@link BuildOrderPlanner#validateStructure}, and either
 * start a {@link BuildJob} or — if it's impossible to build — send the errors back to the LLM
 * for up to {@code BUILD_MAX_RETRIES} corrections (e.g. "connect it to the ground", "you're 3 oak
 * planks short"). This keeps the LLM honest without burning tokens in an infinite loop.
 */
public class StructureBuilder {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String SYS_PROMPT =
            "You are a master Minecraft builder. The player/villager asked you to design a structure. "
            + "Respond with ONE strict JSON object and NOTHING else (no prose, no markdown fences). "
            + "Schema:\n"
            + "{\n"
            + "  \"name\": \"short structure name\",\n"
            + "  \"anchor\": [\"RELATIVE\", dx, dy, dz],\n"
            + "  \"blocks\": [ { \"x\": int, \"y\": int, \"z\": int, \"block\": \"minecraft:block_id\" } ]\n"
            + "}\n"
            + "Rules:\n"
            + "- Coordinates are integers relative to the structure's origin (0,0,0); they may be negative.\n"
            + "- \"anchor\" places the origin RELATIVE to the villager: [\"RELATIVE\", dx, dy, dz] offsets the "
            + "origin from the villager by (dx,dy,dz). Keep the whole structure in front of / beside the villager.\n"
            + "- Every block's id must be a real Minecraft block (e.g. minecraft:oak_planks, minecraft:oak_log, "
            + "minecraft:stone, minecraft:cobblestone, minecraft:glass). Use only blocks a villager can carry.\n"
            + "- The structure MUST be connected to the ground or to an existing block (no floating islands with "
            + "no attachment). Prefer a floor that touches the ground.\n"
            + "- Keep it modest (<= ~60 blocks) so a single villager can build it. Do not include air gaps as blocks.";

    private static final String SYS_REVISE =
            "You are a master Minecraft builder. Your previous structure JSON could not be built. "
            + "Respond with ONE corrected strict JSON object and NOTHING else (no prose, no markdown fences). "
            + "Use the same schema as before:\n"
            + "{\n"
            + "  \"name\": \"short structure name\",\n"
            + "  \"anchor\": [\"RELATIVE\", dx, dy, dz],\n"
            + "  \"blocks\": [ { \"x\": int, \"y\": int, \"z\": int, \"block\": \"minecraft:block_id\" } ]\n"
            + "}\n"
            + "Fix the problems listed by the reviewer. Keep the structure connected to the ground and use only "
            + "real, carryable Minecraft block ids.";

    /**
     * Ask the LLM for a structure matching {@code goalText} and begin building it (or revise).
     */
    public static void requestStructure(VillagerAgentData agent, VillagerEntity villager,
                                         ServerWorld world, String goalText) {
        String user = "Design and lay out a structure for this goal: \"" + goalText + "\". Return only the JSON.";
        LLMService.queryLLM(SYS_PROMPT, user).thenAccept(json -> {
            try {
                BuildOrderPlanner.Structure s = parseStructure(json);
                BlockPos base = resolveBase(villager, s);
                BuildOrderPlanner.ValidationResult r =
                        BuildOrderPlanner.validateStructure(world, base, s, agent);
                if (r.ok) {
                    startBuildJob(agent, s, base, r);
                } else {
                    reviseWithLLM(agent, villager, world, goalText, s, r, ModConfig.BUILD_MAX_RETRIES.get());
                }
            } catch (Exception e) {
                LOGGER.warn(agent.getName() + " structure parse failed: " + e.getMessage());
                reviseWithLLM(agent, villager, world, goalText, null,
                        parseErrorResult(), ModConfig.BUILD_MAX_RETRIES.get());
            }
        }).exceptionally(e -> {
            LOGGER.warn(agent.getName() + " LLM structure request failed: " + e.getMessage());
            return null;
        });
    }

    // ── Internal ──

    private static void reviseWithLLM(VillagerAgentData agent, VillagerEntity villager, ServerWorld world,
                                      String goalText, BuildOrderPlanner.Structure last,
                                      BuildOrderPlanner.ValidationResult prev, int retriesLeft) {
        if (retriesLeft <= 0) {
            agent.addMemory("Wanted to build but the design kept being impossible — gave up.");
            LOGGER.debug(agent.getName() + " gave up building (out of retries)");
            return;
        }
        StringBuilder feedback = new StringBuilder();
        feedback.append("Your previous structure could not be built. Problems:\n");
        for (String err : prev.errors) feedback.append("- ").append(err).append("\n");
        if (last != null) {
            feedback.append("Previous (invalid) JSON, for reference, was for structure \"")
                    .append(last.name).append("\". Return a corrected version now.");
        } else {
            feedback.append("Return a corrected JSON structure now (strict schema only).");
        }

        LLMService.queryLLM(SYS_REVISE, feedback.toString()).thenAccept(json -> {
            try {
                BuildOrderPlanner.Structure s = parseStructure(json);
                BlockPos base = resolveBase(villager, s);
                BuildOrderPlanner.ValidationResult r =
                        BuildOrderPlanner.validateStructure(world, base, s, agent);
                if (r.ok) {
                    startBuildJob(agent, s, base, r);
                } else {
                    reviseWithLLM(agent, villager, world, goalText, s, r, retriesLeft - 1);
                }
            } catch (Exception e) {
                LOGGER.warn(agent.getName() + " structure re-parse failed: " + e.getMessage());
                reviseWithLLM(agent, villager, world, goalText, last, parseErrorResult(), retriesLeft - 1);
            }
        }).exceptionally(e -> {
            LOGGER.warn(agent.getName() + " LLM structure revise failed: " + e.getMessage());
            return null;
        });
    }

    private static void startBuildJob(VillagerAgentData agent, BuildOrderPlanner.Structure s,
                                      BlockPos base, BuildOrderPlanner.ValidationResult r) {
        BuildJob job = new BuildJob(s.name, base, r.steps);
        agent.setBuildJob(job);
        agent.setCurrentActivity("building");
        agent.addMemory("Started building " + s.name + " (" + r.steps.size() + " blocks)");
        LOGGER.info(agent.getName() + " started building '" + s.name + "' with " + r.steps.size() + " blocks");
    }

    private static BlockPos resolveBase(VillagerEntity villager, BuildOrderPlanner.Structure s) {
        if ("ABSOLUTE".equalsIgnoreCase(s.anchorMode)) {
            return new BlockPos(s.anchorX, s.anchorY, s.anchorZ);
        }
        return villager.blockPosition().offset(s.anchorX, s.anchorY, s.anchorZ);
    }

    private static BuildOrderPlanner.ValidationResult parseErrorResult() {
        BuildOrderPlanner.ValidationResult r = new BuildOrderPlanner.ValidationResult();
        r.ok = false;
        r.errors.add("Could not parse your response as valid structure JSON — return only the strict JSON object.");
        return r;
    }

    /** Parse the LLM's JSON into a {@link BuildOrderPlanner.Structure}. Throws on any problem. */
    static BuildOrderPlanner.Structure parseStructure(String json) throws Exception {
        String trimmed = json.trim();
        int s = trimmed.indexOf('{');
        int e = trimmed.lastIndexOf('}');
        if (s < 0 || e < 0) throw new RuntimeException("no JSON object found");
        JsonObject root = new JsonParser().parse(trimmed.substring(s, e + 1)).getAsJsonObject();

        BuildOrderPlanner.Structure st = new BuildOrderPlanner.Structure();
        if (root.has("name")) st.name = root.get("name").getAsString();

        if (root.has("anchor") && root.get("anchor").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("anchor");
            if (arr.size() >= 1) st.anchorMode = arr.get(0).getAsString();
            if (arr.size() >= 4) {
                st.anchorX = arr.get(1).getAsInt();
                st.anchorY = arr.get(2).getAsInt();
                st.anchorZ = arr.get(3).getAsInt();
            }
        }

        JsonArray blocks = root.getAsJsonArray("blocks");
        if (blocks == null || blocks.size() == 0) throw new RuntimeException("no blocks");
        for (JsonElement be : blocks) {
            JsonObject b = be.getAsJsonObject();
            int x = b.get("x").getAsInt();
            int y = b.get("y").getAsInt();
            int z = b.get("z").getAsInt();
            String reg = b.get("block").getAsString();
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(reg));
            if (block == Blocks.AIR || block == null) throw new RuntimeException("unknown block " + reg);
            st.blocks.add(new BuildOrderPlanner.BlockSpec(x, y, z, block));
        }
        return st;
    }
}
