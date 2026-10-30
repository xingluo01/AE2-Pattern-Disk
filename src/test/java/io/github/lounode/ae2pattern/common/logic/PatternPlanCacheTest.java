package io.github.lounode.ae2pattern.common.logic;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分析失败的重试上限与两类记号的「只记一次」语义。
 *
 * <p>固定的是一组按轮数收费的规则：坏样板每轮重解析一次就是每轮一次异常构造加一条 WARN；而记号集合原先只写
 * 不读，「每样板警告一次」从来没生效。上限而不是永久拉黑，是因为第三方样板可能只是暂时读不出来——永久拉黑会
 * 让作业卡到玩家重插磁盘为止。都不需要网格，纯记账即可验证。</p>
 */
class PatternPlanCacheTest {

    /** 读 input 就炸：模拟第三方样板在分析路径上抛异常。 */
    private static IPatternDetails brokenPattern() {
        var pattern = mock(IPatternDetails.class);
        when(pattern.getInputs()).thenThrow(new IllegalStateException("broken pattern"));
        return pattern;
    }

    @Test
    void failingPatternStopsAfterTheAttemptLimit() {
        var cache = new PatternPlanCache();
        var pattern = brokenPattern();
        var reports = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            assertNull(cache.analyse(pattern, (p, cause) -> reports.incrementAndGet()));
        }

        assertEquals(3, reports.get(), "同一样板最多报三次，之后不再重解析");
        verify(pattern, times(3)).getInputs();
    }

    @Test
    void prepareStopsResubmittingAfterTheAttemptLimit() {
        var cache = new PatternPlanCache();
        var pattern = brokenPattern();
        var reports = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            cache.prepare(List.of(pattern), List.of(pattern), null, (p, cause) -> reports.incrementAndGet());
        }

        assertEquals(3, reports.get());
        verify(pattern, times(3)).getInputs();
    }

    /**
     * 工作线程把计划直接 put 进缓存，之后 analyse 从「计划已存在」那条早退——计数必须在那里也清掉，
     * 否则「失败两次 → 池里成功 → 计划被回收 → 再失败一次」会被当成第三次失败直接放弃。
     */
    @Test
    void workerSuccessForgetsEarlierFailures() {
        var cache = new PatternPlanCache();
        var recovering = mock(IPatternDetails.class);
        var stillBroken = mock(IPatternDetails.class);
        var reports = new AtomicInteger();

        when(recovering.getInputs()).thenThrow(new IllegalStateException("temporarily broken"));
        when(stillBroken.getInputs()).thenThrow(new IllegalStateException("broken"));
        cache.analyse(recovering, (p, cause) -> reports.incrementAndGet());
        cache.analyse(recovering, (p, cause) -> reports.incrementAndGet());
        assertEquals(2, reports.get());

        // 恢复之后连同另一份坏样板一起交给工作线程：prepare 只在缺两份以上时才开池。
        doReturn(new IPatternDetails.IInput[0]).when(recovering).getInputs();
        doReturn(List.of()).when(recovering).getOutputs();
        var pool = Executors.newSingleThreadExecutor();
        try {
            cache.prepare(List.of(recovering, stillBroken), List.of(recovering, stillBroken), pool,
                    (p, cause) -> reports.incrementAndGet());
        } finally {
            pool.shutdownNow();
        }
        assertNotNull(cache.get(recovering), "工作线程应当把计划写进缓存");

        // 计划被回收（clear 不动记号）之后再坏，应当从第一次重新算起：三次都报。
        cache.clear();
        doThrow(new IllegalStateException("broken again")).when(recovering).getInputs();
        for (int i = 0; i < 3; i++) {
            assertNull(cache.analyse(recovering, (p, cause) -> reports.incrementAndGet()));
        }
        assertEquals(6, reports.get(), "恢复过一次之后，失败计数必须从头开始");
    }

    @Test
    void warnedNoteFiresOnlyTheFirstTime() {
        var cache = new PatternPlanCache();
        var pattern = mock(IPatternDetails.class);

        assertTrue(cache.noteWarned(pattern));
        assertFalse(cache.noteWarned(pattern));
    }

    @Test
    void damageFallbackNoteFiresOnlyTheFirstTime() {
        var cache = new PatternPlanCache();
        var pattern = mock(IPatternDetails.class);

        assertTrue(cache.noteDamageFallback(pattern));
        assertFalse(cache.noteDamageFallback(pattern));
    }

    @Test
    void clearNotesStartsTheCountOver() {
        var cache = new PatternPlanCache();
        var pattern = brokenPattern();
        var reports = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            cache.analyse(pattern, (p, cause) -> reports.incrementAndGet());
        }
        cache.clearNotes();
        cache.analyse(pattern, (p, cause) -> reports.incrementAndGet());

        assertEquals(4, reports.get(), "记号清掉之后坏样板可以重新试");
        verify(pattern, times(4)).getInputs();
    }
}
