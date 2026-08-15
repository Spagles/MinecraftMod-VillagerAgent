package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.world.server.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
// VillagerNeedsSystem is in the same package — no explicit import needed, but referenced directly.

/**
 * Manages the daily planning lifecycle for each villager:
 * <ol>
 *   <li>At dawn (dayTime 0–2000) generates a new {@link DailySchedule} via LLM (once per day).</li>
 *   <li>Every slow tick: applies the current scheduled task to guide the agent's activity.</li>
 *   <li>At nightfall (dayTime 13000–15000) generates a brief evening reflection (once per day).</li>
 * </ol>
 */
public class VillagerSchedulePlanner {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final long MINECRAFT_DAY     = 24_000L;
    private static final long PLAN_WINDOW_END   =  2_000L; // plan fires in first 2000 ticks of day
    private static final long REFLECT_START     = 13_000L;
    private static final long REFLECT_END       = 15_000L;

    // ── Public entry point ───────────────────────────────────────────────────

    public static void tick(ServerWorld world, VillagerEntity villager, VillagerAgentData agent) {
        long gameTime = world.getGameTime();
        long dayTime  = world.getDayTime() % MINECRAFT_DAY;
        long today    = gameTime / MINECRAFT_DAY;

        // 1. Daily planning at dawn
        if (agent.getLastDayPlanned() < today && dayTime < PLAN_WINDOW_END) {
            agent.setLastDayPlanned(today); // prevent LLM spam before async response
            generateDailyPlan(agent, today);
        }

        // 2. Apply current task to guide agent behaviour
        DailySchedule schedule = agent.getDailySchedule();
        if (schedule != null) {
            DailySchedule.ScheduledTask task = schedule.getCurrentTask(dayTime);
            if (task != null) {
                applyTaskToAgent(agent, task);
            }
        }

        // 3. Evening reflection — once per day, when villager is not busy
        if (dayTime >= REFLECT_START && dayTime < REFLECT_END
                && agent.getLastDayReflected() < today
                && agent.getCurrentAction() == null) {
            agent.setLastDayReflected(today);
            generateReflection(agent);
        }
    }

    // ── Plan generation ──────────────────────────────────────────────────────

    private static void generateDailyPlan(VillagerAgentData agent, long dayNumber) {
        String env = agent.getEnvironmentSummary();
        String envText = (env != null && !env.isEmpty()) ? env : "a Minecraft village";

        List<String> memories = agent.getMemories();
        StringBuilder memSb = new StringBuilder();
        int memStart = Math.max(0, memories.size() - 3);
        for (int i = memStart; i < memories.size(); i++) {
            memSb.append("- ").append(memories.get(i)).append("\n");
        }

        String systemPrompt = "You are a Minecraft villager creating your daily schedule. "
                + "Be practical and stay in character. Respond ONLY with the schedule, no extra text.";

        String needsMood = VillagerNeedsSystem.buildNeedsDescription(agent);
        String userPrompt = "Name: " + agent.getName() + "\n"
                + "Profession: " + agent.getProfession() + "\n"
                + "Personality: " + agent.getPersonality() + "\n"
                + "Current mood/feelings: " + (needsMood.isEmpty() ? "feeling normal" : needsMood) + "\n"
                + "Environment: " + envText + "\n"
                + "Recent memories:\n" + memSb + "\n"
                + "Create a daily schedule that reflects your mood and needs. Format EACH line EXACTLY as:\n"
                + "morning: [activity] - [description]\n"
                + "afternoon: [activity] - [description]\n"
                + "evening: [activity] - [description]\n"
                + "night: [activity] - [description]\n"
                + "Activities must be ONE of: farming, socializing, crafting, exploring, resting";

        LLMService.queryLLM(systemPrompt, userPrompt).thenAccept(response -> {
            DailySchedule schedule = parseDailyPlan(response, dayNumber);
            if (schedule.getTasks().isEmpty()) {
                schedule = createFallbackPlan(agent.getProfession(), dayNumber);
            }
            agent.setDailySchedule(schedule);
            agent.addMemory("Today's plan: " + schedule.toSummaryString());
            LOGGER.info(agent.getName() + " planned their day: " + schedule.toSummaryString());
        }).exceptionally(e -> {
            LOGGER.warn(agent.getName() + " plan failed, using fallback: " + e.getMessage());
            agent.setDailySchedule(createFallbackPlan(agent.getProfession(), dayNumber));
            return null;
        });
    }

    // ── Parse LLM response ───────────────────────────────────────────────────

