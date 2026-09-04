package io.github.lounode.ae2pattern.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.data.ModelData;

import appeng.client.render.effects.ParticleTypes; // AE2 crafting particle
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.network.AssemblerAnimationStatus;

/**
 * Renders the pattern disk assembler's in-world state:
 * <ul>
 * <li>a rainbow animated light strip on the inner faces while the machine is powered (mirrors AE2's
 * {@code molecular_assembler_lights} overlay),</li>
 * <li>a crafting particle effect plus the produced item floating inside the machine right after a
 * unit finishes a craft (driven by the {@code assembler_animation} packet),</li>
 * <li>a faint idle dust while powered but idle (kept from 1.3b).</li>
 * </ul>
 */
public class PatternDiskAssemblerRenderer implements BlockEntityRenderer<PatternDiskAssemblerBlockEntity> {

    public static final ModelResourceLocation LIGHTS_MODEL = ModelResourceLocation
            .standalone(ResourceLocation.fromNamespaceAndPath("ae2_pattern_disk", "block/pattern_disk_assembler_lights"));

    private final RandomSource particleRandom = RandomSource.create();

    public PatternDiskAssemblerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(PatternDiskAssemblerBlockEntity be, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }

        // Animation driven by the assembler_animation packet: play particles + show the produced item.
        AssemblerAnimationStatus status = be.getAnimationStatus();
        if (status != null) {
            if (status.isExpired()) {
                be.setAnimationStatus(null);
            } else {
                status.setAccumulatedTicks(status.getAccumulatedTicks() + partialTicks);
                status.setTicksUntilParticles(status.getTicksUntilParticles() - partialTicks);
                renderStatus(be, poseStack, buffer, packedLight, status);
            }
        }

        // Rainbow light strip while powered.
        if (be.isPowered()) {
            renderPowerLight(poseStack, buffer, packedLight, packedOverlay);
        }

        // Fallback: no animation packet, but a unit is still assembling (e.g. not yet on the grid).
        // Draw the first active unit's output floating inside the machine.
        ItemStack item = be.getRenderedAssemblyItem();
        if (status == null && !item.isEmpty()) {
            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);
            if (!(item.getItem() instanceof BlockItem)) {
                poseStack.translate(0, -0.2, 0);
            } else {
                poseStack.translate(0, -0.3, 0);
            }
            mc.getItemRenderer().renderStatic(
                    item,
                    ItemDisplayContext.GROUND,
                    packedLight,
                    OverlayTexture.NO_OVERLAY,
                    poseStack,
                    buffer,
                    mc.level,
                    0);
            poseStack.popPose();
        }
    }

    private void renderPowerLight(PoseStack ms, MultiBufferSource bufferIn, int combinedLightIn,
            int combinedOverlayIn) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel lightsModel = minecraft.getModelManager().getModel(LIGHTS_MODEL);
        // tripwire layer has the shader-property we're looking for:
        // alpha testing + translucency
        VertexConsumer buffer = bufferIn.getBuffer(RenderType.tripwire());

        minecraft.getBlockRenderer().getModelRenderer().renderModel(ms.last(), buffer, null,
                lightsModel, 1, 1, 1, combinedLightIn, combinedOverlayIn, ModelData.EMPTY, null);
    }

    private void renderStatus(PatternDiskAssemblerBlockEntity be, PoseStack ms, MultiBufferSource bufferIn,
            int combinedLightIn, AssemblerAnimationStatus status) {
        double centerX = be.getBlockPos().getX() + 0.5f;
        double centerY = be.getBlockPos().getY() + 0.5f;
        double centerZ = be.getBlockPos().getZ() + 0.5f;

        Minecraft minecraft = Minecraft.getInstance();
        if (status.getTicksUntilParticles() <= 0) {
            status.setTicksUntilParticles(4);

            if (shouldAddParticles(particleRandom)) {
                for (int x = 0; x < (int) Math.ceil(status.getSpeed() / 5.0); x++) {
                    minecraft.particleEngine.createParticle(ParticleTypes.CRAFTING, centerX, centerY, centerZ, 0, 0, 0);
                }
            }
        }

        ItemStack is = status.getIs();
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5); // Translate to center of block

        if (!(is.getItem() instanceof BlockItem)) {
            ms.translate(0, -0.3f, 0);
        } else {
            ms.translate(0, -0.2f, 0);
        }

        itemRenderer.renderStatic(is, ItemDisplayContext.GROUND, combinedLightIn,
                OverlayTexture.NO_OVERLAY, ms, bufferIn, be.getLevel(), 0);
        ms.popPose();
    }

    private boolean shouldAddParticles(RandomSource r) {
        return switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> true;
            case DECREASED -> r.nextBoolean();
            case MINIMAL -> false;
        };
    }
}
