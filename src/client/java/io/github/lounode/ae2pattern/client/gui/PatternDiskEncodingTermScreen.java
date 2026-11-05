package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;
import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import guideme.PageAnchor;

import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.behaviors.EmptyingAction;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.KeyTypeSelectionButton;
import appeng.core.AELog;
import appeng.core.localization.ButtonToolTips;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.SlotSemantics;
import appeng.parts.encoding.EncodingMode;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.client.sort.NaturalSort;
import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskEncodingTermMenu;
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
public class PatternDiskEncodingTermScreen extends AbstractPatternDiskTermScreen<PatternDiskEncodingTermMenu>
        implements NaturalSort.Provider {

    // states.png (16,0,16,8) 与 (208,224,36,20) 两组常量留在屏幕：前者给 init() 的显示开关用，后者给
    // 构造期的 modeButton.setBackground 用。常规四档与三个额外档的图标已随档位组搬进 EncodingTierWidgets。
    /** NEO ECO 上传按钮的尺寸（neoecoae 的 UploadButton 构造里写死的 18×20）。 */
    static final int NEO_ECO_UPLOAD_BUTTON_WIDTH = 18;
    static final int NEO_ECO_UPLOAD_BUTTON_HEIGHT = 20;

    /** 上传按钮列首个槽距 GUI 底边的距离（改写前手算式里的 173）。 */
    private static final int NEO_ECO_UPLOAD_BOTTOM_OFFSET = 173;

    /** ExtendedAE Plus 上传按钮的尺寸：标准 1.0 缩放下的 16×16（它接在 ECO 按钮正下方，无间隙）。 */
    private static final int EXTENDED_AE_PLUS_UPLOAD_BUTTON_SIZE = 16;

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

    /**
     * 界面右缘外的一列按钮：本模组摆的（NEO ECO 上传）与别人摆的（ExtendedAE Plus 上传只占一个槽）。
     * 位置与 JEI 排除区都只在这里算一次。
     */
    private final RightButtonColumn uploadColumn;

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

    /** 无标记磁盘的显示开关；每帧回写状态，否则点下去图标不会变。状态本体在菜单字段（服务端从宿主 logic 回读）。 */
    private StatesToggleButton showUnmarkedButton;

    /** 档位 / 模式控件组：面板可见性、下拉、高亮与提示语的状态与派生都收在它里面。 */
    private final EncodingTierWidgets tierWidgets;
    /** 磁盘列表控件组：条目、过滤、写盘顺位与点击交互的状态都收在它里面。 */
    private final EncodingDiskListWidgets diskWidgets;

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

        // 注册磁盘列表面板（管理终端不要这个面板：它把磁盘铺进自己的表里，复用面板只为共享搜索状态）
        this.diskListPanel = new DiskListPanel();
        if (usesDiskListPanel()) {
            widgets.add("diskList", this.diskListPanel);
        }

        // 磁盘列表点击回调：左键写盘；右键用迷你搜索栏的文本覆写该盘标记；中键不挂交互。
        // 「手持工作方块覆写标记」与「按标记重命名」已按需求删除（见 issue #4 第 3 条）。
        this.diskListPanel.setOnClick(this::onDiskClick);
        this.diskListPanel.setOnRightClick(this::overwriteMarkFromSearch);

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

        // 右侧上传按钮列：槽**在构造期就登记**——ExtendedAE Plus 在 init 的 TAIL 就来要锚点，那时候屏幕的
        // init() 体还没执行，现填槽就晚了；这里只定几何，控件在 init() 里绑定。
        // （覆写 usesNeoEcoUploadButton() 的子类只返回常量——管理终端 false——构造期调用是安全的。）
        this.uploadColumn = new RightButtonColumn();
        this.uploadColumn.setBottomOffset(NEO_ECO_UPLOAD_BOTTOM_OFFSET);
        this.widgets.add("uploadColumn", this.uploadColumn);
        if (usesNeoEcoUploadButton()
                && io.github.lounode.ae2pattern.client.integration.neoecoae.NeoECOClientIntegration.hasUploadTarget()) {
            this.uploadColumn.reserve(NEO_ECO_UPLOAD_BUTTON_WIDTH, NEO_ECO_UPLOAD_BUTTON_HEIGHT);
            // 判据与屏幕选择同一份（AE2PatternDiskClient 用它选上传屏）：EAE+ 在场但版本没带上传契约时，
            // 不会有人来要锚点，多留一个槽只会让 JEI 排除区白多 16px。
            if (io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusCompat.hasUploadContract()) {
                // ExtendedAE Plus 的按钮由它自己的 mixin 建，只回来要锚点（见 ExtendedAEPlusUploadScreen）。
                this.uploadColumn.reserve(EXTENDED_AE_PLUS_UPLOAD_BUTTON_SIZE, EXTENDED_AE_PLUS_UPLOAD_BUTTON_SIZE);
            }
        }
        // 提示语每帧回写（它报的是当前所属的配方类型），这里不设死文本。

        // 二级排序开关：贴在 AE2 那三枚排序按钮后面，只在「按 mod」那一档显示（见 updateBeforeRender）。
        this.naturalSortButton = new NaturalSortButton(() -> {
            // 比较器换了要重排一遍；updateView 是 AE2 自己换排序档位后走的同一条路。
            repo.updateView();
        });
        addToLeftToolbar(this.naturalSortButton.widget());

        // 编码/保存按钮：槽里和网络里都没有空白样板就不必白跑一趟服务端（AE2 原生的编码终端此时也是静默的）。
        var encodeBtn = new ActionButton(appeng.api.config.ActionItems.ENCODE, act -> {
            if (!menu.canEncode()) {
                // 本地预检：不白跑一趟服务端。不出声——AE2 原生在没空白样板时也是直接 return。
                return;
            }
            menu.encode();
        });
        widgets.add("encodePattern", encodeBtn);

        // 两块协作类在控件全部登记后建好：它们只收窄依赖（菜单、具体控件、一个回调），不持有本屏。
        this.tierWidgets = new EncodingTierWidgets(menu, this.modeButton, this.modePanels,
                this.advancedPanel, this.chiselingPanel, this.overloadedPanel);
        this.diskWidgets = new EncodingDiskListWidgets(menu, this.diskListPanel, this.miniSearchField,
                this.tierWidgets::currentPatternTypeId);
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
        return this.uploadColumn.anchorOf(0);
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
        // 控件在 init 里绑定（槽已在构造期登记，所以那里拿得到锚点）；绑上之后本轮手动挂一次——
        // 首次 init 时通道的 populateScreen 已跑过、当时槽里还没控件，不手动加就不会绘制。
        if (usesNeoEcoUploadButton() && !this.uploadColumn.hasWidget(0)) {
            var ecoUpload = io.github.lounode.ae2pattern.client.integration.neoecoae.NeoECOClientIntegration
                    .createUploadButtonIfPresent(this);
            if (ecoUpload != null && this.uploadColumn.attach(0, ecoUpload)) {
                addWidget(ecoUpload);
            }
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
        // 标题本身随状态走（常态 / 强制），状态行只讲显隐差别：两行各说一件事，不把两个状态挤在标题里。
        showUnmarked.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.show_unmarked.title.on"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.show_unmarked.on")));
        showUnmarked.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.show_unmarked.title.off"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.show_unmarked.off")));
        addRenderableWidget(showUnmarked);
        this.showUnmarkedButton = showUnmarked;
    }

    /**
     * 工具栏顺序：附加排序贴着 AE2 那枚「排序按」，本模组自己的模式轮换按钮排到 AE2 自带的之后。
     * 子屏（管理终端）会再把自家那两枚排到模式轮换之前。
     */
    /**
     * 左侧工具栏的清单：顺序即清单顺序，不在清单里的一律隐藏。
     *
     * <p>闪电科技那两枚（频率卡配置、自动连接开关）由上游自己造、自己挂：按 tooltip 定身份
     * （见基类的 {@code isFrequencyCardButton} / {@code isFrequencyAutoConnectButton}）。</p>
     */
    protected List<String> toolbarPlan() {
        return TOOLBAR;
    }

    private static final List<String> TOOLBAR = List.of(
            "guide", "sortBy", "naturalSort", "viewMode", "typeFilter",
            "sortOrder", "terminalSettings", "terminalStyle", "mode",
            "terminalSwitch", "frequencyCard", "frequencyAutoConnect");

    @Override
    protected String toolbarSlot(Button button) {
        var slot = super.toolbarSlot(button);
        if (slot != null) {
            return slot;
        }
        if (button == this.modeButton) {
            return "mode";
        }
        if (this.naturalSortButton != null && button == this.naturalSortButton.widget()) {
            return "naturalSort";
        }
        if (button instanceof KeyTypeSelectionButton) {
            return "typeFilter";
        }
        return null;
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

    // 模式钮在构造期绑定的两个方法引用：实现与状态都在档位控件组里，本屏只留这一行转调。
    private Blitter currentModeIcon() {
        return tierWidgets.currentModeIcon();
    }

    private List<ModeDropdownButton.Choice> modeChoices() {
        return tierWidgets.modeChoices();
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

        tierWidgets.updateBeforeRender();
        diskWidgets.updateBeforeRender();
    }

    /**
     * 「数值排序」当前是否打开；物品网格的比较器（{@code KeySortersMixin}）只认这一处，
     * 屏幕一关就跟着失效。子屏（管理终端）共用本屏的开关。
     */
    @Override
    public boolean naturalSortEnabled() {
        return this.naturalSortButton != null && this.naturalSortButton.isEnabled();
    }

    // 以下磁盘列表方法的实现与状态都在 EncodingDiskListWidgets；本屏只保留给子类（管理终端）复用的
    // protected 签名，一行转调。原有各方法的说明与注释随实现一并搬到了协作类。

    /** 子类（管理终端）用：把「磁盘序列号」映射到本屏幕列表里的下标，好复用下面的点击交互。 */
    protected int indexOfDisk(long serial) {
        return diskWidgets.indexOfDisk(serial);
    }

    /** 把候选截到可发送的长度内。 */
    protected static long[] capCandidates(long[] serials) {
        return EncodingDiskListWidgets.capCandidates(serials);
    }

    /** 当前搜索栏的解析结果（说明与筛选口径见 EncodingDiskListWidgets#diskSearchQuery）。 */
    protected DiskEntryFilter.Query diskSearchQuery() {
        return diskWidgets.diskSearchQuery();
    }

    protected boolean isDiskSearchActive() {
        return diskWidgets.isDiskSearchActive();
    }

    /** 子类用：过滤后列表里第 {@code index} 张盘的条目；越界返回 {@code null}。 */
    protected @Nullable DiskEntry diskEntryAt(int index) {
        return diskWidgets.diskEntryAt(index);
    }

    protected void onDiskClick(int index) {
        diskWidgets.onDiskClick(index);
    }

    /** 用搜索栏里的标记覆写该盘标记（说明见 EncodingDiskListWidgets#overwriteMarkFromSearch）。 */
    protected void overwriteMarkFromSearch(int index) {
        diskWidgets.overwriteMarkFromSearch(index);
    }

    /** 没搜索条件时的写盘候选（说明见 EncodingDiskListWidgets#tierGroupByUsage）。 */
    protected long[] tierGroupByUsage() {
        return diskWidgets.tierGroupByUsage();
    }

    // ---- 过滤槽交互 ----------------------------------------------------------

    /**
     * 过滤槽（含处理模式的输入/输出）的左右键语义**一律交给 AE2**，本类不要覆写 {@code slotClicked}：
     * 基类对 {@code FakeSlot} 发的是 {@code PICKUP_OR_SET_DOWN}（左键：携带物品即标记/叠加，空手即清空）
     * 与 {@code SPLIT_OR_PLACE_SINGLE}（右键：同类按携带数减量、空手 -1、异类不动），服务端由
     * {@code AEBaseMenu.handleFakeSlotAction} 落到 {@code FakeSlot.increase/decrease/set}。
     *
     * <p>曾经在这里把左键改成「清空」、右键改成「整堆覆盖标记」：{@code FakeSlot.mayPlace} 恒为 false，
     * 覆写之后左键点槽只会把槽清空，物品再也标记不进去（输出槽最先暴露这个问题）。要整堆覆盖请顺手多敲
     * 几下左键，或者走中键的数量对话框。</p>
     *
     * <p>下面这个方法照抄 AE2 样板编码终端的口径：处理槽先直接问手里这件可倒空容器（{@link ContainerItemStrategies}）
     * 能倒出什么，拿到了就把这个动作交给基类去走 EMPTY_ITEM，拿不到再退回基类判定。
     *
     * <p>这里和 AE2 一样跳过了基类的 {@code isItemValid} 闸门。因此今天就会出这个空操作：编码库存只收
     * 物品键，手里拿桶/瓶时上面拿到的是流体动作，客户端发 EMPTY_ITEM，服务端在 {@code FakeSlot.set} →
     * {@code ConfigMenuInventory.isItemValid} 一关静默拒掉，提示了却不落。上游同样是这个病，与
     * {@code PatternEncodingTermScreen} 逐行同形；日后若给编码配置加上 {@code supportedType}/
     * {@code slotFilter} 限制，需同步补回校验。</p>
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
     * 展开的模式列表开在 GUI 左侧（工具栏那一列之外），把它报给 JEI 的排除区：物品面板会因此让开，
     * 而不是盖住列表或被列表盖住。子类（管理终端、无线版、上传屏）共用这一份。
     *
     * <p>右侧的上传按钮列（NEO ECO 的 18×20 与 ExtendedAE Plus 接在它下方的 16×16）同样落在 GUI 之外，
     * 而且 JEI 的默认物品面板就在右边，必须一并报进去。</p>
     */
    @Override
    public List<Rect2i> getExclusionZones() {
        var zones = super.getExclusionZones();
        this.modeButton.addExclusionZone(zones);
        // 右侧上传按钮列不用手算：它作为组合控件进了 widgets，bounds 超出 GUI 时 AE2 会自动报给 JEI。
        return zones;
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
        // 点到了别的地方就收起来。判定的两块必须**分别**查，不能像以前那样拿包络矩形一笔带过：
        // 列表比按钮宽，包络会连按钮旁边那块背景一起圈进来，鼠标扫过那里本不该有任何反应。
        // （旧写法把「按钮 ∪ 列表」的外接框既当收合判定又当 tooltip 判定，其结果就是背景也弹提示。）
        var onButton = this.modeButton.getTooltipArea();
        var onPanel = this.modeButton.getPanelArea();
        if (!onButton.contains((int) xCoord, (int) yCoord)
                && !onPanel.contains((int) xCoord, (int) yCoord)) {
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
