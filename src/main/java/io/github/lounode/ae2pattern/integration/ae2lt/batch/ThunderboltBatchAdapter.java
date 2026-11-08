package io.github.lounode.ae2pattern.integration.ae2lt.batch;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.api.crafting.batch.BatchDispatchMode;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;

/**
 * 把批处理装配室接到 Thunderbolt 的 CPU 批量派发（{@code BatchExecutor.runBatchOnly}）。
 *
 * <p><b>形状照上游的现成适配器抄</b>：{@code NeoEcoFastPathBatchAdapter} 负责身份转发与缓存策略，
 * {@code UselessScaledBatchAdapter} 负责「单份模板 × 份数 → 自有副本」。本仓的
 * {@code BatchAssemblerParallelIntake#dispatchScaled} 早就有同一段缩放与溢出检查，这里只是把返回值
 * 换成 Thunderbolt 要的口径。机器侧不需要新增任何入口。</p>
 *
 * <p><b>上游与自己三条批量路径的差别</b>：ECO / OmniSequence / InsaneAE 都是 CPU 把 N 份材料一次交给
 * provider；Thunderbolt 是 CPU 自己 {@code bulkExtract} 把材料从库存扣走、按 dispatched 记账、
 * 剩余份数 {@code reinject} 退回，provider 只拿到<b>一份只读模板</b>和份数。所以这里必须把模板缩放成
 * 总量，再交给机器自己的整批收料口——总量上 CPU 扣掉的与机器收下的是同一批材料，账本守恒。</p>
 */
public final class ThunderboltBatchAdapter implements BatchProviderResolver {

    /**
     * 端点只持有机器引用，不持有 job / pattern / tick 状态，可以跨 tick 复用。
     * 上游的 {@code BatchProviderResolutionCache} 会按 provider 实例做身份缓存，并在每个物理 tick
     * 调一次 {@code beginDispatchTick}（本端点没有 tick 局部状态，用默认空实现）。
     */
    @Override
    public boolean cacheResolutionAcrossTicks() {
        return true;
    }

    /** 只对批处理装配室返回端点；其它 provider 交回 null，让上游继续查下一个适配器。 */
    @Override
    public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        return provider instanceof BatchAssemblerBlockEntity machine ? new Endpoint(machine) : null;
    }

    private static final class Endpoint implements IBatchCraftingProvider {

        private final BatchAssemblerBlockEntity machine;

        Endpoint(BatchAssemblerBlockEntity machine) {
            this.machine = machine;
        }

        // ---- 身份必须留在原机器上 -------------------------------------------------
        // 上游把端点当 ICraftingProvider 用，但 identity 仍记原 provider；这几个方法一律转发，
        // 免得 AE2 看到的是另一个对象。pushPattern 尤其要覆写：接口的默认实现会转到
        // pushBatch(..., 1)，那会绕过机器自己的单份收料判定。
        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return machine.getAvailablePatterns();
        }

        @Override
        public boolean isBusy() {
            return machine.isBusy();
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return machine.pushPattern(patternDetails, inputHolder);
        }

        @Override
        public int getPatternPriority() {
            return machine.getPatternPriority();
        }

        // ---- 批量契约 -------------------------------------------------------------

        /**
         * 元件缓冲按这块样板还能装下多少份完整配方。
         *
         * <p>上游把容量当三态用：0 不可用、1 退回普通单份路径、&gt;1 才进批量
         * （{@code usesBatchPath(capacity)}）。所以只够一份时如实报 1，不要为了走批量而虚报。</p>
         *
         * <p>这里就是 {@code UNBOUNDED} 模式下该由实现方给的预算：机器一次交接的材料一次投进元件，
         * 之后按自己的 {@code OPERATIONS_PER_RUN} 逐 tick 摊销执行，硬约束是元件的真实空位，
         * 不是 CPU 的份数预算。探测按（样板, tick）记忆、收料成功后失效，无副作用。</p>
         */
        @Override
        public long getBatchCapacity(IPatternDetails details) {
            return capacity(details);
        }

        /**
         * 一次整批交接只算 CPU 的 1 个操作。
         *
         * <p>不开它，份数会被 {@code BatchCpuAccounting.maxCopiesForBatch} 按 CPU 每 tick 的操作预算
         * 掐住，大订单又被压回「一次几百份、分几千万次交接」的规模。开之后上游仍会按
         * {@code eligibleProvider.capacity()} 夹一次 slice，不会越过本机的元件预算。</p>
         */
        @Override
        public BatchDispatchMode getBatchDispatchMode(IPatternDetails details) {
            return BatchDispatchMode.UNBOUNDED;
        }

        /**
         * 把借来的单份模板缩放成 N 份总量，全有或全无地交给机器。
         *
         * <p><b>返回值是「未接受份数」</b>，不是接受数：全收答 {@code maxCraft - count}，一份没收答
         * {@code maxCraft}。上游据此 {@code markDispatched} 扣掉已接受的材料、把剩下的
         * {@code reinject} 回 CPU 库存。本机收料是 all-or-nothing，所以只答这两个值，不产生中间值。</p>
         *
         * <p><b>{@code oneCopy} 是只读借用的模板，不能改也不能留</b>（上游指南原文）。这里逐槽复制成
         * 自己的 {@code totals} 再交给 {@code acceptPatternBatch}；机器成功时会调用
         * {@code counter.removeZeros()}，改的也是我们这份副本。溢出或模板异常时直接答
         * {@code maxCraft}，此时还没有任何东西投进元件，CPU 把整批退回是安全的。</p>
         *
         * <p><b>{@code supportsSharedBatchInputs()} 有意保持默认 false</b>：单份模板里含
         * {@code sharedPerBatch}（催化剂一类只该算一次的输入），整体乘 N 会凭空放大材料。
         * 上游在样板含共享输入时会先跳过不支持的 provider，我们不会收到那种样板；真要支持得先能
         * 区分模板里的共享项，那是另一项改动。</p>
         *
         * <p><b>异常一律不吞</b>：{@code acceptPatternBatch} 已经进入提交阶段后抛出的异常必须传给
         * {@code BatchExecutor}，由它 {@code failDispatch} 停下任务。伪装成拒收会让上游退款重试，
         * 而机器里可能已经记了账。</p>
         */
        @Override
        public long pushBatch(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft) {
            if (maxCraft <= 0L) {
                return 0L;
            }
            long count = Math.min(maxCraft, capacity(details));
            if (count <= 0L) {
                return maxCraft;
            }

            var totals = new KeyCounter[oneCopy.length];
            for (int slot = 0; slot < oneCopy.length; slot++) {
                var counter = new KeyCounter();
                totals[slot] = counter;
                var template = oneCopy[slot];
                if (template == null) {
                    continue;
                }
                for (var entry : template) {
                    long amount = entry.getLongValue();
                    if (amount <= 0L) {
                        continue;
                    }
                    // 饱和检查而不是让 long 折返：装不下就整批退回，CPU 会把材料还回库存。
                    if (amount > Long.MAX_VALUE / count) {
                        return maxCraft;
                    }
                    counter.add(entry.getKey(), amount * count);
                }
            }

            return machine.acceptPatternBatch(details, totals, count) ? maxCraft - count : maxCraft;
        }

        private long capacity(IPatternDetails details) {
            return machine.isBusy() ? 0L : machine.availableParallelSlotsFor(details);
        }
    }
}
