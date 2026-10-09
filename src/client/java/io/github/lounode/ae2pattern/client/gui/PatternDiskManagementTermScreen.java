package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import appeng.client.gui.StackWithBounds;
import appeng.client.gui.me.common.RepoSlot;

import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.blaze3d.platform.InputConstants;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

// 行模型已抽到 DiskTableRowModel：静态导入让 Row / HostRow / DiskRow / FreeSlotsRow 这些短名
// 在本类内照旧可用，下面的表格绘制与点击分发因此一句都没改。
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.DiskRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.FreeSlotsRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.HostRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.Row;

import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.DisabledSlot;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.ServerSettingToggleButton;

import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * The pattern disk management terminal's screen: a table of every disk on the grid, grouped by the machine
 * holding it, with the encoding terminal's encoding area underneath.
 *
 * <p>Layout comes from the style document: the texture slices for the table chrome live in its {@code images}
 * block (this screen fetches them by name), a title strip and 17 columns of 18px cells on
 * top, then the player inventory on the left and the encoding area - the very same widgets the
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
 *
 * <p>{@code @IPNPlayerSideOnly}：整理模组的标注，与父屏同源（它没有 {@code @Inherited}，故在此重标一份），
 * 理由见 {@code PatternDiskEncodingTermScreen} 的类注释。</p>
 */
@IPNPlayerSideOnly
public class PatternDiskManagementTermScreen extends PatternDiskEncodingTermScreen {

    /**
     * 本屏幕的菜单是管理终端自己的那个类型，但父屏把菜单类型写死在签名里（把它泛型化会牵连编码区面板、数量子屏与
     * 两个集成适配类），所以在这里用协变返回把类型收窄：本类内部直接调管理菜单的方法，其余文件一动不动。
     */
    @Override
    public PatternDiskManagementTermMenu getMenu() {
        return (PatternDiskManagementTermMenu) super.getMenu();
    }

    /** 「显示模式」按钮：与 AE2 样板访问终端共用同一个服务端设置。 */
    private final ServerSettingToggleButton<ShowPatternProviders> showProvidersButton;

    /** 「隐藏槽位/显示槽位」按钮：只影响本屏的行模型，不涉服务端。按下时要回写状态，所以不是 final。 */
    private StatesToggleButton hideSlotsButton;

    /** 当前是否收起空槽。默认收起：表的常规观感保持紧凑，想看全槽布局再展开。 */
    private boolean hideEmptySlots = true;
    /** 上一次从服务端读到的值，只用于“服务端变了才跟随”，免得客户端刚点完就被自己的回推拉回去。 */
    private boolean lastServerHideEmptySlots = true;
    private long lastServerSelectedSerial;
    /** 样板内容搜索范围（第 4 项新搜索栏）：本地副本 + 服务端上次给的值（只在它变了时跟随）。 */
    private io.github.lounode.ae2pattern.common.menu.DiskEncodingLogic.SearchScope searchScope = io.github.lounode
            .ae2pattern.common.menu.DiskEncodingLogic.SearchScope.BOTH;
    private io.github.lounode.ae2pattern.common.menu.DiskEncodingLogic.SearchScope lastServerSearchScope = io.github.lounode
            .ae2pattern.common.menu.DiskEncodingLogic.SearchScope.BOTH;
    /** 服务端上次给的「附加排序」开关值；它一变就说明该跟随服务端。 */
    private boolean lastServerNaturalSort = true;
    /** 样板内容搜索栏与它的搜索范围轮换按钮（第 4 项）。 */
    private appeng.client.gui.widgets.AETextField contentSearchField;
    private ContentSearchScopeButton contentSearchScopeButton;
    /** 搜索栏里的文本；空白表示不过滤。 */
    private String contentSearchText = "";

    /**
     * 右键选中的磁盘（「编写样板」的写盘目标）；0 = 没选。
     *
     * <p>serial 从 {@code Long.MIN_VALUE} 起自增、恒为负（见菜单里的说明），所以 0 可以安全地当「没选」。</p>
     */
    private long selectedSerial;

