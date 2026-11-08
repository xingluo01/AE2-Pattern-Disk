package io.github.lounode.ae2pattern.integration.insaneae;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.main.taikun.insaneae.quantum.BulkCraftingProviderAdapters;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;

/**
 * InsaneAE 集成的落地点：把批处理装配室登记进它的批量 provider 适配器注册表。
 *
 * <p><b>只在目标模组在场时加载</b>——本类编译期引用它的类型，缺席时加载会 {@code NoClassDefFoundError}。
 * 调用点的守卫见 {@link InsaneAESoftDep}。</p>
 *
 * <h2>为什么是注册适配器而不是让机器 implements</h2>
 *
 * <p>上游原本只认 {@code provider instanceof IBulkCraftingProvider}，而 AE2 交给 CPU 的那个对象就是本模组
 * 在模组构造期注册的方块实体。给总是加载的机器类加上 InsaneAE 的接口，等于把可选依赖变成硬依赖：没装
 * InsaneAE 的整合包会在加载阶段炸。这是本模组向上游提
 * <a href="https://github.com/taikun24/InsaneAE/issues/19">issue #19</a> 的起因；上游在 1.1.3 合入了
 * {@code BulkCraftingProviderAdapters}（{@code instanceof} 落空时才查表），并在
 * {@code BulkCraftingHandler} 的 javadoc 里写清了材料与容量的契约。</p>
 *
 * <h2>登记表只在这里碰</h2>
 *
 * <p>注册表是静态表、按类匹配且带子类回溯（最近的父类优先），本模组只在启动时登记一次、没有卸载路径。
 * 上游按 provider 实例做弱键缓存适配器，同一台机器在存活期间拿到的是同一个 handler——窗口之间的
 * {@code settleCompletedOutputs()} 要靠这个身份认人，所以工厂每次都返回新对象的写法在这里不行。</p>
 */
public final class InsaneAEIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.insaneae");

    private InsaneAEIntegration() {}

    /**
     * 登记批量适配器。整体裹一层：这是可选集成，任何失败都只该让这一个集成退场，不该把启动带下去。
     *
     * <p>{@code catch} 要兜的是一种具体情形：InsaneAE 1.1.3 之前<b>没有</b>这张注册表，而
     * {@link InsaneAESoftDep} 只能看 modid、看不出新旧，于是对旧版本引用那个类会在这里抛
     * {@code NoClassDefFoundError}——那是 {@link Throwable} 而不仅是 {@link Exception}。</p>
     *
     * <p>工厂写成 lambda 而不是方法引用，是为了让 {@link InsaneAEBulkHandler}（它 {@code implements}
     * 上游接口）的加载发生在这段受保护的调用之后：旧版本上会先在 {@code register} 那一句失败，
     * handler 类根本不会被碰。</p>
     */
    public static void register() {
        try {
            BulkCraftingProviderAdapters.register(
                    BatchAssemblerBlockEntity.class,
                    be -> new InsaneAEBulkHandler(be));
            LOGGER.info("[AE2-Pattern-Disk] Batch assembler registered for InsaneAE bulk crafting "
                    + "(needs InsaneAE 1.1.3 or newer)");
        } catch (Throwable absent) {
            // 旧版本 InsaneAE 没有适配器注册表；本机退回 AE2 原来的单份派发，其余功能不受影响。
            LOGGER.warn("[AE2-Pattern-Disk] InsaneAE bulk crafting could not be registered; the batch "
                    + "assembler keeps AE2's one-craft dispatch (needs InsaneAE 1.1.3 or newer)", absent);
        }
    }
}
