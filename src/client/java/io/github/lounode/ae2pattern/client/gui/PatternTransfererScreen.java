package io.github.lounode.ae2pattern.client.gui;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import guideme.PageAnchor;

import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;

import io.github.lounode.ae2pattern.common.menu.PatternTransfererMenu;
import io.github.lounode.ae2pattern.common.pattern.TransferMode;

/**
 * Client screen for the pattern transferer. Layout is driven by the ScreenStyle JSON
 * ({@code assets/ae2/screens/ae2_pattern_disk/pattern_transferer.json}), loaded through AE2's {@link StyleManager}.
 */
public class PatternTransfererScreen extends UpgradeableScreen<PatternTransfererMenu> {

    // states.png (0,32,32,16)：左侧样板存储，右侧样板复写
    private static final Blitter MODE_STORE = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(0, 32, 16, 16);
    private static final Blitter MODE_COPY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(16, 32, 16, 16);

    // states.png (208,224,36,20) 模式切换按钮背景：左半常态，右半光标选中（与样板磁盘编码终端同款）
    private static final Blitter BG_MODE_NORMAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(208, 224, 18, 20);
    private static final Blitter BG_MODE_HOVER = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(226, 224, 18, 20);

    private final StatesToggleButton modeButton;

    public PatternTransfererScreen(PatternTransfererMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/pattern_transferer.json"));

        this.modeButton = new StatesToggleButton(MODE_STORE, MODE_COPY, v -> {
            menu.setTransferMode(v ? TransferMode.STORE : TransferMode.COPY);
        });
        this.modeButton.setBackground(BG_MODE_NORMAL, BG_MODE_HOVER);
        this.modeButton.setState(menu.getTransferMode() == TransferMode.STORE);
        this.modeButton.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.transferer.mode_store"),
                Component.translatable("gui.ae2_pattern_disk.transferer.mode_store_desc")));
        this.modeButton.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.transferer.mode_copy"),
                Component.translatable("gui.ae2_pattern_disk.transferer.mode_copy_desc")));
        addToLeftToolbar(this.modeButton);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.modeButton.setState(menu.getTransferMode() == TransferMode.STORE);
    }

    @Override
    protected PageAnchor getHelpTopic() {
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/pattern_transferer.md"),
                null);
    }
}
