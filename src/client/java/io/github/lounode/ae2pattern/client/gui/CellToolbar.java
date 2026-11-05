package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.ActionItems;
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.localization.GuiText;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.client.integration.bookmarks.BookmarkReader;
import io.github.lounode.ae2pattern.client.integration.megacells.MegaCutoffButtonFactory;
import io.github.lounode.ae2pattern.client.integration.neoecoae.EcoBulkCutoffButton;
import io.github.lounode.ae2pattern.common.menu.cell.CellBulkCutoffCompat;
import io.github.lounode.ae2pattern.integration.extendedae.VoidCellCompat;
import io.github.lounode.ae2pattern.integration.megacells.MegaCellsCompat;

/**
 * 元件管理终端的左侧工具栏：一批按钮 + 它们的显隐/选中状态 + 按下动作。
 *
 * <p>这一组的状态（上一次算过的元件快照、当前选中的 ECO 标记配置槽、上一档物质聚合模式等）只被它自己的
 * 显隐同步与按钮回调读写，屏幕别处一概不碰，所以整组连状态一起从屏幕里搬出来。屏幕只在构造器里按
 * {@link #buttons()} 给的顺序 {@code addToLeftToolbar}，每帧一行 {@link #syncAll(boolean)} 转调。</p>
 *
 * <p>本类<b>不持有屏幕，也不持有菜单</b>：需要的菜单动作与屏幕特有服务（如 {@code isHandlingRightClick()}）
 * 全部在构造期以回调装进 {@link Context} 注入。这样既不会把协作类变成反过来摸屏幕的上下文对象，可选邻居
 * （JEI / EMI / ECO / MEGA / EAE）也只以本模组自己的软依赖入口出现，缺席时不会在加载阶段被解析。</p>
 */
final class CellToolbar {

    /** 带一个布尔值的菜单动作：用基本类型形参，免得 {@code Consumer<Boolean>} 在方法引用里来回装箱。 */
    @FunctionalInterface
    interface BoolAction {
        void accept(boolean value);
    }

    /** 带两个 int 的菜单动作（配置槽号 + 步进方向）：同上，避开 {@code BiConsumer<Integer, Integer>} 的装箱。 */
    @FunctionalInterface
    interface IntIntAction {
        void accept(int first, int second);
    }

    /** 收藏夹写入：物品清单 + 跳过的非物品收藏数。 */
    @FunctionalInterface
    interface BookmarkSink {
        void accept(List<ItemStack> items, int skippedNonItems);
    }

    /**
     * 一次性的窄依赖：菜单动作与屏幕特有服务，全部在构造期以回调注入。
     *
     * <p>用回调而不是传整个菜单/屏幕，是为了让这一组按钮只认「按下去要调哪几个动作、每帧要问哪几个判据」，
     * 不认它们背后的对象。</p>
     */
    record Context(
            Runnable partition,
            Runnable clearMarkerArea,
            Runnable cycleCopyMode,
            BoolAction cycleCompressionCutoff,
            IntIntAction cycleEcoBulkCutoff,
            BookmarkSink markBookmarks,
            Runnable quickMark,
            BoolAction cycleVoidMode,
            Runnable consolidateEcoBulkMarkers,
            Supplier<ItemStack> encodeStack,
            BooleanSupplier markerAreaEditable,
            BooleanSupplier handlingRightClick) {
    }

    /** 「标记收藏」的图标（states.png 16,48）。 */
    private static final Blitter ICON_MARK_BOOKMARKS = states(16, 48);

    /** 「切换压缩选择」的图标（states.png 64,48）。 */
    private static final Blitter ICON_ECO_BULK_CUTOFF_SWITCH = states(64, 48);

    /** 「压缩统一管理」的图标（states.png 80,48）。 */
    private static final Blitter ICON_ECO_BULK_CONSOLIDATE = states(80, 48);

    /** 「快捷标记」的图标：项目内 states.png 96,48，不是 ae2 那份。 */
    private static final Blitter ICON_QUICK_MARK = states(96, 48);

