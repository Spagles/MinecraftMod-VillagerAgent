# VillagerAgent Mod — AI Agent Reference Guide

> **Purpose:** Fast on-boarding for an AI assistant picking up this project. Read this before touching any code.

---

## 1. Goal & Environment
Transform vanilla Minecraft 1.16.5 villagers into autonomous "Generative Agents" (Stanford-AI-Town style).
- **Platform:** Minecraft 1.16.5 · Forge · Java 8+
- **LLM Backend:** Configurable via `ModConfig` — Gemini (default), OpenAI, Anthropic, Ollama.
- **Day cycle:** 24 000 ticks per day. All time-based logic uses `world.getDayTime() % 24000`.
- **Thread safety:** All LLM calls are `CompletableFuture` (off server thread). Results are applied back with `world.getServer().execute(...)` or via `volatile` guards.

---

## 2. Package Layout
All source lives under `com.github.AaronAA0721.villageragent`.

| Sub-package | Purpose |
|---|---|
| `ai/` | All agent logic — see §3 |
| `config/` | `ModConfig` — Forge config values (API key, model, feature flags) |
| `events/` | `VillagerEventHandler` — wires tick methods into Forge events |
| `mixin/` | `VillagerEntityMixin` — suppresses vanilla AI when agent is active |
| `network/` | `ChatMessagePacket` — sends villager dialogue to clients |

---

## 3. Key Files in `ai/`

### Data & State
| File | Role |
|---|---|
| `VillagerAgentData` | **Central state bag** per villager. Holds name, profession, personality, memories (List\<String\>), relationships (Map\<UUID→int\>), inventory, goals, daily schedule, needs, chunk memory, mood (to be added). NBT-persisted via `VillagerAgentSavedData`. |
| `VillagerAgentSavedData` | WorldSavedData that serialises/deserialises all agent data across saves. |
| `AgentInventory` | Simple slot-based inventory. Items added/removed by farming/crafting systems. |
| `DailySchedule` | Holds 4 `ScheduledTask` entries (morning/afternoon/evening/night). `getCurrentTask(dayTimeTick)` returns the active task. |

### Orchestration
| File | Role |
|---|---|
| `VillagerAgentManager` | **Main dispatcher.** `tickAgents()` (slow, every N ticks), `tickFarming()` (fast, every 3 ticks), `tickCombat()` (fast, every 2 ticks), `tickSocial()` (gated 200 ticks), `tickThoughts()` (gated 40 ticks per villager). |
| `VillagerEventHandler` | Subscribes to Forge `WorldTickEvent`; calls all `tickXxx()` methods and item pickup. |

### Perception
| File | Role |
|---|---|
| `VillagerVisionSystem` | Builds an NL environment summary (time, weather, biome, trees, water, caves, exact position, 4 in-sight chunks, all known chunks). Called every `ENV_REFRESH_INTERVAL = 1200` ticks and cached in `agent.environmentSummary`. |

### Behaviour Systems
| File | Role |
|---|---|
| `VillagerSchedulePlanner` | Dawn planning (ticks 0–2000): async LLM generates 4-slot `DailySchedule`. Evening reflection (ticks 13000–15000): 1 reflective memory sentence. Falls back to profession defaults if LLM fails. |
| `VillagerActivitySystem` | Translates `scheduledActivity` into movement: `exploring` (random 20–50 block target), `resting` (walk to bed/HOME memory), `crafting` (3-phase: walk→ACTING 200 ticks→finalize). |
| `VillagerSocialSystem` | Every 200 ticks scans idle villager pairs ≤5 blocks apart. LLM generates 4-line dialogue. Tone based on relationship score (5 tiers: close friends → hostile). Relationship gain: +2/+4/+1 by tier. |
| `VillagerNeedsSystem` | Hunger (0–100, decays 5pt/1200 ticks, auto-eat below 40). Fatigue (0–100, rises at night 13000–23000, recovers by day). Both exposed as NL description via `buildNeedsDescription()`. |
| `FarmingAction` | Mature-crop harvesting + seed planting. Includes sorted candidate lists, stuck-timeout, WAITING phase for seed pickup before replanting. |
| `CombatAction` | Scans for hostile mobs, equips best weapon, walks toward target, attacks on cooldown. Disengages if target flees or dies. |
| `CraftingAction` | Validates recipe ingredients, calls `recipe.craft(inventory)`. `RecipeRegistry` maps profession→available recipes. |

### Memory Management
- `addMemory(String)` in `VillagerAgentData`: when `memories.size() >= 40`, triggers async LLM to compress oldest 20 into a summary (guarded by `volatile boolean summarizingMemories`). Summary prepended as `[Summary of earlier memories] ...`.
- Memories capped at ~40 entries in practice.

### LLM Integration
- `LLMService.queryLLM(systemPrompt, userPrompt)` → `CompletableFuture<String>`. Thread pool of 4.
- Supported: `gemini`, `openai`, `anthropic`, `ollama`. Configured via `ModConfig.LLM_API_TYPE`.
- All call sites handle `.exceptionally(...)` with graceful fallback.

