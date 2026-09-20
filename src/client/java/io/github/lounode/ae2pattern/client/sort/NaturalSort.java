package io.github.lounode.ae2pattern.client.sort;

import java.util.Comparator;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.SortDir;
import appeng.api.stacks.AEKey;

/**
 * 「数值排序」——按 mod 排序时出现的二级排序口径，以及它在当前屏幕上的开关状态。
 *
 * <p>默认的按 mod 排序在组内比的是名字的字符串（于是 {@code 16k} 排在 {@code 1k} 前面）；打开数值排序后
 * 组内改按 {@link NaturalOrder} 比，容量与编号才排得对。开关只认本模组自己那两个终端（
 * {@link Provider}），AE2 自己的终端不受影响。</p>
 *
 * <p>状态挂在屏幕上而不是全局静态字段：屏幕关掉即失效，不会把上一个终端的选择留给下一个终端。</p>
 */
public final class NaturalSort {

    /** 打开着的屏幕是否开着「数值排序」。本模组两个终端实现它，mixin 只认它们。 */
    public interface Provider {
        boolean naturalSortEnabled();
    }

    private NaturalSort() {
    }

    /** 当前打开的屏幕要不要数值排序；不是本模组的终端（或没开屏）一律不要。 */
    public static boolean activeOnCurrentScreen() {
        return Minecraft.getInstance().screen instanceof Provider provider && provider.naturalSortEnabled();
    }

    /** AE2「按 mod」档在开关打开时用这个：先按 mod 分组，组内按名字的数值序。 */
    public static Comparator<AEKey> aeKeysByMod(SortDir dir) {
        var ascending = Comparator.comparing(AEKey::getModId, String::compareToIgnoreCase)
                .thenComparing(key -> key.getDisplayName().getString(), NaturalOrder.strings());
        return dir == SortDir.DESCENDING ? ascending.reversed() : ascending;
    }

    /** 物品所属 mod 的 id；查不到注册名时给空串（排在所有真 mod 之前，行为与 AE2 的空 id 一致）。 */
    public static String modIdOf(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getNamespace();
    }

    /**
     * 物品名字的两种口径：{@code natural = false} 是 AE2 那套字面序（大小写不敏感），
     * {@code true} 是数值序。方向由 {@code dir} 决定。
     */
    public static Comparator<String> names(SortDir dir, boolean natural) {
        Comparator<String> ascending = natural
                ? NaturalOrder.strings()
                : String::compareToIgnoreCase;
        return dir == SortDir.DESCENDING ? ascending.reversed() : ascending;
    }
}
