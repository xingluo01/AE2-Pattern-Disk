package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import appeng.client.gui.StackWithBounds;
import appeng.client.gui.me.common.RepoSlot;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.DisabledSlot;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.core.localization.ButtonToolTips;

import io.github.lounode.ae2pattern.client.sort.NaturalOrder;
import io.github.lounode.ae2pattern.client.sort.NaturalSort;
import io.github.lounode.ae2pattern.client.sort.SortTiers;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * The pattern disk management terminal's screen: a table of every disk on the grid, grouped by the machine
 * holding it, with the encoding terminal's encoding area underneath.
 *
 * <p>Layout comes from {@code Sprite-0001} (the texture this screen slices): a title strip and 17 columns of
 * 18px cells on top, then the player inventory on the left and the encoding area - the very same widgets the
 * encoding terminal builds in its constructor - on the right. The footer entries in the style JSON are
 * {@code bottom}-anchored, so they follow the panel height that {@code terminalStyle} produces.</p>
 *
 * <p><b>Rows.</b> One row per machine (a header carrying its icon, name and disk count), then one row per
 * disk: cell 0 is the disk itself, the remaining 16 cells are the patterns stored on
 * it. A disk holding more than 16 patterns continues on the rows below, where all 17 cells are patterns.
 * Disk-level clicks go through the inherited {@code onDisk*Click} handlers, so selecting, marking and
 * renaming a disk behave exactly as in the encoding terminal.</p>
 *
 * <p><b>Search.</b> The mini search box is inherited unchanged and still filters the inherited disk list; the
 * table is built from that filtered list, which keeps "what is on screen" and "what the encode button writes
 * to" the same set.</p>
 *
 * <p><b>Contents.</b> Patterns shown in the cells come from {@link PatternDiskManagementTermMenu}'s cache,
 * filled by the server in response to {@link VisibleDisksPayload}. Until a disk's contents arrive its row
 * shows just the disk; the request is re-sent whenever the visible set changes.</p>
 */
public class PatternDiskManagementTermScreen extends PatternDiskEncodingTermScreen {

    /**
     * 本屏幕的菜单是管理终端自己的那个类型，但父屏把菜单类型写死在签名里（把它泛型化会牵连编码区面板、数量子屏与
     * 两个集成适配类），所以在这里用协变返回把类型收窄：本类内部直接调管理菜单的方法，其余文件一动不动。
     */
    @Override
    public PatternDiskManagementTermMenu getMenu() {
        return (PatternDiskManagementTermMenu) super.getMenu();
    }

    private static final ResourceLocation TEXTURE = ResourceLocation
            .parse("ae2_pattern_disk:textures/guis/pattern_disk_management_terminal.png");

    /**
     * 贴图的真实像素尺寸。
     *
     * <p>本模组其余 GUI 贴图都是 256×256，而这张是 512×512：AE2 的 {@code Blitter} 与
     * {@code GuiGraphics.blit} 的简写重载都按 256 算 UV（`srcRect / 256`），不声明真实尺寸的话
     * 同一块 srcRect 会到 2 倍坐标处取样，整块背景都是错的。</p>
     */
    private static final int TEXTURE_SIZE = 512;

    /** 「显示模式」按钮：与 AE2 样板访问终端共用同一个服务端设置。 */
    private final ServerSettingToggleButton<ShowPatternProviders> showProvidersButton;

    /** 「隐藏槽位/显示槽位」按钮：只影响本屏的行模型，不涉服务端。按下时要回写状态，所以不是 final。 */
    private StatesToggleButton hideSlotsButton;

    /** 当前是否收起空槽。默认收起：表的常规观感保持紧凑，想看全槽布局再展开。 */
    private boolean hideEmptySlots = true;

    /**
     * 右键选中的磁盘（「编写样板」的写盘目标）；0 = 没选。
     *
     * <p>serial 从 {@code Long.MIN_VALUE} 起自增、恒为负（见菜单里的说明），所以 0 可以安全地当「没选」。</p>
     */
    private long selectedSerial;

    // 表格区几何：按贴图实测（描边带 x0..7，填充区从 x8 开始；表头 y0..16）。
    // 行分配同 AE2 的样板访问终端（PatternAccessTermScreen.drawBG）：一行 18px，按「行类型」从贴图取行带——
    // 文本带 y17/53/89（无格子框，给主机名这类纯文本行）与物品带 y35/71/107（有 17 格框，给磁盘/样板行），
    // 三份分别对应可见窗口的首行/中间行/末行（该屏的 ROW_TEXT_/ROW_INVENTORY_TOP|MIDDLE|BOTTOM_BBOX 同值）。
    // 落点口径同 AE2 槽位：格子的 x/y 就是「物品左上角」，底图画在它 −1 处，所以绘制时统一 +1——
    // LIST_X 取 7 时物品落在贴图实测的 x=8。
    private static final int PANEL_WIDTH = 340;
    /**
     * 面板高度不是常量：它由终端风格档位算出的行数决定（表头 + 行×18 + 尾饰）。这里的常量是它的三块组成，
     * 以及“一行也不显示”时的高度（17 + 0 + 95 = 112）。
     */
    private static final int HEADER_HEIGHT = 17;
    /** 尾饰带高：贴图 y125..219。编码区、背包都在这一段，它跟着面板底部走。 */
    private static final int FOOTER_HEIGHT = 95;
    private static final int LIST_X = 7;
    private static final int LIST_Y = 0;

    /** 填充区宽：17 格 × 18px。贴图填充区实测到 x=311，再右是滚动条区（本屏只用滚轮，不画它）。 */
    private static final int LIST_WIDTH = 306;

    /** 行高：与 AE2 一致的一行 18px（贴图里每种行带也都是 18px 高）。 */
    private static final int ROW_HEIGHT = 18;

    /**
     * 槽位内容的纵向起点：行带里格框的填充区从带上沿 +1 开始（横向同理，见各绘制处的 +1）。物品与槽底都从这里
     * 画，不然会比格框低 1px、底下露出一道缝。
     */
    private static final int CELL_Y_INSET = 1;
    /**
     * 纯文本行（组头）的纵向起点：那一行没有格框，图标/文字/开关按自己的观感取 +2，不跟着槽位一起上移。
     */
    private static final int ROW_TEXT_Y_INSET = 2;
    private static final int COLUMNS = 17;
    /** 视口外多要一行内容：滚一格时不至于先闪一帧空行。 */
    private static final int CONTENT_MARGIN_ROWS = 1;

    /**
     * 当前可见行数：由终端风格档位与窗口高度共同决定，数值就是父类按 terminalStyle 算好的 imageHeight 反推来的，
     * {@code init()} 里赋。屏幕不随 resize 重建，但 init() 会在 resize 后被调用，所以这一个字段就够。
     */
    private int visibleRows = 6;

    /**
     * 每张盘的「显示顺序」：显示位次 → 盘内存储序号。
     *
     * <p>盘里的样板原来按写入次序铺，而写入次序对玩家没什么意义（按名字找一张合成表要一行行扫）。这里按
     * 终端的排序档位排一遍：名称档按格子显示的那个名字，mod 档先按它的 mod 分组；「数量」档没有可比的东西
     * （一枚样板就是一件），保持原顺序。开关打开时名字按数值比，于是 1k/16k/256k 与 4/16/64 排得对。</p>
     *
     * <p>存的是序号数组而不是排好的堆：格子画什么、点下去取哪一枚，都得回到服务端的存储序号上（取件按
     * 序号说话），排堆会把那层对应关系拆散。
     */
    private final Map<Long, DisplayOrder> displayOrders = new HashMap<>();

