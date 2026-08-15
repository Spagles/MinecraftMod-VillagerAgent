package com.github.AaronAA0721.villageragent.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.IEntityRenderer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.client.renderer.entity.model.VillagerModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom render layer that displays armor on villagers.
 *
 * <p>Vanilla {@code VillagerModel} extends {@code SegmentedModel} (not {@code BipedModel}),
 * so the standard {@code ArmorLayer} cannot be used. This layer creates a {@code BipedModel}
 * overlay, copies the villager's pose onto it, and renders armor textures for each slot.
 *
 * <p>The outer model (inflation 1.0) is used for helmet/chestplate/boots; the inner model
 * (inflation 0.5) is used for leggings — matching vanilla biped armor rendering.
 */
public class VillagerArmorLayer extends LayerRenderer<VillagerEntity, VillagerModel<VillagerEntity>> {

    private static final Map<String, ResourceLocation> TEXTURE_CACHE = new HashMap<>();

    // VillagerModel is wider than BipedModel — use larger inflation so
    // armor renders *over* the villager body instead of being hidden inside it.
    // Standard player values are 0.5 / 1.0; we increase both by ~0.6.
    private final BipedModel<VillagerEntity> innerModel = new BipedModel<>(1.1F);
    private final BipedModel<VillagerEntity> outerModel = new BipedModel<>(1.8F);

    public VillagerArmorLayer(IEntityRenderer<VillagerEntity, VillagerModel<VillagerEntity>> renderer) {
        super(renderer);
    }

    @Override
    public void render(@Nonnull MatrixStack ms, @Nonnull IRenderTypeBuffer buf, int light,
                       @Nonnull VillagerEntity entity, float limbSwing, float limbSwingAmt,
                       float partial, float age, float headYaw, float headPitch) {
        renderSlot(ms, buf, entity, EquipmentSlotType.HEAD,  light, outerModel, limbSwing, limbSwingAmt, partial, age, headYaw, headPitch);
        renderSlot(ms, buf, entity, EquipmentSlotType.CHEST, light, outerModel, limbSwing, limbSwingAmt, partial, age, headYaw, headPitch);
        renderSlot(ms, buf, entity, EquipmentSlotType.LEGS,  light, innerModel, limbSwing, limbSwingAmt, partial, age, headYaw, headPitch);
        renderSlot(ms, buf, entity, EquipmentSlotType.FEET,  light, innerModel, limbSwing, limbSwingAmt, partial, age, headYaw, headPitch);
    }

    private void renderSlot(MatrixStack ms, IRenderTypeBuffer buf, VillagerEntity entity,
                            EquipmentSlotType slot, int light, BipedModel<VillagerEntity> model,
                            float limbSwing, float limbSwingAmt, float partial, float age,
                            float headYaw, float headPitch) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem)) return;

        ArmorItem armor = (ArmorItem) stack.getItem();
        if (armor.getSlot() != slot) return;

        // --- Treat the villager as a standard biped (like zombie) ---
        // Do NOT call model.setupAnim() — it doesn't work with VillagerEntity.
        // Manually set all part rotations to match a zombie-style biped.
        model.young = entity.isBaby();

        // Head follows look direction
        float headYawRad  = headYaw  * ((float) Math.PI / 180F);
        float headPitchRad = headPitch * ((float) Math.PI / 180F);
        model.head.yRot = headYawRad;
        model.head.xRot = headPitchRad;
        model.hat.yRot  = headYawRad;
        model.hat.xRot  = headPitchRad;

        // Body stays upright
        model.body.yRot = 0F;
        model.body.xRot = 0F;

        // Arms crossed like a villager
        model.rightArm.xRot = -0.75F;
        model.rightArm.yRot =  0.0F;
        model.rightArm.zRot =  0.0F;
        model.leftArm.xRot  = -0.75F;
        model.leftArm.yRot  =  0.0F;
        model.leftArm.zRot  =  0.0F;

        // Legs swing with walking (same formula as BipedModel)
        float legSwing = (float) Math.cos(limbSwing * 0.6662F) * limbSwingAmt * 0.7F;
        model.rightLeg.xRot = -legSwing;
        model.rightLeg.yRot = 0F;
        model.rightLeg.zRot = 0F;
        model.leftLeg.xRot  =  legSwing;
        model.leftLeg.yRot  = 0F;
        model.leftLeg.zRot  = 0F;

        // Show only parts relevant to this armor slot
        setSlotVisibility(model, slot);

        // Resolve armor texture (supports Forge custom textures)
        ResourceLocation texture = getArmorResource(armor, slot, entity, stack);

        // Render the armor model — scale leggings wider in X/Z to cover villager body
        IVertexBuilder vb = ItemRenderer.getArmorFoilBuffer(buf,
                RenderType.armorCutoutNoCull(texture), false, stack.hasFoil());

        if (slot == EquipmentSlotType.LEGS) {
            ms.pushPose();
            ms.scale(1.05F, 0.95F, 1.05F); // wider in X/Z only
            model.renderToBuffer(ms, vb, light, OverlayTexture.NO_OVERLAY, 1F, 1F, 1F, 1F);
            ms.popPose();
        } else {
            model.renderToBuffer(ms, vb, light, OverlayTexture.NO_OVERLAY, 1F, 1F, 1F, 1F);
        }
    }

    private static void setSlotVisibility(BipedModel<?> m, EquipmentSlotType slot) {
        m.setAllVisible(false);
        switch (slot) {
            case HEAD:
                m.head.visible = true;
                m.hat.visible  = true;
                break;
            case CHEST:
                m.body.visible     = true;
                m.rightArm.visible = true;
                m.leftArm.visible  = true;
                break;
            case LEGS:
                m.body.visible     = true;
                m.rightLeg.visible = true;
                m.leftLeg.visible  = true;
                break;
            case FEET:
                m.rightLeg.visible = true;
                m.leftLeg.visible  = true;
                break;
            default:
                break;
        }
    }

    private static ResourceLocation getArmorResource(ArmorItem item, EquipmentSlotType slot,
                                                      VillagerEntity entity, ItemStack stack) {
        // Forge hook: mods can override armor textures per-item
        String custom = item.getArmorTexture(stack, entity, slot, null);
        if (custom != null) {
            return TEXTURE_CACHE.computeIfAbsent(custom, ResourceLocation::new);
        }
        // Vanilla path: textures/models/armor/<material>_layer_<1|2>.png
        String material = item.getMaterial().getName();
        String layer = (slot == EquipmentSlotType.LEGS) ? "2" : "1";
        String path = "textures/models/armor/" + material + "_layer_" + layer + ".png";
        return TEXTURE_CACHE.computeIfAbsent(path, ResourceLocation::new);
    }
}

