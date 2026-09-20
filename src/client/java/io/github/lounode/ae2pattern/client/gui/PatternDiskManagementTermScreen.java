package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.client.gui.me.common.RepoSlot;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.core.localization.ButtonToolTips;

import io.github.lounode.ae2pattern.client.sort.NaturalSort;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.network.DiskHostListPayload;
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
 * <p><b>Rows.</b> One row per machine (a header carrying its icon, name and disk count, plus a show/hide
 * toggle), then one row per disk: cell 0 is the disk itself, the remaining 16 cells are the patterns stored on
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

    private static final int TOGGLE_SIZE = 9;

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

    /** 组头行：一台宿主机器。{@code diskCount} 是当前过滤/显示口径下的磁盘数。 */
    private record HostRow(String key, String name, ItemStack icon, int diskCount) implements Row {
    }

    /**
     * 磁盘行。{@code from == 0} 是首行：第 0 格是磁盘本身、后面 16 格是它里面的样板；{@code from > 0} 是续行：
     * 17 格全是样板，{@code from} 是这一行第 0 格对应的样板序号（续行从第一格开始接）。
     */
    private record DiskRow(String hostKey, long serial, ItemStack disk, int from) implements Row {
    }

    /**
     * 供应器剩余的一个空槽，一行一格、竖着排在第一列。{@code foldedCount} &gt; 0 时这一行代表整台机器的全部空槽，
     * 数字写在格的右上角。
     */
    private record FreeSlotsRow(int foldedCount) implements Row {
    }

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> hiddenHosts = new HashSet<>();
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
        // 父类把初始焦点给了 ME 搜索框，但本屏不显示物品网格，那个框在 JSON 里被移出面板；
        // 玩家打字应该进磁盘表的搜索框，否则键会走进一个看不见的输入框。
        setInitialFocus(miniSearchField());
        hideIrrelevantToolbarButtons();

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
     * <p>磁盘来自父类那份列表，所以搜索框筛掉谁，表里就没有谁——编码按钮的写盘目标也是同一条口径。
     * 分组只提供名字、图标与分组键；一台机器被隐藏时它整组都不进表。</p>
     */
    private void rebuildRows() {
        var menu = getMenu();

        var serialToGroup = new HashMap<Long, DiskHostListPayload.HostGroup>();
        var serialToPatternCount = new HashMap<Long, Integer>();
        for (var group : menu.getHostList()) {
            for (var disk : group.disks()) {
                serialToGroup.put(disk.serial(), group);
                serialToPatternCount.put(disk.serial(), disk.patternCount());
            }
        }

        var diskCounts = new HashMap<String, Integer>();
        var emptySlots = new HashMap<String, Integer>();
        for (var group : menu.getHostList()) {
            emptySlots.put(group.key(), group.emptySlots());
        }

        // 盘内顺序缓存跟着当下的盘集合走：被取走、被搜索筛掉的盘不再留条目（连同它那份旧 contents 列表）。
        displayOrders.keySet().retainAll(serialToGroup.keySet());

        var rebuilt = new ArrayList<Row>();
        String currentHost = null;
        for (int i = 0; super.diskEntryAt(i) != null; i++) {
            var entry = super.diskEntryAt(i);
            var group = serialToGroup.get(entry.serial());
            var key = group == null ? "" : group.key();
            if (hiddenHosts.contains(key)) {
                continue;
            }

            diskCounts.merge(key, 1, Integer::sum);
            if (!key.equals(currentHost)) {
                appendFreeSlots(rebuilt, currentHost, emptySlots);
                currentHost = key;
                rebuilt.add(new HostRow(key,
                        group == null ? Component.translatable("gui.ae2_pattern_disk.management_terminal.unknown_host").getString() : group.name(),
                        group == null ? ItemStack.EMPTY : group.icon(),
                        0));
            }
            rebuilt.add(new DiskRow(key, entry.serial(), entry.stack(), 0));
            // 一张盘的内容超过一行时往下续行：首行第 0 格占给了磁盘，续行没有磁盘格，17 格全放内容。
            for (int from = COLUMNS - 1; from < serialToPatternCount.getOrDefault(entry.serial(), 0); from += COLUMNS) {
                rebuilt.add(new DiskRow(key, entry.serial(), ItemStack.EMPTY, from));
            }
        }
        appendFreeSlots(rebuilt, currentHost, emptySlots);

        // 组头的张数要等本组数完才知道，回填一遍（行数很少，代价可以忽略）。
        for (int i = 0; i < rebuilt.size(); i++) {
            if (rebuilt.get(i) instanceof HostRow host) {
                rebuilt.set(i, new HostRow(host.key(), host.name(), host.icon(),
                        diskCounts.getOrDefault(host.key(), 0)));
            }
        }

        if (!rows.equals(rebuilt)) {
            rows.clear();
            rows.addAll(rebuilt);
            clampScroll();
        }
        requestVisibleContents(false);
    }

    /**
     * 给刚数完的那台机器补「剩余槽位」行。
     *
     * <p>空槽数直接取服务端在分组里报的「真正的空格数」：搜索框筛掉部分盘、主机行开关隐藏整台都不会让它变化，
     * 槽位与别的物品共用（NEO ECO 把样板盘与已编码样板放在同一批槽里）也不会被算错。</p>
     *
     * <p>收起时整台只留一行，行上写它代表多少空槽；展开时每个空槽一行，竖着排在第一列。</p>
     */
    private void appendFreeSlots(List<Row> out, String hostKey, Map<String, Integer> emptySlots) {
        if (hostKey == null || hostKey.isEmpty()) {
            return;
        }
        var empty = emptySlots.get(hostKey);
        if (empty == null || empty <= 0) {
            return;
        }

        if (hideEmptySlots) {
            out.add(new FreeSlotsRow(empty));
            return;
        }
        // 展开：一格一行，竖着排在第一列——空槽不是“盘里的内容”，不铺满整行。
        for (int i = 0; i < empty; i++) {
            out.add(new FreeSlotsRow(0));
        }
    }

    private void clampScroll() {
        int max = Math.max(0, rows.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
    }

    /** @return 当前表里磁盘的张数（不含被隐藏的机器），用于「只剩一张就自动落到它」的判断。 */
    private int visibleDiskCount() {
        int count = 0;
        for (var row : rows) {
            // 只数首行：一张盘的内容续行也是 DiskRow，数进去会把一张盘算成好几张。
            if (row instanceof DiskRow disk && disk.from() == 0) {
                count++;
            }
        }
        return count;
    }

    private long soleVisibleDisk() {
        long found = 0;
        for (var row : rows) {
            if (row instanceof DiskRow disk && disk.from() == 0) {
                if (found != 0) {
                    return 0;
                }
                found = disk.serial();
            }
        }
        return found;
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
        rebuildRows();
        if (--contentRefreshCooldown <= 0) {
            contentRefreshCooldown = CONTENT_REFRESH_INTERVAL_TICKS;
            requestVisibleContents(true);
        }
        // 写盘目标：右键选中的那张盘优先（它还在表里才算数），否则沿用「只剩一张就写它」的口径。
        if (selectedSerial != 0) {
            if (containsDisk(selectedSerial)) {
                getMenu().setClientAutoDisk(1, selectedSerial);
            } else {
                // 那张盘被取走、或搜索把它筛出去了，选择跟着失效。
                selectedSerial = 0;
            }
        }
        if (selectedSerial == 0) {
            getMenu().setClientAutoDisk(visibleDiskCount(), soleVisibleDisk());
        }
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
                    guiGraphics.drawString(font, font.plainSubstrByWidth(label, 16 * 18 - TOGGLE_SIZE - 22),
                            baseX + 21, rowY + ROW_TEXT_Y_INSET + 4, textColor, false);
                    drawHostToggle(guiGraphics, baseX, rowY, host.key());
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
            var output = patternOutputOf(pattern);
            var icon = output.isEmpty() ? pattern : output;
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

    /** 组头的显示/隐藏开关：一个小方块，隐藏时画成暗底。 */
    private void drawHostToggle(GuiGraphics guiGraphics, int baseX, int rowY, String key) {
        int x = baseX + LIST_WIDTH - TOGGLE_SIZE - 3;
        // 行带内部自 ROW_TEXT_Y_INSET 起、底部留 1px 边框，开关在这段里居中。
        int y = rowY + ROW_TEXT_Y_INSET + (ROW_HEIGHT - ROW_TEXT_Y_INSET - 1 - TOGGLE_SIZE) / 2;
        boolean shown = !hiddenHosts.contains(key);
        guiGraphics.fill(x, y, x + TOGGLE_SIZE, y + TOGGLE_SIZE, shown ? 0xff9a9a9a : 0xff4a4a4a);
        guiGraphics.fill(x + 1, y + 1, x + TOGGLE_SIZE - 1, y + TOGGLE_SIZE - 1, shown ? 0xffcfcfcf : 0xff2a2a2a);
    }

    // ---- 交互 ----

    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        int rowIndex = rowIndexAt(xCoord, yCoord);
        if (rowIndex < 0) {
            return super.mouseClicked(xCoord, yCoord, btn);
        }

        var row = rows.get(rowIndex);
        if (row instanceof HostRow host) {
            if (btn == 0 && hostToggleAt(xCoord, yCoord)) {
                if (!hiddenHosts.remove(host.key())) {
                    hiddenHosts.add(host.key());
                }
                return true;
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

    @Override
    public boolean mouseScrolled(double xCoord, double yCoord, double scrollX, double scrollY) {
        int max = Math.max(0, rows.size() - visibleRows);
        if (max > 0 && scrollY != 0) {
            scrollOffset = Math.max(0, Math.min(max, scrollOffset - (int) Math.signum(scrollY)));
            requestVisibleContents(false);
            return true;
        }
        return super.mouseScrolled(xCoord, yCoord, scrollX, scrollY);
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int x, int y) {
        int rowIndex = rowIndexAt(x, y);
        if (rowIndex >= 0) {
            var row = rows.get(rowIndex);
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

    private boolean hostToggleAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - leftPos - LIST_X;
        int rowIndex = rowIndexAt(mouseX, mouseY);
        if (rowIndex < 0 || relX < LIST_WIDTH - TOGGLE_SIZE - 3) {
            return false;
        }
        return rows.get(rowIndex) instanceof HostRow;
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
            case MOD -> Comparator.comparing(displayedItemModId, String::compareToIgnoreCase)
                    .thenComparing(this::displayedItemName, NaturalSort.names(dir, natural));
            // 名称档走字面序，与 AE2 原生口径、以及同一屏物品网格的名称档一致；数值序只在按 mod 时生效
            // （它就是为了修 mod 分组内部那一档）。
            case NAME -> Comparator.comparing(this::displayedItemName, NaturalSort.names(dir, false));
            // 数量档：一枚样板就是一件，没有可比的东西，保持盘里的原顺序。
            case AMOUNT -> (left, right) -> 0;
        };
    }

    /** 格子里的名字：能解出主产物就用产物，解不出就用样板本体。 */
    private String displayedItemName(ItemStack pattern) {
        var output = patternOutputOf(pattern);
        return (output.isEmpty() ? pattern : output).getHoverName().getString();
    }

    /** 格子所属的 mod：同上，取产物那一侧。 */
    private static final java.util.function.Function<ItemStack, String> displayedItemModId = pattern -> {
        var output = patternOutputOf(pattern);
        return NaturalSort.modIdOf(output.isEmpty() ? pattern : output);
    };

    /** 给某个格位（18×18 槽框）描一圈高亮：画在框线上，不盖住格内内容。 */
    private static void outlineCell(GuiGraphics guiGraphics, int cellX, int rowY) {
        guiGraphics.fill(cellX, rowY, cellX + ROW_HEIGHT, rowY + 1, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX, rowY + ROW_HEIGHT - 1, cellX + ROW_HEIGHT, rowY + ROW_HEIGHT, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX, rowY + 1, cellX + 1, rowY + ROW_HEIGHT - 1, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX + ROW_HEIGHT - 1, rowY + 1, cellX + ROW_HEIGHT, rowY + ROW_HEIGHT - 1,
                DISK_SELECTED_TINT);
    }
}
