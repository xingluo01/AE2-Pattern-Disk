package io.github.lounode.ae2pattern.client.gui;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
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
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.ToolboxPanel;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.core.localization.GuiText;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.core.network.serverbound.GuiActionPacket;
import appeng.menu.SlotSemantics;

import net.neoforged.neoforge.network.PacketDistributor;

import io.github.lounode.ae2pattern.common.logic.ProviderBlockingMode;
import io.github.lounode.ae2pattern.common.logic.ProviderExtractionMode;
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
public class PatternDiskProviderScreen<T extends PatternDiskProviderMenu> extends AEBaseScreen<T> {

    private final StatesIconButton blockingModeButton;
    private final StatesIconButton extractionButton;
    private final StatesIconButton boostButton;
    private final AETextField boostLimitField;
    private final SettingToggleButton<LockCraftingMode> lockCraftingModeButton;
    private final ToggleButton showInPatternAccessTerminalButton;
    private final LockReasonWidget lockReason;

    /** 输入栏文本正在被代码改写：此时 responder 不应反发客户端动作。 */
    private boolean syncingBoostLimit;

    /** 是否自装配版：两个构造器共用本类，指南页要按它分流。 */
    private final boolean selfAssembling;

    /** 磁盘槽空槽覆盖层：states.png (240,16,16,16)。 */
    private static final Blitter DISK_SLOT_OVERLAY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(240, 16, 16, 16);

    /** 阻挡模式按钮：前两档沿用 AE2 的 
     *  阻挡图标（{@link Icon#BLOCKING_MODE_NO} / {@link Icon#BLOCKING_MODE_YES}），
     *  只有 AE2 没有的智能阻挡用自定义 states.png (48,48,16,16)。 */
    private static final Blitter SMART_BLOCKING_ICON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(48, 48, 16, 16);

    /** 发配倍增按钮：左半为标准发配，右半为智能发配。 */
    private static final Blitter BOOST_STANDARD_ICON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(128, 32, 16, 16);
    private static final Blitter BOOST_SMART_ICON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(144, 32, 16, 16);

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

    /** 工具栏按钮背景（与批处理装配室、转存器同一套 18×20 底图）：常态 / 光标选中。 */
    private static final Blitter BUTTON_BG_NORMAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(208, 224, 18, 20);
    private static final Blitter BUTTON_BG_HOVER = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(226, 224, 18, 20);