    // 表格区几何：外壳的贴图切片（表头带 / 六条行带 / 尾饰带 / 空槽格）全在样式文档的 images 里，
    // 带高与格距都从那些切片反推——文档改一格，代码不用跟着改。
    // 行分配同 AE2 的样板访问终端（PatternAccessTermScreen.drawBG）：按「行类型」从贴图取行带——
    // 文本带（无格子框，给主机名这类纯文本行）与物品带（有 17 格框，给磁盘/样板行），
    // 三份分别对应可见窗口的首行/中间行/末行（该屏的 ROW_TEXT_/ROW_INVENTORY_TOP|MIDDLE|BOTTOM_BBOX 同值）。
    // 落点口径同 AE2 槽位：格子的 x/y 就是「物品左上角」，底图画在它 −1 处，所以绘制时统一 +1——
    // LIST_X 取 7 时物品落在贴图实测的 x=8。
    private static final int LIST_X = 7;
    private static final int LIST_Y = 0;
    private static final int COLUMNS = 17;
    /**
     * 槽位内容的纵向起点：行带里格框的填充区从带上沿 +1 开始（横向同理，见各绘制处的 +1）。物品与槽底都从这里
     * 画，不然会比格框低 1px、底下露出一道缝。
     */
    private static final int CELL_Y_INSET = 1;
    /**
     * 纯文本行（组头）的纵向起点：那一行没有格框，图标/文字/开关按自己的观感取 +2，不跟着槽位一起上移。
     */
    private static final int ROW_TEXT_Y_INSET = 2;
    /** 视口外多要一行内容：滚一格时不至于先闪一帧空行。 */
    private static final int CONTENT_MARGIN_ROWS = 1;

    /**
     * 表头带 / 尾饰带 / 行带的高与格距：全部取自样式文档 images 切片的 srcRect。
     *
     * <p>面板总高由 {@code terminalStyle} 那条链算出，可见行数是 {@code (总高 - 表头 - 尾饰) / 行高}；
     * 这两组带高都在同一份文档里，代码不再抄写一遍数字。</p>
     */
    private final int headerHeight;
    private final int footerHeight;
    private final int rowHeight;
    /** 格距（含框线）：空槽格切片的宽，列命中测试与格子落点都用它。 */
    private final int cellSize;
    /** 填充区宽：列数 × 行高。再右是滚动条区（本屏只用滚轮，不画它）。 */
    private final int listWidth;
    /** 组头文字色：样式文档调色板的 DEFAULT_TEXT_COLOR（与 AE2 其余界面同源）。 */
    private final int textColor;

    /**
     * 当前可见行数：由终端风格档位与窗口高度共同决定，数值就是父类按 terminalStyle 算好的 imageHeight 反推来的，
     * {@code init()} 里赋。屏幕不随 resize 重建，但 init() 会在 resize 后被调用，所以这一个字段就够。
     */
    private int visibleRows = 6;

    /** 盘内样板的显示顺序（内容搜索 + 排序档位）都在 {@link DiskPatternView} 里，这里只持有它。 */
    private final DiskPatternView patternView = new DiskPatternView(this::getSortBy, this::getSortDir,
            this::naturalSortEnabled);

    /**
     * 表格自己的滚动条（字段名与父类那枚区分开）。本屏的行带不是 AE2 物品网格，所以范围得自己喂给它（见 {@link #syncScrollbar()}）；
     * 拖动、滚轮、上下翻页按钮都由它处理，屏幕只负责把位置抄回来。
     */
    private Scrollbar tableScrollbar;

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.management_terminal");

    // 表外壳的切片全部来自样式文档的 images（切片坐标与 512 参考尺寸都写在文档里），代码只按名字取。
    // Blitter 是可变对象且与文档共享同一实例：每次使用必须紧接 dest(...) + blit(...)，不要另存引用或染色。
    private final Blitter headerBand;
    private final Blitter footerBand;
    /** 空槽格的底图：贴图里「空白样板」那一格，宽高正好是一个槽位框。 */
    private final Blitter blankCell;
    // 六条行带，与 AE2 的 ROW_TEXT_/ROW_INVENTORY_TOP|MIDDLE|BOTTOM_BBOX 同一分区（本屏贴图与它同源）。
    private final Blitter rowTextTop;
    private final Blitter rowSlotsTop;
    private final Blitter rowTextMiddle;
    private final Blitter rowSlotsMiddle;
    private final Blitter rowTextBottom;
    private final Blitter rowSlotsBottom;

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

