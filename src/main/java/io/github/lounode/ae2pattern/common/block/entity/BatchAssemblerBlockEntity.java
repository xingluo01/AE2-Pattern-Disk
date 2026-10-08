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
 * <p>Two delays are cut on purpose. A window that gathered next to nothing - fewer than eight jobs - is dead
 * time on the next arrival, so the machine then starts after a single quiet tick instead; every
 * {@link #PROBE_EVERY_RUNS} such runs it serves the full window once more, which is how a supply that has
 * turned into a steady stream gets noticed instead of being run push by push forever, and a silence of
 * {@link #IDLE_RESET_WINDOWS} windows wipes the classification so an order arriving after a long pause
 * aggregates exactly as it always did. Runs that do gather a batch always serve the full window, which is
 * what keeps a bulk order accumulating. Keys the network is currently waiting for skip the smooth-return
 * trickle too, instead of arriving over {@link #OUTPUT_RETURN_TICKS} ticks.</p>
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
    /** A run that assembles this many jobs is a real batch: the quiet window did its job, so the machine
     * serves the full window from then on instead of the short one. */
    private static final int LARGE_BATCH_JOBS = 8;
    /** Consecutive small runs after which the machine serves the full window once more. Without that probe a
     * supply that turned into a stream would be run push by push forever: each run catches about one tick of
     * arrivals, so it can never grow into the batch that would end the short window. */
    private static final int PROBE_EVERY_RUNS = 32;
    /** Quiet windows of silence that wipe the batch classification: an order arriving after such a pause is
     * aggregated like it always was, whatever the machine ran before it. */
    private static final int IDLE_RESET_WINDOWS = 8;
    /** Quiet ticks a push needs while the machine is on the short window. */
    private static final int SHORT_WINDOW_TICKS = 1;
    /** Quiet ticks after which an idle machine hands whatever its cells still hold back to the network. */
    private static final int IDLE_FLUSH_TICKS = 10;
    /** Upper bound on what a claimed key hands over in one tick (never less than the smooth rate). A
     * catalyst-sized amount therefore arrives in full on the spot, while a huge batch still never becomes
     * one giant IO burst - the reason the smooth return exists at all. */
    private static final long PRIORITY_RETURN_BURST = 512;
    /**
     * Smooth-return horizon: accumulated outputs are returned to the network over this many ticks
     * (a twentieth of the accumulated total per tick), so a huge batch never produces one giant IO burst.
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
    /** AE charged per assembled job (container remainders and parallels do not change this). */
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
     * 本机的样板优先级（AE2 供应器界面里那个）。
     *
     * <p>两处作用：一是 {@link #getPatternPriority()} 把它交给 AE2 的合成计算——同一产物的多条样板里优先
     * 用优先级高的，这是真正生效的那一环；二是 AE2 自带的优先级界面（本机屏上那枚按钮）读写这个值。</p>
     *
     * <p>与「元件存储优先级」（驱动器决定谁先被写入）不是同一件事，两组数字互不影响。</p>
     */
    private int priority;

    /** @return how many assembly jobs are still queued. */
    public long getQueuedJobCount() {
        long total = 0;
        for (long remaining : queue.values()) {
            total += remaining;
        }
        return total;
    }

    /** @return the quiet ticks still needed before a batch runs, or 0 when one is free to run now. */
    public int getTicksUntilBatch() {
        if (queue.isEmpty()) {
            return 0;
        }
        return (int) Math.max(0, effectiveWindowTicks() - inputGapTicks());
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
        if (!returns.isEmpty() && queue.isEmpty()) {
            // Distinguished from a return in progress because only one of the two has anything its owner can act
            // on, and from the outside the queue draining and the queue stuck look exactly alike.
            return returnStalled ? BatchWorkState.OUTPUT_BLOCKED : BatchWorkState.RETURNING_OUTPUTS;
        }
        if (queue.isEmpty() && returns.isEmpty()) {
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
    private final CellBuffer cellBuffer = new CellBuffer(cellInv, actionSource);

    private final BatchRecipePool recipePool = new BatchRecipePool();
    private final List<IPatternDetails> exposedPatterns = new ArrayList<>();

    /** Pushed jobs still to assemble, keyed by pattern with the remaining execution count. */
    private final Map<IPatternDetails, Long> queue = new LinkedHashMap<>();

    /**
     * Upper bound on the parallel slots advertised to callers. Unlimited by default: the machine's own ceiling
     * is not a real constraint, so it must not become the limit a pack runs into. What actually bounds one
     * hand-over is the cell buffer's shared free space (measured in {@link #availableParallelSlotsLong()}), and
     * past that what the CPU can put in its inventory, its material and its energy - all of which the CPU already
     * clamps itself. Reporting the type's own maximum therefore hands the limit to the factors that exist rather
     * than to a constant here.
     *
     * <p>A {@code long} rather than an {@code int}: the buffer's room is not bounded by {@code Integer.MAX_VALUE},
     * and an int ceiling made the long form of the estimate pointless in two ways - it capped the answer after the
     * fact, and the probe below used it as a multiplier as well, so on a cell that answers a probe by echoing it
     * back - AE2's own cells with a void upgrade do exactly that - the advertised number came out around
     * {@code Integer.MAX_VALUE / slots} instead.</p>
     *
     * <p>Callers whose contract names an int still get their ceiling from {@link #availableParallelSlots()};
     * NEO ECO's parallel dispatch is the one that does.</p>
     *
     * <p>Overridable with {@code -Dae2pattern.maxParallelSlots=N} to bring back a ceiling. {@code N} is parsed as
     * in {@code Long.getLong} - same {@code 0x} and leading-zero-octal acceptance as {@code Integer.getInteger} -
     * and a malformed value falls back to unlimited, which would quietly run the other group of a comparison, so
     * check the effective value logged at class load (see {@link #logEffectiveTuning}). A value at or below zero
     * becomes 1: one craft per hand-over, meant only for the lower end of a comparison. The default has to stay a
     * primitive {@code long} literal: the boxed {@code getLong(String, Long)} overload returns null for a
     * malformed value, and unboxing that would throw.</p>
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
        if (slots == null && returns == null && operations == null && dropStacks == null) {
            return;
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Batch assembler tuning overridden: maxParallelSlots={} (requested {}), "
                            + "outputReturnTicks={} (requested {}), operationsPerRun={} (requested {}), "
                            + "maxLeftoverDropStacks={} (requested {})",
                    MAX_ADVERTISED_PARALLEL_SLOTS, slots, OUTPUT_RETURN_TICKS, returns,
                    OPERATIONS_PER_RUN, operations, MAX_LEFTOVER_DROP_STACKS, dropStacks);
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

    /** Game time of the last accepted input push. The batch window is the gap measured from here, so
     * material that keeps arriving simply keeps the window open. */
    private long lastInputGameTime;
    /** Game time of the last thing the machine actually did (accepted a push, ran a batch, returned
     * outputs). The idle flush counts its quiet ticks from here. */
    private long lastActivityGameTime;
    /** How many jobs the last run assembled. 0 means the machine has not produced anything yet - it never
     * ran, or its last run assembled nothing - which keeps the full window: the first productive run is what
     * shows whether the supply is a steady stream or a slow trickle. */
    private long lastRunJobs;
    /** Consecutive runs that came out small; every {@link #PROBE_EVERY_RUNS} of them the full window is served
     * once more to re-measure the supply. */
    private int shortRunStreak;
    /** Whether this idle period already tried the cell flush - the network may simply be full, and retrying
     * that every tick would be pointless. Cleared as soon as the machine does anything again. */
    private boolean idleFlushDone;
    /** Fast batch mode flushes after 10 quiet ticks instead of the standard 40. */
    private boolean fastBatchMode = false;
    /** 分析线程池（速度卡定尺寸）与失败上报都在 {@link BatchPatternAnalyser} 里，这里只持有它。 */
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
        if (!acceptsPlans() || !bufferInputs(inputHolder)) {
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
        queue.merge(patternDetails, jobs, Long::sum);
        lastHandedPattern = patternDetails;
        long now = currentGameTime();
        // A pause this long means the last batch is history: whatever it looked like - a small run that put the
        // machine on the short window, say - must not decide how a fresh order is handled. Clearing the
        // classification lets that order aggregate exactly as it did before the short window existed.
        if (lastInputGameTime != 0 && now - lastInputGameTime >= (long) batchIdleTicks() * IDLE_RESET_WINDOWS) {
            lastRunJobs = 0;
            shortRunStreak = 0;
        }
        // Every arrival restarts the window: the machine waits for the arrivals to actually stop.
        lastInputGameTime = now;
        idleFlushDone = false;
        lastActivityGameTime = now;
        alertTicker();
        saveChanges();
    }

    @Override
    public boolean acceptsPlans() {
        // Whether the buffer can actually take the material is decided by bufferInputs; require at
        // least one usable cell so jobs are never accepted without a place to store the inputs.
        return cellBuffer.hasAnyCell();
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
     * <p>返回 {@code long}：缓冲的空间不受 {@code Integer.MAX_VALUE} 约束，够大的元件能装下 int 说不出的
     * 份数。契约指名 int 的调用方走 {@link #availableParallelSlots()}，那里会夹住。</p>
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
        return patternDetails == null ? 0L : parallelSlots.estimate(patternDetails, currentGameTime());
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
     * {@link #availableParallelSlotsLong()} clamped to {@code int}, for contracts that name the type that way.
     *
     * <p>NEO ECO's parallel dispatch states its slot count as an {@code int}, so that path cannot advertise more
     * than {@link Integer#MAX_VALUE} crafts per hand-over however much room the cells hold. That ceiling belongs
     * to the protocol, not to this machine: the long form reports what the buffer actually has, and how many
     * crafts fit in one tick no longer depends on this number at all - the amortised path measures a hand-over
     * in blocks rather than in crafts.</p>
     */
    public int availableParallelSlots() {
        return (int) Math.min(Integer.MAX_VALUE, availableParallelSlotsLong());
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
        if (!acceptsPlans() || !bufferInputs(inputTotal)) {
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
        if (lastHandedPattern != null && queue.containsKey(lastHandedPattern)) {
            return lastHandedPattern;
        }
        IPatternDetails best = null;
        long bestCount = 0;
        for (var entry : queue.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /** True while buffered jobs are waiting to be assembled (drives the slot locks). */
    public boolean hasBufferedWork() {
        return queuedJobs() > 0;
    }

    private long queuedJobs() {
        long total = 0;
        for (var count : queue.values()) {
            total += count;
        }
        return total;
    }

    /**
     * Moves every pushed amount into the cell buffer, all-or-nothing. Cell capacity is shared across
     * keys (total bytes + bytes per type), so per-key simulation can over-estimate: the insert is
     * verified for real and anything short is rolled back before the push is rejected.
     */
    private boolean bufferInputs(KeyCounter[] inputs) {
        var insertedKeys = new ArrayList<AEKey>();
        var insertedAmounts = new ArrayList<Long>();
        boolean complete = true;

        for (var counter : inputs) {
            for (var entry : counter) {
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
        if (queue.isEmpty() && returns.isEmpty()) {
            return idleTick(now);
        }
        // The output drain runs every tick, independent of batch scheduling: the smooth-return queue
        // keeps feeding the crafting CPU while (and after) the batch executes.
        boolean pendingWork = !returns.isEmpty();
        if (pendingWork) {
            drainOutputs();
        }
        // Restated every evaluation so a reason cannot outlive the situation that produced it; a run below
        // overwrites it with whatever it ran into.
        if (queue.isEmpty()) {
            workState = pendingWork ? BatchWorkState.RETURNING_OUTPUTS : BatchWorkState.IDLE;
        } else if (inputGapTicks() < effectiveWindowTicks()) {
            workState = BatchWorkState.WAITING_FOR_MATERIAL;
        }
        // A batch starts only while the material is genuinely quiet: the window is the gap since the last
        // accepted push, so as long as material keeps arriving the machine keeps waiting. Nothing forces
        // a batch through on a timer.
        boolean worked = false;
        // Inside the short-window case one quiet tick is enough: the last batch gathered next to nothing, so
        // holding this one back for a full window would only be dead time.
        if (!queue.isEmpty() && inputGapTicks() >= effectiveWindowTicks()) {
            worked = runBatch();
        }
        if (worked || pendingWork) {
            idleFlushDone = false;
            lastActivityGameTime = now;
            saveChanges();
        }
        if (!queue.isEmpty() || !returns.isEmpty()) {
            return TickRateModulation.IDLE;
        }
        return TickRateModulation.SLEEP;
    }

    /**
     * Tick body while the machine has nothing queued and nothing left to return: normally this is where it
     * goes back to sleep. It stays awake for {@link #IDLE_FLUSH_TICKS} instead when the cells still hold
     * something, and then hands that material back to the network - see {@link #flushCellsToNetwork}.
     */
    private TickRateModulation idleTick(long now) {
        if (idleFlushDone || !cellBuffer.hasContents()) {
            return TickRateModulation.SLEEP;
        }
        if (now - lastActivityGameTime < IDLE_FLUSH_TICKS) {
            return TickRateModulation.IDLE;
        }
        idleFlushDone = true;
        if (flushCellsToNetwork()) {
            saveChanges();
        }
        return TickRateModulation.SLEEP;
    }

    /**
     * Assembles the whole queue in one pass, with no per-tick ceiling: a batch runs until the queued jobs
     * are done or the cell buffer runs out of matching material. Inputs are consumed only through the
     * cells, and produced outputs enter the smooth-return queue instead of hitting the network storage in
     * one burst. Internal chaining is deliberately not performed - see {@link BatchRecipePool}.
     */
    private boolean runBatch() {
        // Analyse patterns the machine has not seen before. That step only reads immutable pattern data,
        // so it is the one part of a run that may leave the server thread.
        plans.prepare(queue.keySet(), queue.keySet(), analyser.pool(), analyser::reportFailure);

        // Snapshot of what the buffer holds, built on first use: it is only consulted to discover candidate
        // variants the pattern never spelled out (a tool that already lost durability). Availability is
        // re-verified per run, so a stale snapshot can never hand out material that is not there.
        KeyCounter buffer = null;

        boolean worked = false;
        long assembled = 0;
        // Operations used up in this run, against OPERATIONS_PER_RUN. Shared across the whole queue, so one
        // pattern cannot spend the tick's budget and leave the rest to start their own.
        int operations = OPERATIONS_PER_RUN;
        // Set when a run stops for a reason of its own, so that a later pattern which does run cannot erase it.
        boolean stopped = false;
        var it = queue.entrySet().iterator();
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

        lastRunJobs = assembled;
        if (assembled > 0 && assembled < LARGE_BATCH_JOBS) {
            // A small run: the window gathered next to nothing, so the next batch is not made to wait for it.
            // Saturated rather than allowed to wrap, so the streak cannot turn negative and stop the short
            // window from ever coming back.
            if (shortRunStreak < Integer.MAX_VALUE) {
                shortRunStreak++;
            }
        } else {
            // A real batch (or a run that assembled nothing): the queue is not starved of arrivals, or there
            // is nothing to gather - either way the full window comes back.
            shortRunStreak = 0;
        }
        if (worked) {
            saveChanges();
        }
        return worked;
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
        var queuedInputs = queuedInputKeys();
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
        for (var pattern : queue.keySet()) {
            var plan = plans.get(pattern);
            if (plan == null) {
                continue;
            }
            for (var input : plan.inputs()) {
                if (input != null) {
                    keys.addAll(input.candidates());
                }
            }
        }
        return keys;
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
        queue.clear();
        // Reset the window: the queue is gone, so the next push must go through a fresh batch window.
        long now = currentGameTime();
        lastInputGameTime = now;
        lastActivityGameTime = now;
        lastRunJobs = 0;
        shortRunStreak = 0;
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

    private void alertTicker() {
        this.getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    // ---- batch window --------------------------------------------------------

    /** Current game time of the level this machine lives in; 0 before the entity is attached. */
    private long currentGameTime() {
        var level = getLevel();
        return level == null ? 0 : level.getGameTime();
    }

    /**
     * Ticks since the last accepted input push - the real gap between two deliveries of material.
     *
     * <p>Measured in game time instead of counting tick callbacks: the grid tick manager may skip ticks,
     * and it reports a whole sleep gap on the first call after waking. Counting callbacks quietly
     * stretches the window in both cases.</p>
     */
    private long inputGapTicks() {
        var level = getLevel();
        if (level == null) {
            return 0;
        }
        return Math.max(0, level.getGameTime() - lastInputGameTime);
    }

    // ---- worker pool ---------------------------------------------------------

    @Override
    public void setRemoved() {
        super.setRemoved();
        // The pool outlives ticks but not the block entity: leaking it would strand one thread group per
        // broken or unloaded machine.
        analyser.shutdown();
    }

    /** Idle ticks that flush a batch for the currently selected mode. */
    public int batchIdleTicks() {
        return fastBatchMode ? FAST_BATCH_TICKS : STANDARD_BATCH_TICKS;
    }

    /**
     * The quiet window that actually applies right now. Runs that gathered a batch - and a machine that has
     * not run yet - serve the window of the selected mode, which is what makes a bulk order accumulate.
     * Small runs serve a single quiet tick instead, with the full window coming back for one round every
     * {@link #PROBE_EVERY_RUNS} small runs so a supply that turned into a stream is noticed. Progress and
     * countdown displays ask this one, so they never promise a wait the machine is not going to serve.
     */
    private long effectiveWindowTicks() {
        if (shortRunStreak > 0 && shortRunStreak % PROBE_EVERY_RUNS == 0) {
            return batchIdleTicks();
        }
        return lastRunJobs > 0 && lastRunJobs < LARGE_BATCH_JOBS
                ? SHORT_WINDOW_TICKS
                : batchIdleTicks();
    }

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
        queue.clear();
        cellBuffer.markDirty();
    }
}
