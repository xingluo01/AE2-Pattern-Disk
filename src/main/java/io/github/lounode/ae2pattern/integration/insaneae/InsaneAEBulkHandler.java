package io.github.lounode.ae2pattern.integration.insaneae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

import jp.main.taikun.insaneae.quantum.BulkCraftingHandler;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;

/**
 * 把批处理装配室的整批收单面呈现给 InsaneAE 的 Quantum CPU。
 *
 * <p>只负责<b>容量与执行</b>：上游明说忙碌态（{@code isBusy}）与 provider 的身份都留在原对象上判定，
 * 适配器不必、也不应转发 {@code ICraftingProvider} 的那几个方法。这也是它与
 * {@code integration/omnisequence/OmniBatchAdapter} 的形状差异：万象构序要求适配器把 AE2 的三个方法
 * 一起转发出去，InsaneAE 只要这两个。</p>
 *
 * <p><b>为什么不是让机器 implements</b>：InsaneAE 是可选依赖，机器类里不能出现它的类型。上游 1.1.3 的
 * 适配器注册口（issue #19）就是为这种情形加的，注册动作在 {@link InsaneAEIntegration}。</p>
 *
 * <p><b>一个 handler 对应一台机器、且一直复用</b>：上游按 provider 实例做弱键缓存，窗口之间的
 * {@code settleCompletedOutputs()} 靠对象身份认人。本类只持有机器引用，不另存跨窗口的计数状态——
 * 那份状态本来就在机器的元件缓冲与回送队列里。</p>
 */
final class InsaneAEBulkHandler implements BulkCraftingHandler {

    private final BatchAssemblerBlockEntity machine;

    InsaneAEBulkHandler(BatchAssemblerBlockEntity machine) {
        this.machine = machine;
    }

    /**
     * 元件缓冲按这块样板还能装下多少份完整配方。
     *
     * <p>上游在「选 provider」「算本窗上限」「同 tick 再开一窗」时会连着问好几次，契约要求无副作用且轻。
     * 这里走的是 {@code ParallelSlotProbe.estimate}：按（样板, 游戏刻, 上限）记忆，同一 tick 的重复询问
     * 直接命中缓存；材料真的写进元件后 {@code bookArrival} 会 {@code invalidate()}，下一次问到的是新值
     * （拒收时则由 {@code denyParallelSlots} 把本 tick 封成 0，避免上游拿着旧答案反复重试）。</p>
     *
     * <p><b>它就是 {@code fusesOperations() == true} 时该由实现方给的预算</b>：本机一次交接的材料一次
     * 投进元件，之后按自己的 {@code OPERATIONS_PER_RUN} 逐 tick 摊销执行（一个摊销块算一个操作，与份数
     * 无关），所以「每 tick 操作数」不是份数上限；真正的硬约束是元件里还剩多少空位，正是这个方法返回的数。
     * 上游自己还会按完成品数量与 long 账本余量再夹一次，两边不冲突。</p>
     */
    @Override
    public long getBulkCapacity(IPatternDetails details) {
        return machine.availableParallelSlotsFor(details);
    }

    /**
     * 一次收下 {@code times} 份的完整材料，全有或全无。
     *
     * <p>{@code inputHolder} 是上游按样板输入槽给出的<b>总量</b>（{@code times} 回份，不是单份），与
     * {@code acceptPatternBatch} 的 {@code inputTotal} 同口径，不需要缩放或重排。这与 NEO ECO 的并行
     * 交付面一致。</p>
     *
     * <p>契约里最要紧的一条：<b>返回 0 时不能碰 {@code inputHolder}</b>，因为调用方会把它原样退回 CPU。
     * 本机的 {@code bufferInputs} 只在全部成功之后才对传入的计数器做 {@code removeZeros()}；失败路径
     * 先把已投进元件的部分抽回来，中途只会读这些计数器（{@code totalsByKey} 生成新表），一个键都不改。
     * 于是「收下就答 {@code times}、没收下就答 0」在这里是安全的，不需要处理 {@code done} 落在中间时
     * 把剩余材料留在 holder 里的那套写法。</p>
     */
    @Override
    public long pushPatternBulk(IPatternDetails details, KeyCounter[] inputHolder, long times) {
        return machine.acceptPatternBatch(details, inputHolder, times) ? times : 0L;
    }

    /**
     * 一次整批交接只算 CPU 的 1 个操作。
     *
     * <p>这正是本集成要接上的那条路：不开它，上游会把份数按 {@code maxPatterns}（CPU 每 tick 的 int
     * 预算）掐一次，大订单又被压回「一次几百份、分几千万次交接」的规模。开了之后份数只受
     * {@link #getBulkCapacity} 约束，而那边已经是本机自己的元件预算。</p>
     */
    @Override
    public boolean fusesOperations() {
        return true;
    }

    /**
     * 把已经攒下的产物交回网络，让 CPU 的完成待ち能清算。
     *
     * <p>与万象构序的记账后钩子共用机器上同一个入口：本机的产物故意走平滑回送队列，不一次砸进网络，
     * 而 CPU 会把「还没送到网络」的产物算成 {@code waitingFor} 未满足。上游在窗口之间（以及 BigInteger
     * 精确路径的周回之间）调这里，把队列里已经拥有的产物交出去，不重跑任何配方。</p>
     */
    @Override
    public void settleCompletedOutputs() {
        machine.flushReturnsAfterCpuAccounting();
    }

    /**
     * {@code repeatsWindowsWithinTick()} 有意保持默认的 {@code false}：本机九格元件缓冲是各层配方共用的，
     * 允许同一样板在同一 tick 里连开窗口，会把还没来得及执行的那一层材料一次性堆满缓冲、把上层链饿死。
     * 每 tick 每样板一窗已经能覆盖本模组面向的 9 层 9:1 压缩大单（底层约 8.13e17 件，在 long 内）；
     * 真要开多窗口，得先让清算能把产物腾出的空位同步回容量估算，再单独实机验证多层链的推进。
     */
}
