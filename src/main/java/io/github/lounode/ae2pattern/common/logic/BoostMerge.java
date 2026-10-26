package io.github.lounode.ae2pattern.common.logic;

import java.util.List;

import appeng.api.stacks.KeyCounter;

/**
 * 发配倍增的合并本务：把多次交接的原料并成一份，让一次 IO 顶掉多次。
 *
 * <p>目前只有样板磁盘供应器在用，因为本质需求是同一个：<b>合并输入的原材料，把多次
 * IO 压成一次</b>。一次交接的固定代价——每 key 插进每个元件、每元件标脏、记一次账——与它带了几份无关，只与
 * 「做了几次」有关。long 级存储元件每次写入还要跑一遍自己的 persist 与状态刷新，那笔开销同样按次数计。</p>
 *
 * <p><b>合并的口径是「按槽逐位相加」</b>：同一个样板的下标 {@code i} 指的是同一个输入槽，N 份同一模板合起来就是
 * N 倍的一份。</p>
 *
 * <p><b>槽数对不上就不能合。</b>供给方的两种到货形状——整批交付的扁平总量（只有一个 counter）与 AE2/ECO 普通
 * 路径的每槽一个 counter——混在同一批里时下标意义不同。合并的语义是按槽逐位相加，槽位对不齐就没有「同槽」可言。
 * 所以这里返回 {@code null} 表示<b>不能合</b>，调用方把这一批改走普通路径：写不进目标就拒收——按 AE 的规矩，
 * 收不下就是收不下，不绕过槽位语义硬塞。</p>
 *
 * <p><b>这个分支目前走不到，但别删。</b>扁平形状只从 FastPath 的两个入口来（{@code dispatchTotals} 给的数组
 * 长度为 1），而 {@code ECOFastPathFacade} 还不查注册表（{@code docs/UPSTREAM_API_REQUESTS.md} 第 4 条），
 * 那些入口处于休眠。顶上有单输入样板时两种形状碰巧都是长度 1，永远对得上——这就是它看起来像死代码的原因。
 * 但上游补上那个查询后，同一个<b>多输入</b>样板会同时以长度 1 和长度 n 到货，那时按槽截断就是静默丢料，而症状
 * （订单卡在某个数不动）极难归因到这个方法。</p>
 */
public final class BoostMerge {

    private BoostMerge() {
    }

    /**
     * 把一批交接按槽逐位相加成一份。
     *
     * @param batch 若干次交接的原料，每次一个按槽排布的 {@code KeyCounter[]}
     * @return 合并后的一份；槽数对不上时返回 {@code null}
     */
    public static KeyCounter[] merge(List<KeyCounter[]> batch) {
        KeyCounter[] merged = null;
        for (var inputs : batch) {
            if (inputs == null) {
                continue;
            }
            if (merged == null) {
                merged = copy(inputs);
                continue;
            }
            if (!mergeInto(merged, inputs)) {
                return null;
            }
        }
        return merged;
    }

    /**
     * 把 {@code incoming} 按槽并进 {@code target}。
     *
     * @return {@code false} 表示两者槽数不等，一个都没并——调用方必须当作没发生过，不能部分采用
     */
    public static boolean mergeInto(KeyCounter[] target, KeyCounter[] incoming) {
        if (target.length != incoming.length) {
            return false;
        }
        for (int i = 0; i < target.length; i++) {
            if (incoming[i] == null) {
                continue;
            }
            for (var entry : incoming[i]) {
                target[i].add(entry.getKey(), entry.getLongValue());
            }
        }
        return true;
    }

    /** 深拷一份按槽的输入：攒批期间上游那几份 counter 会被重写，直接持有就是读别人的活对象。 */
    public static KeyCounter[] copy(KeyCounter[] inputs) {
        var copy = new KeyCounter[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            copy[i] = new KeyCounter();
            if (inputs[i] == null) {
                continue;
            }
            for (var entry : inputs[i]) {
                copy[i].add(entry.getKey(), entry.getLongValue());
            }
        }
        return copy;
    }
}
