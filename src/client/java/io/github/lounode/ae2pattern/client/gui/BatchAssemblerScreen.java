package io.github.lounode.ae2pattern.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.Icon;
import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.IconButton;

import io.github.lounode.ae2pattern.common.menu.BatchAssemblerMenu;

/**
 * Screen of the batch molecular assembler: cell slots, disk slots plus the two left-toolbar buttons
 * (cancel crafting and batch-delay mode), with no per-thread game pages.
 */
public class BatchAssemblerScreen extends UpgradeableScreen<BatchAssemblerMenu> {

    private static final ResourceLocation STATES = ResourceLocation
            .parse("ae2_pattern_disk:textures/guis/states.png");

    // states.png (32,32,16,16) 标准 / (48,32,16,16) 极速，紧邻存入、复写图标右侧
    private static final Blitter MODE_STANDARD = Blitter.texture(STATES).src(32, 32, 16, 16);
    private static final Blitter MODE_FAST = Blitter.texture(STATES).src(48, 32, 16, 16);

    // 与样板转存器同款切换按钮背景：常态 / 光标选中
    private static final Blitter BG_MODE_NORMAL = Blitter.texture(STATES).src(208, 224, 18, 20);
    private static final Blitter BG_MODE_HOVER = Blitter.texture(STATES).src(226, 224, 18, 20);

    private final StatesToggleButton batchModeButton;

    public BatchAssemblerScreen(BatchAssemblerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/batch_molecular_assembler.json"));

        var cancelButton = new IconButton(button -> menu.cancelCraft()) {
            @Override
            protected Icon getIcon() {
                return Icon.CLEAR;
            }
        };
        cancelButton.setMessage(Component.translatable("gui.ae2_pattern_disk.batch_assembler.cancel"));
        addToLeftToolbar(cancelButton);

        // 状态为 true 时显示极速图标，与 fastBatchMode 语义一一对应
        this.batchModeButton = new StatesToggleButton(MODE_FAST, MODE_STANDARD, menu::setFastBatchMode);
        this.batchModeButton.setBackground(BG_MODE_NORMAL, BG_MODE_HOVER);
        this.batchModeButton.setState(menu.isFastBatchMode());
        this.batchModeButton.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.batch_assembler.batch_mode.fast")));
        this.batchModeButton.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.batch_assembler.batch_mode.standard")));
        addToLeftToolbar(this.batchModeButton);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.batchModeButton.setState(getMenu().isFastBatchMode());
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        guiGraphics.drawString(
                getMinecraft().font,
                Component.translatable("gui.ae2_pattern_disk.batch_assembler.buffer"),
                8,
                18,
                style.getColor(appeng.client.gui.style.PaletteColor.DEFAULT_TEXT_COLOR).toARGB(),
                false);
    }
}
