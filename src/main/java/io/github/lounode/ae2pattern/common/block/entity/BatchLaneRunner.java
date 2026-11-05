package io.github.lounode.ae2pattern.common.block.entity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;

import io.github.lounode.ae2pattern.common.logic.BatchLane;
import io.github.lounode.ae2pattern.common.logic.BatchPatternAnalyser;
import io.github.lounode.ae2pattern.common.logic.BatchSynthesisEngine;
import io.github.lounode.ae2pattern.common.logic.BatchWorkState;
import io.github.lounode.ae2pattern.common.logic.CellBuffer;
import io.github.lounode.ae2pattern.common.logic.ParallelSlotProbe;
import io.github.lounode.ae2pattern.common.logic.PatternPlanCache;
import io.github.lounode.ae2pattern.common.logic.SmoothReturnQueue;

/**
 * 批处理装配室的车道运行节拍：两条通道、到货分道、分批窗口、每 tick 的执行预算、并行槽位估算、ticker 醒睡，
 * 以及对外那张「机器在干什么」的状态栏。
 *
 * <p>这一块是「状态 + 它的不变量」整体搬过来的：通道的静默窗口、批龄、分批分类记账本就是
 * {@link BatchLane} 自己的状态，而 {@code workState} / {@code lastActivityGameTime} / {@code idleFlushDone} /
 * {@code tickerAwake} / {@code fastBatchMode} / {@code lastHandedPattern} / 输入键快照这些散在方块实体里的
 * 字段，全都只被本节的方法读写，于是随方法一起搬。方块实体只留一份 {@link BatchHost} 窄接口与几个
 * 回调（世界 / 位置 / 扣能量）供它取平台能力，不反过来被它摸字段。</p>
 *
 * <p>{@code isActive} 那份客户端镜像没有搬：它与 {@code writeToStream}/{@code readFromStream} /
 * {@code onMainNodeStateChanged} 同属方块实体的节点生命周期，留在宿主里。ticker 醒睡则在这里，节点状态变化
 * 与读档时由宿主调 {@link #resetTicker()} 放行下一次 alert。</p>
 */
final class BatchLaneRunner {

    private final BatchHost host;
    private final BatchOutputDelivery delivery;
    private final CellBuffer cellBuffer;
    private final PatternPlanCache plans;
    private final SmoothReturnQueue returns;
    private final BatchPatternAnalyser analyser;
    private final ParallelSlotProbe parallelSlots;
    private final List<IPatternDetails> exposedPatterns;
    private final BatchSynthesisEngine engine;

    /**
     * 两条任务通道。轻载通道收零散的小订单，重载通道收整批 hand-over 与攒起来的大订单；两条道各有自己的
     * 静默窗口、分批分类与操作预算，于是大订单的持续到货不会再把小订单的计时拨回去。判定与记账都在
     * {@link BatchLane}，这里只决定谁进哪条道。
     */
    private final BatchLane<IPatternDetails> lightLane;
    private final BatchLane<IPatternDetails> bulkLane;

    /** 两条通道，按调度顺序：轻载先跑，重载拿剩下的预算。 */
    private final List<BatchLane<IPatternDetails>> lanes;

    /**
     * What the machine is doing, or why it is not doing it. 值域与每档含义在 {@link BatchWorkState}：
     * 它得被拆解出的执行引擎（{@code common/logic}）写，所以状态枚举不能留在方块实体里。
     */
    private BatchWorkState workState = BatchWorkState.IDLE;

    /**
     * 本 tick 的输入键快照与它对应的游戏时间。重建要遍历两条通道的整条队列，而同一 tick 里可能 drain 多次
     * （外部还会经 {@code flushReturnsAfterCpuAccounting} 再叫一次）。
     *
     * <p>同 tick 内队列变了也不重算：这个集合只决定「哪些 key 跳过平滑直接交」，旧一步只影响交付节奏，
     * 不影响记账。</p>
     */
    private Set<AEKey> queuedInputKeyCache = Set.of();
    private long queuedInputKeyCacheTick = -1;

    /** The pattern the parallel-slot estimate is measured against: the last one actually handed over. */
    private IPatternDetails lastHandedPattern;

