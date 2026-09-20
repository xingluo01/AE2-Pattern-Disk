package io.github.lounode.ae2pattern.common.menu;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.PatternTermSlot;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.util.ConfigInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.DiskEncodingLogic;
import io.github.lounode.ae2pattern.common.menu.slot.NetworkBlankPatternSlot;
import io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart;
import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskHostRegistry;
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
public class PatternDiskEncodingTermMenu extends MEStorageMenu {

    private static final int CRAFTING_GRID_WIDTH = 3;
    private static final int CRAFTING_GRID_HEIGHT = 3;
    private static final int CRAFTING_GRID_SLOTS = CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT;

    private static final String ACTION_ENCODE = "encode";
    private static final String ACTION_CLEAR = "clear";
    private static final String ACTION_SET_MODE = "setMode";
    private static final String ACTION_CYCLE_PROCESSING_OUTPUT = "cycleProcessingOutput";
    private static final String ACTION_MULTIPLY_OUTPUT = "multiplyOutput";
    private static final String ACTION_DIVIDE_OUTPUT = "divideOutput";
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

    private final PatternDiskEncodingTerminalPart host;
    private final DiskEncodingLogic encodingLogic;
    private final ConfigInventory encodedInputsInv;
    private final ConfigInventory encodedOutputsInv;

    private final FakeSlot[] craftingGridSlots = new FakeSlot[9];
    private final FakeSlot[] processingInputSlots = new FakeSlot[AEProcessingPattern.MAX_INPUT_SLOTS];
    private final FakeSlot[] processingOutputSlots = new FakeSlot[AEProcessingPattern.MAX_OUTPUT_SLOTS];
    private final FakeSlot stonecuttingInputSlot;
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

    /** serial → 磁盘所在供应器槽位的反查表（仅服务端使用）。 */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<DiskRef> diskRefs = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static long nextDiskSerial = Long.MIN_VALUE;
    /** 上次发送列表的指纹，避免无变化时重复全量推送。 */
    private int lastDiskFingerprint;
    private boolean fingerprintInitialized;

    private record DiskRef(IPatternDiskHost host, int slot) {
    }

    /**
     * A disk slot's value identity, built the same way the sync fingerprint does. Its purpose is to survive
     * hosts that hand out a fresh adapter object on every collect (the Neo ECO integration does), where
     * comparing {@link DiskRef} by reference would silently fail.
     */
    private record DiskSlotKey(BlockPos pos, int salt, int slot) {
    }

