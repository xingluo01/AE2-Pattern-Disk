package io.github.lounode.ae2pattern.integration.insaneae;

import net.neoforged.fml.ModList;

/**
 * InsaneAE（{@code insaneae}）的软依赖入口。
 *
 * <p>本模组把它的批量合成入口当作<b>可选</b>集成面：只有在场时才加载 {@link InsaneAEIntegration}，
 * 那个类在编译期引用它的类型，缺席时加载会 {@code NoClassDefFoundError}。与
 * {@code OmniSequenceSoftDep} / {@code AecsSoftDep} 同一套做法：产品代码只认字符串，不认它的类。</p>
 *
 * <p><b>版本这一层它管不了</b>：适配器注册口是 1.1.3 才有的，光看 modid 认不出新旧。真正的兜底在
 * {@link InsaneAEIntegration#register()} 的 {@code catch}——装了 1.1.2 及更早的版本时引用不到
 * {@code BulkCraftingProviderAdapters}，注册安静退场，机器回到 AE2 原来的逐份派发。
 * {@code neoforge.mods.toml} 里对应的可选依赖因此写 {@code *} 而不是 {@code [1.1.3,)}：后者会让装了旧版
 * InsaneAE 的整合包连本模组都起不来，而那不过是少一个可选集成。</p>
 */
public final class InsaneAESoftDep {

    /** 目标模组 id。产品代码只认这个字符串，不认它的类。 */
    public static final String MOD_ID = "insaneae";

    private static volatile Boolean present;

    private InsaneAESoftDep() {}

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
