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
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.Upgrades;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.UpgradesPanel;
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
 * AE2 Crystal Science's {@code UpgradeablePatternProviderGUI} — the screen extends {@link AEBaseScreen}
 * over a menu with a deterministic slot layout, so it never introduces server/client drift. Layout driven
 * by {@code assets/ae2/screens/ae2_pattern_disk/pattern_disk_provider.json}.
 */
public class PatternDiskProviderScreen extends AEBaseScreen<PatternDiskProviderMenu> {

    private final SettingToggleButton<YesNo> blockingModeButton;
    private final SettingToggleButton<LockCraftingMode> lockCraftingModeButton;
    private final ToggleButton showInPatternAccessTerminalButton;
    private final LockReasonWidget lockReason;

    /** 是否自装配版：两个构造器共用本类，指南页要按它分流。 */
    private final boolean selfAssembling;

    /** 磁盘槽空槽覆盖层：states.png (240,16,16,16)。 */
    private static final Blitter DISK_SLOT_OVERLAY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(240, 16, 16, 16);

    public PatternDiskProviderScreen(PatternDiskProviderMenu menu, Inventory playerInventory, Component title) {
        this(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/pattern_disk_provider.json"), false);
    }

    /**
     * 同一套布局、换一份文档：派生设备（自装配样板磁盘供应器）的字样挂在布局文档里，所以它用这里传进来的
     * 那份文档开屏。构造之后的一切都与本类共用。
     *
     * @param selfAssembling 是否是自装配版：只影响指南页指向（它照着 AE2 Crystal Science 那台做，
     *                       问号应该跳去对方的页面）。
     */
    public PatternDiskProviderScreen(PatternDiskProviderMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style, boolean selfAssembling) {
        super(menu, playerInventory, title, style);
        this.selfAssembling = selfAssembling;

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

        // Opens AE2's priority submenu for this host. The host satisfies IPriorityHost through
        // PatternProviderLogicHost's default methods, so the forwarded value is the provider's own.
        this.widgets.addOpenPriorityButton();

        this.lockReason = new LockReasonWidget(menu);
        this.widgets.add("lockReason", this.lockReason);

        // 升级面板：槽位由它按 AE2 的规矩摆在对话框右侧外沿并画底框（界面上那几个 UPGRADE 槽的定位归它管，
        // 界面文档里那份坐标只是冗余）。没有升级槽的机器不挂——本屏平时就属于那种（供应器自身不带卡槽），
        // 只有装了 Applied Flux 时才会多出它给的那一格感应卡槽，那时面板才挂上去。
        var upgradeSlots = menu.getSlots(SlotSemantics.UPGRADE);
        if (!upgradeSlots.isEmpty()) {
            this.widgets.add("upgrades", new UpgradesPanel(upgradeSlots, this::getCompatibleUpgrades));
        }
    }

    /**
     * 「可用升级」提示：这台设备能装哪些卡（AE2 速度卡、AE2 Crystal Science 的陨石超频卡），鼠标悬在升级
     * 面板上时显示。
     */
    private List<Component> getCompatibleUpgrades() {
        var lines = new ArrayList<Component>();
        if (menu.getProvider() instanceof IUpgradeableObject upgradeable) {
            lines.add(GuiText.CompatibleUpgrades.text());
            lines.addAll(Upgrades.getTooltipLinesForMachine(upgradeable.getUpgrades().getUpgradableItem()));
        }
        return lines;
    }

    @Override
    protected PageAnchor getHelpTopic() {
        // 自装配版（装了 AE2 Crystal Science 才有）指向它那台供应器的指南页：本设备就是照它做的，
        // 讲清来龙去脉比重复本模组的页面有用。普通版仍指向自己的页面。
        if (this.selfAssembling) {
            return new PageAnchor(ResourceLocation.parse("ae2cs:meteorite_pattern_provider.md"), null);
        }
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
        this.lockReason.setVisible(this.menu.getLockCraftingMode() != LockCraftingMode.NONE);
    }

    private void selectNextPatternProviderMode() {
        final boolean backwards = isHandlingRightClick();
        ServerboundPacket message = new ConfigButtonPacket(Settings.PATTERN_ACCESS_TERMINAL, backwards);
        PacketDistributor.sendToServer(message);
    }
}
