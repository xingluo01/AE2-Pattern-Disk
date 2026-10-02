package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.Map;
import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;
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
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.ActionButton;
import appeng.core.AELog;
import appeng.core.localization.ButtonToolTips;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.SlotSemantics;
import appeng.parts.encoding.EncodingMode;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.client.integration.MachineRecipeTypes;
import io.github.lounode.ae2pattern.client.sort.NaturalSort;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.DiskMarkRules;
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
 *
 * <p>类上的 {@code @IPNPlayerSideOnly} 是整理模组（Inventory Profiles Next）给模组作者留的官方注解，
 * 意思是「这个屏幕只整理玩家背包」。不标的话它把本模组的屏幕当「未知容器」——菜单里每一个槽都算可整理
 * 存储，于是无线版的升级卡槽、以及只有「编码」才填、玩家本就取不走的样板输出槽，都会被整理搬到光标上
 * （槽的限制写在 {@code mayPickup} 一侧，而整理走的是直接换位，不经过那道判断）。</p>
 *
 * <p>这个注解没有 {@code @Inherited}，父类上的标注不会落到子类，所以四个终端屏各标一份。新增终端屏时除了
 * 在类上带注解，还要把它加进 {@code AE2PatternDiskClient.clientSetup} 里那份自检清单——漏标时启动日志
 * 会报出屏名。</p>
 */
