package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import io.github.lounode.ae2pattern.integration.ae2lt.OverloadPatterns;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.menu.SlotSemantic;
import appeng.menu.slot.AppEngSlot;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.MEStorage;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.IConfigManager;
import appeng.menu.ISubMenu;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.PatternTermSlot;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.util.ConfigInventory;

import io.github.lounode.ae2pattern.integration.advancedae.AdvPatternSupport;
import io.github.lounode.ae2pattern.integration.polymorph.PolymorphCompat;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingPatternEncoder;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingRecipes;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.DiskEncodingLogic;
import io.github.lounode.ae2pattern.common.menu.slot.NetworkBlankPatternSlot;
import io.github.lounode.ae2pattern.common.menu.slot.PatternOutputSlot;
import io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart;
import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.network.DiskListPayload;

/**
 * Menu for the pattern disk encoding terminal. Extends {@link MEStorageMenu} to inherit network
 * storage access (disk scanning) and the item terminal infrastructure.
 *
 * <p>Core encoding workflow mirrors AE2's {@code PatternEncodingTermMenu}: a mode-adaptive encoding
 * grid (crafting 3x3 / processing in-out / smithing / stonecutting), blank + encoded pattern slots,
 * and encode/clear client actions. On top of that, this terminal exposes the network pattern disk
 * list: click a disk to write the currently encoded pattern into it, shift-right-click to bind the
 * pattern's prefix to the disk (renaming it), middle-click to rename, and a mini search bar.</p>
 */
public class PatternDiskEncodingTermMenu extends MEStorageMenu implements IPatternEncodingHost {

    private static final int CRAFTING_GRID_WIDTH = 3;
    private static final int CRAFTING_GRID_HEIGHT = 3;
    private static final int CRAFTING_GRID_SLOTS = CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT;

    private static final String ACTION_ENCODE = "encode";
    private static final String ACTION_CLEAR = "clear";
    private static final String ACTION_SET_MODE = "setMode";
    private static final String ACTION_CYCLE_PROCESSING_OUTPUT = "cycleProcessingOutput";
    private static final String ACTION_MULTIPLY_OUTPUT = "multiplyOutput";
    private static final String ACTION_DIVIDE_OUTPUT = "divideOutput";
    private static final String ACTION_SET_ADVANCED_MODE = "setAdvancedMode";
    private static final String ACTION_SET_CHISELING_MODE = "setChiselingMode";
    private static final String ACTION_SET_CHISELING = "setChiseling";
    private static final String ACTION_SET_ADVANCED_SIDE = "setAdvancedSide";
    private static final String ACTION_SET_OVERLOADED_MODE = "setOverloadedMode";
    private static final String ACTION_SET_OVERLOADED_ROW = "setOverloadedRow";
    private static final String ACTION_TRANSFER_TO_DISK = "transferToDisk";
    private static final String ACTION_EXTRACT_FROM_DISK = "extractFromDisk";

    /** {@link ExtractRequest#index} 取这个值表示「整张磁盘」，而不是盘里第几张样板。 */
    public static final int WHOLE_DISK = -1;

    /** 从磁盘取出来的东西放哪。 */
    public enum ExtractTarget {
        /** 光标上（左键）。 */
        CURSOR,
        /** 玩家背包（Shift+左键）。 */
        INVENTORY,
        /** 样板编辑槽（右键一张样板）。 */
        ENCODED_SLOT
    }

    /**
     * 「从磁盘取东西」的客户端动作参数。
     *
     * <p>AE2 的客户端动作把参数当 JSON 传（见 {@code AEBaseMenu#registerClientAction}），所以这里用一组公开
     * 字段加无参构造器，而不是 record：不依赖 GSON 对 record 的支持。</p>
     */
    public static final class ExtractRequest {
        public long serial;
        public int index;
        public ExtractTarget target;

        public ExtractRequest() {
        }

        public ExtractRequest(long serial, int index, ExtractTarget target) {
            this.serial = serial;
            this.index = index;
            this.target = target;
        }
    }
    private static final String ACTION_BIND_PREFIX = "bindPrefix";
    private static final String ACTION_RENAME_DISK = "renameDisk";
    private static final String ACTION_UPLOAD_PATTERN = "neoecoae:uploadPattern";

    /** Set by NeoECOIntegration when neoecoae is present. Null when absent. */
    @Nullable
    /**
     * 标记这条链横跨两侧：识别在客户端（配方查看器只有客户端有），落盘在服务端，中间隔着一次客户端动作。
     * 这一笔日志是唯一能把「没识别出来」与「识别出来却没送达」分开的证据——后者只会表现为标记退回模式。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.mark");

    public static volatile java.util.function.Consumer<PatternDiskEncodingTermMenu> uploadHandler;

    // 不可用 build()：会将实例推入 AE2 的 InitMenuTypes 注册队列，与下方 MENUS DeferredRegister 形成同实例双通道注册，
    // 注册冲突即触发 NeoForge MappedRegistry 的 duplicate value 崩溃；其余三个菜单均用 buildUnregistered 单通道。
    public static final MenuType<PatternDiskEncodingTermMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskEncodingTermMenu::createForHost, PatternDiskEncodingTerminalPart.class)
            .buildUnregistered(net.minecraft.resources.ResourceLocation.parse("ae2_pattern_disk:pattern_disk_encoding_terminal"));

    /**
     * 菜单工厂：装了带上传契约的 ExtendedAE Plus 时返回它的适配子类，否则返回本类。
     *
     * <p>适配子类**只能经 {@code ExtendedAEPlusCompat.createUploadMenu} 的反射入口创建**，本类字节码里
     * 不出现它的名字。光有守卫不够：守卫挡的是「执行」，挡不住「加载」——类被加载时会连带解析它 implements
     * 的接口，接口不在（EAE+ 缺席，或装着没有契约的构建，包括在架的 1.6.2）就是 {@code NoClassDefFoundError}。
     * 0.4.0 在 RegisterEvent 期间崩在 {@code IPatternUploadMenu} 上，正是因为这里直接 {@code new} 了那个子类
     * （菜单类在菜单注册表解析 supplier 时就被初始化，而那是 RegisterEvent 阶段）。结论：实现对方接口的类
     * 只能在字符串里出现，绝不能写在签名、字段、局部变量类型或 {@code X.class} 里；也**不得在静态初始化器或
     * mod 构造期调用反射入口**——那时对方可能还没就绪，而反射入口会真去加载并初始化它的接口。</p>
     */
    private static PatternDiskEncodingTermMenu createForHost(int containerId, Inventory playerInventory,
            PatternDiskEncodingTerminalPart host) {
        if (io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusCompat.hasUploadContract()) {
            return io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusCompat
                    .createUploadMenu(containerId, playerInventory, host);
        }
        return new PatternDiskEncodingTermMenu(containerId, playerInventory, host);
    }

    /**
     * 本终端实际可能被实例化的全部菜单类：基类，以及 EAE+ 上传契约在场时的适配子类。
     *
     * <p>给按「容器的运行时类」查表的集成用。JEI 的转移登记表是 {@code ImmutableTable<容器类, 配方类型,
     * 处理器>}，查表键取自 {@code container.getClass()}——精确匹配，不认父类；所以少登记一个类，那种
     * 环境下的「编写样板」按钮就整个不出现，而且不报任何错。</p>
     *
     * <p>条件与 {@link #createForHost} 必须一致（同一个 {@code hasUploadContract()}）：工厂现在能造出的
     * 具体类，这里就得列全，改一处必须同步另一处。适配子类同样经反射取（理由见本类工厂的注释），所以本类
     * 的常量池里不会出现它。</p>
     */
    public static java.util.List<Class<? extends PatternDiskEncodingTermMenu>> concreteMenuClasses() {
        if (io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusCompat.hasUploadContract()) {
            return io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusCompat.uploadMenuClasses();
        }
        return java.util.List.of(PatternDiskEncodingTermMenu.class);
    }

    private final IPatternDiskTerminalHost host;
    private final DiskEncodingLogic encodingLogic;
    private final ConfigInventory encodedInputsInv;
    private final ConfigInventory encodedOutputsInv;

    private final FakeSlot[] craftingGridSlots = new FakeSlot[9];
    private final FakeSlot[] processingInputSlots = new FakeSlot[AEProcessingPattern.MAX_INPUT_SLOTS];
    private final FakeSlot[] processingOutputSlots = new FakeSlot[AEProcessingPattern.MAX_OUTPUT_SLOTS];
    private final FakeSlot stonecuttingInputSlot;
    /** 雕凿档自己的输入槽，与切石那个互不影响（两个语义在样式文档里定在同一坐标，各档只显示自己的）。 */
    private final FakeSlot chiselingInputSlot;
    private final FakeSlot smithingTableTemplateSlot;
    private final FakeSlot smithingTableBaseSlot;
    private final FakeSlot smithingTableAdditionSlot;
    private final PatternTermSlot craftOutputSlot;
    private final NetworkBlankPatternSlot blankPatternSlot;
    private final RestrictedInputSlot encodedPatternSlot;

    private RecipeHolder<CraftingRecipe> currentRecipe;
    private EncodingMode currentMode;

    @GuiSync(97)
    public EncodingMode mode = EncodingMode.CRAFTING;
    @GuiSync(96)
    public boolean substitute = false;
    @GuiSync(95)
    public boolean substituteFluids = true;
    @GuiSync(94)
    @Nullable
    public ResourceLocation stonecuttingRecipeId;

    /** 当前模式的标记（如 #mode:crafting），用于搜索栏自动填充。空字符串=无。由服务端在 broadcastChanges 中同步。 */
    @GuiSync(93)
    public String recipePrefix = "";

    /** 处理模式下同物品合并开关（true=启用，false=禁用）。权威值在宿主 logic 里，这里是回读的镜像。 */
    @GuiSync(92)
    public boolean mergeSameItems = true;

    /**
     * 是否把无标记的样板磁盘也列出来。与「替换」同款：权威值在宿主部件自己的 logic 上（随部件 NBT 持久化），
     * 服务端每 tick 把它回读进这个镜像字段，客户端按钮跟着它走。
     */
    @GuiSync(91)
    public boolean showUnmarkedDisks;

    /**
     * 雕凿档是否已开放。
     *
     * <p>面板（候选列表 + 编码路径）做完前必须为 false：它一旦生效而面板还没挂，屏幕会停在四个常规面板
     * 全让位后的空白区，而雕凿档下 {@code mode} 仍是 CRAFTING（与高级档同样的机制），点「编写样板」会把
     * 输出栏那张样板当合成样板编。面板就绪后翻这一行即可——所有入口与守卫都读它。</p>
     */
    public static final boolean CHISELING_TIER_ENABLED = true;

    @GuiSync(85)
    public int selectedChiseling = -1;

    @GuiSync(87)
    public boolean chiselingModeAvailable;

    /** 当前是否停在雕凿编码模式。与高级档一样，它不在 AE2 的 {@link EncodingMode} 里。 */
    @GuiSync(86)
    public boolean chiselingMode;

    /** 升级槽里装着高级样板编码器——高级编码模式因此可用（屏幕上才会多出那一档）。 */
    @GuiSync(90)
    public boolean advancedModeAvailable;

    /** 当前是否停在高级编码模式。它不在 AE2 的 {@link EncodingMode} 里（那个枚举不可扩展），是并列的一档。 */
    @GuiSync(89)
    public boolean advancedMode;

    /**
     * 样板输出栏那张高级样板每个输入声明的接入面：{@code Direction} 的序号，或 -1 表示「相邻」（没指定）。
     * 逗号分隔、按输入顺序——面板上的按钮逐行跟着它亮。
     */
    @GuiSync(88)
    public String advancedSides = "";

    /** 装没装 AE2 Lightning Tech——过载档因此可用（屏幕上才会多出那一档）。 */
    @GuiSync(99)
    public boolean overloadedModeAvailable;

    /** 当前是否停在过载编码模式。同样不在 AE2 的 {@link EncodingMode} 里，是并列的一档。 */
    @GuiSync(98)
    public boolean overloadedMode;

    /** 过载档每行是输出(1)还是输入(0)，逗号分隔、按行序。面板上的行与编码区的槽一一对应。 */
    @GuiSync(100)
    public String overloadedSides = "";

