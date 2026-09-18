package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import guideme.PageAnchor;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.ActionButton;
import appeng.core.localization.ButtonToolTips;
import appeng.menu.SlotSemantics;
import appeng.parts.encoding.EncodingMode;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.client.gui.DiskListPanel.DiskEntry;

// NEO ECO AE Extension integration
import cn.dancingsnow.neoecoae.api.PatternEncodingTermMenuExtension;
import cn.dancingsnow.neoecoae.gui.widget.UploadButton;

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

    /** 等刷新到的上限：超过这么久还没收到新列表就不再改了。 */
    private static final long RENAME_REFRESH_TIMEOUT_MS = 2000;

    /** 上一次自动填进搜索栏的标记，避免用户清空后又被填回去。 */
    @Nullable
    private String lastAutoFilledMark;

    /** 中键待改名的磁盘。serial 从 Long.MIN_VALUE 起自增、恒为负，所以不能拿它当“无待办”的哨兵。 */
    private boolean pendingRename;
    private long pendingRenameSerial;

    /** 中键时看到的列表修订号：只有收到更新的那一份才开始改名，否则读到的还是旧标记。 */
    private long pendingRenameRevision;

    /** 等刷新的截止时刻。超了这次中键就作罢；用时间而非帧数，免得帧率越高容忍越短。 */
    private long pendingRenameDeadline;

    /** 打开终端时不自动填充：还没绑定任何标记时填了会把列表直接清空。 */
    private boolean autoFillInitialized;

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
        this.diskListPanel.setOnRightClick(this::onDiskRightClick);
        this.diskListPanel.setOnMiddleClick(this::onDiskMiddleClick);

        // 迷你搜索栏（磁盘列表内独立组件，与终端顶部主搜索栏分开）
        this.miniSearchField = widgets.addTextField("miniSearch");
        this.miniSearchField.setPlaceholder(Component.translatable("gui.ae2_pattern_disk.encoding_terminal.disk_search"));
        this.miniSearchField.setTooltip(Tooltip.create(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.disk_search.tooltip")));
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

    @Override
    public void init() {
        super.init();
        int left = (this.width - imageWidth) / 2 + imageWidth;
        int top = (this.height - imageHeight) / 2 + imageHeight - 173;
        addRenderableWidget(new UploadButton(
            left,
            top,
            b -> ((PatternEncodingTermMenuExtension) getMenu()).neoecoae$uploadPattern()
        ));
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

        // 刷新磁盘列表（过滤 PatternDiskItem + 搜索过滤）。
        // 匹配当前配方类型的磁盘不再把列表换掉，而是把搜索条件填进搜索栏：玩家看得见为什么只剩这些，
        // 而且随时能改。用的是将要绑定的那个标记，与 2.2 的绑定规则一致。
        var prefix = menu.deriveMarkId();
        if (!autoFillInitialized) {
            autoFillInitialized = true;
            lastAutoFilledMark = prefix;
        } else if (!Objects.equals(lastAutoFilledMark, prefix)) {
            lastAutoFilledMark = prefix;
            if (prefix != null && !prefix.isEmpty()) {
                miniSearchField.setValue(markSearchTerm(prefix));
            }
        }

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

        // 搜索过滤：# 开头匹配磁盘标记（标记原文或其可读名），否则匹配磁盘显示名。
        String search = diskListPanel.getSearchText();
        if (search != null && !search.isEmpty()) {
            var needle = search.toLowerCase(Locale.ROOT);
            if (needle.startsWith("#")) {
                var markNeedle = needle.substring(1);
                diskEntries.removeIf(d -> !matchesMark(d, markNeedle));
            } else {
                diskEntries.removeIf(d -> !d.displayName().toLowerCase(Locale.ROOT).contains(needle));
            }
        }

        // 按显示名排序
        diskEntries.sort(Comparator.comparing(DiskEntry::displayName));

        // 传给面板
        diskListPanel.setDiskEntries(List.copyOf(diskEntries));

        // 中键的结算：要等到刷新回来的那一份列表，否则读到的还是旧标记。等不到就作罢，而不是拿旧标记
        // 改名——那样只会把上一次的机器名写上去。
        if (pendingRename) {
            if (menu.getDiskListRevision() != pendingRenameRevision) {
                pendingRename = false;
                renameDiskBySerial(pendingRenameSerial);
            } else if (Util.getMillis() > pendingRenameDeadline) {
                pendingRename = false;
            }
        }
    }

    /** Renames the disk {@code serial} after the machine its mark stands for. */
    private void renameDiskBySerial(long serial) {
        // 从菜单的完整列表里找，而不是已经过搜索过滤的 diskEntries：改名不该受搜索框影响。
        for (var entry : menu.getDiskList()) {
            if (entry.serial() != serial) {
                continue;
            }
            var mark = entry.stack().get(AEPatternRegistries.DISK_PREFIX.get());
            var name = PatternDiskMarks.machineName(mark);
            if (name != null && !name.isEmpty()) {
                menu.setPendingDiskName(name);
                menu.renameDisk(serial);
            }
            return;
        }
    }

    /** The search term that selects disks carrying {@code mark}: the {@code #} marker plus its label. */
    private static String markSearchTerm(String mark) {
        var label = PatternDiskMarks.displayName(mark);
        return "#" + (label == null ? mark : label.getString());
    }

    /** Whether {@code entry} carries a mark matching {@code needle} (already lower-cased). */
    private static boolean matchesMark(DiskEntry entry, String needle) {
        var raw = entry.stack().get(AEPatternRegistries.DISK_PREFIX.get());
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        var label = PatternDiskMarks.displayName(entry.stack());
        return label != null && label.getString().toLowerCase(Locale.ROOT).contains(needle);
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
     * 右键：用当前配方类型覆写该磁盘的标记（覆盖旧的，不动磁盘名）。
     */
    private void onDiskRightClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry != null) {
            menu.bindPrefix(entry.serial());
        }
    }

    /**
     * 中键：把磁盘重命名为其标记所属机器的名称。标记可能刚被右键覆写过而客户端还没收到，所以先要一次
     * 权威列表，等它回来后用磁盘上真正的标记算名字（见 {@link #renameDiskBySerial(long)}）。
     */
    private void onDiskMiddleClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry == null) {
            return;
        }
        // 先写三个字段再把标志立起来：标志一为真就代表它们是一套完整值。
        pendingRenameSerial = entry.serial();
        pendingRenameRevision = menu.getDiskListRevision();
        pendingRenameDeadline = Util.getMillis() + RENAME_REFRESH_TIMEOUT_MS;
        pendingRename = true;
        menu.refreshDiskList();
    }

    @Nullable
    private DiskEntry getDiskEntryAt(int index) {
        if (index < 0 || index >= diskEntries.size()) {
            return null;
        }
        return diskEntries.get(index);
    }

    // ---- 可合成指示 ----------------------------------------------------------

    /**
     * 配方输入槽里的物品若 ME 网络能合成，在左上角画 “+”，与 AE2 样板编码终端行为一致。
     */
    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot s) {
        super.renderSlot(guiGraphics, s);

        if (shouldShowCraftableIndicatorForSlot(s)) {
            var poseStack = guiGraphics.pose();
            poseStack.pushPose();
            poseStack.translate(0, 0, 100); // 物品以 z=100 渲染；renderSizeLabel 内部再 +200，角标叠在物品之上
            StackSizeRenderer.renderSizeLabel(guiGraphics, this.font, s.x - 11, s.y - 11, "+", false);
            poseStack.popPose();
        }
    }

    // 父类方法在本项目的 AE2 类路径下是 public，覆写必须同样是 public（改成 protected 会编译失败）
    @Override
    public List<Component> getTooltipFromContainerItem(ItemStack stack) {
        var lines = super.getTooltipFromContainerItem(stack);

        if (hoveredSlot != null && shouldShowCraftableIndicatorForSlot(hoveredSlot)) {
            lines = new ArrayList<>(lines); // 原列表可能被缓存，复制后再加
            lines.add(ButtonToolTips.Craftable.text().withStyle(ChatFormatting.DARK_GRAY));
        }

        return lines;
    }

    /**
     * 只有配方输入槽参与判定（四种模式的输入位），其余槽位不显示角标。
     */
    private boolean shouldShowCraftableIndicatorForSlot(Slot s) {
        var semantic = menu.getSlotSemantic(s);
        if (semantic != SlotSemantics.CRAFTING_GRID
                && semantic != SlotSemantics.PROCESSING_INPUTS
                && semantic != SlotSemantics.SMITHING_TABLE_ADDITION
                && semantic != SlotSemantics.SMITHING_TABLE_BASE
                && semantic != SlotSemantics.SMITHING_TABLE_TEMPLATE
                && semantic != SlotSemantics.STONECUTTING_INPUT) {
            return false;
        }

        var slotContent = GenericStack.fromItemStack(s.getItem());
        return slotContent != null && repo.isCraftable(slotContent.what());
    }

    @Override
    protected PageAnchor getHelpTopic() {
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/pattern_disk_encoding_terminal.md"),
                null);
    }
}