    public PatternDiskProviderScreen(T menu, Inventory playerInventory, Component title) {
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
    public PatternDiskProviderScreen(T menu, Inventory playerInventory, Component title,
            ScreenStyle style, boolean selfAssembling) {
        super(menu, playerInventory, title, style);
        this.selfAssembling = selfAssembling;

        // 网络工具面板：玩家背包里带着网络工具时才出现，位置由样式文档的 toolbox 控件给出。
        if (menu.getToolbox().isPresent()) {
            this.widgets.add("toolbox", new ToolboxPanel(style, menu.getToolbox().getName()));
        }

        this.blockingModeButton = new StatesIconButton(() -> switch (this.menu.getBlockingMode()) {
            case DEFAULT -> Icon.BLOCKING_MODE_NO.getBlitter();
            case ALL -> Icon.BLOCKING_MODE_YES.getBlitter();
            case SMART -> SMART_BLOCKING_ICON;
        }, btn -> sendAction("cycleProviderBlocking", null));
        this.blockingModeButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.blockingModeButton.setTooltip(blockingTooltip());
        this.addToLeftToolbar(this.blockingModeButton);

        this.boostButton = new StatesIconButton(
                () -> this.menu.isBoostEnabled() ? BOOST_SMART_ICON : BOOST_STANDARD_ICON,
                btn -> sendAction("setProviderBoost", Boolean.toString(!this.menu.isBoostEnabled())));
        this.boostButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.boostButton.setTooltip(boostTooltip());
        this.addToLeftToolbar(this.boostButton);

        this.extractionButton = new StatesIconButton(() -> switch (this.menu.getExtractionMode()) {
            case OFF -> EXTRACTION_OFF_ICON;
            case NORMAL -> EXTRACTION_NORMAL_ICON;
            case FAST -> EXTRACTION_FAST_ICON;
        }, btn -> sendAction("cycleProviderExtraction", null));
        this.extractionButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.extractionButton.setTooltip(extractionTooltip());
        this.addToLeftToolbar(this.extractionButton);

        // 24px 的倍增上限输入栏：位置在 updateBeforeRender 里跟随按钮，布局文档只给尺寸。
        this.boostLimitField = this.widgets.addTextField("boostLimit");
        this.boostLimitField.setMaxLength(3);
        this.boostLimitField.setFilter(text -> text.matches("\\d{0,3}"));
        this.boostLimitField.setTooltipMessage(List.of(
                Component.translatable("gui.ae2_pattern_disk.boost.limit"),
                Component.translatable("gui.ae2_pattern_disk.boost.limit_hint")));
        this.boostLimitField.setResponder(this::onBoostLimitChanged);

        // 输入栏只在真的开了智能发配时出现：没开倍增就没有上限可设。
        this.boostLimitField.setVisible(showsBoostLimit());

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
            return new PageAnchor(
                    ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/meteorite_pattern_provider.md"),
                    null);
        }
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/pattern_disk_provider.md"),
                null);
    }

    /**
     * 输入栏只在已开智能发配时显示：没开倍增就没有上限可设。方块与面板形态同理——面板也有世界 tick 驱动合并。
     */
    private boolean showsBoostLimit() {
        return this.menu.isBoostEnabled();
    }

    /**
     * 把输入栏占的区域也让给 JEI：它在左侧工具栏之外，不在 {@code VerticalButtonBar} 自带的排除区里，
     * 不声明的话 JEI 的物品面板会盖上来。返回的矩形用屏幕坐标（与 {@code super} 一致）。
     */
    @Override
    public List<Rect2i> getExclusionZones() {
        var zones = super.getExclusionZones();
        if (this.boostLimitField != null && this.boostLimitField.isVisible()) {
            zones.add(new Rect2i(this.boostLimitField.getX() - 1, this.boostLimitField.getY() - 1,
                    this.boostLimitField.getWidth() + 2, this.boostLimitField.getHeight() + 2));
        }
        return zones;
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
        this.blockingModeButton.setTooltip(blockingTooltip());
        this.extractionButton.setTooltip(extractionTooltip());
        this.boostButton.setTooltip(boostTooltip());
        // 输入栏贴在发配倍增按钮左侧，到按钮命中区左缘间隔 2px（背景左缘在 getX()-1，因此可见缝为 1px）。
        // 纵向在按钮 18×20 背景里居中后再下移 1px：按实机目视，+4 时字面对齐了但视觉上偏高。
        this.boostLimitField.setX(this.boostButton.getX() - 2 - 24);
        this.boostLimitField.setY(this.boostButton.getY() + 5);
        this.boostLimitField.setVisible(showsBoostLimit());
        this.syncBoostLimitField();
        this.lockCraftingModeButton.set(this.menu.getLockCraftingMode());
        this.showInPatternAccessTerminalButton.setState(this.menu.getShowInAccessTerminal() == YesNo.YES);
        this.lockReason.setVisible(this.menu.getLockCraftingMode() != LockCraftingMode.NONE);
    }

    private void syncBoostLimitField() {
        if (this.boostLimitField.isFocused()) {
            return;
        }
        var expected = Integer.toString(this.menu.getBoostLimit());
        if (expected.equals(this.boostLimitField.getValue())) {
            return;
        }
        this.syncingBoostLimit = true;
        try {
            this.boostLimitField.setValue(expected);
        } finally {
            this.syncingBoostLimit = false;
        }
    }

    private void onBoostLimitChanged(String text) {
        if (this.syncingBoostLimit) {
            return;
        }
        int value;
        try {
            value = text.isEmpty() ? 0 : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return;
        }
        value = Math.max(0, Math.min(999, value));
        if (value != this.menu.getBoostLimit()) {
            sendAction("setProviderBoostLimit", Integer.toString(value));
        }
    }

    private List<Component> blockingTooltip() {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("gui.ae2_pattern_disk.provider.blocking"));
        lines.add(switch (this.menu.getBlockingMode()) {
            case DEFAULT -> Component.translatable("gui.ae2_pattern_disk.provider.blocking.default");
            case ALL -> Component.translatable("gui.ae2_pattern_disk.provider.blocking.all");
            case SMART -> Component.translatable("gui.ae2_pattern_disk.provider.blocking.smart");
        });
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

    private List<Component> boostTooltip() {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("gui.ae2_pattern_disk.boost"));
        // 上限说明只属于左侧输入栏（它自己的 tooltip 里已有），不要重复挂到按钮上。
        lines.add(Component.translatable(this.menu.isBoostEnabled()
                ? "gui.ae2_pattern_disk.boost.on"
                : "gui.ae2_pattern_disk.boost.off"));
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