### Spontaneous Thoughts
- `VillagerAgentManager.tickThoughts()`: every 40 ticks, checks each villager for nearby players (≤20 blocks). Per-villager cooldown `THOUGHT_COOLDOWN_TICKS = 6000` (~5 MC minutes). Generates 1 first-person inner thought, broadcasts in italic grey `§7[Name thinks] §o...`, stores as memory.

---

## 4. Key Constants (quick reference)

| Constant | Value | Location |
|---|---|---|
| Day length | 24 000 ticks | everywhere |
| Night start | 13 000 | `VillagerNeedsSystem`, `VillagerSocialSystem` |
| Memory threshold | 40 entries → compress 20 | `VillagerAgentData` |
| Max known chunks | 512 | `VillagerAgentData` |
| Env refresh | 1 200 ticks | `VillagerAgentManager` |
| Thought cooldown | 6 000 ticks | `VillagerAgentManager` |
| Social cooldown | 6 000 ticks | `VillagerSocialSystem` |
| Craft work duration | 200 ticks | `VillagerActivitySystem` |
| Farming scan chance | 0.5%/tick | `VillagerAgentManager` |

---

## 5. Adding a New Feature — Checklist
1. New behaviour → add a `tickXxx(World)` static method in `VillagerAgentManager`.
2. Wire it in `VillagerEventHandler.onWorldTick()`.
3. Gate it with a feature flag in `ModConfig` if it should be optional.
4. Any new per-villager state → add field + getter/setter in `VillagerAgentData`, persist in `writeToNBT`/`readFromNBT`.
5. Run `gradlew compileJava` after every change.

---

## 6. Implemented Systems (as of 2026-04-23)
- ✅ LLM-generated name & personality at spawn
- ✅ Memory system with async summarisation (threshold 40)
- ✅ Daily schedule (LLM planned at dawn, evening reflection)
- ✅ Needs system (hunger + fatigue)
- ✅ Farming (harvest + plant, stuck timeout, seed-wait replant)
- ✅ Combat (scan, chase, attack, disengage)
- ✅ Crafting work phase (walk → 200-tick work → item production)
- ✅ Social conversations (relationship-tone driven, 5 tiers)
- ✅ Spontaneous thought bubbles (near players, 5-min cooldown)
- ✅ Spatial memory (LRU chunk set, 4 in-sight chunks, full map in LLM prompt)
- ✅ Environment sensing (exact time/24000, biome ID+desc, position, weather)
- ✅ Mood System (HAPPY/CONTENT/NEUTRAL/ANXIOUS/DISTRESSED — derived from needs+relations, injected into all 4 LLM call sites)
- ✅ Player Greeting (8-block range, 2400-tick cooldown per player, gold chat text, async LLM)
- ✅ Gossip Propagation (30% chance per social conversation, nudges listener's opinion ±2, memory logged for both)

### New Systems Detail (as of 2026-04-23)

**Mood System** (`VillagerAgentData.Mood` enum, `VillagerNeedsSystem.deriveMood/buildNeedsDescription`):
- 5 tiers: HAPPY, CONTENT, NEUTRAL, ANXIOUS, DISTRESSED
- Score based on hunger (<20→-2, <50→-1), fatigue (>80→-2, >50→-1), avg relationship (>40→+2, >10→+1, <-10→-1, <-40→-2)
- Re-derived every needs tick, stored as `agent.mood`
- Injected into: chat response system prompt, schedule planner user prompt, social conversation user prompt, thought bubble user prompt
- Per-villager last-greeted map: `Map<UUID, Long> lastGreetedPlayer` in `VillagerAgentData`

**Player Greeting** (`VillagerAgentManager.tickGreeting`):
- Scan every 20 ticks; per-villager + per-player cooldown: 2400 ticks (~2 MC min)
- Triggers when `player.distanceToSqr(villager) <= 64.0` (8 blocks)
- LLM prompt includes name, profession, personality, mood, activity, environment
- Broadcasts in gold `§eName: §fgreeting text` to all players within 20 blocks
- Stores "Greeted player X: ..." as a memory

**Gossip Propagation** (`VillagerSocialSystem.propagateGossip`):
- Called at end of `broadcastDialogue` with 30% probability
- Gossiper picks a third-party villager with |relation| > 10
- Listener's relation to that third party nudged ±2 (direction matches gossiper's opinion)
- Both parties receive a memory entry: gossiper records "Told X about Y", listener records "A spoke well/poorly of B (±2)"

## 8. Pending / Next Steps
- ⬜ **Weather Reactions** — seek shelter in rain, comment on storms in prompts
- ⬜ **Death Memory** — nearby villager death adds distress memory to witnesses (hook into `LivingDeathEvent`)
- ⬜ **Trade Negotiation** — use relationship score to adjust trade prices in `TradeRequestPacket`
- ⬜ **Home/Bed Assignment** — assign a home position at spawn; use it for `resting` activity navigation

