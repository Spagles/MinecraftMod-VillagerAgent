package com.github.AaronAA0721.villageragent.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.IEntityRenderer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.entity.model.VillagerModel;
import net.minecraft.client.renderer.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.vector.Vector3f;

import javax.annotation.Nonnull;

/**
 * Custom render layer that displays the villager's main-hand item.
 *
 * <p>Vanilla {@code VillagerModel} does not implement {@code IHasArm}, so the standard
 * {@code HeldItemLayer} cannot be used. This layer manually positions the item at the
 * villager's crossed-arm hand position.
 */
public class VillagerHeldItemLayer extends LayerRenderer<VillagerEntity, VillagerModel<VillagerEntity>> {

    public VillagerHeldItemLayer(IEntityRenderer<VillagerEntity, VillagerModel<VillagerEntity>> renderer) {
        super(renderer);
    }

    @Override
    public void render(@Nonnull MatrixStack ms, @Nonnull IRenderTypeBuffer buf, int light,
                       @Nonnull VillagerEntity entity, float limbSwing, float limbSwingAmt,
                       float partial, float age, float headYaw, float headPitch) {
        ItemStack stack = entity.getItemBySlot(EquipmentSlotType.MAINHAND);
        if (stack.isEmpty()) return;

        ms.pushPose();

        // ── Position the item at the villager's crossed-arms hand ──
        // VillagerModel arms origin: (0, 3, -1) in model space, xRot = -0.75
        // We translate in render space (blocks) relative to the entity's feet.
        //
        //   Y  = ~0.9 blocks up (roughly elbow/hand height of crossed arms)
        //   Z  = ~-0.19 blocks forward (arms are slightly in front of body)
        //   X  = 0 (centered — item appears at the arm crossing point)
        ms.translate(0.0, 0.9, -0.1875);

        // Tilt to match the arm angle (~43° = 0.75 rad)
        ms.mulPose(Vector3f.XP.rotationDegrees(-43.0F));

        // Standard third-person item scale with Y/Z axis flip for MC model convention
        ms.scale(0.625F, -0.625F, -0.625F);

        Minecraft.getInstance().getItemRenderer().renderStatic(
                stack,
                ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND,
                light,
                OverlayTexture.NO_OVERLAY,
                ms,
                buf
        );

        ms.popPose();
    }
}

