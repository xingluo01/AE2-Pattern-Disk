package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import guideme.PageAnchor;

import appeng.api.stacks.AEItemKey;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.ActionButton;
import appeng.parts.encoding.EncodingMode;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.client.gui.DiskListPanel.DiskEntry;

/**
 * 样板磁盘编码终端屏幕。布局：
 * <ul>
 *   <li>左侧 (8,86) 24×66：磁盘列表（DiskListPanel）</li>
 *   <li>中间 (36,86) 115×66：编码编辑区（4 个模式面板按当前模式切换）</li>
 *   <li>右侧 (163,130) 22×22：配方预输出槽 + 保存按钮 + 空白样板存储槽</li>
 * </ul>
 * 模式切换使用轮换按钮（左侧工具栏），而非右侧标签页。
 */
public class PatternDiskEncodingTermScreen extends MEStorageScreen<PatternDiskEncodingTermMenu> {

    // states.png (0,16,64,16) 四模式图标：合成/处理/锻造/切石
    private static final Blitter ICON_CRAFTING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(0, 16, 16, 16);
    private static final Blitter ICON_PROCESSING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(16, 16, 16, 16);
    private static final Blitter ICON_SMITHING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(32, 16, 16, 16);
    private static final Blitter ICON_STONECUTTING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(48, 16, 16, 16);

    // states.png (208,224,36,20) 模式切换按钮背景：左半常态，右半光标选中
    private static final Blitter BG_MODE_NORMAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(208, 224, 18, 20);
    private static final Blitter BG_MODE_HOVER = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(226, 224, 18, 20);

    private final Map<EncodingMode, DiskEncodingModePanel> modePanels = new EnumMap<>(EncodingMode.class);
    private final DiskListPanel diskListPanel;
    private final StatesIconButton modeCycleButton;
    private final AETextField miniSearchField;

    /** 当前磁盘条目列表（含 serial，用于回调映射）。 */
    private final List<DiskEntry> diskEntries = new ArrayList<>();

    /** 当前配方前缀（用于前缀过滤）。 */
    private String currentRecipePrefix = "";

    public PatternDiskEncodingTermScreen(PatternDiskEncodingTermMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);

        // 注册 4 个模式面板
        for (var mode : EncodingMode.values()) {
            var panel = switch (mode) {
                case CRAFTING -> new CraftingEncodingPanel(this, widgets);
                case PROCESSING -> new ProcessingEncodingPanel(this, widgets);
                case SMITHING_TABLE -> new SmithingTableEncodingPanel(this, widgets);
                case STONECUTTING -> new StonecuttingEncodingPanel(this, widgets);
            };
            var modeIndex = modePanels.size();
            widgets.add("modePanel" + modeIndex, panel);
            modePanels.put(mode, panel);
        }

        // 注册磁盘列表面板
        this.diskListPanel = new DiskListPanel();
        widgets.add("diskList", this.diskListPanel);

        // 磁盘列表点击回调
        this.diskListPanel.setOnClick(this::onDiskClick);
        this.diskListPanel.setOnShiftClick(this::onDiskShiftClick);
        this.diskListPanel.setOnMiddleClick(this::onDiskMiddleClick);

        // 迷你搜索栏（磁盘列表内独立组件，与终端顶部主搜索栏分开）
        this.miniSearchField = widgets.addTextField("miniSearch");
        this.miniSearchField.setPlaceholder(Component.translatable("gui.ae2_pattern_disk.encoding_terminal.disk_search"));
        this.miniSearchField.setResponder(text -> diskListPanel.setSearchText(text));

        // 模式轮换按钮（左侧工具栏）—— states.png 项目内图标
        this.modeCycleButton = new StatesIconButton(
                () -> switch (getMenu().getMode()) {
                    case CRAFTING -> ICON_CRAFTING;
                    case PROCESSING -> ICON_PROCESSING;
                    case SMITHING_TABLE -> ICON_SMITHING;
                    case STONECUTTING -> ICON_STONECUTTING;
                },
                btn -> cycleMode());
        this.modeCycleButton.setMessage(Component.translatable("gui.ae2_pattern_disk.encoding_terminal.mode_cycle"));
        // states.png (208,224,36,20)：左半常态背景，右半光标选中背景
        this.modeCycleButton.setBackground(BG_MODE_NORMAL, BG_MODE_HOVER);
        addToLeftToolbar(this.modeCycleButton);

