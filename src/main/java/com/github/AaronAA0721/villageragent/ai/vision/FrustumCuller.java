package com.github.AaronAA0721.villageragent.ai.vision;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;

/**
 * Lightweight frustum (field-of-view) culling for villager vision.
 *
 * <p>Rather than a full 6-plane clip, we use a cheap cone approximation: a target
 * is "visible" if the vector from the eye to the target lies within {@code fovDeg/2}
 * of the villager's forward look vector and within {@code range} blocks. This is
 * O(1) per candidate and is accurate enough for "what am I looking at?" semantics.
 *
 * <p>Forward vector is derived from the entity's {@code yRot}/{@code xRot} directly
 * (Minecraft convention: yRot 0 = South, xRot > 0 looks up), so it works without
 * depending on {@code getLookAngle()} across mapping versions.
 */
public final class FrustumCuller {

    public static final float DEFAULT_FOV = 70f;     // degrees (horizontal-ish)
    public static final float DEFAULT_RANGE = 20f;    // blocks
    public static final float MIN_DIST = 0.5f;        // ignore self

    private FrustumCuller() {}

    // ── Core helpers ─────────────────────────────────────────────────────

    /** Eye position of a living entity (block-center + eye height). */
    public static Vector3d eyePosition(LivingEntity e) {
        BlockPos p = e.blockPosition();
        return new Vector3d(p.getX() + 0.5, p.getY() + e.getEyeHeight(), p.getZ() + 0.5);
    }

    /** Unit forward look vector from yaw (yRot) + pitch (xRot) in degrees. */
    public static Vector3d forwardVector(LivingEntity e) {
        return forwardVector(e.yRot, e.xRot);
    }

    public static Vector3d forwardVector(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double x = -Math.sin(yaw) * Math.cos(pitch);
        double y =  Math.sin(pitch);
        double z =  Math.cos(yaw) * Math.cos(pitch);
        return new Vector3d(x, y, z).normalize();
    }

    /** Cone test: is {@code target} within the view cone defined by eye + forward? */
    public static boolean isInView(Vector3d eye, Vector3d target, Vector3d forward,
                                   float fovDeg, float range) {
        Vector3d to = target.subtract(eye);
        double dist = to.length();
        if (dist > range || dist < MIN_DIST) return false;
        double cosAngle = to.normalize().dot(forward);
        double cosHalf = Math.cos(Math.toRadians(fovDeg / 2.0));
        return cosAngle >= cosHalf;
    }

    // ── Convenience wrappers ─────────────────────────────────────────────

    public static boolean isBlockInView(LivingEntity e, BlockPos block) {
        return isBlockInView(e, block, DEFAULT_FOV, DEFAULT_RANGE);
    }

    public static boolean isBlockInView(LivingEntity e, BlockPos block, float fovDeg, float range) {
        Vector3d eye = eyePosition(e);
        Vector3d target = new Vector3d(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
        return isInView(eye, target, forwardVector(e), fovDeg, range);
    }

    public static boolean isEntityInView(LivingEntity e, Entity ent) {
        return isEntityInView(e, ent, DEFAULT_FOV, DEFAULT_RANGE);
    }

    public static boolean isEntityInView(LivingEntity e, Entity ent, float fovDeg, float range) {
        Vector3d eye = eyePosition(e);
        Vector3d target = new Vector3d(ent.getX(), ent.getY() + ent.getEyeHeight(), ent.getZ());
        return isInView(eye, target, forwardVector(e), fovDeg, range);
    }
}
