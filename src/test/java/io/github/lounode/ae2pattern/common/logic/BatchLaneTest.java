package io.github.lounode.ae2pattern.common.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通道的调度判据：窗口、分批分类、批龄上限与清空。
 *
 * <p>这些用例固定的是「一条道上什么时候算能跑」这条规则本身——真跑一台机器要网格、元件与样板，属于实机
 * 验证的范围。键用字符串，因为通道把键只当成可相等的标签。</p>
 */
class BatchLaneTest {

    private static final int FULL_WINDOW = 40;
    private static final int IDLE_RESET = FULL_WINDOW * BatchLane.IDLE_RESET_WINDOWS;

    @Test
    void emptyLaneNeverRuns() {
        var lane = new BatchLane<String>(120);

        assertFalse(lane.isDue(1_000, FULL_WINDOW));
        assertEquals(0, lane.ticksUntilRun(1_000, FULL_WINDOW));
        assertEquals(0, lane.queuedJobs());
    }

    @Test
    void arrivalAccumulatesPerPattern() {
        var lane = new BatchLane<String>(120);

        assertEquals(1, lane.arrive("a", 1, 0, IDLE_RESET));
        assertEquals(3, lane.arrive("a", 2, 1, IDLE_RESET));
        assertEquals(5, lane.arrive("b", 5, 2, IDLE_RESET));
        assertEquals(8, lane.queuedJobs());
        assertEquals(2, lane.size());
    }

    @Test
    void opensOnlyAfterTheWindowIsQuiet() {
        var lane = new BatchLane<String>(120);
        lane.arrive("a", 1, 0, IDLE_RESET);

        assertFalse(lane.isDue(FULL_WINDOW - 1, FULL_WINDOW));
        assertEquals(1, lane.ticksUntilRun(FULL_WINDOW - 1, FULL_WINDOW));
        assertTrue(lane.isDue(FULL_WINDOW, FULL_WINDOW));
        assertEquals(0, lane.ticksUntilRun(FULL_WINDOW, FULL_WINDOW));
    }

    @Test
    void smallRunSwitchesToTheShortWindow() {
        var lane = new BatchLane<String>(120);
        lane.arrive("a", 1, 0, IDLE_RESET);
        lane.recordRun(1, FULL_WINDOW);

        // 上一轮只组出一件：下一轮等一个静默 tick 就够，不再等整窗。
        assertEquals(BatchLane.SHORT_WINDOW_TICKS, lane.effectiveWindowTicks(FULL_WINDOW));
        assertTrue(lane.isDue(FULL_WINDOW + BatchLane.SHORT_WINDOW_TICKS, FULL_WINDOW));
    }

    @Test
    void realBatchKeepsTheFullWindow() {
        var lane = new BatchLane<String>(120);
        lane.arrive("a", BatchLane.SMALL_RUN_JOBS, 0, IDLE_RESET);
        lane.recordRun(BatchLane.SMALL_RUN_JOBS, FULL_WINDOW);

        assertEquals(FULL_WINDOW, lane.effectiveWindowTicks(FULL_WINDOW));
        assertFalse(lane.isDue(FULL_WINDOW - 1, FULL_WINDOW));
    }

    @Test
    void shortWindowGetsAFullWindowProbeOnceInAWhile() {
        var lane = new BatchLane<String>(120);
        lane.arrive("a", 1, 0, IDLE_RESET);
        for (int run = 1; run <= BatchLane.PROBE_EVERY_RUNS; run++) {
            lane.recordRun(1, run);
        }

        // 连续小批到探针次数：整窗回来一次，好让已经变成流的供料被重新认出来。
        assertEquals(FULL_WINDOW, lane.effectiveWindowTicks(FULL_WINDOW));
    }

