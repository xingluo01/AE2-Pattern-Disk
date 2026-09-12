package io.github.lounode.ae2pattern.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.StyleManager;
import appeng.menu.slot.AppEngSlot;

import io.github.lounode.ae2pattern.common.menu.BatchAssemblerMenu;

/**
 * Screen of the batch molecular assembler. The layout mirrors the pattern disk provider: pattern disk
 * slots on the top row, cell (buffer) slots on the bottom row. Left toolbar holds the return-to-buffer
 * button and the batch-delay mode toggle.
 */
public class BatchAssemblerScreen extends UpgradeableScreen<BatchAssemblerMenu> {

    private static final ResourceLocation STATES = ResourceLocation
            .parse("ae2_pattern_disk:textures/guis/states.png");

    // 空槽覆盖层：states.png (240,16,16,16) 样板磁盘槽（与样板磁盘供应器同款）、(240,48,16,16) 缓存栏槽
    private static final Blitter DISK_SLOT_OVERLAY = Blitter.texture(STATES).src(240, 16, 16, 16);
    private static final Blitter CELL_SLOT_OVERLAY = Blitter.texture(STATES).src(240, 48, 16, 16);

    // states.png (0,48,16,16) 退回缓存图标：存入按钮 (0,32) 的下方
    private static final Blitter RETURN_TO_BUFFER = Blitter.texture(STATES).src(0, 48, 16, 16);

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

        var returnButton = new StatesToggleButton(RETURN_TO_BUFFER, RETURN_TO_BUFFER,
                state -> menu.cancelCraft());
        returnButton.setBackground(BG_MODE_NORMAL, BG_MODE_HOVER);
        List<Component> cancelTooltip = List.of(
                Component.translatable("gui.ae2_pattern_disk.batch_assembler.cancel"),
                Component.translatable("gui.ae2_pattern_disk.batch_assembler.cancel_desc"));
        returnButton.setTooltipOn(cancelTooltip);
        returnButton.setTooltipOff(cancelTooltip);
        addToLeftToolbar(returnButton);

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
        // GuiSync 值回读：点击后服务端切换模式，图标/tooltip 随之刷新（与样板转存器同款做法）
        this.batchModeButton.setState(getMenu().isFastBatchMode());
    }

    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        // 空槽时按槽位身份绘制自定义覆盖层（样板磁盘 / 缓存栏），与底图槽井风格统一
        if (slot instanceof AppEngSlot appEngSlot && appEngSlot.getItem().isEmpty()) {
            var host = getMenu().getHost();
            var inv = appEngSlot.getInventory();
            if (inv == host.getDiskInventory()) {
                DISK_SLOT_OVERLAY.dest(slot.x, slot.y).zOffset(20).blit(guiGraphics);
            } else if (inv == host.getCellInventory()) {
                CELL_SLOT_OVERLAY.dest(slot.x, slot.y).zOffset(20).blit(guiGraphics);
            }
        }
        super.renderSlot(guiGraphics, slot);
    }
}