    /** The serial each disk slot last used, so a re-send can hand out the same one again. */
    private final java.util.Map<DiskSlotKey, Long> diskSerials = new java.util.HashMap<>();

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
            PatternDiskEncodingTerminalPart host) {
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
                this.encodedPatternSlot = new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.ENCODED_PATTERN,
                        encodingLogic.getEncodedPatternInv(), 0),
                SlotSemantics.ENCODED_PATTERN);
        this.encodedPatternSlot.setStackLimit(1);

        registerClientAction(ACTION_ENCODE, this::encode);
        registerClientAction(ACTION_CLEAR, this::clear);
        registerClientAction(ACTION_SET_MODE, EncodingMode.class, encodingLogic::setMode);
        registerClientAction(ACTION_CYCLE_PROCESSING_OUTPUT, this::cycleProcessingOutput);
        registerClientAction(ACTION_MULTIPLY_OUTPUT, Integer.class, this::multiplyOutput);
        registerClientAction(ACTION_DIVIDE_OUTPUT, Integer.class, this::divideOutput);
        registerClientAction("setSubstitution", Boolean.class, encodingLogic::setSubstitution);
        registerClientAction("setFluidSubstitution", Boolean.class, encodingLogic::setFluidSubstitution);
        registerClientAction("setStonecuttingRecipeId", ResourceLocation.class,
                encodingLogic::setStonecuttingRecipeId);
        registerClientAction(ACTION_TRANSFER_TO_DISK, Long.class, this::transferToDisk);
        registerClientAction(ACTION_EXTRACT_FROM_DISK, ExtractRequest.class, this::handleExtractFromDisk);
        registerClientAction(ACTION_BIND_PREFIX, Long.class, this::bindPrefix);
        registerClientAction("setPendingRecipeCategory", String.class, this::setPendingRecipeCategory);
        registerClientAction("setPendingAutoDisk", Long.class, this::setPendingAutoDisk);
        registerClientAction("setPendingAutoDiskCount", Integer.class, this::setPendingAutoDiskCount);
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

    public void encode() {
        if (isClientSide()) {
            // 配方类别只有客户端知道（导入时记下的），而服务端绑标记时要用它，所以像 bindPrefix 一样先单独送过去。
            // 搜索栏筛出的唯一那张盘的 serial 同理。
            var category = pendingRecipeCategory;
            sendClientAction("setPendingRecipeCategory", category == null ? "" : category);
            sendClientAction("setPendingAutoDisk", clientAutoDisk);
            sendClientAction("setPendingAutoDiskCount", clientAutoDiskCount);
            sendClientAction(ACTION_ENCODE);
            return;
        }
        // 先取值再清空，所以提前退出也不会把这次的报数留给下一次编码。
        var autoCount = pendingAutoDiskCount;
        pendingAutoDiskCount = 0;
        var auto = pendingAutoDisk;
        pendingAutoDisk = 0L;
        ItemStack encodedPattern = encodePattern();
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
            // 搜索栏筛完只剩一张盘时，刚编好的样板直接写进去——省掉「编出一个样板再点磁盘」两步。
            // 那张盘收不下（已满、锁定类型不符、主产物重复）由 transferToDisk 自己报原因；
            // 没有唯一目标时由客户端当场说明（张数只有那边知道）。
            if (autoCount == 1) {
                transferToDisk(auto);
            }
        } else {
            // 网格里没有可编码的东西时，如果编码槽里正停着一枚写好的样板，「编写样板」的意图就是把它写进目标
            // 磁盘（管理终端选中盘之后的快捷上传）。没有唯一目标时什么也不做：原来这里会把它清成空白样板，
            // 等于把玩家手里的样板销毁掉。
            var existing = this.encodedPatternSlot.getItem();
            if (PatternDetailsHelper.isEncodedPattern(existing)) {
                if (autoCount == 1) {
                    // 走既有的写盘路径：写进去、清空编码槽、退回空白样板，一处口径。
                    transferToDisk(auto);
                }
                return;
            }
            clearPattern();
        }
    }

    private void clearPattern() {
        var encodedPattern = this.encodedPatternSlot.getItem();
        if (PatternDetailsHelper.isEncodedPattern(encodedPattern)) {
            this.encodedPatternSlot.set(AEItems.BLANK_PATTERN.stack(encodedPattern.getCount()));
        }
    }

    @Nullable
    private ItemStack encodePattern() {
        return switch (this.mode) {
            case CRAFTING -> encodeCraftingPattern();
            case PROCESSING -> encodeProcessingPattern();
            case SMITHING_TABLE -> encodeSmithingTablePattern();
            case STONECUTTING -> encodeStonecuttingPattern();
        };
    }

    @Nullable
    private ItemStack encodeCraftingPattern() {
        var ingredients = new ItemStack[CRAFTING_GRID_SLOTS];
        boolean valid = false;
        for (int x = 0; x < ingredients.length; x++) {
            ingredients[x] = getEncodedCraftingIngredient(x);
            if (ingredients[x] == null) return null;
            else if (!ingredients[x].isEmpty()) valid = true;
        }
        if (!valid) return null;
        var result = getAndUpdateOutput();
        if (result.isEmpty() || currentRecipe == null) return null;
        return PatternDetailsHelper.encodeCraftingPattern(this.currentRecipe, ingredients, result, isSubstitute(),
                isSubstituteFluids());
    }

    @Nullable
    private ItemStack encodeProcessingPattern() {
        var inputs = new GenericStack[encodedInputsInv.size()];
        boolean valid = false;
        for (int slot = 0; slot < encodedInputsInv.size(); slot++) {
            inputs[slot] = encodedInputsInv.getStack(slot);
            if (inputs[slot] != null) valid = true;
        }
        if (!valid) return null;

        // 同物品合并：启用时相同 AEKey 的输入合并为单槽
        java.util.List<GenericStack> mergedInputs;
        if (isMergeSameItems()) {
            var byKey = new java.util.LinkedHashMap<AEKey, Long>();
            for (var in : inputs) {
                if (in != null) {
                    byKey.merge(in.what(), in.amount(), Long::sum);
                }
            }
            mergedInputs = new java.util.ArrayList<>();
            for (var e : byKey.entrySet()) {
                mergedInputs.add(new GenericStack(e.getKey(), e.getValue()));
            }
        } else {
            mergedInputs = new java.util.ArrayList<>(Arrays.asList(inputs));
            mergedInputs.removeIf(Objects::isNull);
        }

        var outputs = new GenericStack[encodedOutputsInv.size()];
        for (int slot = 0; slot < encodedOutputsInv.size(); slot++) {
            outputs[slot] = encodedOutputsInv.getStack(slot);
        }
        if (outputs[0] == null) return null;
        return PatternDetailsHelper.encodeProcessingPattern(mergedInputs, Arrays.asList(outputs));
    }

    @Nullable
    private ItemStack encodeSmithingTablePattern() {
        if (!(encodedInputsInv.getKey(0) instanceof AEItemKey template)
                || !(encodedInputsInv.getKey(1) instanceof AEItemKey base)
                || !(encodedInputsInv.getKey(2) instanceof AEItemKey addition)) {
            return null;
        }
        var input = new SmithingRecipeInput(template.toStack(), base.toStack(), addition.toStack());
        var level = getPlayer().level();
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMITHING, input, level).orElse(null);
        if (recipe == null) return null;
        var output = AEItemKey.of(recipe.value().assemble(input, level.registryAccess()));
        return PatternDetailsHelper.encodeSmithingTablePattern(recipe, template, base, addition, output,
                encodingLogic.isSubstitution());
    }

    @Nullable
    private ItemStack encodeStonecuttingPattern() {
        if (stonecuttingRecipeId == null) return null;
        if (!(encodedInputsInv.getKey(0) instanceof AEItemKey input)) return null;
        var recipeInput = new SingleRecipeInput(input.toStack());
        var level = getPlayer().level();
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.STONECUTTING, recipeInput, level,
                stonecuttingRecipeId).orElse(null);
        if (recipe == null) return null;
        var output = AEItemKey.of(recipe.value().getResultItem(level.registryAccess()));
        return PatternDetailsHelper.encodeStonecuttingPattern(recipe, input, output, encodingLogic.isSubstitution());
    }

    @Nullable
    private ItemStack getEncodedCraftingIngredient(int slot) {
        var what = encodedInputsInv.getKey(slot);
        if (what == null) return ItemStack.EMPTY;
        else if (what instanceof AEItemKey itemKey) return itemKey.toStack(1);
        else return null;
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

    private ItemStack getAndUpdateOutput() {
        var level = this.getPlayerInventory().player.level();
        var items = NonNullList.withSize(CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT, ItemStack.EMPTY);
        boolean invalidIngredients = false;
        for (int x = 0; x < items.size(); x++) {
            var stack = getEncodedCraftingIngredient(x);
            if (stack != null) items.set(x, stack);
            else invalidIngredients = true;
        }
        var input = CraftingInput.of(CRAFTING_GRID_WIDTH, CRAFTING_GRID_HEIGHT, items);
        if (this.currentRecipe == null || !this.currentRecipe.value().matches(input, level)) {
            this.currentRecipe = invalidIngredients ? null
                    : level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
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

        var encodedPattern = encodePattern();
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
        var encoded = encodedPatternSlot.getItem();
        if (encoded.isEmpty() || !PatternDetailsHelper.isEncodedPattern(encoded)) {
            return;
        }

        var ref = diskRefs.get(serial);
        if (ref == null) {
            // 客户端列表可能比服务端旧：那张盘已经被拿走了。不提示的话，玩家只会看到点了没反应。
            notifyStaleTarget();
            return;
        }
        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) {
            notifyStaleTarget();
            return;
        }

        // 接收判据（容量/锁定类型/主产物互斥）统一由 PatternDiskItem.canInsert/tryInsert 负责
        var level = getPlayer().level();
        var updated = stack.copy();
        if (disk.tryInsert(updated, encoded, level)) {
            writeDiskSlot(inv, ref.slot(), updated);
            // 样板已存入磁盘：编码槽清空，原编码样板回退为空白样板并按 ME网络→玩家背包→编码槽 优先级落位
            this.encodedPatternSlot.set(ItemStack.EMPTY);
            returnBlankPatternToStorage();
            notifyPatternWritten(stack.getHoverName());
        } else {
            // 写不进去时说明理由：不写提示的话，玩家只会看到样板留在编码槽里，不知道卡在哪一步。
            notifyDiskRefused(stack.getHoverName(), disk.whyCannotInsert(stack, encoded, level));
        }
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
        var ref = diskRefs.get(request.serial);
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
        if (isClientSide()) {
            // The mark depends on the imported recipe's category, which only the client knows, so it travels
            // as its own action just ahead of the bind.
            var category = pendingRecipeCategory;
            sendClientAction("setPendingRecipeCategory", category == null ? "" : category);
            sendClientAction(ACTION_BIND_PREFIX, serial);
            return;
        }

        var ref = diskRefs.get(serial);
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
        if (pendingRecipeCategory == null || pendingRecipeCategory.isEmpty()) {
            LOGGER.info("Bind skipped for disk {}: nothing on the cursor and nothing imported", serial);
            notifyMarkNotWritten();
            return;
        }

        var mark = deriveMarkId();
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
     * <p>{@code #mode:} 那一支现在只在「没导入过」时供显示层使用：绑盘这一路（{@link #bindPrefix(long)}）会先
     * 判掉空的类别，所以协议上写不出模式标记；要刻意给磁盘打模式标记得走 Shift+右键
     * （{@link #bindSearchMark(long)}，它把搜索栏文本原样存下）。</p>
     *
     * <p>两套写法看起来是两种标记，但显示与搜索会把模式标记归一成对应的类别（见
     * {@link #categoryForMode}），所以玩家看到的、搜到的名字是一致的。</p>
     */
    public String deriveMarkId() {
        return pendingRecipeCategory != null && !pendingRecipeCategory.isEmpty()
                ? "#" + pendingRecipeCategory
                : modeMarkId(this.mode);
    }

    /**
     * 编码模式对应的规范配方类别 id，没有公认类别时返回 null。“导入过”的盘用配方自己的类别，
     * “手动编码”的盘只能用模式，把模式映射到类别是为了让这两种盘叫同一个名字。
     */
    @Nullable
    public static String categoryForMode(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> "minecraft:crafting";
            case STONECUTTING -> "minecraft:stonecutting";
            case SMITHING_TABLE -> "minecraft:smithing";
            // 处理没有唯一的类别：不同机器各有各的 EMI 类别，只能保留模式自己的名字。
            case PROCESSING -> null;
        };
    }

    /** The mark standing for an encoding mode, for disks marked without an imported recipe. */
    public static String modeMarkId(EncodingMode mode) {
        return "#mode:" + mode.name().toLowerCase(Locale.ROOT);
    }

    /** Remembers the recipe category of the recipe just imported, for {@link #deriveMarkId()}. */
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
     * 客户端：样板磁盘搜索栏筛完剩下的张数，以及恰好一张时那台的 serial。
     *
     * <p>判断“有没有唯一目标”只看张数：serial 是从 {@code Long.MIN_VALUE} 开始自增的，恒为负数，
     * 拿它的符号当"没有"的标记会把每一张都误当成没有。</p>
     */
    private int clientAutoDiskCount;
    /** 只在 {@link #clientAutoDiskCount} 为 1 时有意义；其余时候这里的值是占位，不参与查表。 */
    private long clientAutoDisk;

    /** @see #clientAutoDiskCount */
    public int getClientAutoDiskCount() {
        return clientAutoDiskCount;
    }

    /** @see #clientAutoDiskCount */
    public void setClientAutoDisk(int count, long serial) {
        this.clientAutoDiskCount = count;
        this.clientAutoDisk = serial;
    }

    /**
     * 服务端：客户端报上来的张数与唯一目标，与 {@code ACTION_ENCODE} 成对使用，用后清空。
     */
    private int pendingAutoDiskCount;
    /** 只在 {@link #pendingAutoDiskCount} 为 1 时有意义；其余时候这里的值是占位，不参与查表。 */
    private long pendingAutoDisk;

    /** @see #pendingAutoDiskCount */
    public void setPendingAutoDiskCount(int count) {
        this.pendingAutoDiskCount = count;
    }

    /** @see #pendingAutoDiskCount */
    public void setPendingAutoDisk(long serial) {
        this.pendingAutoDisk = serial;
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
        text = DISALLOWED_NAME_CHARS.matcher(text).replaceAll("").strip();
        // 只有“#”一个字符不算标记，当成空——否则盘上会多出一个看不出内容的标记。
        String mark = text.isEmpty() ? null : (text.startsWith("#") ? text : "#" + text);
        if (mark != null && mark.length() > MAX_DISK_NAME_LENGTH) {
            mark = mark.substring(0, MAX_DISK_NAME_LENGTH);
        }
        if (mark != null && mark.length() <= 1) {
            mark = null;
        }

        var ref = diskRefs.get(serial);
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

    /** 磁盘名的上限，与原版铁砧一致。 */
    private static final int MAX_DISK_NAME_LENGTH = 50;

    /** 名字里不允许出现的字符：控制字符与 § 格式码。客户端送来的串不能带着它们进物品组件。 */
    private static final Pattern DISALLOWED_NAME_CHARS = Pattern.compile("[\\p{Cntrl}\u00a7]");

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
        name = DISALLOWED_NAME_CHARS.matcher(name).replaceAll("");
        if (name.length() > MAX_DISK_NAME_LENGTH) {
            name = name.substring(0, MAX_DISK_NAME_LENGTH);
        }
        if (name.isEmpty()) return;

        var ref = diskRefs.get(serial);
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
        var grid = getGrid();
        var storage = grid == null ? null : grid.getStorageService();
        if (storage != null) {
            var inserted = storage.getInventory().insert(AEItemKey.of(AEItems.BLANK_PATTERN), 1,
                    Actionable.MODULATE, IActionSource.ofPlayer(getPlayer()));
            if (inserted > 0) {
                broadcastChanges();
                return;
            }
        }
        if (getPlayer().getInventory().add(AEPatternRegistries.blankPattern())) {
            broadcastChanges();
            return;
        }
        // 都放不下：退回编码槽。调用方已经把编码槽清空了，这里是那一个样板唯一的去处。
        this.encodedPatternSlot.set(AEPatternRegistries.blankPattern());
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
        // 收集当前网格中所有磁盘宿主的磁盘槽（item 类型为 PatternDiskItem 的非空槽）
        var hosts = collectDiskHosts();
        var slots = new java.util.ArrayList<DiskRef>();
        for (var host : hosts) {
            collectHostDisks(host, slots);
        }

        // 宿主名单也进指纹：新插一台还没插盘的供应器、或把最后一盘抽走，"磁盘槽"那部分指纹一点没变，
        // 但表要跟着变（管理终端要能看见没插盘的机器以及它还剩多少空槽）。
        int fingerprint = computeDiskFingerprint(slots, hosts);
        if (!force && fingerprintInitialized && fingerprint == lastDiskFingerprint) {
            return; // unchanged: skip full resend
        }
        lastDiskFingerprint = fingerprint;
        fingerprintInitialized = true;

        // Rebuild serial mapping and full packet. Serials stay attached to the same disk slot across
        // refreshes - matched by value, not by adapter object - because the client uses them to name the disk
        // it is acting on, and handing out new ones would make an in-flight action point at the wrong disk.
        var previousSerials = new java.util.HashMap<>(diskSerials);
        diskSerials.clear();
        diskRefs.clear();
        var entries = new java.util.ArrayList<DiskListPayload.DiskEntry>();
        for (var ref : slots) {
            var key = new DiskSlotKey(ref.host().getBlockPos(), ref.host().getIdentitySalt(), ref.slot());
            var serial = previousSerials.get(key);
            if (serial == null) {
                serial = nextDiskSerial++;
            }
            diskSerials.put(key, serial);
            diskRefs.put(serial, ref);
            var stack = ref.host().getDiskInventory().getStackInSlot(ref.slot());
            entries.add(new DiskListPayload.DiskEntry(serial, stack.copy()));
        }
        sendPacketToClient(new DiskListPayload(entries));
        onDiskListRebuilt(entries);
    }

    /**
     * 子类扩展点：磁盘列表（连同序列号 ↔ 宿主映射）刚重建完。父类发的是扁平清单，子类可以再发它自己的视图数据。
     */
    protected void onDiskListRebuilt(java.util.List<DiskListPayload.DiskEntry> entries) {
    }

    /** 子类用：磁盘序列号对应的宿主；未知返回 {@code null}。父类的 diskRefs 是私有的，这里只开只读口。 */
    protected @org.jetbrains.annotations.Nullable IPatternDiskHost diskHostOf(long serial) {
        var ref = diskRefs.get(serial);
        return ref == null ? null : ref.host();
    }

    /** 子类用：磁盘序列号在宿主库存里的槽位；未知返回 -1。 */
    protected int diskSlotOf(long serial) {
        var ref = diskRefs.get(serial);
        return ref == null ? -1 : ref.slot();
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
        var grid = getGrid();
        if (grid == null) {
            return List.of();
        }

        var seen = java.util.Collections
                .newSetFromMap(new java.util.IdentityHashMap<IPatternDiskHost, Boolean>());
        var hosts = new java.util.ArrayList<IPatternDiskHost>();

        for (var machineClass : grid.getMachineClasses()) {
            if (machineClass == null || !IPatternDiskHost.class.isAssignableFrom(machineClass)) {
                continue;
            }
            for (var machine : grid.getActiveMachines(machineClass)) {
                if (machine instanceof IPatternDiskHost host && seen.add(host)) {
                    hosts.add(host);
                }
            }
        }

        for (var host : PatternDiskHostRegistry.collectExtra(grid)) {
            if (host != null && seen.add(host)) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    /** Appends every pattern disk currently sitting in {@code host}'s disk inventory. */
    private static void collectHostDisks(IPatternDiskHost host, List<DiskRef> slots) {
        var inv = host.getDiskInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) continue;
            slots.add(new DiskRef(host, i));
        }
    }

    /**
     * Fingerprint over the set of disk slots: item id, slot index, host position and the host's own
     * identity salt (two panels on one cable share a position).
     */
    private static int computeDiskFingerprint(List<DiskRef> slots, List<IPatternDiskHost> hosts) {
        int hash = 1;
        for (var host : hosts) {
            // 位置 + 身份盐就认定了宿主（同一根电缆上的两个面板位置相同、盐不同）。
            hash = 31 * hash + host.getBlockPos().hashCode();
            hash = 31 * hash + host.getIdentitySalt();
        }
        for (var ref : slots) {
            var stack = ref.host().getDiskInventory().getStackInSlot(ref.slot());
            hash = 31 * hash + net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(stack.getItem());
            hash = 31 * hash + stack.getComponentsPatch().hashCode();
            hash = 31 * hash + ref.host().getBlockPos().hashCode();
            hash = 31 * hash + ref.host().getIdentitySalt();
            hash = 31 * hash + ref.slot();
        }
        return hash;
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
        return modeMarkId(this.mode);
    }

    @Nullable
    private String resolveCurrentRecipePrefix() {
        // 兼容旧调用：当前模式的标记即当前前缀语义
        return modeMarkId(this.mode);
    }

    // ---- Accessors -----------------------------------------------------------

    @Override
    public void setItem(int slotID, int stateId, ItemStack stack) {
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
        } else {
            if (mode != EncodingMode.PROCESSING) {
                return;
            }
            // 仅有 0/1 个非空输出时无可轮换，直接返回，避免单输出被清空
            if (!canCycleProcessingOutputs()) {
                return;
            }

            var newOutputs = new ItemStack[getProcessingOutputSlots().length];
            for (int i = 0; i < processingOutputSlots.length; i++) {
                newOutputs[i] = ItemStack.EMPTY;
                if (!processingOutputSlots[i].getItem().isEmpty()) {
                    // Search for the next, skipping empty slots
                    for (int j = 1; j < processingOutputSlots.length; j++) {
                        var nextItem = processingOutputSlots[(i + j) % processingOutputSlots.length].getItem();
                        if (!nextItem.isEmpty()) {
                            newOutputs[i] = nextItem;
                            break;
                        }
                    }
                }
            }

            for (int i = 0; i < newOutputs.length; i++) {
                processingOutputSlots[i].set(newOutputs[i]);
            }
        }
    }

    // 仅当已编码多个处理输出时可轮换
    public boolean canCycleProcessingOutputs() {
        return mode == EncodingMode.PROCESSING
                && Arrays.stream(processingOutputSlots).filter(s -> !s.getItem().isEmpty()).count() > 1;
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
        multiplyInventory(encodedInputsInv, factor);
        multiplyInventory(encodedOutputsInv, factor);
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
        for (var inv : new ConfigInventory[] { encodedInputsInv, encodedOutputsInv }) {
            for (int i = 0; i < inv.size(); i++) {
                var stack = inv.getStack(i);
                if (stack == null) {
                    continue;
                }
                if (stack.amount() <= 0 || stack.amount() % factor != 0) {
                    return;
                }
            }
        }
        divideInventory(encodedInputsInv, factor);
        divideInventory(encodedOutputsInv, factor);
        broadcastChanges();
    }

    /** 按槽位遍历倍乘全部非空堆叠，避免压缩索引错位。 */
    private static void multiplyInventory(ConfigInventory inv, int factor) {
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null) {
                inv.setStack(i, new GenericStack(stack.what(), stack.amount() * factor));
            }
        }
    }

    /** 按槽位遍历倍除全部非空堆叠，避免压缩索引错位。 */
    private static void divideInventory(ConfigInventory inv, int factor) {
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null) {
                inv.setStack(i, new GenericStack(stack.what(), stack.amount() / factor));
            }
        }
    }

    public void clear() {
        if (isClientSide()) {
            sendClientAction(ACTION_CLEAR);
            return;
        }
        encodedInputsInv.clear();
        encodedOutputsInv.clear();
        this.broadcastChanges();
        this.getAndUpdateOutput();
    }

    public EncodingMode getMode() { return this.mode; }

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

    @Override
    protected int transferStackToMenu(ItemStack input) {
        int initialCount = input.getCount();
        if (encodedPatternSlot.mayPlace(input)) {
            input = encodedPatternSlot.safeInsert(input);
            if (input.isEmpty()) return initialCount;
        }
        int transferred = initialCount - input.getCount();
        return transferred + super.transferStackToMenu(input);
    }

    public FakeSlot[] getCraftingGridSlots() { return craftingGridSlots; }
    public FakeSlot[] getProcessingInputSlots() { return processingInputSlots; }
    public FakeSlot[] getProcessingOutputSlots() { return processingOutputSlots; }
    public FakeSlot getStonecuttingInputSlot() { return stonecuttingInputSlot; }
    public FakeSlot getSmithingTableTemplateSlot() { return smithingTableTemplateSlot; }
    public FakeSlot getSmithingTableBaseSlot() { return smithingTableBaseSlot; }
    public FakeSlot getSmithingTableAdditionSlot() { return smithingTableAdditionSlot; }
    public List<RecipeHolder<StonecutterRecipe>> getStonecuttingRecipes() { return stonecuttingRecipes; }
    public PatternDiskEncodingTerminalPart getHostPart() { return host; }
}