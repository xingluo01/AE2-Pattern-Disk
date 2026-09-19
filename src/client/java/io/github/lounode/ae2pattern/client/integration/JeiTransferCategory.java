package io.github.lounode.ae2pattern.client.integration;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

/**
 * 单次 JEI 配方传输所属的配方类别 id。
 *
 * <p>{@code IUniversalRecipeTransferHandler} 的实现拿不到类别——JEI 用适配器把通用处理器包起来时
 * 丢掉了上下文里的 RecipeType——所以类别由 {@code IRecipeTransferListener#beforeRecipeTransfer}
 * 记在这里，通用处理器在真正传输时读走。传输结束必须清掉，否则会串到下一条磁盘标记。</p>
 */
public final class JeiTransferCategory {

    @Nullable
    private static ResourceLocation pending;

    private JeiTransferCategory() {
    }

    public static void set(@Nullable ResourceLocation category) {
        pending = category;
    }

    /** 读走当前类别并清空：处理器只消费一次，不会读到上一次传输的残留值。 */
    @Nullable
    public static ResourceLocation take() {
        var category = pending;
        pending = null;
        return category;
    }

    public static void clear() {
        pending = null;
    }
}