    private final List<Row> rows = new ArrayList<>();
    private final LongSet requestedContents = new LongOpenHashSet();

    private int scrollOffset;
    private int contentRefreshCooldown = CONTENT_REFRESH_INTERVAL_TICKS;

    public PatternDiskManagementTermScreen(PatternDiskManagementTermMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        // 表外壳的切片与从它们反推的几何：全部读自样式文档（images 与调色板）。文档缺键会直接抛
        // IllegalStateException、界面开不出来——这是有意口径：静默降级只会画出一半底图。
        this.headerBand = style.getImage("tableHeader");
        this.footerBand = style.getImage("tableFooter");
        this.blankCell = style.getImage("blankCell");
        this.rowTextTop = style.getImage("rowTextTop");
        this.rowSlotsTop = style.getImage("rowSlotsTop");
        this.rowTextMiddle = style.getImage("rowTextMiddle");
        this.rowSlotsMiddle = style.getImage("rowSlotsMiddle");
        this.rowTextBottom = style.getImage("rowTextBottom");
        this.rowSlotsBottom = style.getImage("rowSlotsBottom");
        this.headerHeight = this.headerBand.getSrcHeight();
        this.footerHeight = this.footerBand.getSrcHeight();
        // 六条行带同高（文档里都是 18），取哪一条反推行高都一样。
        this.rowHeight = this.rowTextTop.getSrcHeight();
        this.cellSize = this.blankCell.getSrcWidth();
        this.listWidth = COLUMNS * this.rowHeight;
        this.textColor = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();

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
            // 回传：状态本体在服务端的编码逻辑里（面板写部件 NBT、无线写物品组件），不回传就不会被记住。
            getMenu().sendViewStateToServer(this.hideEmptySlots, this.selectedSerial, this.searchScope,
                    naturalSortEnabled());
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

        // 样板内容搜索栏（第 4 项）：顶部居中、与磁盘搜索栏同一行（样式 JSON 的 contentSearch 给坐标），
        // 右侧紧跟搜索范围轮换按钮（contentSearchScope）。位置与磁盘搜索栏的附属按钮同一摆法。
        this.contentSearchField = widgets.addTextField("contentSearch");
        this.contentSearchField.setPlaceholder(Component.translatable("gui.ae2_pattern_disk.content_search"));
        this.contentSearchField.setTooltipMessage(List.of(
                Component.translatable("gui.ae2_pattern_disk.content_search.title"),
                Component.translatable("gui.ae2_pattern_disk.content_search.hint")));
        this.contentSearchField.setResponder(text -> this.contentSearchText = text == null ? "" : text);
        this.contentSearchScopeButton = new ContentSearchScopeButton(this.searchScope, scope -> {
            this.searchScope = scope;
            // 范围是终端自己的状态（面板写部件 NBT、无线写物品组件），改完得回传才会被记住。
            getMenu().sendViewStateToServer(this.hideEmptySlots, this.selectedSerial, this.searchScope,
                    naturalSortEnabled());
        });
        widgets.add("contentSearchScope", this.contentSearchScopeButton.widget());

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
        this.visibleRows = Math.max(1, (imageHeight - this.headerHeight - this.footerHeight) / this.rowHeight);

        // MEStorageScreen.init() 给终端网格加了 RepoSlot；我们用自定义表格，不需要它们。
        this.menu.slots.removeIf(slot -> slot instanceof RepoSlot);
        // 父类把初始焦点给了 ME 搜索框，但本屏不显示物品网格，那个框在 JSON 里被移出面板——让它拿着焦点
        // 等于把玩家的按键送进一个看不见的输入框。所以清掉初始焦点：开局谁也不选中，要用搜索框自己点。
        //
        // 用 setFocused(null) 而不是 setInitialFocus(null)：后者的实现要先拿参数解引用去算焦点路径，传 null
        // 直接 NPE，而且抛在 init() 里会连带把整个开屏打断（NeoForge 报 "Failed to handle advanced open
        // screen from server"，客户端被断开）。setFocused 才是 null 安全的那个。
        setFocused(null);
        // 工具栏按清单排：顺序与显隐都由那一份清单说了算（父类那几枚 + 本屏的显示模式 / 隐藏槽位）。
        ToolbarPlan.apply(this, "management terminal", TOOLBAR, this::toolbarSlot);

        // 风格档位可能把面板改矮：清单没变时 rebuildRows 不会夹偏移，这里补一次，免得顶部留白。
        // （带高与 terminalStyle 的 header/row/bottom 同源：两者都在本屏的样式文档里，不存在跨文件同步。）
        clampScroll();
    }

