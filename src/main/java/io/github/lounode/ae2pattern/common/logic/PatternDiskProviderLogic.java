package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.neoforge.capabilities.Capabilities;
import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.me.helpers.MachineSource;
import appeng.me.storage.CompositeStorage;
import appeng.parts.AEBasePart;
import appeng.parts.automation.StackWorldBehaviors;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.logic.BoostMergeLimit;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.DiskSlotVersions;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTier;
import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.api.PatternDiskContents;
import io.github.lounode.ae2pattern.integration.advancedae.AdvPatternSupport;

/**
 * A {@link PatternProviderLogic} whose available patterns are expanded from the contents of inserted
 * pattern disks, instead of a fixed slot-per-pattern inventory.
 *
 * <p>We do NOT touch AE2's private {@code patterns}/{@code patternInputs} fields. Instead we write the
 * disk patterns into the parent's own {@code patternInventory} (the {@code mirror} field below) and let the
 * parent's {@link #updatePatterns()} decode and register them. This stays within public API.</p>
 *
 * <p><b>镜像栏对外只读</b>（{@link #getPatternInv()}）：那栏只是磁盘的派生副本，外部写进去的样板不会落到
 * 任何磁盘上，却会被 `updatePatterns()` 当成可用样板注册进网络，于是成为「网络看得见、磁盘里没有」的幻影
 * 样板——磁盘一变就被抹掉，拆机也不掉落。写样板必须走 {@code getTerminalPatternInventory()}。</p>
 *
 * <p>The disk inventory is supplied lazily via a {@link Supplier} so this logic may be constructed during
 * the block-entity's parent constructor before the disk inventory field is initialized.</p>
 */