    /** 过载档每行是否「忽略组件匹配」（1=忽略）。缺位一律当 0，也就是开关的默认态「启用组件匹配」。 */
    @GuiSync(101)
    public String overloadedMatchModes = "";

    /**
     * 客户端侧：最近一次从 EMI/JEI 导入的配方类别 id。绑定标记时优先用它——它才是「这是一台什么
     * 机器」的答案，编码模式只是四个粗类。为空则退回编码模式。
     */
    @Nullable
    private String pendingRecipeCategory;

    /** 支持流体替换的合成网格槽位（用于 CraftingEncodingPanel 高亮）。 */
    public IntSet slotsSupportingFluidSubstitution = new IntArraySet();

    // ---- 磁盘列表同步（供应器扫描 <-> 客户端） ----

    /** 客户端接收到的磁盘列表（由 DiskListPayload 更新，供 Screen 渲染）。 */
    private List<DiskListPayload.DiskEntry> diskList = List.of();

    /** 客户端已收到多少次磁盘列表推送，用来判断“刚才要的刷新到货了没有”。 */
    private long diskListRevision;

    /** serial → 磁盘槽的反查与序列号沿用都在 {@link DiskIndex} 里，这里只持有它。 */
    private final DiskIndex diskIndex = new DiskIndex();

    private final List<RecipeHolder<StonecutterRecipe>> stonecuttingRecipes = new java.util.ArrayList<>();

    public PatternDiskEncodingTermMenu(int id, Inventory ip, PatternDiskEncodingTerminalPart host) {
        this(TYPE, id, ip, host);
    }

    /**
     * 子类（管理终端）用：菜单实例的类型会被服务端写进开界面的包，客户端据此挑屏幕。父类的公开构造器传的是
     * {@link #TYPE}，子类若沿用，客户端会开出编码终端的界面，而菜单数据来自子类，形成界面与数据错位。所以这里
     * 把类型开成参数，子类显式传自己的 {@code TYPE}。
     */
    protected PatternDiskEncodingTermMenu(MenuType<?> menuType, int id, Inventory ip,
            IPatternDiskTerminalHost host) {
        super(menuType, id, ip, host, true);
        this.host = host;
        this.encodingLogic = host.getLogic();
        this.encodedInputsInv = encodingLogic.getEncodedInputInv();
        this.encodedOutputsInv = encodingLogic.getEncodedOutputInv();

        var encodedInputs = encodedInputsInv.createMenuWrapper();
        var encodedOutputs = encodedOutputsInv.createMenuWrapper();

        // Crafting grid (3x3)
        for (int i = 0; i < CRAFTING_GRID_SLOTS; i++) {
            var slot = new FakeSlot(encodedInputs, i);
            slot.setHideAmount(true);
            this.addSlot(this.craftingGridSlots[i] = slot, SlotSemantics.CRAFTING_GRID);
        }
        this.addSlot(this.craftOutputSlot = new PatternTermSlot(), SlotSemantics.CRAFTING_RESULT);

        // Processing in/out
        for (int i = 0; i < processingInputSlots.length; i++) {
            this.addSlot(this.processingInputSlots[i] = new FakeSlot(encodedInputs, i), SlotSemantics.PROCESSING_INPUTS);
        }
        for (int i = 0; i < this.processingOutputSlots.length; i++) {
            this.addSlot(this.processingOutputSlots[i] = new FakeSlot(encodedOutputs, i),
                    SlotSemantics.PROCESSING_OUTPUTS);
        }

        // Stonecutting input
        this.addSlot(this.stonecuttingInputSlot = new FakeSlot(encodedInputs, 0), SlotSemantics.STONECUTTING_INPUT);
        this.stonecuttingInputSlot.setHideAmount(true);

        // 雕凿输入：单独一份库存（一格的量级），只给雕凿档用。样式文档里给的是与切石槽相同的坐标，
        // 所以两档切换时玩家看到的是同一格——但两边各记各的，不会串。
        var chiselingInputs = encodingLogic.getChiselingInputInv().createMenuWrapper();
        this.addSlot(this.chiselingInputSlot = new FakeSlot(chiselingInputs, 0), AEPatternRegistries.CHISELING_INPUT);
        this.chiselingInputSlot.setHideAmount(true);

        // Smithing inputs
        this.addSlot(this.smithingTableTemplateSlot = new FakeSlot(encodedInputs, 0),
                SlotSemantics.SMITHING_TABLE_TEMPLATE);
        this.smithingTableTemplateSlot.setHideAmount(true);
        this.addSlot(this.smithingTableBaseSlot = new FakeSlot(encodedInputs, 1), SlotSemantics.SMITHING_TABLE_BASE);
        this.smithingTableBaseSlot.setHideAmount(true);
        this.addSlot(this.smithingTableAdditionSlot = new FakeSlot(encodedInputs, 2),
                SlotSemantics.SMITHING_TABLE_ADDITION);
        this.smithingTableAdditionSlot.setHideAmount(true);

        // Blank + encoded pattern slots
        // Blank pattern slot: a read-only mirror of what the network holds. The terminal no longer has to be
        // stocked by hand - encoding pulls from the network (see encode()).
        this.addSlot(this.blankPatternSlot = new NetworkBlankPatternSlot(), SlotSemantics.BLANK_PATTERN);
        this.addSlot(
                this.encodedPatternSlot = new PatternOutputSlot(encodingLogic.getEncodedPatternInv(), 0),
                SlotSemantics.ENCODED_PATTERN);
        this.encodedPatternSlot.setStackLimit(1);

        // 升级槽：高级样板编码器放这儿。切忌别信「UPGRADES 槽的准入就是查登记表」——AE2 实际上只做一句
        // instanceof UpgradeCardItem，所以槽自带一层自己的判定（见 TerminalUpgradeSlot）。
        // AE2 的 MEStorageMenu 在构造里已经按 SlotSemantics.UPGRADE 建过一套（它的判定只认 UpgradeCardItem，
        // 不看登记表），而 AE2 没有移除服务端槽的 API。那套槽在界面文档里没有坐标、画不出来，但仍然作为
        // 槽存在（整理模组与拖放都会看见同一格的两份），所以先把它们按死，再由下面这一套顶替。
        // 必须走 super：本类覆写了 getSlots(UPGRADE)（只返回自己那套），此刻自己的槽还没加——直接写
        // getSlots(...) 是虚调用，只会拿到空列表，循环一次都不执行。
        for (var builtByAe2 : super.getSlots(SlotSemantics.UPGRADE)) {
            if (builtByAe2 instanceof AppEngSlot appEngSlot) {
                appEngSlot.setSlotEnabled(false);
            }
        }
        // 升级槽：高级样板编码器放这儿（无线终端那侧要它）。面板版终端不摆——它的高级编码能力不由升级卡
        // 给（产品口径），见 supportsUpgradeSlots。另，切忌信「UPGRADES 槽的准入就是查登记表」：AE2 实际
        // 上只做一句 instanceof UpgradeCardItem，所以槽自带一层自己的判定（见 TerminalUpgradeSlot）。
        if (supportsUpgradeSlots()) {
            var upgrades = host.getUpgrades();
            for (int i = 0; i < upgrades.size(); i++) {
                this.addSlot(new TerminalUpgradeSlot(upgrades, i), SlotSemantics.UPGRADE);
            }
        }

        registerClientAction(ACTION_ENCODE, this::encode);
        registerClientAction(ACTION_SET_ADVANCED_MODE, Boolean.class, this::setAdvancedMode);
        registerClientAction(ACTION_SET_CHISELING_MODE, Boolean.class, this::setChiselingMode);
        registerClientAction(ACTION_SET_CHISELING, Integer.class, this::setChiseling);
        registerClientAction(ACTION_SET_ADVANCED_SIDE, AdvancedSideChange.class,
                change -> applyAdvancedSide(change.input(), change.side()));
        registerClientAction(ACTION_SET_OVERLOADED_MODE, Boolean.class, this::setOverloadedMode);
        registerClientAction(ACTION_SET_OVERLOADED_ROW, OverloadedRowChange.class,
                change -> applyOverloadedRow(change.row(), change.output(), change.ignoreComponents()));
        registerClientAction(ACTION_CLEAR, this::clear);
        registerClientAction(ACTION_SET_MODE, EncodingMode.class, encodingLogic::setMode);
        registerClientAction(ACTION_CYCLE_PROCESSING_OUTPUT, this::cycleProcessingOutput);
        registerClientAction(ACTION_MULTIPLY_OUTPUT, Integer.class, this::multiplyOutput);
        registerClientAction(ACTION_DIVIDE_OUTPUT, Integer.class, this::divideOutput);
        registerClientAction("setSubstitution", Boolean.class, encodingLogic::setSubstitution);
        registerClientAction("setFluidSubstitution", Boolean.class, encodingLogic::setFluidSubstitution);
        // 多态合成：客户端选了另一个配方之后清掉产物缓存并重算。
        registerClientAction(PolymorphCompat.ACTION_SELECT_RECIPE, () -> {
            this.currentRecipe = null;
            getAndUpdateOutput();
        });
        registerClientAction("setStonecuttingRecipeId", ResourceLocation.class,
                encodingLogic::setStonecuttingRecipeId);
        registerClientAction(ACTION_TRANSFER_TO_DISK, Long.class, this::transferToDisk);
        registerClientAction(ACTION_EXTRACT_FROM_DISK, ExtractRequest.class, this::handleExtractFromDisk);
        registerClientAction(ACTION_BIND_PREFIX, Long.class, this::bindPrefix);
        registerClientAction("setPendingRecipeCategory", String.class, this::setPendingRecipeCategory);
        registerClientAction("setPendingAutoDisks", long[].class, this::setPendingAutoDisks);
        registerClientAction("setPendingDiskName", String.class, this::setPendingDiskName);
        registerClientAction("setPendingMarkText", String.class, this::setPendingMarkText);
        registerClientAction("bindSearchMark", Long.class, this::bindSearchMark);
        registerClientAction("refreshDiskList", this::refreshDiskList);
        registerClientAction(ACTION_RENAME_DISK, Long.class, this::renameDisk);
        registerClientAction("setMergeSameItems", Boolean.class, this::setMergeSameItems);
        registerClientAction("setShowUnmarkedDisks", Boolean.class, this::setShowUnmarkedDisks);
        registerClientAction(ACTION_UPLOAD_PATTERN, this::uploadPattern);

        updateStonecuttingRecipes();
    }

    // ---- 过滤槽 --------------------------------------------------------------

    /**
     * 处理模式的输入/输出槽是过滤槽：玩家配置的是“要什么、要几个”，不是搬进真实物品。只有它们允许改
     * 数量，这与 AE2 样板编码终端一致（中键数量对话框的适用面同此）。
     */
    public boolean isProcessingPatternSlot(@Nullable Slot slot) {
        if (slot == null) {
            return false;
        }
        for (var candidate : processingInputSlots) {
            if (candidate == slot) {
                return true;
            }
        }
        for (var candidate : processingOutputSlots) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }

    /** 过滤槽里有东西时，中键打开数量对话框才有意义。 */
    public boolean canModifyAmountForSlot(@Nullable Slot slot) {
        return isProcessingPatternSlot(slot) && slot.hasItem();
    }

    // ---- Encoding ------------------------------------------------------------

    /**
     * 编一枚雕凿样板：输入取自雕凿自己的输入槽（与切石那个互不影响），输出取自面板上选中的候选。
     *
     * <p>槽空着或没选候选就返回 null，但**不在这里发提示**：编不出来不等于什么都没发生——编码槽里停着
     * 一枚写好的样板时，下面会把它顺位写进唯一的目标盘（与合成/锻造/切石同款），那时再说「需要先选候选」
     * 就成了自相矛盾的两句话。提示统一由 {@link #encode()} 在确实什么都没写之后发。</p>
     */
    @Nullable
    private ItemStack encodeChiselingPattern() {
        var input = this.chiselingInputSlot.getItem();
        var candidate = input.isEmpty()
                ? null
                : ChiselingRecipes.serverCandidateAt(this.selectedChiseling, input.getItem());
        if (candidate == null) {
            // 不在这里发提示：编不出来不等于什么都没发生——编码槽里停着一枚写好的样板时，下面会把它
            // 顺位写进唯一的目标盘（与合成/锻造/切石同款），那时再发「需要先选候选」就成了自相矛盾的两句。
            // 提示统一由 encode() 在确实什么都没写之后发（见 notifyExtraTierNeedsInput）。
            return null;
        }
        return ChiselingRecipes.encode(input.getItem(), candidate.output());
    }