    /**
     * 左侧工具栏的清单：顺序即清单顺序，不在清单里的一律隐藏（包括 AE2 那枚「终端设置」——它的设置页
     * 全是物品网格的项，而本屏把网格整个藏了）。
     */
    private static final List<String> TOOLBAR = List.of(
            "guide", "sortBy", "naturalSort", "sortOrder", "showProviders", "hideSlots",
            "mode", "terminalSwitch", "frequencyCard", "frequencyAutoConnect");

    /** 本屏多出来的两枚：显示模式（AE2 的样板访问终端开关）与隐藏槽位（本屏自己的行模型开关）。 */
    @Override
    protected String toolbarSlot(Button button) {
        var slot = super.toolbarSlot(button);
        if (slot != null) {
            return slot;
        }
        if (button == this.hideSlotsButton) {
            return "hideSlots";
        }
        return null;
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

        boolean searched = isDiskSearchActive();
        // 内容搜索（新搜索栏）是否在生效：它决定不匹配的样板/机器/空行要不要真的从表里消失。
        boolean contentSearched = this.contentSearchText != null && !this.contentSearchText.isEmpty();

        // 盘内顺序缓存跟着当下的盘集合走：被取走、被搜索筛掉的盘不再留条目（连同它那份旧 contents 列表）。
        patternView.retainOnly(visibleDisks.keySet());

        // 机器列表默认按显示名排（大小写不敏感的字符串序；中文名走 Unicode 码点序、不是拼音序——项目里
        // 唯一的拼音能力只有搜索用的 JECH 匹配，取不到拼音串）。同名机器的相对次序沿用服务端原序
        //（同名本就合并成一行，这里排的是不同机器之间）。
        var hosts = new ArrayList<>(menu.getHostList());
        hosts.sort(Comparator.comparing(host -> host.name() == null ? "" : host.name(),
                String.CASE_INSENSITIVE_ORDER));
        // 行的构建（三层过滤、续行、空槽行）已搬去 DiskTableRowBuilder，屏幕只负责把当下状态打包成
        // 输入：哪些盘可见、每张盘命中多少样板（-1 表示内容未下发，构建器不会据此隐藏）。
        var groupInputs = new ArrayList<DiskTableRowBuilder.GroupInput>(hosts.size());
        for (var group : hosts) {
            var diskInputs = new ArrayList<DiskTableRowBuilder.DiskInput>(group.disks().size());
            for (var disk : group.disks()) {
                var visible = visibleDisks.get(disk.serial());
                if (visible != null) {
                    diskInputs.add(new DiskTableRowBuilder.DiskInput(disk.serial(), visible.stack(),
                            disk.patternCount(), matchedPatternCount(disk.serial())));
                }
            }
            groupInputs.add(new DiskTableRowBuilder.GroupInput(group.key(), group.name(), group.icon(),
                    diskInputs, group.emptySlots()));
        }
        var rebuilt = DiskTableRowBuilder.build(groupInputs, searched, contentSearched, hideEmptySlots);

        if (!rows.equals(rebuilt)) {
            rows.clear();
            rows.addAll(rebuilt);
            clampScroll();
        }
        requestVisibleContents(false);
    }

    /** 搜索框里有没有内容；有内容时没命中磁盘的组不进表（否则一搜就满屏空机器）。 */
    // isDiskSearchActive() 来自基类：编码终端的自动写盘也按同一判据。

