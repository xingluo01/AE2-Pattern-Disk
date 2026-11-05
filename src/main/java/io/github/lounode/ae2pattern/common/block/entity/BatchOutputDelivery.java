package io.github.lounode.ae2pattern.common.block.entity;

import java.util.ArrayList;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

import io.github.lounode.ae2pattern.common.logic.CellBuffer;
import io.github.lounode.ae2pattern.common.logic.SmoothReturnQueue;

/**
 * 批处理装配室的输出投递与网络回流：把材料写进元件缓冲、把产物交还 ME 存储、闲置回灌、以及拆机 / 取消时
 * 把还留在机器里的东西清空。
 *
 * <p>这一块的状态是 {@code returnStalled}（上一次回送是否被网络挡住）与元件缓冲、回送队列这些共享对象；
 * 前者连同它的读写入口一起搬到这里，后者本就是 {@code common/logic} 里自带不变量的对象，按引用传给三个
 * 协作类共用。方块实体只经 {@link BatchHost} 提供主节点 / 时间 / 落盘，车道那边经 {@link BatchLaneRunner}
 * 提供输入键快照、清道、活动时钟与 ticker 提醒。</p>
 *
 * <p>拆机路径 {@code addAdditionalDrops} 里的 {@code super.addAdditionalDrops(...)} 必须留在方块实体里调用，
 * 这里收的是一个 {@code Runnable} 回调，由宿主在「清空回送队列之后、掉落元件 / 磁盘 / 升级之前」这一原位插入。</p>
 */
final class BatchOutputDelivery {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchAssemblerBlockEntity.class);

    private final BatchHost host;
    private final CellBuffer cellBuffer;
    private final SmoothReturnQueue returns;
    private final IActionSource actionSource;

    /** Set when a return attempt found the network unwilling to take what was waiting. */
    private boolean returnStalled;

    /** 车道那边：清道、活动时钟、ticker 提醒与输入键快照。构造后由宿主 attach。 */
    private BatchLaneRunner runner;

    BatchOutputDelivery(BatchHost host, CellBuffer cellBuffer, SmoothReturnQueue returns,
            IActionSource actionSource) {
        this.host = host;
        this.cellBuffer = cellBuffer;
        this.returns = returns;
        this.actionSource = actionSource;
    }

    void attach(BatchLaneRunner runner) {
        this.runner = runner;
    }

    boolean isReturnStalled() {
        return returnStalled;
    }

    // ---- intake into the cell buffer ----------------------------------------

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
    boolean bufferInputs(KeyCounter[] inputs) {
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

    // ---- output drain / network hand-over -----------------------------------

    /**
     * 交付一轮平滑回送：每个 key 交出它这一轮的额度，网络没收下的部分留到下一 tick。额度与优先通道的算法在
     * {@link SmoothReturnQueue#drain}，这里只提供投递目标（ME 存储 + 「谁正在被索要」）。
     */
    void drainOutputs() {
        var node = host.gridNodeManager().getNode();
        if (node == null || node.getGrid() == null) {
            return;
        }
        var grid = node.getGrid();
        var storage = grid.getStorageService().getInventory();
        // Read once per drain: the per-key check below would otherwise walk every queued plan again.
        var queuedInputs = runner.queuedInputKeysForTick(host.gameTimeNow());
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
     * Charges the grid for one assembled run ({@link BatchTuning#ENERGY_PER_RUN}). A whole batch is one execution unit:
     * the parallelism inside it - pattern multipliers, container remainders, further crafts of the same
     * pattern - does not add consumption, so the charge does not scale with the batch size.
     *
     * <p>执行引擎通过回调调它：网格不属于引擎，扣能量的判断与执行得留在方块实体这一侧。</p>
     */
    boolean consumePower() {
        double wanted = BatchTuning.ENERGY_PER_RUN;
        var node = host.gridNodeManager().getNode();
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
        var node = host.gridNodeManager().getNode();
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
    boolean flushCellsToNetwork() {
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
    void flushReturnsAfterCpuAccounting() {
        if (!returns.isEmpty()) {
            drainOutputs();
        }
    }

    /** Empties the cell buffer back into the ME network (used by the "cancel crafting" action). */
    void cancelAndReturnContents() {
        // 通道清空时自己的窗口统计一并归零：下一份订单必须重新走一遍完整窗口，不能继承上一份的短窗判定。
        runner.clearLanes();
        long now = host.gameTimeNow();
        runner.noteActivity(now);
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
        host.persistChanges();
        runner.alertTicker();
    }
}
