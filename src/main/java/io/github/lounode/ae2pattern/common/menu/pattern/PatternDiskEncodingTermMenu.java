package io.github.lounode.ae2pattern.common.menu.pattern;

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
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import io.github.lounode.ae2pattern.config.AEPDCommonConfig;
import io.github.lounode.ae2pattern.integration.ae2lt.OverloadPatterns;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingRecipes;

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
import appeng.api.stacks.GenericStack;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
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
import io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic;
import io.github.lounode.ae2pattern.common.menu.slot.BlankPatternSlot;
import io.github.lounode.ae2pattern.common.menu.slot.PatternOutputSlot;
import io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart;
import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.api.PatternClassifier;
import io.github.lounode.ae2pattern.network.DiskListPayload;
import io.github.lounode.ae2pattern.common.menu.AbstractPatternDiskTermMenu;
import io.github.lounode.ae2pattern.common.menu.IPatternDiskTerminalHost;

/**
 * Menu for the pattern disk encoding terminal. Extends {@link MEStorageMenu} to inherit network
 * storage access (disk scanning) and the item terminal infrastructure.
 *
 * <p>Core encoding workflow mirrors AE2's {@code PatternEncodingTermMenu}: a mode-adaptive encoding
 * grid (crafting 3x3 / processing in-out / smithing / stonecutting), blank + encoded pattern slots,
 * and encode/clear client actions. On top of that, this terminal exposes the network pattern disk
 * list: click a disk to write the currently encoded pattern into it, right-click to bind the mini
 * search box text as that disk's mark ({@link #bindSearchMark(long)}), and a mini search bar.</p>
 */
public class PatternDiskEncodingTermMenu extends EncodingTermStateMenu implements IPatternEncodingHost {

    private static final int CRAFTING_GRID_WIDTH = 3;
    private static final int CRAFTING_GRID_HEIGHT = 3;
    private static final int CRAFTING_GRID_SLOTS = CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT;



    /** Set by NeoECOIntegration when neoecoae is present. Null when absent. */
    @Nullable
    /**
     * 标记这条链横跨两侧：识别在客户端（配方查看器只有客户端有），落盘在服务端，中间隔着一次客户端动作。
     * 这一笔日志是唯一能把「没识别出来」与「识别出来却没送达」分开的证据——后者只会表现为标记退回模式。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.mark");


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
        super(menuType, id, ip, host);

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
        // 空白样板槽是终端自己的实物缓冲：由网络补满、编码先扣它（见 BlankPatternSlot）；编码槽只收已编好的样板。
        // 两个槽与它们那本账都交给 PatternDock；菜单只负责把它们登记给 AE2。
        var blank = new BlankPatternSlot(encodingLogic.getBlankPatternInv(), 0);
        this.addSlot(blank, SlotSemantics.BLANK_PATTERN);
        var encoded = new PatternOutputSlot(encodingLogic.getEncodedPatternInv(), 0);
        this.addSlot(encoded, SlotSemantics.ENCODED_PATTERN);
        encoded.setStackLimit(1);
        this.dock = new PatternDock(encoded, blank, this::getGrid, this::getActionSource,
                () -> energySource, this::getPlayer, this::broadcastChanges);
        this.transfer = new PatternDiskTransfer(this);
        this.binder = new DiskMarkBinder(diskIndex, this::writeDiskSlot);

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
        registerClientAction(ACTION_CYCLE_PROCESSING_INPUT, this::cycleProcessingInput);
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
        registerClientAction("setPendingMarkText", String.class, this::setPendingMarkText);
        registerClientAction("bindSearchMark", Long.class, this::bindSearchMark);
        registerClientAction("refreshDiskList", this::refreshDiskList);
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
            // 提示统一由 encode() 在确实什么都没写之后处理；现在这一类不再出声。
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
     * 这几点不再单独出声（对应档位的空转在 AE2 原生里同样静默）。</p>
     */
    @Nullable
    private ItemStack encodeOverloadedPattern() {
        // 源就是输出栏那张样板：摊行读的也是它（见 seedOverloadedRowsFromPattern），两边必须同源，
        // 否则行的下标对上的是另一张样板的槽。
        var source = dock.encoded();
        if (source.isEmpty() || !PatternDetailsHelper.isEncodedPattern(source)) {
            return null;
        }
        int rows = overloadedRowCount();
        if (rows <= 0) {
            // 一行都没摊出来 = 进档时输出栏那张样板 AE2LT 解不开（那时它不会写下任何行）。
            return null;
        }
        var isOutput = new boolean[rows];
        var ignoresComponents = new boolean[rows];
        for (int row = 0; row < rows; row++) {
            isOutput[row] = overloadedRowIsOutput(row);
            ignoresComponents[row] = overloadedRowIgnoresComponents(row);
        }
        return OverloadPatterns.encode(source, isOutput, ignoresComponents, getLevel());
    }