    /** 该盘命中内容搜索的样板数；内容未下发时返回 -1（未知，调用方不得据此隐藏）。 */
    private int matchedPatternCount(long serial) {
        if (getMenu().getDiskContents(serial) == null) {
            return -1;
        }
        return displayOrder(serial).length;
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
        } else if (diskSearchQuery().preferOrder()) {
            // 没选中盘、且写了 {@code @order}/{@code @顺位}：按表格行序把一张填满再下一张（与玩家看到的顺序一致）。
            // 默认不写这个修饰词，所以“筛了盘之后写哪张”仍然是按剩余空间挑（与编码终端同口径）。
            for (var row : rows) {
                if (row instanceof DiskRow disk && disk.from() == 0) {
                    out.add(disk.serial());
                }
            }
        } else {
            // 没选中盘（搜索与否都一样）：当前档位那一组盘按存量升序，写最少的那张
            // （与编码终端同口径，见基类 tierGroupByUsage）。本分支原本返回空，而「盘明明对得上、点编写样板
            // 却没反应」正是那个空造成的。选中盘的优先级在本分支之上，已选中的目标不会被这里抢走。
            return tierGroupByUsage();
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
        // 服务端那份才是权威（面板存部件 NBT、无线存物品组件）。只在它真的变了时跟随，开屏与刷新都能恢复上次的值。
        if (this.lastServerHideEmptySlots != getMenu().viewHideEmptySlots()) {
            this.lastServerHideEmptySlots = getMenu().viewHideEmptySlots();
            this.hideEmptySlots = this.lastServerHideEmptySlots;
            this.hideSlotsButton.setState(this.hideEmptySlots);
        }
        if (this.lastServerSelectedSerial != getMenu().viewSelectedSerial()) {
            this.lastServerSelectedSerial = getMenu().viewSelectedSerial();
            this.selectedSerial = this.lastServerSelectedSerial;
        }
        if (this.lastServerSearchScope != getMenu().viewSearchScope()) {
            this.lastServerSearchScope = getMenu().viewSearchScope();
            this.searchScope = this.lastServerSearchScope;
            if (this.contentSearchScopeButton != null) {
                this.contentSearchScopeButton.setScope(this.searchScope);
            }
        }
        // 「附加排序」开关：服务端值变了就跟随（开屏恢复）；没变而本地与它不同，说明是玩家刚点的，回传。
        if (this.lastServerNaturalSort != getMenu().viewNaturalSort()) {
            this.lastServerNaturalSort = getMenu().viewNaturalSort();
            if (this.naturalSortButton != null) {
                this.naturalSortButton.setEnabled(this.lastServerNaturalSort);
            }
        } else if (this.naturalSortButton != null
                && this.naturalSortButton.isEnabled() != this.lastServerNaturalSort) {
            getMenu().sendViewStateToServer(this.hideEmptySlots, this.selectedSerial, this.searchScope,
                    this.naturalSortButton.isEnabled());
        }
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
        blit(this.headerBand, guiGraphics, offsetX, offsetY);

        int y = offsetY + this.headerHeight;

        // 行分配同 AE2：每行先铺「文本带」作底，含物品格的行再叠「物品带」；带是整条的（含左右边框）。
        for (int i = 0; i < visibleRows; i++) {
            boolean firstLine = i == 0;
            boolean lastLine = i == visibleRows - 1;
            int rowY = y + i * this.rowHeight;
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

        blit(this.footerBand, guiGraphics, offsetX, offsetY + this.headerHeight + visibleRows * this.rowHeight);
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
     * 末行取 BOTTOM、其余取 MIDDLE。那六条带本身来自样式文档的 images。
     */
    private Blitter selectRowBand(boolean slotsRow, boolean firstLine, boolean lastLine) {
        if (slotsRow) {
            return firstLine ? this.rowSlotsTop : lastLine ? this.rowSlotsBottom : this.rowSlotsMiddle;
        }
        return firstLine ? this.rowTextTop : lastLine ? this.rowTextBottom : this.rowTextMiddle;
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
        int baseY = LIST_Y + this.headerHeight;

        for (int i = 0; i < visibleRows; i++) {
            int rowIndex = scrollOffset + i;
            if (rowIndex >= rows.size()) {
                break;
            }

            int rowY = baseY + i * this.rowHeight;
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

            int cellX = baseX + column * this.cellSize + 1;
            int cellY = rowY + CELL_Y_INSET;

            // 显示主产物，而不是样板本体：与 AE2 样板访问终端同口径（它的 PatternSlot.getDisplayStack 用
            // EncodedPatternItem#getOutput 换掉槽位显示）。任何类型的产物都直接显示——那个方法对流体等
            // 非物品产出会包一层伪物品；取不到时回退到样板本体。玩家因此不必按住 Shift 才知道样板做什么。
            var icon = DiskPatternView.displayedItem(pattern);
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
        // blankCell 是一个完整的槽框（自带 1px 边框），落点与行带本身的格框同格位——框对框，它内部就自然落在
        // +1，与同排物品格的内容线一致（物品画在 +CELL_Y_INSET）。
        blit(this.blankCell, guiGraphics, baseX, rowY);

        if (row.foldedCount() <= 0) {
            return;
        }
        var count = Integer.toString(row.foldedCount());
        // 右上角：与那格右/上边线各留 1px（格内从框 +1 起）。
        guiGraphics.drawString(font, count, baseX + 1 + 16 - font.width(count), rowY + 2, 0xFF3F3F3F, false);
    }

    // ---- 交互 ----

    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        // 展开的模式列表要先抢这一下：本屏的行表格排在 children 前面，行带与列表条目的第二列重叠时
        // 会把点击吃掉。本方法在行内不调 super，所以这个拦截必须自己先做一次。
        if (btn == InputConstants.MOUSE_BUTTON_LEFT && interceptModeMenuClick(xCoord, yCoord)) {
            return true;
        }

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
        getMenu().sendViewStateToServer(this.hideEmptySlots, this.selectedSerial, this.searchScope,
                naturalSortEnabled());
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
        return PatternDiskApi.isPatternDisk(getMenu().getCarried());
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
        this.tableScrollbar.setHeight(Math.max(1, visibleRows * this.rowHeight - 2));
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
        int relY = (int) mouseY - topPos - LIST_Y - this.headerHeight;
        if (relX < 0 || relX >= this.listWidth || relY < 0) {
            return -1;
        }
        int slotRow = relY / this.rowHeight;
        if (slotRow < 0 || slotRow >= visibleRows) {
            return -1;
        }
        int rowIndex = scrollOffset + slotRow;
        return rowIndex < rows.size() ? rowIndex : -1;
    }

    private int columnAt(double mouseX) {
        return ((int) mouseX - leftPos - LIST_X) / this.cellSize;
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
            var stack = GenericStack.fromItemStack(pattern == null ? ItemStack.EMPTY : DiskPatternView.outputOf(pattern));
            if (stack != null) {
                // 非物品产出（流体等）是经伪物品包装过来的，里面带的量是 0，而浏览器侧只有 JEI 会把 0 夹到 1。
                // 这里统一补到 1，两家口径一致，也免得键匹配不上。
                if (stack.amount() <= 0) {
                    stack = new GenericStack(stack.what(), 1);
                }
                // 坐标与 drawDiskRow 逐字一致（baseX = LIST_X、rowY = 表头下沿 + 可见行号 × 行高、格内再各 +1），
                // 只是把局部坐标换成绝对屏幕坐标；矩形口径与 AE2 的 StackWithBounds.fromSlot 相同（一格内容 16×16）。
                int cellX = leftPos + LIST_X + column * this.cellSize + 1;
                int cellY = topPos + LIST_Y + this.headerHeight + (rowIndex - scrollOffset) * this.rowHeight
                        + CELL_Y_INSET;
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

    /** 盘内样板的显示顺序（显示位次 → 存储序号）；口径与缓存都在 {@link DiskPatternView}。 */
    private int[] displayOrder(long serial) {
        return patternView.orderOf(serial, getMenu().getDiskContents(serial), this.contentSearchText,
                this.searchScope);
    }

    /** 给某个格位（一个槽框）描一圈高亮：画在框线上，不盖住格内内容。 */
    private void outlineCell(GuiGraphics guiGraphics, int cellX, int rowY) {
        int size = this.cellSize;
        guiGraphics.fill(cellX, rowY, cellX + size, rowY + 1, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX, rowY + size - 1, cellX + size, rowY + size, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX, rowY + 1, cellX + 1, rowY + size - 1, DISK_SELECTED_TINT);
        guiGraphics.fill(cellX + size - 1, rowY + 1, cellX + size, rowY + size - 1,
                DISK_SELECTED_TINT);
    }
}