        // 编码/保存按钮
        var encodeBtn = new ActionButton(appeng.api.config.ActionItems.ENCODE, act -> menu.encode());
        widgets.add("encodePattern", encodeBtn);
    }

    private void cycleMode() {
        var current = getMenu().getMode();
        var next = switch (current) {
            case CRAFTING -> EncodingMode.PROCESSING;
            case PROCESSING -> EncodingMode.SMITHING_TABLE;
            case SMITHING_TABLE -> EncodingMode.STONECUTTING;
            case STONECUTTING -> EncodingMode.CRAFTING;
        };
        getMenu().setMode(next);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();

        // 根据当前模式切换面板可见性
        var currentMode = menu.getMode();
        for (var entry : modePanels.entrySet()) {
            entry.getValue().setVisible(entry.getKey() == currentMode);
        }

        // 刷新磁盘列表（过滤 PatternDiskItem + 前缀匹配 + 搜索过滤）
        currentRecipePrefix = menu.getCurrentRecipePrefix();
        updateDiskEntries();
    }

    // ---- 磁盘列表数据 --------------------------------------------------------

    /**
     * 从服务端同步的磁盘列表（供应器磁盘槽扫描结果）构建磁盘条目，应用配方前缀过滤与迷你搜索过滤，
     * 并传递给磁盘列表面板。
     */
    private void updateDiskEntries() {
        diskEntries.clear();

        // 磁盘来自服务端扫描的供应器磁盘槽（DiskListPayload 同步）
        for (var entry : menu.getDiskList()) {
            var stack = entry.stack();
            if (!(stack.getItem() instanceof PatternDiskItem disk)) continue;

            var contents = disk.contents(stack);
            diskEntries.add(new DiskEntry(
                    stack,
                    stack.getHoverName().getString(),
                    contents.used(),
                    contents.capacity(),
                    1,
                    entry.serial()));
        }

        // 配方前缀过滤：若网络中存在前缀匹配的磁盘，仅显示该磁盘；否则显示全部
        var prefix = currentRecipePrefix;
        if (prefix != null && !prefix.isEmpty()) {
            boolean hasMatchingDisk = diskEntries.stream()
                    .anyMatch(d -> prefix.equals(d.stack().get(io.github.lounode.ae2pattern.AEPatternRegistries.DISK_PREFIX.get())));
            if (hasMatchingDisk) {
                diskEntries.removeIf(d -> !prefix.equals(d.stack().get(io.github.lounode.ae2pattern.AEPatternRegistries.DISK_PREFIX.get())));
            }
        }

        // 迷你搜索过滤
        String search = diskListPanel.getSearchText();
        if (search != null && !search.isEmpty()) {
            diskEntries.removeIf(d -> !d.displayName().toLowerCase(java.util.Locale.ROOT)
                    .contains(search.toLowerCase(java.util.Locale.ROOT)));
        }

        // 按显示名排序
        diskEntries.sort(Comparator.comparing(DiskEntry::displayName));

        // 传给面板
        diskListPanel.setDiskEntries(List.copyOf(diskEntries));
    }

    // ---- 磁盘列表交互 --------------------------------------------------------

    /**
     * 左键点击磁盘：把当前编码的样板写入该磁盘。
     */
    private void onDiskClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry != null) {
            menu.transferToDisk(entry.serial());
        }
    }

    /**
     * 潜行左键：把当前配方前缀绑定到磁盘并重命名。
     */
    private void onDiskShiftClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry != null) {
            menu.bindPrefix(entry.serial());
        }
    }

    /**
     * 中键：重命名磁盘（占位）。
     */
    private void onDiskMiddleClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry != null) {
            menu.renameDisk(entry.serial());
        }
    }

    @Nullable
    private DiskEntry getDiskEntryAt(int index) {
        if (index < 0 || index >= diskEntries.size()) {
            return null;
        }
        return diskEntries.get(index);
    }

    @Override
    protected PageAnchor getHelpTopic() {
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/pattern_disk_encoding_terminal.md"),
                null);
    }
}