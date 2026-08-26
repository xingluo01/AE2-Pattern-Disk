package io.github.lounode.ae2pattern.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;

/**
 * Renders the item currently being assembled inside the machine plus a crafting particle effect,
 * mirroring the original AE2 molecular assembler's in-world item depiction. The first active
 * execution unit's primary output is drawn floating, and while any unit runs a short-lived spark
 * particle is emitted from the machine's centre to signal the ongoing craft (the "border sparkle").
 */
public class PatternDiskAssemblerRenderer implements BlockEntityRenderer<PatternDiskAssemblerBlockEntity> {

    public PatternDiskAssemblerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(PatternDiskAssemblerBlockEntity be, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }

        ItemStack item = be.getRenderedAssemblyItem();
        if (item.isEmpty()) {
            // Not assembling but still on grid: a faint blue dust signals the powered-but-idle state
            // (1.3b border readout), keeping the status visible from outside the GUI.
            if (be.isPowered()) {
                float cx = be.getBlockPos().getX() + 0.5f;
                float cy = be.getBlockPos().getY() + 0.5f;
                float cz = be.getBlockPos().getZ() + 0.5f;
                mc.particleEngine.createParticle(ParticleTypes.END_ROD, cx, cy, cz, 0, 0.02, 0);
            }
            return;
        }

        // 1.3b: emit a crafting spark from the machine centre so the running state is readable even from
        // outside the GUI, like the original assembler's border/particle feedback.
        float nx = be.getBlockPos().getX() + 0.5f;
        float ny = be.getBlockPos().getY() + 0.5f;
        float nz = be.getBlockPos().getZ() + 0.5f;
        mc.particleEngine.createParticle(ParticleTypes.END_ROD, nx, ny, nz, 0, 0.05, 0);

        // 1.3a: draw the first active unit's output floating inside the machine.
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.translate(0, -0.05, 0);
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