    /** 机器最后一次真正干活的游戏时间（收货、跑批、回送都算）。闲置回灌从这里数静默 tick。 */
    private long lastActivityGameTime;
    /** Whether this idle period already tried the cell flush - the network may simply be full, and retrying
     * that every tick would be pointless. Cleared as soon as the machine does anything again. */
    private boolean idleFlushDone;
    /** Fast batch mode flushes after 10 quiet ticks instead of the standard 40. */
    private boolean fastBatchMode = false;

    /**
     * ticker 是否醒着。醒着时它按 {@code TickingRequest(1, 1)} 每 tick 被调一次；再 alert 只会让 AE2 把它的
     * 队列项摘下来再插回去（{@code PriorityQueue.remove} 是线性扫描），到货越碎这笔越贵。
     *
     * <p>拿不准就置 {@code false}：多一次 alert 只是白跑一趟，漏一次就是机器睡着没人叫。</p>
     */
    private boolean tickerAwake = false;

    BatchLaneRunner(BatchHost host, BatchOutputDelivery delivery, Supplier<Level> level,
            Supplier<BlockPos> position, BooleanSupplier power, CellBuffer cellBuffer, PatternPlanCache plans,
            SmoothReturnQueue returns, BatchPatternAnalyser analyser, ParallelSlotProbe parallelSlots,
            List<IPatternDetails> exposedPatterns) {
        this.host = host;
        this.delivery = delivery;
        this.cellBuffer = cellBuffer;
        this.plans = plans;
        this.returns = returns;
        this.analyser = analyser;
        this.parallelSlots = parallelSlots;
        this.exposedPatterns = exposedPatterns;
        this.lightLane = new BatchLane<>(BatchTuning.BATCH_AGE_LIMIT_TICKS);
        this.bulkLane = new BatchLane<>(BatchTuning.BATCH_AGE_LIMIT_TICKS);
        this.lanes = List.of(lightLane, bulkLane);
        // 能量与状态回写是执行引擎跟本机借的两样东西：网格不属于它，状态栏也不属于它。
        this.engine = new BatchSynthesisEngine(cellBuffer, plans, returns, level, position, power,
                s -> workState = s);
    }

    /** @return how many assembly jobs are still queued on both lanes. */
    long getQueuedJobCount() {
        return lightLane.queuedJobs() + bulkLane.queuedJobs();
    }

    /**
     * @return the quiet ticks still needed before the earliest lane runs, or 0 when one is free to run now
     */
    int getTicksUntilBatch() {
        long now = host.gameTimeNow();
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
    BatchWorkState getWorkState() {
        // The structural reasons are worked out here rather than recorded while ticking, because ticking only
        // runs while the machine is active: a machine that is offline, has no cell to work from, or has
        // nothing executable on its disks would otherwise keep reporting whatever it was last doing. That
        // staleness is also what made an offline machine come out as "no power" - the same node check feeds
        // both, so it has to be asked here first.
        var node = host.gridNodeManager().getNode();
        if (node == null || node.getGrid() == null) {
            return BatchWorkState.OFFLINE;
        }
        if (!host.canAcceptPlans()) {
            return BatchWorkState.NO_BUFFER;
        }
        if (exposedPatterns.isEmpty()) {
            return BatchWorkState.NO_PATTERN;
        }
        if (!returns.isEmpty() && queueEmpty()) {
            // Distinguished from a return in progress because only one of the two has anything its owner can act
            // on, and from the outside the queue draining and the queue stuck look exactly alike.
            return delivery.isReturnStalled() ? BatchWorkState.OUTPUT_BLOCKED : BatchWorkState.RETURNING_OUTPUTS;
        }
        if (queueEmpty() && returns.isEmpty()) {
            return BatchWorkState.IDLE;
        }
        return workState;
    }

    // ---- ticking / batch execution ------------------------------------------

    TickRateModulation tickingRequest() {
        long now = host.gameTimeNow();
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
            delivery.drainOutputs();
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
            host.persistChanges();
        }
        if (!queueEmpty() || !returns.isEmpty()) {
            return modulated(TickRateModulation.IDLE);
        }
        return modulated(TickRateModulation.SLEEP);
    }