    /** 「物质聚合模式」三档的图标，从左到右：虚空 / 物质球 / 奇点（states.png 一条 48x16）。序号就是模式的枚举序号。
     * 固定三格：EAE 将来加第四档时图标会重复最后一格（提示语仍按枚举名取键，不会指错人）——那时补一格贴图即可。 */
    private static final Blitter[] ICON_VOID_MODES = { states(0, 80), states(16, 80), states(32, 80) };

    /** 工具栏按钮底（18x20，与 AE2 自家那几枚同款），常态 / 光标选中。 */
    private static final ResourceLocation STATES = ResourceLocation
            .parse("ae2_pattern_disk:textures/guis/states.png");
    private static final Blitter BUTTON_BG_NORMAL = Blitter.texture(STATES).src(208, 224, 18, 20);
    private static final Blitter BUTTON_BG_HOVER = Blitter.texture(STATES).src(226, 224, 18, 20);

    /** 元件工作台那三个按钮：分区存储 / 清除 / 复制模式。 */
    private ActionButton partitionButton;
    private ActionButton clearButton;
    private ToggleButton copyModeButton;
    /** MEGA Cells 的大宗压缩截断钮；那个模组不在场时为 null（工厂返回 null）。 */
    @Nullable
    private Button megaCutoffButton;
    /** ECO 大宗元件的截断物按钮与选择按钮；ECO 不在场时整组为 null。 */
    @Nullable
    private EcoBulkCutoffButton ecoBulkCutoffButton;
    @Nullable
    private StatesIconButton ecoBulkCutoffSwitchButton;
    @Nullable
    private StatesIconButton ecoBulkConsolidateButton;
    /** 上一次算过 ECO 标记列表的编码槽内容快照；null = 还没算过。 */
    @Nullable
    private ItemStack lastEcoBulkCell;
    private List<CellBulkCutoffCompat.Marker> ecoBulkMarkers = List.of();
    /** 当前选中的 ECO 标记配置槽；-1 = 没有选中。 */
    private int selectedEcoBulkSlot = -1;
    /** 上一次低频重算标记的客户端 tick；压缩链数据是异步同步的，不能只按元件内容失效。 */
    private long lastEcoBulkRefreshTick = Long.MIN_VALUE;
    /** 「标记收藏」「快捷标记」与「物质聚合模式」；后者只在编码槽里是 EAE 虚空元件时出现。 */
    private StatesIconButton markBookmarksButton;
    private StatesIconButton quickMarkButton;
    private StatesIconButton voidModeButton;
    /** 上一次给模式按钮设的提示对应哪一档（-1 = 还没设过）；只在它变了时重设，省得每帧造 Component。 */
    private int lastVoidMode = -1;
    /**
     * 上一次算过「截断状态」的编码槽内容快照；null = 还没算过。
     *
     * <p>截断状态只随这张元件变，而元件的任何变化（换元件、我们自己按了按钮被服务端写回、别处改了这个元件）
     * 都会让客户端拿到一份内容不同的栈，所以按**内容**做失效判据就够。不按引用判是因为两者都不可靠：
     * 引用可能被就地改写（那样快照内容会变、引用不会），而快照比较本身很便宜（元件就那几个组件）。</p>
     */
    private ItemStack lastMegaCell;

    private final Context ctx;

    /** 按 {@code addToLeftToolbar} 的先后顺序建好的按钮；屏幕照这个顺序上栏，顺序影响渲染层级与输入命中。 */
    private final List<Button> buttons;

