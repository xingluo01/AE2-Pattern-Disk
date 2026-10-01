package io.github.lounode.ae2pattern.common.util;

import java.util.List;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;

/**
 * 把一个 key 的数量摊成掉落物。
 *
 * <p><b>为什么不直接按堆叠上限铺。</b>一台机器里可能积着极大的余料——订单有多大，余料就可能有多大。
 * 按 64 一踏铺开，一百万件的余料就是一万五千多个 {@code ItemStack}、一万五千多个掉落物实体，服务端会被
 * 这一下拖垮。所以正常堆只铺到调用方给的上限，剩下的改用<b>超大堆</b>（数量超过堆叠上限的单个
 * {@code ItemStack}）承载：材料照样留在玩家手里，实体数却只有个位数。</p>
 *
 * <p>这个上限是「铺得动」与「铺得完」之间的折中，由调用方按场景定——方块被破坏时的掉落物会进世界实体，
 * 上限就该小。</p>
 */
public final class DropStacks {

    private DropStacks() {}

    /**
     * 把 {@code amount} 个 {@code key} 追加进 {@code drops}。
     *
     * @param maxNormalStacks 正常堆的条数上限；到顶后剩下的改走超大堆
     * @return 走超大堆承载的数量。{@code 0} 表示全部按正常堆铺开了；非 0 时调用方应当告警——材料没丢，
     *         但出现超大堆本身值得记一笔
     */
    public static long add(AEItemKey key, long amount, List<ItemStack> drops, int maxNormalStacks) {
        long remaining = amount;
        int perNormalStack = Math.max(1, key.getMaxStackSize());
        int stacks = 0;
        while (remaining > 0 && stacks < maxNormalStacks) {
            int perStack = (int) Math.min(remaining, perNormalStack);
            drops.add(key.toStack(perStack));
            remaining -= perStack;
            stacks++;
        }
        if (remaining <= 0) {
            return 0;
        }
        long oversized = remaining;
        // 超大堆自己也可能顶到 int 上限，继续按 int 的最大值切——最后一次的余数落在一个堆里。
        while (remaining > 0) {
            int perStack = (int) Math.min(remaining, Integer.MAX_VALUE);
            drops.add(key.toStack(perStack));
            remaining -= perStack;
        }
        return oversized;
    }
}
