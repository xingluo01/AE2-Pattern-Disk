package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
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

import io.github.lounode.ae2pattern.common.pattern.PatternDiskRemoveInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.logic.BatchRecipePool;
import io.github.lounode.ae2pattern.common.logic.PatternPlan;

/**
 * Block entity of the batch molecular assembler (批处理分子装配室).
 *
 * <p>It consumes crafting jobs pushed by AE2 crafting CPUs (via {@link ICraftingMachine}) and buffers
 * the pushed materials inside private storage-cell slots instead of executing immediately. Once no new
 * material has arrived for a short while, the buffered jobs are assembled in one batch - crafting table,
 * smithing table and stonecutting recipes are all supported - and the results (including container
 * remainders) are pushed back into the ME network, where the crafting CPU continues from there.</p>
 *
 * <p>The cell slots never join the ME network storage: this block entity deliberately implements neither
 * {@code IStorageProvider} nor the {@code ME_STORAGE} capability.</p>
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
    /** Smooth-return horizon: accumulated outputs are returned to the network over this many ticks
     * (5% of the accumulated total per tick), so a huge batch never produces one giant IO burst. */
    private static final int OUTPUT_RETURN_TICKS = 20;
    /** Upper bound on cached execution plans. Plans are only a speed-up, so a machine fed an endless
     * variety of pushed patterns drops the whole cache instead of growing without limit. */
    private static final int MAX_CACHED_PLANS = 1024;
    /** AE charged per assembled job (container remainders and parallels do not change this). */
    private static final double ENERGY_PER_RUN = 10.0;

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
    /** Fast batch mode flushes after 10 quiet ticks instead of the standard 40. */
    private boolean fastBatchMode = false;
    /** Worker threads used to analyse newly queued patterns in parallel; null while there are none. */
    private ExecutorService workers;
    private int workerCount;

    public BatchAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_BATCH_ASSEMBLER.get(), pos, blockState);

        this.upgrades = UpgradeInventories.forMachine(AEPatternRegistries.BLOCK_BATCH_ASSEMBLER.get(),
                MAX_SPEED_CARDS, this::onUpgradesChanged);

        this.getMainNode()
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
        // Every arrival restarts the window: the machine waits for the arrivals to actually stop.
        lastInputGameTime = currentGameTime();
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
        return PatternContainerGroup.nothing();
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
        if (queue.isEmpty() && pendingOutputs.isEmpty()) {
            return TickRateModulation.SLEEP;
        }
        // The output drain runs every tick, independent of batch scheduling: the smooth-return queue
        // keeps feeding the crafting CPU while (and after) the batch executes.
        boolean pendingWork = !pendingOutputs.isEmpty();
        if (pendingWork) {
            drainOutputs();
        }
        // A batch starts only while the material is genuinely quiet: the window is the gap since the last
        // accepted push, so as long as material keeps arriving the machine keeps waiting. Nothing forces
        // a batch through on a timer.
        boolean worked = false;
        if (!queue.isEmpty() && inputGapTicks() >= batchIdleTicks()) {
            worked = runBatch();
        }
        if (worked || pendingWork) {
            saveChanges();
        }
        if (!queue.isEmpty() || !pendingOutputs.isEmpty()) {
            return TickRateModulation.IDLE;
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

        boolean worked = false;
        var it = queue.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var plan = planFor(entry.getKey());
            if (plan == null) {
                // Unanalysable pattern: keep its job queued instead of aborting the rest of the batch.
                continue;
            }
            long remaining = entry.getValue();

            while (remaining > 0) {
                if (!assembleOnce(plan)) {
                    break;
                }
                remaining--;
                worked = true;
            }

            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
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
    private boolean assembleOnce(PatternPlan plan) {
        if (!hasInputs(plan)) {
            return false;
        }
        var consumed = consumeInputs(plan);
        if (consumed == null) {
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
            // Buckets, bottles and other containers must be handed back, otherwise they vanish and the
            // crafting CPU keeps waiting for its expected container items forever.
            var remaining = input.remainingFor(usedKey);
            long consumedCount = input.multiplier();
            if (remaining != null && consumedCount > 0) {
                // AE2 books one remaining item per consumed template, i.e. one per occupied slot.
                outputs.merge(remaining, consumedCount, Long::sum);
            }
        }

        // Defer (keeping the inputs in the cell buffer) when the machine cannot run at all.
        if (!consumePower()) {
            rollbackInputs(consumed.byKey());
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
        var storage = node.getGrid().getStorageService().getInventory();
        var it = pendingOutputs.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var slot = entry.getValue();
            long rate = Math.max(1, (slot[1] + OUTPUT_RETURN_TICKS - 1) / OUTPUT_RETURN_TICKS);
            long amount = Math.min(slot[0], rate);
            long inserted = storage.insert(entry.getKey(), amount, Actionable.MODULATE, actionSource);
            slot[0] -= inserted;
            if (slot[0] <= 0) {
                it.remove();
            }
        }
    }

    /**
     * Charges the grid for one assembly run. Energy is charged per run, not per parallel item, matching
     * the design rule that parallel work does not add extra consumption.
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

    private boolean hasInputs(PatternPlan plan) {
        for (var input : plan.inputs()) {
            if (input == null) {
                continue;
            }
            long needed = input.multiplier();
            if (!canSupply(input, needed)) {
                return false;
            }
        }
        return true;
    }

    private boolean canSupply(PatternPlan.Input input, long needed) {
        if (needed < 0) {
            // Malformed slot: a negative requirement must never reach the cells as a negative extract.
            return false;
        }
        for (var candidate : input.candidates()) {
            if (extractFromCells(candidate, needed, Actionable.SIMULATE) >= needed) {
                return true;
            }
        }
        return false;
    }

    /**
     * Consumes the inputs for one execution from the cell buffer. Returns the per-key totals (for
     * rollback) plus the variant actually consumed per input index (containers may differ), or
     * {@code null} when a required input is missing.
     */
    private Consumption consumeInputs(PatternPlan plan) {
        var byKey = new LinkedHashMap<AEKey, Long>();
        var usedByInput = new ArrayList<AEKey>();
        for (var input : plan.inputs()) {
            if (input == null) {
                usedByInput.add(null);
                continue;
            }
            var usedKey = consumeOne(input, input.multiplier());
            if (usedKey == null) {
                rollbackInputs(byKey);
                return null;
            }
            byKey.merge(usedKey, input.multiplier(), Long::sum);
            usedByInput.add(usedKey);
        }
        return new Consumption(byKey, usedByInput);
    }

    /** Per-run consumption record: {@code byKey} drives rollbacks, {@code usedByInput} drives container remainders. */
    private record Consumption(LinkedHashMap<AEKey, Long> byKey, List<AEKey> usedByInput) {
    }

    /** Extracts one input's requirement from the cells; returns the key actually consumed, or null. */
    private AEKey consumeOne(PatternPlan.Input input, long needed) {
        if (needed < 0) {
            // Defensive: hasInputs already rejects negative requirements.
            return null;
        }
        for (var candidate : input.candidates()) {
            if (extractFromCells(candidate, needed, Actionable.SIMULATE) >= needed) {
                extractFromCells(candidate, needed, Actionable.MODULATE);
                return candidate;
            }
        }
        return null;
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

    /** Empties the cell buffer back into the ME network (used by the "cancel crafting" action). */
    public void cancelAndReturnContents() {
        queue.clear();
        // Reset the window: the queue is gone, so the next push must go through a fresh batch window.
        lastInputGameTime = currentGameTime();
        // Flush the smooth-return queue immediately: after the block is removed (drops path) nothing
        // would tick anymore, and the crafting CPU is still waiting for these outputs.
        for (var entry : pendingOutputs.entrySet()) {
            var slot = entry.getValue();
            if (slot[0] > 0) {
                pushToNetwork(entry.getKey(), slot[0]);
            }
        }
        pendingOutputs.clear();
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
                    pushToNetwork(entry.getKey(), taken);
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
            cachedTerminalInventory = null; // invalidate the pattern access terminal view
        } else {
            cellsDirty = true;
        }
        saveChanges();
        alertTicker();
    }

    // ---- pattern access terminal (PatternContainer) -------------------------

    /** Cached terminal view over the disk contents; invalidated whenever the disks change. */
    private PatternDiskRemoveInventory cachedTerminalInventory;

    @Override
    public IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        if (cachedTerminalInventory != null) {
            return cachedTerminalInventory;
        }
        cachedTerminalInventory = new PatternDiskRemoveInventory(diskInv,
                new PatternDiskRemoveInventory.BlankPatternSink() {
                    @Override
                    public boolean drawBlankPatterns(int count) {
                        return tryDrawBlankPattern(count);
                    }

                    @Override
                    public boolean hasBlankPatterns(int count) {
                        return canDrawBlankPattern(count);
                    }

                    @Override
                    public boolean returnBlankPatterns(int count) {
                        return returnBlankPattern(count);
                    }
                },
                this::markTerminalChanged);
        return cachedTerminalInventory;
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

    /** Read-only pre-check: whether the ME network holds at least {@code count} blank patterns. */
    private boolean canDrawBlankPattern(int count) {
        var grid = getMainNode().getGrid();
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.extract(blank, count, Actionable.SIMULATE, actionSource) == count;
    }

    /** Draws {@code count} blank patterns from the ME network, all-or-nothing. */
    private boolean tryDrawBlankPattern(int count) {
        if (!canDrawBlankPattern(count)) {
            return false;
        }
        var grid = getMainNode().getGrid();
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.extract(blank, count, Actionable.MODULATE, actionSource) == count;
    }

    /** Returns {@code count} blank patterns to the ME network (undo of a swap restore). */
    private boolean returnBlankPattern(int count) {
        var grid = getMainNode().getGrid();
        if (grid == null || count <= 0) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var blank = AEItemKey.of(AEItems.BLANK_PATTERN);
        return storage.insert(blank, count, Actionable.MODULATE, actionSource) == count;
    }

    private void markTerminalChanged() {
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
        // cells (and therefore their contents) leave the machine.
        if (hasBufferedWork()) {
            cancelAndReturnContents();
        }
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
        upgrades.clear();
        queue.clear();
        cellsDirty = true;
        refreshCells();
    }
}
