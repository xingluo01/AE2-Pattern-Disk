package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import guideme.PageAnchor;

import appeng.api.config.LockCraftingMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.upgrades.Upgrades;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.localization.GuiText;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.menu.SlotSemantics;

import net.neoforged.neoforge.network.PacketDistributor;

import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * Client screen for the pattern disk provider. Re-implements the three toolbar toggles of the original
 * pattern provider (blocking mode, lock crafting, show in sample access terminal) directly, mirroring
 * AE2 Crystal Science's {@code UpgradeablePatternProviderGUI} — the menu extends {@link AEBaseScreen}
 * with a deterministic slot layout, so this screen never introduces server/client drift. Layout driven
 * by {@code assets/ae2/screens/ae2_pattern_disk/pattern_disk_provider.json}.
 */
public class PatternDiskProviderScreen extends AEBaseScreen<PatternDiskProviderMenu> {

    private final SettingToggleButton<YesNo> blockingModeButton;
    private final SettingToggleButton<LockCraftingMode> lockCraftingModeButton;
    private final ToggleButton showInPatternAccessTerminalButton;

    /** 磁盘槽空槽覆盖层：states.png (240,16,16,16)。 */
    private static final Blitter DISK_SLOT_OVERLAY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(240, 16, 16, 16);

    public PatternDiskProviderScreen(PatternDiskProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/pattern_disk_provider.json"));

        this.blockingModeButton = new ServerSettingToggleButton<>(Settings.BLOCKING_MODE, YesNo.NO);
        this.addToLeftToolbar(this.blockingModeButton);

        this.lockCraftingModeButton = new ServerSettingToggleButton<>(Settings.LOCK_CRAFTING_MODE,
                LockCraftingMode.NONE);
        this.addToLeftToolbar(this.lockCraftingModeButton);

        this.showInPatternAccessTerminalButton = new ToggleButton(
                Icon.PATTERN_ACCESS_SHOW,
                Icon.PATTERN_ACCESS_HIDE,
                GuiText.PatternAccessTerminal.text(),
                GuiText.PatternAccessTerminalHint.text(),
                btn -> selectNextPatternProviderMode());
        this.addToLeftToolbar(this.showInPatternAccessTerminalButton);
    }

    @Override
    protected PageAnchor getHelpTopic() {
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/pattern_disk_provider.md"),
                null);
    }

    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        // 磁盘槽仅在有物品时占据槽位，空槽时由 Screen 绘制自定义覆盖层图标
        if (slot instanceof PatternDiskProviderMenu.DiskSlot diskSlot && diskSlot.getItem().isEmpty()) {
            DISK_SLOT_OVERLAY.dest(diskSlot.x, diskSlot.y).zOffset(20).blit(guiGraphics);
        }
        super.renderSlot(guiGraphics, slot);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.blockingModeButton.set(this.menu.getBlockingMode());
        this.lockCraftingModeButton.set(this.menu.getLockCraftingMode());
        this.showInPatternAccessTerminalButton.setState(this.menu.getShowInAccessTerminal() == YesNo.YES);
    }

    private void selectNextPatternProviderMode() {
        final boolean backwards = isHandlingRightClick();
        ServerboundPacket message = new ConfigButtonPacket(Settings.PATTERN_ACCESS_TERMINAL, backwards);
        PacketDistributor.sendToServer(message);
    }
}
