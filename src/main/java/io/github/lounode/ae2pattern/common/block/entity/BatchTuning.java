package io.github.lounode.ae2pattern.common.block.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.lounode.ae2pattern.common.logic.BatchLane;
import io.github.lounode.ae2pattern.common.logic.ParallelSlotProbe;

/**
 * 批处理装配室的调参常量、它们的取值口径，以及类装载时那次调参日志。
 *
 * <p>这些值原本散在方块实体里，注释动辄上百行，把「这台机器到底能跑多快」这件一句话能讲清的事埋没在
 * 一千多行里。它们不是房间里的运行态，而是启动参数（{@code -Dae2pattern.xxx=N}）解析出来的配置，于是整块
 * 搬到这里：值、字面量、解析规则与注释一字不动，只把「配置」与「运行」分开。日志类别仍用
 * {@code BatchAssemblerBlockEntity.class}，且仍由方块实体的静态块在类装载时触发，时序不变。</p>
 */
final class BatchTuning {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchAssemblerBlockEntity.class);

    private BatchTuning() {
    }

    /** Flush window: idle ticks after the last input that trigger a batch, regardless of queue size. */
    static final int STANDARD_BATCH_TICKS = 40;
    static final int FAST_BATCH_TICKS = 10;
    // 分批口径（短窗、整窗探针、久停复位、分批大小）跟着通道走，见 {@link BatchLane}：它们现在是每条通道
    // 各自的状态，不再是整台机器共用的一份。
    /** Quiet ticks after which an idle machine hands whatever its cells still hold back to the network. */
    static final int IDLE_FLUSH_TICKS = 10;

    /** Upper bound on what a claimed key hands over in one tick (never less than the smooth rate). A
     * catalyst-sized amount therefore arrives in full on the spot, while a huge batch still never becomes
     * one giant IO burst - the reason the smooth return exists at all. */
    static final long PRIORITY_RETURN_BURST = 512;
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
    static final int OUTPUT_RETURN_TICKS =
            Math.max(1, Integer.getInteger("ae2pattern.outputReturnTicks", 1));

    static final double ENERGY_PER_RUN = 10.0;

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
     * 悄悄改变对照实验里另一组的结果，所以启动时请核对 {@code logEffectiveTuning} 打出的实际值。小于等于零
     * 按 1 处理：一次交接一份配方，只用于对照实验的下限组。默认值必须写成原始数字字面量（{@code int} 或
     * {@code long} 都行）：这样才能选中 {@code getLong(String, long)}，它不会装箱。要小心另外两个会装箱的重载
     * ——{@code getLong(String, Long)} 遇到畸形值返回的是你传进去的默认值（不是 null），而单参的
     * {@code getLong(String)} 才真的返回 null，对它拆箱会抛异常。</p>
     */
    static final long MAX_ADVERTISED_PARALLEL_SLOTS =
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
     * see {@code logEffectiveTuning}.</p>
     */
    static final int OPERATIONS_PER_RUN =
            Math.max(1, Integer.getInteger("ae2pattern.operationsPerRun", 128));

    /**
     * 两条通道同时就绪时轻载通道最多用掉的操作数，其余留给重载通道。
     *
     * <p>只有一条就绪时它拿满 {@link #OPERATIONS_PER_RUN}：份额是为了让两边同时跑时不被对方挤掉一整轮，
     * 不是给任意一方设上限。默认对半分。{@code OPERATIONS_PER_RUN} 被调到 1 时轻载通道会拿走那唯一一个操作，
     * 重载通道本轮让位、下一轮再跑——这是该极端配置下唯一的交接方式。</p>
     *
     * <p>Overridable with {@code -Dae2pattern.lightOperationShare=N}; same parsing and same fallback as the
     * other tuning properties - see {@code logEffectiveTuning}.</p>
     */
    static final int LIGHT_OPERATION_SHARE =
            Math.max(1, Integer.getInteger("ae2pattern.lightOperationShare", OPERATIONS_PER_RUN / 2));

    /** 一个样板在轻载通道累计到这么多件就迁到重载通道：单件推送的大订单只能靠件数认出来。 */
    static final long PROMOTE_TO_BULK_JOBS =
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
    static final int BATCH_AGE_LIMIT_TICKS =
            Math.max(0, Integer.getInteger("ae2pattern.batchAgeLimitTicks", 120));

    /**
     * Most item stacks a block break may scatter for one leftover key.
     *
     * <p>Splitting a very large leftover the ordinary way creates one entity per stack - a machine holding a
     * billion leftover items would scatter fifteen million of them and take the server with it. Past this many
     * stacks the remainder is carried in oversized stacks instead, which keeps the material rather than
     * dropping it and produces a handful of entries instead of millions.</p>
     */
    static final int MAX_LEFTOVER_DROP_STACKS =
            Math.max(1, Integer.getInteger("ae2pattern.maxLeftoverDropStacks", 1024));

    /**
     * Reports the tuning values at class load, but only for the properties that were actually set. All are read
     * with {@code Integer.getInteger} - or {@code Long.getLong} for the parallel-slot ceiling - which fall back to
     * the default on a malformed value without saying so, and the difference decides how many hand-overs an order
     * costs, how much of a tick a batch may spend, or whether output is trickled at all. Printing only what was
     * overridden makes a mistyped launch argument visible while keeping a default launch silent, which is what a
     * release build has to be.
     *
     * <p>由 {@code BatchAssemblerBlockEntity} 的静态块调用：方块实体类装载时打一次，时序与原实现一致。</p>
     */
    static void logEffectiveTuning() {
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
}
