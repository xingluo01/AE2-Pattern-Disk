package io.github.lounode.ae2pattern.client.gui;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ITooltip;
import appeng.client.gui.widgets.IconButton;

/**
 * 支持自定义 Blitter 纹理的切换按钮（用于 states.png 项目内图标）。
 * <p>
 * 与 AE2 的 {@link appeng.client.gui.widgets.ToggleButton} 不同，本类不依赖
 * {@code Icon} 枚举，而是直接使用 Blitter 绘制 on/off 两种状态的自定义纹理。
 */
public class StatesToggleButton extends IconButton implements ITooltip {

    private final Blitter blitterOn;
    private final Blitter blitterOff;
    private final Listener listener;

    // states.png 模式切换按钮背景：常态 / 光标选中（如未设置则不绘制背景）
    private Blitter backgroundNormal;
    private Blitter backgroundHover;

    private boolean state;
    private boolean pressAnimation = true;
    private List<Component> tooltipOn = Collections.emptyList();
    private List<Component> tooltipOff = Collections.emptyList();

    @FunctionalInterface
    public interface Listener {
        void onChange(boolean state);
    }

    public StatesToggleButton(Blitter on, Blitter off, Listener listener) {
        super(null);
        this.blitterOn = on;
        this.blitterOff = off;
        this.listener = listener;
    }

    /**
     * 设置按钮背景纹理（常态 / 光标选中）。
     *
     * @param normal 常态背景，可为 null 表示不绘制
     * @param hover  光标选中背景，可为 null 表示不绘制
     */
    public void setBackground(@org.jetbrains.annotations.Nullable Blitter normal,
            @org.jetbrains.annotations.Nullable Blitter hover) {
        this.backgroundNormal = normal;
        this.backgroundHover = hover;
    }

    public void setTooltipOn(List<Component> lines) {
        this.tooltipOn = lines;
    }

    public void setTooltipOff(List<Component> lines) {
        this.tooltipOff = lines;
    }

    /**
     * 是否启用 hover 下压动画（鼠标悬停时图标下移 1px）。
     * 默认 true；同物品合并按钮等需要常态/选中保持同一水平高度的场景可关闭。
     */
    public void setPressAnimation(boolean pressAnimation) {
        this.pressAnimation = pressAnimation;
    }

    @Override
    public void onPress() {
        this.listener.onChange(!state);
    }

    public void setState(boolean isOn) {
        this.state = isOn;
    }

    public boolean getState() {
        return this.state;
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
        // 与 IconButton 一致：半尺寸时点击区收缩为 8x8
        if (isHalfSize()) {
            this.width = 8;
            this.height = 8;
        }

        var yOffset = pressAnimation && isHovered() ? 1 : 0;

        // 背景：常态用左半，光标选中用右半（与 AE2 工具栏按钮背景一致，18x20 包在图标外侧）
        var bg = isHovered() && backgroundHover != null ? backgroundHover : backgroundNormal;
        if (bg != null) {
            if (isHalfSize()) {
                bg.dest(getX(), getY() + yOffset, 8, 8).zOffset(2).blit(guiGraphics);
            } else {
                bg.dest(getX() - 1, getY() + yOffset, 18, 20).zOffset(2).blit(guiGraphics);
            }
        }

        var blitter = this.state ? this.blitterOn : this.blitterOff;
        if (!this.active) {
            blitter.opacity(0.5f);
        }

        if (isHalfSize()) {
            blitter.dest(getX(), getY() + yOffset).zOffset(20).blit(guiGraphics);
        } else {
            blitter.dest(getX(), getY() + 1 + yOffset).zOffset(20).blit(guiGraphics);
        }
    }

    @Override
    public List<Component> getTooltipMessage() {
        return state ? tooltipOn : tooltipOff;
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return super.isTooltipAreaVisible() && !getTooltipMessage().isEmpty();
    }
}
