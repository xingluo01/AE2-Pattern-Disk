package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
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
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.blockentity.inventory.AppEngCellInventory;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.filter.IAEItemFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTerminalView;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.logic.BatchRecipePool;
import io.github.lounode.ae2pattern.common.logic.PatternPlan;

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
        IPatternDiskHost, PatternContainer {

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
    /** Smooth-return horizon: accumulated outputs are returned to the network over this many ticks
     * (5% of the accumulated total per tick), so a huge batch never produces one giant IO burst. */
    private static final int OUTPUT_RETURN_TICKS = 20;
    /** Upper bound on cached execution plans. Plans are only a speed-up, so a machine fed an endless
     * variety of pushed patterns drops the whole cache instead of growing without limit. */
    private static final int MAX_CACHED_PLANS = 1024;
    /** AE charged per assembled job (container remainders and parallels do not change this). */
    private static final double ENERGY_PER_RUN = 10.0;

    /**
     * What the machine is doing, or why it is not doing it.
     *
     * <p>Three different situations all leave {@link #assembleOnce} returning {@code false} without saying
     * which: the pattern could not be resolved against the buffer, the buffer could not supply the material,
     * or the grid could not pay for the run. From outside the machine they were indistinguishable, so a
     * machine that refuses to work looked exactly like one waiting on a delivery - and no display could tell
     * the player which was in front of them.</p>
     */
    public enum WorkState {
        /** The machine is not on the grid, so nothing it reports about itself is current. */
        OFFLINE,
        /** No storage cell to work out of, so the crafting CPU never hands it anything. */
        NO_BUFFER,
        /** The disks hold no pattern this machine can run, so a job could never be matched to one. */
        NO_PATTERN,
        /** Nothing queued and nothing left to return. */
        IDLE,
        /** Jobs are queued, still inside the quiet window that closes before a batch runs. */
        WAITING_FOR_MATERIAL,
        /** A queued pattern produced no execution plan, so its job can never run. */
        UNRESOLVABLE_PATTERN,
        /** The cell buffer could not supply the inputs for the pattern being run. */
        INPUTS_UNAVAILABLE,
        /** The grid could not pay for the run. */
        NO_POWER,
        /** A container remainder could not be worked out, so the run was rolled back before producing anything. */
        REMAINDER_FAILED,
        /** The queue is done; produced outputs are still returning to the network. */
        RETURNING_OUTPUTS,
        /** Outputs are waiting but the network is taking none of them, so they will not drain on their own. */
        OUTPUT_BLOCKED,
        /** A run completed on the last attempt. */
        WORKING
    }

    private WorkState workState = WorkState.IDLE;

    /** Client-side mirror of the ME node state; synced through the block entity stream. */
    private boolean isActive = false;

    /** Set when a return attempt found the network unwilling to take what was waiting. */
    private boolean returnStalled;

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
    public WorkState getWorkState() {
        // The structural reasons are worked out here rather than recorded while ticking, because ticking only
        // runs while the machine is active: a machine that is offline, has no cell to work from, or has
        // nothing executable on its disks would otherwise keep reporting whatever it was last doing. That
        // staleness is also what made an offline machine come out as "no power" - the same node check feeds
        // both, so it has to be asked here first.
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            return WorkState.OFFLINE;
        }
        if (!acceptsPlans()) {
            return WorkState.NO_BUFFER;
        }
        if (exposedPatterns.isEmpty()) {
            return WorkState.NO_PATTERN;
        }
        if (!pendingOutputs.isEmpty() && queue.isEmpty()) {
            // Distinguished from a return in progress because only one of the two has anything its owner can act
            // on, and from the outside the queue draining and the queue stuck look exactly alike.
            return returnStalled ? WorkState.OUTPUT_BLOCKED : WorkState.RETURNING_OUTPUTS;
        }
        if (queue.isEmpty() && pendingOutputs.isEmpty()) {
            return WorkState.IDLE;
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
    private final StorageCell[] cells = new StorageCell[CELL_SLOTS];
    private final AppEngInternalInventory diskInv = new AppEngInternalInventory(this, DISK_SLOTS);
    private final IUpgradeInventory upgrades;
    private final IActionSource actionSource = new MachineSource(this);

    private final BatchRecipePool recipePool = new BatchRecipePool();
    private final List<IPatternDetails> exposedPatterns = new ArrayList<>();

    /** Pushed jobs still to assemble, keyed by pattern with the remaining execution count. */
    private final Map<IPatternDetails, Long> queue = new LinkedHashMap<>();

    /** Execution plans of the queued patterns, shared between the analysis workers and the tick thread. */
    private final Map<IPatternDetails, PatternPlan> planCache = new ConcurrentHashMap<>();

    /** Produced outputs waiting for their smooth return to the ME network: AEKey ->
     * long[]{remainingToReturn, accumulatedTotal}. The per-tick return rate is total/20 (min 1),
     * so a batch's outputs reach the CPU over roughly {@link #OUTPUT_RETURN_TICKS} ticks. */
    private final Map<AEKey, long[]> pendingOutputs = new LinkedHashMap<>();

    private boolean cellsDirty = true;
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
    /** Worker threads used to analyse newly queued patterns in parallel; null while there are none. */
    private ExecutorService workers;
    private int workerCount;

    public BatchAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_BATCH_ASSEMBLER.get(), pos, blockState);

        this.upgrades = UpgradeInventories.forMachine(AEPatternRegistries.BLOCK_BATCH_ASSEMBLER.get(),
                MAX_SPEED_CARDS, this::onUpgradesChanged);

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
        if (workerCount > 0
                && (1 << upgrades.getInstalledUpgrades(AEItems.SPEED_CARD)) != workerCount) {
            shutdownWorkers();
        }
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

    /**
     * Rebuilds the cached {@link StorageCell} for every slot. The cached handler points at the live
     * {@link ItemStack} in the slot; it therefore has to be dropped whenever a cell enters or leaves a
     * slot, otherwise it would keep writing into a detached stack.
     */
    private void refreshCells() {
        for (int i = 0; i < CELL_SLOTS; i++) {
            cells[i] = null;
            cellInv.setHandler(i, null);
            var stack = cellInv.getStackInSlot(i); // also persists the previous cell content
            if (stack.isEmpty()) {
                continue;
            }
            var cell = StorageCells.getCellInventory(stack, null);
            if (cell != null) {
                cellInv.setHandler(i, cell);
                cells[i] = cell;
            }
        }
        cellsDirty = false;
    }

    private StorageCell[] resolvedCells() {
        if (cellsDirty) {
            refreshCells();
        }
        return cells;
    }

    /** Simulated/real insert across every cell slot; returns how much was accepted. */
    private long insertIntoCells(AEKey key, long amount, Actionable mode) {
        long remaining = amount;
        for (var cell : resolvedCells()) {
            if (cell == null || remaining <= 0) {
                continue;
            }
            long inserted = cell.insert(key, remaining, mode, actionSource);
            remaining -= inserted;
        }
        return amount - remaining;
    }

    /** Simulated/real extract across every cell slot; returns how much was taken. */
    private long extractFromCells(AEKey key, long amount, Actionable mode) {
        long remaining = amount;
        for (var cell : resolvedCells()) {
            if (cell == null || remaining <= 0) {
                continue;
            }
            long extracted = cell.extract(key, remaining, mode, actionSource);
            remaining -= extracted;
        }
        return amount - remaining;
    }

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
        planCache.clear();

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
        // Each push books exactly one job: the CPU delivers the full order one push at a time, so the
        // job volume must never be scaled here.
        queue.merge(patternDetails, 1L, Long::sum);
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
        return true;
    }

    @Override
    public boolean acceptsPlans() {
        // Whether the buffer can actually take the material is decided by bufferInputs; require at
        // least one usable cell so jobs are never accepted without a place to store the inputs.
        for (var cell : resolvedCells()) {
            if (cell != null) {
                return true;
            }
        }
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
                long accepted = insertIntoCells(key, amount, Actionable.MODULATE);
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
                    extractFromCells(insertedKeys.get(i), accepted, Actionable.MODULATE);
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
        if (queue.isEmpty() && pendingOutputs.isEmpty()) {
            return idleTick(now);
        }
        // The output drain runs every tick, independent of batch scheduling: the smooth-return queue
        // keeps feeding the crafting CPU while (and after) the batch executes.
        boolean pendingWork = !pendingOutputs.isEmpty();
        if (pendingWork) {
            drainOutputs();
        }
        // Restated every evaluation so a reason cannot outlive the situation that produced it; a run below
        // overwrites it with whatever it ran into.
        if (queue.isEmpty()) {
            workState = pendingWork ? WorkState.RETURNING_OUTPUTS : WorkState.IDLE;
        } else if (inputGapTicks() < effectiveWindowTicks()) {
            workState = WorkState.WAITING_FOR_MATERIAL;
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
        if (!queue.isEmpty() || !pendingOutputs.isEmpty()) {
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
        if (idleFlushDone || !hasCellContents()) {
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
        preparePlans(queue.keySet());

        // Snapshot of what the buffer holds, built on first use: it is only consulted to discover candidate
        // variants the pattern never spelled out (a tool that already lost durability). Availability is
        // re-verified per run, so a stale snapshot can never hand out material that is not there.
        KeyCounter buffer = null;

        boolean worked = false;
        int assembled = 0;
        // Set when a run stops for a reason of its own, so that a later pattern which does run cannot erase it.
        boolean stopped = false;
        var it = queue.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var plan = planFor(entry.getKey());
            if (plan == null) {
                // Unanalysable pattern: keep its job queued instead of aborting the rest of the batch.
                workState = WorkState.UNRESOLVABLE_PATTERN;
                continue;
            }
            long remaining = entry.getValue();

            while (remaining > 0) {
                if (buffer == null) {
                    buffer = cellContents();
                }
                if (!assembleOnce(plan, buffer)) {
                    stopped = true;
                    break;
                }
                if (!stopped) {
                    // Only the ticks where nothing stopped report work: a machine that ran one pattern and was
                    // refused the next is better described by the refusal, which is what its owner has to act on.
                    workState = WorkState.WORKING;
                }
                remaining--;
                worked = true;
                assembled++;
            }

            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }

        lastRunJobs = assembled;
        if (assembled > 0 && assembled < LARGE_BATCH_JOBS) {
            // A small run: the window gathered next to nothing, so the next batch is not made to wait for it.
            // Saturated rather than allowed to wrap: the probe keys off this counter, and a negative value
            // would stop it from ever firing again.
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
     * Executes one pattern from the cell buffer: inputs are consumed first, then the whole output set
     * (main outputs + container remainders) enters the smooth-return queue. Inputs are rolled back, and
     * the run deferred, only when the machine cannot run at all (no power) or when the buffer cannot
     * supply the material - never because the network is full, since outputs queue up instead. Internal
     * chaining is deliberately not performed - see {@link BatchRecipePool}.
     */
    private boolean assembleOnce(PatternPlan plan, KeyCounter buffer) {
        var resolved = resolveInputs(plan, buffer);
        if (resolved == null) {
            workState = WorkState.INPUTS_UNAVAILABLE;
            return false;
        }
        var consumed = consumeInputs(plan, resolved);
        if (consumed == null) {
            workState = WorkState.INPUTS_UNAVAILABLE;
            return false;
        }

        // Collect every produced key (main outputs + container remainders) before touching the network.
        // The main outputs arrive pre-aggregated from the plan, so they are not walked again per run.
        var outputs = new LinkedHashMap<AEKey, Long>(plan.baseOutputs());
        var inputs = plan.inputs();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            if (input == null || input.isEmpty()) {
                continue;
            }
            // Container remainders are computed against the variant actually consumed (the inputs may
            // accept substitutes whose containers differ).
            var usedKey = consumed.usedByInput().get(i);
            if (usedKey == null) {
                continue;
            }
            try {
                // Container remainders are derived from the variant actually consumed - buckets, bottles
                // and other containers must be handed back, otherwise they vanish and the crafting CPU
                // keeps waiting for its expected container items forever. The same call is what returns a
                // worn tool to the network: AE2 derives the remainder by re-running the recipe for the
                // variant that was actually consumed.
                var remaining = input.remainingFor(usedKey);
                long consumedCount = input.multiplier();
                if (remaining != null && consumedCount > 0) {
                    // AE2 books one remaining item per occupied slot.
                    outputs.merge(remaining, consumedCount, Long::sum);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to compute container remainder for pattern slot {} at {}; rolling back",
                        i, getBlockPos(), e);
                rollbackInputs(consumed.byKey());
                workState = WorkState.REMAINDER_FAILED;
                return false;
            }
        }

        // Defer (keeping the inputs in the cell buffer) when the machine cannot run at all.
        if (!consumePower()) {
            rollbackInputs(consumed.byKey());
            workState = WorkState.NO_POWER;
            return false;
        }
        // Outputs enter the smooth-return queue instead of hitting the network storage in one burst:
        // drainOutputs() returns 1/20 of the accumulated total per tick.
        for (var entry : outputs.entrySet()) {
            var slot = pendingOutputs.computeIfAbsent(entry.getKey(), k -> new long[2]);
            slot[0] += entry.getValue();
            slot[1] += entry.getValue();
        }
        return true;
    }

    /**
     * Smooth-return: every tick, up to 1/20 of each key's accumulated total is inserted into the ME
     * network, so a huge batch's outputs reach the CPU over roughly {@link #OUTPUT_RETURN_TICKS}
     * ticks instead of one giant IO burst. Network-full leftovers stay queued for the next tick.
     */
    private void drainOutputs() {
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            return;
        }
        returnStalled = false;
        var grid = node.getGrid();
        var storage = grid.getStorageService().getInventory();
        // Read once per drain: the per-key check below would otherwise walk every queued plan again.
        var queuedInputs = queuedInputKeys();
        var it = pendingOutputs.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var slot = entry.getValue();
            long rate = Math.max(1, (slot[1] + OUTPUT_RETURN_TICKS - 1) / OUTPUT_RETURN_TICKS);
            long amount = Math.min(slot[0], rate);
            if (claimedByNetwork(grid, queuedInputs, entry.getKey())) {
                // Wanted right now: hand the whole claim over at once instead of trickling it over the horizon.
                // A recycled container arriving one tick at a time is exactly what keeps a planning chain
                // from starting its next wave.
                amount = Math.min(slot[0], Math.max(rate, PRIORITY_RETURN_BURST));
            }
            long inserted = storage.insert(entry.getKey(), amount, Actionable.MODULATE, actionSource);
            slot[0] -= inserted;
            if (inserted == 0) {
                returnStalled = true;
            }
            if (slot[0] <= 0) {
                it.remove();
            }
        }
    }

    /**
     * Every input key the queued plans declare. Only already-analysed plans are read: the drain runs every
     * tick and must not put pattern analysis back on the server thread.
     */
    private Set<AEKey> queuedInputKeys() {
        var keys = new HashSet<AEKey>();
        for (var pattern : queue.keySet()) {
            var plan = planCache.get(pattern);
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
     * Charges the grid for one assembled job ({@link #ENERGY_PER_RUN}). Parallel work inside that job -
     * pattern multipliers, container remainders - does not add extra consumption.
     */
    private boolean consumePower() {
        var node = getMainNode().getNode();
        if (node == null || node.getGrid() == null) {
            return false;
        }
        var energy = node.getGrid().getEnergyService();
        if (energy == null) {
            return false;
        }
        if (energy.extractAEPower(ENERGY_PER_RUN, Actionable.SIMULATE,
                PowerMultiplier.CONFIG) < ENERGY_PER_RUN - 0.01) {
            return false;
        }
        return energy.extractAEPower(ENERGY_PER_RUN, Actionable.MODULATE, PowerMultiplier.CONFIG) > 0;
    }

    /**
     * Snapshot of the keys the cell buffer currently holds. Only used to discover candidate variants;
     * every candidate is re-verified against the live buffer before it is consumed.
     */
    private KeyCounter cellContents() {
        var contents = new KeyCounter();
        for (var cell : resolvedCells()) {
            if (cell != null) {
                cell.getAvailableStacks(contents);
            }
        }
        return contents;
    }

    /**
     * Resolves the variant to consume for every input slot, in AE2's own order: the variants the pattern
     * spells out first, then any same-item variant the pattern accepts. That second step is what lets a
     * slot take a tool that already lost durability - AE2's crafting patterns accept such a variant through
     * {@code IInput.isValid} as long as the pattern was encoded with substitution enabled, and a pattern
     * encoded without it rejects everything that is not an exact match.
     *
     * @return the key per input slot ({@code null} for holes), or {@code null} when a slot cannot be filled
     */
    private List<AEKey> resolveInputs(PatternPlan plan, KeyCounter buffer) {
        var level = getLevel();
        var inputs = plan.inputs();
        var resolved = new ArrayList<AEKey>(inputs.size());
        for (var input : inputs) {
            if (input == null) {
                resolved.add(null);
                continue;
            }
            var key = resolveVariant(input, level, buffer);
            if (key == null) {
                return null;
            }
            resolved.add(key);
        }
        return resolved;
    }

    private AEKey resolveVariant(PatternPlan.Input input, Level level, KeyCounter buffer) {
        long needed = input.multiplier();
        if (needed < 0) {
            // Malformed slot: a negative requirement must never reach the cells as a negative extract.
            return null;
        }
        for (var candidate : input.candidates()) {
            if (extractFromCells(candidate, needed, Actionable.SIMULATE) >= needed) {
                return candidate;
            }
        }
        if (level == null) {
            return null;
        }
        // Nothing the pattern listed is available: look for a same-item variant sitting in the buffer. The
        // pattern itself decides which variants it accepts, so this cannot widen a pattern's contract.
        for (var candidate : input.candidates()) {
            if (!(candidate instanceof AEItemKey itemCandidate)) {
                continue;
            }
            for (var variant : buffer.findFuzzy(itemCandidate, FuzzyMode.IGNORE_ALL)) {
                var key = variant.getKey();
                if (key.equals(candidate) || !input.accepts(key, level)) {
                    continue;
                }
                if (extractFromCells(key, needed, Actionable.SIMULATE) >= needed) {
                    return key;
                }
            }
        }
        return null;
    }

    /**
     * Consumes the resolved variants for one run. Returns the per-key totals (for rollback) plus the
     * variant consumed per input slot, or {@code null} when a slot turned out to be empty in the meantime
     * - in that case everything this run already took is put back.
     */
    private Consumption consumeInputs(PatternPlan plan, List<AEKey> resolved) {
        var byKey = new LinkedHashMap<AEKey, Long>();
        var inputs = plan.inputs();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            var usedKey = resolved.get(i);
            if (input == null || usedKey == null) {
                continue;
            }
            long needed = input.multiplier();
            if (extractFromCells(usedKey, needed, Actionable.MODULATE) < needed) {
                // The simulation and the real extraction disagreed (something else emptied the slot in
                // between): hand back what this run took and leave the job queued.
                rollbackInputs(byKey);
                return null;
            }
            byKey.merge(usedKey, needed, Long::sum);
        }
        return new Consumption(byKey, resolved);
    }

    /** Per-run consumption record: {@code byKey} drives rollbacks, {@code usedByInput} drives container remainders. */
    private record Consumption(LinkedHashMap<AEKey, Long> byKey, List<AEKey> usedByInput) {
    }

    /** Puts rolled-back inputs (and only those) back into the cell buffer they came from. */
    private void rollbackInputs(LinkedHashMap<AEKey, Long> consumed) {
        for (var entry : consumed.entrySet()) {
            insertIntoCells(entry.getKey(), entry.getValue(), Actionable.MODULATE);
        }
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

    /** @return whether any cell slot currently holds something. */
    private boolean hasCellContents() {
        for (var cell : resolvedCells()) {
            if (cell == null) {
                continue;
            }
            var contents = new KeyCounter();
            cell.getAvailableStacks(contents);
            if (!contents.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hands whatever the cells still hold back to the ME network. Material that is only sitting in them is
     * locked up: the cells refuse extraction while work is buffered, and once the machine falls idle nothing
     * else would ever take it out again. Nothing is dropped - what the network will not take goes back into
     * the cells and waits for the next attempt.
     *
     * @return whether anything was handed over
     */
    private boolean flushCellsToNetwork() {
        boolean any = false;
        for (var cell : resolvedCells()) {
            if (cell == null) {
                continue;
            }
            var contents = new KeyCounter();
            cell.getAvailableStacks(contents);
            for (var entry : contents) {
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                long taken = cell.extract(entry.getKey(), amount, Actionable.MODULATE, actionSource);
                if (taken <= 0) {
                    continue;
                }
                any = true;
                long leftover = pushToNetwork(entry.getKey(), taken);
                if (leftover > 0) {
                    // The cell just handed this much out, so it goes back there rather than to any cell - the
                    // space is guaranteed and nothing can be lost in between. Third-party cells are not bound
                    // by that assumption, so a shortfall is booked for another attempt instead of vanishing.
                    long back = cell.insert(entry.getKey(), leftover, Actionable.MODULATE, actionSource);
                    requeueOutput(entry.getKey(), leftover - back);
                }
            }
        }
        return any;
    }

    /** Puts an amount back into the smooth-return queue, so nothing can fall between two structures that
     * each took a part of it. */
    private void requeueOutput(AEKey key, long amount) {
        if (amount <= 0) {
            return;
        }
        var slot = pendingOutputs.computeIfAbsent(key, k -> new long[]{0, 0});
        slot[0] += amount;
        slot[1] += amount;
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
        for (var entry : pendingOutputs.entrySet()) {
            var slot = entry.getValue();
            if (slot[0] <= 0) {
                continue;
            }
            long leftover = pushToNetwork(entry.getKey(), slot[0]);
            // What the network refused goes into the cells; only what neither of them accepts stays queued.
            // Writing back the whole leftover would book the same items twice - once in a cell, once in the
            // queue - and both routes would deliver them.
            long placed = leftover > 0
                    ? insertIntoCells(entry.getKey(), leftover, Actionable.MODULATE)
                    : 0;
            slot[0] = leftover - placed;
            if (slot[0] > 0) {
                LOGGER.warn("Batch assembler could not return {} x{}: network full and no cell space",
                        entry.getKey(), slot[0]);
            }
        }
        pendingOutputs.entrySet().removeIf(entry -> entry.getValue()[0] <= 0);
        for (var cell : resolvedCells()) {
            if (cell == null) {
                continue;
            }
            var contents = new KeyCounter();
            cell.getAvailableStacks(contents);
            for (var entry : contents) {
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                long taken = cell.extract(entry.getKey(), amount, Actionable.MODULATE, actionSource);
                if (taken > 0) {
                    long leftover = pushToNetwork(entry.getKey(), taken);
                    if (leftover > 0) {
                        // Put it back where it came from: the cell just handed that much out, so the space is
                        // still there. Handing it to "some cell" instead could quietly drop it.
                        long back = cell.insert(entry.getKey(), leftover, Actionable.MODULATE, actionSource);
                        requeueOutput(entry.getKey(), leftover - back);
                    }
                }
            }
        }
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

    /**
     * Worker pool used to analyse newly queued patterns off the server thread.
     *
     * <p>Size is the speed-card multiplier (1 &lt;&lt; cards, so 16 threads at four cards). That is the
     * only place speed cards can buy real parallelism here: the cell buffer and the ME network are not
     * thread-safe, so every cell access, grid call and world access stays on the server thread. Without
     * speed cards there is nothing worth handing off and the machine stays single-threaded.</p>
     */
    private ExecutorService workerPool() {
        int wanted = 1 << upgrades.getInstalledUpgrades(AEItems.SPEED_CARD);
        if (wanted <= 1) {
            shutdownWorkers();
            return null;
        }
        if (workers == null || workers.isShutdown() || workerCount != wanted) {
            shutdownWorkers();
            workers = Executors.newFixedThreadPool(wanted, runnable -> {
                var thread = new Thread(runnable, "ae2-pattern-disk-batch-assembler");
                // Daemon so a shutdown or a chunk unload is never held up by a running analysis.
                thread.setDaemon(true);
                // Analysis must never compete with the server thread for CPU time.
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            workerCount = wanted;
        }
        return workers;
    }

    private void shutdownWorkers() {
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
            workerCount = 0;
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // The pool outlives ticks but not the block entity: leaking it would strand one thread group per
        // broken or unloaded machine.
        shutdownWorkers();
    }

    /**
     * Ensures every queued pattern has an execution plan before the batch runs.
     *
     * <p>Analysing a pattern is pure work, so with speed cards installed the missing plans are built in
     * parallel on the worker pool: one task per pattern, with the server thread waiting for the results
     * because the very same tick has to execute them. An interrupted wait is a path that can leave a worker
     * analysing a pattern while this thread retries it - a pure read repeated, never written to.</p>
     * <p>That purity is an assumption about the pattern implementation: container remainders are read
     * through {@code IInput.getRemainingKey}, so a third-party pattern that keeps mutable state in that
     * path would not be safe to analyse here. AE2's own crafting, smithing and stonecutting patterns only
     * read fields built in their constructors, and those are the types the intake gate lets in.</p>
     */
    private void preparePlans(Collection<IPatternDetails> patterns) {
        var missing = new ArrayList<IPatternDetails>();
        for (var pattern : patterns) {
            if (!planCache.containsKey(pattern)) {
                missing.add(pattern);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (planCache.size() + missing.size() > MAX_CACHED_PLANS) {
            // Plans are only a speed-up, so reclaim the cache - but keep the entries the running batch can
            // still reuse instead of dropping everything.
            planCache.keySet().retainAll(queue.keySet());
        }

        var pool = workerPool();
        if (pool == null || missing.size() < 2) {
            // A single analysis is cheaper inline than handed to a worker.
            for (var pattern : missing) {
                planFor(pattern);
            }
            return;
        }

        var submitted = new ArrayList<IPatternDetails>(missing.size());
        var futures = new ArrayList<Future<?>>(missing.size());
        for (var pattern : missing) {
            try {
                futures.add(pool.submit(() -> planCache.put(pattern, PatternPlan.of(pattern))));
                submitted.add(pattern);
            } catch (RejectedExecutionException e) {
                // The pool was shut down under us (machine removed mid-tick). This pattern never reached a
                // worker, so analysing it here cannot race with anything.
                planFor(pattern);
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Cancelling keeps tasks that have not started yet from ever starting. A task that is
                // already running keeps running - interrupting a thread cannot stop a pure computation -
                // so this thread may still duplicate its work, see the safety net below.
                for (int j = i; j < futures.size(); j++) {
                    futures.get(j).cancel(true);
                }
                break;
            } catch (ExecutionException e) {
                // planFor below retries the analysis and reports a permanent failure itself.
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            var future = futures.get(i);
            if (future.isCancelled() || !future.isDone()) {
                // A cancelled task may still be running; runBatch picks the pattern up again on this very
                // tick if it is still missing. Only storage, grid and world access have to stay on this
                // thread - a duplicated pure analysis is harmless.
                continue;
            }
            planFor(submitted.get(i));
        }
    }

    /**
     * Cached plan of a pattern, analysed inline when the cache holds no entry for it yet.
     *
     * <p>Returns {@code null} when the pattern cannot be analysed at all. Callers leave that job queued
     * instead of aborting the batch, and "return buffer" hands the buffered material back to the network.</p>
     */
    private PatternPlan planFor(IPatternDetails pattern) {
        var plan = planCache.get(pattern);
        if (plan != null) {
            return plan;
        }
        try {
            plan = PatternPlan.of(pattern);
        } catch (Exception e) {
            // Log the pattern's class rather than its definition: reading the definition is third-party code
            // and must not be able to throw out of this catch block.
            LOGGER.warn("Could not analyse pattern {} for the batch molecular assembler at {}; leaving the job queued",
                    pattern.getClass().getName(), getBlockPos(), e);
            return null;
        }
        planCache.put(pattern, plan);
        return plan;
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
            cellsDirty = true;
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
    private final PatternDiskTerminalView terminalView = new PatternDiskTerminalView(diskInv,
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
        // Persist the smooth-return queue so a save/load cannot lose already-produced outputs.
        var pendingTag = new CompoundTag();
        int pendingIndex = 0;
        for (var entry : pendingOutputs.entrySet()) {
            var entryTag = new CompoundTag();
            entryTag.put("key", entry.getKey().toTag(registries));
            entryTag.putLong("remaining", entry.getValue()[0]);
            entryTag.putLong("total", entry.getValue()[1]);
            pendingTag.put("e" + pendingIndex++, entryTag);
        }
        tag.put("pendingOutputs", pendingTag);
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
        pendingOutputs.clear();
        var pendingTag = tag.getCompound("pendingOutputs");
        for (var entryKey : pendingTag.getAllKeys()) {
            var entryTag = pendingTag.getCompound(entryKey);
            var key = AEKey.fromTagGeneric(registries, entryTag.getCompound("key"));
            if (key == null) {
                continue;
            }
            long remaining = Math.max(0, entryTag.getLong("remaining"));
            long total = Math.max(remaining, entryTag.getLong("total"));
            if (remaining > 0) {
                pendingOutputs.put(key, new long[]{remaining, total});
            }
        }
        cellsDirty = true;
        refreshRecipePool();
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        // Anything still buffered belongs to jobs the CPU already accounts for; hand it back before the
        // cells (and therefore their contents) leave the machine. Queued jobs are not the only case: the
        // smooth-return queue may still hold finished outputs (or be stuck on a full network), and those are
        // just as lost if the block goes away without flushing them.
        if (hasBufferedWork() || !pendingOutputs.isEmpty()) {
            cancelAndReturnContents();
        }
        // Whatever neither the network nor a cell would take is still the player's: it leaves with the block
        // as drops, because after this method the block entity and its queues are gone.
        for (var entry : pendingOutputs.entrySet()) {
            long amount = entry.getValue()[0];
            var key = entry.getKey();
            if (amount <= 0) {
                continue;
            }
            if (!(key instanceof AEItemKey itemKey)) {
                // Fluids and other non-item keys have no item stack to be dropped as.
                LOGGER.warn("Batch assembler leftover {} x{} cannot be dropped as an item", key, amount);
                continue;
            }
            while (amount > 0) {
                int perStack = (int) Math.min(amount, Math.max(1, itemKey.getMaxStackSize()));
                drops.add(itemKey.toStack(perStack));
                amount -= perStack;
            }
        }
        pendingOutputs.clear();
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
        pendingOutputs.clear();
        for (int i = 0; i < CELL_SLOTS; i++) {
            cellInv.setItemDirect(i, ItemStack.EMPTY);
        }
        diskInv.clear();
        // Clearing the slots notifies per slot, but the view is dropped explicitly as well so
        // correctness does not hinge on that notification surviving future changes.
        terminalView.invalidate();
        upgrades.clear();
        queue.clear();
        cellsDirty = true;
        refreshCells();
    }
}