    @Test
    void aLongSilenceWipesTheClassification() {
        var lane = new BatchLane<String>(120);
        lane.arrive("a", 1, 0, IDLE_RESET);
        lane.recordRun(1, 1);
        assertEquals(BatchLane.SHORT_WINDOW_TICKS, lane.effectiveWindowTicks(FULL_WINDOW));

        // 久停之后的第一份订单按整窗攒，上一次运行长的样子不该决定它怎么处理。
        lane.arrive("b", 1, IDLE_RESET, IDLE_RESET);

        assertEquals(FULL_WINDOW, lane.effectiveWindowTicks(FULL_WINDOW));
    }

    @Test
    void ageLimitLetsASteadySupplyThrough() {
        var lane = new BatchLane<String>(10);
        // 每 tick 都有料进来：窗口永远不闭合，只有上限能放行。
        for (long tick = 0; tick <= 20; tick++) {
            lane.arrive("a", 1, tick, IDLE_RESET);
            if (tick < 10) {
                assertFalse(lane.isDue(tick, FULL_WINDOW), "tick " + tick + " 还没到上限");
            } else {
                assertTrue(lane.isDue(tick, FULL_WINDOW), "tick " + tick + " 应当被上限放行");
            }
        }
    }

    @Test
    void ageLimitZeroMeansQuietOnly() {
        var lane = new BatchLane<String>(0);
        for (long tick = 0; tick <= 200; tick++) {
            lane.arrive("a", 1, tick, IDLE_RESET);
            assertFalse(lane.isDue(tick, FULL_WINDOW), "关掉上限后持续到货永远不到期");
        }
    }

    @Test
    void theAgeLimitIsMeasuredFromTheLastRun() {
        var lane = new BatchLane<String>(10);
        for (long tick = 0; tick < 10; tick++) {
            lane.arrive("a", 1, tick, IDLE_RESET);
        }
        // 一轮跑过之后积压起点重置：上限量的是「距上一轮」，不是「距这条道第一次到货」。
        lane.recordRun(1, 10);
        for (long tick = 10; tick < 20; tick++) {
            lane.arrive("a", 1, tick, IDLE_RESET);
            assertFalse(lane.isDue(tick, FULL_WINDOW), "tick " + tick + " 距上一轮还不够久");
        }
        lane.arrive("a", 1, 20, IDLE_RESET);
        assertTrue(lane.isDue(20, FULL_WINDOW));
    }

    @Test
    void anEmptyingLaneForgetsItsBacklog() {
        var lane = new BatchLane<String>(10);
        for (long tick = 0; tick < 10; tick++) {
            lane.arrive("a", 1, tick, IDLE_RESET);
        }

        lane.remove("a");

        assertTrue(lane.isEmpty());
        // 道空了，积压也就算完：下一份订单从自己的到货时刻重新数批龄。
        lane.arrive("b", 1, 100, IDLE_RESET);
        assertFalse(lane.isDue(100, FULL_WINDOW));
    }

    @Test
    void clearingResetsTheWindowClassification() {
        var lane = new BatchLane<String>(120);
        lane.arrive("a", 1, 0, IDLE_RESET);
        lane.recordRun(1, 1);
        assertEquals(BatchLane.SHORT_WINDOW_TICKS, lane.effectiveWindowTicks(FULL_WINDOW));

        lane.clear();
        lane.arrive("b", 1, 2, IDLE_RESET);

        assertFalse(lane.isEmpty());
        assertEquals(FULL_WINDOW, lane.effectiveWindowTicks(FULL_WINDOW));
        assertFalse(lane.isDue(FULL_WINDOW + 1, FULL_WINDOW));
    }

    @Test
    void lanesDoNotShareTheirCountdown() {
        var light = new BatchLane<String>(120);
        var bulk = new BatchLane<String>(120);

        light.arrive("small", 1, 0, IDLE_RESET);
        // 大批量订单每 tick 都在到货，但它只在重载通道上刷新自己的时钟。
        for (long tick = 0; tick < FULL_WINDOW; tick++) {
            bulk.arrive("big", 1, tick, IDLE_RESET);
            assertFalse(bulk.isDue(tick, FULL_WINDOW));
        }

        // 轻载通道的到货停在 0，于是它自己的窗口已经安静够久了。
        assertTrue(light.isDue(FULL_WINDOW, FULL_WINDOW));
    }
}
