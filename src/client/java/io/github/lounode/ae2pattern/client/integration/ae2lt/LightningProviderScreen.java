package io.github.lounode.ae2pattern.client.integration.ae2lt;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

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
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.ToolboxPanel;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.core.localization.GuiText;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.menu.SlotSemantics;

import net.neoforged.neoforge.network.PacketDistributor;

import io.github.lounode.ae2pattern.client.gui.LockReasonWidget;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * 闪电科技三台供应器共用的屏幕骨架：磁盘槽、返回库存、玩家背包与前身
 * {@code PatternDiskProviderScreen} 相同，只是工具栏交给子类。
 *
 * <p>没有复制那三枚本模组特色按钮（阻挡/倍增/抽取）：本设备只保留闪电科技自己的按钮组。</p>
 */
public abstract class LightningProviderScreen<M extends PatternDiskProviderMenu> extends AEBaseScreen<M> {

    private static final Blitter DISK_SLOT_OVERLAY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(240, 16, 16, 16);

    private final SettingToggleButton<LockCraftingMode> lockCraftingModeButton;
    private final ToggleButton showInPatternAccessTerminalButton;
    private final LockReasonWidget lockReason;

    protected LightningProviderScreen(M menu, Inventory playerInventory, Component title) {
        this(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/pattern_disk_provider.json"));
    }

    protected LightningProviderScreen(M menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        if (menu.getToolbox().isPresent()) {
            this.widgets.add("toolbox", new ToolboxPanel(style, menu.getToolbox().getName()));
        }

        this.lockCraftingModeButton = new ServerSettingToggleButton<>(Settings.LOCK_CRAFTING_MODE,
                LockCraftingMode.NONE);
        this.addToLeftToolbar(this.lockCraftingModeButton);

        this.showInPatternAccessTerminalButton = new ToggleButton(
                appeng.client.gui.Icon.PATTERN_ACCESS_SHOW,
                appeng.client.gui.Icon.PATTERN_ACCESS_HIDE,
                GuiText.PatternAccessTerminal.text(),
                GuiText.PatternAccessTerminalHint.text(),
                btn -> selectNextPatternProviderMode());
        this.addToLeftToolbar(this.showInPatternAccessTerminalButton);

        this.widgets.addOpenPriorityButton();

        this.lockReason = new LockReasonWidget(menu);
        this.widgets.add("lockReason", this.lockReason);

        var upgradeSlots = menu.getSlots(SlotSemantics.UPGRADE);
        if (!upgradeSlots.isEmpty()) {
            this.widgets.add("upgrades", new UpgradesPanel(upgradeSlots, this::getCompatibleUpgrades));
        }
    }

    /** 子类在这里挂闪电科技的按钮。 */
    protected abstract void addLightningToolbar();

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
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/pattern_disk_provider.md"),
                null);
    }

    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        if (slot instanceof PatternDiskProviderMenu.DiskSlot diskSlot && diskSlot.getItem().isEmpty()) {
            DISK_SLOT_OVERLAY.dest(diskSlot.x, diskSlot.y).zOffset(20).blit(guiGraphics);
        }
        super.renderSlot(guiGraphics, slot);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.lockCraftingModeButton.set(this.menu.getLockCraftingMode());
        this.showInPatternAccessTerminalButton.setState(this.menu.getShowInAccessTerminal() == YesNo.YES);
        this.lockReason.setVisible(this.menu.getLockCraftingMode() != LockCraftingMode.NONE);
    }

    private void selectNextPatternProviderMode() {
        final boolean backwards = isHandlingRightClick();
        ServerboundPacket message = new ConfigButtonPacket(Settings.PATTERN_ACCESS_TERMINAL, backwards);
        PacketDistributor.sendToServer(message);
    }

    /** 子类按钮要发包时共用。 */
    protected void sendMenuAction(String name, @Nullable String json) {
        PacketDistributor.sendToServer(new appeng.core.network.serverbound.GuiActionPacket(
                this.menu.containerId, name, json));
    }
}