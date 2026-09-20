package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import guideme.PageAnchor;

import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.behaviors.EmptyingAction;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.ActionButton;
import appeng.core.localization.ButtonToolTips;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.SlotSemantics;
import appeng.parts.encoding.EncodingMode;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.client.integration.MachineRecipeTypes;
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
    /** NEO ECO 上传按钮的尺寸（neoecoae 的 UploadButton 构造里写死的 18×20）。 */
    static final int NEO_ECO_UPLOAD_BUTTON_WIDTH = 18;
    static final int NEO_ECO_UPLOAD_BUTTON_HEIGHT = 20;
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

    // states.png (16,0,16,8)：左 8x8 = 强制列出全部磁盘（含无标记的），右 8x8 = 只列有标记的
    private static final Blitter ICON_SHOW_UNMARKED_ON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(16, 0, 8, 8);
    private static final Blitter ICON_SHOW_UNMARKED_OFF = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(24, 0, 8, 8);

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

    /** 磁盘列表的搜索框（子屏要给它焦点，或者按自己的布局重新定位时读它）。 */
    protected final AETextField miniSearchField() {
        return miniSearchField;
    }

    /** 当前磁盘条目列表（含 serial，用于回调映射）。 */
    private final List<DiskEntry> diskEntries = new ArrayList<>();

    /** 等刷新到的上限：超过这么久还没收到新列表就不再改了。 */
    private static final long RENAME_REFRESH_TIMEOUT_MS = 2000;

    /** 已经填过的那次导入（菜单里的导入修订号）：搜索栏只在导入发生时填，见 updateBeforeRender。 */
    private int seenImportRevision;

    /** 是否把无标记的磁盘也列出来。默认否；按钮的状态跟着它走（init 会被多次调用）。 */
    private boolean showUnmarkedDisks;

    /** 无标记磁盘的显示开关；每帧回写状态，否则点下去图标不会变。 */
    private StatesToggleButton showUnmarkedButton;

    /** 中键待改名的磁盘。serial 从 Long.MIN_VALUE 起自增、恒为负，所以不能拿它当“无待办”的哨兵。 */
    private boolean pendingRename;
    private long pendingRenameSerial;

    /** 中键时看到的列表修订号：只有收到更新的那一份才开始改名，否则读到的还是旧标记。 */
    private long pendingRenameRevision;

    /** 等刷新的截止时刻。超了这次中键就作罢；用时间而非帧数，免得帧率越高容忍越短。 */
    private long pendingRenameDeadline;

    public PatternDiskEncodingTermScreen(PatternDiskEncodingTermMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);
        // 与菜单对齐，不假设「新屏幕一定配新菜单」：万一菜单是复用的，开屏第一帧就不该把旧导入填回去。
        this.seenImportRevision = menu.getCategoryImportRevision();

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

        // 注册磁盘列表面板（管理终端不要这个面板：它把磁盘铺进自己的表里，复用面板只为共享搜索状态）
        this.diskListPanel = new DiskListPanel();
        if (usesDiskListPanel()) {
            widgets.add("diskList", this.diskListPanel);
        }

        // 磁盘列表点击回调
        this.diskListPanel.setOnClick(this::onDiskClick);
        this.diskListPanel.setOnRightClick(this::onDiskRightClick);
        this.diskListPanel.setOnShiftRightClick(this::onDiskShiftRightClick);
        this.diskListPanel.setOnMiddleClick(this::onDiskMiddleClick);

        // 迷你搜索栏（磁盘列表内独立组件，与终端顶部主搜索栏分开）
        this.miniSearchField = widgets.addTextField("miniSearch");
        this.miniSearchField.setPlaceholder(Component.translatable("gui.ae2_pattern_disk.encoding_terminal.disk_search"));
        // 走 AE2 自己的链路（不是自建 Tooltip）：AEBaseScreen 会给实现 ITooltip 的控件渲染 tooltip，
        // 并自动把第一行刷白、其余行刷灰，与 AE2 终端搜索框完全一致。
        this.miniSearchField.setTooltipMessage(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.disk_search.title"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.disk_search.mark_hint")));
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
        // 编码/保存按钮：网络里没有空白样板就不必白跑一趟服务端，直接说清楚原因。
        var encodeBtn = new ActionButton(appeng.api.config.ActionItems.ENCODE, act -> {
            if (!menu.canEncode()) {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.sendSystemMessage(Component.translatable(
                            "gui.ae2_pattern_disk.encoding_terminal.no_blank_pattern"));
                }
                return;
            }
            // 写盘目标是不是唯一，只有这边知道（过滤后的列表在客户端），所以没目标时由客户端说。
            // 报上实际张数：只说“没有恰好一张”的话，看不出到底是零张还是多张。
            if (menu.getClientAutoDiskCount() != 1) {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.sendSystemMessage(Component.translatable(
                            "gui.ae2_pattern_disk.encoding_terminal.no_auto_target", diskEntries.size()));
                }
            }
            menu.encode();
        });
        widgets.add("encodePattern", encodeBtn);
    }

    /**
     * 是否把这个屏幕的磁盘列表面板接进界面。
     *
     * <p>管理终端把磁盘铺进自己的表格，不要那个 24×66 的竖列表；但它的迷你搜索框、筛选口径、选中/写盘目标
     * 逻辑都靠这个面板对象承载，所以面板本身照建（见使用处），只是不接进 widgets——不接进 widgets 就不会被绘制、
     * 也收不到鼠标事件，等于一个只存状态的容器。</p>
     */
    protected boolean usesDiskListPanel() {
        return true;
    }

    /**
     * NEO ECO 上传按钮在屏幕上的绝对位置与尺寸（该按钮固定 18×20，见 neoecoae 的 UploadButton）。
     *
     * <p>{@link #init()} 造按钮与 ExtendedAE Plus 的适配屏幕取锚点都走这一份算式，免得两处位置漂移。</p>
     */
    public Rect2i neoEcoUploadButtonBounds() {
        int left = (this.width - imageWidth) / 2 + imageWidth;
        int top = (this.height - imageHeight) / 2 + imageHeight - 173;
        return new Rect2i(left, top, NEO_ECO_UPLOAD_BUTTON_WIDTH, NEO_ECO_UPLOAD_BUTTON_HEIGHT);
    }

    @Override
    public void init() {
        super.init();
        var ecoUpload = neoEcoUploadButtonBounds();
        var search = this.miniSearchField;
        io.github.lounode.ae2pattern.client.integration.neoecoae.NeoECOClientIntegration.addUploadButtonIfPresent(this,
                ecoUpload.getX(), ecoUpload.getY());

        // 无标记磁盘的显示开关，贴在搜索栏右边 2px（搜索栏的可见宽度含内边距，所以要用它的 tooltip 区域），
        // 与它同高：搜索栏高 8，按钮也是 8x8，顶对齐即居中。
        var showUnmarked = new StatesToggleButton(ICON_SHOW_UNMARKED_ON, ICON_SHOW_UNMARKED_OFF,
                state -> this.showUnmarkedDisks = state);
        showUnmarked.setHalfSize(true);
        // 不要 hover 下压动画：开关的两种状态对应同一枚图标，悬停时下移 1px 会让它看起来在跳。
        showUnmarked.setPressAnimation(false);
        var searchArea = search.getTooltipArea();
        showUnmarked.setX(searchArea.getX() + searchArea.getWidth() + 2);
        showUnmarked.setY(search.getY());
        showUnmarked.setState(showUnmarkedDisks);
        showUnmarked.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.show_unmarked.on")));
        showUnmarked.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.show_unmarked.off")));
        addRenderableWidget(showUnmarked);
        this.showUnmarkedButton = showUnmarked;
    }

    /**
     * 磁盘搜索框聚焦时直接转给它，绕开 {@code MEStorageScreen.charTyped} 的“搜索框为空时吞掉空格”。
     *
     * <p>那条特例是给物品网格搜索用的（空格在那边是快捷操作），但磁盘名里就有空格，所以磁盘搜索框必须收得下。</p>
     */
    @Override
    public boolean charTyped(char character, int modifiers) {
        if (miniSearchField.isFocused()) {
            return miniSearchField.charTyped(character, modifiers);
        }
        return super.charTyped(character, modifiers);
    }

    /**
     * 同理：父类的回车分支只认物品网格搜索框，磁盘搜索框里的回车会落到 super，行为不定。
     * 这里按同一口径处理：回车收起焦点。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (miniSearchField.isFocused() && keyCode == GLFW.GLFW_KEY_ENTER) {
            miniSearchField.setFocused(false);
            setFocused(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

        // 开关状态以字段为准回写：按钮自己只会翻转它内部那个 state，不回写就永远停在初始态。
        if (this.showUnmarkedButton != null) {
            this.showUnmarkedButton.setState(showUnmarkedDisks);
        }

        // 根据当前模式切换面板可见性
        var currentMode = menu.getMode();
        for (var entry : modePanels.entrySet()) {
            entry.getValue().setVisible(entry.getKey() == currentMode);
        }

        // 刷新磁盘列表（过滤 PatternDiskItem + 搜索过滤）。
        // 搜索栏的自动填充只跟「导入配方」有关：JEI/EMI 配方页点「编写样板」那一刻在菜单里记一次修订号，
        // 这里跟着填。绑定标记、切换模式、放样板回流同样会改掉标记的值，但它们都不该动玩家正在用的搜索
        // 条件——判据是「发生了导入」，而不是「标记值变了」（后者曾把右键写标记也变成改写搜索框）。
        var importRevision = menu.getCategoryImportRevision();
        if (importRevision != seenImportRevision) {
            seenImportRevision = importRevision;
            // 用导入当场记下的类别，而不是此刻的「当前类别」：后者可能已被右键（光标上的工作方块）改掉，或被
            // 切模式清空。类别为空时不填——那会退回模式标记（#mode:...），不是导入者想要的筛选词。
            var imported = menu.getLastImportedCategory();
            if (imported != null && !imported.isEmpty()) {
                miniSearchField.setValue(markSearchTerm("#" + imported));
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

        // 搜索过滤：**没有搜索条件就什么都不剔除**（含无标记的空盘）——这是「常态显示」的字面意思，也是旧
        // 实现写错的地方（它不带任何搜索条件也按标记默认剔一遍）。
        //
        // 一个搜索条件只筛它自己那一维：名字搜索只比名字（无标记的盘照常参与，它也有名字），`#` 标记搜索
        // 只比标记（无标记的盘没东西可匹配，自然不出现）。开关打开 = 无标记的盘不受本次搜索约束，一律留下。
        // 输入先 strip，免得一串空格被当成搜索条件把列表清空。
        var search = diskListPanel.getSearchText();
        var needleText = search == null ? "" : search.strip();
        if (!needleText.isEmpty()) {
            var needle = needleText.toLowerCase(Locale.ROOT);
            var markSearch = needle.startsWith("#");
            // `#` 之后也 strip：玩家习惯输入 `# 合成`，多一个空格不该把结果清空（标记本身也存不下首尾空格）。
            var matchNeedle = markSearch ? needle.substring(1).strip() : needle;
            diskEntries.removeIf(d -> {
                if (!hasMark(d)) {
                    // 无标记：开关打开时一律留下；常态下仅在标记搜索里被筛掉（它没有标记可匹配）。
                    return !showUnmarkedDisks && markSearch;
                }
                return !matchesSearch(d, matchNeedle, markSearch);
            });
        }

        // 没有搜索条件时什么都不剔除（含无标记的空盘）——规则写在上面那段注释里。

        // 按显示名排序
        diskEntries.sort(Comparator.comparing(DiskEntry::displayName));

        // 传给面板
        diskListPanel.setDiskEntries(List.copyOf(diskEntries));

        // 编码按钮要不要直接落盘，取决于搜索栏筛完还剩几张盘。这一步必须等过滤做完：玩家点按钮时看到的
        // 就是这份列表，早一帧算出来就可能把目标算成此刻已经看不到的那张盘。
        menu.setClientAutoDisk(diskEntries.size(), diskEntries.size() == 1 ? diskEntries.get(0).serial() : 0L);

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

    /** 这张盘有没有标记——标记就是它属于哪个配方类型的记录。 */
    /** 磁盘是否匹配当前搜索：{@code markSearch} 时比标记（原文或可读名），否则比显示名。 */
    private static boolean matchesSearch(DiskEntry entry, String needle, boolean markSearch) {
        if (markSearch) {
            return matchesMark(entry, needle);
        }
        return entry.displayName().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean hasMark(DiskEntry entry) {
        var mark = entry.stack().get(AEPatternRegistries.DISK_PREFIX.get());
        return mark != null && !mark.isEmpty();
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
    /** 子类（管理终端）用：把「磁盘序列号」映射到本屏幕列表里的下标，好复用下面的点击交互。 */
    protected int indexOfDisk(long serial) {
        for (int i = 0; i < diskEntries.size(); i++) {
            if (diskEntries.get(i).serial() == serial) {
                return i;
            }
        }
        return -1;
    }

    /** 子类用：过滤后列表里第 {@code index} 张盘的条目；越界返回 {@code null}。 */
    protected @Nullable DiskEntry diskEntryAt(int index) {
        return index >= 0 && index < diskEntries.size() ? diskEntries.get(index) : null;
    }

    protected void onDiskClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry != null) {
            menu.transferToDisk(entry.serial());
        }
    }

    /**
     * 右键：用当前配方类型覆写该磁盘的标记（覆盖旧的，不动磁盘名）。
     *
     * <p>「当前配方类型」优先看**鼠标上拿着**的那个工作方块：拿起工作方块右键，写的就是它所属的类别，任何
     * 时机都成立——不取决于有没有导入过配方，也不取决于中间切没切过模式（这两件事都会把导入时记下的类别
     * 清掉）。光标上那件认不出类别时退回刚导入的配方类别；两样都没有就干脆不写——写下去只会是模式标记，而
     * 清空/改写标记是 Shift+右键的活。写没写成由服务端在聊天栏回执（见 Menu#bindPrefix）。</p>
     */
    protected void onDiskRightClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry == null) {
            return;
        }
        applyHeldMachineMark();
        menu.bindPrefix(entry.serial());
        // 写没写成由服务端在聊天栏里回执（与上传链路同一路），客户端不抢着报结果，也不必再管搜索栏：
        // 填充只认「导入配方」一个入口。
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.mark");

    /**
     * 把标记的类别换成鼠标上拿着的工作方块所属的那一个；认不出就不动标记（玩家侧的回执由服务端发，见
     * {@code Menu#bindPrefix}）。
     *
     * <p>「持有」只认**光标上拿着的那一件**，主手/副手不参与：在终端里整理磁盘时，工作方块正是这么被拿起来的，
     * 而手边顺带放着的东西不该决定这张盘的标记。</p>
     *
     * <p>「认不出来」这件事必须记下来：否则玩家只看到这次右键没写入，会以为是功能坏了，而实际上是拿着的方块
     * 不在配方查看器的机器表里（EMI：类别图标或工作站；JEI：催化剂）。</p>
     */
    private void applyHeldMachineMark() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // 「持有」只认鼠标上拿着的那一件：终端里整理磁盘时，工作方块正是这么被拿起来的。主手/副手不参与——
        // 它们往往只是顺带放着的东西，拿它们去决定这张盘的标记只会让人莫名其妙。
        var held = menu.getCarried();
        String category = null;
        try {
            var imported = menu.getPendingRecipeCategory();
            category = MachineRecipeTypes.forHeldMachine(held, imported);
        } catch (Throwable failed) {
            // 配方查看器还没就绪、或者它换了 API，都不该让右键失效：退回原有行为即可。
            LOGGER.warn("Work-block mark lookup failed for {}", held, failed);
        }
        if (category != null) {
            // 提到 info：一次右键一行，而排查「识别成功却没写出标记」时正是靠它与服务端的
            // "Binding mark ..." 成对出现——只有客户端有日志的话，就没法区分「没识别」与「没送达」。
            LOGGER.info("Mark taken from work block on the cursor {}: {}", held, category);
            menu.setPendingRecipeCategory(category);
            return;
        }
        if (!held.isEmpty()) {
            // 认不出来时把反查链路的现场一起说出来：只有「认不出」一句的话，看的人只能猜是光标上那件不对、
            // 查看器没就绪、还是索引里根本没有它。诊断本身会遍历建表，可能被第三方查看器数据噎住，所以
            // 夹住它——诊断失败不该让右键也跟着失败。
            String scene;
            try {
                scene = MachineRecipeTypes.diagnose(held);
            } catch (Throwable failed) {
                scene = "诊断本身失败（不影响标记回退）：" + failed;
                LOGGER.warn("Work-block diagnose failed for {}", held, failed);
            }
            LOGGER.info("Held {} is no recipe category's work block; there is no work-block category to bind. {}",
                    held, scene);
            // 玩家侧不再由客户端发话：写没写成、为什么没写成，统一由服务端在聊天栏里回执。
        } else {
            // 光标上什么都没有：没什么可识别。写不写由服务端定（见 Menu#bindPrefix），顺便记下当时选中的
            // 快捷栏格号，省得下次还要猜玩家周围到底放了什么。
            LOGGER.info("Nothing on the cursor; no work-block category to take (selected hotbar slot={})",
                    player.getInventory().selected);
        }
    }

    /**
     * Shift+右键：把搜索栏里写的那个标记打到这张盘上。它不依赖“当前导入的配方类型”，所以玩家可以先搜出
     * 某类磁盘，再把同一个标记标到别的盘上。搜索栏为空时反过来清掉这张盘的标记。
     */
    protected void onDiskShiftRightClick(int index) {
        var entry = getDiskEntryAt(index);
        if (entry == null) {
            return;
        }
        // 搜索栏为空 = 没有标记可打，那就把这张盘已有的标记去掉。
        var search = diskListPanel.getSearchText();
        menu.setPendingMarkText(search == null ? "" : search);
        menu.bindSearchMark(entry.serial());
    }

    /**
     * 中键：把磁盘重命名为其标记所属机器的名称。标记可能刚被右键覆写过而客户端还没收到，所以先要一次
     * 权威列表，等它回来后用磁盘上真正的标记算名字（见 {@link #renameDiskBySerial(long)}）。
     */
    protected void onDiskMiddleClick(int index) {
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

    // ---- 过滤槽交互 ----------------------------------------------------------

    /**
     * 处理模式的输入/输出过滤槽按玩家口径改写：右键携带物品=把该物品连同它的堆叠数标记进槽位（覆盖
     * 槽内原有内容，不累加），左键=清空。
     * AE2 默认的 FakeSlot 语义是“左键放物品、右键加减数量”，与这套口径不同，所以直接发显式的
     * SET_FILTER，而不走默认的 FakeSlot 动作。
     *
     * <p>空手右键没有可标记的东西，交回 AE2 的默认语义（把槽内数量减一）；Shift 点击、拖动、双击也一律
     * 走 AE2 默认，属已知差异。手持桶/瓶这类可倒空的容器时也交回基类，走 AE2 的 EMPTY_ITEM 把内容物
     * 设成过滤（见 {@link #getEmptyingAction}），而不是把容器本身标记进去。</p>
     */
    @Override
    protected void slotClicked(@Nullable Slot slot, int slotIdx, int mouseButton, ClickType clickType) {
        if (clickType == ClickType.PICKUP && menu.isProcessingPatternSlot(slot)) {
            var carried = menu.getCarried();
            if (mouseButton == InputConstants.MOUSE_BUTTON_RIGHT) {
                if (!carried.isEmpty()) {
                    // 能倒空的容器（桶/瓶）优先：AE2 会把内容物设成过滤，这是手动设流体过滤的唯一入口。
                    if (getEmptyingAction(slot, carried) != null) {
                        super.slotClicked(slot, slotIdx, mouseButton, clickType);
                        return;
                    }
                    sendSetFilter(slot.index, carried.copy());
                    return;
                }
            } else if (mouseButton == InputConstants.MOUSE_BUTTON_LEFT) {
                sendSetFilter(slot.index, ItemStack.EMPTY);
                return;
            }
        }

        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    /**
     * 照抄 AE2 样板编码终端的口径：处理槽先直接问手里这件可倒空容器（{@link ContainerItemStrategies}）
     * 能倒出什么，拿到了就把这个动作交给基类去走 EMPTY_ITEM，拿不到再退回基类判定。
     *
     * <p>这里和 AE2 一样跳过了基类的 {@code isItemValid} 闸门。日后若给编码配置加上
     * {@code supportedType}/{@code slotFilter} 限制，需同步补回校验，否则会出现「提示可倒空、服务端
     * 静默不落」的空操作。</p>
     */
    @Override
    protected EmptyingAction getEmptyingAction(Slot slot, ItemStack carried) {
        if (menu.isProcessingPatternSlot(slot)) {
            var emptyingAction = ContainerItemStrategies.getEmptyingAction(carried);
            if (emptyingAction != null) {
                return emptyingAction;
            }
        }

        return super.getEmptyingAction(slot, carried);
    }

    /** 中键落在过滤槽上时打开数量对话框（与 AE2 样板编码终端同款）；其余中键仍交给基类。 */
    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        if (minecraft != null && minecraft.options.keyPickItem.matchesMouse(btn)) {
            var slot = processingPatternSlotAt(xCoord, yCoord);
            if (menu.canModifyAmountForSlot(slot)) {
                var currentStack = GenericStack.fromItemStack(slot.getItem());
                if (currentStack != null) {
                    switchToScreen(new DiskEncodingAmountScreen(this, currentStack,
                            newStack -> sendSetFilter(slot.index,
                                    newStack == null ? ItemStack.EMPTY : GenericStack.wrapInItemStack(newStack))));
                    return true;
                }
            }
        }

        return super.mouseClicked(xCoord, yCoord, btn);
    }

    /**
     * 鼠标下的处理模式过滤槽；没命中时返回 null。
     *
     * <p>命中区自己算：AE2 是带着它自己的访问放宽才调用 {@code findSlot} 的，该项目类路径下这个方法
     * 不可访问（实测编译不通过），所以这里照 MC 的 18×18 口径自己判。</p>
     */
    @Nullable
    private Slot processingPatternSlotAt(double mouseX, double mouseY) {
        for (var slot : menu.slots) {
            if (!slot.isActive() || !menu.isProcessingPatternSlot(slot)) {
                continue;
            }
            // 命中区与 MC 一致：以槽位左上角为准的 18×18（含 1 像素边框）。
            if (mouseX >= leftPos + slot.x - 1 && mouseX < leftPos + slot.x + 17
                    && mouseY >= topPos + slot.y - 1 && mouseY < topPos + slot.y + 17) {
                return slot;
            }
        }
        return null;
    }

    /**
     * 过滤槽的内容由服务端盖章（SET_FILTER 走的 {@code AEBaseMenu#setFilter}），客户端只负责把请求发出去；
     * 空物品即清空。
     */
    private static void sendSetFilter(int slotIndex, ItemStack stack) {
        PacketDistributor.sendToServer(
                new InventoryActionPacket(InventoryAction.SET_FILTER, slotIndex, stack));
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

    /**
     * Public wrapper for adding widgets (delegates to protected {@link #addRenderableWidget}).
     * Used by the neoecoae integration to install the upload button without accessing
     * the protected method from a different package.
     */
    public void addWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
        addRenderableWidget(widget);
    }
}