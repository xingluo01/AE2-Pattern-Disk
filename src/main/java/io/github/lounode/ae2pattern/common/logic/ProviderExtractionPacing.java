package io.github.lounode.ae2pattern.common.logic;

/**
 * 产物抽取的节拍计算，单独成类是为了能直接测：抽取本身要世界、能力与网格，算间隔只靠这几个常数。
 *
 * <p>
 * 快速抽取的意图是「有货就每 tick 抽，没货就别每 tick 空转」：抽到了立刻回到最快档，抽不到就翻倍，
 * 上限 {@link #FAST_MAX_INTERVAL}。所以 1 → 2 → 4 → 8 → 16 → 20（16 翻倍越界，取上限）。
 * </p>
 */
public final class ProviderExtractionPacing {

    /** 普通抽取的固定间隔。 */
    public static final int NORMAL_INTERVAL = 20;

    /** 快速抽取的最快间隔（也是抽到产物后回落到的值）。 */
    public static final int FAST_START_INTERVAL = 1;

    /** 快速抽取退避的上限。 */
    public static final int FAST_MAX_INTERVAL = 20;

    private ProviderExtractionPacing() {
    }

    /**
     * 冷却递减：返回本 tick 结束后剩余的冷却，0 表示这一 tick 该抽。
     *
     * <p>写成函数是为了能被单测盯住：间隔 N 必须恰好隔 N 个 tick 抽一次（FAST 的 1 就是相邻 tick 各抽一次，
     * 不是隔一 tick）。</p>
     */
    public static int advanceCooldown(int cooldown) {
        return cooldown > 0 ? cooldown - 1 : 0;
    }

    /**
     * 下一次抽取要等多少 tick。
     *
     * @param mode            当前模式
     * @param currentInterval 上一次用的间隔（快速档退避用；其它档不看）
     * @param extracted       这一次是否真的抽到了产物
     * @return 0 表示不抽取
     */
    public static int nextInterval(ProviderExtractionMode mode, int currentInterval, boolean extracted) {
        return switch (mode) {
            case OFF -> 0;
            case NORMAL -> NORMAL_INTERVAL;
            case FAST -> extracted ? FAST_START_INTERVAL
                    : Math.min(FAST_MAX_INTERVAL, Math.max(FAST_START_INTERVAL, currentInterval) * 2);
        };
    }
}
