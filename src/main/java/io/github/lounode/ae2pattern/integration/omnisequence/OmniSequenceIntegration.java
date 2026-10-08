package io.github.lounode.ae2pattern.integration.omnisequence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.networking.crafting.ICraftingProvider;

import com.atir.molecularmanipulator.api.crafting.OmniBatchProviderAdapterRegistry;
import com.atir.molecularmanipulator.api.crafting.OmniPostAccountingOutputAdapterRegistry;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;

/**
 * 万象构序集成的落地点：把批处理装配室登记进它的两个适配器注册表。
 *
 * <p><b>只在目标模组在场时加载</b>——本类编译期引用它的类型，缺席时加载会 {@code NoClassDefFoundError}。
 * 调用点的守卫见 {@link OmniSequenceSoftDep}。</p>
 *
 * <h2>登记了什么</h2>
 *
 * <ul>
 *   <li>{@code OmniBatchProviderAdapterRegistry}：核心那条——CPU 一次分配多份<b>完整</b>配方。这是本模组
 *       当初向上游提 issue #4 要的入口，上游在 2.0.7 补上了。</li>
 *   <li>{@code OmniPostAccountingOutputAdapterRegistry}：记账后补送一次产物。本机的产物走平滑回传队列
 *       （故意分摊到多 tick，见 {@code SmoothReturnQueue}），而 CPU 会把「还没送到网络」的产物算成
 *       {@code waitingFor} 未满足、于是停摆。上游这个钩子正是在它记完账、要判 waitingFor 之前问一次——
 *       把队列里已经拥有的产物交出去，不重跑任何配方，与钩子的约定一致。</li>
 * </ul>
 *
 * <h2>为什么登记表只在这里碰</h2>
 *
 * <p>两个注册表都是静态表、且都带 {@code unregister}。本模组只在启动时登记一次、没有卸载路径，所以不需要
 * 反注册；写在这里而不是机器类里，是为了让机器那一侧只留下「按样板报容量」「整批收单」这两个自己的入口，
 * 不认识万象构序。</p>
 */
public final class OmniSequenceIntegration {

    /**
     * 适配器 id。上游按「优先级降序、再按 id 升序」取首个匹配，登记的 id 是替换键——带模组前缀，避免与
     * 别的集成撞名。
     */
    private static final String BATCH_ID = "ae2_pattern_disk:batch_assembler";
    private static final String FLUSH_ID = "ae2_pattern_disk:batch_assembler_flush";

    /**
     * 优先级。这台机器的适配器只匹配本模组自己的机器类，撞不上别人，所以数字取大一点就行——它的作用是
     * 万一以后有人注册了一个更宽的谓词，本机仍然走自己这条精确匹配。
     */
    private static final int PRIORITY = 1000;

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.omnisequence");

    private OmniSequenceIntegration() {}

    /**
     * 登记两个适配器。整体裹一层：这是可选集成，任何失败都只该让这一个集成退场，不该把启动带下去。
     *
     * <p>{@code catch} 要兜的是一种具体情形：万象构序 2.0.7 之前<b>没有</b>批量契约的注册表，而
     * {@link OmniSequenceSoftDep} 只能看 modid、看不出新旧，于是对老版本引用那个类会在这里抛
     * {@code NoClassDefFoundError}——那是 {@link Throwable} 而不仅是 {@code Exception}。</p>
     */
    public static void register() {
        try {
            OmniBatchProviderAdapterRegistry.register(
                    BATCH_ID,
                    PRIORITY,
                    BatchAssemblerBlockEntity.class::isInstance,
                    provider -> new OmniBatchAdapter((BatchAssemblerBlockEntity) provider));
            OmniPostAccountingOutputAdapterRegistry.register(
                    FLUSH_ID,
                    PRIORITY,
                    BatchAssemblerBlockEntity.class::isInstance,
                    OmniSequenceIntegration::flushReturns);
            LOGGER.info("[AE2-Pattern-Disk] Batch assembler registered for OmniSequence batch delivery");
        } catch (Throwable absent) {
            // 老版本万象构序（2.0.7 之前）没有批量契约的注册表；本机退回 AE2 原来的单份派发，其余功能不受影响。
            LOGGER.warn("[AE2-Pattern-Disk] OmniSequence batch delivery could not be registered; the batch "
                    + "assembler keeps AE2's one-craft dispatch (needs OmniSequence 2.0.7 or newer)", absent);
        }
    }

    /**
     * 记账后把已经攒下的产物补送一次。
     *
     * <p>只做「交出已经拥有的产物」：队列里没有东西时连网络都不碰。</p>
     */
    private static void flushReturns(ICraftingProvider provider) {
        if (provider instanceof BatchAssemblerBlockEntity machine) {
            machine.flushReturnsAfterCpuAccounting();
        }
    }
}