    /**
     * 表格自己的滚动条（字段名与父类那枚区分开）。本屏的行带不是 AE2 物品网格，所以范围得自己喂给它（见 {@link #syncScrollbar()}）；
     * 拖动、滚轮、上下翻页按钮都由它处理，屏幕只负责把位置抄回来。
     */
    private Scrollbar tableScrollbar;

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.management_terminal");

    private static final int[] NO_ORDER = new int[0];

    /** 一次排序的结果；内容或排序口径一变就重算（拿内容列表的引用比，服务端每次推送会换一个新列表）。 */
    private record DisplayOrder(List<ItemStack> contents, SortOrder order, SortDir dir, boolean natural,
            int[] storageIndexes) {

        boolean stillMatches(List<ItemStack> contents, SortOrder order, SortDir dir, boolean natural) {
            return this.contents == contents && this.order == order && this.dir == dir && this.natural == natural;
        }
    }

    // 静态 Blitter：UV 按 512 算（见 TEXTURE_SIZE），每帧不新建对象。
    // 注意它们是可变对象：每次使用必须紧接 dest(...) + blit(...)，不要缓存引用到别处再画。
    private static final Blitter HEADER_BAND = band(0, HEADER_HEIGHT);
    private static final Blitter FOOTER_BAND = band(125, FOOTER_HEIGHT);

    /** 空槽格的底图：贴图里「空白样板」那一格，宽高正好是 18×18 的槽位框。 */
    private static final Blitter BLANK_CELL = Blitter.texture(TEXTURE, TEXTURE_SIZE, TEXTURE_SIZE)
            .src(304, 144, 18, 18);

    // 六条行带，与 AE2 的 ROW_TEXT_/ROW_INVENTORY_TOP|MIDDLE|BOTTOM_BBOX 同一分区（本屏贴图与它同源）。
    private static final Blitter ROW_TEXT_TOP = rowBand(17);
    private static final Blitter ROW_INVENTORY_TOP = rowBand(35);
    private static final Blitter ROW_TEXT_MIDDLE = rowBand(53);
    private static final Blitter ROW_INVENTORY_MIDDLE = rowBand(71);
    private static final Blitter ROW_TEXT_BOTTOM = rowBand(89);
    private static final Blitter ROW_INVENTORY_BOTTOM = rowBand(107);

    /** 整条带（含面板左右边框）：行带要盖住 x0..6 的描边，否则多行铺出来会留下重影。 */
    private static Blitter band(int srcY, int height) {
        return Blitter.texture(TEXTURE, TEXTURE_SIZE, TEXTURE_SIZE).src(0, srcY, PANEL_WIDTH, height);
    }

    private static Blitter rowBand(int srcY) {
        return band(srcY, ROW_HEIGHT);
    }

    /**
     * 可见集合没变也重报一次的间隔（tick）。
     *
     * <p>没有这个“心跳”，盘内内容一变就会过期：客户端只会在自己那份「已请求」集合变化时上报，而往里写入一张
     * 样板并不改变集合。服务端每张盘比指纹、没变就不发包，所以这条心跳的常态开销只是一次空请求。</p>
     */
    private static final int CONTENT_REFRESH_INTERVAL_TICKS = 20;

    /**
     * 磁盘槽的浅绿色底色（纯上色，不走纹理资源）。
     *
     * <p>与盘内样板的格子在视觉上分开：一眼能看出哪一格是磁盘本身。带 alpha，底下的槽位描边与棋盘格仍透着。</p>
     */
    private static final int DISK_SLOT_TINT = 0x66B9F6CA;

    /** 右键选中的那张盘：给它的首格描一圈高亮，一眼看出「编写样板」会写进谁。 */
    private static final int DISK_SELECTED_TINT = 0xCCFFD54F;

    // 「显示槽位 / 隐藏槽位」按钮的图标：states.png (64,32) 四格 = 展开、扫(80,32) 单格 = 收起（就在「标准/极速」
    // 图标右侧、同一行，同一套 16×16 规格与配色）。按钮背景同本模组其他自绘按钮。
    private static final ResourceLocation STATES = ResourceLocation
            .parse("ae2_pattern_disk:textures/guis/states.png");
    private static final Blitter ICON_SHOW_SLOTS = Blitter.texture(STATES).src(64, 32, 16, 16);
    private static final Blitter ICON_HIDE_SLOTS = Blitter.texture(STATES).src(80, 32, 16, 16);
    private static final Blitter BUTTON_BG_NORMAL = Blitter.texture(STATES).src(208, 224, 18, 20);
    private static final Blitter BUTTON_BG_HOVER = Blitter.texture(STATES).src(226, 224, 18, 20);

    private sealed interface Row permits HostRow, DiskRow, FreeSlotsRow {
    }

    /** 组头行：一台（或几台同名合并的）宿主机器。{@code diskCount} 是当前过滤/显示口径下的磁盘数。 */
    private record HostRow(String groupName, String name, ItemStack icon, int diskCount) implements Row {
    }

    /**
     * 磁盘行。{@code from == 0} 是首行：第 0 格是磁盘本身、后面 16 格是它里面的样板；{@code from > 0} 是续行：
     * 17 格全是样板，{@code from} 是这一行第 0 格对应的样板序号（续行从第一格开始接）。
     */
    private record DiskRow(String groupName, long serial, ItemStack disk, int from) implements Row {
    }

    /**
     * 供应器剩余的一个空槽，一行一格、竖着排在第一列。{@code foldedCount} &gt; 0 时这一行代表整组的全部空槽，
     * 数字写在格的右上角。
     */
    private record FreeSlotsRow(String groupName, int foldedCount) implements Row {
    }

    private final List<Row> rows = new ArrayList<>();
    private final LongSet requestedContents = new LongOpenHashSet();

    private int scrollOffset;
    private int contentRefreshCooldown = CONTENT_REFRESH_INTERVAL_TICKS;