    CellToolbar(Context ctx) {
        this.ctx = ctx;
        var order = new ArrayList<Button>();

        // 三个按钮在构造器里就建好并上左侧工具栏：AE2 的左工具栏是在开屏时按已注册的按钮排一次位的，
        // 放到 init() 里加会没有按钮底与图标（而且每 resize 一次会再添一套）。与 AE2 元件工作台同一个建法。
        this.partitionButton = new ActionButton(ActionItems.COG, item -> ctx.partition().run());
        this.clearButton = new ActionButton(ActionItems.CLOSE, item -> ctx.clearMarkerArea().run());
        this.copyModeButton = new ToggleButton(Icon.COPY_MODE_ON, Icon.COPY_MODE_OFF,
                GuiText.CopyMode.text(), GuiText.CopyModeDesc.text(),
                enabled -> ctx.cycleCopyMode().run());
        order.add(this.partitionButton);
        order.add(this.clearButton);
        order.add(this.copyModeButton);

        // MEGA Cells 的大宗压缩截断钮：那个模组不在场时工厂返回 null，这一枚就不存在。
        // 与上面三枚同一时机建（工具栏只在开屏时按已注册的按钮排一次位，补在 init() 里不会有按钮底与图标）。
        this.megaCutoffButton = MegaCutoffButtonFactory.create(this::onMegaCutoffPressed);
        if (this.megaCutoffButton != null) {
            order.add(this.megaCutoffButton);
        }

        // ECO 大宗元件：截断物按钮 + 切换按钮。ECO 不在场时适配器不注册，这一组就不建；按钮每屏各建一次。
        if (CellBulkCutoffCompat.isAvailable()) {
            this.ecoBulkCutoffButton = new EcoBulkCutoffButton(this::onEcoBulkCutoffPressed);
            this.ecoBulkCutoffButton.setVisible(false);
            order.add(this.ecoBulkCutoffButton);

            this.ecoBulkCutoffSwitchButton = new StatesIconButton(
                    () -> ICON_ECO_BULK_CUTOFF_SWITCH, this::onEcoBulkCutoffSwitchPressed);
            this.ecoBulkCutoffSwitchButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
            this.ecoBulkCutoffSwitchButton.setTooltip(List.of(
                    Component.translatable(
                            "gui.ae2_pattern_disk.cell_management_terminal.eco_bulk_cutoff_switch"),
                    Component.translatable(
                            "gui.ae2_pattern_disk.cell_management_terminal.eco_bulk_cutoff_switch.previous"),
                    Component.translatable(
                            "gui.ae2_pattern_disk.cell_management_terminal.eco_bulk_cutoff_switch.next")));
            this.ecoBulkCutoffSwitchButton.setVisibility(false);
            order.add(this.ecoBulkCutoffSwitchButton);

            this.ecoBulkConsolidateButton = new StatesIconButton(
                    () -> ICON_ECO_BULK_CONSOLIDATE, this::onEcoBulkConsolidatePressed);
            this.ecoBulkConsolidateButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
            this.ecoBulkConsolidateButton.setTooltip(List.of(
                    Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.eco_bulk_consolidate"),
                    Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.eco_bulk_consolidate.desc"),
                    Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.mark_targets"),
                    Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.mark_target_eco")));
            this.ecoBulkConsolidateButton.setVisibility(false);
            order.add(this.ecoBulkConsolidateButton);
        }

        // 三枚项目自己的图标按钮（states.png）。都做成“图标每帧现取”的那种：模式那枚的图标要跟着当前模式走。
        this.markBookmarksButton = new StatesIconButton(() -> ICON_MARK_BOOKMARKS, this::onMarkBookmarksPressed);
        this.markBookmarksButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.markBookmarksButton.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.mark_bookmarks")));
        order.add(this.markBookmarksButton);

        this.quickMarkButton = new StatesIconButton(() -> ICON_QUICK_MARK, this::onQuickMarkPressed);
        this.quickMarkButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.quickMarkButton.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.quick_mark"),
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.quick_mark.desc"),
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.mark_targets"),
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.mark_target_eco"),
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.mark_target_bigger")));
        this.quickMarkButton.setVisibility(false);
        order.add(this.quickMarkButton);

        this.voidModeButton = new StatesIconButton(this::voidModeIcon, this::onVoidModePressed);
        this.voidModeButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        order.add(this.voidModeButton);

        this.buttons = List.copyOf(order);
    }

    /** 上栏顺序：与建按钮的顺序一致，屏幕照它逐个 {@code addToLeftToolbar}。 */
    List<Button> buttons() {
        return this.buttons;
    }

    /** 本屏的按钮身份：清单里的名字只在这里给出；认不出返回 null。 */
    @Nullable
    String slotOf(Button button) {
        if (button == this.partitionButton) {
            return "partition";
        }
        if (button == this.clearButton) {
            return "clear";
        }
        if (button == this.copyModeButton) {
            return "copyMode";
        }
        if (button == this.markBookmarksButton) {
            return "markBookmarks";
        }
        if (button == this.quickMarkButton) {
            return "quickMark";
        }
        if (button == this.voidModeButton) {
            return "voidMode";
        }
        if (button == this.megaCutoffButton) {
            return "megaCutoff";
        }
        if (button == this.ecoBulkCutoffButton) {
            return "ecoBulkCutoff";
        }
        if (button == this.ecoBulkCutoffSwitchButton) {
            return "ecoBulkCutoffSwitch";
        }
        if (button == this.ecoBulkConsolidateButton) {
            return "ecoBulkConsolidate";
        }
        return null;
    }

    /** 每帧一次：复制模式回显 + 五组按钮的显隐同步（顺序与屏幕原来逐行调的完全一致）。 */
    void syncAll(boolean copyModeClear) {
        // 复制模式回显：与元件工作台同一个图标、同一个极性（亮 = 取出元件时清空配置格，即 CLEAR_ON_REMOVE）。
        this.copyModeButton.setState(copyModeClear);
        // MEGA 那枚（装了才有）每帧对一次：显隐与图标跟着编码槽那张元件走。
        syncMegaCutoffButton();
        // ECO 大宗元件的两枚按钮同理。
        syncEcoBulkButtons();
        syncQuickMarkButton();
        syncMarkBookmarksButton();
        syncVoidModeButton();
    }

    // ---- 按钮动作 ----

    /**
     * MEGA 那枚按钮按下：把它看到的元件与「是否反向」报给服务端。
     *
     * <p>反向就是右键（AE2 那套：右键当左键转发）。按钮自己的 {@code isHandlingRightClick()} 是受保护的、
     * 拿不到，所以退一步用 Shift／右键的常规语义取。</p>
     */
    private void onMegaCutoffPressed(Button button) {
        var stack = this.ctx.encodeStack().get();
        if (stack.isEmpty()) {
            return;
        }
        boolean reverse = Screen.hasShiftDown();
        this.ctx.cycleCompressionCutoff().accept(reverse);
    }

    /** ECO 截断物按钮：左键降一级、右键升一级（与 MEGA 那枚同一手势）。 */
    private void onEcoBulkCutoffPressed(Button button) {
        int index = indexOfSelectedEcoMarker();
        if (index < 0) {
            return;
        }
        var selected = ecoBulkMarkers.get(index);
        if (!selected.compressible()) {
            // 按钮只在可压缩链上出现；这里仍守住旧语义，防止选中态在刷新间隙落到单级链上。
            return;
        }
        this.ctx.cycleEcoBulkCutoff().accept(selected.slot(),
                this.ctx.handlingRightClick().getAsBoolean() ? 1 : -1);
    }

    /** 切换当前操作的标记：左键上一个，右键下一个，首尾循环。 */
    private void onEcoBulkCutoffSwitchPressed(Button button) {
        var compressible = compressibleEcoBulkMarkers();
        if (compressible.isEmpty()) {
            return;
        }
        int current = -1;
        for (int i = 0; i < compressible.size(); i++) {
            if (compressible.get(i).slot() == this.selectedEcoBulkSlot) {
                current = i;
                break;
            }
        }
        int delta = this.ctx.handlingRightClick().getAsBoolean() ? 1 : -1;
        current = Math.floorMod(current < 0 ? 0 : current + delta, compressible.size());
        this.selectedEcoBulkSlot = compressible.get(current).slot();
        updateEcoBulkButtons();
    }

    /**
     * 「标记收藏」：把 JEI / EMI 收藏夹里的物品追加到编码槽那张元件的标记里。
     *
     * <p>收藏夹只在客户端有，所以读在这儿、写在那边的服务端（见菜单的 {@code markBookmarks}）。</p>
     */
    private void onMarkBookmarksPressed(Button button) {
        var bookmarks = BookmarkReader.read();
        this.ctx.markBookmarks().accept(bookmarks.items(), bookmarks.nonItems());
    }

    /** 「快捷标记」：整个标记管理域的分配在服务端算，这里只发一个无参动作。 */
    private void onQuickMarkPressed(Button button) {
        this.ctx.quickMark().run();
    }

    /** 「物质聚合模式」：往前循环，Shift 往回（与 MEGA 那枚同一手势）。 */
    private void onVoidModePressed(Button button) {
        this.ctx.cycleVoidMode().accept(Screen.hasShiftDown());
    }

    /** 模式按钮当前该画哪一档；EAE 不在场或取不到时回到第一档，至少不会画空。 */
    private Blitter voidModeIcon() {
        var stack = this.ctx.encodeStack().get();
        int mode = VoidCellCompat.modeOrdinal(stack);
        return ICON_VOID_MODES[Math.clamp(mode, 0, ICON_VOID_MODES.length - 1)];
    }

    // ---- 每帧显隐同步 ----

    /**
     * 「快捷标记」的显隐：只在元件编码槽空着时出现。
     *
     * <p>编码槽里放着元件时，玩家是在编辑那一张；这两枚按钮动的却是网络里所有目标元件，摆在一起容易误按。
     * 「统一压缩管理」同口径，见 {@link #updateEcoBulkButtons()}。</p>
     */
    private void syncQuickMarkButton() {
        this.quickMarkButton.setVisibility(encodeSlotEmpty());
    }

    /** 元件编码槽里有没有元件：两枚全网批量按钮共用的判据。 */
    private boolean encodeSlotEmpty() {
        return this.ctx.encodeStack().get().isEmpty();
    }

    /**
     * 「标记收藏」的显隐：编码槽里得是张带标记区的元件（与「分区存储」「清除」同一个前提），而且真有个
     * 收藏夹模组在读——两个都没有时按下什么都不会发生，不如不显示。
     */
    private void syncMarkBookmarksButton() {
        this.markBookmarksButton.setVisibility(
                this.ctx.markerAreaEditable().getAsBoolean() && BookmarkReader.available());
    }

    /**
     * 「物质聚合模式」的显隐与提示：只在编码槽里是 EAE 的 ME 虚空元件时出现（模式本身在元件栈的组件上，
     * 不在按钮上，所以这里只负责显隐与把当前档写进提示）。
     */
    private void syncVoidModeButton() {
        var stack = this.ctx.encodeStack().get();
        boolean usable = VoidCellCompat.isVoidCell(stack);
        this.voidModeButton.setVisibility(usable);
        if (!usable) {
            this.lastVoidMode = -1;
            return;
        }
        int mode = VoidCellCompat.modeOrdinal(stack);
        if (mode == this.lastVoidMode) {
            return;
        }
        this.lastVoidMode = mode;
        this.voidModeButton.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.void_mode"),
                Component.translatable("gui.ae2_pattern_disk.cell_management_terminal.void_mode."
                        + VoidCellCompat.modeKey(mode))));
    }

