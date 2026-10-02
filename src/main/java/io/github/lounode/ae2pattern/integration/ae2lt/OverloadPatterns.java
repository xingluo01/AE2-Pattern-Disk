package io.github.lounode.ae2pattern.integration.ae2lt;

import net.neoforged.fml.ModList;

/**
 * AE2 Lightning Tech（modId {@code ae2lt}）的过载样板：本终端「过载」档编出来的就是它的物品。
 *
 * <p>隔离在这一包里，与其它集成同一个理由：AE2LT 缺席时这些类不会被解析，本模组照常工作——那个档
 * 根本不会出现（见 {@link #isAvailable()}）。</p>
 *
 * <p>过载样板带两个别处没有的东西：每个槽位声明自己是<b>输入还是输出</b>，以及该槽的<b>组件匹配模式</b>
 * （{@code MatchMode.STRICT} 连组件一起比，{@code ID_ONLY} 只比物品 id）。后者就是面板上那个「忽略组件
 * 匹配」开关——它是样板自身的一部分，随样板走，不需要本模组的磁盘另存一份。</p>
 */
public final class OverloadPatterns {

    /** AE2LT 的 modId。只在这里出现一次，别处一律走 {@link #isAvailable()}。 */
    private static final String MOD_ID = "ae2lt";

    private OverloadPatterns() {
    }

    /**
     * AE2LT 是否在场。
     *
     * <p>档位的可用性按它判：过载样板是那个模组的物品，它不在时装不了也用不上。这里只问 ModList、
     * 不探类——缺席的可选模组不该让本模组在加载期去定义它的类。</p>
     */
    public static boolean isAvailable() {
        return ModList.get().isLoaded(MOD_ID);
    }
}
