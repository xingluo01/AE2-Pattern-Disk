package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

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
import appeng.api.stacks.KeyCounter;
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
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.filter.IAEItemFilter;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.api.PatternDiskTerminalView;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.logic.BatchLane;
import io.github.lounode.ae2pattern.common.logic.BatchPatternAnalyser;
import io.github.lounode.ae2pattern.common.logic.BatchRecipePool;
import io.github.lounode.ae2pattern.common.logic.BatchWorkState;
import io.github.lounode.ae2pattern.common.logic.CellBuffer;
import io.github.lounode.ae2pattern.common.logic.ParallelSlotProbe;
import io.github.lounode.ae2pattern.common.logic.PatternPlanCache;
import io.github.lounode.ae2pattern.common.logic.SmoothReturnQueue;

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
 * once the machine has gone idle is handed back to the network after {@link BatchTuning#IDLE_FLUSH_TICKS} quiet ticks,
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
 * keeps a bulk order accumulating. A lane whose backlog has waited {@link BatchTuning#BATCH_AGE_LIMIT_TICKS} is let through
 * regardless of the window, which is the only hard bound on a supply that never goes quiet. Keys the network is
 * currently waiting for skip the smooth-return trickle too, instead of arriving over {@link BatchTuning#OUTPUT_RETURN_TICKS}
 * ticks.</p>
 *
 * <p><b>职责边界</b>：本类只留 AE2 的接口覆写、节点生命周期、客户端在线态镜像、终端视图与三份库存的装配。
 * 车道运行节拍在 {@link BatchLaneRunner}，输出投递与网络回流在 {@link BatchOutputDelivery}，存档与掉落在
 * {@link BatchPersistence}；调参常量在 {@link BatchTuning}，宿主服务窄接口在 {@link BatchHost}。所有
 * {@code @Override} 入口都留在这里做一行转调，存档键字面量与 public 签名一字不动。</p>
 */
public class BatchAssemblerBlockEntity extends AENetworkedBlockEntity
        implements InternalInventoryHost, IUpgradeableObject, IGridTickable, ICraftingMachine, ICraftingProvider,
        IPatternDiskHost, PatternContainer, IPriorityHost {

    public static final int CELL_SLOTS = 9;
    public static final int DISK_SLOTS = 9;
    public static final int MAX_SPEED_CARDS = 4;

    static {
        // 仍在方块实体类装载时打一次：调参常量与日志都搬去了 {@link BatchTuning}，时序不变。
        BatchTuning.logEffectiveTuning();
    }

    /** Client-side mirror of the ME node state; synced through the block entity stream. */
    private boolean isActive = false;

    private final AppEngCellInventory cellInv = new AppEngCellInventory(this, CELL_SLOTS);
    private final AppEngInternalInventory diskInv = new AppEngInternalInventory(this, DISK_SLOTS);
    private final IUpgradeInventory upgrades = UpgradeInventories.forMachine(
            AEPatternRegistries.BLOCK_BATCH_ASSEMBLER.get(), MAX_SPEED_CARDS, this::onUpgradesChanged);
    private final IActionSource actionSource = new MachineSource(this);

    /** 元件缓冲：解析、读写与「还剩多少」都在 {@link CellBuffer} 里，这里只持有它。 */
    private final CellBuffer cellBuffer = new CellBuffer(cellInv, actionSource, this::saveChanges);

    private final BatchRecipePool recipePool = new BatchRecipePool();
    private final List<IPatternDetails> exposedPatterns = new ArrayList<>();

    /** 并行槽位估算与它每 tick 的记忆值都在 {@link ParallelSlotProbe} 里，这里只持有它。 */
    private final ParallelSlotProbe parallelSlots =
            new ParallelSlotProbe(cellBuffer, BatchTuning.MAX_ADVERTISED_PARALLEL_SLOTS);

    /** 配方计划、余料与告警记号都在 {@link PatternPlanCache} 里，这里只持有它。 */
    private final PatternPlanCache plans = new PatternPlanCache();

    /**
     * 产出物的平滑回送队列：增速、额度与优先通道都在 {@link SmoothReturnQueue} 里，这里只持有它。
     */
    private final SmoothReturnQueue returns =
            new SmoothReturnQueue(BatchTuning.OUTPUT_RETURN_TICKS, BatchTuning.PRIORITY_RETURN_BURST);

    /** 只能在这里构造：它读的就是上面那个升级库存。 */
    private final BatchPatternAnalyser analyser = new BatchPatternAnalyser(
            () -> upgrades.getInstalledUpgrades(AEItems.SPEED_CARD), this::getBlockPos);

    /**
     * Terminal view shared with the provider (see {@link PatternDiskTerminalView}): the pattern access
     * terminal has to read the disks, never this machine's derived recipe pool.
     */
    private final PatternDiskTerminalView terminalView = PatternDiskApi.terminalView(diskInv,
            this::getGrid, this, this::markTerminalChanged, this::getLevel);

    /** 宿主服务快照：把节点 / 时间 / 落盘 / 收料 / 重建配方池收成五个回调给协作类。 */
    private final BatchHost host = new BatchHost(this::getMainNode, this::currentGameTime, this::saveChanges,
            this::acceptsPlans, this::refreshRecipePool);

    /** 输出投递与网络回流。先建它，车道执行引擎的扣能量回调要绑到它。 */
    private final BatchOutputDelivery delivery =
            new BatchOutputDelivery(host, cellBuffer, returns, actionSource);

    /** 车道运行节拍：两条通道、分批窗口、执行预算、ticker 醒睡都在它里面。 */
    private final BatchLaneRunner runner = new BatchLaneRunner(host, delivery, this::getLevel, this::getBlockPos,
            delivery::consumePower, cellBuffer, plans, returns, analyser, parallelSlots, exposedPatterns);

    /** 存档与掉落。 */
    private final BatchPersistence persistence = new BatchPersistence(host, runner, delivery,
            cellInv, diskInv, upgrades, terminalView, cellBuffer, returns);

    public BatchAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_BATCH_ASSEMBLER.get(), pos, blockState);

        // 它向自动合成暴露样板并自己从网络取料，按 AE2 的设备口径要占 1 个频道。
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0)
                .setInWorldNode(true)
                .setExposedOnSides(java.util.Set.of(net.minecraft.core.Direction.values()))
                .addService(IGridTickable.class, this)
                .addService(ICraftingProvider.class, this);

        // 交付侧要按调用时刻问车道「队列里还有没有货」；attach 放在构造器里，之后才会有人调它。
        this.delivery.attach(runner);

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
        runner.alertTicker();
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
        runner.clearLastHandedPattern();
        parallelSlots.invalidate();

        exposedPatterns.clear();
        exposedPatterns.addAll(recipePool.all());
        if (getMainNode().getNode() != null) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    // ---- ICraftingMachine (jobs pushed by AE2 pattern providers) -------------

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs,
            net.minecraft.core.Direction ejectionDirection) {
        return runner.acceptPush(patternDetails, inputs);
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
        return persistence.priority();
    }

    @Override
    public int getPriority() {
        return persistence.priority();
    }

    @Override
    public void setPriority(int newValue) {
        if (persistence.priority() == newValue) {
            return;
        }
        persistence.setPriority(newValue);
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
        MenuOpener.returnTo(io.github.lounode.ae2pattern.common.menu.assembler.BatchAssemblerMenu.TYPE, player,
                subMenu.getLocator());
    }

    /** 优先级界面那一角的图标：本机自己。 */
    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(getBlockState().getBlock());
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        return runner.acceptPush(patternDetails, inputHolder);
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

    /** @see BatchLaneRunner#availableParallelSlotsLong() */
    public long availableParallelSlotsLong() {
        return runner.availableParallelSlotsLong();
    }

    /** @see BatchLaneRunner#availableParallelSlotsFor(IPatternDetails) */
    public long availableParallelSlotsFor(@Nullable IPatternDetails patternDetails) {
        return runner.availableParallelSlotsFor(patternDetails);
    }

    /** @see BatchOutputDelivery#flushReturnsAfterCpuAccounting() */
    public void flushReturnsAfterCpuAccounting() {
        delivery.flushReturnsAfterCpuAccounting();
    }

    /** @see BatchLaneRunner#availableParallelSlots() */
    public int availableParallelSlots() {
        return runner.availableParallelSlots();
    }

    /** @see BatchLaneRunner#acceptPatternBatch(IPatternDetails, KeyCounter[], long) */
    public boolean acceptPatternBatch(IPatternDetails patternDetails, KeyCounter[] inputTotal, long craftCount) {
        return runner.acceptPatternBatch(patternDetails, inputTotal, craftCount);
    }

    /** True while buffered jobs are waiting to be assembled (drives the slot locks). */
    public boolean hasBufferedWork() {
        return runner.hasBufferedWork();
    }

    // ---- ticking / batch execution ------------------------------------------

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 1, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        return runner.tickingRequest();
    }

    /** @see BatchOutputDelivery#cancelAndReturnContents() */
    public void cancelAndReturnContents() {
        delivery.cancelAndReturnContents();
    }

    /** @see BatchLaneRunner#getQueuedJobCount() */
    public long getQueuedJobCount() {
        return runner.getQueuedJobCount();
    }

    /** @see BatchLaneRunner#getTicksUntilBatch() */
    public int getTicksUntilBatch() {
        return runner.getTicksUntilBatch();
    }

    /** @see BatchLaneRunner#getWorkState() */
    public BatchWorkState getWorkState() {
        return runner.getWorkState();
    }

    /** @see BatchLaneRunner#batchIdleTicks() */
    public int batchIdleTicks() {
        return runner.batchIdleTicks();
    }

    /** @see BatchLaneRunner#isFastBatchMode() */
    public boolean isFastBatchMode() {
        return runner.isFastBatchMode();
    }

    /** @see BatchLaneRunner#setFastBatchMode(boolean) */
    public void setFastBatchMode(boolean fastBatchMode) {
        runner.setFastBatchMode(fastBatchMode);
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
            runner.resetTicker();
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
            runner.setIdleFlushDone(false);
        }
        saveChanges();
        runner.alertTicker();
    }

    // ---- pattern access terminal (PatternContainer) -------------------------

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
        runner.resetTicker();
        refreshRecipePool();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        persistence.saveAdditional(tag, registries);
    }

    @Override
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        persistence.loadTag(tag, registries);
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        persistence.addAdditionalDrops(level, pos, drops,
                () -> super.addAdditionalDrops(level, pos, drops));
    }

    @Override
    public void clearContent() {
        super.clearContent();
        persistence.clearContent();
    }

    /** Current game time of the level this machine lives in; 0 before the entity is attached. */
    private long currentGameTime() {
        var level = getLevel();
        return level == null ? 0 : level.getGameTime();
    }
}