    /**
     * 每帧把 MEGA 那枚按钮对到编码槽里那张元件上：不是大宗元件、或它没接压缩链时藏起来。
     *
     * <p>与 MEGA 自己在元件工作台上做的一样（它也是每帧读元件再决定显隐与图标），只是那一边读的是元件工作台
     * 的元件槽，这边读的是本终端的元件编码槽。</p>
     */
    private void syncMegaCutoffButton() {
        if (this.megaCutoffButton == null) {
            return;
        }
        var stack = this.ctx.encodeStack().get();
        // 快照没变 = 状态没变。省下每帧那一次 bulkInventoryOf：它会 new 一个 BulkCellInventory
        // （构造里就要读元件组件）再走几次反射。本屏另有每帧工作（行模型重建等），这里只动这一处。
        if (this.lastMegaCell != null && ItemStack.matches(this.lastMegaCell, stack)) {
            return;
        }
        this.lastMegaCell = stack.copy();
        var bulk = MegaCellsCompat.bulkInventoryOf(stack);
        boolean usable = bulk != null && MegaCellsCompat.hasCompressionChain(bulk);
        MegaCutoffButtonFactory.setVisible(this.megaCutoffButton, usable);
        if (usable) {
            MegaCutoffButtonFactory.setItem(this.megaCutoffButton, MegaCellsCompat.cutoffItem(bulk));
        }
    }