    private static DailySchedule parseDailyPlan(String response, long dayNumber) {
        DailySchedule schedule = new DailySchedule(dayNumber);
        for (String raw : response.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int colonIdx = line.indexOf(":");
            if (colonIdx <= 0) continue;
            String slot = line.substring(0, colonIdx).trim().toLowerCase();
            if (!isValidSlot(slot)) continue;
            String rest    = line.substring(colonIdx + 1).trim();
            int dashIdx    = rest.indexOf(" - ");
            String activity, description;
            if (dashIdx > 0) {
                activity    = rest.substring(0, dashIdx).trim().toLowerCase();
                description = rest.substring(dashIdx + 3).trim();
            } else {
                activity    = rest.split(" ")[0].toLowerCase();
                description = rest;
            }
            if (isValidActivity(activity)) {
                schedule.addTask(new DailySchedule.ScheduledTask(slot, activity, description));
            }
        }
        return schedule;
    }

    private static boolean isValidSlot(String s) {
        return s.equals("morning") || s.equals("afternoon") || s.equals("evening") || s.equals("night");
    }

    private static boolean isValidActivity(String a) {
        return a.equals("farming") || a.equals("socializing") || a.equals("crafting")
                || a.equals("exploring") || a.equals("resting");
    }

    // ── Fallback plan (no LLM) ───────────────────────────────────────────────

    private static DailySchedule createFallbackPlan(String profession, long dayNumber) {
        DailySchedule s = new DailySchedule(dayNumber);
        if ("farmer".equalsIgnoreCase(profession)) {
            s.addTask(new DailySchedule.ScheduledTask("morning",   "farming",    "Check and harvest mature crops"));
            s.addTask(new DailySchedule.ScheduledTask("afternoon", "farming",    "Plant seeds in empty farmland"));
            s.addTask(new DailySchedule.ScheduledTask("evening",   "socializing","Chat with neighbours"));
            s.addTask(new DailySchedule.ScheduledTask("night",     "resting",    "Sleep until dawn"));
        } else {
            s.addTask(new DailySchedule.ScheduledTask("morning",   "crafting",   "Work at the workshop"));
            s.addTask(new DailySchedule.ScheduledTask("afternoon", "socializing","Visit the village centre"));
            s.addTask(new DailySchedule.ScheduledTask("evening",   "resting",    "Wind down for the evening"));
            s.addTask(new DailySchedule.ScheduledTask("night",     "resting",    "Sleep peacefully"));
        }
        return s;
    }

    // ── Task execution (guide agent activity) ────────────────────────────────

    /**
     * Translates the scheduled task into {@code agent.scheduledActivity} without
     * overriding actions already in progress (farming, combat, etc.).
     */
    private static void applyTaskToAgent(VillagerAgentData agent, DailySchedule.ScheduledTask task) {
        if (agent.getCurrentAction() != null) return; // busy — don't interfere
        if (agent.isInFarmingState()) return;

        String activity = task.getActivity();
        String current  = agent.getCurrentActivity();
        // Don't override active farming/fighting labels
        if (("farming".equals(activity) || "farming".equals(current))
                && ("harvesting".equals(current) || "planting".equals(current))) return;
        if ("fighting".equals(current)) return;

        agent.setScheduledActivity(activity);
    }

    // ── Evening reflection ───────────────────────────────────────────────────

    private static void generateReflection(VillagerAgentData agent) {
        List<String> memories = agent.getMemories();
        if (memories.isEmpty()) return;

        int start = Math.max(0, memories.size() - 8);
        StringBuilder memSb = new StringBuilder();
        for (int i = start; i < memories.size(); i++) {
            memSb.append("- ").append(memories.get(i)).append("\n");
        }

        String systemPrompt = "Generate one reflective first-person sentence for a Minecraft villager about their day. "
                + "Stay in character. Output only the sentence — no labels or extra text.";
        String userPrompt   = agent.getName() + " is a " + agent.getProfession() + ".\n"
                + "Today's events:\n" + memSb
                + "Generate one reflective sentence about today.";

        LLMService.queryLLM(systemPrompt, userPrompt).thenAccept(reflection -> {
            if (reflection != null && !reflection.trim().isEmpty()) {
                agent.addMemory("[Reflection] " + reflection.trim());
                LOGGER.info(agent.getName() + " reflected: " + reflection.trim());
            }
        }).exceptionally(e -> {
            LOGGER.debug(agent.getName() + " reflection failed: " + e.getMessage());
            return null;
        });
    }
}


