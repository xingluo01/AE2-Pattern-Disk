package io.github.lounode.ae2pattern.integration.ae2lt.batch;

import net.neoforged.fml.ModList;

/**
 * Thunderbolt Core（{@code thunderbolt}）的软依赖入口。
 *
 * <p>批量派发的 API 在 Thunderbolt Core 里，不在 AE2LT 本体里；AE2LT 把它声明成
 * {@code [2.0.3,3.0.0)} 的必需前置，所以按 {@code thunderbolt} 门禁既能覆盖「只装库」的情形，
 * 也不会漏掉装了 AE2LT 的整合包。与 {@code OmniSequenceSoftDep} 同一套做法：产品代码只认字符串，
 * 不认它的类——{@link ThunderboltBatchIntegration} 编译期引用上游类型，缺席时加载会
 * {@code NoClassDefFoundError}。</p>
 *
 * <p><b>版本这一层它管不了</b>：{@code BatchProviderAdapters} 是 2.0.3 才有的，光看 modid 认不出新旧。
 * 真正的兜底在 {@link ThunderboltBatchIntegration#register()} 的 {@code catch}——装更旧的
 * Thunderbolt 时引用不到注册表，安静退场，机器保持 AE2 原来的逐份派发。</p>
 */
public final class ThunderboltBatchSoftDep {

    /** 目标模组 id。产品代码只认这个字符串，不认它的类。 */
    public static final String MOD_ID = "thunderbolt";

    private static volatile Boolean present;

    private ThunderboltBatchSoftDep() {}

    /** 目标模组是否在场。集成类是否加载，以它为前提。 */
    public static boolean isLoaded() {
        var cached = present;
        if (cached == null) {
            cached = ModList.get().isLoaded(MOD_ID);
            present = cached;
        }
        return cached;
    }
}