    /**
     * 每帧把 ECO 的三枚按钮对到编码槽里那张元件上。
     *
     * <p>标记列表随元件内容变（换元件、调级、别处改了这个元件），另外每 100 tick 重算一次：压缩链数据是
     * 客户端异步同步的，元件不变时也要能把刚到的链补上。选中槽在列表重建后仍按配置槽号保留。</p>
     *
     * <p>显隐：「统一压缩管理」只在 ECO 在场且编码槽空着时出现（与「快捷标记」同口径）；截断物与切换两枚
     * 只在编码槽那张元件有可压缩链时出现（不是大宗元件、或一条可压缩链都没有时，这两枚藏起来）。</p>
     */
    private void syncEcoBulkButtons() {
        if (this.ecoBulkCutoffButton == null || this.ecoBulkCutoffSwitchButton == null
                || this.ecoBulkConsolidateButton == null) {
            return;
        }
        var stack = this.ctx.encodeStack().get();
        var level = Minecraft.getInstance().level;
        long tick = level == null ? 0L : level.getGameTime();
        boolean periodic = tick != this.lastEcoBulkRefreshTick && tick % 100L == 0L;
        if (!periodic && this.lastEcoBulkCell != null && ItemStack.matches(this.lastEcoBulkCell, stack)) {
            return;
        }
        if (periodic) {
            this.lastEcoBulkRefreshTick = tick;
        }
        this.lastEcoBulkCell = stack.copy();
        var markers = CellBulkCutoffCompat.markers(stack.copy());
        this.ecoBulkMarkers = markers == null ? List.of() : markers;
        var compressible = compressibleEcoBulkMarkers();
        int selectedIndex = indexOfSelectedEcoMarker();
        // 选中位可能落在单级链上（链数据每 100 tick 刷新，压缩关系会变）：不在可压缩集合里就重挑。
        if (selectedIndex < 0 || !this.ecoBulkMarkers.get(selectedIndex).compressible()) {
            this.selectedEcoBulkSlot = compressible.isEmpty() ? -1 : compressible.get(0).slot();
        }
        updateEcoBulkButtons();
    }

