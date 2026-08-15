package com.github.AaronAA0721.villageragent.ai.memory;

/**
 * Coarse block categories used by the villager vision/memory system.
 *
 * <p>Instead of remembering every individual block, the villager only records
 * the dominant category of each scanned column. This keeps chunk memory tiny
 * while still letting the LLM reason about "a forest", "a field", "a village".
 *
 * <p>Categories follow the user's examples: dirt / crops / trees / stone / ore / buildings.
 */
public enum BlockCategory {
    DIRT,       // dirt, grass blocks, podzol, coarse dirt ...
    GRASS,      // tall grass / grass decoration (kept separate from DIRT ground)
    SAND,       // sand, sandstone, red sand
    STONE,      // stone, cobblestone, andesite, etc.
    WATER,      // water (fluid)
    WOOD_LOG,   // logs and stripped logs  ("trees")
    LEAVES,     // leaves ("trees")
    CROP,       // wheat / carrot / potato / beetroot / farmland ("crops")
    BUILDING,   // planks, stone bricks, walls, glass, stairs ... ("artificial")
    ORE,        // coal / iron / gold / diamond / emerald / redstone / lapis ores
    PATH,       // grass path, roads
    OTHER       // anything not otherwise classified (lava is mapped to OTHER but flagged separately)
}
