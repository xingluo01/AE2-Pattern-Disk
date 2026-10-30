package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.api.storage.StorageCells;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.blockentity.inventory.AppEngCellInventory;
import appeng.core.definitions.AEItems;
import appeng.helpers.IPriorityHost;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.filter.IAEItemFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.api.PatternDiskTerminalView;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.logic.BatchLane;
import io.github.lounode.ae2pattern.common.logic.BatchPatternAnalyser;
import io.github.lounode.ae2pattern.common.logic.BatchRecipePool;
import io.github.lounode.ae2pattern.common.logic.BatchSynthesisEngine;
import io.github.lounode.ae2pattern.common.logic.BatchWorkState;
import io.github.lounode.ae2pattern.common.logic.CellBuffer;
import io.github.lounode.ae2pattern.common.logic.ParallelSlotProbe;
import io.github.lounode.ae2pattern.common.logic.PatternPlanCache;
import io.github.lounode.ae2pattern.common.logic.PatternPlan;
import io.github.lounode.ae2pattern.common.logic.SmoothReturnQueue;
import io.github.lounode.ae2pattern.common.util.DropStacks;

/**
 * Block entity of the batch assembler (批处理装配室).
 *
 * <p>It consumes crafting jobs pushed by AE2 crafting CPUs (via {@link ICraftingMachine}) and buffers
 * the pushed materials inside private storage-cell slots instead of executing immediately. Once no new
 * material has arrived for a short while, the buffered jobs are assembled in one batch - crafting table,
 * smithing table and stonecutting recipes are all supported - and the results (including container
 * remainders) are pushed back into the ME network, where the crafting CPU continues from there.</p>
 *
 * <p>The cell slots never join the ME network storage: this block entity deliberately implements neither
 * {@code IStorageProvider} nor the {@code ME_STORAGE} capability. Material that is still sitting in them
 * once the machine has gone idle is handed back to the network after {@link #IDLE_FLUSH_TICKS} quiet ticks,
 * so leftovers do not pile up in the machine; a full network defers that handover rather than dropping it.</p>
 *
 * <p>The machine runs two lanes, not one queue: a light lane for the scattered small orders and a bulky lane
 * for whole-hand-over batches and the orders that accumulated past a threshold. Each lane keeps its own quiet
 * window, its own batch classification and its own operation budget, so a bulk order that keeps arriving no
 * longer pushes a small order's countdown back - the window measures how quiet <em>that lane</em> is. Two delays
 * are cut on purpose per lane: a window that gathered next to nothing - fewer than eight jobs - is dead time on
 * the next arrival, so the lane then starts after a single quiet tick instead; every
 * {@link BatchLane#PROBE_EVERY_RUNS} such runs it serves the full window once more, which is how a supply that
 * has turned into a steady stream gets noticed instead of being run push by push forever, and a silence of
 * {@link BatchLane#IDLE_RESET_WINDOWS} windows wipes the classification so an order arriving after a long pause
 * aggregates exactly as it always did. Runs that do gather a batch always serve the full window, which is what
 * keeps a bulk order accumulating. A lane whose backlog has waited {@link #BATCH_AGE_LIMIT_TICKS} is let through
 * regardless of the window, which is the only hard bound on a supply that never goes quiet. Keys the network is
 * currently waiting for skip the smooth-return trickle too, instead of arriving over {@link #OUTPUT_RETURN_TICKS}
 * ticks.</p>
 */
