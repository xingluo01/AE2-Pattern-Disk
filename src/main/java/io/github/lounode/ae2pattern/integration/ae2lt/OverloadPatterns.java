package io.github.lounode.ae2pattern.integration.ae2lt;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * AE2 Lightning Tech（modId {@code ae2lt}）的过载样板：本终端「过载」档编出来的就是它的物品。
 *
 * <p>隔离在这一包里，与其它集成同一个理由：AE2LT 缺席时这些类不会被解析，本模组照常工作——那一档
 * 也就不会出现（它的可用性看升级槽里有没有那枚编码器，见 {@link #isEncoder(ItemStack)}）。</p>
 *
 * <p>过载样板带两个别处没有的东西：每个槽位声明自己是<b>输入还是输出</b>，以及该槽的<b>组件匹配模式</b>
 * （{@code MatchMode.STRICT} 连组件一起比，{@code ID_ONLY} 只比物品 id）。后者就是面板上那个「忽略组件
 * 匹配」开关——它是样板自身的一部分，随样板走，不需要本模组的磁盘另存一份。</p>
 */
public final class OverloadPatterns {

    /** AE2LT 的 modId。 */
    private static final String MOD_ID = "ae2lt";

    /** AE2LT 的「过载样板编码器」（{@code item.ae2lt.overload_pattern_encoder}）。 */
    private static final ResourceLocation OVERLOAD_ENCODER =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "overload_pattern_encoder");

    private OverloadPatterns() {
    }

    /**
     * 这一叠升级卡里是否装着过载样板编码器——那一档的可用性就按它判，与高级档同口径（升级槽里那张卡）。
     *
     * <p>只查物品 id，不碰 AE2LT 的任何类：注册表按 id 查，模组不在场时同样安全（查不到就是 false），
     * 所以这里不会把缺席的可选模组拖进「加载期定义它的类」那个老坑。</p>
     */
    public static boolean isEncoder(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(OVERLOAD_ENCODER);
    }
}
