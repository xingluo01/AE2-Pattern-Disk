package io.github.lounode.ae2pattern.client.integration.neoecoae;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.ITooltip;
import appeng.client.gui.widgets.IconButton;

/**
 * 仿 MEGA {@code CompressionCutoffButton} 的截断物按钮：画当前标记物本身，而不是一个固定图标。
 *
 * <p>不直接复用 MEGA 的类：本按钮服务于 ECO 大宗元件，MEGA 可能根本没装；这里只照它的外观与行为，
 * 图标、tooltip 与点击方向都自己维护，免得两边随着版本各自漂移。</p>
 */
public final class EcoBulkCutoffButton extends IconButton implements ITooltip {

    private ItemStack item = ItemStack.EMPTY;

    public EcoBulkCutoffButton(OnPress onPress) {
        super(onPress);
    }

    public void setItem(ItemStack item) {
        this.item = item == null ? ItemStack.EMPTY : item;
    }

    public void setVisible(boolean visible) {
        setVisibility(visible);
    }

    @Override
    protected Icon getIcon() {
        return null;
    }

    @Override
    public List<Component> getTooltipMessage() {
        var message = new ArrayList<Component>();
        message.add(Component.translatable(
                "gui.ae2_pattern_disk.cell_management_terminal.eco_bulk_cutoff"));
        if (!item.isEmpty()) {
            message.add(item.getHoverName());
        }
        return message;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
        if (!this.visible) {
            return;
        }
        var yOffset = isHovered() ? 1 : 0;
        var background = isHovered() ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                : isFocused() ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS
                : Icon.TOOLBAR_BUTTON_BACKGROUND;

        background.getBlitter()
                .dest(getX() - 1, getY() + yOffset, 18, 20)
                .zOffset(2)
                .blit(guiGraphics);
        guiGraphics.renderItem(item, getX(), getY() + 1 + yOffset, 0, 3);
    }
}
