package io.github.lounode.ae2pattern.client.integration.ae2cs;

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
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.ToolboxPanel;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.core.localization.GuiText;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.core.network.serverbound.GuiActionPacket;
import appeng.menu.SlotSemantics;

import net.neoforged.neoforge.network.PacketDistributor;

import io.github.lounode.ae2pattern.client.gui.LockReasonWidget;
import io.github.lounode.ae2pattern.client.gui.StatesIconButton;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;
import io.github.lounode.ae2pattern.integration.ae2cs.resonating.ResonatingPatternDiskProviderMenu;

/**
 * 谐振样板磁盘供应器的屏幕：布局复用样板磁盘供应器那份；阻挡仍用 AE2 的开关，产物抽取换成样板磁盘供应器
 * 自己的三档图标按钮。
 *
 * <p>
 * 不继承本模组现有的 {@code PatternDiskProviderScreen}：那屏挂的阻挡/倍增/抽取按钮会发本模组自己的动作，
 * 服务端动作强转 {@code PatternDiskProviderLogic}，而本设备的逻辑是 AECS 的谐振逻辑。这里改为直接挂 AE2 的
 * 设置开关（阻挡、锁定、样板访问终端）、优先级、升级面板，抽取走谐振逻辑接管的 {@code cycleResonatingExtraction}。
 * </p>
 */
public class ResonatingPatternDiskProviderScreen extends AEBaseScreen<ResonatingPatternDiskProviderMenu> {

    /** 磁盘槽空槽覆盖层：states.png (240,16,16,16)。 */
    private static final Blitter DISK_SLOT_OVERLAY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(240, 16, 16, 16);

    /** 产物抽取按钮：三档依次为不抽取 (0,96)、抽取 (16,96)、快速抽取 (32,96)。 */
    private static final Blitter EXTRACTION_OFF_ICON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(0, 96, 16, 16);
    private static final Blitter EXTRACTION_NORMAL_ICON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(16, 96, 16, 16);
    private static final Blitter EXTRACTION_FAST_ICON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(32, 96, 16, 16);

    /** 工具栏按钮背景（与样板磁盘供应器同一套 18×20 底图）：常态 / 光标选中。 */
    private static final Blitter BUTTON_BG_NORMAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(208, 224, 18, 20);
    private static final Blitter BUTTON_BG_HOVER = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(226, 224, 18, 20);

    private final ServerSettingToggleButton<YesNo> blockingModeButton;
    private final ServerSettingToggleButton<LockCraftingMode> lockCraftingModeButton;
    private final ToggleButton showInPatternAccessTerminalButton;
    private final LockReasonWidget lockReason;
    private final StatesIconButton extractionButton;

    public ResonatingPatternDiskProviderScreen(ResonatingPatternDiskProviderMenu menu, Inventory playerInventory,
            Component title) {
        this(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/resonating_pattern_disk_provider.json"));
    }

    public ResonatingPatternDiskProviderScreen(ResonatingPatternDiskProviderMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        if (menu.getToolbox().isPresent()) {
            this.widgets.add("toolbox", new ToolboxPanel(style, menu.getToolbox().getName()));
        }

        this.blockingModeButton = new ServerSettingToggleButton<>(Settings.BLOCKING_MODE, YesNo.NO);
        this.addToLeftToolbar(this.blockingModeButton);

        this.lockCraftingModeButton = new ServerSettingToggleButton<>(Settings.LOCK_CRAFTING_MODE,
                LockCraftingMode.NONE);
        this.addToLeftToolbar(this.lockCraftingModeButton);

        this.widgets.addOpenPriorityButton();

        this.showInPatternAccessTerminalButton = new ToggleButton(Icon.PATTERN_ACCESS_SHOW,
                Icon.PATTERN_ACCESS_HIDE,
                GuiText.PatternAccessTerminal.text(), GuiText.PatternAccessTerminalHint.text(),
                btn -> selectNextPatternProviderMode());
        this.addToLeftToolbar(this.showInPatternAccessTerminalButton);

        this.extractionButton = new StatesIconButton(() -> switch (this.menu.getExtractionMode()) {
            case OFF -> EXTRACTION_OFF_ICON;
            case NORMAL -> EXTRACTION_NORMAL_ICON;
            case FAST -> EXTRACTION_FAST_ICON;
        }, btn -> sendAction("cycleResonatingExtraction", null));
        this.extractionButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.extractionButton.setTooltip(extractionTooltip());
        this.addToLeftToolbar(this.extractionButton);

        this.lockReason = new LockReasonWidget(menu);
        this.widgets.add("lockReason", this.lockReason);

        this.widgets.add("upgrades", new UpgradesPanel(
                menu.getSlots(SlotSemantics.UPGRADE),
                this::getCompatibleUpgrades));
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.blockingModeButton.set(this.menu.getAe2BlockingMode());
        this.lockCraftingModeButton.set(this.menu.getLockCraftingMode());
        this.showInPatternAccessTerminalButton.setState(this.menu.getShowInAccessTerminal() == YesNo.YES);
        this.lockReason.setVisible(this.menu.getLockCraftingMode() != LockCraftingMode.NONE);
        this.extractionButton.setTooltip(extractionTooltip());
    }

    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        if (slot instanceof PatternDiskProviderMenu.DiskSlot diskSlot && diskSlot.getItem().isEmpty()) {
            DISK_SLOT_OVERLAY.dest(diskSlot.x, diskSlot.y).zOffset(20).blit(guiGraphics);
        }
        super.renderSlot(guiGraphics, slot);
    }

    @Override
    protected PageAnchor getHelpTopic() {
        // 设备照 AECS 的谐振供应器做，讲清来源比重复本模组页面有用。
        return new PageAnchor(ResourceLocation.parse("ae2cs:resonating_pattern_provider.md"), null);
    }

    private List<Component> getCompatibleUpgrades() {
        var lines = new ArrayList<Component>();
        if (this.menu.getProvider() instanceof IUpgradeableObject upgradeable) {
            lines.add(GuiText.CompatibleUpgrades.text());
            lines.addAll(Upgrades.getTooltipLinesForMachine(upgradeable.getUpgrades().getUpgradableItem()));
        }
        return lines;
    }

    private List<Component> extractionTooltip() {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("gui.ae2_pattern_disk.provider.extraction"));
        lines.add(Component.translatable(switch (this.menu.getExtractionMode()) {
            case OFF -> "gui.ae2_pattern_disk.provider.extraction.off";
            case NORMAL -> "gui.ae2_pattern_disk.provider.extraction.normal";
            case FAST -> "gui.ae2_pattern_disk.provider.extraction.fast";
        }));
        return lines;
    }

    private void sendAction(String name, @Nullable String json) {
        PacketDistributor.sendToServer(new GuiActionPacket(this.menu.containerId, name, json));
    }

    private void selectNextPatternProviderMode() {
        final boolean backwards = isHandlingRightClick();
        ServerboundPacket message = new ConfigButtonPacket(Settings.PATTERN_ACCESS_TERMINAL, backwards);
        PacketDistributor.sendToServer(message);
    }
}
