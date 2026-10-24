package io.github.lounode.ae2pattern.common.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 产物抽取的节拍：快速档的退避序列是纯算术，最容易写错（一倍到底还是取上限），也最容易在改动里被悄悄改掉。
 */
class ProviderExtractionPacingTest {

    @Test
    void normalModeAlwaysWaitsTwentyTicks() {
        assertEquals(ProviderExtractionPacing.NORMAL_INTERVAL,
                ProviderExtractionPacing.nextInterval(ProviderExtractionMode.NORMAL, 1, true));
        assertEquals(ProviderExtractionPacing.NORMAL_INTERVAL,
                ProviderExtractionPacing.nextInterval(ProviderExtractionMode.NORMAL, 35, false));
    }

    @Test
    void offModeNeverSchedules() {
        assertEquals(0, ProviderExtractionPacing.nextInterval(ProviderExtractionMode.OFF, 1, true));
        assertEquals(0, ProviderExtractionPacing.nextInterval(ProviderExtractionMode.OFF, 20, false));
    }

    @Test
    void fastModeDoublesOnMissesUpToTheCap() {
        // 用户口径：1 起步，未抽到就翻倍，上限 20。1→2→4→8→16→20（16 翻倍越界取上限）。
        int interval = ProviderExtractionPacing.FAST_START_INTERVAL;
        var seen = new StringBuilder().append(interval);
        for (int i = 0; i < 6; i++) {
            interval = ProviderExtractionPacing.nextInterval(ProviderExtractionMode.FAST, interval, false);
            seen.append('→').append(interval);
        }
        assertEquals("1→2→4→8→16→20→20", seen.toString());
    }

    @Test
    void fastModeSnapsBackToFastestOnSuccess() {
        assertEquals(ProviderExtractionPacing.FAST_START_INTERVAL,
                ProviderExtractionPacing.nextInterval(ProviderExtractionMode.FAST, 20, true));
        assertEquals(ProviderExtractionPacing.FAST_START_INTERVAL,
                ProviderExtractionPacing.nextInterval(ProviderExtractionMode.FAST, 1, true));
    }

    @Test
    void cooldownPutsTheFirstExtractionExactlyOneIntervalApart() {
        // 间隔 N 必须恰好隔 N 个 tick 抽一次：1 就是相邻 tick 各抽一次，20 就是每 20 tick 一次。
        assertEquals(0, ticksUntilExtraction(ProviderExtractionPacing.FAST_START_INTERVAL));
        assertEquals(1, ticksUntilExtraction(2));
        assertEquals(19, ticksUntilExtraction(ProviderExtractionPacing.NORMAL_INTERVAL));
        assertEquals(19, ticksUntilExtraction(ProviderExtractionPacing.FAST_MAX_INTERVAL));
    }

    @Test
    void fastModeTreatsNonPositiveStoredIntervalAsTheFastest() {
        // 读档兜底：旧存档没有这个字段时是 0，翻倍不能变成 0（那样就成了每 tick 抽）。
        assertEquals(2, ProviderExtractionPacing.nextInterval(ProviderExtractionMode.FAST, 0, false));
    }

    /** 从 interval 推进到「该抽」需要几次 advance（0 表示下一 tick 就抽）。 */
    private static int ticksUntilExtraction(int interval) {
        int cooldown = interval;
        for (int tick = 0; tick < interval + 2; tick++) {
            cooldown = ProviderExtractionPacing.advanceCooldown(cooldown);
            if (cooldown == 0) {
                return tick;
            }
        }
        return -1;
    }
}