public class PatternDiskProviderLogic extends PatternProviderLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternDiskProviderLogic.class);

    private final Supplier<AppEngInternalInventory> diskInventorySupplier;

    /** 宿主与节点自己留一份：父类那两个字段是 private，而多面投递要靠它们定位邻居。 */
    private final PatternProviderLogicHost host;

    /** 与父类同源的动作源（同一个 {@code MachineSource} 形态，挂在同一个网格节点上）。 */
    private final IActionSource actionSource;

    /**
     * 镜像样板栏的原件（磁盘展开出的样板副本，<b>不是真身</b>）：真身在磁盘物品组件里，终端的读写走
     * {@code getTerminalPatternInventory()}。只在本类与子类内部用（重建与清空）；不对外开访问器，那等于
     * 给只读化留一个绕过口。
     *
     * <p>标记式镜像设备的样板来自网格上别的机器，但写的是同一份镜像、同一套只读语义。</p>
     */
    protected final InternalInventory mirror;

    /** 主节点：子类要扫整张网格（标记式镜像设备从别人的磁盘取样板）。 */
    protected final IManagedGridNode mainNode;

    /** {@link #getPatternInv()} 交出去的那份只读视图，构造一次、长期复用。 */
    private final InternalInventory readOnlyMirror;

    /**
     * 上次重建时的磁盘内容快照（每槽一项），由 {@link DiskSlotVersions#ofInventory} 生成、用
     * {@link DiskSlotVersions#sameAs} 比较；{@code null} 表示还没重建过。
     *
     * <p>跳过未变的重建很重要——重建会整体重写镜像——而这项检查本身的成本必须低于它跳过的工作；快照为什么
     * 能用引用比较、以及它对「原地修改内容」看不见，见 {@link DiskSlotVersions} 的类注释。</p>
     */
    private List<Object> lastDiskVersions;

    // ---- 本模组的供应器设置（AE2 的 Settings 枚举不可扩展，这几项自带 NBT 与菜单同步） ----

    public static final String NBT_BLOCKING_MODE = "blockingMode";
    public static final String NBT_BOOST_ENABLED = "boostEnabled";
    public static final String NBT_BOOST_LIMIT = "boostLimit";
    public static final String NBT_EXTRACTION_MODE = "extractionMode";

    /** 上限输入栏的可接受范围：真值在 {@link BoostMergeLimit#MAX_LIMIT}。保留这个别名是因为界面侧仍按机器类取它。 */
    public static final int MAX_BOOST_LIMIT = BoostMergeLimit.MAX_LIMIT;

    /**
     * 发配倍增的合并额度。额度在 {@link BoostMergeLimit}（目前只有本机在用）：它量的是
     * 「这一批攒了多少份合成所需的输入」，单位是份（样板输入倍数），不是交接次数也不是 tick。
     */
    private BoostMergeLimit mergeLimit = new BoostMergeLimit(0);

    private ProviderBlockingMode blockingMode = ProviderBlockingMode.DEFAULT;
    private boolean boostEnabled;
    private int boostLimit;

    /** 产物抽取模式：不抽 / 每 20 tick / 带退避的快速档。 */
    private ProviderExtractionMode extractionMode = ProviderExtractionMode.OFF;
    /** 还要等多少 tick 才再抽一次；0 表示下一次 tick 就抽。 */
    private int extractionCooldown;
    /** 快速档当前间隔（退避用，抽到就回落到 1）。 */
    private int extractionInterval = ProviderExtractionPacing.FAST_START_INTERVAL;

    /**
     * 发配倍增的合并表：每张样板一条，按到达顺序。值里的每个 {@code KeyCounter[]} 是 CPU 交来的一份完整输入。
     *
     * <p>只在 {@link #boostEnabled} 时非空。发配倍增期间材料在这里，不在父类的发送队列里，所以拆机必须由
     * {@link #addDrops} 掉出去。</p>
     */
    private final Map<IPatternDetails, List<KeyCounter[]>> pendingBatches = new LinkedHashMap<>();
    /** 最近一次收到交付的游戏时间，用来判定「发送方停手了」。 */
    private long pendingBatchTick = -1L;

    /**
     * 读档时把攒批的原始条目暂存到这里，等首个 tick（有 level）再解码成样板。
     *
     * <p>{@code readFromNBT} 只拿到 {@link HolderLookup.Provider}，没有 {@code Level}，而
     * {@code PatternDetailsHelper.decodePattern} 必须要 level。所以读档阶段只搬运原始 tag，不在那里解码。</p>
     *
     * <p>存的是「编码样板物品 + 每份的扁平输入」，不存 {@code IPatternDetails}：样板是磁盘内容的派生态，
     * 每次重建都是新实例，只有物品形态能跨存档。</p>
     */
    private List<PendingBatchRestore> pendingRestore = List.of();

    /** 一条待还原的批：样板物品 + 每份的输入总量（每份一个 ListTag）。 */
    private record PendingBatchRestore(CompoundTag pattern, List<ListTag> batches) {}

    /** 攒批表与待还原表的 NBT 键。 */
    private static final String NBT_PENDING_BATCHES = "pendingBatches";
    private static final String NBT_PENDING_PATTERN = "pattern";
    private static final String NBT_PENDING_INPUTS = "inputs";
    private static final String NBT_PENDING_RETURNS = "pendingReturns";

    /**
     * 退回网络失败的材料，等网络可用了再退。
     *
     * <p>与 {@link #pendingRestore} 同一种性质：材料是 CPU 已经记过账的交付，网络没接上（断电、被拆下来）
     * 时<b>既不能丢也不能掉在地上</b>——掉成物品就成了不可回滚，而且机器在无人加载的区块里时会直接被清除。
     * 所以先留在这里，通电后每 {@link BoostMergeLimit#QUIET_TICKS} 个 tick 重试一次。</p>
     *
     * <p>它也进存档：重试窗口可能刚好跨到一次存档，不写就白等了。</p>
     */
    private final List<KeyCounter> pendingReturns = new ArrayList<>();

    /** 下一次尝试退回网络的时间，避免网络长时间不通时每 tick 刷一遍。 */
    private long nextReturnAttempt;

    /**
     * 写进存档的撺批份数上限。
     *
     * <p>撺批表在“份数上限填 0”（不设限）时没有上界，而每份都要写一组 tag。不封的话一张大订单可以把存档
     * 撑到数 MB，区块存档和网络同步都跟着变慢。超过的部分不写盘——它们仍在内存里、仍会正常投递，只是存档
     * 不背这个量；真碰上崩服就会丢，所以配一次限流告警把玩家引向“填个数字”。</p>
     */
    private static final int MAX_PERSISTED_BATCHES = 512;

    /** 全部已装样板的输入集合缓存（阻挡档判据用）；{@link #updatePatterns()} 时作废。 */
    private Set<AEKey> cachedPatternInputs;

    /** 全部已装样板的输出集合缓存（产物抽取白名单）；{@link #updatePatterns()} 时作废。 */
    private Set<AEKey> cachedPatternOutputs;

    /** 只报一次的「抽取后塞不回目标」纸条，避免目标长期拒收时刷屏。 */
    private final Set<AEKey> extractionReturnWarned = new HashSet<>();

    /** 连续多少次抽取都没抽到任何东西时打一条诊断（含面数 / 可读存储 / 白名单大小）。 */
    private static final int EXTRACTION_DIAGNOSE_AFTER = 64;
    private int extractionMissStreak;
    /** 每隔这么多 tick 做一次全视图扫描（其余轮只查正对面）：既省 cache 注册，又不漏「产物在别的面」。 */
    private static final long FULL_SWEEP_INTERVAL = 40L;
    private static final Direction[] DIRECTIONS = Direction.values();
    /** 侧视图连续这么多轮没产出就停掉粘性全扫。 */
    private static final int STICKY_IDLE_LIMIT = 20;
    private long lastFullSweep;
    /** 换档后首轮要立刻做一次全视图扫描（否则最长要等 {@link #FULL_SWEEP_INTERVAL} tick 才看得到别的面）。 */
    private boolean extractionSweepDue = true;
    /** 已报过异常的视图（"坐标:标签" 字符串键：视图对象每轮新建，用身份做键既拦不住重复又会吊住 cache）。 */
    private final Set<String> extractionViewWarned = new HashSet<>();
    /** 侧视图上一轮抽到过东西：下一轮仍做全视图扫描，否则 FAST 档对「产物只在侧面」的机器会退化成 2 秒一次。 */
    private boolean sweepSticky;
    private int sweepStickyIdle;
    /** 本轮是否需要收集「目标里看得到什么」这类贵信息（只在首轮与 64 次那轮开）。 */
    /** 每个面拿到了哪些 AEKeyType 的存储包装（判断「没暴露物品」还是「暴露了但槽空」的关键证据）。 */
    /** 每个面的 IItemHandler 到底存不存在、有几个槽、槽里是什么——区分「没暴露」与「暴露了但看不到产物」。 */
    /** 本轮在目标里看到的、恰好属于白名单的 key 数量（>0 说明「容器里确实有样板产物」）。 */
    /** 上一次打周期性诊断的 tick。 */

    /** 只报一次的「部分投出」纸条，避免同一样板长期短少时刷屏。 */
    private final Set<IPatternDetails> partialWarned = new HashSet<>();

    public PatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            int diskSlots, Supplier<AppEngInternalInventory> diskInventorySupplier) {
        this(mainNode, host, diskSlots, diskInventorySupplier, null);
    }

    /**
     * 让调用方直接定镜像槽数：镜像设备没有自己的磁盘槽，容量没法按槽数推。
     *
     * @param mirrorSlots 显式镜像槽数；{@code null} 时仍按磁盘槽数推导
     */
    protected PatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            int diskSlots, Supplier<AppEngInternalInventory> diskInventorySupplier,
            @Nullable Integer mirrorSlots) {
        super(mainNode, host, mirrorSlots != null ? mirrorSlots : mirrorCapacity(diskSlots));
        this.diskInventorySupplier = diskInventorySupplier;
        this.lastDiskVersions = null;
        this.host = host;
        this.mainNode = mainNode;
        this.actionSource = new MachineSource(mainNode::getNode);
        // 父构造已经把镜像栏建好，这里抓住原件：覆写后的 getPatternInv() 只读，重建与清空要用原件。
        this.mirror = super.getPatternInv();
        this.readOnlyMirror = new ReadOnlyMirror();
    }

    /**
     * 镜像栏的只读视图，见 {@link ReadOnlyMirror}。
     *
     * <p>这条口子必须自己堵：AE2 的 {@code PatternProviderLogicHost#getTerminalPatternInventory()} 只是默认
     * 实现（返回 {@code getLogic().getPatternInv()}），第三方上传器完全可以绕过它直接写镜像。ExtendedAE Plus
     * 的「上传到装配矩阵」就是按类名匹配到本设备后这么写的（它的 {@code findAllMatrixPatternInventories}）。
     * 写进来的样板会进合成清单、却没有任何磁盘背书：磁盘一变就被重建抹掉，拆机也不掉落。</p>
     */
    @Override
    public InternalInventory getPatternInv() {
        // 构造期（父构造尚未返回）还没有建出这份视图；那时只有本类自己会问到，原样交回原件。
        return readOnlyMirror != null ? readOnlyMirror : super.getPatternInv();
    }

    /**
     * 只读的镜像栏：读转发给原件（返回副本，避免调用方改到共享的 ItemStack），写与取一律拒绝。
     *
     * <p>取出也要拒绝：镜像里的每张样板都还在某张磁盘上，放行等于免费复制。AE2 内部只用私有字段
     * {@code patternInventory}，不经过 {@link #getPatternInv()}，所以拒绝不影响解码与投递。</p>
     *
     * <p>日志带 5 秒冷却：AE2 的 {@code addItems} 在大库存上逐槽试写，一次上传会问上百次，逐次记一行就是刷屏。</p>
     */
    private final class ReadOnlyMirror implements InternalInventory {

        private static final long REFUSAL_LOG_COOLDOWN_NANOS = 5_000_000_000L;

        private long lastRefusalLogNanos;

        @Override
        public int size() {
            return mirror.size();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= mirror.size()) {
                return ItemStack.EMPTY;
            }
            return mirror.getStackInSlot(slot).copy();
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            refuse("setItemDirect");
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            // 模拟不是写尝试：上传器会满仓逐槽探一遍，那一步不该占日志。
            if (!simulate) {
                refuse("insertItem");
            }
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (simulate || amount <= 0 || getStackInSlot(slot).isEmpty()) {
                return ItemStack.EMPTY;
            }
            refuse("extractItem");
            return ItemStack.EMPTY;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            // 样板按单张存；写又全拒，这个数只影响显示。
            return 1;
        }

        @Override
        public void clear() {
            // 逐槽走 setItemDirect 只会把冷却日志刷满，空终态也拿不到：原件只有本类能清。
            refuse("clear");
        }

        private void refuse(String action) {
            long now = System.nanoTime();
            if (now - lastRefusalLogNanos < REFUSAL_LOG_COOLDOWN_NANOS) {
                return;
            }
            lastRefusalLogNanos = now;
            LOGGER.warn("样板磁盘供应器在 {} 拒绝了外部对镜像样板栏的 {}：那栏只是磁盘的副本，写进去的样板会变成"
                    + "既不在磁盘上、也不进掉落的幻影样板；写样板请走 getTerminalPatternInventory()",
                    where(), action);
        }

        private String where() {
            var blockEntity = host.getBlockEntity();
            return blockEntity == null ? "未知位置" : String.valueOf(blockEntity.getBlockPos());
        }
    }

    /**
     * 高级处理样板的「多面输入」：一张样板若给每个输入各指定了一个接入面，投递时就按那张表决定「这份材料
     * 从哪一面接进邻居」。
     *
     * <p><b>面选的是接入方式，不是邻居</b>——邻居照旧由候选方向决定，与普通投递同一套。AdvancedAE 自己也
     * 就是这么做的（它的 {@code AdvPatternProviderLogic.pushPattern} 遍历候选方向、对每个方向试一次方向
     * 投递，失败再走普通投递），而它的 {@code AdvPatternProviderTargetCache.find} 也只是把查 ME 能力留给
     * 「邻居对着我」的那一面、把声明面交给外部存储策略查询。声明面对 AE2 自己的容器与机器没有影响，它真正
     * 起作用的地方是那些按面注册了外部存储策略的机器（管道、带面限定的接入器）。</p>
     *
     * <p>探不到 AdvancedAE、样板没声明面、或这个方向上整批投不进去，都原样交给父类——退回默认行为，不少投
     * 也不崩。</p>
     *
     * <p><b>一处已知取舍</b>：父类的 {@code onPushPatternSuccess} 是 private，这条路（含机器分支）成功后
     * 调不到它，于是「锁定合成」的解锁（直到脉冲 / 直到结果）不发生——开着锁的供应器推完一张多面样板会保持
     * 锁定，直到玩家手动解开。要补上只能等 AE2 把它露出来；在子类重写一遍锁语义会与父类那几个同样 private
     * 的字段脱节。</p>
     */
    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (boostEnabled || blockingMode == ProviderBlockingMode.SMART) {
            return pushWithOwnRules(patternDetails, inputHolder);
        }
        if (!AdvPatternSupport.hasDirectionalInputs(patternDetails)) {
            return super.pushPattern(patternDetails, inputHolder);
        }
        // 与父类同一批前置守卫。方向投递是另一条路，不是绕过它们的特权：这四条里「锁定合成」最要紧——
        // AE2 全仓只在父类的 pushPattern 里读它，漏掉这一条就等于这类样板上的锁完全失效，开着锁也照推。
        if (!canPush(patternDetails)) {
            return false;
        }
        if (pushDirectionally(patternDetails, inputHolder)) {
            return true;
        }
        return super.pushPattern(patternDetails, inputHolder);
    }

    /** 父类 {@code pushPattern} 开头那四条守卫，自己的投递路径必须逐条重放。 */
    private boolean canPush(IPatternDetails patternDetails) {
        return !isBusy() && mainNode.isActive()
                && getCraftingLockedReason() == LockCraftingMode.NONE
                && getAvailablePatterns().contains(patternDetails);
    }

    // ---- 智能阻挡 / 智能倍增：自己的投递路径 ---------------------------------

    /**
     * 智能阻挡与发配倍增走这里。
     *
     * <p>倍增时合并交接：CPU 交来的多份先存着，等它停交（或攒满上限、或攒满一个静默窗口）再一次性投给目标。
     * 停交没有公开信号：方块形态由宿主 {@code serverTick} 每 tick 逼一次，面板形态没有 {@code IGridTickable}
     * 服务槽（被 AE2 供应器逻辑占着），走 {@code LevelTickEvent.Post} → {@code pumpLogic} 汇合到同一个
     * {@code tickServer()}，所以两种形态的攒批与落地时机完全一致。</p>
     */
    private boolean pushWithOwnRules(IPatternDetails details, KeyCounter[] inputs) {
        long now = gameTime();
        // 上一 tick 留下的批先发：CPU 停交后不一定会再给我们一次 pushPattern。
        flushIfQuiet(now);
        if (!canPush(details)) {
            return false;
        }
        if (!boostEnabled) {
            return deliver(details, inputs, declaredInputs(details), false) != Delivery.NONE;
        }

        // 没设份数上限（isDue 恒 false）时这一段完全跳过：落地交给下面的停手判据，
        // 不能用「pendingCount >= 0」这种比较——那会每次都 flushAll 并拒收。
        if (mergeLimit.isDue(pendingCount())) {
            flushAll(now);
            if (mergeLimit.isDue(pendingCount())) {
                // 目标一直收不下：这一份还没收下，不返回 true，让 CPU 下个 tick 重投（材料仍在网络里）。
                return false;
            }
        }

        pendingBatches.computeIfAbsent(details, k -> new ArrayList<>()).add(BoostMerge.copy(inputs));
        pendingBatchTick = now;
        var batch = pendingBatches.get(details);
        // 这份数是该样板攒了多少份合成：AE2 的 pushPattern 一次一份，所以 list 的长度就是份数。
        if (mergeLimit.isDue(batch.size())) {
            // flush 会把没投出去的份留在 pending（材料所有权已在本机），所以这里一律回答「已收下」，
            // 不能把 false 交给 CPU——那样它会拿同一份材料重投，pending 里就会出现两份。
            //
            // 目标持续收不下时 batch.size() 会一直超过额度，于是每次 push 都重试一次落地。这是既有行为，
            // 不存在丢料或叠份，只是失败重试比停手窗口那 10 tick 的节奏密。
            flush(details);
            pendingBatchTick = now;
        }
        return true;
    }

    /** 一次投递的结果：完全投出、只投出一部分（余量净丢、不得重投）、完全未投。 */
    private enum Delivery { NONE, PARTIAL, ALL }

    /** 一次投进目标。{@code inventoryOnly} 为真时跳过机器（机器只认「一份」语义，合并批不能塞给它）。 */
    private Delivery deliver(IPatternDetails details, KeyCounter[] inputs, Set<AEKey> ownInputs,
            boolean inventoryOnly) {
        var be = host.getBlockEntity();
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return Delivery.NONE;
        }
        var sides = activeSides();
        var takenByMachine = EnumSet.noneOf(Direction.class);

        if (!inventoryOnly) {
            for (var facing : sides) {
                var adjacentPos = be.getBlockPos().relative(facing);
                var fromUs = facing.getOpposite();
                var machine = ICraftingMachine.of(level, adjacentPos, fromUs);
                if (machine != null && machine.acceptsPlans()) {
                    if (machine.pushPattern(details, inputs, fromUs)) {
                        resetCraftingLock();
                        return Delivery.ALL;
                    }
                    takenByMachine.add(facing);
                }
            }
        }

        if (!details.supportsPushInputsToExternalInventory()) {
            return Delivery.NONE;
        }

        for (var facing : sides) {
            if (takenByMachine.contains(facing)) {
                continue;
            }
            var adjacentPos = be.getBlockPos().relative(facing);
            var adjacentBe = level.getBlockEntity(adjacentPos);
            var fromUs = facing.getOpposite();

            if (blockingMode == ProviderBlockingMode.SMART) {
                if (hasForeignInput(level, adjacentPos, adjacentBe, fromUs, ownInputs)) {
                    // 目标里已经有别的配方的原料：换一个目标，这才是「智能阻挡」。
                    continue;
                }
            } else if (isBlocking() && containsAnyPatternInput(level, adjacentPos, adjacentBe, fromUs)) {
                // 阻挡档（AE2 原生）：目标已有任一已注册样板的输入就换目标。延着 boost 走这条路径时
                // 父类不会替我们做这个判定，所以在这里补上，与原生口径一致。
                continue;
            }

            // 预检与实投共用同一份 key→target 映射：两处若按不同面解析，会预检出 A 目标、实投去 B 目标。
            var targets = resolveTargets(details, inputs, level, adjacentPos, adjacentBe, fromUs);
            if (targets.isEmpty() || !acceptsAll(targets, inputs)) {
                continue;
            }

            var result = pushToTarget(details, inputs, targets);
            if (result != Delivery.NONE) {
                // 全额与部分都算已交付：材料已经进了目标，重投就是复制。
                // 锁定合成只能清、不能重新武装（父类 onPushPatternSuccess 是 private），但清锁仍要做。
                resetCraftingLock();
                if (result == Delivery.PARTIAL && notePartialWarning(details)) {
                    LOGGER.warn("Pattern disk provider at {} handed over part of {}; the shortfall is not "
                            + "recoverable and will not be retried",
                            be.getBlockPos(), details.getClass().getSimpleName());
                }
                return result;
            }
        }
        return Delivery.NONE;
    }

    /** 目标里是否有任何一张已注册样板的输入（与父类 {@code patternInputs} 同口径，按样板集合缓存）。 */
    private boolean containsAnyPatternInput(ServerLevel level, BlockPos pos, @Nullable BlockEntity be,
            Direction meSide) {
        var all = allPatternInputs();
        if (all.isEmpty()) {
            return false;
        }
        var target = targetAt(level, pos, be, meSide, meSide, actionSource);
        return target != null && target.containsPatternInput(all);
    }

    /** 全部已装样板的输入集合；样板一变（{@link #updatePatterns()}）就重建。 */
    private Set<AEKey> allPatternInputs() {
        if (cachedPatternInputs == null) {
            var all = new HashSet<AEKey>();
            for (var pattern : getAvailablePatterns()) {
                all.addAll(declaredInputs(pattern));
            }
            cachedPatternInputs = all;
        }
        return cachedPatternInputs;
    }

    /** 按样板声明的接入面把每个 key 映到一个目标；任一 key 解析不到目标就整份放弃。 */
    private Map<AEKey, PatternProviderTarget> resolveTargets(IPatternDetails details, KeyCounter[] inputs,
            ServerLevel level, BlockPos adjacentPos, @Nullable BlockEntity adjacentBe, Direction fromUs) {
        var targets = new HashMap<AEKey, PatternProviderTarget>();
        for (var counter : inputs) {
            if (counter == null) {
                continue;
            }
            for (var key : counter.keySet()) {
                var declared = AdvPatternSupport.accessSide(details, key);
                var target = targetAt(level, adjacentPos, adjacentBe, fromUs,
                        declared != null ? declared : fromUs, actionSource);
                if (target == null) {
                    return Map.of();
                }
                targets.put(key, target);
            }
        }
        return targets;
    }

    /** @return true 表示这张样板还没报过，应当打一条 warn */
    private boolean notePartialWarning(IPatternDetails details) {
        if (partialWarned.size() > 64) {
            // 有界：被喂进无数种样板时宁可重报，也不能无限增长。
            partialWarned.clear();
        }
        return partialWarned.add(details);
    }

    /** 用同一份 key→target 映射做全额模拟。
     *
     * <p>逐 key 独立模拟，同一个目标上多个 key 的总量可能被高估（模拟不累计）——因此 PARTIAL 仍可能发生，
     * 且必须由调用方按「已交付、不重投」处理。这条近似换来的是不依赖目标内部的剩余容量接口。</p>
     */
    private static boolean acceptsAll(Map<AEKey, PatternProviderTarget> targets, KeyCounter[] inputs) {
        for (var counter : inputs) {
            if (counter == null) {
                continue;
            }
            for (var key : counter.keySet()) {
                var amount = counter.get(key);
                var target = targets.get(key);
                if (target == null || target.insert(key, amount, Actionable.SIMULATE) < amount) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 把输入交给样板自己派发（按声明的接入面找 sink），与方向投递同一套。 */
    private Delivery pushToTarget(IPatternDetails details, KeyCounter[] inputs,
            Map<AEKey, PatternProviderTarget> targets) {
        boolean[] complete = { true };
        boolean[] any = { false };
        details.pushInputsToExternalInventory(inputs, (what, amount) -> {
            var target = targets.get(what);
            if (target == null) {
                complete[0] = false;
                return;
            }
            any[0] = true;
            long inserted = target.insert(what, amount, Actionable.MODULATE);
            if (inserted < amount) {
                complete[0] = false;
                LOGGER.warn("Pattern disk provider at {} handed over {} x{} but its target took only {}",
                        host.getBlockEntity() == null ? "?" : host.getBlockEntity().getBlockPos(), what, amount,
                        inserted);
            }
        });
        if (!any[0]) {
            return Delivery.NONE;
        }
        return complete[0] ? Delivery.ALL : Delivery.PARTIAL;
    }

    /** 目标内容快照里是否存在与本样板无关的原料。读不到存储（无 ME 能力）时按「无外来物」处理。 */
    private static boolean hasForeignInput(ServerLevel level, BlockPos pos, @Nullable BlockEntity be, Direction meSide,
            Set<AEKey> ownInputs) {
        var storage = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, be.getBlockPos(), be.getBlockState(), be, meSide)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, meSide);
        if (storage == null) {
            return false;
        }
        return ProviderBlockingRules.hasForeignInput(storage.getAvailableStacks(), ownInputs);
    }

    /**
     * 发送方停手就把攒着的批交出去。
     *
     * <p>判据只有一条：距上次收下满 {@link BoostMergeLimit#QUIET_TICKS} 个 tick。</p>
     *
     * <p>不能改成「只要不是本 tick 就 flush」——那等于发送方隔一 tick 来一次就把批清一次，合并攒不起来，
     * 原材料会陆陆续续单独写进目标，正是「发配倍增好像没生效」的那种表现。</p>
     *
     * <p><b>没设份数上限（填 0）时，这里是唯一的落地触发。</b>发送方如果一直不停手，批就一直攒着——这是
     * 有意的取舍：要上界就填数字，填了就由 {@code isDue(craftCount)} 按份数落地，而不是靠计时。</p>
     */
    private void flushIfQuiet(long now) {
        if (pendingBatches.isEmpty()) {
            return;
        }
        if (now - pendingBatchTick >= BoostMergeLimit.QUIET_TICKS) {
            flushAll(now);
        }
    }

    private void flushAll(long now) {
        for (var details : new ArrayList<>(pendingBatches.keySet())) {
            flush(details);
        }
        pendingBatchTick = now;
    }

    /** 发出一张样板攒下的全部份数；合并批先试，不行再逐份（逐份失败交给父类的发送队列）。 */
    private void flush(IPatternDetails details) {
        var batch = pendingBatches.remove(details);
        if (batch == null || batch.isEmpty()) {
            return;
        }
        var ownInputs = declaredInputs(details);
        // 合并成一次 IO：槽数对不上时 merge 返回 null（那两种到货形状下标意义不同，按下标截断会丢料），
        // 那就跳过合并、直接走下面的逐份路径。
        //
        // 单份批次（size == 1）不进合并：没有可合并的，合并的意义只是把它的投递降成 inventoryOnly（只给存储、
        // 不给机器），而单份恰好是机器最愿意接的形状。所以它走逐份路径的 deliver(..., false)。
        var mergedInputs = batch.size() > 1 ? BoostMerge.merge(batch) : null;
        if (mergedInputs != null) {
            var merged = deliver(details, mergedInputs, ownInputs, true);
            if (merged != Delivery.NONE) {
                // ALL 与 PARTIAL 都算已接管：合并批已进入目标，重投就是复制。
                return;
            }
        }
        var leftover = new ArrayList<KeyCounter[]>();
        for (var one : batch) {
            var result = deliver(details, one, ownInputs, false);
            if (result != Delivery.NONE) {
                // 部分投出也算已交付：材料已经进去，重投就是复制。
                continue;
            }
            if (super.pushPattern(details, one)) {
                continue;
            }
            // 只回填真的没投出去的那几份：整批回填会把已投出的份再投一次（复制）。
            leftover.add(one);
        }
        if (!leftover.isEmpty()) {
            pendingBatches.computeIfAbsent(details, k -> new ArrayList<>()).addAll(leftover);
        }
    }

    /** 由宿主的服务端 tick 调用：只在发送方停手时把攒着的批交出去，不做每 tick 无条件清空。 */
    public void flushPending() {
        flushIfQuiet(gameTime());
    }

    private int pendingCount() {
        int total = 0;
        for (var list : pendingBatches.values()) {
            total += list.size();
        }
        return total;
    }

    private long gameTime() {
        var be = host.getBlockEntity();
        var level = be == null ? null : be.getLevel();
        return level == null ? 0L : level.getGameTime();
    }

    /** 深拷与合并都在 {@link BoostMerge}。 */

    /** 同槽同 key 求和与深拷都在 {@link BoostMerge}。 */

    /**
     * AdvancedAE 式的方向投递：邻居照旧按候选方向挑，声明面只决定「从哪一面接入它」。
     *
     * <p>两趟：先跨所有方向把机器试完，再按面把整批投进某个方向上的邻居。机器优先是父类的规矩——能整张收下
     * 样板的机器自己会用这张样板，材料不该塞进它的存储。</p>
     *
     * <p>先模拟后实投，且要求每个输入都能被它那个面全量收下，否则整批放弃。上游只要求「收得下一点」而把残量
     * 丢进发送队列慢慢补，本模组没有那份记账能力：父类的 {@code addToSendList} 是 private，投出去却收不下的
     * 部分就是净丢。要求全额是拿「这一轮不投」换「一件不丢」。实投短少仍会记日志。</p>
     */
    private boolean pushDirectionally(IPatternDetails details, KeyCounter[] inputHolder) {
        var be = host.getBlockEntity();
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        var sides = activeSides();

        // 机器那一趟要跨完所有方向才轮到别的：边试机器边投存储的话，A 方向的机器拒收、B 方向的机器会收时，
        // 样板会被 A 的存储抢走。
        var takenByMachine = EnumSet.noneOf(Direction.class);
        for (var facing : sides) {
            var adjacentPos = be.getBlockPos().relative(facing);
            var fromUs = facing.getOpposite();
            var machine = ICraftingMachine.of(level, adjacentPos, fromUs);
            if (machine != null && machine.acceptsPlans()) {
                if (machine.pushPattern(details, inputHolder, fromUs)) {
                    return true;
                }
                // 拒收：这个方向本轮不再当普通邻居用（与父类同一处理）。
                takenByMachine.add(facing);
            }
        }

        // 样板自己说了不让往普通库存里推（分子装配室类）就不要推，与父类同一判据。
        if (!details.supportsPushInputsToExternalInventory()) {
            return false;
        }

        var patternInputs = isBlocking() ? declaredInputs(details) : null;

        for (var facing : sides) {
            if (takenByMachine.contains(facing)) {
                continue;
            }
            var adjacentPos = be.getBlockPos().relative(facing);
            var adjacentBe = level.getBlockEntity(adjacentPos);
            // 邻居对着我们的那一面：查它的 ME 能力用这个，与样板声明无关。
            var fromUs = facing.getOpposite();

            var targets = new HashMap<AEKey, PatternProviderTarget>();
            var usable = true;
            for (var counter : inputHolder) {
                var key = counter.getFirstKey();
                if (key == null) {
                    continue;
                }
                // 面按输入槽的首个 key 取（一套输入一个面，同上游），但整槽的 key 都要映到同一个接入点：
                // sink 会把槽里每个 key 都推出来，只登记首 key 会让其余的在下面查不到而静默消失。
                var declared = AdvPatternSupport.accessSide(details, key);
                var target = targetAt(level, adjacentPos, adjacentBe, fromUs,
                        declared != null ? declared : fromUs, actionSource);
                if (target == null || !accepts(target, counter)
                        || (patternInputs != null && target.containsPatternInput(patternInputs))) {
                    usable = false;
                    break;
                }
                for (var slotKey : counter.keySet()) {
                    targets.put(slotKey, target);
                }
            }
            if (!usable || targets.isEmpty()) {
                continue;
            }

            // 交给样板自己派发：它按内部顺序把材料交给 sink，本模组只负责把每个 key 送到它那个接入点。
            details.pushInputsToExternalInventory(inputHolder, (what, amount) -> {
                var target = targets.get(what);
                if (target == null) {
                    // 样板整理/合并过输入时可能冒出新 key：按它自己的声明面现取一个，不丢。
                    var declared = AdvPatternSupport.accessSide(details, what);
                    target = targetAt(level, adjacentPos, adjacentBe, fromUs,
                            declared != null ? declared : fromUs, actionSource);
                    if (target != null) {
                        // 同一个 key 可能被重复推好几次，记下来省后面的查询。
                        targets.put(what, target);
                    }
                }
                long inserted = target == null ? 0 : target.insert(what, amount, Actionable.MODULATE);
                if (inserted < amount) {
                    LOGGER.warn("Pattern disk provider at {} handed over {} x{} but its target took only {}; "
                            + "the shortfall is not recoverable", be.getBlockPos(), what, amount, inserted);
                }
            });
            return true;
        }
        return false;
    }

    /**
     * 自己组装一个接入点，因为 AE2 的 {@code PatternProviderTarget.get} 只接受一个面。
     *
     * <p>那一个面在它内部要兼任两件事：查 ME 能力、查外部存储策略。高级样板的按面接入正要求这两件事分开——
     * 能力按「邻居对着我」那一面查（本来就该如此），策略按样板声明的面查（让按面注册策略的机器能在它自己认的
     * 那个面上被接进来）。公开 API 表达不了这个组合，所以这里自己走一遍它的两个分支。声明面对 AE2 的容器与
     * 机器没有影响，差异只在带面限定的机器上。</p>
     *
     * <p>不缓存：父类与 AdvancedAE 都按面缓存了这一层，但这条路径一次投递最多问几个目标，而缓存要连带处理
     * 失效语义（方块被换掉、能力被重注册）。省下的这点查询换不来一份需要维护的失效逻辑。</p>
     */
    @Nullable
    private static PatternProviderTarget targetAt(ServerLevel level, BlockPos pos, @Nullable BlockEntity be,
            Direction meSide, Direction strategySide, IActionSource src) {
        var storage = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, be.getBlockPos(), be.getBlockState(), be, meSide)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, meSide);
        if (storage != null) {
            return wrap(storage, src);
        }

        var strategies = StackWorldBehaviors.createExternalStorageStrategies(level, pos, strategySide);
        var external = new IdentityHashMap<AEKeyType, MEStorage>(2);
        for (var entry : strategies.entrySet()) {
            var wrapper = entry.getValue().createWrapper(false, () -> {});
            if (wrapper != null) {
                external.put(entry.getKey(), wrapper);
            }
        }
        return external.isEmpty() ? null : wrap(new CompositeStorage(external), src);
    }

    private static PatternProviderTarget wrap(MEStorage storage, IActionSource src) {
        return new PatternProviderTarget() {
            @Override
            public long insert(AEKey what, long amount, Actionable type) {
                return storage.insert(what, amount, type, src);
            }

            @Override
            public boolean containsPatternInput(Set<AEKey> patternInputs) {
                for (var stack : storage.getAvailableStacks()) {
                    if (patternInputs.contains(stack.getKey().dropSecondary())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static boolean accepts(PatternProviderTarget target, KeyCounter counter) {
        for (var key : counter.keySet()) {
            long amount = counter.get(key);
            if (target.insert(key, amount, Actionable.SIMULATE) < amount) {
                return false;
            }
        }
        return true;
    }

    /**
     * 自己的可用面：与 AE2 同一判据——宿主声明的目标面，减去连着同网其他供应器与接口的那些面。
     *
     * <p>排除很重要：那些面背面坐着的是另一台拿样板干活的机器，把输入推过去等于把活派给它。</p>
     */
    private Set<Direction> activeSides() {
        var declared = host.getTargets();
        var sides = declared.isEmpty() ? EnumSet.noneOf(Direction.class) : EnumSet.copyOf(declared);
        var node = mainNode.getNode();
        if (node == null) {
            return sides;
        }
        for (var entry : node.getInWorldConnections().entrySet()) {
            var other = entry.getValue().getOtherSide(node);
            if (other.getOwner() instanceof PatternProviderLogicHost
                    || (other.getOwner() instanceof InterfaceLogicHost
                            && other.getGrid().equals(mainNode.getGrid()))) {
                sides.remove(entry.getKey());
            }
        }
        return sides;
    }

    /** 这张样板声明的输入 key 集合（与父类同一口径：丢掉二级变体）。 */
    private static Set<AEKey> declaredInputs(IPatternDetails details) {
        var keys = new HashSet<AEKey>();
        for (var input : details.getInputs()) {
            for (var candidate : input.getPossibleInputs()) {
                if (candidate != null) {
                    keys.add(candidate.what().dropSecondary());
                }
            }
        }
        return keys;
    }

    /**
     * How many slots the mirror needs: every disk slot the machine has, times the largest disk tier.
     *
     * <p>Derived rather than fixed, because a fixed number has to be right for every machine and every
     * tier at once. The one that used to be here - 1024 - was exactly one full disk, so a provider
     * holding nine of them exposed only the first and silently dropped the rest.</p>
     *
     * <p>The slot count is a parameter rather than a read of the disk inventory: this logic is built
     * inside the host's own {@code super()} constructor, before that inventory field exists.</p>
     *
     * <p>The upper bound comes from this mod's own tiers, which is what these machines' disks can hold -
     * every write path in the mod clamps to a tier capacity. A component edited out of band to claim more
     * than the largest tier would not be covered; covering that would mean deriving the bound from the
     * disks at runtime, and this constructor runs before the disks can be read.</p>
     *
     * <p>Truncation is unreachable on the normal path: one disk holds at most the largest tier's capacity,
     * so the sum never exceeds this bound. If a future tier or an out-of-band write path breaks that,
     * {@code refreshPatternsFromDisks} degrades to a silent truncation - no log, no status report.</p>
     */
    private static int mirrorCapacity(int diskSlots) {
        int perDisk = 0;
        for (PatternDiskTier tier : PatternDiskTier.values()) {
            perDisk = Math.max(perDisk, tier.capacity());
        }
        return diskSlots * perDisk;
    }

    /**
     * Rebinds the parent's pattern inventory to the encoded patterns on all inserted disks, then lets the
     * parent rebuild its pattern list.
     *
     * <p>Strategy 2 (static resolver): take the disk state again; if it is unchanged since the last rebuild
     * we short-circuit and skip the expensive {@code clear + rewrite + updatePatterns()} so a burst of
     * same-content invocations coalesces into a single rebuild. A content change (slot in/out or disk
     * repattern) rebuilds fresh in one pass.</p>
     */
    public boolean refreshPatternsFromDisks() {
        return refreshPatternsFromDisks(false);
    }

    /**
     * @param force rebuild even when the disks look unchanged. The mirror can be rewritten from under us
     *              while the disks stay where they are - importing a memory card clears it and then lets
     *              AE2 write into it - and the state check cannot see that, so those callers pass true.
     */
    public boolean refreshPatternsFromDisks(boolean force) {
        var diskInventory = diskInventorySupplier.get();
        if (diskInventory == null) {
            return false;
        }

        var versions = DiskSlotVersions.ofInventory(diskInventory);
        if (!force && DiskSlotVersions.sameAs(lastDiskVersions, versions)) {
            return false; // unchanged: coalesce, skip full rebuild
        }

        InternalInventory patternInv = mirror;
        if (patternInv == null) {
            return false;
        }

        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < diskInventory.size(); i++) {
            ItemStack diskStack = diskInventory.getStackInSlot(i);
            if (diskStack.isEmpty() || !(diskStack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            PatternDiskContents contents = disk.contents(diskStack);
            all.addAll(contents.patterns());
        }

        rewriteMirror(all);

        // Parent decodes patternInventory into its patterns list and requests a grid update.
        updatePatterns();

        lastDiskVersions = versions;
        return true;
    }

    /**
     * 用一组样板整体替换镜像栏：抑制逐槽通知，由调用方在末尾跑一次 {@link #updatePatterns()}。
     *
     * <p>只在镜像容量不足时截断并记一条日志——镜像设备的样板来自整张网格，容量是按「满配磁盘」估的上界，
     * 正常玩法碰不到；碰得到也不该静默少掉几张。</p>
     */
    protected final void rewriteMirror(List<ItemStack> patterns) {
        rebuildingMirror = true;
        try {
            mirror.clear();
            int limit = Math.min(patterns.size(), mirror.size());
            for (int i = 0; i < limit; i++) {
                // copy(): 样板出自磁盘组件，镜像的持有者不能借此改到磁盘看到的内容。
                mirror.setItemDirect(i, patterns.get(i).copy());
            }
            if (patterns.size() > limit && LOGGER.isWarnEnabled()) {
                LOGGER.warn("样板磁盘供应器的镜像容量不足：{} 张样板只能放入前 {} 张，其余被截断",
                        patterns.size(), limit);
            }
        } finally {
            rebuildingMirror = false;
        }
    }

    // The parent's updatePatterns re-reads patternInventory; our refresh already filled it.
    @Override
    public void updatePatterns() {
        super.updatePatterns();
        // 样板集合变了，阻挡档的全量输入缓存与抽取白名单都得重建。
        cachedPatternInputs = null;
        cachedPatternOutputs = null;
        extractionReturnWarned.clear();
        dropOrphanBatches();
    }

    /**
     * 把「样板已经不在磁盘上」的撺批退出去。
     *
     * <p>撺批表的 key 是 {@code IPatternDetails}，而样板是磁盘内容的派生态：玩家把磁盘抽了、或换了一张别的，
     * 旧表里那一项就再也没有对应的样板了。它的材料是 CPU 已经记过账的交付，不能留在表里等一个永远不会发生的
     * 投递（也不该到存档时才被当成“没有样板可存”默默丢掉）。</p>
     *
     * <p>放在 {@code updatePatterns} 里而不是写盘时：镜像是从磁盘重建的，重建完那一刻就是判断“还在不在”最准的
     * 时机；写在写盘路径里会把副作用塞进存档流程，而存档应该只读。</p>
     */
    private void dropOrphanBatches() {
        if (pendingBatches.isEmpty()) {
            return;
        }
        var be = host.getBlockEntity();
        if (be == null || !(be.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var byPattern = patternStacksByDetails(level);
        var iterator = pendingBatches.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (byPattern.containsKey(entry.getKey())) {
                continue;
            }
            int dropped = entry.getValue().size();
            for (var inputs : entry.getValue()) {
                returnInputsToNetwork(flatten(inputs));
            }
            iterator.remove();
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Pattern disk provider at {} dropped {} merged hand-over(s) of a pattern that is no "
                        + "longer on the disks; their inputs went back to the network", be.getBlockPos(), dropped);
            }
        }
    }

    /**
     * True while {@link #refreshPatternsFromDisks} is rewriting the mirror.
     *
     * <p>Every write into the mirror notifies the host, and the host's answer is a full
     * {@code updatePatterns()} - which walks every slot and decodes every pattern. Left alone, one rebuild
     * runs that once per written pattern instead of once at the end: a nine-disk mirror holds 9216, so that
     * is thousands of full passes against one. The flag collapses the burst, and the rebuild calls
     * {@code updatePatterns()} itself once the mirror is whole.</p>
     */
    private boolean rebuildingMirror;

    /**
     * Empties the mirror with a single parent update instead of one per slot.
     *
     * <p>{@code clear()} empties slot by slot and every slot notifies, so the plain call this replaces ran
     * a full {@code updatePatterns()} per slot - over the whole mirror each time. The callers only need the
     * empty end state, so the burst is collapsed the same way a rebuild collapses it. Only the mirror is
     * written while that flag is up; the disk inventory is not touched.</p>
     */
    public void clearMirror() {
        InternalInventory patternInv = mirror;
        if (patternInv == null) {
            return;
        }
        rebuildingMirror = true;
        try {
            patternInv.clear();
        } finally {
            rebuildingMirror = false;
        }
        // 镜像已非磁盘内容的映像：不把快照作废的话，下一个非强制的 refresh 会拿旧快照比出「没变」而跳过重建。
        lastDiskVersions = null;
        updatePatterns();
    }

    /**
     * 拆机/重置时清空镜像。父实现逐槽清，每槽一次通知就直接跑一次 9216 槽的 {@code updatePatterns()}——
     * 通知量会被放大成几千万次槽访问。这里用与重建同一个抑制开关（{@link #rebuildingMirror}）把它压成一次，
     * 末尾统一重建。
     *
     * <p>与 {@link #clearMirror()} 同一套思路：两个入口都只关心「空终态」。</p>
     */
    @Override
    public void clearContent() {
        rebuildingMirror = true;
        try {
            super.clearContent();
        } finally {
            rebuildingMirror = false;
        }
        // 镜像内容不再是磁盘内容的映像，快照得作废，否则下一个非强制刷新会拿旧快照比出「没变」。
        lastDiskVersions = null;
        // 合并中的材料已经由 addDrops 掉出去了，这里只清账，避免拆机后再被 flush 一遍。
        pendingBatches.clear();
        updatePatterns();
    }

    // ---- 内存卡：本模组自己的设置 ------------------------------------------

    /**
     * 把本模组的设置写进内存卡。AE2 的 {@code Settings} 枚举不可扩展，阻挡三档/发配倍增/产物抽取只能
     * 自带一段组件，否则粘贴时这三项会悄悄丢掉。
     */
    public void exportCustomSettings(DataComponentMap.Builder builder) {
        var tag = new CompoundTag();
        tag.putString(NBT_BLOCKING_MODE, blockingMode.name());
        tag.putBoolean(NBT_BOOST_ENABLED, boostEnabled);
        tag.putInt(NBT_BOOST_LIMIT, boostLimit);
        tag.putString(NBT_EXTRACTION_MODE, extractionMode.name());
        writeExtraSettings(tag);
        builder.set(AEPatternRegistries.MEMORY_CARD_SETTINGS, tag);
    }

    /**
     * 应用内存卡里的本模组设置。卡上没有这一段（旧卡、或由别的机器写出的卡）时保持原样。
     */
    public void importCustomSettings(DataComponentMap input) {
        var tag = input.get(AEPatternRegistries.MEMORY_CARD_SETTINGS.get());
        if (tag == null) {
            return;
        }
        blockingMode = parseBlockingMode(tag.getString(NBT_BLOCKING_MODE));
        boostEnabled = tag.getBoolean(NBT_BOOST_ENABLED);
        boostLimit = Math.max(0, Math.min(BoostMergeLimit.MAX_LIMIT, tag.getInt(NBT_BOOST_LIMIT)));
        mergeLimit = new BoostMergeLimit(boostLimit);
        extractionMode = parseExtractionMode(tag.getString(NBT_EXTRACTION_MODE));
        readExtraSettings(tag);
        // 与读档同一条对齐：AE2 那边的开关得跟着本模组的三档走。
        getConfigManager().putSetting(Settings.BLOCKING_MODE,
                blockingMode == ProviderBlockingMode.ALL ? YesNo.YES : YesNo.NO);
        saveChanges();
    }

    /** 子类追加要随卡走的设置（标记式设备的选中标记）。 */
    protected void writeExtraSettings(CompoundTag tag) {
    }

    /** 子类读回自己追加的设置。 */
    protected void readExtraSettings(CompoundTag tag) {
    }

    // ---- 供应器设置：阻挡三档与发配倍增 -----------------------------------

    public ProviderBlockingMode getBlockingMode() {
        return blockingMode;
    }

    /**
     * 切换阻挡档位。{@link ProviderBlockingMode#ALL} 交回父类，其余两档在父类眼里都是「关」——
     * 智能阻挡的判据在本类，父类只要不插手。
     */
    public void setBlockingMode(@Nullable ProviderBlockingMode mode) {
        if (mode == null || mode == blockingMode) {
            return;
        }
        blockingMode = mode;
        getConfigManager().putSetting(Settings.BLOCKING_MODE,
                mode == ProviderBlockingMode.ALL ? YesNo.YES : YesNo.NO);
        saveChanges();
    }

    public void cycleBlockingMode() {
        setBlockingMode(blockingMode.next());
    }

    // ---- 产物抽取 -------------------------------------------------------------

    public ProviderExtractionMode getExtractionMode() {
        return extractionMode;
    }

    /**
     * 切换产物抽取模式。切档后立即生效（下一 tick 就抽一次），不把上一档的退避带过来。
     */
    public void setExtractionMode(@Nullable ProviderExtractionMode mode) {
        if (mode == null || mode == extractionMode) {
            return;
        }
        extractionMode = mode;
        extractionCooldown = 0;
        extractionInterval = ProviderExtractionPacing.FAST_START_INTERVAL;
        extractionMissStreak = 0;
        extractionSweepDue = true;
        saveChanges();
    }

    public void cycleExtractionMode() {
        setExtractionMode(extractionMode.next());
    }

    /**
     * 服务端每 tick 的唯一入口：发配倍增的停交兜底 + 产物抽取节拍。
     *
     * <p>方块形态由 {@code serverTick} 调，面板形态由 {@code pumpLogic} 调——两条驱动通道只在这里汇合，
     * 想改「每 tick 做什么」只改这一处，不用在两个宿主里各改一遍。</p>
     */
    public void tickServer() {
        // 读档时攒批只存了原始 Tag（没有 level 解不出样板），到第一次 tick 才还原。没事时是一次空列表检查。
        restorePendingBatches();
        retryReturns(gameTime());
        flushPending();
        tickExtraction();
    }

    /**
     * 每 tick 的抽取节拍：由方块形态的 {@code serverTick} 与面板形态的世界 tick 事件驱动。
     *
     * <p>冷却递减与实际抽取都放在这里，是因为只有这两个入口知道「这个世界在跑」；逻辑自己不持有 tick 源。</p>
     */
    public void tickExtraction() {
        if (extractionMode == ProviderExtractionMode.OFF) {
            extractionCooldown = 0;
            return;
        }
        if (allPatternOutputs().isEmpty()) {
            extractionCooldown = 0;
            extractionMissStreak = 0;
            return;
        }
        var sides = activeSides();
        if (sides.isEmpty()) {
            // 这台供应器无事可做：磁盘里没有样板产物，或没有可用的目标面。不计空抽、不告警——
            // 否则空机器每 64 轮刷一条「抽不到」，把真正的故障埋掉。
            extractionCooldown = 0;
            extractionMissStreak = 0;
            return;
        }
        if (extractionCooldown > 0) {
            extractionCooldown = ProviderExtractionPacing.advanceCooldown(extractionCooldown);
            if (extractionCooldown > 0) {
                return;
            }
        }
        boolean extracted = extractProducts(sides);
        extractionInterval = ProviderExtractionPacing.nextInterval(extractionMode, extractionInterval, extracted);
        extractionCooldown = extractionInterval;
        if (extracted) {
            extractionMissStreak = 0;
        } else if (++extractionMissStreak == EXTRACTION_DIAGNOSE_AFTER) {
            // 开着抽取却连续抽不到：通常是目标没有把样板产物暴露在任何一面，报一次便于定位。
            LOGGER.warn("Pattern disk provider at {} has extraction on ({}) but nothing was extracted in the last "
                    + "{} passes; check that the target exposes the pattern outputs on one of its faces",
                    host.getBlockEntity() == null ? "?" : host.getBlockEntity().getBlockPos(), extractionMode,
                    EXTRACTION_DIAGNOSE_AFTER);
        }
    }

    /**
     * 从活跃面把「属于已装样板输出」的物品抽进 ME 网络。
     *
     * <p>白名单是样板自己的 {@code getOutputs()}：目标里剩的原料、工具、其它物品都不动，否则抽取会变成
     * 「把机器拆了」。每个视图各自跑一遍，能抽多少抽多少，网络装不下就留在目标里。</p>
     *
     * @return 是否真的抽到了东西（快速档靠它决定要不要退避）
     */
    private boolean extractProducts(Set<Direction> sides) {
        var be = host.getBlockEntity();
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        var outputs = allPatternOutputs();
        if (outputs.isEmpty()) {
            return false;
        }
        var grid = mainNode.getNode().getGrid();
        if (grid == null) {
            return false;
        }
        var network = grid.getStorageService().getInventory();

        boolean extractedAny = false;
        long now = gameTime();
        boolean fullSweep = extractionSweepDue || sweepSticky || now - lastFullSweep >= FULL_SWEEP_INTERVAL;
        if (fullSweep) {
            lastFullSweep = now;
            extractionSweepDue = false;
        }
        for (var facing : sides) {
            var adjacentPos = be.getBlockPos().relative(facing);
            var storages = extractableStorages(level, adjacentPos, facing.getOpposite(), fullSweep);
            int viewIndex = 0;
            boolean sideProduced = false;
            for (var storage : storages) {
                int viewIdx = viewIndex++;
                boolean sideView = viewIdx > 0;
                try {
                    boolean got = extractFromStorage(storage, outputs, network, actionSource, be.getBlockPos());
                    extractedAny |= got;
                    if (got && sideView) {
                        sideProduced = true;
                    }
                } catch (RuntimeException e) {
                    // 视图来源各不相同：一个第三方实现抛异常不该让整轮抽取停摆，只跳过它并限流告警。
                    var warnKey = adjacentPos.toShortString() + ":" + viewIdx;
                    if (extractionViewWarned.size() > 64) {
                        extractionViewWarned.clear();
                    }
                    if (extractionViewWarned.add(warnKey)) {
                        LOGGER.warn("Pattern disk provider at {}: storage view {} threw while extracting; "
                                + "skipping it this pass", be.getBlockPos(), warnKey, e);
                    }
                }
            }
            if (sideProduced) {
                sweepSticky = true;
                sweepStickyIdle = 0;
            } else if (sweepSticky && ++sweepStickyIdle >= STICKY_IDLE_LIMIT) {
                // 侧视图连续这么多个抽取轮都没产出，就退回只查正对面。
                sweepSticky = false;
                sweepStickyIdle = 0;
            }
        }
        return extractedAny;
    }
    /**
     * 目标这一面实际持有的「去组件 key → 实际 key」索引，只收<b>无组件且唯一</b>的条目。
     *
     * <p>两道限定的理由：带组件的变体不是样板声明的那一种，回退到它会抽走别的物品；而同一个去组件 key
     * 对应两个变体时，留哪个取决于 {@code getAvailableStacks()} 的迭代顺序，抽到的东西会随 tick 摇摆。
     * 两者都宁可判「抽不到」，交给玩家把样板编码成精确变体。</p>
     */
    private static Map<AEKey, AEKey> indexByNormalizedKey(MEStorage storage) {
        var index = new HashMap<AEKey, AEKey>();
        var ambiguous = new HashSet<AEKey>();
        for (var stack : storage.getAvailableStacks()) {
            var key = stack.getKey();
            if (key == null) {
                continue;
            }
            var normalized = key.dropSecondary();
            if (!key.equals(normalized) || ambiguous.contains(normalized)) {
                continue;
            }
            if (index.putIfAbsent(normalized, key) != null) {
                // 多义：两个「干净」变体共享同一个去组件 key，宁可不抽。
                index.remove(normalized);
                ambiguous.add(normalized);
            }
        }
        return index;
    }
    /** 在单个存储视图上跑一轮「白名单 → 网络」的抽取。 */
    private boolean extractFromStorage(MEStorage storage, Set<AEKey> outputs, MEStorage network,
            IActionSource actionSource, BlockPos bePos) {
        boolean extractedAny = false;
        Map<AEKey, AEKey> byNormalized = null;
        for (var key : outputs) {
            long available = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
            var target = key;
            if (available <= 0) {
                if (byNormalized == null) {
                    byNormalized = indexByNormalizedKey(storage);
                }
                var actual = byNormalized.get(key.dropSecondary());
                if (actual == null) {
                    continue;
                }
                target = actual;
                available = storage.extract(target, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
            }
            if (available <= 0) {
                continue;
            }
            // 先问网络收多少，再按那个量抽：顺序反过来的话，网络装不下的部分要退回目标，而退不回就是净丢。
            long accepted = network.insert(target, available, Actionable.SIMULATE, actionSource);
            if (accepted <= 0) {
                continue;
            }
            long taken = storage.extract(target, accepted, Actionable.MODULATE, actionSource);
            if (taken <= 0) {
                continue;
            }
            long inserted = network.insert(target, taken, Actionable.MODULATE, actionSource);
            // 只认「真的进了网络」：目标能抽、网络却拒收（SIMULATE 后被别人占满）时，抽出来又要退回，
            // 把它算成成功会让快速档掉回每 tick，变成抽出再塞回的空转。
            extractedAny |= inserted > 0;
            if (inserted < taken) {
                // 抽出来之后网络才满（同一 tick 里别的东西占了位置）：多出来的塞回目标。
                long leftover = taken - inserted;
                long returned = storage.insert(target, leftover, Actionable.MODULATE, actionSource);
                if (returned < leftover && noteExtractionReturnWarning(target)) {
                    LOGGER.warn(
                            "Pattern disk provider at {} extracted {} x{} but the target took back only {}{}; "
                                    + "the shortfall is not recoverable",
                            bePos, target, leftover, returned,
                            target == key ? "" : " (via normalized-key fallback, the target face may be output-only)");
                }
            }
        }
        return extractedAny;
    }

    /** 全部已装样板的输出集合（抽取白名单）；样板一变（{@link #updatePatterns()}）就重建。
     *
     * <p>存的是样板的<b>原型 key</b>，不是 {@code dropSecondary()} 后的爪化版：{@code extract} 要的是精确 key
     * （爪化只适合比较），用爪化版去抽会把「带组件的产物」漏掉或抽错变体。</p>
     *
     * <p><b>主来源是父类解码好的 {@code patterns} 列表</b>（零额外解码）；列表为空时退回直接解码镜像栏
     * 本身（`{@code mirror}` 就是磁盘内容的派生态）作为兜底。这两个来源读的是同一份库存、用的是同一个
     * AE2 helper，结果等价——所以这里的兜底只防「父类还没解码过」这一种边界，不要把它当成修了什么同步问题。
     * 缓存仍按 {@code updatePatterns()} 失效，不在每 tick 的抽取路径上重解。</p>
     *
     * <p><b>不剔除「同时也是输入」的 key</b>：早先为了避免与投递抢料做过一次全局剔除，结果链式生产
     * （A 的产物正是 B 的原料）里那个中间产物永远抽不出来，看起来就像功能没生效。现在的取舍是按用户口径
     * 只认输出：料被抽回网络后如果还要用，推送会再取一次。</p>
     */
    private Set<AEKey> allPatternOutputs() {
        if (cachedPatternOutputs == null) {
            var outputs = new HashSet<AEKey>();
            for (var details : getAvailablePatterns()) {
                collectOutputs(details, outputs);
            }
            if (outputs.isEmpty()) {
                // 兜底：父类还没解码过（刚加载、或列表被清空的那一瞬）时直接解码镜像栏。
                var be = host.getBlockEntity();
                if (this.mirror != null && be != null && be.getLevel() != null) {
                    for (int slot = 0; slot < this.mirror.size(); slot++) {
                        var details = PatternDiskApi.decodePattern(this.mirror.getStackInSlot(slot), be.getLevel());
                        collectOutputs(details, outputs);
                    }
                }
            }
            cachedPatternOutputs = outputs;
        }
        return cachedPatternOutputs;
    }

    private static void collectOutputs(@Nullable IPatternDetails details, Set<AEKey> into) {
        if (details == null) {
            return;
        }
        for (var stack : details.getOutputs()) {
            if (stack != null && stack.what() != null) {
                into.add(stack.what());
            }
        }
    }

    /** @return true 表示这个 key 还没报过退回失败，应当打一条 warn */
    private boolean noteExtractionReturnWarning(AEKey key) {
        if (extractionReturnWarned.size() > 64) {
            extractionReturnWarned.clear();
        }
        return extractionReturnWarned.add(key);
    }


    /**
     * 一次抽取轮要查的存储视图（调用内不缓存）。Mekanism 等机器按面暴露槽位，正对的那一面可能根本不含
     * 产物槽，所以除正对面之外还查无面视图与其余五面——见方法体里的说明。
     *
     * <p>AE2 的 ME 能力（{@link AECapabilities#ME_STORAGE}）若存在则视为权威视图（它自己代表机器的完整
     * 库存），不再叠平台能力，否则同一库存会被抽两次。</p>
     */
    private List<MEStorage> extractableStorages(ServerLevel level, BlockPos pos, Direction meSide,
            boolean fullSweep) {
        var be = level.getBlockEntity(pos);
        var direct = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, be.getBlockPos(), be.getBlockState(), be, meSide)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, meSide);
        if (direct != null) {
            // ME 能力是权威视图，它自己就代表机器的完整库存，不再叠平台能力（叠了会抽同一个库存两次）。
            return List.of(direct);
        }
        var storages = new ArrayList<MEStorage>(7);
        collectExternal(level, pos, meSide, storages);
        if (!fullSweep) {
            return storages;
        }
        // 全视图扫描：产物槽不一定在正对的那一面。无面视图（side = null）在多数实现里是未按面过滤的全槽，
        // 其余五面则覆盖「输出面开在侧面」的机器。白名单仍只认样板输出，多查几个视图不会多抽东西。
        collectExternal(level, pos, null, storages);
        for (var side : DIRECTIONS) {
            if (side != meSide) {
                collectExternal(level, pos, side, storages);
            }
        }
        return storages;
    }
    /** 把某个面（{@code side} 可为 null）上的平台存储能力包成一个 {@link MEStorage} 加进 {@code into}。 */
    private void collectExternal(ServerLevel level, BlockPos pos, @Nullable Direction side,
            List<MEStorage> into) {
        var strategies = StackWorldBehaviors.createExternalStorageStrategies(level, pos, side);
        var external = new IdentityHashMap<AEKeyType, MEStorage>(2);
        for (var entry : strategies.entrySet()) {
            var wrapper = entry.getValue().createWrapper(false, () -> {
            });
            if (wrapper != null) {
                external.put(entry.getKey(), wrapper);
            }
        }
        if (!external.isEmpty()) {
            into.add(new CompositeStorage(external));
        }
    }

    public boolean isBoostEnabled() {
        return boostEnabled;
    }

    public void setBoostEnabled(boolean enabled) {
        if (enabled == boostEnabled) {
            return;
        }
        boostEnabled = enabled;
        if (!enabled) {
            // 关掉时把攒着的批立即发出去，不让材料滞在机器里。
            flushAll(gameTime());
        }
        saveChanges();
    }

    public int getBoostLimit() {
        return boostLimit;
    }

    public void setBoostLimit(int limit) {
        var next = mergeLimit.withConfigured(limit);
        if (next.configured() == boostLimit) {
            return;
        }
        mergeLimit = next;
        boostLimit = next.configured();
        saveChanges();
    }

    /**
     * 攒到玩家设的份数上限时对外算忙：CPU 见到忙就不再交付，超出的材料留在网络里。
     *
     * <p>攒批期间（未达上限）<b>不算忙</b>：材料已经收进本机内存，让 CPU 继续交付才能攒得更深。由此有一条
     * 硬约束——攒批期间必须有人把批捅出去。玩家填了数字时由 {@code isDue} 负责；填 0（不设限）时就只剩
     * {@link #flushIfQuiet} 的停手判据，发送方若永不停手批就会一直攒着。那是有意的取舍：要上界就填数字。</p>
     *
     * <p>同时这里也是「CPU 停交」的兵家必争之处：CPU 每 tick 都会问一次，到期的那批就在这一步发出。</p>
     */
    @Override
    public boolean isBusy() {
        if (!pendingBatches.isEmpty()) {
            // 兜底：正常路径已由 tickServer()（方块 serverTick / 面板 pumpLogic）每 tick 覆盖，
            // 这里只防两条驱动通道都不可用的极端情形。
            flushIfQuiet(gameTime());
        }
        return super.isBusy() || mergeLimit.isDue(pendingCount());
    }

    @Override
    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeToNBT(tag, registries);
        writePendingBatches(tag, registries);
        // 退回缓冲与攒批表分开写：它不依赖 level，也不受「盘里没有样板」这些早退影响，
        // 而「只压着退回材料、没攒批」恰恰是最常见的状态，两者必须各自独立进入存档。
        writePendingReturns(tag, registries);
        tag.putString(NBT_BLOCKING_MODE, blockingMode.name());
        tag.putBoolean(NBT_BOOST_ENABLED, boostEnabled);
        tag.putInt(NBT_BOOST_LIMIT, boostLimit);
        tag.putString(NBT_EXTRACTION_MODE, extractionMode.name());
    }

    @Override
    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.readFromNBT(tag, registries);
        blockingMode = parseBlockingMode(tag.getString(NBT_BLOCKING_MODE));
        boostEnabled = tag.getBoolean(NBT_BOOST_ENABLED);
        boostLimit = Math.max(0, Math.min(BoostMergeLimit.MAX_LIMIT, tag.getInt(NBT_BOOST_LIMIT)));
        mergeLimit = new BoostMergeLimit(boostLimit);
        // 之前漏了这一步：档位写进存档却从不读回，读档后总是回落 OFF。
        extractionMode = parseExtractionMode(tag.getString(NBT_EXTRACTION_MODE));
        // 退避进度是运行时状态，不落盘：快速档的初始间隔永远是 1 tick（读档后从最快档重新开始）。
        extractionInterval = ProviderExtractionPacing.FAST_START_INTERVAL;
        extractionCooldown = 0;
        // 读档后把 AE2 的设置对齐一次：内存卡/旧存档里那个开关可能与本模组的三档不一致。
        getConfigManager().putSetting(Settings.BLOCKING_MODE,
                blockingMode == ProviderBlockingMode.ALL ? YesNo.YES : YesNo.NO);
        readPendingBatches(tag);
        readPendingReturns(tag, registries);
    }

    /** 待退回材料写成平平的 key/amount 表：它们不需要样板就能还原。 */
    private void writePendingReturns(CompoundTag tag, HolderLookup.Provider registries) {
        if (pendingReturns.isEmpty()) {
            return;
        }
        var list = new ListTag();
        for (var counter : pendingReturns) {
            for (var stack : counter) {
                if (stack.getLongValue() <= 0) {
                    continue;
                }
                if (list.size() >= MAX_PERSISTED_BATCHES) {
                    // 超出部分仍留在内存里、仍会重试，只是不进存档。
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Pattern disk provider saved only the first {} of {} entries in its "
                                + "pending-return buffer; the rest stay in memory only",
                                MAX_PERSISTED_BATCHES, list.size());
                    }
                    tag.put(NBT_PENDING_RETURNS, list);
                    return;
                }
                var keyTag = stack.getKey().toTagGeneric(registries);
                if (keyTag == null) {
                    // 与写攒批表同口径：编不出来的 key 至少留一条日志，否则「缓冲里少了一点」永远查不到。
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Pattern disk provider cannot serialise {} in its pending-return buffer; "
                                + "x{} stays in memory only", stack.getKey(), stack.getLongValue());
                    }
                    continue;
                }
                var entryTag = new CompoundTag();
                entryTag.put("key", keyTag);
                entryTag.putLong("amount", stack.getLongValue());
                list.add(entryTag);
            }
        }
        if (!list.isEmpty()) {
            tag.put(NBT_PENDING_RETURNS, list);
        }
    }

    private void readPendingReturns(CompoundTag tag, HolderLookup.Provider registries) {
        pendingReturns.clear();
        if (!tag.contains(NBT_PENDING_RETURNS, Tag.TAG_LIST)) {
            return;
        }
        var list = tag.getList(NBT_PENDING_RETURNS, Tag.TAG_COMPOUND);
        var counter = new KeyCounter();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            long amount = entry.getLong("amount");
            if (amount <= 0 || !entry.contains("key", Tag.TAG_COMPOUND)) {
                continue;
            }
            var key = AEKey.fromTagGeneric(registries, entry.getCompound("key"));
            if (key != null) {
                counter.add(key, amount);
            }
        }
        if (!counter.isEmpty()) {
            pendingReturns.add(counter);
        }
    }

    /**
     * 把攒批表写进 NBT。
     *
     * <p>样板是磁盘内容的派生态，存不进存档；这里存的是编码样板<b>物品</b>，与 {@code mirror} 里的那一张
     * 对应。反查靠 {@code IPatternDetails} 的 equals：{@code getAvailablePatterns()} 本身就是个 Set，
     * 样板实现必然重写了 equals/hashCode，否则父类没法去重。</p>
     *
     * <p>找不到对应物品的批直接跳过并记一条 warn——那说明磁盘已经换过、这条批成了孤儿，它的材料应该早在
     * {@code updatePatterns} 里退回去了，不应该还留在这里。</p>
     */
    private void writePendingBatches(CompoundTag tag, HolderLookup.Provider registries) {
        if (pendingBatches.isEmpty()) {
            return;
        }
        var be = host.getBlockEntity();
        var level = be == null ? null : be.getLevel();
        if (level == null) {
            return;
        }
        var byPattern = patternStacksByDetails(level);
        if (byPattern.isEmpty()) {
            return;
        }

        var list = new ListTag();
        int written = 0;
        boolean truncated = false;
        for (var entry : pendingBatches.entrySet()) {
            var patternStack = byPattern.get(entry.getKey());
            if (patternStack == null) {
                // 正常不可达：{@link #dropOrphanBatches} 在每次镜像重建后就把孤儿批退掉了。
                // 真到了这里说明磁盘在两次重建之间被动过，不写盘、也不在这里做副作用（存档只读）。
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Pattern disk provider at {} has a merged batch with no disk pattern left; "
                            + "it is not written to the save", be.getBlockPos());
                }
                continue;
            }

            var batches = new ListTag();
            for (var inputs : entry.getValue()) {
                if (written >= MAX_PERSISTED_BATCHES) {
                    truncated = true;
                    break;
                }
                written++;
                // 三层：每份 → 每个输入槽 → 条目。槽位形状必须留着，否则读档后的批与新建的批槽数不等，
                // BoostMerge 合并不了，那台样板会退化成逐份投递。
                var slotsTag = new ListTag();
                for (var counter : inputs) {
                    var slotTag = new ListTag();
                    if (counter != null) {
                        for (var stack : counter) {
                            if (stack.getLongValue() <= 0) {
                                continue;
                            }
                            var keyTag = stack.getKey().toTagGeneric(registries);
                            if (keyTag == null) {
                                if (LOGGER.isWarnEnabled()) {
                                    LOGGER.warn("Pattern disk provider cannot persist {} in a merged batch; "
                                            + "it will be missing from the save", stack.getKey());
                                }
                                continue;
                            }
                            var entryTag = new CompoundTag();
                            entryTag.put("key", keyTag);
                            entryTag.putLong("amount", stack.getLongValue());
                            slotTag.add(entryTag);
                        }
                    }
                    slotsTag.add(slotTag);
                }
                batches.add(slotsTag);
            }

            var patternTag = new CompoundTag();
            patternTag.put(NBT_PENDING_PATTERN, patternStack.saveOptional(registries));
            patternTag.put(NBT_PENDING_INPUTS, batches);
            list.add(patternTag);
            if (truncated) {
                break;
            }
        }
        if (!list.isEmpty()) {
            tag.put(NBT_PENDING_BATCHES, list);
        }
        if (truncated && LOGGER.isWarnEnabled()) {
            LOGGER.warn("Pattern disk provider at {} only saved the first {} of {} merged hand-over(s); the other {} "
                    + "stay in memory only. Set a hand-over cap to keep the save small", be.getBlockPos(),
                    MAX_PERSISTED_BATCHES, pendingCount(),
                    Math.max(0, pendingCount() - MAX_PERSISTED_BATCHES));
        }
    }

    /** 读档只搬运原始 tag：decode 要 level，而这里拿不到。 */
    private void readPendingBatches(CompoundTag tag) {
        pendingRestore = List.of();
        if (!tag.contains(NBT_PENDING_BATCHES, Tag.TAG_LIST)) {
            return;
        }
        var list = tag.getList(NBT_PENDING_BATCHES, Tag.TAG_COMPOUND);
        var restored = new ArrayList<PendingBatchRestore>(list.size());
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            if (!entry.contains(NBT_PENDING_PATTERN, Tag.TAG_COMPOUND)) {
                continue;
            }
            var batchesTag = entry.getList(NBT_PENDING_INPUTS, Tag.TAG_LIST);
            var batches = new ArrayList<ListTag>(batchesTag.size());
            for (int b = 0; b < batchesTag.size(); b++) {
                batches.add(batchesTag.getList(b));
            }
            restored.add(new PendingBatchRestore(entry.getCompound(NBT_PENDING_PATTERN), batches));
        }
        pendingRestore = restored;
    }

    /**
     * 首个 tick 把待还原的批解码回 {@code pendingBatches}。
     *
     * <p>解码不出来（样板已经不在磁盘里、或者物品 tag 损坏）时，这一批的材料<b>退回网络</b>；网络也没接上
     * （断电、已拆除）就掉在该机器位置——这两条都是为了不让 CPU 已经记过账的材料凭空消失。</p>
     */
    private void restorePendingBatches() {
        if (pendingRestore.isEmpty()) {
            return;
        }
        var be = host.getBlockEntity();
        var level = be == null ? null : be.getLevel();
        if (level == null) {
            return;
        }
        var restore = pendingRestore;
        pendingRestore = List.of();

        for (var entry : restore) {
            var patternStack = ItemStack.parseOptional(level.registryAccess(), entry.pattern());
            var details = patternStack.isEmpty() ? null : PatternDiskApi.decodePattern(patternStack, level);
            if (details == null) {
                // 样板没了：把这一批的材料原样退回去，不让它跟着方块一起消失。
                for (var inputsTag : entry.batches()) {
                    returnInputsToNetwork(flatten(readSlicedInputs(inputsTag, level.registryAccess())));
                }
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Pattern disk provider at {} could not restore a merged batch (pattern gone); "
                            + "its inputs go to the pending-return buffer", be.getBlockPos());
                }
                continue;
            }

            var existing = pendingBatches.get(details);
            var target = existing != null ? existing : new ArrayList<KeyCounter[]>();
            for (var inputsTag : entry.batches()) {
                var inputs = readSlicedInputs(inputsTag, level.registryAccess());
                if (isBlank(inputs)) {
                    continue;
                }
                target.add(inputs);
            }
            if (!target.isEmpty()) {
                pendingBatches.put(details, target);
            }
        }
        pendingBatchTick = gameTime();
    }

    /** 把一份按槽的输入还原成 KeyCounter[]；槽数原样保留，读档后的批才能与新建的批合并。 */
    private static KeyCounter[] readSlicedInputs(ListTag slotsTag, HolderLookup.Provider registries) {
        var inputs = new KeyCounter[slotsTag.size()];
        for (int slot = 0; slot < slotsTag.size(); slot++) {
            var counter = new KeyCounter();
            var slotTag = slotsTag.getList(slot);
            for (int i = 0; i < slotTag.size(); i++) {
                var entry = slotTag.getCompound(i);
                long amount = entry.getLong("amount");
                if (amount <= 0 || !entry.contains("key", Tag.TAG_COMPOUND)) {
                    continue;
                }
                var key = AEKey.fromTagGeneric(registries, entry.getCompound("key"));
                if (key != null) {
                    counter.add(key, amount);
                }
            }
            inputs[slot] = counter;
        }
        return inputs;
    }
    /** 把按槽的输入压成一个平坦 counter，供退回/掉落使用（那边不关心槽位）。 */
    private static KeyCounter flatten(KeyCounter[] inputs) {
        var counter = new KeyCounter();
        for (var slice : inputs) {
            if (slice == null) {
                continue;
            }
            for (var stack : slice) {
                counter.add(stack.getKey(), stack.getLongValue());
            }
        }
        return counter;
    }

    /** 一份输入里有没有料；全空就不必占一个位置。 */
    private static boolean isBlank(KeyCounter[] inputs) {
        for (var slice : inputs) {
            if (slice != null && !slice.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 磁盘镜像里每一张样板对应的物品：{@code IPatternDetails} 靠 equals 反查到它来自哪一格。 */
    private Map<IPatternDetails, ItemStack> patternStacksByDetails(Level level) {
        var result = new HashMap<IPatternDetails, ItemStack>();
        for (int slot = 0; slot < mirror.size(); slot++) {
            var stack = mirror.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var details = PatternDiskApi.decodePattern(stack, level);
            if (details != null) {
                result.putIfAbsent(details, stack);
            }
        }
        return result;
    }

    /**
     * 把材料退回网络。
     *
     * <p>网络没接上（断电、被拆下来）或装不下时，余量进 {@link #pendingReturns} 静静等着，
     * 通电后每 {@link BoostMergeLimit#QUIET_TICKS} 个 tick 重试一次。<b>不掉地上</b>：掉落物会过期、
     * 玩家也未必在场，那是不可回滚的丢料；只有拆机（{@code addDrops}）才该掉。
     */
    private void returnInputsToNetwork(KeyCounter inputs) {
        var leftover = insertIntoNetwork(inputs);
        if (leftover != null && !leftover.isEmpty()) {
            // 网络接不上或装不下：先攒着，通电后节流重试。不掉地上——那是不可回滚的。
            pendingReturns.add(leftover);
        }
    }

    /** 把材料插回网络，返回没插进去的那部分（全部插进去时返回 null）。 */
    @Nullable
    private KeyCounter insertIntoNetwork(KeyCounter inputs) {
        // 节点可能尚未初始化或已销毁（AE2 的 IManagedGridNode 允许 getNode() 为空，面板形态首 tick
        // 尤其容易碰上）。这里不能直接解引用：抛异常会打断 tickServer，而本意是「接不上就静静等着」。
        var node = mainNode.getNode();
        if (node == null || node.getGrid() == null) {
            return inputs;
        }
        var network = node.getGrid().getStorageService().getInventory();
        KeyCounter leftover = null;
        for (var entry : inputs) {
            long amount = entry.getLongValue();
            if (amount <= 0) {
                continue;
            }
            long inserted = network.insert(entry.getKey(), amount, Actionable.MODULATE, actionSource);
            long rest = amount - inserted;
            if (rest > 0) {
                if (leftover == null) {
                    leftover = new KeyCounter();
                }
                leftover.add(entry.getKey(), rest);
            }
        }
        return leftover;
    }

    /**
     * 把攒着待退回的材料再试一次。
     *
     * <p>节流到每 {@link BoostMergeLimit#QUIET_TICKS} 个 tick 一次：网络断开时这两个数可能是几百 tick，
     * 每 tick 跑一次模拟插入没意义，还会把主线程的预算吃掉。</p>
     */
    private void retryReturns(long now) {
        if (pendingReturns.isEmpty() || now < nextReturnAttempt) {
            return;
        }
        nextReturnAttempt = now + BoostMergeLimit.QUIET_TICKS;
        // 整表替换而不是边遍历边改：同一次重试里没退完的会变成新的剩余量，直接写回原表会 CME。
        var stillPending = new ArrayList<KeyCounter>();
        for (var pending : pendingReturns) {
            var leftover = insertIntoNetwork(pending);
            if (leftover != null && !leftover.isEmpty()) {
                stillPending.add(leftover);
            }
        }
        pendingReturns.clear();
        pendingReturns.addAll(stillPending);
    }

    private static ProviderBlockingMode parseBlockingMode(String name) {
        for (var mode : ProviderBlockingMode.values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return ProviderBlockingMode.DEFAULT;
    }

    private static ProviderExtractionMode parseExtractionMode(String name) {
        for (var mode : ProviderExtractionMode.values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return ProviderExtractionMode.OFF;
    }

    /** 拆机时把合并中的物品掉出去，与父类处理发送队列同口径。 */
    @Override
    public void addDrops(List<ItemStack> drops) {
        super.addDrops(drops);
        for (var batch : pendingBatches.values()) {
            for (var inputs : batch) {
                for (var counter : inputs) {
                    for (var entry : counter) {
                        if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() > 0) {
                            drops.add(itemKey.toStack(
                                    (int) Math.min(Integer.MAX_VALUE, entry.getLongValue())));
                        }
                    }
                }
            }
        }
        pendingBatches.clear();
        // 退回缓冲里也是 CPU 已记账的材料：拆机时不掉出去就永远没有了。
        for (var counter : pendingReturns) {
            for (var entry : counter) {
                if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() > 0) {
                    drops.add(itemKey.toStack(
                            (int) Math.min(Integer.MAX_VALUE, entry.getLongValue())));
                }
            }
        }
        pendingReturns.clear();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (rebuildingMirror) {
            // Keep the parent's change-is-persisted semantics, drop the redundant re-decode.
            saveChanges();
            return;
        }
        super.onChangeInventory(inv, slot);
    }
}
