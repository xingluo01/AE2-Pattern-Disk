package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;

/**
 * 把磁盘的标记补进物品自身的 tooltip，让玩家在背包、箱子或 JEI/EMI 里就能看到这张盘归哪台机器——
 * 不必先进终端。
 *
 * <p>为什么不写在 {@code PatternDiskItem.appendHoverText} 里：标记的可读名来自配方查看器（EMI/JEI 里
 * 的配方类别名，见 {@link PatternDiskMarks}），而那个类在客户端源集，主源集引用它会破坏「main 不得依赖
 * client」的编译期防线。物品 tooltip 本身只在客户端渲染，所以挂在客户端事件上，两边的分层都不动。</p>
 *
 * <p>行的措辞与终端磁盘列表、管理终端的磁盘格共用 {@code ae2_pattern_disk.tooltip.mark} 这一套键：
 * 说的是同一张盘，就应当叫同一个名字。</p>
 *
 * <p>这个事件在客户端启动建 tooltip 搜索树时也会被调用一次（那时没有玩家），所以处理器不能碰
 * {@code event.getEntity()}。</p>
 */
public final class PatternDiskMarkTooltip {

    private PatternDiskMarkTooltip() {
    }

    public static void appendMark(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        if (!(stack.getItem() instanceof PatternDiskItem)) {
            return;
        }

        var mark = PatternDiskMarks.displayName(stack);
        if (mark == null) {
            // 没有标记的盘（含空盘）不多一行，免得每张盘都挂一句「标记：无」。
            return;
        }

        var tooltip = event.getToolTip();
        tooltip.add(Component.translatable("ae2_pattern_disk.tooltip.mark", mark).withStyle(ChatFormatting.GRAY));

        // 只在原版高级信息（F3+H）下多给一行原始标记：机器名可能重复，排查时看 id 才准。
        // 判据用事件给的 flags，而不是 Minecraft 的选项：显式问「这次渲染要不要高级信息」，
        // 不依赖客户端单例（事件在启动建搜索树时也会跑一次）。
        if (!event.getFlags().isAdvanced()) {
            return;
        }
        var raw = PatternDiskMarks.rawMark(stack);
        if (raw != null) {
            tooltip.add(Component.translatable("ae2_pattern_disk.tooltip.mark.raw", raw)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
