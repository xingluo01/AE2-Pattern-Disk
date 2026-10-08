package io.github.lounode.ae2pattern.integration.omnisequence;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

import com.atir.molecularmanipulator.api.crafting.OmniBatchAdmission;
import com.atir.molecularmanipulator.api.crafting.OmniBatchCraftingProvider;
import com.atir.molecularmanipulator.api.crafting.OmniBatchDelivery;
import com.atir.molecularmanipulator.api.crafting.OmniBatchProbe;
import com.atir.molecularmanipulator.api.crafting.OmniBatchRequest;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;

/**
 * 把批处理装配室的整批收单面呈现给万象构序的 Omni Batch Provider API。
 *
 * <p><b>为什么是适配器而不是让机器 implements</b>：万象构序是可选依赖，机器类里不能出现它的类型，否则
 * 没装它的整合包里那个类会加载失败。上游从 2.0.7 起给了
 * {@code OmniBatchProviderAdapterRegistry}，正好是给这种情况用的——这也是本模组当初提
 * <a href="https://github.com/AyaYumi/OmniSequence-Transfinite/issues/4">上游 issue #4</a> 要的东西；
 * 在那之前只剩条件 mixin 一条路，而本仓已经从同一个理由上撤掉过一次 mixin（见
 * {@code NeoECOIntegration} 的类注释）。</p>
 *
 * <p><b>身份必须留在原机器上</b>：上游明确要求「返回的能力对象与 provider 分开，调用方保留原 provider 做
 * 一切与身份有关的操作」。所以下面转发的是 AE2 的那三个方法（样板清单、单份 push、忙碌态）与样板优先级，
 * 让 AE2 看到的一直是那台机器本身；只有两阶段的 {@code prepareOmniBatch} 落在这里。</p>
 *
 * <p><b>不覆写 {@code isBusy()}</b>：上游明说 CPU 只对解析出的能力对象调 {@code prepareOmniBatch}，忙碌态读的是
 * <b>原 provider</b>（AE2 也用它决定要不要再派单份），所以这里直接转发机器自己的答案。曾经想把 probe 的样板
 * 记下来供 {@code isBusy()} 用，那是错的：一旦按「这块样板凑不满两份」答忙，AE2 就再也不给这台机器派单份活了。</p>
 */
final class OmniBatchAdapter implements OmniBatchCraftingProvider {

    private final BatchAssemblerBlockEntity machine;

    OmniBatchAdapter(BatchAssemblerBlockEntity machine) {
        this.machine = machine;
    }