    private int indexOfSelectedEcoMarker() {
        for (int index = 0; index < this.ecoBulkMarkers.size(); index++) {
            if (this.ecoBulkMarkers.get(index).slot() == this.selectedEcoBulkSlot) {
                return index;
            }
        }
        return -1;
    }

    private void updateEcoBulkButtons() {
        if (this.ecoBulkCutoffButton == null || this.ecoBulkCutoffSwitchButton == null
                || this.ecoBulkConsolidateButton == null) {
            return;
        }
        int index = indexOfSelectedEcoMarker();
        boolean compressible = index >= 0 && this.ecoBulkMarkers.get(index).compressible();
        // 与「快捷标记」同口径：编码槽空着才显示。这两枚都是全网批量动作，编码槽里放着元件时
        // 玩家是在编辑那一张，藏起来免得误按（快捷标记那枚见 syncQuickMarkButton）。
        this.ecoBulkConsolidateButton.setVisibility(
                CellBulkCutoffCompat.isAvailable() && encodeSlotEmpty());
        if (!compressible) {
            this.ecoBulkCutoffButton.setVisible(false);
            this.ecoBulkCutoffSwitchButton.setVisibility(false);
            return;
        }
        this.ecoBulkCutoffButton.setItem(this.ecoBulkMarkers.get(index).item());
        this.ecoBulkCutoffButton.setVisible(true);
        this.ecoBulkCutoffSwitchButton.setVisibility(true);
    }

    /** 属于多级压缩链的标记：只有这些能让截断物按钮有活干。 */
    private List<CellBulkCutoffCompat.Marker> compressibleEcoBulkMarkers() {
        var result = new ArrayList<CellBulkCutoffCompat.Marker>();
        for (var marker : this.ecoBulkMarkers) {
            if (marker.compressible()) {
                result.add(marker);
            }
        }
        return result;
    }

    /** 统一压缩管理：只整理网络里已装好的大宗矩阵，不碰元件编码槽。 */
    private void onEcoBulkConsolidatePressed(Button button) {
        this.ctx.consolidateEcoBulkMarkers().run();
    }

    /** 本模组那份 states.png 里的一格：16x16，坐标即 UV。 */
    private static Blitter states(int u, int v) {
        return Blitter.texture(net.minecraft.resources.ResourceLocation
                .parse("ae2_pattern_disk:textures/guis/states.png")).src(u, v, 16, 16);
    }
}
