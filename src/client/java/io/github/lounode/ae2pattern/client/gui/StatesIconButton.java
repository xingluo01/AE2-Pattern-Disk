package io.github.lounode.ae2pattern.client.gui;

import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ITooltip;
import appeng.client.gui.widgets.IconButton;

/**
 * 使用自定义 Blitter 纹理绘制的图标按钮（用于 states.png 项目内图标）。
 */
public class StatesIconButton extends IconButton implements ITooltip {

    private final BlitterProvider blitterProvider;
    private List<Component> tooltip = Collections.emptyList();

    /** 是否启用 hover 下压动画（鼠标悬停时图标下移 1px）。单形态小按钮可关闭保持静止。 */
    private boolean pressAnimation = true;

    // states.png 模式切换按钮背景：常态 / 光标选中（如未设置则不绘制背景）
    private Blitter backgroundNormal;
    private Blitter backgroundHover;

    @FunctionalInterface
    public interface BlitterProvider {
        Blitter get();
    }

    public StatesIconButton(BlitterProvider blitterProvider, OnPress onPress) {
        super(onPress);
        this.blitterProvider = blitterProvider;
    }

    /**
     * 是否启用 hover 下压动画（鼠标悬停时图标下移 1px）。
     * 默认 true；单形态小按钮需要常态/选中保持同一水平高度时可关闭。
     */
    public void setPressAnimation(boolean pressAnimation) {
        this.pressAnimation = pressAnimation;
    }

    /**
     * 设置按钮背景纹理（常态 / 光标选中）。
     *
     * @param normal 常态背景，可为 null 表示不绘制
     * @param hover  光标选中背景，可为 null 表示不绘制
     */
    public void setBackground(@Nullable Blitter normal, @Nullable Blitter hover) {
        this.backgroundNormal = normal;
        this.backgroundHover = hover;
    }

    public void setTooltip(List<Component> lines) {
        this.tooltip = lines;
    }

    @Override
    public void setHalfSize(boolean halfSize) {
        super.setHalfSize(halfSize);
        if (halfSize) {
            // 半尺寸按钮立即收缩点击区域，避免首帧前命中判定用 16x16
            this.width = 8;
            this.height = 8;
        }
    }

    @Override
    protected appeng.client.gui.Icon getIcon() {
        return null;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
        if (!this.visible) {
            return;
        }

        if (isHalfSize()) {
            this.width = 8;
            this.height = 8;
        }

        var yOffset = isHovered() && pressAnimation ? 1 : 0;
        // 背景：常态用左半，光标选中用右半（与 AE2 工具栏按钮背景一致，18x20 包在 16x16 图标外侧）
        var bg = isHovered() && backgroundHover != null ? backgroundHover : backgroundNormal;
        if (bg != null) {
            if (isHalfSize()) {
                bg.dest(getX(), getY() + yOffset, 8, 8).zOffset(2).blit(guiGraphics);
            } else {
                bg.dest(getX() - 1, getY() + yOffset, 18, 20).zOffset(2).blit(guiGraphics);
            }
        }

        var blitter = this.blitterProvider.get();
        if (!this.active) {
            blitter.opacity(0.5f);
        }

        if (isHalfSize()) {
            blitter.dest(getX(), getY() + yOffset).zOffset(3).blit(guiGraphics);
        } else {
            blitter.dest(getX(), getY() + 1 + yOffset).zOffset(3).blit(guiGraphics);
        }
    }

    @Override
    public List<Component> getTooltipMessage() {
        return tooltip;
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return super.isTooltipAreaVisible() && !tooltip.isEmpty();
    }
}
