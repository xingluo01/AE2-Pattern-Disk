package io.github.lounode.ae2pattern.integration.ae2lt.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapters;

import io.github.lounode.ae2pattern.AE2PatternDisk;

/**
 * 把批处理装配室登记进 Thunderbolt 的全局批量 provider 适配器表。
 *
 * <p><b>只在目标模组在场时加载</b>——本类编译期引用它的类型，缺席时加载会 {@code NoClassDefFoundError}。
 * 调用点的守卫见 {@link ThunderboltBatchSoftDep}。</p>
 *
 * <h2>为什么走注册表</h2>
 *
 * <p>Thunderbolt 的 CPU 派发器要求 provider 实现 {@code IBatchCraftingProvider}，但那会让可选依赖的
 * 类型名进到总是加载的机器类里。上游 2.0.3 的 {@code BatchProviderAdapters} 正是给这种情形用的：
 * {@code BatchProviderResolutionCache} 先认原生实现，否则按优先级降序查这张表，第一个返回非 null
 * 的适配器赢。本仓把机器包装成 {@link ThunderboltBatchAdapter} 交出去，机器类一行都不用改。</p>
 *
 * <p>注册时机按上游 {@code docs/global-batch-provider-adapters.zh-CN.md} 的约定：commonSetup 的串行
 * {@code enqueueWork}。注册表是纯静态表，CPU 在派发时才查，所以晚于模组构造期登记没有时序问题。</p>
 */
public final class ThunderboltBatchIntegration {

    /** 带命名空间前缀，避免与其它集成撞名；上游对重复 id 直接抛异常。 */
    private static final ResourceLocation ADAPTER_ID =
            ResourceLocation.fromNamespaceAndPath(AE2PatternDisk.MOD_ID, "batch_assembler");

    /**
     * 内置兼容适配器用优先级 -100，这里只精确匹配本模组自己的机器类，给高一点确保别的宽谓词不会抢先。
     */
    private static final int PRIORITY = 1000;

    private static final Logger LOGGER =
            LoggerFactory.getLogger("ae2_pattern_disk.integration.ae2lt.batch");

    private ThunderboltBatchIntegration() {}

    /**
     * 登记适配器。整体裹一层：这是可选集成，任何失败都只该让这一个集成退场，不该把启动带下去。
     *
     * <p>{@code catch} 要兜的是两种具体情形：Thunderbolt 2.0.3 之前<b>没有</b>这张注册表
     * （{@code NoClassDefFoundError}，属于 {@link Throwable} 而非 {@link Exception}），以及同一个 id
     * 被重复登记时上游抛的 {@code IllegalArgumentException}。</p>
     */
    public static void register() {
        try {
            BatchProviderAdapters.register(ADAPTER_ID, PRIORITY, new ThunderboltBatchAdapter());
            LOGGER.info("[AE2-Pattern-Disk] Batch assembler registered for Thunderbolt batch dispatch "
                    + "(needs Thunderbolt Core 2.0.3 or newer)");
        } catch (Throwable absent) {
            LOGGER.warn("[AE2-Pattern-Disk] Thunderbolt batch dispatch could not be registered; the batch "
                    + "assembler keeps AE2's one-craft dispatch (needs Thunderbolt Core 2.0.3 or newer)", absent);
        }
    }
}