@IPNPlayerSideOnly
public class PatternDiskEncodingTermScreen extends MEStorageScreen<PatternDiskEncodingTermMenu>
        implements NaturalSort.Provider {

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

    // states.png (64,16,16,16)：高级编码模式的档位图标。装上高级样板编码器后，它才会进模式循环。
    private static final Blitter ICON_ADVANCED = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(64, 16, 16, 16);

    // states.png (80,16,16,16)：雕凿编码模式的档位图标（紧接上一格的 16px 步长）。装上雕凿样板编码器后才会进循环。
    private static final Blitter ICON_CHISELING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(80, 16, 16, 16);

    // states.png (96,16,16,16)：过载编码模式的档位图标（同上一步长）。装上 AE2 Lightning Tech 后才会进循环。
    private static final Blitter ICON_OVERLOADED = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(96, 16, 16, 16);

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

    /** 额外档注册表（构造器里建好，之后只读）。加一档就在这里加一项。 */
    private final List<ExtraTier> extraTiers;
    /** 高级编码模式面板。它不在 {@link EncodingMode} 里（那个枚举不可扩展），所以单拎一份。 */
    private final AdvancedEncodingPanel advancedPanel;

    /** 雕凿档面板；没装 Rechiseled 或 RechiseledAE2 时为 null（那一档也就不会进轮换）。 */
    @Nullable
    private ChiselingEncodingPanel chiselingPanel;

    /** 过载档面板；没装 AE2 Lightning Tech 时为 null（那一档也就不会进轮换）。 */
    @Nullable
    private OverloadedEncodingPanel overloadedPanel;
    private final DiskListPanel diskListPanel;
    protected final ModeDropdownButton modeButton;

    /** 上次写进模式选择钮的 tooltip 输入；变了才重建那几行文本，不必每帧新建。 */
    private EncodingMode tooltipMode;

    /**
     * 上次提示语用的是哪个额外档（{@link #activeTier()} 的序号，-1 = 常规档）。
     *
     * <p>与 {@link #tooltipMode} 一起决定何时重写提示语。以前是每档一个 boolean（高/雕两个），
     * 加第三档就得再加一个；现在收成一个序号，加档不用动这里。</p>
     */
    private int tooltipTier = -1;

    /**
     * 附加排序开关：按 mod 排序时出现的二级排序，默认开。按钮贴在 AE2 那枚「排序按」后面，只在
     * 「按 mod」那一档显示；状态与提示语由 {@link NaturalSortButton} 自己管，本屏只负责把它摆上去、
     * 每帧转达当前排序档位。
     */
    protected NaturalSortButton naturalSortButton;
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

    /** 无标记磁盘的显示开关；每帧回写状态，否则点下去图标不会变。状态本体在菜单字段（服务端从宿主 logic 回读）。 */
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

        // 高级编码模式：AE2 的 EncodingMode 没有它，单独挂一份，可见性跟着菜单的 advancedMode。
        //
        // 这份控件得出现在会构造本屏幕类的样式文档里（编码终端、管理终端各一份，两个无线版由 include 继承）
        // —— AE2 对缺失的键不是「不画」，而是在开界面的那一刻抛异常，整个屏幕打不开。
        // ExtendedAE Plus 的上传子屏继承本类、却带着它自己的样式文档，所以这里容一手：挂不上就算了。
        this.advancedPanel = new AdvancedEncodingPanel(this, widgets);
        try {
            widgets.add("advancedPanel", this.advancedPanel);
        } catch (IllegalStateException e) {
            AELog.debug("Screen style has no 'advancedPanel' widget; the advanced encoding panel is not shown");
        }

        // 雕凿面板：外观继承切石，但内容建在 Rechiseled 的配方表上，所以它缺席时不挂（那一档也就不出现）。
        this.chiselingPanel = createChiselingPanel(widgets);
        if (this.chiselingPanel == null) {
            // 面板缺席（没装 Rechiseled、样式缺键、或构造抛异常被吞）时，没人会去隐藏那个输入槽——
            // 它会停在样式坐标上，成为一个看不见却能被 JEI/EMI 幽灵拖放写进的假槽。先藏起来；
            // 真装上了面板，它每帧的 setVisible 会在雕凿档里把它重新显示出来。
            setSlotsHidden(AEPatternRegistries.CHISELING_INPUT, true);
        }

        // 过载面板：与雕凿同款的条件挂载。它不占自己的槽位（面板上的行就是编辑区那些槽），
        // 所以没有上面那段「面板缺席就先把槽藏起来」的顾虑。
        this.overloadedPanel = createOverloadedPanel(widgets);

        // 额外档注册表：图标/名称/面板/可用性/是否开/怎么开，五样各一处。下拉列表、互斥、
        // 可见性、常规档高亮全从它派生（见 docs/ENCODING_MODES.md）。
        // 面板在构造器里已全部建好，所以这张表可以一次定下来；表里的 available/active 是
        // Supplier，每帧重新求值，不会因为菜单字段变化而变旧。
        this.extraTiers = List.of(
                new ExtraTier(ICON_ADVANCED,
                        "gui.ae2_pattern_disk.encoding_terminal.advanced_mode",
                        null,
                        PatternDiskItem.ADVANCED_PROCESSING_PATTERN,
                        this.advancedPanel,
                        () -> getMenu().advancedModeAvailable,
                        () -> getMenu().advancedMode,
                        getMenu()::setAdvancedMode),
                new ExtraTier(ICON_CHISELING,
                        "gui.ae2_pattern_disk.encoding_terminal.chiseling_mode",
                        DiskMarkRules.CHISELING_MARK,
                        PatternDiskItem.CHISELING_PATTERN,
                        this.chiselingPanel,
                        () -> getMenu().chiselingModeAvailable
                                && PatternDiskEncodingTermMenu.CHISELING_TIER_ENABLED,
                        () -> getMenu().chiselingMode,
                        getMenu()::setChiselingMode),
                new ExtraTier(ICON_OVERLOADED,
                        "gui.ae2_pattern_disk.encoding_terminal.overloaded_mode",
                        null,
                        PatternDiskItem.OVERLOAD_PATTERN,
                        this.overloadedPanel,
                        () -> getMenu().overloadedModeAvailable,
                        () -> getMenu().overloadedMode,
                        getMenu()::setOverloadedMode));

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
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.disk_search.mark_hint"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.disk_search.clear_hint")));
        this.miniSearchField.setResponder(text -> diskListPanel.setSearchText(text));

        // 编码模式选择钮（左侧工具栏）：按本体展开一列档位，点其中一个直接切过去——不再循环轮换，
        // 档位一多那种「点几下才到」的交互就难用了。可选列表每帧现算（哪些档可用要看菜单字段）。
        this.modeButton = new ModeDropdownButton(
                this::currentModeIcon,
                this::modeChoices);
        this.modeButton.setBackground(BG_MODE_NORMAL, BG_MODE_HOVER);
        addToLeftToolbar(this.modeButton);
        // 提示语每帧回写（它报的是当前所属的配方类型），这里不设死文本。

        // 二级排序开关：贴在 AE2 那三枚排序按钮后面，只在「按 mod」那一档显示（见 updateBeforeRender）。
        this.naturalSortButton = new NaturalSortButton(() -> {
            // 比较器换了要重排一遍；updateView 是 AE2 自己换排序档位后走的同一条路。
            repo.updateView();
        });
        addToLeftToolbar(this.naturalSortButton.widget());

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
     * 是否给这个屏幕挂 NEO ECO 的上传按钮（neoecoae 在场时才真的出现）。
     *
     * <p>管理终端走标记路线，不提供上传入口——它不把样板上传到网络。ECO 与 ExtendedAE Plus 两条口径必须
     * 一致：EAE+ 那条挂在编码类型下，管理终端天然不会拿到；ECO 这条由本钩子挡掉。</p>
     */
    protected boolean usesNeoEcoUploadButton() {
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

    /**
     * 造雕凿面板并挂上。任一前置缺席、或样式文档里没有那一格时返回 null——面板缺席就等于那一档不存在，
     * 这是它的正常降级形态（与高级编码器缺席时高级档不出现同一个口径）。
     *
     * <p>逮 {@code Throwable} 而不是 {@code IllegalStateException}：除了「样式文档缺键」（那种是前者，
     * 另一处 try/catch 同款），还可能碰上前置在位但版本对不上导致的类链接失败——那时候只该丢掉这一个面板，
     * 不该带崩整个终端。</p>
     */
    @Nullable
    private ChiselingEncodingPanel createChiselingPanel(WidgetContainer widgets) {
        if (!net.neoforged.fml.ModList.get().isLoaded("rechiseled")
                || !net.neoforged.fml.ModList.get().isLoaded("rechiseledae")) {
            return null;
        }
        try {
            var panel = new ChiselingEncodingPanel(this, widgets);
            widgets.add("chiselingPanel", panel);
            return panel;
        } catch (Throwable t) {
            AELog.debug("Could not set up the chiseling encoding panel: %s", t.toString());
            return null;
        }
    }

    /**
     * 过载档面板。与高级面板同款：**无条件**创建（档位可不可用由升级槽里那枚编码器决定，那是每 tick
     * 算的菜单字段，不是建屏时能定的），样式文档缺键就吞掉、面板不显示。
     */
    @Nullable
    private OverloadedEncodingPanel createOverloadedPanel(WidgetContainer widgets) {
        try {
            var panel = new OverloadedEncodingPanel(this, widgets);
            widgets.add("overloadedPanel", panel);
            return panel;
        } catch (Throwable t) {
            AELog.debug("Could not set up the overloaded encoding panel: %s", t.toString());
            return null;
        }
    }

    @Override
    public void init() {
        super.init();
        // 磁盘搜索栏不抢开局焦点：它是磁盘列表的局部筛子，先看列表再决定要不要搜。
        // （AE2 自己的容器搜索框拿不拿焦点仍由父类决定，本屏不干预。）
        this.miniSearchField.setFocused(false);
        var search = this.miniSearchField;
        if (usesNeoEcoUploadButton()) {
            var ecoUpload = neoEcoUploadButtonBounds();
            io.github.lounode.ae2pattern.client.integration.neoecoae.NeoECOClientIntegration.addUploadButtonIfPresent(this,
                    ecoUpload.getX(), ecoUpload.getY());
        }

        // 无标记磁盘的显示开关，贴在搜索栏右边 2px（搜索栏的可见宽度含内边距，所以要用它的 tooltip 区域），
        // 与它同高：搜索栏高 8，按钮也是 8x8，顶对齐即居中。
        var showUnmarked = new StatesToggleButton(ICON_SHOW_UNMARKED_ON, ICON_SHOW_UNMARKED_OFF,
                menu::setShowUnmarkedDisks);
        showUnmarked.setHalfSize(true);
        // 不要 hover 下压动画：开关的两种状态对应同一枚图标，悬停时下移 1px 会让它看起来在跳。
        showUnmarked.setPressAnimation(false);
        var searchArea = search.getTooltipArea();
        showUnmarked.setX(searchArea.getX() + searchArea.getWidth() + 2);
        showUnmarked.setY(search.getY());
        showUnmarked.setState(menu.isShowUnmarkedDisks());
        showUnmarked.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.show_unmarked.on")));
        showUnmarked.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.show_unmarked.off")));
        addRenderableWidget(showUnmarked);
        this.showUnmarkedButton = showUnmarked;

        orderToolbar();
    }

    /**
     * 工具栏顺序：附加排序贴着 AE2 那枚「排序按」，本模组自己的模式轮换按钮排到 AE2 自带的之后。
     * 子屏（管理终端）会再把自家那两枚排到模式轮换之前。
     */
    private void orderToolbar() {
        var sortBy = NaturalSortButton.findSortByButton(this);
        if (sortBy != null) {
            ToolbarOrder.placeAfter(this, naturalSortButton.widget(), sortBy);
        }
        ToolbarOrder.placeAtEnd(this, modeButton);
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

    /** 常规四档的图标。 */
    private static Blitter iconFor(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> ICON_CRAFTING;
            case PROCESSING -> ICON_PROCESSING;
            case SMITHING_TABLE -> ICON_SMITHING;
            case STONECUTTING -> ICON_STONECUTTING;
        };
    }

    /**
     * 选择钮展开列表的内容。两个额外档排在四个常规档后面（高级在前、雕凿在后），与它们的可用性判断放在一起
     * —— 与下面给面板做可见性判断的地方用同一组条件。
     */
    /**
     * 一个额外档的全部声明。顺序 = 下拉列表里额外档的排列顺序。
     *
     * <p>{@code panel} 为 null 时该档不进下拉（面板缺席）；{@code available}/{@code active} 用
     * Supplier 而不是即时值，因为它们是菜单字段，每帧都可能在变（升级槽里的卡被拿走、服务端回读等）。</p>
     */
    private record ExtraTier(
            Blitter icon,
            String nameKey,
            /**
             * 该档的专属标记字面量，**匹配时**用它跟磁盘标记比。
             *
             * <p>{@code null} = 该档没有专属标记：匹配时**不认标记**，只认类型锁（见 {@link #matchesCurrentType}）。
             * 别把它读成「继承进入本档之前那个常规档的标记」——那是**写盘侧**的行为（{@code deriveMarkId} 读
             * {@code menu.mode}，而额外档不改 mode），两侧不一样：高级/过载档下 {@code mode} 停在进入本档
             * 之前那个常规档，拿它去匹盘会把两种处理类样板引到错误的匹配项上。
             * 详见 {@code docs/ENCODING_MODES.md} §七与 {@code docs/ARCHITECTURE.md}。</p>
             */
            @Nullable String ownMark,
    /**
     * 该档编出的样板物品 id，也就是磁盘类型锁里存的那个串（{@code PatternDiskContents.type}）。
     *
     * <p>用来判「这张盘固定住的类型就是当前档要编的那一类」——那比标记硬：写进去走的
     * {@code PatternDiskContents.acceptsType} 就是同一个比较，类型锁相同就一定收得下。
     * 三个额外档各有各的样板物品；常规四档不用填，它们的 id 由模式推得（见
     * {@code DiskMarkRules.patternTypeForMode}）。</p>
     *
     * <p>值全部取自 {@code PatternDiskItem} 那组常量——那里是类型的单一事实源（“类型 ↔ tooltip
     * 名字”那张表也用它），不要在这里另写一遍字面量。</p>
     */
            @Nullable String patternTypeId,
            @Nullable DiskEncodingModePanel panel,
            BooleanSupplier available,
            BooleanSupplier active,
            Consumer<Boolean> setter) {
    }

    /** 当前开着的是第几个额外档；都没开时返回 -1（即停在常规档）。 */
    private int activeTier() {
        for (int i = 0; i < this.extraTiers.size(); i++) {
            if (this.extraTiers.get(i).active().getAsBoolean()) {
                return i;
            }
        }
        return -1;
    }

    /** 模式钮上画哪个图标：停在额外档就用那一档的图标，否则用常规档的。 */
    private Blitter currentModeIcon() {
        var tier = activeTier();
        return tier >= 0 ? this.extraTiers.get(tier).icon() : iconFor(getMenu().getMode());
    }

    private List<ModeDropdownButton.Choice> modeChoices() {
        var currentMode = getMenu().getMode();
        // 常规档是否高亮：只要**没有任何**额外档开着。不要展开成 !a && !b && !c——加一档必漏，
        // 漏了就不是「哪个高亮」的问题：过载档下常规档会同时显示选中，看上去像两档叠着开。
        var regular = !anyExtraTierActive();

        var choices = new ArrayList<ModeDropdownButton.Choice>();
        for (var mode : EncodingMode.values()) {
            choices.add(new ModeDropdownButton.Choice(
                    iconFor(mode),
                    modeName(mode),
                    regular && currentMode == mode,
                    () -> pickMode(mode)));
        }
        // 额外档在四个常规档后面，顺序就是 extraTiers 里的顺序；面板缺席或当前不可用的不进列表。
        for (int i = 0; i < this.extraTiers.size(); i++) {
            var tier = this.extraTiers.get(i);
            if (tier.panel() == null || !tier.available().getAsBoolean()) {
                continue;
            }
            var index = i;
            choices.add(new ModeDropdownButton.Choice(
                    tier.icon(),
                    Component.translatable(tier.nameKey()),
                    tier.active().getAsBoolean(),
                    () -> pickExtraTier(index)));
        }
        return choices;
    }

    /**
     * 直接点到某个常规档。
     *
     * <p>档位与那两个额外开关要在**同一帧内**全部落定，否则屏幕会看见一个中间态：{@code mode} 是
     * {@code @GuiSync} 的服务端权威字段，{@code setMode} 在客户端只发包、不本地改，要等一个往返才切过去；
     * 而 {@code advancedMode} / {@code chiselingMode} 在客户端是立即生效的。两者不同步，从额外档切到常规档
     * 时就会先回落到上一次的常规档去（等往返才到目标），看起来像「闪了一下」。所以最后补一句本地赋值。
     * AE2 自己不必补，是因为它四个档全走 {@code mode}，切换只是单次延迟、不存在中间态。</p>
     *
     * <p>赋值必须在 {@code setMode} <b>之后</b>：那个方法内部要读 {@code this.mode != mode} 来决定是否
     * 重算切石配方，先改本地值会把那一步跳过。</p>
     */
    private void pickMode(EncodingMode mode) {
        closeAllExtraTiers();
        getMenu().setMode(mode);
        // 等一个往返才切过去的话，从额外档回来时会先回落到上一次的常规档，中间那一下看着像「闪了一下」。
        getMenu().mode = mode;
    }

    /**
     * 切到某一个额外档。
     *
     * <p>互斥由 {@link #closeAllExtraTiers()} 统一收：**不要**在这一档里手动去清另外两个
     * ——那是 O(n²)，加一档必漏（上一版就漏了回常规档与进高级档两处，前者让编辑区一片空白、
     * 后者让两块同坐标的面板叠画）。</p>
     */
    private void pickExtraTier(int index) {
        closeAllExtraTiers();
        this.extraTiers.get(index).setter().accept(true);
    }

    /** 收掉所有开着的额外档。只对真正开着的那个发关闭，免得平白多发一次客户端动作。 */
    private void closeAllExtraTiers() {
        for (var tier : this.extraTiers) {
            if (tier.active().getAsBoolean()) {
                tier.setter().accept(false);
            }
        }
    }

    /** 当前是否停在某个额外档上（常规档的高亮与可见性都看它）。 */
    private boolean anyExtraTierActive() {
        return activeTier() >= 0;
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();

        // 开关状态每帧从菜单回写：权威值在部件的 logic 里，服务端每 tick 把它回读进菜单字段。
        if (this.showUnmarkedButton != null) {
            this.showUnmarkedButton.setState(menu.isShowUnmarkedDisks());
        }

        // 附加排序开关：只在「按 mod」时露面（其它档位下它没有意义），提示语里报的是当前状态。
        if (this.naturalSortButton != null) {
            this.naturalSortButton.update(getSortBy());
        }

        // 模式选择钮的提示语：第一行就是它现在所处的档位，第二行说明点它会发生什么。
        // 额外档不在 EncodingMode 里，但它们同样是一次「当前在哪儿」，所以一并跟。
        var mode = menu.getMode();
        var tierIndex = activeTier();
        if (mode != this.tooltipMode || tierIndex != this.tooltipTier) {
            this.tooltipMode = mode;
            this.tooltipTier = tierIndex;
            this.modeButton.setTooltip(List.of(
                    tierIndex >= 0
                            ? Component.translatable(this.extraTiers.get(tierIndex).nameKey())
                            : modeName(mode),
                    Component.translatable("gui.ae2_pattern_disk.encoding_terminal.mode_cycle")));
        }

        // 根据当前模式切换面板可见性。额外档是并列的：任一开着时四个常规面板全让位。
        var currentMode = menu.getMode();
        var regular = !anyExtraTierActive();
        for (var entry : modePanels.entrySet()) {
            entry.getValue().setVisible(regular && entry.getKey() == currentMode);
        }
        for (var tier : this.extraTiers) {
            if (tier.panel() != null) {
                tier.panel().setVisible(tier.active().getAsBoolean());
            }
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
                miniSearchField.setValue(DiskEntryFilter.markSearchTerm("#" + imported));
            }
        }

        updateDiskEntries();
    }

    /**
     * 「数值排序」当前是否打开；物品网格的比较器（{@code KeySortersMixin}）只认这一处，
     * 屏幕一关就跟着失效。子屏（管理终端）共用本屏的开关。
     */
    @Override
    public boolean naturalSortEnabled() {
        return this.naturalSortButton != null && this.naturalSortButton.isEnabled();
    }

    /** 当前所属的配方类型名；模式轮换按钮拿它当提示语的第一行。 */
    private static Component modeName(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> Component.translatable("ae2_pattern_disk.tooltip.type.crafting");
            case PROCESSING -> Component.translatable("ae2_pattern_disk.tooltip.type.processing");
            case SMITHING_TABLE -> Component.translatable("ae2_pattern_disk.tooltip.type.smithing");
            case STONECUTTING -> Component.translatable("ae2_pattern_disk.tooltip.type.stonecutting");
        };
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
        var query = DiskEntryFilter.Query.of(diskListPanel.getSearchText());
        if (query.isActive()) {
            diskEntries.removeIf(d -> !DiskEntryFilter.keep(d, query, menu.isShowUnmarkedDisks()));
        }

        // 没有搜索条件时什么都不剔除（含无标记的空盘）——规则写在上面那段注释里。

        // 按显示名排序
        diskEntries.sort(Comparator.comparing(DiskEntry::displayName));

        // 传给面板
        diskListPanel.setDiskEntries(List.copyOf(diskEntries));

        // 编码按钮要不要直接落盘。这一步必须等过滤做完：玩家点按钮时看到的就是这份列表，早一帧算出来就可能
        // 把目标算成此刻已经看不到的那张盘。
        // ① 搜索栏筛过盘：按列表顺位写（沿用原语义）；
        // ② 没搜索：不再「随手塞第一张」，但若是符合当前档位的盘全网只剩这一张，它就是唯一目标——
        //    直接落盘，省掉先点中那张盘的两步。判定只看盘自己的类型锁与标记，不靠显示名猜。
        long[] autoDisks = NO_DISKS;
        if (isDiskSearchActive()) {
            autoDisks = capCandidates(diskEntries.stream().mapToLong(DiskListPanel.DiskEntry::serial).toArray());
        } else {
            var matched = diskEntries.stream().filter(this::matchesCurrentType)
                    .mapToLong(DiskListPanel.DiskEntry::serial).toArray();
            if (matched.length == 1) {
                autoDisks = matched;
            }
        }
        menu.setClientAutoDisks(autoDisks);

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

    /**
     * 这张盘是不是「当前档位编出的样板」的候选目标：可能落盘，且判断只认盘自己的东西，不猜显示名。
     *
     * <p>两条判据，先硬后软：</p>
     *
     * <ol>
     * <li><b>类型锁</b>：磁盘写入第一枚样板之后类型就固定下来了（{@code PatternDiskContents.type}）。
     * 锁住的类型正是当前档要编的那一类，那写进去必然成功——写入路径走的 {@code acceptsType} 就是同一个
     * 比较。这就是「固定匹配」：盘锁了哪一类、当前档编的就是哪一类，于是它就算那个目标，<b>不需要它打过标记</b>。
     * 一张空盘（没锁类型）不算，否则每张空盘都会来抢这个唯一目标。</li>
     * <li><b>标记</b>：类型锁没命中时，退回标记那条。磁盘标记有两种写法：导入过配方时写
     * {@code #<配方类别>}（如 {@code #minecraft:crafting}），手动编码留下的写 {@code #mode:<模式>}
     * ——同一台机器两种都得认。</li>
     * </ol>
     *
     * <p><b>但标记这条不是每档都能用</b>，而它用错时的后果很重：它会把样板引到一张类型根本不符的盘上。
     * 那时写入会被 {@code tryInsert} 拒掉（聊天栏报「已锁定为其它样板类型」），更糟的是那张被误认的盘
     * 会占掉「唯一目标」这个名额，真正类型对得上的那张盘反而没被试。所以没有可靠标记的档一律只认类型锁：</p>
     *
     * <ul>
     * <li><b>处理档</b>：处理没有公认类别（见 {@code DiskMarkRules.categoryForMode}），本来就不会
     * 走到标记那条（{@code category == null} 直接 false）。</li>
     * <li><b>高级档 / 过载档</b>：它们<b>没有专属标记</b>，而 {@code mode} 在额外档下停在「进入本档之前
     * 那个常规档」。若让它们落回模式标记，高级处理/过载处理样板就会去匹「合成 / 切石 / …」标记的盘
     * ——三条处理类的样板各是自己的类型（{@code ae2:processing_pattern} /
     * {@code advanced_ae:adv_processing_pattern} / {@code ae2lt:overload_pattern}），不能互串，也不能
     * 跟别的档串。所以它们到此为止，只认上面那条类型锁。</li>
     * <li><b>雕凿档</b>：有自己的固定字面量（注册表的 {@code ownMark}），比它自己的。</li>
     * </ul>
     */
    private boolean matchesCurrentType(DiskEntry entry) {
        // ① 类型锁（盘自己的事实）
        var locked = lockedTypeOf(entry);
        if (locked != null && locked.equals(currentPatternTypeId())) {
            return true;
        }
        // ② 标记（玩家/导入留下的意图）——只给有可靠标记的档用
        //
        // 已经被锁成别类的盘先退出：它的类型锁已经替它回答了「它要哪一种样板」，再按标记把它当候选只会
        // 挤掉真正对得上的那张盘（写进去也必然被拒）。这种盘系统自己会造出来——高级/过载档写盘时盖的是
        // 「继承来的常规档标记」，而类型锁是那个高级/过载类型，于是它回到常规档下就会被标记误认。
        // 空盘（locked == null）不受此限：它还没表过态，标记就是它唯一的依据。
        if (locked != null && !locked.equals(currentPatternTypeId())) {
            return false;
        }
        var raw = entry.stack().get(AEPatternRegistries.DISK_PREFIX.get());
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        // 额外档先判，不管有没有专属标记都到此为止：没有专属标记的那两档（高级/过载）不能落回下面那条
        // mode 判据，理由见 javadoc——那是「处理类样板被引到错误匹配项」的来源。
        var tier = activeTier();
        if (tier >= 0) {
            var ownMark = this.extraTiers.get(tier).ownMark();
            return ownMark != null && ownMark.equals(raw);
        }
        // 常规档：雕凿不在 EncodingMode 里也没有配方类别，它的标记是唯一的固定字面量，已在上面比过；
        // 常规四档则比当前模式的规范类别与模式标记。处理没有公认类别，一律不算，免得把样板送错机器。
        var mode = menu.getMode();
        var category = DiskMarkRules.categoryForMode(mode);
        if (category == null) {
            return false;
        }
        return ("#" + category).equals(raw) || DiskMarkRules.modeMarkId(mode).equals(raw);
    }

    /** 这张盘锁定的样板类型；它不是磁盘、或还是空盘（未锁定）时返回 null。 */
    @Nullable
    private static String lockedTypeOf(DiskEntry entry) {
        return entry.stack().getItem() instanceof PatternDiskItem disk
                ? disk.contents(entry.stack()).type()
                : null;
    }

    /**
     * 当前档位编出的样板物品 id。额外档各问自己注册表里那一格；常规四档由模式推
     * （AE2 那四个编码样板就是 {@code ae2:<模式名小写>_pattern}，值与映射都在 {@code DiskMarkRules}）。
     */
    private String currentPatternTypeId() {
        var tier = activeTier();
        // 额外档优先：雕凿/高级/过载档下 mode 仍停着「进入本档之前那个常规档」，直接问它会答错。
        return tier >= 0
                ? this.extraTiers.get(tier).patternTypeId()
                : DiskMarkRules.patternTypeForMode(menu.getMode());
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

    // ---- 磁盘列表交互 --------------------------------------------------------

    /** 子类（管理终端）用：把「磁盘序列号」映射到本屏幕列表里的下标，好复用下面的点击交互。 */
    protected int indexOfDisk(long serial) {
        for (int i = 0; i < diskEntries.size(); i++) {
            if (diskEntries.get(i).serial() == serial) {
                return i;
            }
        }
        return -1;
    }

    /** 没有写盘目标时传给服务端的哨兵（空数组 = 这次编码不自动写盘）。 */
    private static final long[] NO_DISKS = new long[0];

    /**
     * 客户端动作的参数有 32767 字符的硬上限（AE2 的 {@code AEBaseMenu}），候选整表上传时超了会在
     * 点击处抛异常。顺位到 {@link #MAX_AUTO_DISKS} 张早已超出实用范围，更长的候选只会变成一条超长消息。
     */
    private static final int MAX_AUTO_DISKS = 256;

    /** 把候选截到可发送的长度内。 */
    protected static long[] capCandidates(long[] serials) {
        return serials.length <= MAX_AUTO_DISKS ? serials : java.util.Arrays.copyOf(serials, MAX_AUTO_DISKS);
    }

    /** 磁盘搜索框里有没有内容；编码与管理的自动写盘都按它决定要不要顺位。 */
    protected boolean isDiskSearchActive() {
        var search = miniSearchField().getValue();
        return search != null && !search.isBlank();
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
     * 光标上那一件是不是某个配方类别的工作方块。
     *
     * <p>管理终端用它决定右键是「打标」还是「选中」：打标需要手里拿着工作方块，
     * 空手（或拿着别的）才把右键让给选中。</p>
     */
    protected boolean isHoldingWorkBlock() {
        var held = menu.getCarried();
        if (held.isEmpty()) {
            return false;
        }
        try {
            return MachineRecipeTypes.forHeldMachine(held, menu.getPendingRecipeCategory()) != null;
        } catch (Throwable failed) {
            // 配方查看器还没就绪时按「不是工作方块」处理：右键退回选中，而不是打一个写不出去的标记。
            return false;
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

    /**
     * 磁盘搜索栏右键清空（与 AE2 顶部搜索框同款手势）；中键落在过滤槽上时打开数量对话框（与 AE2 样板
     * 编码终端同款），其余按键仍交给基类。
     */
    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        // 展开的模式列表由本屏先接管：工具栏其它按钮与样式面板都排在 children 前面，会把落在它们地盘的
        // 点击先吃掉——列表压在谁身上都点不中。所以这里先把这一下抢过来，没抢到才让点击照常往下走。
        if (btn == InputConstants.MOUSE_BUTTON_LEFT && interceptModeMenuClick(xCoord, yCoord)) {
            return true;
        }

        // 清空后不 return：这一下照旧交给父类，让搜索框拿到焦点（AE2 自己也是这么干的）。
        if (btn == InputConstants.MOUSE_BUTTON_RIGHT && miniSearchField.isMouseOver(xCoord, yCoord)) {
            miniSearchField.setValue("");
        }

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
     * 把展开的模式列表这一下抢下来（命中则切档并消费）。
     *
     * <p>放在父类里给子类共用：命中的判断必须发生在点击被派发给 children 之前，而子类（管理终端）自己覆写
     * 了 {@link #mouseClicked} 且会在行内直接返回、不调 {@code super}，所以它得在自己的开头先调一次这个。</p>
     *
     * @return true 表示这次点击已被模式列表吃掉，调用方应直接返回
     */
    protected boolean interceptModeMenuClick(double xCoord, double yCoord) {
        if (this.modeButton.handleMenuClick(xCoord, yCoord)) {
            return true;
        }
        // 点到了别的地方就收起来。展开时 getTooltipArea 是「按钮 ∪ 列表」的包络，所以点在按钮本体或列表上
        // 都不会被误收（那两种情形分别交给按钮自己的 onPress 与上面的 handleMenuClick）。
        var menuArea = this.modeButton.getTooltipArea();
        if (!menuArea.contains((int) xCoord, (int) yCoord)) {
            this.modeButton.closeMenu();
        }
        return false;
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