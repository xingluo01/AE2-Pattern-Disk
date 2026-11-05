package io.github.lounode.ae2pattern.client.gui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.SettingToggleButton;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskManagementTermMenu;

/**
 * 管理终端表格的「按钮组 + 它们自己的视图状态」：显示模式、隐藏槽位、折叠虚拟样板、内容搜索栏与搜索范围
 * 五枚控件，以及 {@code hideEmptySlots} / {@code selectedSerial} / {@code searchScope} / {@code contentSearchText}
 * / {@code collapseVirtualPatterns} 这五个本地值和它们各自的服务端镜像。
 *
 * <p>从 {@code PatternDiskManagementTermScreen} 里整块搬出来：状态与「谁改它、谁回显它」放在同一个类里，
 * 不再让屏幕为了同步去放宽一堆 {@code private}。{@code updateBeforeRender} 里那段服务端镜像跟随、以及原先
 * 散在五个回调里的同一行 {@code sendViewStateToServer(...)}，都收在这里。</p>
 *
 * <p><b>不持有屏幕</b>：只注入菜单、控件容器、工具栏挂载回调、附加排序按钮与 {@code naturalSortEnabled}
 * 的取值回调。控件回显直接落在自己这几枚按钮字段上，不需要回调；{@code DiskTableWidgets} 只读这里五个
 * getter（不超过 5 个成员），随帧变化的值由这些 getter 现取。</p>
 *
 * <p><b>控件顺序即清单</b>：构造器里 {@code addToLeftToolbar} / {@code widgets.add*} 的先后与屏幕原样一致——
 * 它们决定渲染层级与输入命中，不能重排。</p>
 */
final class DiskToolbarWidgets {

    // 「显示槽位 / 隐藏槽位」按钮的图标：states.png (64,32) 四格 = 展开、扫(80,32) 单格 = 收起（就在「标准/极速」
    // 图标右侧、同一行，同一套 16×16 规格与配色）。按钮背景同本模组其他自绘按钮。
    private static final ResourceLocation STATES = ResourceLocation
            .parse("ae2_pattern_disk:textures/guis/states.png");
    private static final Blitter ICON_SHOW_SLOTS = Blitter.texture(STATES).src(64, 32, 16, 16);
    private static final Blitter ICON_HIDE_SLOTS = Blitter.texture(STATES).src(80, 32, 16, 16);
    // 「折叠虚拟样板」按钮的图标：states.png (160,32) 起 32×16 两块，左半 (160,32) 是关闭、右半 (176,32) 是开启。
    private static final Blitter ICON_COLLAPSE_PATTERNS_OFF = Blitter.texture(STATES).src(160, 32, 16, 16);
    private static final Blitter ICON_COLLAPSE_PATTERNS_ON = Blitter.texture(STATES).src(176, 32, 16, 16);
    private static final Blitter BUTTON_BG_NORMAL = Blitter.texture(STATES).src(208, 224, 18, 20);
    private static final Blitter BUTTON_BG_HOVER = Blitter.texture(STATES).src(226, 224, 18, 20);

    private final PatternDiskManagementTermMenu menu;
    private final WidgetContainer widgets;
    private final Consumer<Button> addToLeftToolbar;
    /** 回传视图状态时要带的「附加排序」现值（屏幕的 {@code naturalSortEnabled()}）。 */
    private final BooleanSupplier naturalSort;
    /** 父屏那枚附加排序按钮：服务端回推后由本类回显，不从屏幕反查。 */
    private final NaturalSortButton naturalSortButton;

    /** 「显示模式」按钮：与 AE2 样板访问终端共用同一个服务端设置。 */
    private final SettingToggleButton<ShowPatternProviders> showProvidersButton;
    /** 「隐藏槽位/显示槽位」按钮：只影响本屏的行模型，不涉服务端。按下时要回写状态，所以不是 final。 */
    private StatesToggleButton hideSlotsButton;
    /** 「折叠虚拟样板」按钮：状态随视图状态回传服务端，开屏由服务端镜像恢复。按下时要回写图标。 */
    private StatesToggleButton collapsePatternsButton;
    /** 样板内容搜索栏与它的搜索范围轮换按钮。 */
    private AETextField contentSearchField;
    private ContentSearchScopeButton contentSearchScopeButton;

