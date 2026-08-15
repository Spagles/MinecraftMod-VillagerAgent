package com.github.AaronAA0721.villageragent.mixin;

import com.github.AaronAA0721.villageragent.client.VillagerArmorLayer;
import com.github.AaronAA0721.villageragent.client.VillagerHeldItemLayer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.model.VillagerModel;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.resources.IReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link VillagerRenderer} to add custom render layers for
 * armor and held items. Vanilla {@code VillagerModel} doesn't support
 * these, so we add our own layers at the end of the constructor.
 *
 * <p>This mixin is client-only (registered in the "client" section of
 * the mixin config).
 */
@Mixin(VillagerRenderer.class)
public abstract class VillagerRendererMixin
        extends MobRenderer<VillagerEntity, VillagerModel<VillagerEntity>> {

    // Required by compiler — never actually called (mixin bytecode is merged)
    protected VillagerRendererMixin(EntityRendererManager mgr,
                                    VillagerModel<VillagerEntity> model,
                                    float shadow) {
        super(mgr, model, shadow);
    }

    /**
     * Inject at the end of VillagerRenderer's constructor to register our layers.
     * The actual constructor signature is (EntityRendererManager, IReloadableResourceManager).
     * {@code addLayer} is protected on {@code LivingRenderer}, but accessible
     * here because the mixin extends it.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void villageragent$addEquipmentLayers(EntityRendererManager manager,
                                                   IReloadableResourceManager resourceManager,
                                                   CallbackInfo ci) {
        this.addLayer(new VillagerArmorLayer(this));
        this.addLayer(new VillagerHeldItemLayer(this));
    }
}