    /**
     * 过载档的编码：把面板上每行的「输入/输出 + 组件匹配」与输出栏那张样板并成一枚过载样板。
     *
     * <p>待接：AE2LT 的模型里过载样板必须带一份「源样板快照」（它自己的编码器也是「拿一张源样板转出
     * 来」），而源快照要从一个 ItemStack 取。面板与档位、存储都已就位，就差这一步接线——需要
     * {@code compileOnly} 上 AE2LT 才能在隔离包里调它的 Builder API（照 Rechiseled 那套）。
     * 在那之前这里返回 null，等于「编不出来」，{@code encode()} 会经
     * {@code notifyExtraTierNeedsInput()} 给一句提示（不是默默无反应）。</p>
     */
    @Nullable
    private ItemStack encodeOverloadedPattern() {
        return null;
    }

    /** 四套编码的实现已搬去 {@link PatternEncodingLogic}，这里只留一个引用。 */
    private final PatternEncodingLogic patternEncodingLogic = new PatternEncodingLogic(this);

    public void encode() {
        if (isClientSide()) {
            // 配方类别只有客户端知道（导入时记下的），而服务端绑标记时要用它，所以像 bindPrefix 一样先单独送过去。
            // 写盘顺位候选（搜索栏筛出的列表、或选中的那张盘）同理。
            var category = pendingRecipeCategory;
            sendClientAction("setPendingRecipeCategory", category == null ? "" : category);
            sendClientAction("setPendingAutoDisks", clientAutoDisks);
            sendClientAction(ACTION_ENCODE);
            return;
        }
        // 先取值再清空，所以提前退出也不会把这次的候选留给下一次编码。
        var autoDisks = pendingAutoDisks;
        pendingAutoDisks = NO_DISKS;
        // 雕凿档的产物不从输出栏那张样板推，而是由选中的候选项直接决定（它自己记着「把谁雕成谁」），所以
        // 在这里就把结果定下来，后面扣空白样板与顺位写盘那段照旧复用。与高级档同理：不先判的话会落到
        // patternEncodingLogic 的默认分支上（雕凿档下 mode 仍停在 CRAFTING）。
        ItemStack encodedPattern = this.overloadedMode
                ? encodeOverloadedPattern()
                : this.chiselingMode
                        ? encodeChiselingPattern()
                        : patternEncodingLogic.encodePattern();
        if (encodedPattern != null) {
            var encodeOutput = this.encodedPatternSlot.getItem();
            if (!encodeOutput.isEmpty()
                    && !PatternDetailsHelper.isEncodedPattern(encodeOutput)
                    && !AEItems.BLANK_PATTERN.is(encodeOutput)) {
                return;
            } else if (encodeOutput.isEmpty()) {
                // 空白样板来自网络，不再从槽里拿。扣不到就告诉玩家，而不是默默没反应。
                if (!consumeNetworkBlankPattern()) {
                    notifyNoBlankPattern();
                    return;
                }
            }
            this.encodedPatternSlot.set(encodedPattern);
            // 搜索栏有内容时，刚编好的样板按列表顺序顺位写进第一张能收的盘——省掉「编出一个样板再点
            // 磁盘」两步。候选由客户端给出（顺序就是屏幕上的顺序）；全部写不进时由顺位路径报原因。
            if (autoDisks.length > 0) {
                // 写进去了才顺手把标记写成它的工作方块：写盘被拒（容量/重复产出/类型锁定）时盘里没这份
                // 样板，再去改标记只会让盘与样板对不上。
                if (transferToFirstWritable(autoDisks) && autoDisks.length == 1) {
                    bindPrefix(autoDisks[0], false, uploadMark());
                }
            }
        } else {
            // 网格里没有可编码的东西时，如果编码槽里正停着一枚写好的样板，「编写样板」的意图就是把它写进目标
            // 磁盘（管理终端选中盘之后的快捷上传）。没有唯一目标时什么也不做：原来这里会把它清成空白样板，
            // 等于把玩家手里的样板销毁掉。
            var existing = this.encodedPatternSlot.getItem();
            if (PatternDetailsHelper.isEncodedPattern(existing)) {
                if (autoDisks.length > 0) {
                    // 走既有的写盘路径：写进去、清空编码槽、退回空白样板，一处口径。
                    // 同样只在真写进去之后才绑标记（理由见上一处调用点）。
                    if (transferToFirstWritable(autoDisks) && autoDisks.length == 1) {
                        bindPrefix(autoDisks[0], false, uploadMark());
                    }
                } else {
                    notifyExtraTierNeedsInput();
                }
                return;
            }
            notifyExtraTierNeedsInput();
            clearPattern();
        }
    }

