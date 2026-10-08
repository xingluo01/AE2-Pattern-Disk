package io.github.lounode.ae2pattern.integration.omnisequence;

import net.neoforged.fml.ModList;

/**
 * 万象构序（{@code molecularmanipulator}）的软依赖入口。
 *
 * <p>本模组把它的 Omni Batch Provider API 当作<b>可选</b>集成面：只有在场时才加载
 * {@link OmniSequenceIntegration}——那个类在编译期引用它的类型，缺席时加载它会 {@code NoClassDefFoundError}。
 * 与 {@code AecsSoftDep} / {@code ExtendedAEPlusCompat} 同一套做法：产品代码只认字符串，不认它的类。</p>
 *
 * <p><b>版本这一层它管不了</b>：批量契约的适配器注册表是 2.0.7 才有的，光看 modid 认不出新旧。所以真正
 * 的兜底在 {@link OmniSequenceIntegration#register()} 那个 {@code catch}——老版本上引用不到那个类时它安静
 * 退场，而不是把启动带下去。{@code neoforge.mods.toml} 里对应的可选依赖因此写 {@code *} 而不是
 * {@code [2.0.7,)}：后者会让装了 2.0.6 的整合包连本模组都起不来，而那不过是少一个可选集成。</p>
 */
public final class OmniSequenceSoftDep {

    /** 目标模组 id。产品代码只认这个字符串，不认它的类。 */
    public static final String MOD_ID = "molecularmanipulator";

    private static volatile Boolean present;

    private OmniSequenceSoftDep() {}

    /** 目标模组是否在场。集成类是否加载、并行槽要不要按样板报，都以它为前提。 */
    public static boolean isLoaded() {
        var cached = present;
        if (cached == null) {
            cached = ModList.get().isLoaded(MOD_ID);
            present = cached;
        }
        return cached;
    }
}