    /**
     * Tick body while the machine has nothing queued and nothing left to return: normally this is where it
     * goes back to sleep. It stays awake for {@link BatchTuning#IDLE_FLUSH_TICKS} instead when the cells still hold
     * something, and then hands that material back to the network - see {@code flushCellsToNetwork}.
     */
    private TickRateModulation idleTick(long now) {
        if (idleFlushDone || !cellBuffer.hasContents()) {
            return modulated(TickRateModulation.SLEEP);
        }
        if (now - lastActivityGameTime < BatchTuning.IDLE_FLUSH_TICKS) {
            return modulated(TickRateModulation.IDLE);
        }
        idleFlushDone = true;
        if (delivery.flushCellsToNetwork()) {
            host.persistChanges();
        }
        return modulated(TickRateModulation.SLEEP);
    }

    /**
     * 跑两条通道，总额度 {@link BatchTuning#OPERATIONS_PER_RUN}。
     *
     * <p>两条都就绪时轻载通道先跑、只拿 {@link BatchTuning#LIGHT_OPERATION_SHARE}，其余留给重载通道；只有一条就绪时
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
            int lightBudget = BatchTuning.OPERATIONS_PER_RUN <= 1
                    ? BatchTuning.OPERATIONS_PER_RUN
                    : Math.min(BatchTuning.OPERATIONS_PER_RUN - 1, BatchTuning.LIGHT_OPERATION_SHARE);
            int used = runLane(lightLane, lightBudget, now);
            // 不用短路：重载通道的这一轮不能因为轻载通道已经跑出东西就被跳过。
            boolean workedBulk = runLane(bulkLane, BatchTuning.OPERATIONS_PER_RUN - used, now) > 0;
            return used > 0 || workedBulk;
        }
        if (lightDue) {
            return runLane(lightLane, BatchTuning.OPERATIONS_PER_RUN, now) > 0;
        }
        return runLane(bulkLane, BatchTuning.OPERATIONS_PER_RUN, now) > 0;
    }

    /**
     * 跑一条通道，最多用掉 {@code budget} 个操作。
     *
     * <p>与原先「整队列跑一轮」的差别只在边界：预算按通道给，遍历只碰这条道的队列，分批分类也记在这条道上。
     * 摊销块与单件慢路径的顺序、以及「一个样板没跑完就轮到下一个」的规则都照旧。Inputs are consumed only
     * through the cells, and produced outputs enter the smooth-return queue instead of hitting the network
     * storage in one burst. Internal chaining is deliberately not performed - see {@code BatchRecipePool}.</p>
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
            host.persistChanges();
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
    Set<AEKey> queuedInputKeysForTick(long now) {
        if (queuedInputKeyCacheTick != now) {
            queuedInputKeyCache = queuedInputKeys();
            queuedInputKeyCacheTick = now;
        }
        return queuedInputKeyCache;
    }

    /** 清空两条通道及其窗口统计，并作废输入键快照。 */
    void clearLanes() {
        for (var lane : lanes) {
            lane.clear();
        }
        queuedInputKeyCache = Set.of();
        queuedInputKeyCacheTick = -1;
    }

    /** True while buffered jobs are waiting to be assembled (drives the slot locks). */
    boolean hasBufferedWork() {
        return !queueEmpty();
    }

    // ---- intake -------------------------------------------------------------