    /**
     * 额外档无从下笔时给一句提示。常规四档静默——那几档「网格里没东西可编」是正常状态，不必报告。
     *
     * <p>只在本轮确实什么都没写时调：编码槽里停着写好的样板且找到了唯一目标时，样板会被顺位写进盘，
     * 那时再说「需要选目标」就与写盘回执自相矛盾了。</p>
     *
     * <p>过载档也要说话：它现在的编码侧还没接上 AE2LT，不提示就成「点了完全没反应」。</p>
     */
    private void notifyExtraTierNeedsInput() {
        if (this.chiselingMode) {
            if (getPlayer() instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.translatable(
                        "gui.ae2_pattern_disk.encoding_terminal.chiseling_needs_target"));
            }
            return;
        }
        if (this.overloadedMode && getPlayer() instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.overloaded_needs_input"));
        }
    }

    private void clearPattern() {
        var encodedPattern = this.encodedPatternSlot.getItem();
        if (PatternDetailsHelper.isEncodedPattern(encodedPattern)) {
            this.encodedPatternSlot.set(AEItems.BLANK_PATTERN.stack(encodedPattern.getCount()));
        }
    }

    private boolean isBlankPattern(ItemStack output) {
        return !output.isEmpty() && AEItems.BLANK_PATTERN.is(output);
    }

    // ---- 空白样板：网络镜像 -----------------------------------------------------

    /** 旧存档遗留在样板槽里的空白样板只迁移一次。 */
    private boolean legacyBlankPatternsMigrated;

    /** 服务端：网络里可用的空白样板数量（模拟提取，不动库存）。网络不在时是 0，也就编不了码。 */
    public long getNetworkBlankPatternCount() {
        var grid = getGrid();
        var storage = grid == null ? null : grid.getStorageService();
        if (storage == null) {
            return 0;
        }
        return storage.getInventory().extract(AEItemKey.of(AEItems.BLANK_PATTERN), Long.MAX_VALUE,
                Actionable.SIMULATE, IActionSource.ofPlayer(getPlayer()));
    }

    /** 从网络取走一个空白样板；网络里没有则返回 false。 */
    private boolean consumeNetworkBlankPattern() {
        var grid = getGrid();
        var storage = grid == null ? null : grid.getStorageService();
        if (storage == null) {
            return false;
        }
        return storage.getInventory().extract(AEItemKey.of(AEItems.BLANK_PATTERN), 1,
                Actionable.MODULATE, IActionSource.ofPlayer(getPlayer())) > 0;
    }

    /**
     * 客户端：现在能不能编码——网络里有空白样板，或者编码槽里停着一个已经从网络取出的样板（空白载体、
     * 或可被直接覆盖的已编码样板），后两种情形都不再需要网络。这只是提前拦住，真正的裁决仍在服务端。
     */
    public boolean canEncode() {
        var encodedOutput = encodedPatternSlot.getItem();
        return isBlankPattern(blankPatternSlot.getItem())
                || isBlankPattern(encodedOutput)
                || PatternDetailsHelper.isEncodedPattern(encodedOutput);
    }

    /** 网络里拿不到空白样板时告诉玩家一声；静默失败会让人以为是界面卡了。 */
    private void notifyNoBlankPattern() {
        if (getPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.no_blank_pattern"));
        }
    }

    /**
     * 旧存档里玩家是往样板槽塞样板的，现在那个槽只是网络镜像、放不下东西了。把遗留的物品转进网络，
     * 网络收不下就还给玩家，别让它卡在只用于显示的地方再也拿不出来。网络未就绪时直接退化为还给玩家。
     */
    private void migrateLegacyBlankPatterns() {
        var inv = encodingLogic.getBlankPatternInv();
        var legacy = inv.getStackInSlot(0);
        if (legacy.isEmpty()) {
            return;
        }
        inv.setItemDirect(0, ItemStack.EMPTY);

        var remainder = legacy.copy();
        var grid = getGrid();
        var storage = grid == null ? null : grid.getStorageService();
        if (storage != null) {
            var inserted = storage.getInventory().insert(AEItemKey.of(AEItems.BLANK_PATTERN), remainder.getCount(),
                    Actionable.MODULATE, IActionSource.ofPlayer(getPlayer()));
            remainder.shrink((int) inserted);
        }
        if (!remainder.isEmpty() && !getPlayer().getInventory().add(remainder)) {
            getPlayer().drop(remainder, false);
        }
    }

    /**
     * 取编码区某一格的物品。它是编码逻辑与产物预览共用的取格口径，所以由宿主接口暴露，
     * 免得 {@link PatternEncodingLogic} 里再写一份一样的。
     */
    @Override
    public @Nullable ItemStack getCraftingIngredient(int slot) {
        var what = this.encodedInputsInv.getKey(slot);
        if (what == null) return ItemStack.EMPTY;
        else if (what instanceof AEItemKey itemKey) return itemKey.toStack(1);
        else return null;
    }

    /**
     * 多态合成：客户端选中了另一个配方，请服务端重算产物。
     *
     * <p>只负责发动作，实际重算在 {@code ACTION_SELECT_RECIPE} 的服务端处理里——那边才有权威的配方数据。</p>
     */
    public void notifyRecipeSelected() {
        sendClientAction(PolymorphCompat.ACTION_SELECT_RECIPE);
    }

    private ItemStack getAndUpdateOutput() {
        var level = this.getPlayerInventory().player.level();
        var items = NonNullList.withSize(CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT, ItemStack.EMPTY);
        boolean invalidIngredients = false;
        for (int x = 0; x < items.size(); x++) {
            var stack = getCraftingIngredient(x);
            if (stack != null) items.set(x, stack);
            else invalidIngredients = true;
        }
        var input = CraftingInput.of(CRAFTING_GRID_WIDTH, CRAFTING_GRID_HEIGHT, items);
        if (this.currentRecipe == null || !this.currentRecipe.value().matches(input, level)) {
            // 装了多态合成时由它按玩家在这组材料上选过的配方返回，没装则退回原生的「第一个匹配」。
            this.currentRecipe = invalidIngredients ? null
                    : PolymorphCompat.getCraftingRecipe(this, input, level, getPlayer());
            this.currentMode = this.mode;
            checkFluidSubstitutionSupport();
        }
        final ItemStack is;
        if (this.currentRecipe == null) is = ItemStack.EMPTY;
        else is = this.currentRecipe.value().assemble(input, level.registryAccess());
        this.craftOutputSlot.setResultItem(is);
        return is;
    }

    private void checkFluidSubstitutionSupport() {
        this.slotsSupportingFluidSubstitution.clear();

        if (this.currentRecipe == null) {
            return; // No recipe -> no substitution
        }

        var encodedPattern = patternEncodingLogic.encodePattern();
        if (encodedPattern != null) {
            var decodedPattern = PatternDetailsHelper.decodePattern(encodedPattern,
                    this.getPlayerInventory().player.level());
            if (decodedPattern instanceof AECraftingPattern craftingPattern) {
                for (int i = 0; i < craftingPattern.getSparseInputs().size(); i++) {
                    if (craftingPattern.getValidFluid(i) != null) {
                        slotsSupportingFluidSubstitution.add(i);
                    }
                }
            }
        }
    }

    // ---- Disk list interactions ---------------------------------------------

    /**
     * 把改好的磁盘写回槽位，并立即把列表推给客户端。
     *
     * <p>写入不再依赖 {@link #syncDiskList()} 的指纹比对去到达客户端：那条路曾被看到落后于写入，
     * 而玩家刚刚标记完磁盘、眼里却没变化时，只会得出「这次右键没生效」的结论。传入调用方已经解析好的
     * 库存，免得写回时再解析一次。</p>
     */
    private void writeDiskSlot(InternalInventory inv, int slot, ItemStack updated) {
        inv.setItemDirect(slot, updated); // triggers host refresh
        syncDiskList(true);
    }

    /**
     * Writes the currently encoded pattern into the disk identified by its serial.
     * The disk lives in a PatternDiskProvider's disk inventory; after the write the provider
     * re-decodes its patterns, so the newly stored pattern becomes available to autocrafting.
     */
    public void transferToDisk(long serial) {
        if (isClientSide()) {
            sendClientAction(ACTION_TRANSFER_TO_DISK, serial);
            return;
        }
        transferToFirstWritable(new long[] { serial });
    }

    /** 候选为空时的哨兵：直接拿空数组当值传，不必每处新建。 */
    private static final long[] NO_DISKS = new long[0];

    /**
     * 按顺序把编码槽里的样板写进候选磁盘，写进第一张能收的就停。
     *
     * <p>候选与顺序都由客户端给出，顺序就是玩家的意图顺序：编码终端是搜索栏筛出的列表，管理终端是
     * 右键选中的那张盘加上同一容器内的其他盘。全部写不进时只报**第一张被拒**（已过期的候选会被跳过）的原因，
     * 而不是逐张刷屏；一张盘都没碰到（列表过期）时才报「目标已不在列表里」。</p>
     */
    private boolean transferToFirstWritable(long[] serials) {
        var encoded = encodedPatternSlot.getItem();
        if (encoded.isEmpty() || !PatternDetailsHelper.isEncodedPattern(encoded)) {
            return false;
        }

        var level = getPlayer().level();
        boolean stale = false;
        Component refusedName = null;
        PatternDiskItem.InsertFailure refusedReason = null;
        for (long serial : serials) {
            var ref = diskIndex.refOf(serial);
            if (ref == null) {
                // 客户端列表可能比服务端旧：那张盘已经被拿走了。全部失败时再一起说，免得顺位中途刷屏。
                stale = true;
                continue;
            }
            var inv = ref.host().getDiskInventory();
            var stack = inv.getStackInSlot(ref.slot());
            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
                stale = true;
                continue;
            }

            // 接收判据（容量/锁定类型/主产物互斥）统一由 PatternDiskItem.canInsert/tryInsert 负责
            var updated = stack.copy();
            if (disk.tryInsert(updated, encoded, level)) {
                writeDiskSlot(inv, ref.slot(), updated);
                // 样板已存入磁盘：编码槽清空，原编码样板回退为空白样板并按 ME网络→玩家背包→编码槽 优先级落位
                this.encodedPatternSlot.set(ItemStack.EMPTY);
                returnBlankPatternToStorage();
                notifyPatternWritten(stack.getHoverName());
                return true;
            }
            if (refusedName == null) {
                refusedName = stack.getHoverName();
                refusedReason = disk.whyCannotInsert(stack, encoded, level);
            }
        }

        if (refusedName != null) {
            notifyDiskRefused(refusedName, refusedReason);
        } else if (stale) {
            notifyStaleTarget();
        }
        return false;
    }

    /** 样板写不进磁盘时说明理由。原因与写入路径共用同一套判据（见 whyCannotInsert）。 */
    private void notifyDiskRefused(Component diskName, @Nullable PatternDiskItem.InsertFailure reason) {
        if (!(getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        var key = reason == null ? "unknown" : switch (reason) {
            case FULL -> "full";
            case TYPE_LOCKED -> "type_locked";
            case DUPLICATE_OUTPUT -> "duplicate_output";
            case UNRESOLVABLE -> "unresolvable";
        };
        player.sendSystemMessage(Component.translatable(
                "gui.ae2_pattern_disk.encoding_terminal.disk_refused." + key, diskName));
    }

    /** 目标磁盘已不在列表里（客户端列表比服务端旧）时说明一句，否则又是点了没反应。 */
    private void notifyStaleTarget() {
        if (getPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.disk_refused.stale_target"));
        }
    }

    // ---- 从磁盘取东西（管理终端的左键/Shift+左键/右键）--------------------------------------

    /** 把某张磁盘整张取出来（管理终端左键）。 */
    public void extractDisk(long serial, ExtractTarget target) {
        requestExtract(serial, WHOLE_DISK, target);
    }

    /** 把某张盘里第 {@code index} 张样板取出来（管理终端左键 / Shift+左键 / 右键）。 */
    public void extractPattern(long serial, int index, ExtractTarget target) {
        requestExtract(serial, index, target);
    }

    private void requestExtract(long serial, int index, ExtractTarget target) {
        if (isClientSide()) {
            sendClientAction(ACTION_EXTRACT_FROM_DISK, new ExtractRequest(serial, index, target));
        } else {
            handleExtractFromDisk(new ExtractRequest(serial, index, target));
        }
    }

    /**
     * 服务端：从磁盘里取出一张样板，或者整张磁盘。
     *
     * <p>样板是「物化」出来的：按本模组一贯口径，把一张样板带出磁盘要消耗网络里的一张空白样板（与样板访问
     * 终端取走同一个账）；磁盘本身只是件物品，不扣。落点腾不出位置、或网络里没有空白样板时，整件事都不做：
     * 既不扣空白样板，也不动磁盘。</p>
     */
    private void handleExtractFromDisk(ExtractRequest request) {
        // 畸形的客户端可能送来缺字段的请求；这里不报错也不改任何东西。
        if (request == null || request.target == null) {
            return;
        }
        var ref = diskIndex.refOf(request.serial);
        if (ref == null) {
            notifyStaleTarget();
            return;
        }
        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
            notifyStaleTarget();
            return;
        }

        if (request.index == WHOLE_DISK) {
            // 编辑槽只收样板，整张磁盘不进它（客户端不会这么请，防的是改造过的客户端）。
            if (request.target == ExtractTarget.ENCODED_SLOT || !hasRoomFor(request.target)) {
                notifyNoRoom(request.target);
                return;
            }
            inv.setItemDirect(ref.slot(), ItemStack.EMPTY);
            syncDiskList(true);
            deliverExtracted(stack.copy(), request.target);
            return;
        }

        var patterns = disk.contents(stack).patterns();
        if (request.index < 0 || request.index >= patterns.size()) {
            // 盘里的张数与客户端看到的不一致（刚被写入/取走）。不是「落点」也不是「网络缺料」，说清楚。
            notifyContentChanged();
            return;
        }
        if (patterns.get(request.index).isEmpty()) {
            return;
        }
        if (!hasRoomFor(request.target)) {
            notifyNoRoom(request.target);
            return;
        }
        if (!consumeNetworkBlankPattern()) {
            notifyNoBlankPattern();
            return;
        }

        var extracted = patterns.get(request.index).copy();
        var updated = stack.copy();
        disk.removeAt(updated, request.index);
        writeDiskSlot(inv, ref.slot(), updated);
        deliverExtracted(extracted, request.target);
    }

    /** 取出来的东西有没有地方放。 */
    private boolean hasRoomFor(ExtractTarget target) {
        return switch (target) {
            case CURSOR -> getCarried().isEmpty();
            case INVENTORY -> getPlayer().getInventory().getFreeSlot() >= 0;
            case ENCODED_SLOT -> encodedPatternSlot.getItem().isEmpty();
        };
    }

    private void deliverExtracted(ItemStack stack, ExtractTarget target) {
        switch (target) {
            case CURSOR -> setCarried(stack);
            case INVENTORY -> {
                // 预检过有空位；万一还是整件塞不下，丢在玩家脚下也不让它凭空消失。
                if (!getPlayer().getInventory().add(stack)) {
                    getPlayer().drop(stack, false);
                }
            }
            // 走到这里的只能是样板：handler 已挡住「整张磁盘进编辑槽」，取出来的单张也必定是编码样板。
            case ENCODED_SLOT -> encodedPatternSlot.set(stack);
        }
        // 推一次：槽位内容与光标上的东西都靠这一个包到客户端（光标的同步在 broadcastChanges 里，见原版菜单）。
        broadcastChanges();
    }

    /** 落点满/被占时说明一句，否则玩家只看到点了没反应。 */
    private void notifyNoRoom(ExtractTarget target) {
        if (getPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.no_room."
                            + target.name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /** 盘里那张样板的序号已经无效（内容刚变过）时说明一句：不是落点问题，也不是网络缺料。 */
    private void notifyContentChanged() {
        if (getPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.content_changed"));
        }
    }

    /** 样板写进磁盘后给个回执，免得玩家不确定刚才那一下到底落没落盘。 */
    private void notifyPatternWritten(Component diskName) {
        if (getPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(
                    Component.translatable("gui.ae2_pattern_disk.encoding_terminal.written_to_disk", diskName));
        }
    }

    /**
     * 标记写入回执。与上传链路（{@link #notifyPatternWritten(Component)}）同一路：服务端发言、落在聊天栏——
     * 玩家看到的是「这一次右键到底写没写成」，而不用去磁盘提示里猜。
     */
    private void notifyMarkWritten(Component diskName) {
        if (getPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(
                    Component.translatable("gui.ae2_pattern_disk.encoding_terminal.mark_written", diskName));
        }
    }

    /** 没写成的回执：光标上有东西就说清是哪件认不出来，光标为空则只说没有可用的类别。 */
    /**
     * 菜单要不要给宿主摆升级槽。
     *
     * <p>默认不摆：面板版终端的高级编码能力不由升级卡给（产品口径），所以连槽都不出。无线版覆盖成
     * true——那边要放 AdvancedAE 的高级样板编码器。</p>
     */
    protected boolean supportsUpgradeSlots() {
        return false;
    }

    /**
     * 升级槽只暴露本模组这一套。
     *
     * <p>AE2 的 {@code MEStorageMenu} 在构造里已经无条件建过一套（判定是 {@code instanceof UpgradeCardItem}，
     * 不看登记表），而 AE2 没有移除服务端槽的 API。那套槽在界面文档里没有坐标、本来就画不出来，这里再把它们
     * 从「按语义取槽」的结果里剔掉：AE2WTLib 的升级面板正是按这个结果排布槽位的，留着它就会去摆 AE2 那两格
     * 与真库存同步的镜像格。</p>
     */
    @Override
    public java.util.List<net.minecraft.world.inventory.Slot> getSlots(SlotSemantic semantic) {
        var slots = super.getSlots(semantic);
        if (semantic != SlotSemantics.UPGRADE) {
            return slots;
        }
        var ours = new java.util.ArrayList<net.minecraft.world.inventory.Slot>(slots.size());
        for (var slot : slots) {
            if (slot instanceof TerminalUpgradeSlot) {
                ours.add(slot);
            }
        }
        return ours;
    }

    /**
     * 升级槽：准入改用「登记表」而不是 AE2 的物品类型判定。
     *
     * <p>AE2 的 {@code RestrictedInputSlot} 在 {@code UPGRADES} 语义下只认 {@code UpgradeCardItem} 的子类
     * （{@code Upgrades.isUpgradeCardItem} 内部就是一句 {@code instanceof}），**完全不看 {@code Upgrades}
     * 登记表**。AdvancedAE 的高级样板编码器是 Curios 饰品、不属于那个类，于是「在表里登记过」也照旧被拒：
     * 槽在那儿、卡拖进去弹回来、还没有任何提示。这里把判定换成库存自己的登记结果——登记过就能放。</p>
     */
    private static final class TerminalUpgradeSlot extends RestrictedInputSlot {

        private final IUpgradeInventory upgrades;

        TerminalUpgradeSlot(IUpgradeInventory upgrades, int index) {
            super(PlacableItemType.UPGRADES, upgrades, index);
            this.upgrades = upgrades;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (super.mayPlace(stack)) {
                return true;
            }
            // 回退只补「物品类型」那一条闸（AE2 只认 UpgradeCardItem）；其余闸在 super 里都是私有的，
            // 这里自己重放一遍，免得把卡放进一个已经被禁用/收起或不允许编辑的格里。
            return isActive() && !stack.isEmpty() && this.upgrades.getMaxInstalled(stack.getItem()) > 0;
        }
    }

    private void notifyMarkNotWritten() {
        if (!(getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        // 光标上拿的是哪件，服务端自己有（菜单的光标槽是同步的），不必让客户端报一遍。口径必须与客户端一致：
        // 两边都只看光标，主手/副手不算。
        var held = getCarried();
        if (held.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.mark_skipped"));
        } else {
            player.sendSystemMessage(Component.translatable(
                    "gui.ae2_pattern_disk.encoding_terminal.mark_unidentified", held.getHoverName()));
        }
    }

    /**
     * Binds the current recipe type to the disk identified by serial, as a mark in the disk's
     * {@code DISK_PREFIX} component. The disk's own name is left alone: the mark shows up in the disk's
     * tooltip instead of renaming the item, which used to make every disk of a kind look identical.
     * Renaming is a separate interaction (see {@link #renameDisk(long)}).
     */
    public void bindPrefix(long serial) {
        bindPrefix(serial, true, null);
    }

    /**
     * @param announceSkip 类别缺失、写不出标记时，是否在聊天栏说一句。玩家右键打标要说——他明确是冲着标记来的；
     *                     而编码后的顺手绑盘不说：那一刻已经在写盘路径上回执过成败，再补一句「标记未写入」
     *                     会被读成写盘失败。
     * @param markOverride 非 null 时直接用它当作要写的标记、跳过 {@link #deriveMarkId()}。编码上传路径用它
     *                     给雕凿档指定固定标记（雕凿不在 {@link EncodingMode} 里、也不对应任何配方类别）；
     *                     右键打标那条手势传 null，沿用「光标上工作方块的类别 / 当前模式」那一套。
     */
    private void bindPrefix(long serial, boolean announceSkip, @Nullable String markOverride) {
        if (isClientSide()) {
            // The mark depends on the imported recipe's category, which only the client knows, so it travels
            // as its own action just ahead of the bind.
            //
            // 这条分支会丢掉 markOverride：带它进来的只有 encode() 的服务端段（雕凿的固定标记在那里算），
            // 客户端永远不带。将来真要客户端带标记的话，得照转发类别那样多送一个 action。
            var category = pendingRecipeCategory;
            sendClientAction("setPendingRecipeCategory", category == null ? "" : category);
            sendClientAction(ACTION_BIND_PREFIX, serial);
            return;
        }

        var ref = diskIndex.refOf(serial);
        if (ref == null) {
            // 客户端列表可能比服务端旧：那张盘已经被拿走了。不提示的话，玩家只会看到点了没反应。
            notifyStaleTarget();
            return;
        }

        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) {
            // 同一个理由：列表里还有它，槽里已经不是了。
            notifyStaleTarget();
            return;
        }

        // 光标上没有可识别的工作方块、也没有刚导入的配方时，deriveMarkId() 给出的只是当前模式标记——那不是玩家
        // 对这张盘的判断，写下去只会把盘上原有的标记冲成「处理样板」。这种右键不写，并且跟上传链路一样把结果
        // 说进聊天栏：写没写成，玩家得看得到。
        //
        // 带着 markOverride 进来时不适用这条：雕凿的标记本来就是固定的，不需要「导入过类别」这个前提。
        if (markOverride == null && (pendingRecipeCategory == null || pendingRecipeCategory.isEmpty())) {
            LOGGER.info("Bind skipped for disk {}: nothing on the cursor and nothing imported", serial);
            if (announceSkip) {
                notifyMarkNotWritten();
            }
            return;
        }

        var mark = markOverride != null ? markOverride : deriveMarkId();
        LOGGER.info("Binding mark {} to disk {}", mark, serial);

        var updated = stack.copy();
        updated.set(AEPatternRegistries.DISK_PREFIX, mark);
        writeDiskSlot(inv, ref.slot(), updated);
        notifyMarkWritten(stack.getHoverName());
    }

    /**
     * 要绑到磁盘上的标记：导入过配方就用它自己的类别（同一台机器下的不同类别分得开），否则回退到编码模式。
     * 两者都存成 {@code #} 开头的标识符。
     *
     * <p>{@code #mode:} 那一支在绑盘链路上通常出不来：{@link #bindPrefix(long)} 会先判掉空的类别。例外是
     * 编码上传路径（{@link #uploadMark()}）——雕凿不在 {@link EncodingMode} 里、也没有配方类别，它带着
     * 一个固定标记进 {@link #bindPrefix(long, boolean, String)}，绕过了那个判断。要刻意给磁盘打模式标记
     * 还可以走 Shift+右键（{@link #bindSearchMark(long)}，它把搜索栏文本原样存下）。</p>
     *
     * <p>两套写法看起来是两种标记，但显示与搜索会把模式标记归一成对应的类别（见
     * {@link DiskMarkRules#categoryForMode}），所以玩家看到的、搜到的名字是一致的。</p>
     *
     * <p><b>已知行为（不是缺陷）</b>：高级档（{@code advancedMode}）不在 {@link EncodingMode} 里、切进去时也不改
     * {@code mode}，所以它<b>没有专属标记</b>——读到的 {@code mode} 是「进入高级档之前那个常规档」，
     * 写出来就是那个常规档的标记（从处理档进去就是处理档的）。
     * 已记在 {@code TODO.md} 的「功能缺口」一节里，后续审查不必再把它当新问题报。雕凿档因为样板语义不同
     * （记「把谁雕成谁」，与导入的配方类别无关），单独走 {@link #uploadMark()} 给了固定标记；若日后要给高级档
     * 补一个，照 {@link DiskMarkRules#CHISELING_MARK} 那一套加，且同样<b>只加在上传路径上</b>。</p>
     */
    public String deriveMarkId() {
        return DiskMarkRules.deriveMarkId(pendingRecipeCategory, this.mode);
    }

    /**
     * 从磁盘编码上传（顺位写盘）时该绑的标记：雕凿档用固定的模式标记——它的样板记的是「把谁雕成谁」，
     * 与导入的类别无关；其它档沿用 {@link #deriveMarkId()}。
     *
     * <p>这个雕凿分支只在**上传路径**上生效，没有写进 {@code deriveMarkId()}：右键打标是一条独立手势，
     * 它承诺「以光标上那个工作方块的配方类型覆写」（见 {@code tooltip.disk.right_click}），雕凿档下
     * 也该照办。</p>
     *
     * <p>只给雕凿开分支，是因为它的样板语义与常规档不同（记的是「把谁雕成谁」，与导入的配方类别无关）。
     * <b>高级档没有专属标记</b>——它继承进入本档之前那个常规档的，属已记录项（见 {@code TODO.md}）；
     * 不要顺手在这里补一个：那会改变磁盘上现有标记的含义，不是修复。</p>
     */
    private String uploadMark() {
        return this.chiselingMode ? DiskMarkRules.CHISELING_MARK : deriveMarkId();
    }

    /** The mark standing for an encoding mode, for disks marked without an imported recipe. */
    public void setPendingRecipeCategory(@Nullable String categoryId) {
        this.pendingRecipeCategory = categoryId;
    }

    /**
     * 导入配方（JEI/EMI 配方页的「编写样板」）的最后一步：记下这次导入的类别与一次导入事实，供搜索栏决定要不要填自己。
     *
     * <p>搜索栏的自动填充只认这一个入口。绑定标记、切换模式、放样板回流同样会改掉标记的值，但它们都不该
     * 动玩家正在用的搜索条件——所以判据是「发生了导入」，而不是「标记值变了」。</p>
     *
     * <p>用修订号而不是回调屏幕：导入那一下由配方页的转写处理器执行，它拿不到终端屏幕（也不该去拿）。
     * 修订号只记事实，屏幕下一帧自行读取，两边互不依赖对方存在。</p>
     */
    public void noteCategoryImported(@Nullable String categoryId) {
        this.lastImportedCategory = categoryId;
        this.categoryImportRevision++;
    }

    /** 导入配方发生过多少次；{@code 0} 表示还没导入过（屏幕据此判断「首次打开不填」）。 */
    public int getCategoryImportRevision() {
        return this.categoryImportRevision;
    }

    /**
     * 最近一次导入的类别，没导入过、或者那次没取到类别时是 {@code null}。填充用的是它而不是「当前类别」：
     * 后者在导入之后还可能被右键（光标上的工作方块）改掉、或被换模式清空，快照下来才能保证填的就是这次导入。
     */
    @Nullable
    public String getLastImportedCategory() {
        return this.lastImportedCategory;
    }

    private int categoryImportRevision;
    @Nullable
    private String lastImportedCategory;

    /**
     * 刚导入的配方类别（只有客户端知道），或者 {@code null}。给「光标上拿工作方块右键」当取舍提示用：光标上那个
     * 工作方块能跑这个类别时就用它——比同命名空间之类的启发式更贴切。
     */
    @Nullable
    public String getPendingRecipeCategory() {
        return pendingRecipeCategory;
    }

    /**
     * 客户端：写盘顺位候选（serial 按尝试顺序）。空数组表示这次编码不自动写盘。
     *
     * <p>候选与顺序只有客户端知道（屏幕上的列表、右键选中的那张盘都在那边），服务端拿到了就按顺序试，
     * 不再自己猜“该不该写”。</p>
     */
    private long[] clientAutoDisks = NO_DISKS;

    /** @see #clientAutoDisks */
    public void setClientAutoDisks(long[] serials) {
        this.clientAutoDisks = serials == null ? NO_DISKS : serials;
    }

    /**
     * 服务端：客户端报上来的候选，与 {@code ACTION_ENCODE} 成对使用，用后清空。
     */
    private long[] pendingAutoDisks = NO_DISKS;

    /** @see #pendingAutoDisks */
    private void setPendingAutoDisks(long[] serials) {
        this.pendingAutoDisks = serials == null ? NO_DISKS : serials;
    }

    /** The name the client wants to give a disk, for {@link #renameDisk(long)}. */
    private String pendingDiskName;

    /** 客户端要打到磁盘上的标记文本（Shift+右键），与 {@link #bindSearchMark(long)} 成对使用。 */
    private String pendingMarkText;

    /** Remembers the mark text the client resolved, for {@link #bindSearchMark(long)}. */
    public void setPendingMarkText(@Nullable String text) {
        this.pendingMarkText = text;
    }

    /**
     * 把搜索栏里写的标记打到磁盘上（Shift+右键）；文本为空则清掉这张盘的标记。文本带不带 # 前缀
     * 都行，统一按标记存。与 {@link #bindPrefix(long)} 不同，这条标记不来自导入的配方，而是玩家自己
     * 写/搜出来的，所以它能把任意一类标记标到任意一张盘上。
     */
    public void bindSearchMark(long serial) {
        if (isClientSide()) {
            sendClientAction("setPendingMarkText", pendingMarkText == null ? "" : pendingMarkText);
            sendClientAction("bindSearchMark", serial);
            return;
        }

        // 一次操作一个值，理由同 pendingDiskName。
        var text = pendingMarkText;
        pendingMarkText = null;
        // null 表示客户端根本没设过值（误用），空串表示要清标记，两者不能混。
        if (text == null) return;

        // 值来自客户端，服务端自己收紧：去掉控制字符与 §，再裁掉首尾空白。
        text = DiskMarkRules.sanitizeName(text.strip());
        // 只有“#”一个字符不算标记，当成空——否则盘上会多出一个看不出内容的标记。
        String mark = text.isEmpty() ? null : (text.startsWith("#") ? text : "#" + text);
        if (mark != null && mark.length() > DiskMarkRules.MAX_DISK_NAME_LENGTH) {
            mark = mark.substring(0, DiskMarkRules.MAX_DISK_NAME_LENGTH);
        }
        if (mark != null && mark.length() <= 1) {
            mark = null;
        }

        var ref = diskIndex.refOf(serial);
        if (ref == null) return;
        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) return;

        // 没什么可清的就别写：对无标记的盘重复 Shift+右键会白白重发一次列表。
        if (mark == null && stack.get(AEPatternRegistries.DISK_PREFIX.get()) == null) return;

        var updated = stack.copy();
        if (mark == null) {
            updated.remove(AEPatternRegistries.DISK_PREFIX);
        } else {
            updated.set(AEPatternRegistries.DISK_PREFIX, mark);
        }
        writeDiskSlot(inv, ref.slot(), updated);
    }

    /** Remembers the name the client resolved for the hovered disk, for {@link #renameDisk(long)}. */
    public void setPendingDiskName(@Nullable String name) {
        this.pendingDiskName = name;
    }

    /**
     * Renames the disk identified by serial to the name the client resolved. It travels as its own action
     * just ahead of the rename, the same way {@link #bindPrefix(long)} carries the recipe category: the name
     * comes from the mark's recipe category, which only the client can look up.
     */
    public void renameDisk(long serial) {
        if (isClientSide()) {
            sendClientAction("setPendingDiskName", pendingDiskName == null ? "" : pendingDiskName);
            sendClientAction(ACTION_RENAME_DISK, serial);
            return;
        }

        // 一次操作一个名字。留着会让下一个只发 rename、没发 setPendingDiskName 的调用沿用旧名。
        var name = pendingDiskName;
        pendingDiskName = null;
        if (name == null || name.isEmpty()) return;

        // 名字来自客户端，所以服务端要自己收紧一遍：改包客户端可以送任意长的串或不含格式字符的串。
        name = DiskMarkRules.sanitizeName(name);
        if (name.isEmpty()) return;

        var ref = diskIndex.refOf(serial);
        if (ref == null) return;

        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) return;

        var updated = stack.copy();
        updated.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        writeDiskSlot(inv, ref.slot(), updated);
    }

    /**
     * 样板存入磁盘后，把回退产生的空白样板还回去。样板本来就取自网络，所以优先入网；网络不在或满了
     * 就落玩家背包；背包也满则留在编码槽由玩家手动取走（不丢失）。
     */
    private void returnBlankPatternToStorage() {
        returnPatternToStorage(AEPatternRegistries.blankPattern());
    }

    /**
     * 把一张样板交回：优先网络存储，其次玩家背包，两处都放不下时退回编码槽——调用方已经清空编码槽，
     * 那是它唯一的去处。网络只吃下了一部分时，余量接着走同一条兜底链：不能因为「进去了一点」就把整张
     * 当作已退。
     *
     * <p>今天所有调用方都只退单件（编码槽本身限一件），所以「部分合入」在这些路径上不可达；这里仍按
     * {@code count} 推导余量，免得将来有人拿它退多件时把余量吞掉。与 NEO ECO 那边「替代物要进到网络里
     * 才允许清源」相比，这里把「已交到玩家手里」也算兑现——两边都不丢件，差别只在这条兜底链更长。</p>
     *
     * @param pattern 要退还的样板；空表示不退还（样板只是被搬了位置，物品并没有消失）
     */
    private void returnPatternToStorage(ItemStack pattern) {
        var remaining = pattern.copy();
        if (remaining.isEmpty()) {
            return;
        }
        var grid = getGrid();
        var storage = grid == null ? null : grid.getStorageService();
        if (storage != null) {
            long inserted = storage.getInventory().insert(AEItemKey.of(remaining), remaining.getCount(),
                    Actionable.MODULATE, IActionSource.ofPlayer(getPlayer()));
            if (inserted >= remaining.getCount()) {
                broadcastChanges();
                return;
            }
            if (inserted > 0) {
                remaining.shrink((int) inserted);
            }
        }
        // 背包 add 可能只收下一部分：传进去的就是 remaining 本身，剩下的量留在它手里，不会重复给。
        if (getPlayer().getInventory().add(remaining)) {
            broadcastChanges();
            return;
        }
        this.encodedPatternSlot.set(remaining);
        broadcastChanges();
    }

    // ---- NEO ECO AE Extension upload ----------------------------------------

    /**
     * Uploads the currently encoded pattern to the NEO ECO computation cluster.
     * Delegates to the integration-registered handler when neoecoae is present;
     * otherwise does nothing.
     */
    public void uploadPattern() {
        if (isClientSide()) {
            sendClientAction(ACTION_UPLOAD_PATTERN);
            return;
        }
        var h = uploadHandler;
        if (h != null) {
            h.accept(this);
        }
    }

    /**
     * @return the item in the encoded pattern slot.
     */
    public ItemStack getEncodedPatternItem() {
        return encodedPatternSlot.getItem();
    }

    /**
     * 编码槽本身。ExtendedAE Plus 的「上传到供应器」要在服务端直接读/清这个槽，见
     * {@code integration.extendedae_plus.ExtendedAEPlusUploadMenu}。
     */
    public Slot getEncodedPatternSlot() {
        return encodedPatternSlot;
    }

    /**
     * Clears the encoded pattern slot and returns a blank pattern to storage / the player's inventory.
     */
    public void clearEncodedPatternAndReturnBlank() {
        this.encodedPatternSlot.set(ItemStack.EMPTY);
        returnBlankPatternToStorage();
    }

    /**
     * 上传成功后清空编码槽。
     *
     * <p>与 {@link #clearEncodedPatternAndReturnBlank()} 的区别在“退多少”：上传方知道这次是被容器吃掉了
     * （物品真的没了，得补），还是只被搬进了槽位（物品还在网络里，补一张就是凭空多造），所以由它决定。
     *
     * @param replacement 退还的内容；空表示不用退
     */
    public void clearEncodedPatternAfterUpload(ItemStack replacement) {
        this.encodedPatternSlot.set(ItemStack.EMPTY);
        returnPatternToStorage(replacement);
    }

    // ---- 磁盘列表同步（服务端扫描 <-> 客户端渲染） ----

    /**
     * 服务端：扫描网格中所有样板磁盘宿主（ME样板磁盘供应器、批处理装配室等）的磁盘槽，
     * 指纹变化时重建 serial 映射并推送全量列表到客户端。serial 按“宿主位置 + 身份盐 + 槽位”
     * 这一值身份在菜单生命周期内稳定映射到同一个磁盘槽，与宿主适配器是否被重建无关。
     */
    private void syncDiskList() {
        syncDiskList(false);
    }

    /**
     * @param force re-send even when the fingerprint says nothing changed. The client asks for this before
     *              reading a disk's components for an action, because a mark written a moment ago may not
     *              have reached it yet.
     */
    private void syncDiskList(boolean force) {
        var entries = diskIndex.rebuild(collectDiskHosts(), force);
        if (entries == null) {
            return; // unchanged: skip full resend
        }
        sendPacketToClient(new DiskListPayload(entries));
        onDiskListRebuilt(entries);
    }

    /**
     * 子类扩展点：磁盘列表（连同序列号 ↔ 宿主映射）刚重建完。父类发的是扁平清单，子类可以再发它自己的视图数据。
     */
    protected void onDiskListRebuilt(java.util.List<DiskListPayload.DiskEntry> entries) {
    }

    /** 子类用：磁盘序列号对应的宿主；未知返回 {@code null}。索引是私有的，这里只开只读口。 */
    protected @org.jetbrains.annotations.Nullable IPatternDiskHost diskHostOf(long serial) {
        return diskIndex.hostOf(serial);
    }

    /** 子类用：磁盘序列号在宿主库存里的槽位；未知返回 -1。 */
    protected int diskSlotOf(long serial) {
        return diskIndex.slotOf(serial);
    }

    /**
     * Re-sends the disk list even when nothing seems to have changed. The client asks for this before an
     * action that reads a disk's components, since a mark written a moment ago may not have reached it yet.
     */
    public void refreshDiskList() {
        if (isClientSide()) {
            sendClientAction("refreshDiskList");
            return;
        }
        syncDiskList(true);
    }

    /**
     * 网格上所有支持样板磁盘的宿主：实现了 {@link IPatternDiskHost} 的机器，加上插件注册进来的收集器给出的
     * 宿主（“能把样板磁盘当内容物收着”的机器也算）。
     *
     * <p>同一个宿主只给一次（按身份去重）：插件给的是每帧新建的适配器对象，那样去不掉重，但同一次收集里重复
     * 给出的同一个对象能去掉。磁盘槽收集与管理终端的分组都走这里，两张表看到的是同一批机器。</p>
     *
     * <p>子类可读：管理终端要连“一张盘都没插”的机器一起列出来，所以不能只依赖磁盘清单。</p>
     */
    protected List<IPatternDiskHost> collectDiskHosts() {
        return PatternDiskApi.diskHosts(getGrid());
    }

    /**
     * 返回当前网格，或 null（未连接/未激活）。
     */
    @Nullable
    private IGrid getGrid() {
        var node = getGridNode();
        return node != null && node.isActive() ? node.getGrid() : null;
    }

    /**
     * 客户端：接收服务端推送的磁盘列表，供 Screen 每帧渲染。
     */
    public void receiveDiskList(List<DiskListPayload.DiskEntry> disks) {
        this.diskList = disks;
        this.diskListRevision++; // 客户端用它判断一次刷新是否真的到货了
    }

    /**
     * How many disk-list payloads this client has received. A screen that just asked for a refresh compares
     * this against the value it saw when asking, so it acts on the fresh list rather than the stale one.
     */
    public long getDiskListRevision() {
        return diskListRevision;
    }

    /**
     * 客户端：当前已知磁盘列表（serial + ItemStack）。
     */
    public List<DiskListPayload.DiskEntry> getDiskList() {
        return diskList;
    }

    /**
     * Returns the mark of the current encoding mode, for disks bound without an imported recipe category.
     */
    @Nullable
    public String getCurrentRecipePrefix() {
        if (isClientSide()) {
            return this.recipePrefix.isEmpty() ? null : this.recipePrefix;
        }
        return DiskMarkRules.modeMarkId(this.mode);
    }

    @Nullable
    private String resolveCurrentRecipePrefix() {
        // 兼容旧调用：当前模式的标记即当前前缀语义
        return DiskMarkRules.modeMarkId(this.mode);
    }

    // ---- Accessors -----------------------------------------------------------

    @Override
    public void setItem(int slotID, int stateId, ItemStack stack) {
        // 多态合成：材料变了才清缓存。槽位同步调得非常频，无条件清会让每次同步都重扫一遍合成配方；
        // 而内容真的变了时，清掉再重算才会重新走取配方路径，把选择权交回给 Polymorph。
        if (slotID >= 0 && slotID < this.slots.size()
                && !ItemStack.matches(this.slots.get(slotID).getItem(), stack)) {
            this.currentRecipe = null;
        }
        super.setItem(slotID, stateId, stack);
        this.getAndUpdateOutput();
    }

    @Override
    public void initializeContents(int stateId, List<ItemStack> items, ItemStack carried) {
        super.initializeContents(stateId, items, carried);
        this.getAndUpdateOutput();
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (isServerSide()) {
            if (this.mode != encodingLogic.getMode()) {
                this.setMode(encodingLogic.getMode());
            }
            // 高级档与 mode 并列，权威值同样在 logic 里：那是唯一跟着终端持久化的地方，关屏重开不掉档。
            this.advancedMode = encodingLogic.isAdvancedMode();
            this.chiselingMode = encodingLogic.isChiselingMode();
            this.overloadedMode = encodingLogic.isOverloadedMode();
            // 两张行表也回读：它们是服务端权威（客户端点一下只是先改自己的那份），关屏重开要从 logic 拉回来。
            this.overloadedSides = encodingLogic.getOverloadedSides();
            this.overloadedMatchModes = encodingLogic.getOverloadedMatchModes();
            this.substitute = encodingLogic.isSubstitution();
            this.substituteFluids = encodingLogic.isFluidSubstitution();
            // 两个开关的权威值在部件自己的 logic 里（与替换同款），服务端每 tick 回读进菜单字段再下发客户端。
            this.mergeSameItems = encodingLogic.isMergeSameItems();
            this.showUnmarkedDisks = encodingLogic.isShowUnmarkedDisks();
            this.stonecuttingRecipeId = encodingLogic.getStonecuttingRecipeId();
            this.recipePrefix = Objects.toString(resolveCurrentRecipePrefix(), "");
            syncDiskList();
            if (!legacyBlankPatternsMigrated) {
                legacyBlankPatternsMigrated = true;
                migrateLegacyBlankPatterns();
            }
            blankPatternSlot.updateMirror(getNetworkBlankPatternCount());
            this.advancedModeAvailable = hasAdvancedEncoder();
            if (!this.advancedModeAvailable && this.advancedMode) {
                // 卡被拿走了：模式立刻退出去，否则面板会停在一个再也读不出方向的空档上。
                this.setAdvancedMode(false);
            }
            // 过载档不靠升级卡，靠 AE2LT 在不在场——过载样板是它的物品。模组被摘掉时同样立刻退出去。
            this.overloadedModeAvailable = OverloadPatterns.isAvailable();
            if (!this.overloadedModeAvailable && this.overloadedMode) {
                this.setOverloadedMode(false);
            }
            this.chiselingModeAvailable = hasChiselingEncoder();
            // 卡不在、或这一档还没开放时，模式立刻退出去。后半句不是多余的：存档里可能残留一个为真的
            // chiselingMode（开放前用开发构建写进去的），而它是每 tick 从 logic 拉回来的——不在这儿清掉，
            // 界面会一直停在空白区。与「卡被拿走了就退出」同一口径。
            if ((!this.chiselingModeAvailable || !CHISELING_TIER_ENABLED) && this.chiselingMode) {
                this.setChiselingMode(false);
            }
        }
    }

    @Override
    public void onServerDataSync(it.unimi.dsi.fastutil.shorts.ShortSet updatedFields) {
        super.onServerDataSync(updatedFields);
        for (var slot : craftingGridSlots) slot.setActive(mode == EncodingMode.CRAFTING);
        craftOutputSlot.setActive(mode == EncodingMode.CRAFTING);
        for (var slot : processingInputSlots) slot.setActive(mode == EncodingMode.PROCESSING);
        for (var slot : processingOutputSlots) slot.setActive(mode == EncodingMode.PROCESSING);
        if (this.currentMode != this.mode) {
            this.encodingLogic.setMode(this.mode);
            this.getAndUpdateOutput();
            this.updateStonecuttingRecipes();
        }
    }

    @Override
    public void onSlotChange(Slot s) {
        if (s == this.stonecuttingInputSlot) {
            updateStonecuttingRecipes();
        }
        if (s == this.encodedPatternSlot && isServerSide()) {
            this.broadcastChanges();
        }
    }

    private void updateStonecuttingRecipes() {
        stonecuttingRecipes.clear();
        if (encodedInputsInv.getKey(0) instanceof AEItemKey itemKey) {
            var level = getPlayer().level();
            var recipeInput = new SingleRecipeInput(itemKey.toStack());
            stonecuttingRecipes.addAll(level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, recipeInput,
                    level));
        }
        if (stonecuttingRecipeId != null
                && stonecuttingRecipes.stream().noneMatch(r -> r.id().equals(stonecuttingRecipeId))) {
            stonecuttingRecipeId = null;
        }
    }

    /**
     * 在配方转移未把玩家认为的主输出放入正确槽位时，轮换已编码的处理输出。
     */
    public void cycleProcessingOutput() {
        if (isClientSide()) {
            sendClientAction(ACTION_CYCLE_PROCESSING_OUTPUT);
            return;
        }
        // 轮换的算术在 ProcessingOutputMath；这里只判「这个模式该不该轮换」。
        if (mode != EncodingMode.PROCESSING || !canCycleProcessingOutputs()) {
            return;
        }
        ProcessingOutputMath.cycle(processingOutputSlots);
    }

    // 仅当已编码多个处理输出时可轮换
    public boolean canCycleProcessingOutputs() {
        return mode == EncodingMode.PROCESSING && ProcessingOutputMath.canCycle(processingOutputSlots);
    }

    /**
     * 将处理配方的全部输入与输出（含主副产物）堆叠数量乘以指定倍数。
     */
    public void multiplyOutput(int factor) {
        if (isClientSide()) {
            sendClientAction(ACTION_MULTIPLY_OUTPUT, factor);
            return;
        }
        if (mode != EncodingMode.PROCESSING || factor <= 0) {
            return;
        }
        ProcessingOutputMath.multiply(encodedInputsInv, factor);
        ProcessingOutputMath.multiply(encodedOutputsInv, factor);
        broadcastChanges();
    }

    /**
     * 将处理配方的全部输入与输出（含主副产物）堆叠数量除以指定倍数。
     * 全有或全无：任一物品数量无法整除时整体不做处理。
     */
    public void divideOutput(int factor) {
        if (isClientSide()) {
            sendClientAction(ACTION_DIVIDE_OUTPUT, factor);
            return;
        }
        if (mode != EncodingMode.PROCESSING || factor <= 0) {
            return;
        }
        // 先校验两个库存中全部非空物品均可整除，任一不满足则整体不处理
        if (!ProcessingOutputMath.allDivisible(factor, encodedInputsInv, encodedOutputsInv)) {
            return;
        }
        ProcessingOutputMath.divide(encodedInputsInv, factor);
        ProcessingOutputMath.divide(encodedOutputsInv, factor);
        broadcastChanges();
    }

    public void clear() {
        if (isClientSide()) {
            sendClientAction(ACTION_CLEAR);
            return;
        }
        encodedInputsInv.clear();
        encodedOutputsInv.clear();
        // 雕凿输入也是一份输入，按「清空」的语义一并清掉。
        encodingLogic.getChiselingInputInv().clear();
        this.broadcastChanges();
        this.getAndUpdateOutput();
    }

    public EncodingMode getMode() { return this.mode; }

    // ---- 高级编码模式 --------------------------------------------------------

    /**
     * 高级编码模式编辑的是样板输出栏里那张**已编码**的高级样板：读出它的输入与「输入 → 接入面」表，让玩家
     * 逐个输入改面，再整张重写回去。
     *
     * <p>它不动样板的输入与输出，只动方向表——那正是 AdvancedAE 自己的编码器编不出来的东西，也是本模组的
     * 供应器按面投递时要读的东西。没有产物槽、也不碰编码按钮：产物就是输出栏里原来那张样板。</p>
     */
    public record AdvancedSideChange(int input, int side) {
    }

    /** 每个输入槽分配的面序号；-1 = 相邻。客户端与服务端都读它。 */
    public int advancedSideAt(int slot) {
        var parts = this.advancedSides.isEmpty() ? new String[0] : this.advancedSides.split(",");
        if (slot < 0 || slot >= parts.length) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[slot].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 是否停在高级编码模式（供编码路径判断要不要编成高级处理样板）。 */
    public boolean isAdvancedMode() {
        return this.advancedMode;
    }

    /** 是否停在过载编码模式（供编码路径判断要不要编成过载样板）。 */
    public boolean isOverloadedMode() {
        return this.overloadedMode;
    }

    /** 过载档第 row 行是输出还是输入。没写过的行按输入算。 */
    public boolean overloadedRowIsOutput(int row) {
        return flagAt(this.overloadedSides, row);
    }

    /** 过载档第 row 行是否忽略组件匹配。没写过的行按不忽略算——也就是开关的默认态。 */
    public boolean overloadedRowIgnoresComponents(int row) {
        return flagAt(this.overloadedMatchModes, row);
    }

    /** 逗号分隔的 0/1 表里取第 index 位；越界或格式不对一律当 false（“没配置过”的那个默认态）。 */
    private static boolean flagAt(String flags, int index) {
        if (index < 0 || flags == null || flags.isEmpty()) {
            return false;
        }
        var parts = flags.split(",");
        if (index >= parts.length) {
            return false;
        }
        try {
            return Integer.parseInt(parts[index].trim()) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 把逗号分隔的 0/1 表里的第 index 位改成 value（不够长就补齐），其余保持。 */
    private static String withFlag(String flags, int index, boolean value) {
        if (index < 0) {
            return flags == null ? "" : flags;
        }
        var parts = flags == null || flags.isEmpty() ? new String[0] : flags.split(",");
        var size = Math.max(index + 1, parts.length);
        var out = new StringBuilder();
        for (int i = 0; i < size; i++) {
            var current = i < parts.length && "1".equals(parts[i].trim());
            if (i == index) {
                current = value;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(current ? 1 : 0);
        }
        return out.toString();
    }

    /** 过载档某一行改了什么（物品属性 = 输入/输出，加上那个组件匹配开关）。 */
    public record OverloadedRowChange(int row, boolean output, boolean ignoreComponents) {
    }

    /**
     * 客户端点过载档某一行：本地先改（界面立刻响应），再发给服务端；服务端的改动会同步回来。
     *
     * <p>与高级档的方向按钮同口径：只改菜单上的两张表，样板一个字节也不动——它在点「编写样板」那一刻
     * 才并进去。</p>
     */
    public void setOverloadedRow(int row, boolean output, boolean ignoreComponents) {
        // 上界也要查：行号来自客户端，越界会把那张 0/1 表撑成一条没有意义的长串（并照样写进存档）。
        if (row < 0 || row >= encodedInputsInv.size()) {
            return;
        }
        if (isClientSide()) {
            applyOverloadedRow(row, output, ignoreComponents);
            sendClientAction(ACTION_SET_OVERLOADED_ROW,
                    new OverloadedRowChange(row, output, ignoreComponents));
        } else {
            applyOverloadedRow(row, output, ignoreComponents);
        }
    }

    /** 改本次编辑的过载行表，并把结果交给 logic 存住（关屏重开不掉）。 */
    private void applyOverloadedRow(int row, boolean output, boolean ignoreComponents) {
        this.overloadedSides = withFlag(this.overloadedSides, row, output);
        this.overloadedMatchModes = withFlag(this.overloadedMatchModes, row, ignoreComponents);
        this.encodingLogic.setOverloadedSides(this.overloadedSides);
        this.encodingLogic.setOverloadedMatchModes(this.overloadedMatchModes);
    }

    /**
     * 切过载编码模式。与高级/雕凿同形：客户端先换显示状态、服务端把权威值写进 logic。
     *
     * <p>不动输出栏那张样板，也不清编辑区：过载档的行就是编辑区那些槽，进去时看到的就是原来那批输入。
     * 退出时两张表照旧留着，下次进来接着用。</p>
     */
    public void setOverloadedMode(boolean on) {
        if (isClientSide()) {
            this.overloadedMode = on;
            sendClientAction(ACTION_SET_OVERLOADED_MODE, on);
            return;
        }
        this.overloadedMode = on;
        this.encodingLogic.setOverloadedMode(on);
    }

    /**
     * 编辑区每个输入槽分配的面，按 {@link AEKey} 索引；没分配（相邻）的不放键。
     *
     * <p>编码路径上的「同物品合并」会改变输入的下标，所以方向表按 key 递过去。</p>
     */
    @Override
    public java.util.Map<AEKey, Direction> advancedSidesByKey() {
        var out = new LinkedHashMap<AEKey, Direction>();
        for (int i = 0; i < encodedInputsInv.size(); i++) {
            var key = encodedInputsInv.getKey(i);
            if (key == null) {
                continue;
            }
            var side = advancedSideAt(i);
            // 没分配的格子显式放进一个 null（「未指定，交给供应器按相邻那面」），与上游自己的
            // AdvPatternEncoderMenu 同一写法（它对每个输入都 put(key, null)）。AdvancedAE 的编码会把它
            // 翻成 NULLDIR，缺键与 null 值等价——这里补全只是为了少一条隐含约定。
            out.put(key, side < 0 ? null : Direction.values()[side]);
        }
        return out;
    }

    /** 升级槽里是否装着高级样板编码器。 */
    private boolean hasAdvancedEncoder() {
        for (var stack : host.getUpgrades()) {
            if (!stack.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(AdvPatternSupport.ADVANCED_ENCODER)) {
                return true;
            }
        }
        return false;
    }

    /** 升级槽里是否装着雕凿样板编码器。与上一支同形：只认物品 id，不看数量。 */
    private boolean hasChiselingEncoder() {
        for (var stack : host.getUpgrades()) {
            if (ChiselingPatternEncoder.isEncoder(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 选中第几个雕凿候选。两侧都按同一份配方表算候选，所以只传序号——不传列表。
     *
     * <p>序号在服务端可能对不上（配方表是同步过来的，理论上与客户端同源，但服务端总是权威）：
     * 落盘时按序号现查，查不到就什么也不做，比写出一枚错的雕凿样板好。</p>
     */
    public void setChiseling(int index) {
        if (isClientSide()) {
            this.selectedChiseling = index;
            sendClientAction(ACTION_SET_CHISELING, index);
            return;
        }
        this.selectedChiseling = index;
    }

    /**
     * 切换雕凿编码模式。
     *
     * <p>与高级档不同，这一档不从输出栏那张样板里摊任何东西：雕凿的候选来自 Rechiseled 自己的配方表
     * （{@code ChiselingRecipeManager}），不来自样板。所以这里只切开关，权威值写进 logic 跟着终端持久化。</p>
     */
    public void setChiselingMode(boolean on) {
        if (isClientSide()) {
            // 客户端先换自己的显示状态（面板要立刻跟着变），权威值由服务端回读后下发。
            this.chiselingMode = on;
            sendClientAction(ACTION_SET_CHISELING_MODE, on);
            return;
        }
        this.chiselingMode = on;
        this.encodingLogic.setChiselingMode(on);
    }

    /**
     * 切换高级编码模式。进去时把输出栏那张样板的原材料拦进编辑区、方向归零；出来时只关开关。
     *
     * <p>不把方向写回任何样板：它只在点「编写样板」那一刻并进去（见
     * {@link PatternEncodingLogic#encodeProcessingPattern()}）。所以进出模式不会破坏输出栏里那张样板。</p>
     *
     * <p>接受**普通处理样板**：它就是「玩家已经编好的样板」，而高级化的意思正是给它的每个输入补一个接入
     * 面。要求先有一张高级样板会变成一个悖论——那样板本来就是这条路要产出的东西。</p>
     */
    public void setAdvancedMode(boolean on) {
        if (isClientSide()) {
            // 客户端只换自己的显示状态：那几格原材料得由服务端从样板里摊（那是世界数据）。
            this.advancedMode = on;
            this.advancedSides = "";
            sendClientAction(ACTION_SET_ADVANCED_MODE, on);
            return;
        }
        this.advancedMode = on;
        this.advancedSides = "";
        // 权威值写进 logic（与 mode 同处）：它们一起回答「当前在哪一档」，得跟着终端走。
        this.encodingLogic.setAdvancedMode(on);
        if (!on) {
            return;
        }
        var details = PatternDetailsHelper.decodePattern(this.encodedPatternSlot.getItem(), getLevel());
        // 普通处理样板与高级样板都得收：前者是「玩家已编好、想给它补面」的那张，后者是「回来改面」的那张。
        // 注意两者**不是**同一个类——AE2 的是 AEProcessingPattern，AdvancedAE 的 AdvProcessingPattern 只
        // 实现 IPatternDetails，所以不能只判 AE2 那个类。
        var isPlainProcessing = details instanceof AEProcessingPattern;
        var isAdvanced = AdvPatternSupport.isEditable(details);
        if (!isPlainProcessing && !isAdvanced) {
            // 输出栏里没有可编辑的处理样板：停在空面板上，并说清楚为什么。
            // 不回滚模式：玩家常常是先切进来、再往输出栏放样板；但一个空档配一句沉默很容易被当成坏了。
            if (getPlayer() instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.translatable(
                        "gui.ae2_pattern_disk.encoding_terminal.advanced_needs_pattern"));
            }
            return;
        }
        // 输入从样板的「实际材料表」读，而不是从 getInputs()：后者给的是每格的候选模板，倍数与替代品都在
        // 里面。高级样板走 AdvancedAE 的接口，普通处理样板直接读 AE2 自己那一份（getSparseInputs 是 public）。
        var inputs = isAdvanced
                ? AdvPatternSupport.sparseInputs(details)
                : ((AEProcessingPattern) details).getSparseInputs();
        if (inputs == null) {
            return;
        }
        encodedInputsInv.clear();
        for (int i = 0; i < inputs.size() && i < encodedInputsInv.size(); i++) {
            encodedInputsInv.setStack(i, inputs.get(i));
        }
        // 输出也要摊：编码路径要求首个输出非空（那是「主产物」的定义），空着必定编不出来。
        // 高级模式下 PROCESSING_OUTPUTS 槽是藏起来的，玩家也补不了，所以必须在这里给全。
        encodedOutputsInv.clear();
        var outputs = details.getOutputs();
        for (int i = 0; i < outputs.size() && i < encodedOutputsInv.size(); i++) {
            encodedOutputsInv.setStack(i, outputs.get(i));
        }

        // 把样板已有的面读回来。玩家进来常常是「看一眼再改一格」，不播种的话面板会把每一行都显示成
        // 「相邻」，那时点「编写样板」就把原有的面按 NULLDIR 写回去——配置被静默丢掉，还会顺着写盘落进磁盘。
        // 只有高级样板有这张表；普通处理样板没有面可读，保持全 -1。
        var existing = AdvPatternSupport.directionMap(details);
        if (existing != null && !existing.isEmpty()) {
            var builder = new StringBuilder();
            for (int i = 0; i < encodedInputsInv.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                var key = encodedInputsInv.getKey(i);
                var direction = key == null ? null : existing.get(key);
                builder.append(direction == null ? -1 : direction.ordinal());
            }
            this.advancedSides = builder.toString();
        }
    }

    /**
     * 客户端点某个方向按钮：本地先改（界面立刻响应），再发给服务端；服务端的改动会同步回来。
     */
    public void setAdvancedSide(int input, int side) {
        if (isClientSide()) {
            applyAdvancedSide(input, side);
            sendClientAction(ACTION_SET_ADVANCED_SIDE, new AdvancedSideChange(input, side));
        } else {
            applyAdvancedSide(input, side);
        }
    }

    /** 改本次编辑的方向表；样板一个字节也不动。 */
    private void applyAdvancedSide(int input, int side) {
        if (input < 0) {
            return;
        }
        var size = Math.max(input + 1, encodedInputsInv.size());
        var sides = new int[size];
        for (int i = 0; i < size; i++) {
            sides[i] = advancedSideAt(i);
        }
        sides[input] = side;
        var out = new StringBuilder();
        for (int value : sides) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        this.advancedSides = out.toString();
    }

    public void setMode(EncodingMode mode) {
        if (this.mode != mode && mode == EncodingMode.STONECUTTING) {
            updateStonecuttingRecipes();
        }
        if (isClientSide()) {
            // 手动换模式等于放弃刚才导入的那个配方：类别是绑盘时要写的标记，留着它会让下一次绑定写出
            // 一份与当前模式不符的标记。导入自己也会走 setMode，所以设置类别的一方要放在编码之后。
            pendingRecipeCategory = null;
            sendClientAction(ACTION_SET_MODE, mode);
        } else {
            this.mode = mode;
        }
    }

    // ---- IPatternEncodingHost：四套编码抽到 PatternEncodingLogic 之后，
    // 菜单只经这些访问器把状态交给它，自身的私有字段不再被编码逻辑直接触碰 ----

    @Override
    public EncodingMode getEncodingMode() {
        return this.mode;
    }

    @Override
    public ConfigInventory getEncodedInputs() {
        return this.encodedInputsInv;
    }

    @Override
    public ConfigInventory getEncodedOutputs() {
        return this.encodedOutputsInv;
    }

    @Override
    public boolean isEncodingSubstitute() {
        return this.isSubstitute();
    }

    @Override
    public boolean isEncodingSubstituteFluids() {
        return this.isSubstituteFluids();
    }

    @Override
    public boolean isEncodingMergeSameItems() {
        return this.isMergeSameItems();
    }

    @Override
    public @Nullable RecipeHolder<CraftingRecipe> getCurrentCraftingRecipe() {
        return this.currentRecipe;
    }

    @Override
    public ItemStack updateAndGetCraftingOutput() {
        return this.getAndUpdateOutput();
    }

    @Override
    public net.minecraft.world.level.Level getLevel() {
        return getPlayer().level();
    }

    public boolean isSubstitute() { return this.substitute; }
    public void setSubstitute(boolean v) {
        if (isClientSide()) sendClientAction("setSubstitution", v); else this.encodingLogic.setSubstitution(v);
    }
    public boolean isSubstituteFluids() { return this.substituteFluids; }
    public void setSubstituteFluids(boolean v) {
        if (isClientSide()) sendClientAction("setFluidSubstitution", v); else this.encodingLogic.setFluidSubstitution(v);
    }
    public boolean isMergeSameItems() { return this.mergeSameItems; }
    public void setMergeSameItems(boolean v) {
        // 与替换同款：值落在宿主 logic 上并即时存盘，菜单字段只是镜像（服务端每 tick 回读）。
        if (isClientSide()) sendClientAction("setMergeSameItems", v); else this.encodingLogic.setMergeSameItems(v);
    }
    public boolean isShowUnmarkedDisks() { return this.showUnmarkedDisks; }
    public void setShowUnmarkedDisks(boolean v) {
        if (isClientSide()) sendClientAction("setShowUnmarkedDisks", v); else this.encodingLogic.setShowUnmarkedDisks(v);
    }
    public @Nullable ResourceLocation getStonecuttingRecipeId() { return stonecuttingRecipeId; }
    public void setStonecuttingRecipeId(ResourceLocation id) {
        if (isClientSide()) sendClientAction("setStonecuttingRecipeId", id); else this.encodingLogic.setStonecuttingRecipeId(id);
    }

    /**
     * 玩家侧槽位的快捷移动（Shift+左键）。
     *
     * <p>AE2 的父类在「菜单里找不到真正的目标槽」时会退化成把物品写进第一个空 {@code FakeSlot}（不看
     * {@code mayPlace}，也不看那个槽当前是不是活的）——那条回退是给过滤器槽准备的（存储总线配置那种）。
     * 本屏的 FakeSlot 全是合成格、处理输入/输出、切石与锻造，写进去就是一份不消耗原物的鬼影，还会落到
     * 当前编码模式并不在用的槽里。这里把它挡掉：没有真目标槽时只走 {@code transferStackToMenu} 那条看得见的
     * 去处（编码终端＝已编码样板进编辑槽、其余进 ME 网络；管理终端＝什么都不收），其余留在原处。</p>
     *
     * <p>另有真目标槽时仍旧交给父类，它会按槽位语义的优先级分配；菜单侧往背包的移动也照旧。</p>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int idx) {
        if (isClientSide()) {
            return ItemStack.EMPTY; // 与服务端同一口径：这一下不由客户端自己算，等包回来（父类也这么拦）
        }
        if (idx < 0 || idx >= this.slots.size()) {
            return ItemStack.EMPTY; // 越界的槽号（改造过的客户端）什么都不做
        }
        var source = this.slots.get(idx);
        if (!isPlayerSideSlot(source) || source.getItem().isEmpty()) {
            return super.quickMoveStack(player, idx);
        }
        if (!source.mayPickup(player)) {
            return ItemStack.EMPTY;
        }

        var stack = source.getItem();
        if (!getQuickMoveDestinationSlots(stack, true).isEmpty()) {
            return super.quickMoveStack(player, idx);
        }

        // 别走父类那条回退：只走 transferStackToMenu 这条看得见的去处
        // （编码终端＝已编码样板进编辑槽、其余进 ME 网络；管理终端＝什么都不收），其余留在原处。
        int transferred = transferStackToMenu(stack.copy());
        if (transferred > 0) {
            source.remove(transferred);
        }
        return ItemStack.EMPTY;
    }

    @Override
    protected int transferStackToMenu(ItemStack input) {
        // 已编码的样板优先回编辑槽（AE2 的老规矩：拿着编好的样板 Shift+左键＝接着改它）；其余的照常规
        // 进 ME 网络——本屏有物品网格，送进去看得见（与 AE2 原版编码终端一致）。那个编码槽只吃
        // **已编码**样板，空白样板不在此列。管理终端把这条改成了 0（那边没有网络物品栏）。
        int initialCount = input.getCount();
        if (encodedPatternSlot.mayPlace(input)) {
            input = encodedPatternSlot.safeInsert(input);
            if (input.isEmpty()) {
                return initialCount;
            }
        }
        int transferred = initialCount - input.getCount();
        return transferred + super.transferStackToMenu(input);
    }

    public FakeSlot[] getCraftingGridSlots() { return craftingGridSlots; }
    public FakeSlot[] getProcessingInputSlots() { return processingInputSlots; }
    public FakeSlot[] getProcessingOutputSlots() { return processingOutputSlots; }
    public FakeSlot getStonecuttingInputSlot() { return stonecuttingInputSlot; }
    public FakeSlot getChiselingInputSlot() { return chiselingInputSlot; }
    public FakeSlot getSmithingTableTemplateSlot() { return smithingTableTemplateSlot; }
    public FakeSlot getSmithingTableBaseSlot() { return smithingTableBaseSlot; }
    public FakeSlot getSmithingTableAdditionSlot() { return smithingTableAdditionSlot; }
    public List<RecipeHolder<StonecutterRecipe>> getStonecuttingRecipes() { return stonecuttingRecipes; }
}