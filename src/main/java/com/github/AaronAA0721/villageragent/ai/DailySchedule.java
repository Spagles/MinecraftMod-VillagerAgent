package com.github.AaronAA0721.villageragent.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the villager's plan for one Minecraft day.
 * Generated once at dawn by {@link VillagerSchedulePlanner}.
 *
 * <p>Minecraft time slots (ticks within a 24 000-tick day):
 * <ul>
 *   <li>morning   [0,    6 000) — sunrise to noon</li>
 *   <li>afternoon [6 000, 12 000) — noon to sunset</li>
 *   <li>evening   [12 000, 18 000) — sunset to midnight</li>
 *   <li>night     [18 000, 24 000) — midnight to pre-dawn</li>
 * </ul>
 */
public class DailySchedule {

    public static final long MORNING_START   = 0L;
    public static final long AFTERNOON_START = 6_000L;
    public static final long EVENING_START   = 12_000L;
    public static final long NIGHT_START     = 18_000L;

    // ── Inner class ──────────────────────────────────────────────────────────

    /** One entry in the day's plan. */
    public static class ScheduledTask {
        private final String timeSlot;   // "morning" | "afternoon" | "evening" | "night"
        private final String activity;   // "farming" | "socializing" | "crafting" | "exploring" | "resting"
        private final String description;
        private boolean completed;

        public ScheduledTask(String timeSlot, String activity, String description) {
            this.timeSlot    = timeSlot;
            this.activity    = activity;
            this.description = description;
            this.completed   = false;
        }

        public String  getTimeSlot()   { return timeSlot; }
        public String  getActivity()   { return activity; }
        public String  getDescription(){ return description; }
        public boolean isCompleted()   { return completed; }
        public void    setCompleted(boolean v) { this.completed = v; }

        /** First tick (within the day) at which this task is active. */
        public long getStartTick() {
            switch (timeSlot.toLowerCase()) {
                case "morning":   return MORNING_START;
                case "afternoon": return AFTERNOON_START;
                case "evening":   return EVENING_START;
                case "night":     return NIGHT_START;
                default:          return MORNING_START;
            }
        }

        @Override
        public String toString() {
            return timeSlot + ": " + activity + " - " + description;
        }
    }

    // ── Schedule state ────────────────────────────────────────────────────────

    private final List<ScheduledTask> tasks;
    private final long dayNumber;

    public DailySchedule(long dayNumber) {
        this.tasks     = new ArrayList<>();
        this.dayNumber = dayNumber;
    }

    public void addTask(ScheduledTask task) { tasks.add(task); }
    public List<ScheduledTask> getTasks()   { return tasks; }
    public long getDayNumber()              { return dayNumber; }

    /**
     * Returns the task that should be active right now, or {@code null} if
     * all tasks are completed or none have started yet.
     *
     * <p>Among all uncompleted tasks whose start tick ≤ {@code dayTimeTick},
     * the one with the highest start tick is returned (i.e., the most recent one).
     */
    public ScheduledTask getCurrentTask(long dayTimeTick) {
        ScheduledTask best = null;
        for (ScheduledTask task : tasks) {
            if (!task.isCompleted() && task.getStartTick() <= dayTimeTick) {
                if (best == null || task.getStartTick() > best.getStartTick()) {
                    best = task;
                }
            }
        }
        return best;
    }

    public boolean allCompleted() {
        return tasks.stream().allMatch(ScheduledTask::isCompleted);
    }

    /** Short human-readable summary for memories/logs. */
    public String toSummaryString() {
        if (tasks.isEmpty()) return "No plan for today";
        StringBuilder sb = new StringBuilder();
        for (ScheduledTask t : tasks) {
            sb.append(t.getTimeSlot()).append(":").append(t.getActivity()).append(", ");
        }
        String s = sb.toString();
        return s.substring(0, s.length() - 2); // trim trailing ", "
    }
}