    public PatternDiskManagementTermScreen(PatternDiskManagementTermMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        // 「显示模式」与 AE2 样板访问终端共用同一个服务端设置：同一套取值、同一份持久化。按钮的图标与提示由
        // AE2 的 SettingToggleButton 静态注册表提供，本模组不需要自带资源。
        this.showProvidersButton = new ServerSettingToggleButton<>(
                Settings.TERMINAL_SHOW_PATTERN_PROVIDERS, ShowPatternProviders.VISIBLE);
        addToLeftToolbar(this.showProvidersButton);

        // 「隐藏槽位」只改客户端的行模型（空槽数由服务端在分组里带出），所以是本屏自己的开关。状态由本屏维护
        // 并回写，不依赖按钮自身的翻转时机——按钮只负责把“按了一下”告诉监听器，图标由 setState 决定。
        // 状态为 true（收起）时画单格图标，为 false（展开）时画四格图标，与「标准/极速」的排列一致。
        this.hideSlotsButton = new StatesToggleButton(ICON_HIDE_SLOTS, ICON_SHOW_SLOTS, state -> {
            this.hideEmptySlots = !this.hideEmptySlots;
            this.hideSlotsButton.setState(this.hideEmptySlots);
        });
        this.hideSlotsButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.hideSlotsButton.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.management_terminal.hide_slots"),
                Component.translatable("gui.ae2_pattern_disk.management_terminal.hide_slots_hint")));
        this.hideSlotsButton.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.management_terminal.show_slots"),
                Component.translatable("gui.ae2_pattern_disk.management_terminal.show_slots_hint")));
        this.hideSlotsButton.setState(this.hideEmptySlots);
        addToLeftToolbar(this.hideSlotsButton);

        // 滚动条：给表格自己接一条（样式 JSON 的 widgets.tableScrollbar 给了落点，高度每帧按行数设）。
        // 必须换一个 id：父类在自己的构造器里已经用 "scrollbar" 那一个 id 注册过物品网格的滚动条，
        // 同一个 id 再注册一次会直接抛 IllegalStateException（界面开不出来）。
        this.tableScrollbar = widgets.addScrollBar("tableScrollbar", Scrollbar.BIG);
        // 关掉物品网格那枚的滚轮捕获：它的范围会被父类在开屏/缩放/外部搜索时重新喂（不经过 repo），
        // 所以只换 repo 的监听器不够；它排在 widget 表更前、又默认 captureMouseWheel，一旦有 range 就会
        // 把整屏滚轮吃掉（而它 park 在屏外，玩家看不到任何反应）。
        var gridScrollbar = parentScrollbar(this);
        if (gridScrollbar != null) {
            gridScrollbar.setCaptureMouseWheel(false);
        }
    }

    /**
     * 取父类那枚物品网格滚动条（字段 private：AE2 没给读法，ModAccessor 又只在运行时改名、
     * 编译期看不见，所以只能反射）。取不到就作罢：最坏是表格滚轮不灵，不该因此把界面弄坏。
     */
    @Nullable
    private static Scrollbar parentScrollbar(MEStorageScreen<?> screen) {
        try {
            var field = MEStorageScreen.class.getDeclaredField("scrollbar");
            field.setAccessible(true);
            return field.get(screen) instanceof Scrollbar bar ? bar : null;
        } catch (Throwable e) {
            LOGGER.debug("Could not reach AE2's grid scrollbar; table scrolling may ignore the wheel", e);
            return null;
        }
    }

    @Override
    protected boolean usesDiskListPanel() {
        return false;
    }

    @Override
    protected boolean usesNeoEcoUploadButton() {
        // 管理终端走标记路线，不挂 NEO ECO 的上传按钮（EAE+ 那条本来就不在管理类型下）。
        return false;
    }

    @Override
    public void init() {
        super.init();

        // 行数由父类按 style 的 terminalStyle 算好（imageHeight 就是它的产物：表头 + 行×18 + 尾饰），这里反推
        // 回来用，保证行带与父类摆好的槽位/控件用的是同一个值——自己再算一遍公式只会引入偏差。
        this.visibleRows = Math.max(1, (imageHeight - HEADER_HEIGHT - FOOTER_HEIGHT) / ROW_HEIGHT);

        // MEStorageScreen.init() 给终端网格加了 RepoSlot；我们用自定义表格，不需要它们。
        this.menu.slots.removeIf(slot -> slot instanceof RepoSlot);
        // 父类把初始焦点给了 ME 搜索框，但本屏不显示物品网格，那个框在 JSON 里被移出面板——让它拿着焦点
        // 等于把玩家的按键送进一个看不见的输入框。所以清掉初始焦点：开局谁也不选中，要用搜索框自己点。
        //
        // 用 setFocused(null) 而不是 setInitialFocus(null)：后者的实现要先拿参数解引用去算焦点路径，传 null
        // 直接 NPE，而且抛在 init() 里会连带把整个开屏打断（NeoForge 报 "Failed to handle advanced open
        // screen from server"，客户端被断开）。setFocused 才是 null 安全的那个。
        setFocused(null);
        hideIrrelevantToolbarButtons();
        // 本模组自己的按钮排到 AE2 自带的之后，次序：显示模式 → 显示槽位 → 模式轮换
        //（附加排序已在父类里贴到了「排序按」后面）。
        ToolbarOrder.placeAtEnd(this, List.of(showProvidersButton, hideSlotsButton, modeCycleButton));

        // 风格档位可能把面板改矮：清单没变时 rebuildRows 不会夹偏移，这里补一次，免得顶部留白。
        // （前提：JSON 的 header=17、firstRow/lastRow=18、bottom=95，即 imageHeight = 18×行 + 112；改那几处要同步这里。）
        clampScroll();
    }

    /**
     * 隐藏 AE2 标准终端工具栏里对本屏无意义的按钮：「终端设置」（它的设置页全是物品网格的项）。排序按钮
     * 现在留着了——本表的行内样板顺序就按它的档位排（见 {@link #displayOrder(long)}）。
     *
     * <p>AE2 对这两枚都是无条件添加：字段 private、按钮条（{@code VerticalButtonBar}）只有 add 没有移除接口、
     * 也没有可覆写的开关，所以在 super.init() 之后按控件身份精确匹配再关掉——排序顺序读
     * {@link SettingToggleButton#getSetting()}，终端设置比对它自己的 tooltip 常量
     * （{@link ButtonToolTips#TerminalSettings}，AE2 自带的语言键）。本屏自己的「编码」「清空」按钮虽然也是
     * {@link ActionButton}，但 tooltip 是 Encode / ClearSettings，不会被误伤。</p>
     *
     * <p>{@code setVisibility(false)} 同时关掉 visible 与 active，TAB 焦点路径也取不到它；按钮条只排布可见
     * 按钮，隐藏后不留空位。</p>
     */
    private void hideIrrelevantToolbarButtons() {
        // 比字符串而非 Component：按钮的消息是已解析的字面文本，语言键形式的 Component 永远不等于它。
        // 两边都走同一份语言文件，中英任何一种语言下都成立。
        var terminalSettings = ButtonToolTips.TerminalSettings.text().getString();
        for (var listener : this.children()) {
            if (!(listener instanceof IconButton button)) {
                continue;
            }
            boolean isTerminalSettings = button.getTooltipMessage().stream()
                    .anyMatch(line -> line.getString().contains(terminalSettings));
            if (isTerminalSettings) {
                button.setVisibility(false);
            }
        }
    }

    // ---- 行模型 ----

    /**
     * 依据「父类过滤后的磁盘清单」与「服务端的分组清单」重塑行模型。
     *
     * <p>骨架是**宿主**而不是磁盘：一台支持样板磁盘的机器哪怕一张盘都没插也占一组（能看出它有几个空
     * 槽），这正是这张表与「只列磁盘」的区别。盘仍旧取父类那份过滤后的列表，所以搜索框筛掉谁，表里就
     * 没有谁；搜索生效时没有命中磁盘的组不出现（否则一搜就满屏空机器），没搜索时所有宿主都在。</p>
     */
    private void rebuildRows() {
        var menu = getMenu();

        // 父类过滤后的磁盘（搜索口径），按 serial 索引。
        var visibleDisks = new HashMap<Long, DiskListPanel.DiskEntry>();
        for (int i = 0; super.diskEntryAt(i) != null; i++) {
            var entry = super.diskEntryAt(i);
            visibleDisks.put(entry.serial(), entry);
        }

        var serialToPatternCount = new HashMap<Long, Integer>();
        for (var group : menu.getHostList()) {
            for (var disk : group.disks()) {
                serialToPatternCount.put(disk.serial(), disk.patternCount());
            }
        }

        boolean searched = isDiskSearchActive();

        // 盘内顺序缓存跟着当下的盘集合走：被取走、被搜索筛掉的盘不再留条目（连同它那份旧 contents 列表）。
        displayOrders.keySet().retainAll(visibleDisks.keySet());

        var rebuilt = new ArrayList<Row>();
        // 机器列表默认按显示名排（大小写不敏感的字符串序；中文名走 Unicode 码点序、不是拼音序——项目里
        // 唯一的拼音能力只有搜索用的 JECH 匹配，取不到拼音串）。同名机器的相对次序沿用服务端原序
        //（同名本就合并成一行，这里排的是不同机器之间）。
        var hosts = new ArrayList<>(menu.getHostList());
        hosts.sort(Comparator.comparing(host -> host.name() == null ? "" : host.name(),
                String.CASE_INSENSITIVE_ORDER));
        for (var group : hosts) {
            var groupDisks = new ArrayList<DiskListPanel.DiskEntry>();
            for (var disk : group.disks()) {
                var visible = visibleDisks.get(disk.serial());
                if (visible != null) {
                    groupDisks.add(visible);
                }
            }
            if (searched && groupDisks.isEmpty()) {
                continue;
            }

            rebuilt.add(new HostRow(group.key(), group.name(), group.icon(), groupDisks.size()));
            for (var disk : groupDisks) {
                rebuilt.add(new DiskRow(group.key(), disk.serial(), disk.stack(), 0));
                // 一张盘的内容超过一行时往下续行：首行第 0 格占给了磁盘，续行没有磁盘格，17 格全放内容。
                for (int from = COLUMNS - 1; from < serialToPatternCount.getOrDefault(disk.serial(), 0); from += COLUMNS) {
                    rebuilt.add(new DiskRow(group.key(), disk.serial(), ItemStack.EMPTY, from));
                }
            }
            appendFreeSlots(rebuilt, group.key(), group.emptySlots());
        }

        if (!rows.equals(rebuilt)) {
            rows.clear();
            rows.addAll(rebuilt);
            clampScroll();
        }
        requestVisibleContents(false);
    }

    /** 搜索框里有没有内容；有内容时没命中磁盘的组不进表（否则一搜就满屏空机器）。 */
    // isDiskSearchActive() 来自基类：编码终端的自动写盘也按同一判据。

    /**
     * 给刚数完的那一组补「剩余槽位」行。
     *
     * <p>空槽数直接取服务端在分组里报的「真正的空格数」（同名几台是它们的和）：搜索框筛掉部分盘、主机行开关隐藏
     * 整组都不会让它变化，槽位与别的物品共用（NEO ECO 把样板盘与已编码样板放在同一批槽里）也不会被算错。</p>
     *
     * <p>收起时整组只留一行，行上写它代表多少空槽；展开时每个空槽一行，竖着排在第一列。</p>
     */
    private void appendFreeSlots(List<Row> out, String groupName, int empty) {
        if (empty <= 0 || groupName == null || groupName.isEmpty()) {
            return;
        }

        if (hideEmptySlots) {
            out.add(new FreeSlotsRow(groupName, empty));
            return;
        }
        // 展开：一格一行，竖着排在第一列——空槽不是“盘里的内容”，不铺满整行。
        for (int i = 0; i < empty; i++) {
            out.add(new FreeSlotsRow(groupName, 0));
        }
    }

    private void clampScroll() {
        int max = Math.max(0, rows.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
    }

    /**
     * 写盘顺位候选：选中的那张盘排在最前，其后是同一容器（表格里的同一组）内的其他已插入磁盘；
     * 没有选中盘时退回与编码终端相同的口径——搜索栏有内容才按列表顺序顺位，否则不给目标。
     */
    private long[] buildAutoDiskCandidates() {
        var out = new ArrayList<Long>();
        if (selectedSerial != 0) {
            out.add(selectedSerial);
            var groupKey = groupKeyOf(selectedSerial);
            if (groupKey != null) {
                for (var group : getMenu().getHostList()) {
                    if (!groupKey.equals(group.key())) {
                        continue;
                    }
                    for (var disk : group.disks()) {
                        if (disk.serial() != selectedSerial) {
                            out.add(disk.serial());
                        }
                    }
                }
            }
        } else if (isDiskSearchActive()) {
            // 没选中盘时按表格行序顺位（与玩家看到的顺序一致）。
            for (var row : rows) {
                if (row instanceof DiskRow disk && disk.from() == 0) {
                    out.add(disk.serial());
                }
            }
        }
        var serials = new long[out.size()];
        for (int i = 0; i < serials.length; i++) {
            serials[i] = out.get(i);
        }
        return capCandidates(serials);
    }

    /** 这张盘属于哪一组（同一键即为同一容器，与表格的分组口径一致）。 */
    private String groupKeyOf(long serial) {
        for (var group : getMenu().getHostList()) {
            for (var disk : group.disks()) {
                if (disk.serial() == serial) {
                    return group.key();
                }
            }
        }
        return null;
    }

    /**
     * 上报当前视口（多要一行余量）里的磁盘序列号。
     *
     * <p>只在集合真的变了才发包：滚动一屏发一次，而不是每帧一次。</p>
     */
    private void requestVisibleContents(boolean force) {
        var wanted = new LongOpenHashSet();
        int first = Math.max(0, scrollOffset - CONTENT_MARGIN_ROWS);
        int last = Math.min(rows.size(), scrollOffset + visibleRows + CONTENT_MARGIN_ROWS);
        for (int i = first; i < last; i++) {
            if (rows.get(i) instanceof DiskRow disk) {
                wanted.add(disk.serial());
            }
        }
        if (!force && wanted.equals(requestedContents)) {
            return;
        }
        requestedContents.clear();
        requestedContents.addAll(wanted);
        if (!wanted.isEmpty()) {
            PacketDistributor.sendToServer(new VisibleDisksPayload(List.copyOf(wanted)));
        }
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        // 显示模式按钮的档位回显：换档后服务端会重推分组清单，档位跟着清单回来。
        this.showProvidersButton.set(getMenu().shownProviders());
        syncScrollbar();
        rebuildRows();
        syncScrollbar();
        if (--contentRefreshCooldown <= 0) {
            contentRefreshCooldown = CONTENT_REFRESH_INTERVAL_TICKS;
            requestVisibleContents(true);
        }
        // 右键选中的那张盘被取走、或搜索把它筛出去了，选择跟着失效。
        if (selectedSerial != 0 && !containsDisk(selectedSerial)) {
            selectedSerial = 0;
        }
        // 写盘目标：选中的那张盘优先，写不进时顺位到同一容器内的其他盘（候选顺序就是意图顺序）。
        getMenu().setClientAutoDisks(buildAutoDiskCandidates());
    }

    // ---- 绘制 ----

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        // 不调 super.drawBG：那张底图是一整块固定高度的贴图，而本表的面板高随终端风格档位变化，所以这里自己拼
        // 面板：表头带 + 可见行带 + 尾饰带（行数见 visibleRows）。跳过它的代价是 AE2 物品网格的 pinned 行覆盖层
        // 与那次手写 searchField.render——本屏不显示那个网格，而搜索框仍由 widget 容器正常渲染。
        blit(HEADER_BAND, guiGraphics, offsetX, offsetY);

        int y = offsetY + HEADER_HEIGHT;

        // 行分配同 AE2：每行先铺「文本带」作底，含物品格的行再叠「物品带」；带是整条的（含左右边框）。
        for (int i = 0; i < visibleRows; i++) {
            boolean firstLine = i == 0;
            boolean lastLine = i == visibleRows - 1;
            int rowY = y + i * ROW_HEIGHT;
            boolean slotsRow = rowKindAt(scrollOffset + i) == RowKind.SLOTS;

            blit(selectRowBand(false, firstLine, lastLine), guiGraphics, offsetX, rowY);
            if (slotsRow) {
                blit(selectRowBand(true, firstLine, lastLine), guiGraphics, offsetX, rowY);
            }
            // 磁盘首格再压一层浅绿，位置与 drawFG 的磁盘图标同一点（续行的第 0 格是样板格，不上色）。
            if (scrollOffset + i < rows.size() && rows.get(scrollOffset + i) instanceof DiskRow diskRow
                    && diskRow.from() == 0) {
                int cellX = offsetX + LIST_X + 1;
                guiGraphics.fill(cellX, rowY + CELL_Y_INSET, cellX + 16, rowY + CELL_Y_INSET + 16, DISK_SLOT_TINT);
            }
            // 选中的那张盘：首格格框描一圈高亮。
            if (scrollOffset + i < rows.size() && rows.get(scrollOffset + i) instanceof DiskRow selected
                    && selected.from() == 0 && selected.serial() == selectedSerial) {
                outlineCell(guiGraphics, offsetX + LIST_X, rowY);
            }
        }

        blit(FOOTER_BAND, guiGraphics, offsetX, offsetY + HEADER_HEIGHT + visibleRows * ROW_HEIGHT);
    }

    /** 一行在贴图上该用哪条带：纯文本行（主机名）用文本带，含物品格的行用物品带。 */
    private enum RowKind { TEXT, SLOTS }

    private RowKind rowKindAt(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return RowKind.TEXT;
        }
        return rows.get(rowIndex) instanceof HostRow ? RowKind.TEXT : RowKind.SLOTS;
    }

    /**
     * 行带的选择与 AE2 的 {@code PatternAccessTermScreen#selectRowBackgroundBox} 同口径：可见窗口的首行取 TOP、
     * 末行取 BOTTOM、其余取 MIDDLE。
     */
    private static Blitter selectRowBand(boolean inventoryLine, boolean firstLine, boolean lastLine) {
        if (inventoryLine) {
            return firstLine ? ROW_INVENTORY_TOP : lastLine ? ROW_INVENTORY_BOTTOM : ROW_INVENTORY_MIDDLE;
        }
        return firstLine ? ROW_TEXT_TOP : lastLine ? ROW_TEXT_BOTTOM : ROW_TEXT_MIDDLE;
    }

    private static void blit(Blitter blitter, GuiGraphics guiGraphics, int destX, int destY) {
        blitter.dest(destX, destY).blit(guiGraphics);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);

        // renderLabels 的 pose 已由 vanilla 平移到 (leftPos, topPos)，这里必须用裸局部坐标：
        // 再加 offsetX/offsetY 会把整个 GUI 原点算第二遍，表格内容整体右下偏移一格到数格（AE2 自家
        // drawFG 同样用裸坐标，如 VibrationChamberScreen 的 dest(80, 20 + ...)）。
        // 注意传入的 mouseX/mouseY 是绝对屏幕坐标（命中测试因此要减 leftPos/topPos，本类已如此）。
        int baseX = LIST_X;
        int baseY = LIST_Y + HEADER_HEIGHT;
        int textColor = 0xFF404040;

        for (int i = 0; i < visibleRows; i++) {
            int rowIndex = scrollOffset + i;
            if (rowIndex >= rows.size()) {
                break;
            }

            int rowY = baseY + i * ROW_HEIGHT;
            switch (rows.get(rowIndex)) {
                case HostRow host -> {
                    if (!host.icon().isEmpty()) {
                        guiGraphics.renderItem(host.icon(), baseX + 1, rowY + ROW_TEXT_Y_INSET);
                    }
                    var label = host.diskCount() > 1
                            ? host.name() + " (" + host.diskCount() + ")"
                            : host.name();
                    guiGraphics.drawString(font, font.plainSubstrByWidth(label, 16 * 18 - 22),
                            baseX + 21, rowY + ROW_TEXT_Y_INSET + 4, textColor, false);
                }
                case DiskRow disk -> drawDiskRow(guiGraphics, baseX, rowY, disk);
                case FreeSlotsRow free -> drawFreeSlotsRow(guiGraphics, baseX, rowY, free);
            }
        }
    }

    /**
     * 磁盘行：首行的第 0 格是磁盘、后面 16 格是盘内样板；续行没有磁盘格，17 格全是样板（内容到达前只画磁盘）。
     */
    private void drawDiskRow(GuiGraphics guiGraphics, int baseX, int rowY, DiskRow row) {
        int firstColumn = row.from() == 0 ? 1 : 0;
        if (firstColumn == 1) {
            guiGraphics.renderItem(row.disk(), baseX + 1, rowY + CELL_Y_INSET);
            guiGraphics.renderItemDecorations(font, row.disk(), baseX + 1, rowY + CELL_Y_INSET);
        }

        var patterns = getMenu().getDiskContents(row.serial());
        if (patterns == null) {
            return;
        }
        var order = displayOrder(row.serial());

        // 这一行第 0 格对应的样板位次：首行被磁盘占了第 0 格，所以减 1。
        int patternBase = row.from() - firstColumn;
        var level = Minecraft.getInstance().level;
        for (int column = firstColumn; column < COLUMNS; column++) {
            int position = patternBase + column;
            if (position < 0 || position >= order.length) {
                break;
            }
            var pattern = patterns.get(order[position]);
            if (pattern.isEmpty()) {
                continue;
            }

            int cellX = baseX + column * 18 + 1;
            int cellY = rowY + CELL_Y_INSET;

            // 显示主产物，而不是样板本体：与 AE2 样板访问终端同口径（它的 PatternSlot.getDisplayStack 用
            // EncodedPatternItem#getOutput 换掉槽位显示）。任何类型的产物都直接显示——那个方法对流体等
            // 非物品产出会包一层伪物品；取不到时回退到样板本体。玩家因此不必按住 Shift 才知道样板做什么。
            var icon = displayedItem.apply(pattern);
            guiGraphics.renderItem(icon, cellX, cellY);
            guiGraphics.renderItemDecorations(font, icon, cellX, cellY);

            // 解不出来的样板标红：与样板访问终端同一个提示口径，一眼看出哪张盘里有坏样板。
            if (level != null && appeng.api.crafting.PatternDetailsHelper.decodePattern(pattern, level) == null) {
                guiGraphics.fill(cellX, cellY, cellX + 16, cellY + 16, 0x7fff0000);
            }
        }
    }

    /**
     * 剩余槽位行：一行一格，把空槽画成空格；收起时那一行代表整台机器的全部空槽，数字写在格的右上角。
     */
    private void drawFreeSlotsRow(GuiGraphics guiGraphics, int baseX, int rowY, FreeSlotsRow row) {
        // BLANK_CELL 是 18×18 的槽框（自带 1px 边框），落点与行带本身的格框同格位——框对框，它内部就自然落在
        // +1，与同排物品格的内容线一致（物品画在 +CELL_Y_INSET）。
        blit(BLANK_CELL, guiGraphics, baseX, rowY);

        if (row.foldedCount() <= 0) {
            return;
        }
        var count = Integer.toString(row.foldedCount());
        // 右上角：与那格右/上边线各留 1px（格内从框 +1 起）。
        guiGraphics.drawString(font, count, baseX + 1 + 16 - font.width(count), rowY + 2, 0xFF3F3F3F, false);
    }

    /**
     * 样板在终端里该显示的主产物；不是 AE2 样板物品、或取不到主产物时返回空堆。
     *
     * <p>直接用 AE2 的 {@code EncodedPatternItem#getOutput}：它对非物品产出（流体等）会包一层伪物品，
     * 所以任何类型的产物都能直接显示；它也自带缓存，逐帧调用不会反复解码。该方法在“解出的样板报告零产出”
     * 时会在内部越界，而渲染路径不能因此炸掉整帧，所以在边界收口一次。</p>
     */
    private static ItemStack patternOutputOf(ItemStack pattern) {
        if (!(pattern.getItem() instanceof appeng.crafting.pattern.EncodedPatternItem encodedPattern)) {
            return ItemStack.EMPTY;
        }
        try {
            return encodedPattern.getOutput(pattern);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    // ---- 交互 ----

    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        int rowIndex = rowIndexAt(xCoord, yCoord);
        if (rowIndex < 0) {
            return super.mouseClicked(xCoord, yCoord, btn);
        }

        var row = rows.get(rowIndex);
        if (row instanceof HostRow) {
            // 组头行只是一条分隔 + 标签，没有可点的东西：吃掉这一下，别漏给底下的控件。
            return true;
        }

        // 空磁盘槽格：左键＝把光标上那张放进去（服务端挑第一个空槽）。
        // 「把背包里的盘存进某台容器」不挂在这里，而是 Shift+左键背包里的那张盘（见 slotClicked）。
        if (row instanceof FreeSlotsRow free) {
            if (btn == 0 && !hasShiftDown() && holdingDisk()) {
                getMenu().insertDisk(new PatternDiskManagementTermMenu.InsertDiskRequest(free.groupName()));
            }
            return true;
        }

        // 磁盘格只在首行存在：续行的第 0 格是样板，不该把选中/标记/改名这些磁盘动作落到它头上。
        if (row instanceof DiskRow disk && disk.from() == 0 && columnAt(xCoord) == 0) {
            int index = indexOfDisk(disk.serial());
            // 列表口径与父类不一致时（例如磁盘刚被换走）不动作，也不把这一下漏给底下的控件。
            if (index < 0) {
                return true;
            }
            if (btn == 0 && hasShiftDown()) {
                getMenu().extractDisk(disk.serial(), PatternDiskEncodingTermMenu.ExtractTarget.INVENTORY);
            } else if (btn == 0) {
                getMenu().extractDisk(disk.serial(), PatternDiskEncodingTermMenu.ExtractTarget.CURSOR);
            } else if (btn == 1 && hasShiftDown()) {
                onDiskShiftRightClick(index);
            } else if (btn == 1 && isHoldingWorkBlock()) {
                // 手里拿着工作方块右键仍是既有的「以该方块打标」；空手或拿着别的东西才轮到选中。
                onDiskRightClick(index);
            } else if (btn == 1) {
                this.selectedSerial = this.selectedSerial == disk.serial() ? 0 : disk.serial();
            } else if (btn == 2) {
                onDiskMiddleClick(index);
            }
            return true;
        }

        // 样板格（含续行整行）：左键取到光标、Shift+左键取到背包、右键填进样板编辑槽。
        if (row instanceof DiskRow disk) {
            int pattern = patternIndexAt(rowIndexAt(xCoord, yCoord), columnAt(xCoord));
            if (pattern >= 0) {
                var target = btn == 1
                        ? PatternDiskEncodingTermMenu.ExtractTarget.ENCODED_SLOT
                        : (hasShiftDown() ? PatternDiskEncodingTermMenu.ExtractTarget.INVENTORY
                                : PatternDiskEncodingTermMenu.ExtractTarget.CURSOR);
                if (btn == 0 || btn == 1) {
                    getMenu().extractPattern(disk.serial(), pattern, target);
                }
                return true;
            }
        }

        return true;
    }

    /** 光标上是不是拿着一块样板磁盘（只影响要不要发动作，真伪由服务端再判一次）。 */
    private boolean holdingDisk() {
        return getMenu().getCarried().getItem() instanceof PatternDiskItem;
    }

    /**
     * Shift+左键**背包里**的样板磁盘：把它存进「右键选中那张盘」所在的容器。
     *
     * <p>拦在 {@code super} 之前：这一下原本会走普通的快捷移动——本屏已经把进网络那条堵了，而 AE2 还有一条
     * “没目标槽位就塞进空 FakeSlot”的回退，会往合成格里放一份不消耗原物的鬼影；拦下来既避免了那一下，
     * 也把这条手势拿来做正事。目标容器不取鼠标下的位置——此刻鼠标在背包上——而取选中的那张盘：
     * 这条手势的意思就是「跟它放一起」。</p>
     */
    @Override
    protected void slotClicked(@Nullable Slot slot, int slotIdx, int mouseButton, ClickType clickType) {
        if (clickType == ClickType.QUICK_MOVE && slot != null && !(slot instanceof DisabledSlot)
                && slot.getItem().getItem() instanceof PatternDiskItem && isPlayerSideSlot(slot)) {
            if (this.selectedSerial == 0) {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(Component.translatable(
                            "gui.ae2_pattern_disk.management_terminal.disk_store.select_first"), false);
                }
                return;
            }
            getMenu().storeInventoryDisk(new PatternDiskManagementTermMenu.StoreInventoryDiskRequest(
                    this.selectedSerial, slot.getContainerSlot()));
            return;
        }
        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    /** 这个槽位是不是玩家背包那批（背包容量的槽号在服务端才用得着）。 */
    private boolean isPlayerSideSlot(Slot slot) {
        var semantic = getMenu().getSlotSemantic(slot);
        return semantic == SlotSemantics.PLAYER_INVENTORY || semantic == SlotSemantics.PLAYER_HOTBAR;
    }

    /**
     * 把行数告诉滚动条，再把滚动条的位置抄回 {@code scrollOffset}。
     *
     * <p>滚动状态只有滚动条一份：滚轮、拖动滑块、上下翻页都改它（{@code wantsAllMouseWheelEvents}
     * 让它接管整屏滚轮，所以本屏不再自己处理 wheel）；行数变少时这里把越界的位置夹回来。</p>
     */
    private void syncScrollbar() {
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        this.tableScrollbar.setHeight(Math.max(1, visibleRows * ROW_HEIGHT - 2));
        this.tableScrollbar.setRange(0, maxScroll, Math.max(1, visibleRows / 6));
        this.scrollOffset = this.tableScrollbar.getCurrentScroll();
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
            this.tableScrollbar.setCurrentScroll(maxScroll);
        }
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int x, int y) {
        int rowIndex = rowIndexAt(x, y);
        if (rowIndex >= 0) {
            var row = rows.get(rowIndex);
            // 空磁盘槽格：两条存入手势写在这里（这格本来什么都不做，提示与手势一一对应）。
            if (row instanceof FreeSlotsRow) {
                guiGraphics.renderComponentTooltip(font, List.of(
                        Component.translatable("gui.ae2_pattern_disk.management_terminal.tooltip.free_slot.click")
                                .withStyle(ChatFormatting.WHITE),
                        Component.translatable("gui.ae2_pattern_disk.management_terminal.tooltip.free_slot.inventory")
                                .withStyle(ChatFormatting.GRAY)),
                        x, y);
                return;
            }
            // 磁盘格（首行第 0 格）：给与编码终端磁盘列表同一套信息（容量、标记、手势），而不是只报物品名。
            if (row instanceof DiskRow disk && disk.from() == 0 && columnAt(x) == 0) {
                renderDiskTooltip(guiGraphics, disk, x, y);
                return;
            }
            var stack = itemAt(rowIndex, columnAt(x));
            if (stack != null && !stack.isEmpty()) {
                guiGraphics.renderTooltip(font, stack, x, y);
                return;
            }
        }
        super.renderTooltip(guiGraphics, x, y);
    }

    /**
     * 磁盘格的信息：名字、已用/容量、标记（原版高级信息下多一行原始 id），以及这一格上的四种手势。
     *
     * <p>与编码终端磁盘列表用同一套用词与语言键（它们说的是同一张盘），只把手势换成管理终端自己的。
     */
    private void renderDiskTooltip(GuiGraphics guiGraphics, DiskRow row, int x, int y) {
        var stack = row.disk();
        var lines = new ArrayList<Component>();
        lines.add(Component.literal(stack.getHoverName().getString()));

        if (stack.getItem() instanceof PatternDiskItem disk) {
            var contents = disk.contents(stack);
            lines.add(Component.translatable("ae2_pattern_disk.tooltip.capacity", contents.used(), contents.capacity()));
        }

        var mark = PatternDiskMarks.displayName(stack);
        if (mark != null) {
            lines.add(Component.translatable("ae2_pattern_disk.tooltip.mark", mark));
            if (Minecraft.getInstance().options.advancedItemTooltips) {
                var raw = PatternDiskMarks.rawMark(stack);
                if (raw != null) {
                    lines.add(Component.translatable("ae2_pattern_disk.tooltip.mark.raw", raw)
                            .withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }

        lines.add(Component.translatable("gui.ae2_pattern_disk.management_terminal.tooltip.disk.click")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gui.ae2_pattern_disk.management_terminal.tooltip.disk.shift_click")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gui.ae2_pattern_disk.management_terminal.tooltip.disk.right_click")
                .withStyle(ChatFormatting.GRAY));
        // Shift+右键 这条直接复用编码终端磁盘列表那句话：两个终端上它是同一件事（拿搜索框里的内容打标）。
        lines.add(Component.translatable("ae2_pattern_disk.tooltip.disk.shift_right_click")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("gui.ae2_pattern_disk.management_terminal.tooltip.disk.middle_click")
                .withStyle(ChatFormatting.GRAY));

        guiGraphics.renderComponentTooltip(font, lines, x, y);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    // ---- 命中测试（面板内坐标 → 行列） ----

    private int rowIndexAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - leftPos - LIST_X;
        int relY = (int) mouseY - topPos - LIST_Y - HEADER_HEIGHT;
        if (relX < 0 || relX >= LIST_WIDTH || relY < 0) {
            return -1;
        }
        int slotRow = relY / ROW_HEIGHT;
        if (slotRow < 0 || slotRow >= visibleRows) {
            return -1;
        }
        int rowIndex = scrollOffset + slotRow;
        return rowIndex < rows.size() ? rowIndex : -1;
    }

    private int columnAt(double mouseX) {
        return ((int) mouseX - leftPos - LIST_X) / 18;
    }

    @Nullable
    private ItemStack itemAt(int rowIndex, int column) {
        if (rowIndex < 0 || rowIndex >= rows.size() || column < 0 || column >= COLUMNS) {
            return null;
        }
        if (rows.get(rowIndex) instanceof DiskRow disk) {
            int index = patternIndexAt(rowIndex, column);
            if (index >= 0) {
                return getMenu().getDiskContents(disk.serial()).get(index);
            }
            if (disk.from() == 0 && column == 0) {
                return disk.disk();
            }
        }
        return null;
    }

    /**
     * 把光标下的那一格交给切片浏览器（JEI / EMI / REI）。表格是自绘的，真实槽位在 {@code init()} 里被移除，
     * 所以父类的默认实现（只认 vanilla 槽）在这一屏问不出任何东西。这是 AE2 为「不在普通槽里的 ingredient」
     * 留的钩子（见 {@code AEBaseScreen#getStackUnderMouse} 的注释），AE2 自己的自绘表格也这么做（样板访问
     * 终端、合成 CPU 的 {@code CraftingCPUScreen}）。
     *
     * <p>交出的是格子上画着的那个东西：样板的主产物（与 {@code drawDiskRow} 的图标同一个来源，产物不是物品
     * 时是它自带的伪物品包装，{@code GenericStack.fromItemStack} 会拆开）。R/U 因此交给浏览器自己响应——
     * 本项目不注册键位、也不自己判断按了什么键。</p>
     *
     * <p>只认样板格（磁盘格、空格、组头行不在此列），解不出主产物的坏样板不交（那一格按 R/U 安静地无事发生，
     * 与格子上标红的提示一致）；背包与快捷栏那些真实槽仍走父类。</p>
     *
     * <p>两条前提：JEI 那一侧要装 AE2 的 JEI 集成模组（AE2 19.2.x 本体不带 JEI 支持，本屏的 JEI 转发由那个
     * 模组提供；EMI 与 REI 由 AE2 自带模块转发）。另外，格子 tooltip 说的是样板本体（含输入清单），而这里的
     * R/U 查的是它的主产物——两者口径不同是有意的。</p>
     */
    @Nullable
    @Override
    public StackWithBounds getStackUnderMouse(double mouseX, double mouseY) {
        int rowIndex = rowIndexAt(mouseX, mouseY);
        int column = columnAt(mouseX);
        if (rowIndex >= 0 && column >= 0 && column < COLUMNS && patternIndexAt(rowIndex, column) >= 0) {
            var pattern = itemAt(rowIndex, column);
            var stack = GenericStack.fromItemStack(pattern == null ? ItemStack.EMPTY : patternOutputOf(pattern));
            if (stack != null) {
                // 非物品产出（流体等）是经伪物品包装过来的，里面带的量是 0，而浏览器侧只有 JEI 会把 0 夹到 1。
                // 这里统一补到 1，两家口径一致，也免得键匹配不上。
                if (stack.amount() <= 0) {
                    stack = new GenericStack(stack.what(), 1);
                }
                // 坐标与 drawDiskRow 逐字一致（baseX = LIST_X、rowY = LIST_Y + HEADER_HEIGHT + 可见行号
                // × ROW_HEIGHT、格内再各 +1），只是把局部坐标换成绝对屏幕坐标；矩形口径与 AE2 的
                // StackWithBounds.fromSlot 相同（一格内容 16×16）。
                int cellX = leftPos + LIST_X + column * 18 + 1;
                int cellY = topPos + LIST_Y + HEADER_HEIGHT + (rowIndex - scrollOffset) * ROW_HEIGHT + CELL_Y_INSET;
                return new StackWithBounds(stack, new Rect2i(cellX, cellY, 16, 16));
            }
        }
        return super.getStackUnderMouse(mouseX, mouseY);
    }

    /** 表里（当前过滤/显示口径下）有没有这张盘。 */
    private boolean containsDisk(long serial) {
        for (var row : rows) {
            if (row instanceof DiskRow disk && disk.from() == 0 && disk.serial() == serial) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命中位置对应的样板序号（盘内内容的平坦序号）；不在样板格上返回 -1。
     *
     * <p>空槽、越界、不在磁盘行上一并算作 -1：取件、tooltip 与点击因此共用同一套判定。</p>
     */
    private int patternIndexAt(int rowIndex, int column) {
        if (rowIndex < 0 || rowIndex >= rows.size() || column < 0 || column >= COLUMNS) {
            return -1;
        }
        if (!(rows.get(rowIndex) instanceof DiskRow disk)) {
            return -1;
        }
        int firstColumn = disk.from() == 0 ? 1 : 0;
        if (column < firstColumn) {
            return -1; // 首行第 0 格是磁盘本身
        }
        var patterns = getMenu().getDiskContents(disk.serial());
        var order = displayOrder(disk.serial());
        int position = disk.from() - firstColumn + column;
        if (patterns == null || position < 0 || position >= order.length) {
            return -1;
        }
        // 回到存储序号：取件、换槽都是按它与服务端对话的，所以命中的是排好的那一位、拿到的是盘里的那一位。
        int index = order[position];
        if (index < 0 || index >= patterns.size() || patterns.get(index).isEmpty()) {
            return -1;
        }
        return index;
    }

    /**
     * 盘内样板的显示顺序（显示位次 → 存储序号）；内容与排序口径没变就直接用上次算的。
     *
     * <p>拿内容列表的引用做缓存键：服务端每次推送内容都会换一个新列表对象，引用一变就说明该重算了。</p>
     */
    private int[] displayOrder(long serial) {
        var contents = getMenu().getDiskContents(serial);
        if (contents == null || contents.isEmpty()) {
            return NO_ORDER;
        }

        var order = getSortBy();
        var dir = getSortDir();
        boolean natural = naturalSortEnabled();
        var cached = displayOrders.get(serial);
        if (cached != null && cached.stillMatches(contents, order, dir, natural)) {
            return cached.storageIndexes();
        }

        var indexes = new Integer[contents.size()];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = i;
        }
        var comparator = patternComparator(order, dir, natural);
        // 排序号而不是排堆：n log n，且不丢“显示位次 ↔ 存储序号”的对应（满盘 1024 张也不会在帧里抖）。
        Arrays.sort(indexes, (left, right) -> comparator.compare(contents.get(left), contents.get(right)));

        var storageIndexes = new int[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            storageIndexes[i] = indexes[i];
        }

        displayOrders.put(serial, new DisplayOrder(contents, order, dir, natural, storageIndexes));
        return storageIndexes;
    }

    /**
     * 盘内样板的排序口径。
     *
     * <p>比的是格子里显示的那个名字（样板的主产物），不是样板本体：玩家在格子看到的是产物，按产物排才找得到
     * 东西。mod 档同理，比的是产物所属的 mod。名字档的两个口径（字面/数值）由「数值排序」开关决定。</p>
     */
    private Comparator<ItemStack> patternComparator(SortOrder order, SortDir dir, boolean natural) {
        return switch (order) {
            case MOD -> byModComparator(dir, natural);
            case NAME -> Comparator.comparing(this::displayedItemName, NaturalSort.names(dir, false));
            // 数量档：一枚样板就是一件，没有可比的东西，保持盘里的原顺序。
            case AMOUNT -> (left, right) -> 0;
        };
    }

    /**
     * 按 mod 排：附加排序打开时是四层——mod → 阶层（见 {@link SortTiers}）→ 去掉数字后的文本 → 名字的
     * 数值序；关掉时退回 AE2 原本的两层（mod → 名字字面序）。第三层才是数大小：先分组再排数，同一系列
     * （只是容量不同）才会相邻，不会出现「1k存储元件、1k存储组件、4k存储元件」这种把同系列拆散的次序。
     */
    private Comparator<ItemStack> byModComparator(SortDir dir, boolean additional) {
        Comparator<ItemStack> ascending = Comparator.comparing(displayedItemModId, String::compareToIgnoreCase);
        if (additional) {
            // 与物品网格同一套口径：阶层层夹在 mod 与文本分组之间，命中的排在未命中的前面。四层都按
            // 格子实际显示的那个栈算（见 displayedItem），否则阶层会按样板本体去查，一个也命中不了。
            var tiers = SortTiers.rankerForItems();
            ascending = ascending
                    .thenComparingInt(pattern -> tiers.applyAsInt(displayedItem.apply(pattern)))
                    .thenComparing(stack -> NaturalOrder.template(displayedItemName(stack)),
                            String::compareToIgnoreCase)
                    .thenComparing(this::displayedItemName, NaturalOrder.strings());
        } else {
            ascending = ascending.thenComparing(this::displayedItemName, String::compareToIgnoreCase);
        }
        return dir == SortDir.DESCENDING ? ascending.reversed() : ascending;
    }

    /** 格子实际显示的那个栈：能解出主产物就用产物，解不出就用样板本体。四层排序口径都以它为准。 */
    private static final java.util.function.Function<ItemStack, ItemStack> displayedItem = pattern -> {
        var output = patternOutputOf(pattern);
        return output.isEmpty() ? pattern : output;
    };

    /** 格子里的名字。 */
    private String displayedItemName(ItemStack pattern) {
        return displayedItem.apply(pattern).getHoverName().getString();
    }

    /** 格子所属的 mod：同上，取产物那一侧。 */
    private static final java.util.function.Function<ItemStack, String> displayedItemModId =
            pattern -> NaturalSort.modIdOf(displayedItem.apply(pattern));

    /** 给某个格位（18×18 槽框）描一圈高亮：画在框线上，不盖住格内内容。 */
    private static void outlineCell(GuiGraphics guiGraphics, int cellX, int rowY) {
        guiGraphics.fill(cellX, rowY, cellX + ROW_HEIGHT, rowY + 1, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX, rowY + ROW_HEIGHT - 1, cellX + ROW_HEIGHT, rowY + ROW_HEIGHT, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX, rowY + 1, cellX + 1, rowY + ROW_HEIGHT - 1, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX + ROW_HEIGHT - 1, rowY + 1, cellX + ROW_HEIGHT, rowY + ROW_HEIGHT - 1,
                DISK_SELECTED_TINT);
    }
}