    /** 当前是否收起空槽。默认收起：表的常规观感保持紧凑，想看全槽布局再展开。 */
    private boolean hideEmptySlots = true;
    /** 当前是否折叠虚拟样板。默认关闭＝正常展开每一行；打开时每张盘只留磁盘后的第一行样板。 */
    private boolean collapseVirtualPatterns = false;
    /** 上一次从服务端读到的折叠开关值，只用于「服务端变了才跟随」，免得客户端刚点完被旧镜像拉回去。 */
    private boolean lastServerCollapseVirtualPatterns = false;
    /** 上一次从服务端读到的值，只用于“服务端变了才跟随”，免得客户端刚点完就被自己的回推拉回去。 */
    private boolean lastServerHideEmptySlots = true;
    private long lastServerSelectedSerial;
    /** 样板内容搜索范围（新搜索栏）：本地副本 + 服务端上次给的值（只在它变了时跟随）。 */
    private io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope searchScope = io.github.lounode
            .ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope.BOTH;
    private io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope lastServerSearchScope = io.github.lounode
            .ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope.BOTH;
    /** 服务端上次给的「附加排序」开关值；它一变就说明该跟随服务端。 */
    private boolean lastServerNaturalSort = true;
    /** 服务端镜像的显示模式档位；只在它真的变了时跟随，免得本地点完那两帧被旧值拉回去。 */
    private ShowPatternProviders lastServerShownProviders = ShowPatternProviders.VISIBLE;
    /** 搜索栏里的文本；空白表示不过滤。 */
    private String contentSearchText = "";
    /**
     * 右键选中的磁盘（「编写样板」的写盘目标）；0 = 没选。
     *
     * <p>serial 从 {@code Long.MIN_VALUE} 起自增、恒为负（见菜单里的说明），所以 0 可以安全地当「没选」。</p>
     */
    private long selectedSerial;