    // ---- 转发给机器的部分：AE2 的身份必须仍是那台机器 --------------------------

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return machine.getAvailablePatterns();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        return machine.pushPattern(patternDetails, inputHolder);
    }

    @Override
    public boolean isBusy() {
        // 一律转发：忙态是 AE2 用来决定「还要不要给这台机器派单份活」的，口径必须与机器自己一致。
        // 不能因为「这块样板凑不满两份」就答忙——那会让单份派发一起停掉。整批的容量由 prepareOmniBatch 回答。
        return machine.isBusy();
    }

    @Override
    public int getPatternPriority() {
        return machine.getPatternPriority();
    }

    // ---- 两阶段整批交接 -------------------------------------------------------

    @Override
    @Nullable
    public OmniBatchAdmission prepareOmniBatch(OmniBatchProbe probe) {
        // 上游把「一组实际选中的材料」作为容量提示给出，且明说它只是提示；这里只借它估算还能吃几份，
        // 不据此扣任何东西——真正的材料要到 commit 才到。
        long capacity = machine.availableParallelSlotsFor(probe.pattern());
        if (capacity < 2) {
            // 上游把「少于 2 份」定义为不参与整批；返回 null 即婉拒，正常单份派发不受影响。
            return null;
        }
        return new Admission(machine, Math.min(capacity, probe.requestedMaxCrafts()));
    }

    /**
     * 一次性的容量准入。
     *
     * <p>本机在 probe 阶段不预留任何东西（容量会变，而预留要能回滚），所以 {@code close} 无事可做——这与上游
     * 「probe 阶段只可只读或做可回滚的预留」的约定不冲突。</p>
     */
    private static final class Admission implements OmniBatchAdmission {

        private final BatchAssemblerBlockEntity machine;
        private final long maxCrafts;

        Admission(BatchAssemblerBlockEntity machine, long maxCrafts) {
            this.machine = machine;
            this.maxCrafts = maxCrafts;
        }

        @Override
        public long maxCrafts() {
            return maxCrafts;
        }

        @Override
        public void commit(OmniBatchDelivery delivery) {
            var request = delivery.request();
            if (request.craftCount() > maxCrafts) {
                // 上游保证交付份数不会超过我们报的上限；真超过了就是合同被违反，不接——接下去就是「答 4 收 8」。
                delivery.reject(OmniBatchDelivery.Rejection.reject(
                        OmniBatchDelivery.RejectReason.CAPACITY_CHANGED));
                return;
            }
            var inputs = inputsOf(request);
            if (inputs == null) {
                // 交付清单与本机样板的输入槽对不上（槽位越界、数量非正）——不是「输入类型不支持」的意思。
                // 一份都不能少也不能多，所以整批退回，而不是「丢掉读不懂的那条、照收剩下的」：那等于把那份
                // 材料吞了，而 Omni 已经记成已接收。
                delivery.reject(OmniBatchDelivery.Rejection.reject(
                        OmniBatchDelivery.RejectReason.UNSUPPORTED_INPUT));
                return;
            }
            // 材料一次性全投进元件缓冲：机器自己的收单入口就是全有或全无，且投不进去时它会把已投的部分
            // 抽回去（{@code bufferInputs} 的回滚分支），于是这里 reject 出去的材料本机一份都没留——
            // 与上游「reject 意味着没有不可逆改动」一致。
            if (!machine.acceptPatternBatch(request.pattern(), inputs, request.craftCount())) {
                // 容量变了而已，下 tick 可以再试；用 OTHER 会让这台机器在当前作业里退回单份。
                delivery.reject(OmniBatchDelivery.Rejection.reject(
                        OmniBatchDelivery.RejectReason.CAPACITY_CHANGED));
                return;
            }
            // 所有权报「进了本机自己的持久队列」而不是「交给了外部持久目标」：材料进的是本机自己的元件缓冲，
            // 跟着本机的元件物品落盘。契约原文的第二支就是这种情形（{@code persisted its own queue}）。
            //
            // backpressure 取 RECHECK_NEXT_TICK 而不是 MAY_ACCEPT_MORE：本机的容量估算是按（样板, 游戏刻）
            // 记忆的，而插材料不会让它失效（{@code ParallelSlotProbe} 只在配方池重建时 invalidate），
            // 所以同一 tick 对同一样板再问一次会读回插入前的数字，随后的 commit 必然投不进去。
            // 上游自家的机器也是这个节奏（接受后 block 当前 tick）。下 tick 重新报价不受影响。
            delivery.accept(new OmniBatchDelivery.Receipt(
                    OmniBatchDelivery.Ownership.PERSISTED_PROVIDER_QUEUE,
                    OmniBatchDelivery.Backpressure.RECHECK_NEXT_TICK));
        }
    }

    /**
     * 把上游的交付清单还原成 AE2 的 {@code KeyCounter[]}（按样板输入槽对位）。
     *
     * <p><b>为什么不拿 {@code probe} 乘份数</b>：上游明说交付的键与比例可能因 AE2 的替换而与
     * {@code probe × craftCount} 不同，只有这里的清单是权威的。</p>
     *
     * <p><b>读不懂就整批作废</b>（返回 {@code null}）：槽位越界或数量非正说明这条交付不是本机能接的东西。
     * 绝不能「跳过那一条、照收其余」——本机的收单入口只看计数对不对得上，发现不了少一条，结果是 Omni 记成
     * 已接收、而那份材料从没进来。按契约「禁止部分接收」，宁可整批退回。</p>
     *
     * <p>上游的 {@code Input} 构造器已经保证了 {@code slot ≥ 0}、{@code amount > 0}、{@code key != null}，
     * 所以下面那个校验是防御性的：正常情况下永远不进。</p>
     */
    @Nullable
    private static KeyCounter[] inputsOf(OmniBatchRequest request) {
        var counters = new KeyCounter[request.pattern().getInputs().length];
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new KeyCounter();
        }
        for (var input : request.inputs()) {
            if (input.slot() < 0 || input.slot() >= counters.length || input.amount() <= 0) {
                return null;
            }
            counters[input.slot()].add(input.key(), input.amount());
        }
        return counters;
    }
}