    /** 过载档已摊出的行数。两张表按行序记，项数就是行数（没进过档、或样板解不开时是 0）。 */
    private int overloadedRowCount() {
        var sides = this.overloadedSides;
        return sides == null || sides.isEmpty() ? 0 : sides.split(",").length;
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
            var encodeOutput = dock.encoded();
            if (!encodeOutput.isEmpty()
                    && !PatternDetailsHelper.isEncodedPattern(encodeOutput)
                    && !AEItems.BLANK_PATTERN.is(encodeOutput)) {
                return;
            } else if (encodeOutput.isEmpty()) {
                // 先扣终端槽里的那一份，槽空了才向网络要（见 consumeBlankPattern）。扣不到就静默收手——
                // AE2 原生的编码终端在没空白样板时也是直接 return（源码里就写着 "no blanks."），不提示玩家。
                if (!consumeBlankPattern()) {
                    return;
                }
            }
            dock.setEncoded(encodedPattern);
            // 基线跟上：这一枚是本菜单按当前档位自己编出来的，不是玩家放进去的（理由见 adoptOutputPatternAsBaseline）。
            adoptOutputPatternAsBaseline();
            // 搜索栏有内容时，刚编好的样板按列表顺序顺位写进第一张能收的盘——省掉「编出一个样板再点
            // 磁盘」两步。候选由客户端给出（顺序就是屏幕上的顺序）；全部写不进时由顺位路径报原因。
            if (autoDisks.length > 0) {
                // 写进去了才顺手把标记写成它的工作方块：写盘被拒（容量/重复产出/类型锁定）时盘里没这份
                // 样板，再去改标记只会让盘与样板对不上。
                if (transfer.transferTo(autoDisks) && autoDisks.length == 1) {
                    bindMarkIfUnmarked(autoDisks[0]);
                }
            }
        } else {
            // 网格里没有可编码的东西时，如果编码槽里正停着一枚写好的样板，「编写样板」的意图就是把它写进目标
            // 磁盘（管理终端选中盘之后的快捷上传）。没有唯一目标时什么也不做：原来这里会把它清成空白样板，
            // 等于把玩家手里的样板销毁掉。
            var existing = dock.encoded();
            if (PatternDetailsHelper.isEncodedPattern(existing)) {
                if (autoDisks.length > 0) {
                    // 走既有的写盘路径：写进去、清空编码槽、退回空白样板，一处口径。
                    // 同样只在真写进去之后才绑标记（理由见上一处调用点）。
                    if (transfer.transferTo(autoDisks) && autoDisks.length == 1) {
                        bindMarkIfUnmarked(autoDisks[0]);
                    }
                }
                return;
            }
            clearPattern();
        }
    }

    /**
     * 服务端给玩家一句聊天栏反馈——本终端所有回执的唯一出口。
     *
     * <p>开关就在这里落地：一条消息一个，在 {@code ae2_pattern_disk-common.toml} 里（服务端当场写进聊天栏
     * 的那句话，客户端配置拦不住）。关掉只让这句话不说，动作本身照做——这些全是回执与拒绝原因，不是流程的
     * 一部分。</p>
     *
     * <p>非服务端（客户端手上的菜单没有收发这包的那一线）自然不发，与各调用点原来的写法一致。</p>
     */
    void tell(String key, Object... args) {
        if (!AEPDCommonConfig.isMessageShown(key)) {
            return;
        }
        if (getPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable(key, args));
        }
    }

    private void clearPattern() {
        dock.clearToBlank();
    }

    // ---- 空白样板：账在 PatternDock 里，这里只转调 -------------------------------------

    /** 服务端每 tick 补一次料，并把「网络还剩多少」写进同步字段。 */
    @Override
    protected void refillBlankPatternSlot() {
        networkBlankPatternCount = (int) Math.min(dock.refill(), Integer.MAX_VALUE);
    }

    /** 取一张空白样板给这次编码用（槽优先、网络兜底，见 {@link PatternDock}）。 */
    boolean consumeBlankPattern() {
        return dock.consume();
    }

    /**
     * 客户端：现在能不能编码——槽里有空白样板，或者网络里还有（数量由服务端同步，见
     * {@link #networkBlankPatternCount}），或者编码槽里停着一枚可直接覆盖的样板（空白载体、已编码样板）。
     * 这只是提前拦住，真正的裁决仍在服务端。
     */
    public boolean canEncode() {
        var encodedOutput = dock.encoded();
        return PatternDock.isBlank(dock.blankSlot().getItem())
                || networkBlankPatternCount > 0
                || PatternDock.isBlank(encodedOutput)
                || PatternDetailsHelper.isEncodedPattern(encodedOutput);
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

    @Override
    protected ItemStack getAndUpdateOutput() {
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


    // ---- Accessors -----------------------------------------------------------


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
        // 与同物品合并同款：本字段是编码路径直接读的那份（见 PatternEncodingLogic 经宿主接口取），
        // 先写本地再派发，同一 tick 内先改开关再编码就不会用旧值。
        this.substitute = v;
        if (isClientSide()) {
            sendClientAction("setSubstitution", v);
        } else {
            this.encodingLogic.setSubstitution(v);
        }
    }
    public boolean isSubstituteFluids() { return this.substituteFluids; }
    public void setSubstituteFluids(boolean v) {
        this.substituteFluids = v;
        if (isClientSide()) {
            sendClientAction("setFluidSubstitution", v);
        } else {
            this.encodingLogic.setFluidSubstitution(v);
        }
    }
    public boolean isMergeSameItems() { return this.mergeSameItems; }
    public void setMergeSameItems(boolean v) {
        // 与替换同款：权威值落在宿主 logic 上并即时存盘，服务端每 tick 把它回读进这个镜像字段。
        // 但两个分支都顺手写一次本字段，理由有两条：
        //   服务端——编码路径读的是这个镜像（PatternEncodingLogic 经宿主接口取），只写 logic 的话，
        //   同一 tick 内先改开关再点编码就会用旧值；
        //   客户端——先写本地，按钮与面板立刻响应，不必等下一帧的服务端广播，也避免这一帧里
        //   「按钮说开着、编码按关着算」的错位。
        this.mergeSameItems = v;
        if (isClientSide()) {
            sendClientAction("setMergeSameItems", v);
        } else {
            this.encodingLogic.setMergeSameItems(v);
        }
    }
    public boolean isShowUnmarkedDisks() { return this.showUnmarkedDisks; }
    public void setShowUnmarkedDisks(boolean v) {
        if (isClientSide()) sendClientAction("setShowUnmarkedDisks", v); else this.encodingLogic.setShowUnmarkedDisks(v);
    }
    public @Nullable ResourceLocation getStonecuttingRecipeId() { return stonecuttingRecipeId; }
    public void setStonecuttingRecipeId(ResourceLocation id) {
        if (isClientSide()) sendClientAction("setStonecuttingRecipeId", id); else this.encodingLogic.setStonecuttingRecipeId(id);
    }

    @Override
    protected int transferStackToMenu(ItemStack input) {
        // 先槽后网络：空白样板落空白样板槽（满了才继续往下），已编码样板回编码槽（AE2 的老规矩：拿着编好的
        // 样板 Shift+左键＝接着改它），剩下的才进 ME 网络——本屏有物品网格，送进去看得见（与 AE2 原版一致）。
        // 管理终端把网络那半改成不进（那边没有网络物品栏），槽那半照旧，见 insertIntoPatternSlots。
        int initialCount = input.getCount();
        var rest = insertIntoPatternSlots(input);
        int placed = initialCount - rest.getCount();
        if (rest.isEmpty()) {
            return placed;
        }
        return placed + super.transferStackToMenu(rest);
    }

    /**
     * 把物品放进终端自己的两格样板槽：空白样板优先落空白样板槽，已编码样板落编码槽，都不收则原样返回。
     *
     * <p>这一层单独抽出来是给管理终端用的：那边不进网络，但两格槽照样收东西（它继承本类，只把网络那半关掉）。
     * 空白槽在编码槽之前——两张槽的准入不重叠，顺序不影响结果，但写成「先装料、后成品」读起来才顺。</p>
     */
    protected ItemStack insertIntoPatternSlots(ItemStack input) {
        var rest = input;
        if (dock.blankSlot().mayPlace(rest)) {
            rest = dock.blankSlot().safeInsert(rest);
        }
        if (!rest.isEmpty() && dock.encodedSlot().mayPlace(rest)) {
            rest = dock.encodedSlot().safeInsert(rest);
        }
        return rest;
    }

    /**
     * 从网络物品网格里 Shift 取货时，已编码样板先进编码槽，而不是直接落玩家背包——「接着改这份样板」是那一步的
     * 意图，AE2 原生终端也是这个口径。AE2 默认的目的地只含玩家侧槽，所以这里给它补一格并排在最前。
     *
     * <p>只在 {@code fromPlayerSide == false}（从网络往背包搬）且物品确实是已编码样板时才动；其余情形原样返回，
     * 免得影响背包 → 槽那侧已有的优先级。</p>
     */
    @Override
    protected List<Slot> getQuickMoveDestinationSlots(ItemStack stackToMove, boolean fromPlayerSide) {
        var destinations = super.getQuickMoveDestinationSlots(stackToMove, fromPlayerSide);
        if (fromPlayerSide
                || !PatternDetailsHelper.isEncodedPattern(stackToMove)
                || !dock.encodedSlot().mayPlace(stackToMove)) {
            return destinations;
        }
        var reordered = new ArrayList<Slot>(destinations.size() + 1);
        reordered.add(dock.encodedSlot());
        reordered.addAll(destinations);
        return reordered;
    }

    /**
     * 本终端有物品网格，送进去看得见：把快捷移动的网络出口接回 {@code MEStorageMenu}
     * （见基类 {@link AbstractPatternDiskTermMenu#hasNetworkQuickMoveTarget 的开关}）。
     */
    @Override
    protected boolean hasNetworkQuickMoveTarget() {
        return true;
    }

    public FakeSlot[] getCraftingGridSlots() { return craftingGridSlots; }
    public FakeSlot[] getProcessingInputSlots() { return processingInputSlots; }
    public FakeSlot[] getProcessingOutputSlots() { return processingOutputSlots; }
    public FakeSlot getStonecuttingInputSlot() { return stonecuttingInputSlot; }
    public FakeSlot getChiselingInputSlot() { return chiselingInputSlot; }
    public FakeSlot getSmithingTableTemplateSlot() { return smithingTableTemplateSlot; }
    public FakeSlot getSmithingTableBaseSlot() { return smithingTableBaseSlot; }
    public FakeSlot getSmithingTableAdditionSlot() { return smithingTableAdditionSlot; }
}