public class BatchAssemblerBlockEntity extends AENetworkedBlockEntity
        implements InternalInventoryHost, IUpgradeableObject, IGridTickable, ICraftingMachine, ICraftingProvider,
        IPatternDiskHost, PatternContainer, IPriorityHost {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchAssemblerBlockEntity.class);

    public static final int CELL_SLOTS = 9;
    public static final int DISK_SLOTS = 9;
    public static final int MAX_SPEED_CARDS = 4;

    /** Flush window: idle ticks after the last input that trigger a batch, regardless of queue size. */
    private static final int STANDARD_BATCH_TICKS = 40;
    private static final int FAST_BATCH_TICKS = 10;
    // 分批口径（短窗、整窗探针、久停复位、分批大小）跟着通道走，见 {@link BatchLane}：它们现在是每条通道
    // 各自的状态，不再是整台机器共用的一份。
    /** Quiet ticks after which an idle machine hands whatever its cells still hold back to the network. */
    private static final int IDLE_FLUSH_TICKS = 10;

    /** Upper bound on what a claimed key hands over in one tick (never less than the smooth rate). A
     * catalyst-sized amount therefore arrives in full on the spot, while a huge batch still never becomes
     * one giant IO burst - the reason the smooth return exists at all. */
    private static final long PRIORITY_RETURN_BURST = 512;
    /**
     * Smooth-return horizon: accumulated outputs are returned to the network over this many ticks
     * (one tick's share — 1/N of the accumulated total — per tick), so a huge batch never produces one giant
     * IO burst.
     *
     * <p>{@code 1} means everything is handed over at once, i.e. no smoothing. That is the default because the
     * trickle cannot bound anything once a queue grows large: the priority branch below raises the hand-over to
     * {@link #PRIORITY_RETURN_BURST} when the network is waiting for a key, and once one tick's share is at or
     * above that burst, both branches compute the same amount - the trickle never applies. A share reaches the
     * burst as soon as a key holds more than {@code OUTPUT_RETURN_TICKS * PRIORITY_RETURN_BURST} items, which
     * the orders this machine is built for exceed by orders of magnitude. Handing such a queue over in one go
     * was measured to be harmless: several hundred billion items in a single insert cost a fraction of a
     * millisecond, because the storage layer books per key rather than per item.
     *
     * <p>A machine whose output keys the network never asks for would take the trickle path at every key, and
     * there a larger value still spreads the return. That is why the value is configurable rather than gone.</p>
     *
     * <p>Overridable with {@code -Dae2pattern.outputReturnTicks=N}, parsed as in {@code Integer.getInteger} (so
     * {@code 0x} and a leading-zero octal are accepted) and floored at 1. A malformed value falls back to this
     * default.</p>
     */
    private static final int OUTPUT_RETURN_TICKS =
            Math.max(1, Integer.getInteger("ae2pattern.outputReturnTicks", 1));

    private static final double ENERGY_PER_RUN = 10.0;

    /**
     * What the machine is doing, or why it is not doing it. 值域与每档含义在 {@link BatchWorkState}：
     * 它得被拆解出的执行引擎（{@code common/logic}）写，所以状态枚举不能留在方块实体里。
     */
    private BatchWorkState workState = BatchWorkState.IDLE;

    /** Client-side mirror of the ME node state; synced through the block entity stream. */
    private boolean isActive = false;

    /** Set when a return attempt found the network unwilling to take what was waiting. */
    private boolean returnStalled;

    /**
     * ticker 是否醒着。醒着时它按 {@code TickingRequest(1, 1)} 每 tick 被调一次；再 alert 只会让 AE2 把它的
     * 队列项摘下来再插回去（{@code PriorityQueue.remove} 是线性扫描），到货越碎这笔越贵。
     *
     * <p>拿不准就置 {@code false}：多一次 alert 只是白跑一趟，漏一次就是机器睡着没人叫。</p>
     */
    private boolean tickerAwake = false;

    /**
     * 本 tick 的输入键快照与它对应的游戏时间。重建要遍历两条通道的整条队列，而同一 tick 里可能 drain 多次
     * （外部还会经 {@code flushReturnsAfterCpuAccounting} 再叫一次）。
     *
     * <p>同 tick 内队列变了也不重算：这个集合只决定「哪些 key 跳过平滑直接交」，旧一步只影响交付节奏，
     * 不影响记账。</p>
     */
    private Set<AEKey> queuedInputKeyCache = Set.of();
    private long queuedInputKeyCacheTick = -1;

    /**
     * 本机的样板优先级（AE2 供应器界面里那个）。
     *
     * <p>两处作用：一是 {@link #getPatternPriority()} 把它交给 AE2 的合成计算——同一产物的多条样板里优先
     * 用优先级高的，这是真正生效的那一环；二是 AE2 自带的优先级界面（本机屏上那枚按钮）读写这个值。</p>
     *
     * <p>与「元件存储优先级」（驱动器决定谁先被写入）不是同一件事，两组数字互不影响。</p>
     */
    private int priority;

    /** @return how many assembly jobs are still queued on both lanes. */
    public long getQueuedJobCount() {
        return lightLane.queuedJobs() + bulkLane.queuedJobs();
    }

    /**
     * @return the quiet ticks still needed before the earliest lane runs, or 0 when one is free to run now
     */
    public int getTicksUntilBatch() {
        long now = currentGameTime();
        int window = batchIdleTicks();
        int wait = Integer.MAX_VALUE;
        if (!lightLane.isEmpty()) {
            wait = lightLane.ticksUntilRun(now, window);
        }
        if (!bulkLane.isEmpty()) {
            wait = Math.min(wait, bulkLane.ticksUntilRun(now, window));
        }
        return wait == Integer.MAX_VALUE ? 0 : wait;
    }

    /** @return whether neither lane has anything left to assemble. */
    private boolean queueEmpty() {
        return lightLane.isEmpty() && bulkLane.isEmpty();
    }

    /** @return what the machine is doing, or why it is not doing it. */
    public BatchWorkState getWorkState() {
        // The structural reasons are worked out here rather than recorded while ticking, because ticking only
        // runs while the machine is active: a machine that is offline, has no cell to work from, or has
        // nothing executable on its disks would otherwise keep reporting whatever it was last doing. That
        // staleness is also what made an offline machine come out as "no power" - the same node check feeds
        // both, so it has to be asked here first.
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            return BatchWorkState.OFFLINE;
        }
        if (!acceptsPlans()) {
            return BatchWorkState.NO_BUFFER;
        }
        if (exposedPatterns.isEmpty()) {
            return BatchWorkState.NO_PATTERN;
        }
        if (!returns.isEmpty() && queueEmpty()) {
            // Distinguished from a return in progress because only one of the two has anything its owner can act
            // on, and from the outside the queue draining and the queue stuck look exactly alike.
            return returnStalled ? BatchWorkState.OUTPUT_BLOCKED : BatchWorkState.RETURNING_OUTPUTS;
        }
        if (queueEmpty() && returns.isEmpty()) {
            return BatchWorkState.IDLE;
        }
        return workState;
    }

    /**
     * Whether the ME node is online, which drives the block's powered state (off/on model).
     *
     * <p>The server reads the live node state; the client uses the value pushed through
     * {@link #writeToStream}/{@link #readFromStream}. Same wiring as
     * {@code PatternTransfererBlockEntity} and AE2's IO port.</p>
     */
    public boolean isActive() {
        if (level != null && !level.isClientSide()) {
            return this.getMainNode().isOnline();
        }
        return this.isActive;
    }

    @Override
    public void onMainNodeStateChanged(appeng.api.networking.IGridNodeListener.State state) {
        // Grid boot is skipped like AE2's IO port: the node state is not settled yet, and
        // AENetworkedBlockEntity.onReady() aligns the block state once the node exists.
        if (state != appeng.api.networking.IGridNodeListener.State.GRID_BOOT) {
            markForUpdate();
            tickerAwake = false;
        }
    }

    @Override
    protected void writeToStream(net.minecraft.network.RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeBoolean(this.isActive());
    }

    @Override
    protected boolean readFromStream(net.minecraft.network.RegistryFriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);

        boolean active = data.readBoolean();
        changed = active != this.isActive || changed;
        this.isActive = active;

        return changed;
    }

    private final AppEngCellInventory cellInv = new AppEngCellInventory(this, CELL_SLOTS);
    private final AppEngInternalInventory diskInv = new AppEngInternalInventory(this, DISK_SLOTS);
    private final IUpgradeInventory upgrades;
    private final IActionSource actionSource = new MachineSource(this);

    /** 元件缓冲：解析、读写与「还剩多少」都在 {@link CellBuffer} 里，这里只持有它。 */
    private final CellBuffer cellBuffer = new CellBuffer(cellInv, actionSource, this::saveChanges);

    private final BatchRecipePool recipePool = new BatchRecipePool();
    private final List<IPatternDetails> exposedPatterns = new ArrayList<>();

    /**
     * 两条任务通道。轻载通道收零散的小订单，重载通道收整批 hand-over 与攒起来的大订单；两条道各有自己的
     * 静默窗口、分批分类与操作预算，于是大订单的持续到货不会再把小订单的计时拨回去。判定与记账都在
     * {@link BatchLane}，这里只决定谁进哪条道。
     */
    private final BatchLane<IPatternDetails> lightLane = new BatchLane<>(BATCH_AGE_LIMIT_TICKS);
    private final BatchLane<IPatternDetails> bulkLane = new BatchLane<>(BATCH_AGE_LIMIT_TICKS);

    /** 两条通道，按调度顺序：轻载先跑，重载拿剩下的预算。 */
    private final List<BatchLane<IPatternDetails>> lanes = List.of(lightLane, bulkLane);

    /**
     * 对外公布的并行槽位上限（long 契约用），默认不限。
     *
     * <p><b>它不是探测值的乘数。</b>估算实际上按「配置上限与本次调用能用到的数量取小」去探测——即
     * {@link ParallelSlotProbe#estimate} 的 {@code cap} 参数。契约把槽位数写成 int 的调用方（NEO ECO 的并行
     * 派发）传 {@link Integer#MAX_VALUE}，于是探测只按 21.4 亿份去问，元件不必为多出来的两个数量级做模拟插入；
     * 契约是 long 的（OmniSequence）传这个常量，拿到的是元件真实能装下的份数。两条契约共用同一个记忆槽，
     * 但按「配方 + tick + 上限」分键：答案不会互相污染，代价是同一配方被两条契约各问一次时要重算一遍。</p>
     *
     * <p><b>为什么不设小。</b>这个数就是「一次交接能带走几份完整配方」：调小它只会把 CPU 必须做的交接
     * 次数成倍放大。这台机器面向的订单——9:1 逐阶压缩九层、目标 2.1e9 个九重——底层是 {@code 8.13e17} 件
     * 物品，整条链约 {@code 1.0e17} 次合成。</p>
     *
     * <p>用 {@code -Dae2pattern.maxParallelSlots=N} 可收紧上限。{@code N} 按 {@code Long.getLong} 的规则解析
     * （与 {@code Integer.getInteger} 一样接受 {@code 0x} 与前导零八进制），写坏了就回落到这个默认值——那会
     * 悄悄改变对照实验里另一组的结果，所以启动时请核对 {@link #logEffectiveTuning} 打出的实际值。小于等于零
     * 按 1 处理：一次交接一份配方，只用于对照实验的下限组。默认值必须写成原始数字字面量（{@code int} 或
     * {@code long} 都行）：这样才能选中 {@code getLong(String, long)}，它不会装箱。要小心另外两个会装箱的重载
     * ——{@code getLong(String, Long)} 遇到畸形值返回的是你传进去的默认值（不是 null），而单参的
     * {@code getLong(String)} 才真的返回 null，对它拆箱会抛异常。</p>
     */
    private static final long MAX_ADVERTISED_PARALLEL_SLOTS =
            Math.max(1L, Long.getLong("ae2pattern.maxParallelSlots", Long.MAX_VALUE));

    /**
     * How many synthesis operations one server tick may perform.
     *
     * <p>An operation is either one amortised block - any number of crafts sharing one variant per slot - or
     * one craft on the slow path. That is what keeps this ceiling from bounding the order: a single variant
     * covering a billion crafts is still one operation, so the limit only starts to matter once a buffer holds
     * so many distinct variants that the blocks themselves add up. Counting crafts instead would make a large
     * amortised batch look expensive and throttle exactly the case that is already cheap.</p>
     *
     * <p>Overridable with {@code -Dae2pattern.operationsPerRun=N}. As with the other tuning properties, values
     * are read through {@code Integer.getInteger} - and the parallel-slot ceiling through {@code Long.getLong},
     * since it is a long - so a malformed one falls back to the default and a value at or below zero becomes 1 -
     * see {@link #logEffectiveTuning}.</p>
     */
    private static final int OPERATIONS_PER_RUN =
            Math.max(1, Integer.getInteger("ae2pattern.operationsPerRun", 128));

    /**
     * 两条通道同时就绪时轻载通道最多用掉的操作数，其余留给重载通道。
     *
     * <p>只有一条就绪时它拿满 {@link #OPERATIONS_PER_RUN}：份额是为了让两边同时跑时不被对方挤掉一整轮，
     * 不是给任意一方设上限。默认对半分。{@code OPERATIONS_PER_RUN} 被调到 1 时轻载通道会拿走那唯一一个操作，
     * 重载通道本轮让位、下一轮再跑——这是该极端配置下唯一的交接方式。</p>
     *
     * <p>Overridable with {@code -Dae2pattern.lightOperationShare=N}; same parsing and same fallback as the
     * other tuning properties - see {@link #logEffectiveTuning}.</p>
     */
    private static final int LIGHT_OPERATION_SHARE =
            Math.max(1, Integer.getInteger("ae2pattern.lightOperationShare", OPERATIONS_PER_RUN / 2));

    /** 一个样板在轻载通道累计到这么多件就迁到重载通道：单件推送的大订单只能靠件数认出来。 */
    private static final long PROMOTE_TO_BULK_JOBS =
            Math.max(2L, Long.getLong("ae2pattern.promoteToBulkJobs", BatchLane.SMALL_RUN_JOBS));

    /**
     * 同一段积压最多等多少 tick 就强制放一轮。
     *
     * <p>静默判定对「一直有料进来」的通道没有上界：只要还在推，窗口就永远不闭合，一条稳定的供料流能把一条
     * 通道压到地老天荒。上限兜住这一头，保证任何一件任务最多等这么久就会被跑到。
     * {@code 0} 关掉上限，只按静默判定。</p>
     *
     * <p>Overridable with {@code -Dae2pattern.batchAgeLimitTicks=N}.</p>
     */
    private static final int BATCH_AGE_LIMIT_TICKS =
            Math.max(0, Integer.getInteger("ae2pattern.batchAgeLimitTicks", 120));

    /**
     * Most item stacks a block break may scatter for one leftover key.
     *
     * <p>Splitting a very large leftover the ordinary way creates one entity per stack - a machine holding a
     * billion leftover items would scatter fifteen million of them and take the server with it. Past this many
     * stacks the remainder is carried in oversized stacks instead, which keeps the material rather than
     * dropping it and produces a handful of entries instead of millions.</p>
     */
    private static final int MAX_LEFTOVER_DROP_STACKS =
            Math.max(1, Integer.getInteger("ae2pattern.maxLeftoverDropStacks", 1024));

    static {
        logEffectiveTuning();
    }

    /**
     * Reports the tuning values at class load, but only for the properties that were actually set. All are read
     * with {@code Integer.getInteger} - or {@code Long.getLong} for the parallel-slot ceiling - which fall back to
     * the default on a malformed value without saying so, and the difference decides how many hand-overs an order
     * costs, how much of a tick a batch may spend, or whether output is trickled at all. Printing only what was
     * overridden makes a mistyped launch argument visible while keeping a default launch silent, which is what a
     * release build has to be.
     */
    private static void logEffectiveTuning() {
        var slots = System.getProperty("ae2pattern.maxParallelSlots");
        var returns = System.getProperty("ae2pattern.outputReturnTicks");
        var operations = System.getProperty("ae2pattern.operationsPerRun");
        var dropStacks = System.getProperty("ae2pattern.maxLeftoverDropStacks");
        var lightShare = System.getProperty("ae2pattern.lightOperationShare");
        var promote = System.getProperty("ae2pattern.promoteToBulkJobs");
        var ageLimit = System.getProperty("ae2pattern.batchAgeLimitTicks");
        if (slots == null && returns == null && operations == null && dropStacks == null
                && lightShare == null && promote == null && ageLimit == null) {
            return;
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Batch assembler tuning overridden: maxParallelSlots={} (requested {}), "
                            + "outputReturnTicks={} (requested {}), operationsPerRun={} (requested {}), "
                            + "maxLeftoverDropStacks={} (requested {}), lightOperationShare={} (requested {}), "
                            + "promoteToBulkJobs={} (requested {}), batchAgeLimitTicks={} (requested {})",
                    MAX_ADVERTISED_PARALLEL_SLOTS, slots, OUTPUT_RETURN_TICKS, returns,
                    OPERATIONS_PER_RUN, operations, MAX_LEFTOVER_DROP_STACKS, dropStacks,
                    LIGHT_OPERATION_SHARE, lightShare, PROMOTE_TO_BULK_JOBS, promote,
                    BATCH_AGE_LIMIT_TICKS, ageLimit);
        }
    }

    /** The pattern the parallel-slot estimate is measured against: the last one actually handed over. */
    private IPatternDetails lastHandedPattern;

    /** 并行槽位估算与它每 tick 的记忆值都在 {@link ParallelSlotProbe} 里，这里只持有它。 */
    private final ParallelSlotProbe parallelSlots = new ParallelSlotProbe(cellBuffer, MAX_ADVERTISED_PARALLEL_SLOTS);

    /** 配方计划、余料与告警记号都在 {@link PatternPlanCache} 里，这里只持有它。 */
    private final PatternPlanCache plans = new PatternPlanCache();

    /**
     * 产出物的平滑回送队列：增速、额度与优先通道都在 {@link SmoothReturnQueue} 里，这里只持有它。
     */
    private final SmoothReturnQueue returns = new SmoothReturnQueue(OUTPUT_RETURN_TICKS, PRIORITY_RETURN_BURST);

    /**
     * 一条作业怎么兑现成产物（摊销块与单件慢路径）都在 {@link BatchSynthesisEngine} 里，这里只持有它。
     * 能量与状态回写是它跟本机借的两样东西：网格不属于它，状态栏也不属于它。
     */
    private final BatchSynthesisEngine engine = new BatchSynthesisEngine(cellBuffer, plans, returns,
            this::getLevel, this::getBlockPos, this::consumePower, s -> workState = s);

    /** 机器最后一次真正干活的游戏时间（收货、跑批、回送都算）。闲置回灌从这里数静默 tick。 */
    private long lastActivityGameTime;
    /** Whether this idle period already tried the cell flush - the network may simply be full, and retrying
     * that every tick would be pointless. Cleared as soon as the machine does anything again. */
    private boolean idleFlushDone;
    /** Fast batch mode flushes after 10 quiet ticks instead of the standard 40. */
    private boolean fastBatchMode = false;
    private final BatchPatternAnalyser analyser;

    public BatchAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_BATCH_ASSEMBLER.get(), pos, blockState);

        this.upgrades = UpgradeInventories.forMachine(AEPatternRegistries.BLOCK_BATCH_ASSEMBLER.get(),
                MAX_SPEED_CARDS, this::onUpgradesChanged);
        // 只能在这里构造：它读的就是上面那个升级库存。
        this.analyser = new BatchPatternAnalyser(
                () -> upgrades.getInstalledUpgrades(AEItems.SPEED_CARD), this::getBlockPos);

        // 它向自动合成暴露样板并自己从网络取料，按 AE2 的设备口径要占 1 个频道。
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0)
                .setInWorldNode(true)
                .setExposedOnSides(java.util.Set.of(net.minecraft.core.Direction.values()))
                .addService(IGridTickable.class, this)
                .addService(ICraftingProvider.class, this);

        this.cellInv.setFilter(new IAEItemFilter() {
            @Override
            public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
                return !stack.isEmpty() && StorageCells.isCellHandled(stack);
            }

            @Override
            public boolean allowExtract(InternalInventory inv, int slot, int amount) {
                // Cells may only leave while nothing is buffered (anti-duplication guard). isBusy()
                // only means "cannot take more jobs" and is false during normal operation, so it must
                // not be used to guard item extraction.
                return !hasBufferedWork();
            }
        });
    }

    private void onUpgradesChanged() {
        // Speed cards size the analysis pool, so dropping cards must not leave the old threads idle. The
        // pool only exists once cards were installed, hence the workerCount guard before touching upgrades.
        analyser.onSpeedCardsChanged(upgrades.getInstalledUpgrades(AEItems.SPEED_CARD));
        saveChanges();
        alertTicker();
    }

    // ---- slots ---------------------------------------------------------------

    public InternalInventory getCellInventory() {
        return cellInv;
    }

    public AppEngInternalInventory getDiskInventory() {
        return diskInv;
    }

    /** 与供应器同口径：本机也是 {@code PatternContainer}，直接转发 AE2 那个「在样板访问终端中显示」开关。 */
    @Override
    public boolean isVisibleInPatternAccessTerminal() {
        return isVisibleInTerminal();
    }

    // 元件缓冲的解析与读写都在 {@link CellBuffer} 里：换元件、清空、读档要 markDirty，理由见那个类。

    // ---- recipe pool ---------------------------------------------------------

    /** Rebuilds the shared recipe pool from every inserted pattern disk and re-exposes it to the CPU. */
    public void refreshRecipePool() {
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < DISK_SLOTS; i++) {
            var disk = diskInv.getStackInSlot(i);
            if (!(disk.getItem() instanceof PatternDiskItem diskItem)) {
                continue;
            }
            var contents = diskItem.contents(disk);
            if (contents == null) {
                continue;
            }
            stacks.addAll(contents.patterns());
        }
        recipePool.rebuild(stacks, getLevel());
        // Patterns are decoded again here, so every cached plan is potentially stale: a plan stores output
        // amounts and container remainders, which a recipe reload can change for the same pattern value.
        plans.clear();
        // Remainders and damage notes describe the patterns that were just re-decoded; both are keyed by
        // pattern instances, so they have to go together with the plans.
        plans.clearNotes();
        // The pattern the parallel-slot estimate was measured against may be gone from the disks now.
        lastHandedPattern = null;
        parallelSlots.invalidate();

        exposedPatterns.clear();
        exposedPatterns.addAll(recipePool.all());
        if (getMainNode().getNode() != null) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    // ---- ICraftingMachine (jobs pushed by AE2 pattern providers) -------------

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs, net.minecraft.core.Direction ejectionDirection) {
        return acceptPush(patternDetails, inputs);
    }

    /**
     * Common intake for both push entry points (CPU direct push and adjacent pattern provider):
     * buffers the delivered inputs and books exactly one job per push. Job volume must never be
     * scaled here - the CPU will still deliver the full order one push at a time, so any
     * compensation would multiply the total output beyond the ordered amount.
     */
    private boolean acceptPush(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern)) {
            // Only crafting / smithing / stonecutting can be executed here. Accepting an AE processing
            // pattern would let the machine turn whatever was pushed in into the pattern's outputs.
            return false;
        }
        if (patternDetails.getInputs().length == 0) {
            // Defensive: a pattern without inputs would queue forever as an empty freebie.
            return false;
        }
        if (!acceptsPlans()) {
            return false;
        }
        if (!bufferInputs(inputHolder)) {
            // 拒收就封本 tick，理由同 {@link #acceptPatternBatch}。
            denyParallelSlots(patternDetails);
            return false;
        }
        // Each push books exactly one job: the CPU delivers the full order one push at a time, so the job
        // volume must never be scaled here. NEO ECO's parallel hand-over is the other intake path, and it
        // books as many jobs as the CPU itself counted - see acceptPatternBatch.
        bookArrival(patternDetails, 1L);
        return true;
    }

    /**
     * Books {@code jobs} arrivals of one pattern and re-arms the batch window. Both intake paths end here:
     * AE2's one-craft push, and NEO ECO's parallel hand-over that delivers a whole batch of crafts - with
     * their complete input sets - in a single call.
     */
    private void bookArrival(IPatternDetails patternDetails, long jobs) {
        long now = currentGameTime();
        routeArrival(patternDetails, jobs, now);
        lastHandedPattern = patternDetails;
        idleFlushDone = false;
        lastActivityGameTime = now;
        // 元件内容刚刚变过，估算记忆值必须作废。它不是「缓存不准」这种精度问题：ECO 拿到同一 tick 的旧答案会
        // 再交付同样的份数，而本机是 all-or-nothing，于是拒绝、ECO 重试、旧答案再报同一个数——服务端主线程
        // 在这个环里出不来（表现为世界停更、除背包外任何 GUI 都打不开，因为那些都要服务端回包）。
        parallelSlots.invalidate();
        alertTicker();
        saveChanges();
    }

    /**
     * 把一次到货放进它该去的通道。
     *
     * <p>判定只看两件事，因为机器知道的信息就只有这两件：这一次 hand-over 带了几件，以及这个样板已在轻载
     * 通道上攒了多少。整批交付（并行接入路径）一次就是多件，直接进重载通道；单件推送先当小订单，等它累计到
     * {@link #PROMOTE_TO_BULK_JOBS} 再迁过去——一条大订单走 AE2 原生路径时只能靠件数认出来。</p>
     *
     * <p>迁移会把整份剩余件数原样搬过去，而不是重开一份：CPU 已经为这些件数过账，搬丢了就是少交货。
     * 搬过去的同时批龄从头算——重载通道的积压起点是这次迁移的时刻，所以迁过来的件数最多等一个
     * {@link #BATCH_AGE_LIMIT_TICKS}，不会继承轻载通道上已经等过的那段。</p>
     */
    private void routeArrival(IPatternDetails patternDetails, long jobs, long now) {
        int idleResetTicks = batchIdleTicks() * BatchLane.IDLE_RESET_WINDOWS;
        // 已经在重载通道上的样板不再回头：它已经是大订单了。
        if (bulkLane.contains(patternDetails)) {
            bulkLane.arrive(patternDetails, jobs, now, idleResetTicks);
            return;
        }
        if (lightLane.contains(patternDetails)) {
            long held = lightLane.remainingOf(patternDetails);
            if (held + jobs < PROMOTE_TO_BULK_JOBS) {
                lightLane.arrive(patternDetails, jobs, now, idleResetTicks);
                return;
            }
            // 攒够了：整份迁到重载通道，剩下的这点要在长窗口里聚合，而不是被短窗一件一件磨。
            lightLane.remove(patternDetails);
            bulkLane.arrive(patternDetails, held + jobs, now, idleResetTicks);
            return;
        }
        if (jobs > 1) {
            // 整批 hand-over：并行接入路径一次给出一份完整的多件任务。
            bulkLane.arrive(patternDetails, jobs, now, idleResetTicks);
            return;
        }
        lightLane.arrive(patternDetails, jobs, now, idleResetTicks);
    }

    @Override
    public boolean acceptsPlans() {
        // Whether the buffer can actually take the material is decided by bufferInputs; require at
        // least one usable cell so jobs are never accepted without a place to store the inputs.
        return cellBuffer.hasAnyCell();
    }

    /**
     * 给 NEO ECO 的阻塞开关探测一个快路径。
     *
     * <p>它每次派发前都会问「这个供应器开了阻塞吗」。探测方式是反射：先试 {@code getLogic()}，拿到的对象再看
     * 是不是 {@code PatternProviderLogic}、有没有 {@code isBlocking()}，最后才退回 {@code getConfigManager()}。
     * 本机三者都没有，于是每问一次就抛<b>三个</b> {@link NoSuchMethodException}，每个都要 {@code fillInStackTrace}。
     * 而它是在派发每个候选、每次派发之前问的——大订单下每 tick 上万次，异常构造的开销把主线程吃掉了（实测
     * 服务端线程 291 秒内烧了 118 秒 CPU，全在 ECO 的派发链里）。</p>
     *
     * <p>提供这两个方法让它走出那条路：{@code getLogic()} 返回自己，{@code isBlocking()} 答 {@code false}
     * ——与它原本反射全部失败后的返回值一致，行为不变，只是不再抛异常。</p>
     *
     * <p>签名刻意不涉及 NEO ECO 的任何类型：这里是总能加载的类，不能因为可选依赖而漏进邻居的类型名。</p>
     *
     * <p><b>三条不能动的约束</b>：</p>
     *
     * <ol>
     * <li>只能返回 {@code this}。返回别的对象会让对方后续的 {@code instanceof} 换一支走，要么被当成别的模组的
     * 逻辑，要么在别人的代码里 ClassCastException。它不是一个通用逻辑访问器，别的地方不要拿它当入口。</li>
     * <li>类与方法必须保持 {@code public}。类一旦改成包私有，{@code invoke} 会抛
     * {@code IllegalAccessException}，而那条 catch 的返回值是 {@code true}——语义直接反转成「认为本机在阻塞」。</li>
     * <li>将来真做了阻塞模式，{@link #isBlocking()} 必须跟着改，否则对方会继续按非阻塞批量派发。</li>
     * </ol>
     */
    public Object getLogic() {
        return this;
    }

    /**
     * 本机没有阻塞模式（不像 AE2 样板供应器那样「目标满了就停供」），所以一律答不在阻塞。
     *
     * <p>这不是占位值：它必须等于真实的阻塞行为。固定 {@code false} 是因为这台机器根本没有那个开关，而不是
     * 因为没人填。改成非 {@code false} 会改变 ECO 的派发策略。</p>
     *
     * @see #getLogic()
     */
    public boolean isBlocking() {
        return false;
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        // A real group, not nothing(): a machine reporting nothing() becomes a neighbour whose name
        // pattern providers borrow, so the pattern access terminal ends up showing "Nothing" with no
        // icon for every provider placed against it. AE2's own molecular assembler reports its icon and
        // name the same way this does.
        var item = AEPatternRegistries.ITEM_BATCH_ASSEMBLER.get();
        return new PatternContainerGroup(
                AEItemKey.of(item),
                item.getDescription(),
                List.of());
    }

    // ---- ICraftingProvider (patterns exposed to the ME autocrafting service) --

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return exposedPatterns;
    }

    /**
     * 样板优先级：AE2 的合成计算按它给同一产物的多条样板排序（高的先用）。
     *
     * <p>{@code ICraftingProvider} 的默认实现返回 0，而供应的每一条样板都带着发起它的供应器的这个值（见
     * {@code NetworkCraftingProviders} 的 {@code ProviderState}），所以本机只要覆写成自己的数就能参与排序。</p>
     */
    @Override
    public int getPatternPriority() {
        return priority;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public void setPriority(int newValue) {
        if (priority == newValue) {
            return;
        }
        priority = newValue;
        saveChanges();
        // 优先级变了，网络里的可合成清单得重算一次，否则改完要重载世界才见效（与 AE2 供应器同口径）。
        ICraftingProvider.requestUpdate(getMainNode());
    }

    /**
     * 优先级界面返回后回哪一屏：本机自己的界面。
     *
     * <p>AE2 那个界面是子菜单，它的返回键走宿主的这个方法——不重写就会跟着默认实现去别的屏。</p>
     */
    @Override
    public void returnToMainMenu(net.minecraft.world.entity.player.Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(io.github.lounode.ae2pattern.common.menu.BatchAssemblerMenu.TYPE, player,
                subMenu.getLocator());
    }

    /** 优先级界面那一角的图标：本机自己。 */
    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(getBlockState().getBlock());
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        return acceptPush(patternDetails, inputHolder);
    }

    /** True while the buffer cannot take any more jobs (no usable cell installed). */
    @Override
    public boolean isBusy() {
        // "Busy" makes the crafting CPU stop offering jobs to this provider, so it must only be true when
        // the buffer genuinely cannot take more - otherwise batching could never accumulate. There is no
        // queue-size limit: the cell buffer's own capacity is the natural bound.
        return !acceptsPlans();
    }

    // ---- batch intake (optional integrations) --------------------------------
    // Neither contract interface is on this class. Both are supplied by registration entry points instead:
    // NEO ECO's ({@code integration.neoecoae.BatchAssemblerParallelIntake}) and OmniSequence's
    // ({@code integration.omnisequence.OmniBatchAdapter}). That is what keeps this class free of both mods'
    // types - in a pack without either, nothing is registered and the methods below are just this machine's
    // own bookkeeping entry points.

    // 类型条目的容量换算在 {@link CellBuffer#typeEntryUnits}，含「为什么按每个槽位各预留一份」的取舍。

    /**
     * 元件缓冲还能吃下多少个完整配方（用于 CPU 正在派发的那个配方）。估算算法与它的取舍都在
     * {@link ParallelSlotProbe}。
     *
     * <p>返回 {@code long}：上限是 {@link #MAX_ADVERTISED_PARALLEL_SLOTS}，默认不限，所以够大的元件能报出
     * {@code int} 说不出的份数。契约指名 int 的调用方走 {@link #availableParallelSlots()}，那里用 int 上限
     * 单独探测一次并夹住。</p>
     */
    public long availableParallelSlotsLong() {
        return availableParallelSlotsFor(currentDispatchPattern());
    }

    /**
     * {@link #availableParallelSlotsLong()} measured against a caller-supplied pattern instead of this
     * machine's own guess at what is being dispatched.
     *
     * <p>For contracts that name the pattern before asking for capacity. OmniSequence's batch probe is one:
     * it hands over a single pattern and asks how many complete crafts fit. Answering from the machine's own
     * guess would be wrong there - a machine that has not been handed anything yet has an empty queue, so
     * {@link #currentDispatchPattern()} is null and the probe would always see zero, meaning batch delivery
     * could never start. The machine's own guess stays the default for callers that have no pattern to offer
     * (NEO ECO's {@code eco$getAvailableParallelSlots()}, and AE2 asking whether the machine is idle).</p>
     *
     * <p>The estimate is the same code path either way, cache included, so a probe naming the pattern it is
     * about to deliver measures capacity against that pattern's real inputs.</p>
     */
    public long availableParallelSlotsFor(@Nullable IPatternDetails patternDetails) {
        // 没有配方时不碰时钟：空闲机器每 tick 都会被问到这里，没必要为了一个必然为 0 的答案读世界时间。
        if (patternDetails == null) {
            return 0L;
        }
        long estimate = parallelSlots.estimate(patternDetails, currentGameTime(), MAX_ADVERTISED_PARALLEL_SLOTS);
        return Math.max(0L, estimate);
    }

    /**
     * Hands over whatever the return queue already owns, without waiting for the queue's own pacing.
     *
     * <p>For OmniSequence's post-accounting hook ({@code OmniPostAccountingOutputAdapterRegistry}): that CPU
     * asks before it judges whether a crafting job's {@code waitingFor} has been satisfied, and output still
     * sitting in this queue reads as "not delivered yet" - the job then stalls even though the machine has
     * already produced it. So this moves output the machine already owns; it never assembles anything, which
     * is exactly what the hook promises.</p>
     *
     * <p>It does not double-deliver: {@code SmoothReturnQueue.drain} hands over only its own allowance each
     * call and removes what it delivered, so an extra call can at most spend that allowance early.</p>
     */
    public void flushReturnsAfterCpuAccounting() {
        if (!returns.isEmpty()) {
            drainOutputs();
        }
    }

    /**
     * {@link #availableParallelSlotsLong()} 夹到 {@code int}，给契约把类型写成 int 的调用方。
     *
     * <p>NEO ECO 的并行派发把槽位数写成 {@code int}，所以那条路径无论元件还有多少空间，一次交接都不可能
     * 超过 {@link Integer#MAX_VALUE} 份。这里因此按 {@code Integer.MAX_VALUE} 单独探测一次，而不是先算 long
     * 再夹：按 long 去算会用 {@link #MAX_ADVERTISED_PARALLEL_SLOTS} 当探测值，而这条路径根本用不到那么多，
     * 多出来的部分只是让元件的模拟插入变贵。一个 tick 能装下几份配方与此无关——摊销路径是按块而不是按份数
     * 计一次交接的。</p>
     */
    public int availableParallelSlots() {
        var patternDetails = currentDispatchPattern();
        if (patternDetails == null) {
            return 0;
        }
        long estimate = parallelSlots.estimate(patternDetails, currentGameTime(), Integer.MAX_VALUE);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, estimate));
    }

    /**
     * 封本 tick 的可交付份数：两条契约各按自己的上限封一次。
     *
     * <p>只封一个档位不够：刚拒收的那条路径用的可能是另一个上限，另一档仍会拿着未过期的旧值让供给方再交付。</p>
     */
    private void denyParallelSlots(IPatternDetails patternDetails) {
        long now = currentGameTime();
        parallelSlots.denyFor(patternDetails, now, MAX_ADVERTISED_PARALLEL_SLOTS);
        parallelSlots.denyFor(patternDetails, now, Integer.MAX_VALUE);
    }

    /**
     * Takes a whole batch in one hand-over. {@code inputTotal} is the complete input for {@code craftCount}
     * crafts - not one copy of it - which is how NEO ECO's parallel dispatch states the contract, and why
     * nothing has to be scaled here: the CPU counted every craft already and books exactly these.
     *
     * <p>All or nothing, like every other intake: either the buffer takes the complete total and the jobs
     * are booked, or the counters are left untouched and ECO keeps the material.</p>
     */
    public boolean acceptPatternBatch(IPatternDetails patternDetails, KeyCounter[] inputTotal, long craftCount) {
        if (craftCount <= 0) {
            return false;
        }
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern)) {
            // Same gate as the single-craft intake: only crafting / smithing / stonecutting runs here.
            return false;
        }
        if (patternDetails.getInputs().length == 0) {
            return false;
        }
        if (!acceptsPlans()) {
            return false;
        }
        if (!bufferInputs(inputTotal)) {
            // 拒收后不能只清缓存：元件被回滚过、探测是确定性的，重算会给出同一个过大的数（多输入样板逐 key
            // 模拟时各自以为能独占整片空闲），ECO 拿着它再交一次、再被拒，同 tick 内出不去。封本 tick。
            denyParallelSlots(patternDetails);
            return false;
        }
        bookArrival(patternDetails, craftCount);
        return true;
    }

    /**
     * The pattern the parallel-slot estimate is measured against: the last hand-over, or - on a machine
     * that has not been handed anything yet - whatever the queue is waiting on most.
     */
    private IPatternDetails currentDispatchPattern() {
        // Only while it is still what the machine works on: once its jobs are done, sizing the next hand-over by
        // a pattern that is no longer queued could only overshoot.
        if (lastHandedPattern != null
                && (lightLane.contains(lastHandedPattern) || bulkLane.contains(lastHandedPattern))) {
            return lastHandedPattern;
        }
        IPatternDetails best = null;
        long bestCount = 0;
        for (var lane : lanes) {
            for (var entry : lane.entries().entrySet()) {
                if (entry.getValue() > bestCount) {
                    bestCount = entry.getValue();
                    best = entry.getKey();
                }
            }
        }
        return best;
    }

    /** True while buffered jobs are waiting to be assembled (drives the slot locks). */
    public boolean hasBufferedWork() {
        return !queueEmpty();
    }

    /**
     * Moves every pushed amount into the cell buffer, all-or-nothing. Cell capacity is shared across
     * keys (total bytes + bytes per type), so per-key simulation can over-estimate: the insert is
     * verified for real and anything short is rolled back before the push is rejected.
     *
     * <p>Inputs arrive one counter per crafting slot, and a single craft often fills several slots with the
     * same item (a 9:1 compression recipe is nine slots of one thing). The totals are merged per key first:
     * what gets booked is unchanged, but the cells are written - and re-bookkeep - once per key instead of
     * once per slot.</p>
     */
    private boolean bufferInputs(KeyCounter[] inputs) {
        var totals = CellBuffer.totalsByKey(inputs);
        var insertedKeys = new ArrayList<AEKey>();
        var insertedAmounts = new ArrayList<Long>();
        boolean complete = true;

        for (var entry : totals) {
            long amount = entry.getLongValue();
            if (amount <= 0) {
                continue;
            }
            var key = entry.getKey();
            long accepted = cellBuffer.insert(key, amount, Actionable.MODULATE);
            insertedKeys.add(key);
            insertedAmounts.add(accepted);
            if (accepted < amount) {
                complete = false;
                break;
            }
        }

        if (!complete) {
            for (int i = 0; i < insertedKeys.size(); i++) {
                long accepted = insertedAmounts.get(i);
                if (accepted > 0) {
                    cellBuffer.extract(insertedKeys.get(i), accepted, Actionable.MODULATE);
                }
            }
            return false;
        }

        long totalInserted = 0;
        for (long amount : insertedAmounts) {
            totalInserted += amount;
        }
        if (totalInserted <= 0) {
            // A push that carries no material at all must not book a job: the queue has no per-tick ceiling
            // and nothing to assemble, so such a job would sit there and keep the cell slots locked.
            return false;
        }

        for (var counter : inputs) {
            if (counter == null) {
                // 空槽在合并那步已跳过；这里同口径，否则第三方给的空槽会在这里炸。
                continue;
            }
            counter.removeZeros();
        }
        return true;
    }

    // ---- ticking / batch execution ------------------------------------------

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 1, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        long now = currentGameTime();
        if (lastActivityGameTime == 0) {
            // Freshly loaded or just powered: start the idle countdown here instead of reading the world clock
            // as one long silence, which would hand the cells over on the very first tick.
            lastActivityGameTime = now;
        }
        // 开做之前没有任何东西需要提前备料：材料收到即写元件（没有接收侧撺批层）。
        if (queueEmpty() && returns.isEmpty()) {
            return idleTick(now);
        }
        // The output drain runs every tick, independent of batch scheduling: the smooth-return queue
        // keeps feeding the crafting CPU while (and after) the batch executes.
        boolean pendingWork = !returns.isEmpty();
        if (pendingWork) {
            drainOutputs();
        }
        int window = batchIdleTicks();
        // 每一条道各自算自己的窗口：一条道上的到货不会把另一条道的计时拨回去，这正是通道存在的意义。
        boolean lightDue = lightLane.isDue(now, window);
        boolean bulkDue = bulkLane.isDue(now, window);
        // Restated every evaluation so a reason cannot outlive the situation that produced it; a run below
        // overwrites it with whatever it ran into.
        if (queueEmpty()) {
            workState = pendingWork ? BatchWorkState.RETURNING_OUTPUTS : BatchWorkState.IDLE;
        } else if (!lightDue && !bulkDue) {
            workState = BatchWorkState.WAITING_FOR_MATERIAL;
        }
        // A batch starts only while its own lane is genuinely quiet - or when that lane's backlog has waited
        // long enough to be let through regardless. Nothing forces a batch through on a plain timer otherwise.
        boolean worked = false;
        if (lightDue || bulkDue) {
            worked = runLanes(now, lightDue, bulkDue);
        }
        if (worked || pendingWork) {
            idleFlushDone = false;
            lastActivityGameTime = now;
            saveChanges();
        }
        if (!queueEmpty() || !returns.isEmpty()) {
            return modulated(TickRateModulation.IDLE);
        }
        return modulated(TickRateModulation.SLEEP);
    }

    /**
     * Tick body while the machine has nothing queued and nothing left to return: normally this is where it
     * goes back to sleep. It stays awake for {@link #IDLE_FLUSH_TICKS} instead when the cells still hold
     * something, and then hands that material back to the network - see {@link #flushCellsToNetwork}.
     */
    private TickRateModulation idleTick(long now) {
        if (idleFlushDone || !cellBuffer.hasContents()) {
            return modulated(TickRateModulation.SLEEP);
        }
        if (now - lastActivityGameTime < IDLE_FLUSH_TICKS) {
            return modulated(TickRateModulation.IDLE);
        }
        idleFlushDone = true;
        if (flushCellsToNetwork()) {
            saveChanges();
        }
        return modulated(TickRateModulation.SLEEP);
    }

    /**
     * 跑两条通道，总额度 {@link #OPERATIONS_PER_RUN}。
     *
     * <p>两条都就绪时轻载通道先跑、只拿 {@link #LIGHT_OPERATION_SHARE}，其余留给重载通道；只有一条就绪时
     * 它拿满。先轻后重是有意的：小订单短，通常几个操作就清空，剩下的预算正好交给重订单，而不是让大订单把整轮
     * 吃光。</p>
     *
     * @return 是否有任何一条通道真的组出了东西
     */
    private boolean runLanes(long now, boolean lightDue, boolean bulkDue) {
        if (!lightDue && !bulkDue) {
            return false;
        }
        // 计划只在这里补齐一次：两条通道共用一个缓存，按单条道传 live 会在缓存超限时把另一条道的计划
        // 也丢给回收。这一步只读样板数据，是整轮里唯一可以离开服务端线程的部分。
        var live = queuedPatterns();
        plans.prepare(live, live, analyser.pool(), analyser::reportFailure);

        if (lightDue && bulkDue) {
            int lightBudget = OPERATIONS_PER_RUN <= 1
                    ? OPERATIONS_PER_RUN
                    : Math.min(OPERATIONS_PER_RUN - 1, LIGHT_OPERATION_SHARE);
            int used = runLane(lightLane, lightBudget, now);
            // 不用短路：重载通道的这一轮不能因为轻载通道已经跑出东西就被跳过。
            boolean workedBulk = runLane(bulkLane, OPERATIONS_PER_RUN - used, now) > 0;
            return used > 0 || workedBulk;
        }
        if (lightDue) {
            return runLane(lightLane, OPERATIONS_PER_RUN, now) > 0;
        }
        return runLane(bulkLane, OPERATIONS_PER_RUN, now) > 0;
    }

    /**
     * 跑一条通道，最多用掉 {@code budget} 个操作。
     *
     * <p>与原先「整队列跑一轮」的差别只在边界：预算按通道给，遍历只碰这条道的队列，分批分类也记在这条道上。
     * 摊销块与单件慢路径的顺序、以及「一个样板没跑完就轮到下一个」的规则都照旧。Inputs are consumed only
     * through the cells, and produced outputs enter the smooth-return queue instead of hitting the network
     * storage in one burst. Internal chaining is deliberately not performed - see {@link BatchRecipePool}.</p>
     *
     * @return 用掉的操作数
     */
    private int runLane(BatchLane<IPatternDetails> lane, int budget, long now) {
        if (budget <= 0 || lane.isEmpty()) {
            return 0;
        }

        // Snapshot of what the buffer holds, built on first use: it is only consulted to discover candidate
        // variants the pattern never spelled out (a tool that already lost durability). Availability is
        // re-verified per run, so a stale snapshot can never hand out material that is not there.
        KeyCounter buffer = null;

        boolean worked = false;
        long assembled = 0;
        // 本通道这一轮的操作预算。跨样板共享，于是单个样板不能把整轮的额度花光、让后面的样板连一条都开不了头。
        int operations = budget;
        // Set when a run stops for a reason of its own, so that a later pattern which does run cannot erase it.
        boolean stopped = false;
        var it = lane.entries().entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var plan = plans.analyse(entry.getKey(), analyser::reportFailure);
            if (plan == null) {
                // Unanalysable pattern: keep its job queued instead of aborting the rest of the batch.
                workState = BatchWorkState.UNRESOLVABLE_PATTERN;
                continue;
            }
            long remaining = entry.getValue();

            // Amortise in blocks until nothing more can be covered. One block is one variant per slot, so a
            // buffer holding several damage variants is served by several blocks instead of collapsing to the
            // per-craft path: the block count follows the number of variants, never the number of crafts. Each
            // block costs the same however many crafts it carries, which is what keeps a very large order off
            // the per-tick ceiling - one variant covering a billion crafts is one block, hence one operation.
            // The covered size only shrinks across the blocks of one order, so each search may start from the
            // previous answer rather than from the whole remaining count.
            long covered = remaining;
            while (remaining > 1 && operations > 0) {
                long chunk = engine.uniformChunk(plan, remaining, covered);
                if (chunk < 2) {
                    // Not even two crafts share a variant any more: the rest is the per-craft path's work.
                    break;
                }
                if (!engine.amortise(entry.getKey(), plan, chunk)) {
                    // Refused outright - a recipe that disagrees with the pattern, or no power - so the rest
                    // falls through to one craft at a time below.
                    if (remaining > 1) {
                        engine.notePartialFallback(entry.getKey());
                    }
                    break;
                }
                if (!stopped) {
                    workState = BatchWorkState.WORKING;
                }
                worked = true;
                operations--;
                assembled += chunk;
                remaining -= chunk;
                covered = chunk;
            }

            // Whatever the blocks could not cover runs one craft at a time. Usually this is a handful left
            // over from a mixed buffer, not the bulk of the order.
            while (remaining > 0 && operations > 0) {
                if (buffer == null) {
                    buffer = cellBuffer.contents();
                }
                if (!engine.craftOnce(plan, buffer)) {
                    stopped = true;
                    break;
                }
                if (!stopped) {
                    // Only the ticks where nothing stopped report work: a machine that ran one pattern and was
                    // refused the next is better described by the refusal, which is what its owner has to act on.
                    workState = BatchWorkState.WORKING;
                }
                operations--;
                remaining--;
                worked = true;
                assembled++;
            }

            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }

            if (operations <= 0) {
                // Out of budget for this tick. Every still-queued pattern keeps its count, so the next tick
                // picks up exactly where this one stopped rather than redoing or dropping work.
                break;
            }
        }

        // 分批分类记在通道自己身上：整窗探针、短窗与久停复位都跟着这条道走。
        lane.recordRun(assembled, now);
        if (worked) {
            saveChanges();
        }
        return budget - operations;
    }

    /** 两条通道上的全部样板。 */
    private Set<IPatternDetails> queuedPatterns() {
        var all = new HashSet<IPatternDetails>(lightLane.patterns());
        all.addAll(bulkLane.patterns());
        return all;
    }

    /**
     * 交付一轮平滑回送：每个 key 交出它这一轮的额度，网络没收下的部分留到下一 tick。额度与优先通道的算法在
     * {@link SmoothReturnQueue#drain}，这里只提供投递目标（ME 存储 + 「谁正在被索要」）。
     */
    private void drainOutputs() {
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            return;
        }
        var grid = node.getGrid();
        var storage = grid.getStorageService().getInventory();
        // Read once per drain: the per-key check below would otherwise walk every queued plan again.
        var queuedInputs = queuedInputKeysForTick(currentGameTime());
        returnStalled = returns.drain(new SmoothReturnQueue.Target() {
            @Override
            public long insert(AEKey key, long amount) {
                return storage.insert(key, amount, Actionable.MODULATE, actionSource);
            }

            @Override
            public boolean waitingFor(AEKey key) {
                return claimedByNetwork(grid, queuedInputs, key);
            }
        });
    }

    /**
     * Every input key the queued plans declare. Only already-analysed plans are read: the drain runs every
     * tick and must not put pattern analysis back on the server thread.
     */
    private Set<AEKey> queuedInputKeys() {
        var keys = new HashSet<AEKey>();
        for (var lane : lanes) {
            for (var pattern : lane.patterns()) {
                var plan = plans.get(pattern);
                if (plan == null) {
                    continue;
                }
                for (var input : plan.inputs()) {
                    if (input != null) {
                        for (var candidate : input.candidates()) {
                            keys.add(candidate.key());
                        }
                    }
                }
            }
        }
        return keys;
    }

    /** 本 tick 的输入键快照；同 tick 的多次 drain 复用它，跨 tick 重建。 */
    private Set<AEKey> queuedInputKeysForTick(long now) {
        if (queuedInputKeyCacheTick != now) {
            queuedInputKeyCache = queuedInputKeys();
            queuedInputKeyCacheTick = now;
        }
        return queuedInputKeyCache;
    }

    /**
     * Whether the network is after this key right now. {@code ICraftingService.getRequestedAmount} covers the
     * outputs and container items of every job in flight, which is the main case for a machine fed by a
     * crafting CPU; the queued inputs cover what the machine is still going to consume itself. Either way
     * such a key skips the smooth-return trickle - the container a chain recycles above all.
     */
    private boolean claimedByNetwork(IGrid grid, Set<AEKey> queuedInputs, AEKey key) {
        var crafting = grid.getCraftingService();
        if (crafting != null && crafting.getRequestedAmount(key) > 0) {
            return true;
        }
        return queuedInputs.contains(key);
    }


    /**
     * Charges the grid for one assembled run ({@link #ENERGY_PER_RUN}). A whole batch is one execution unit:
     * the parallelism inside it - pattern multipliers, container remainders, further crafts of the same
     * pattern - does not add consumption, so the charge does not scale with the batch size.
     *
     * <p>执行引擎通过回调调它：网格不属于引擎，扣能量的判断与执行得留在方块实体这一侧。</p>
     */
    private boolean consumePower() {
        double wanted = ENERGY_PER_RUN;
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            return false;
        }
        var energy = node.getGrid().getEnergyService();
        if (energy == null) {
            return false;
        }
        if (energy.extractAEPower(wanted, Actionable.SIMULATE, PowerMultiplier.CONFIG) < wanted - 0.01) {
            return false;
        }
        return energy.extractAEPower(wanted, Actionable.MODULATE, PowerMultiplier.CONFIG) > 0;
    }

    /**
     * Pushes a produced key to the ME network; returns the amount that did not fit.
     */
    private long pushToNetwork(AEKey key, long amount) {
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            // Without a grid nothing can be delivered: report the full amount as leftover.
            return amount;
        }
        var storage = node.getGrid().getStorageService().getInventory();
        long inserted = storage.insert(key, amount, Actionable.MODULATE, actionSource);
        return amount - inserted;
    }

    /**
     * 把元件里还留着的东西交还给网络。判断与回写都在 {@link CellBuffer#flushTo}，这里只说明去向：网络不收、
     * 元件也放不回的部分进回送队列，下一轮再试。
     *
     * @return 是否交出了任何东西
     */
    private boolean flushCellsToNetwork() {
        return cellBuffer.flushTo(new CellBuffer.Sink() {
            @Override
            public long push(AEKey key, long amount) {
                return pushToNetwork(key, amount);
            }

            @Override
            public void requeue(AEKey key, long amount) {
                returns.add(key, amount);
            }
        });
    }

    /** Empties the cell buffer back into the ME network (used by the "cancel crafting" action). */
    public void cancelAndReturnContents() {
        // 通道清空时自己的窗口统计一并归零：下一份订单必须重新走一遍完整窗口，不能继承上一份的短窗判定。
        clearLanes();
        long now = currentGameTime();
        lastActivityGameTime = now;
        idleFlushDone = false;
        // Flush the smooth-return queue immediately: after the block is removed (drops path) nothing
        // would tick anymore, and the crafting CPU is still waiting for these outputs. Whatever the network
        // refuses goes back into the cells instead of vanishing - on the drops path those cells leave the
        // machine as items, so the goods stay with the player.
        for (var entry : returns.snapshot().entrySet()) {
            long remaining = entry.getValue();
            if (remaining <= 0) {
                continue;
            }
            long leftover = pushToNetwork(entry.getKey(), remaining);
            // What the network refused goes into the cells; only what neither of them accepts stays queued.
            // Writing back the whole leftover would book the same items twice - once in a cell, once in the
            // queue - and both routes would deliver them.
            long placed = leftover > 0
                    ? cellBuffer.insert(entry.getKey(), leftover, Actionable.MODULATE)
                    : 0;
            long stillQueued = leftover - placed;
            returns.setRemaining(entry.getKey(), stillQueued);
            if (stillQueued > 0) {
                LOGGER.warn("Batch assembler could not return {} x{}: network full and no cell space",
                        entry.getKey(), stillQueued);
            }
        }
        returns.discardEmpty();
        // 元件里剩下的东西走与闲置回灌同一段逻辑：两者要的都是「把缓冲清空交还网络」，没有理由各写一遍。
        flushCellsToNetwork();
        saveChanges();
        alertTicker();
    }

    /** 清空两条通道及其窗口统计。 */
    private void clearLanes() {
        for (var lane : lanes) {
            lane.clear();
        }
        queuedInputKeyCache = Set.of();
        queuedInputKeyCacheTick = -1;
    }

    private void alertTicker() {
        if (tickerAwake) {
            return;
        }
        // 叫醒成功后立刻记上：同一 tick 里后续的到货就不用再各叫一次了。
        this.getMainNode().ifPresent((grid, node) -> tickerAwake = grid.getTickManager().alertDevice(node));
    }

    /** 记下这一 tick 之后 ticker 醒不醒；返回原值，调用点照旧直接 return。 */
    private TickRateModulation modulated(TickRateModulation modulation) {
        tickerAwake = modulation != TickRateModulation.SLEEP;
        return modulation;
    }

    // ---- batch window --------------------------------------------------------

    /** Current game time of the level this machine lives in; 0 before the entity is attached. */
    private long currentGameTime() {
        var level = getLevel();
        return level == null ? 0 : level.getGameTime();
    }

    // ---- worker pool ---------------------------------------------------------

    @Override
    public void setRemoved() {
        // 先交还再销毁节点：super.setRemoved() 会 getMainNode().destroy()，那之后 pushToNetwork 只剩
        // 「进元件」一条路，装不下的部分会掉进不持久化的回送队列。
        super.setRemoved();
        // The pool outlives ticks but not the block entity: leaking it would strand one thread group per
        // broken or unloaded machine.
        analyser.shutdown();
    }

    /** Idle ticks that flush a batch for the currently selected mode. */
    public int batchIdleTicks() {
        return fastBatchMode ? FAST_BATCH_TICKS : STANDARD_BATCH_TICKS;
    }

    // 窗口口径（整窗、短窗、整窗探针、久停复位）随通道走，见 {@link BatchLane#effectiveWindowTicks(int)}。

    public boolean isFastBatchMode() {
        return fastBatchMode;
    }

    /** Switches between the standard (40 tick) and fast (10 tick) batch delay. */
    public void setFastBatchMode(boolean fastBatchMode) {
        this.fastBatchMode = fastBatchMode;
        // The window itself is left alone: it measures the gap since the last accepted input, and changing
        // the threshold must not pretend that material just arrived. Re-arming the ticker is enough, the
        // new threshold is evaluated on the next tick.
        saveChanges();
        alertTicker();
    }

    // ---- inventory host ------------------------------------------------------

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv == diskInv) {
            // Only disk changes alter the recipe pool; cell content changes on every buffered input and
            // must not trigger a network-wide pattern index refresh.
            refreshRecipePool();
            terminalView.invalidate(); // invalidate the pattern access terminal view
        } else {
            cellBuffer.markDirty();
            // A freshly inserted cell brings its own contents along: give the idle flush another chance.
            idleFlushDone = false;
        }
        saveChanges();
        alertTicker();
    }

    // ---- pattern access terminal (PatternContainer) -------------------------

    /**
     * Terminal view shared with the provider (see {@link PatternDiskTerminalView}): the pattern access
     * terminal has to read the disks, never this machine's derived recipe pool.
     */
    private final PatternDiskTerminalView terminalView = PatternDiskApi.terminalView(diskInv,
            this::getGrid, this, this::markTerminalChanged, this::getLevel);

    @Override
    public IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return terminalView.view();
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        // getName() falls back to the block entity's visual item, which is empty for this machine and
        // renders as "Air" in the pattern access terminal - prefer an anvil custom name if present,
        // otherwise the block's display name (matching the pattern provider's terminal behaviour).
        var name = hasCustomName() ? getCustomName() : getBlockState().getBlock().getName();
        return new PatternContainerGroup(
                AEItemKey.of(AEPatternRegistries.ITEM_BATCH_ASSEMBLER.get()),
                name,
                List.of());
    }

    private void markTerminalChanged() {
        // This is reached from paths that write the disks directly, so dropping the view here is what keeps
        // the terminal honest; leaving it to the inventory's own notification makes correctness depend on
        // every write path raising one.
        terminalView.invalidate(); // invalidate the pattern access terminal view
        saveChanges();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        saveChanges();
    }

    @Override
    public boolean isClientSide() {
        return level != null && level.isClientSide();
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    // ---- persistence ---------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        // 刚读档时不知道 ticker 的旧状态是否还成立，放行下一次 alert。
        tickerAwake = false;
        refreshRecipePool();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        for (int i = 0; i < CELL_SLOTS; i++) {
            // getStackInSlot persists the cached cell content into the item before saving.
            tag.put("cell" + i, cellInv.getStackInSlot(i).saveOptional(registries));
        }
        diskInv.writeToNBT(tag, "disks", registries);
        upgrades.writeToNBT(tag, "upgrades", registries);
        tag.putBoolean("fastBatchMode", fastBatchMode);
        tag.putInt("priority", priority);
        // Persist the smooth-return queue so a save/load cannot lose already-produced outputs.
        returns.writeToNbt(tag, registries, "pendingOutputs");
    }

    @Override
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        for (int i = 0; i < CELL_SLOTS; i++) {
            cellInv.setItemDirect(i, ItemStack.parseOptional(registries, tag.getCompound("cell" + i)));
        }
        diskInv.readFromNBT(tag, "disks", registries);
        // readFromNBT writes the slots straight in and raises no change notification, so a terminal view built
        // before this load would keep serving the pre-load contents - an empty list, with nothing left to
        // trigger a rebuild. Invalidating keeps loading symmetric with a slot change.
        terminalView.invalidate(); // invalidate the pattern access terminal view
        upgrades.readFromNBT(tag, "upgrades", registries);
        fastBatchMode = tag.getBoolean("fastBatchMode");
        priority = tag.getInt("priority");
        returns.readFromNbt(tag, registries, "pendingOutputs");
        cellBuffer.markDirty();
        refreshRecipePool();
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        // Anything still buffered belongs to jobs the CPU already accounts for; hand it back before the
        // cells (and therefore their contents) leave the machine. Queued jobs are not the only case: the
        // smooth-return queue may still hold finished outputs (or be stuck on a full network), and those are
        // just as lost if the block goes away without flushing them.
        if (hasBufferedWork() || !returns.isEmpty()) {
            cancelAndReturnContents();
        }
        // Whatever neither the network nor a cell would take is still the player's: it leaves with the block
        // as drops, because after this method the block entity and its queues are gone.
        for (var entry : returns.snapshot().entrySet()) {
            long amount = entry.getValue();
            var key = entry.getKey();
            if (amount <= 0) {
                continue;
            }
            if (!(key instanceof AEItemKey itemKey)) {
                // Fluids and other non-item keys have no item stack to be dropped as.
                LOGGER.warn("Batch assembler leftover {} x{} cannot be dropped as an item", key, amount);
                continue;
            }
            // Split into normal stacks, but stop at a ceiling: a machine holding a very large leftover would
            // otherwise spawn one entity per stack - millions of them - and take the server down with it.
            // Anything past the ceiling is carried in oversized stacks instead, which keeps the material
            // rather than dropping it and yields a handful of entries rather than millions.
            long oversized = DropStacks.add(itemKey, amount, drops, MAX_LEFTOVER_DROP_STACKS);
            if (oversized > 0) {
                LOGGER.warn("Batch assembler leftover {} x{} exceeds {} drop stacks; carrying the rest in "
                                + "oversized stacks so the world is not flooded with entities",
                        key, oversized, MAX_LEFTOVER_DROP_STACKS);
            }
        }
        returns.clear();
        super.addAdditionalDrops(level, pos, drops);
        for (int i = 0; i < CELL_SLOTS; i++) {
            var cell = cellInv.getStackInSlot(i);
            if (!cell.isEmpty()) {
                drops.add(cell);
            }
        }
        for (int i = 0; i < DISK_SLOTS; i++) {
            var disk = diskInv.getStackInSlot(i);
            if (!disk.isEmpty()) {
                drops.add(disk);
            }
        }
        for (int i = 0; i < upgrades.size(); i++) {
            var upgrade = upgrades.getStackInSlot(i);
            if (!upgrade.isEmpty()) {
                drops.add(upgrade);
            }
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        returns.clear();
        for (int i = 0; i < CELL_SLOTS; i++) {
            cellInv.setItemDirect(i, ItemStack.EMPTY);
        }
        diskInv.clear();
        // Clearing the slots notifies per slot, but the view is dropped explicitly as well so
        // correctness does not hinge on that notification surviving future changes.
        terminalView.invalidate();
        upgrades.clear();
        clearLanes();
        cellBuffer.markDirty();
    }
}
