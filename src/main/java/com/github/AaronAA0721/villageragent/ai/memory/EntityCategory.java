package com.github.AaronAA0721.villageragent.ai.memory;

/**
 * Coarse entity categories for the villager's entity memory.
 *
 * <p>Used to filter memories quickly, e.g. "find all hostile mobs near me"
 * or "find the nearest other villager".
 */
public enum EntityCategory {
    HOSTILE,    // monsters (creeper, zombie, skeleton ...)
    ANIMAL,     // passive / neutral creatures (cow, sheep, wolf, fish ...)
    VILLAGER,   // other villagers
    PLAYER,     // the player(s)
    ITEM,       // dropped item entities on the ground
    OTHER       // anything else (minecarts, boats, projectiles ...)
}