    DiskToolbarWidgets(PatternDiskManagementTermMenu menu, WidgetContainer widgets,
            Consumer<Button> addToLeftToolbar, NaturalSortButton naturalSortButton,
            BooleanSupplier naturalSort) {
        this.menu = menu;
        this.widgets = widgets;
        this.addToLeftToolbar = addToLeftToolbar;
        this.naturalSortButton = naturalSortButton;
        this.naturalSort = naturalSort;

        // 「显示模式」的取值与 AE2 样板访问终端同名同义（Settings.TERMINAL_SHOW_PATTERN_PROVIDERS），但**不走
        // AE2 的服务端设置**：那项一旦注册进宿主配置管理器，AE2 的 MEStorageMenu 开屏时就会遍历到它、再去它自己
        // 那份客户端设置（只认排序/视图三项）取同名项而抛 UnsupportedSettingException，首帧同步发不出去，
        // 终端打开后空无一物。档位由本模组的编码逻辑持有（面板存部件 NBT、无线存物品组件），
        // 按钮只负责把「翻了一档」报给服务端（随 patternDiskViewState 一起回传）。
        // 图标与提示仍由 AE2 的 SettingToggleButton 静态注册表按「设置 + 档位」提供，本模组不需自带资源。
        this.showProvidersButton = new SettingToggleButton<>(Settings.TERMINAL_SHOW_PATTERN_PROVIDERS,
                ShowPatternProviders.VISIBLE, (button, backwards) -> {
                    var next = button.getNextValue(backwards);
                    button.set(next);
                    sendToServer(next);
                });
        addToLeftToolbar.accept(this.showProvidersButton);

        // 「隐藏槽位」只改客户端的行模型（空槽数由服务端在分组里带出），所以是本屏自己的开关。状态由本屏维护
        // 并回写，不依赖按钮自身的翻转时机——按钮只负责把“按了一下”告诉监听器，图标由 setState 决定。
        // 状态为 true（收起）时画单格图标，为 false（展开）时画四格图标，与「标准/极速」的排列一致。
        this.hideSlotsButton = new StatesToggleButton(ICON_HIDE_SLOTS, ICON_SHOW_SLOTS, state -> {
            this.hideEmptySlots = !this.hideEmptySlots;
            this.hideSlotsButton.setState(this.hideEmptySlots);
            // 回传：状态本体在服务端的编码逻辑里（面板写部件 NBT、无线写物品组件），不回传就不会被记住。
            sendToServer();
        });
        this.hideSlotsButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.hideSlotsButton.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.management_terminal.hide_slots"),
                Component.translatable("gui.ae2_pattern_disk.management_terminal.hide_slots_hint")));
        this.hideSlotsButton.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.management_terminal.show_slots"),
                Component.translatable("gui.ae2_pattern_disk.management_terminal.show_slots_hint")));
        this.hideSlotsButton.setState(this.hideEmptySlots);
        addToLeftToolbar.accept(this.hideSlotsButton);

        // 「折叠虚拟样板」排在「隐藏槽位」正下方：只改本屏的行模型（要不要生成续行），但状态随视图状态
        // 回传服务端（面板存部件 NBT、无线存物品组件），关屏重开才不会回到「正常展开」。图标左半关闭、右半开启。
        this.collapsePatternsButton = new StatesToggleButton(ICON_COLLAPSE_PATTERNS_ON,
                ICON_COLLAPSE_PATTERNS_OFF, state -> {
                    this.collapseVirtualPatterns = !this.collapseVirtualPatterns;
                    this.collapsePatternsButton.setState(this.collapseVirtualPatterns);
                    sendToServer();
                });
        this.collapsePatternsButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);
        this.collapsePatternsButton.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.management_terminal.collapse_virtual_patterns"),
                Component.translatable("gui.ae2_pattern_disk.management_terminal.collapse_virtual_patterns.on")));
        this.collapsePatternsButton.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.management_terminal.collapse_virtual_patterns"),
                Component.translatable("gui.ae2_pattern_disk.management_terminal.collapse_virtual_patterns.off")));
        this.collapsePatternsButton.setState(this.collapseVirtualPatterns);
        addToLeftToolbar.accept(this.collapsePatternsButton);

        // 样板内容搜索栏：顶部居中、与磁盘搜索栏同一行（样式 JSON 的 contentSearch 给坐标），
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
            sendToServer();
        });
        widgets.add("contentSearchScope", this.contentSearchScopeButton.widget());
    }

    /** 本屏多出来、需要自己认领的两枚工具栏按钮：隐藏槽位、折叠虚拟样板。 */
    @Nullable
    String toolbarSlot(Button button) {
        if (button == this.hideSlotsButton) {
            return "hideSlots";
        }
        return button == this.collapsePatternsButton ? "collapsePatterns" : null;
    }

    /** 把本地视图状态回传服务端（原先散在各按钮回调与点击里的同一行）。 */
    void sendToServer() {
        sendToServer(this.menu.shownProviders());
    }

    /** 显示模式按钮换档时用它：末位档位传刚翻到的那个值，不取服务端镜像。 */
    private void sendToServer(ShowPatternProviders shownProviders) {
        this.menu.sendViewStateToServer(this.hideEmptySlots, this.selectedSerial, this.searchScope,
                this.naturalSort.getAsBoolean(), shownProviders, this.collapseVirtualPatterns);
    }

    /**
     * 服务端状态回显：显示模式按钮的档位回显——换档后服务端会重推分组清单，档位跟着清单回来。与下面几枚同一口径——
     * 只在服务端镜像真的变了时跟随；否则本地点完刚翻过去的图标会被这一帧的旧镜像拉回来。
     */
    void syncFromServer() {
        if (this.lastServerShownProviders != menu.shownProviders()) {
            this.lastServerShownProviders = menu.shownProviders();
            this.showProvidersButton.set(this.lastServerShownProviders);
        }
        // 服务端那份才是权威（面板存部件 NBT、无线存物品组件）。只在它真的变了时跟随，开屏与刷新都能恢复上次的值。
        if (this.lastServerHideEmptySlots != menu.viewHideEmptySlots()) {
            this.lastServerHideEmptySlots = menu.viewHideEmptySlots();
            this.hideEmptySlots = this.lastServerHideEmptySlots;
            this.hideSlotsButton.setState(this.hideEmptySlots);
        }
        // 「折叠虚拟样板」同一口径：只在服务端镜像真的变了时跟随，开屏恢复上次的值，本地刚点的那两帧不被拉回。
        if (this.lastServerCollapseVirtualPatterns != menu.viewCollapseVirtualPatterns()) {
            this.lastServerCollapseVirtualPatterns = menu.viewCollapseVirtualPatterns();
            this.collapseVirtualPatterns = this.lastServerCollapseVirtualPatterns;
            this.collapsePatternsButton.setState(this.collapseVirtualPatterns);
        }
        if (this.lastServerSelectedSerial != menu.viewSelectedSerial()) {
            this.lastServerSelectedSerial = menu.viewSelectedSerial();
            this.selectedSerial = this.lastServerSelectedSerial;
        }
        if (this.lastServerSearchScope != menu.viewSearchScope()) {
            this.lastServerSearchScope = menu.viewSearchScope();
            this.searchScope = this.lastServerSearchScope;
            if (this.contentSearchScopeButton != null) {
                this.contentSearchScopeButton.setScope(this.searchScope);
            }
        }
        // 「附加排序」开关：服务端值变了就跟随（开屏恢复）；没变而本地与它不同，说明是玩家刚点的，回传。
        if (this.lastServerNaturalSort != menu.viewNaturalSort()) {
            this.lastServerNaturalSort = menu.viewNaturalSort();
            if (this.naturalSortButton != null) {
                this.naturalSortButton.setEnabled(this.lastServerNaturalSort);
            }
        } else if (this.naturalSortButton != null
                && this.naturalSortButton.isEnabled() != this.lastServerNaturalSort) {
            menu.sendViewStateToServer(this.hideEmptySlots, this.selectedSerial, this.searchScope,
                    this.naturalSortButton.isEnabled(), menu.shownProviders(), this.collapseVirtualPatterns);
        }
    }

    /** 右键选中的那张盘被取走、或搜索把它筛出去了，选择跟着失效。 */
    void validateSelection(Predicate<Long> stillPresent) {
        if (selectedSerial != 0 && !stillPresent.test(selectedSerial)) {
            selectedSerial = 0;
        }
    }

    // ---- 只读取值：给 DiskTableWidgets 与屏幕的动作分发用 ----

    boolean hideEmptySlots() {
        return this.hideEmptySlots;
    }

    /** 是否折叠虚拟样板：打开时每张盘只显示磁盘后的第一行样板，续行不生成。 */
    boolean collapseVirtualPatterns() {
        return this.collapseVirtualPatterns;
    }

    io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope searchScope() {
        return this.searchScope;
    }

    String contentSearchText() {
        return this.contentSearchText;
    }

    long selectedSerial() {
        return this.selectedSerial;
    }

    /** 右键选中盘：再点同一张就取消（0 = 没选）。 */
    void toggleSelectedSerial(long serial) {
        this.selectedSerial = this.selectedSerial == serial ? 0 : serial;
    }

    /** 内容搜索栏的右键清空手势命中判定。 */
    boolean isContentSearchFieldOver(double x, double y) {
        return this.contentSearchField.isMouseOver(x, y);
    }

    /** 内容搜索栏右键清空：控件值与过滤文本成对写（与 AE2 顶部搜索框同款口径）。 */
    void clearContentSearch() {
        this.contentSearchField.setValue("");
        this.contentSearchText = "";
    }
}
