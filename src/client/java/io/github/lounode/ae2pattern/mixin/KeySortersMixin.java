package io.github.lounode.ae2pattern.mixin;

import java.util.Comparator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.stacks.AEKey;

import io.github.lounode.ae2pattern.client.sort.NaturalSort;

/**
 * AE2 终端物品网格的「按 mod」排序在组内比的是名字字符串，于是 16k 排在 1k 前面。这里在比较器构造处插一脚：
 * 只有当前屏幕是开了「数值排序」的本模组终端、且排序档位是 mod 时，才换成组内数值序。
 *
 * <p>目标类是包级私有的，所以走 {@code targets} 字符串形式，且只注入公开的 {@code getComparator}；AE2 若改了
 * 这个方法的签名，注入失败也只是本功能静默失效（{@code require = 0} + 配置 {@code required: false}），
 * 不会让游戏起不来。</p>
 */
@Mixin(targets = "appeng.client.gui.me.common.KeySorters")
public abstract class KeySortersMixin {

    @Inject(
            method = "getComparator(Lappeng/api/config/SortOrder;Lappeng/api/config/SortDir;)Ljava/util/Comparator;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void ae2PatternDisk$naturalOrderForModSort(SortOrder order, SortDir dir,
            CallbackInfoReturnable<Comparator<AEKey>> callback) {
        if (order == SortOrder.MOD && NaturalSort.activeOnCurrentScreen()) {
            callback.setReturnValue(NaturalSort.aeKeysByMod(dir));
        }
    }
}