    /**
     * Common intake for both push entry points (CPU direct push and adjacent pattern provider):
     * buffers the delivered inputs and books exactly one job per push. Job volume must never be
     * scaled here - the CPU will still deliver the full order one push at a time, so any
     * compensation would multiply the total output beyond the ordered amount.
     */
    boolean acceptPush(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern)) {
            // Only crafting / smithing / stonecutting can be executed here. Accepting an AE processing
            // pattern would let the machine turn whatever was pushed in into the pattern's outputs.
            return false;
        }
        if (patternDetails.getInputs().length == 0) {
            // Defensive: a pattern without inputs would queue forever as an empty freebie.
            return false;
        }
        if (!host.canAcceptPlans()) {
            return false;
        }
        if (!delivery.bufferInputs(inputHolder)) {
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
     * Takes a whole batch in one hand-over. {@code inputTotal} is the complete input for {@code craftCount}
     * crafts - not one copy of it - which is how NEO ECO's parallel dispatch states the contract, and why
     * nothing has to be scaled here: the CPU counted every craft already and books exactly these.
     *
     * <p>All or nothing, like every other intake: either the buffer takes the complete total and the jobs
     * are booked, or the counters are left untouched and ECO keeps the material.</p>
     */
    boolean acceptPatternBatch(IPatternDetails patternDetails, KeyCounter[] inputTotal, long craftCount) {
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
        if (!host.canAcceptPlans()) {
            return false;
        }
        if (!delivery.bufferInputs(inputTotal)) {
            // 拒收后不能只清缓存：元件被回滚过、探测是确定性的，重算会给出同一个过大的数（多输入样板逐 key
            // 模拟时各自以为能独占整片空闲），ECO 拿着它再交一次、再被拒，同 tick 内出不去。封本 tick。
            denyParallelSlots(patternDetails);
            return false;
        }
        bookArrival(patternDetails, craftCount);
        return true;
    }

    /**
     * Books {@code jobs} arrivals of one pattern and re-arms the batch window. Both intake paths end here:
     * AE2's one-craft push, and NEO ECO's parallel hand-over that delivers a whole batch of crafts - with
     * their complete input sets - in a single call.
     */
    private void bookArrival(IPatternDetails patternDetails, long jobs) {
        long now = host.gameTimeNow();
        routeArrival(patternDetails, jobs, now);
        lastHandedPattern = patternDetails;
        idleFlushDone = false;
        lastActivityGameTime = now;
        // 元件内容刚刚变过，估算记忆值必须作废。它不是「缓存不准」这种精度问题：ECO 拿到同一 tick 的旧答案会
        // 再交付同样的份数，而本机是 all-or-nothing，于是拒绝、ECO 重试、旧答案再报同一个数——服务端主线程
        // 在这个环里出不来（表现为世界停更、除背包外任何 GUI 都打不开，因为那些都要服务端回包）。
        parallelSlots.invalidate();
        alertTicker();
        host.persistChanges();
    }

    /**
     * 把一次到货放进它该去的通道。
     *
     * <p>判定只看两件事，因为机器知道的信息就只有这两件：这一次 hand-over 带了几件，以及这个样板已在轻载
     * 通道上攒了多少。整批交付（并行接入路径）一次就是多件，直接进重载通道；单件推送先当小订单，等它累计到
     * {@link BatchTuning#PROMOTE_TO_BULK_JOBS} 再迁过去——一条大订单走 AE2 原生路径时只能靠件数认出来。</p>
     *
     * <p>迁移会把整份剩余件数原样搬过去，而不是重开一份：CPU 已经为这些件数过账，搬丢了就是少交货。
     * 搬过去的同时批龄从头算——重载通道的积压起点是这次迁移的时刻，所以迁过来的件数最多等一个
     * {@link BatchTuning#BATCH_AGE_LIMIT_TICKS}，不会继承轻载通道上已经等过的那段。</p>
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
            if (held + jobs < BatchTuning.PROMOTE_TO_BULK_JOBS) {
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

    // ---- parallel slots -----------------------------------------------------

    /**
     * 元件缓冲还能吃下多少个完整配方（用于 CPU 正在派发的那个配方）。估算算法与它的取舍都在
     * {@link ParallelSlotProbe}。
     *
     * <p>返回 {@code long}：上限是 {@link BatchTuning#MAX_ADVERTISED_PARALLEL_SLOTS}，默认不限，所以够大的元件能报出
     * {@code int} 说不出的份数。契约指名 int 的调用方走 {@code availableParallelSlots()}，那里用 int 上限
     * 单独探测一次并夹住。</p>
     */
    long availableParallelSlotsLong() {
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
    long availableParallelSlotsFor(@Nullable IPatternDetails patternDetails) {
        // 没有配方时不碰时钟：空闲机器每 tick 都会被问到这里，没必要为了一个必然为 0 的答案读世界时间。
        if (patternDetails == null) {
            return 0L;
        }
        long estimate = parallelSlots.estimate(patternDetails, host.gameTimeNow(),
                BatchTuning.MAX_ADVERTISED_PARALLEL_SLOTS);
        return Math.max(0L, estimate);
    }

    /**
     * {@link #availableParallelSlotsLong()} 夹到 {@code int}，给契约把类型写成 int 的调用方。
     *
     * <p>NEO ECO 的并行派发把槽位数写成 {@code int}，所以那条路径无论元件还有多少空间，一次交接都不可能
     * 超过 {@link Integer#MAX_VALUE} 份。这里因此按 {@code Integer.MAX_VALUE} 单独探测一次，而不是先算 long
     * 再夹：按 long 去算会用 {@link BatchTuning#MAX_ADVERTISED_PARALLEL_SLOTS} 当探测值，而这条路径根本用不到那么多，
     * 多出来的部分只是让元件的模拟插入变贵。一个 tick 能装下几份配方与此无关——摊销路径是按块而不是按份数
     * 计一次交接的。</p>
     */
    int availableParallelSlots() {
        var patternDetails = currentDispatchPattern();
        if (patternDetails == null) {
            return 0;
        }
        long estimate = parallelSlots.estimate(patternDetails, host.gameTimeNow(), Integer.MAX_VALUE);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, estimate));
    }

    /**
     * 封本 tick 的可交付份数：两条契约各按自己的上限封一次。
     *
     * <p>只封一个档位不够：刚拒收的那条路径用的可能是另一个上限，另一档仍会拿着未过期的旧值让供给方再交付。</p>
     */
    private void denyParallelSlots(IPatternDetails patternDetails) {
        long now = host.gameTimeNow();
        parallelSlots.denyFor(patternDetails, now, BatchTuning.MAX_ADVERTISED_PARALLEL_SLOTS);
        parallelSlots.denyFor(patternDetails, now, Integer.MAX_VALUE);
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

    // ---- ticker -------------------------------------------------------------

    void alertTicker() {
        if (tickerAwake) {
            return;
        }
        // 叫醒成功后立刻记上：同一 tick 里后续的到货就不用再各叫一次了。
        host.gridNodeManager().ifPresent((grid, node) -> tickerAwake = grid.getTickManager().alertDevice(node));
    }

    /** 记下这一 tick 之后 ticker 醒不醒；返回原值，调用点照旧直接 return。 */
    private TickRateModulation modulated(TickRateModulation modulation) {
        tickerAwake = modulation != TickRateModulation.SLEEP;
        return modulation;
    }

    /** 节点状态变化 / 读档后放行下一次 alert：旧状态不一定还成立。 */
    void resetTicker() {
        tickerAwake = false;
    }

    /** 让交付侧能把「刚刚干过活」记到活动时钟上。 */
    void noteActivity(long now) {
        lastActivityGameTime = now;
        idleFlushDone = false;
    }

    /** 新插入一张元件时给闲置回灌再开一次机会。 */
    void setIdleFlushDone(boolean value) {
        idleFlushDone = value;
    }

    /** 配方池重建后，旧的「最后交接配方」不该再拿来量下一次交付。 */
    void clearLastHandedPattern() {
        lastHandedPattern = null;
    }

    // ---- batch window -------------------------------------------------------

    /** Idle ticks that flush a batch for the currently selected mode. */
    int batchIdleTicks() {
        return fastBatchMode ? BatchTuning.FAST_BATCH_TICKS : BatchTuning.STANDARD_BATCH_TICKS;
    }

    // 窗口口径（整窗、短窗、整窗探针、久停复位）随通道走，见 {@link BatchLane#effectiveWindowTicks(int)}。

    boolean isFastBatchMode() {
        return fastBatchMode;
    }

    /** Switches between the standard (40 tick) and fast (10 tick) batch delay. */
    void setFastBatchMode(boolean fastBatchMode) {
        this.fastBatchMode = fastBatchMode;
        // The window itself is left alone: it measures the gap since the last accepted input, and changing
        // the threshold must not pretend that material just arrived. Re-arming the ticker is enough, the
        // new threshold is evaluated on the next tick.
        host.persistChanges();
        alertTicker();
    }

    /** 读档用：只回填字段，不触发落盘 / alert 这些运行期副作用。 */
    void setFastBatchModeLoaded(boolean fastBatchMode) {
        this.fastBatchMode = fastBatchMode;
    }
}
