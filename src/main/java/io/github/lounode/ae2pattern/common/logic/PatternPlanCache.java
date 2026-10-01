package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

import org.jetbrains.annotations.Nullable;

/**
 * 配方计划缓存：把样板分析成 {@link PatternPlan} 并留着，外加两类随计划一起失效的记号。
 *
 * <p>分析一份计划要把样板的每个输入槽的候选、产出与余料都读出来，其中「余料」那一步 AE2 会为了结果把配方
 * 重跑一遍（{@code AECraftingPattern#getRecipeRemainder} 自己都挂着一条「待缓存」的 TODO）。机器每跑一份
 * 都要用这些答案，所以它们必须被记住。</p>
 *
 * <p><b>分析可以并行，但只对纯读的样板安全。</b>多个样板的分析互不依赖，成批出现时交给工作线程更快；代价
 * 是对样板实现的一个假设——余料经 {@code IInput.getRemainingKey} 读出，第三方样板若在那条路径上存可变状态
 * 就不安全。AE2 自家的合成/锻造/切石样板只在构造器里建字段，而入口闸门也只放这几种进来。</p>
 *
 * <p><b>三份数据都按样板实例的<b>引用</b>做键。</b>样板每次解码都是一个新实例，按值比较既贵又会把不同解码
 * 混成一个。三者同生共死：计划里存着产出量与余料，记号讲的是「这份样板的配方答案已经出过问题了」——样板
 * 一被重新解码，三者就都失去意义，必须一起清。</p>
 */
public final class PatternPlanCache {

    /** 分析失败的上报口：缓存不认识方块实体，失败得有处可去。 */
    public interface FailureReporter {

        /** 某个样板的计划分析抛了异常。实现方不应再抛出去。 */
        void report(IPatternDetails pattern, Exception cause);
    }

    /** 上限：计划只是加速手段，被喂进无穷多种样板时宁可回收，也不能无界增长。 */
    private static final int MAX_CACHED_PLANS = 1024;

    /** 同上，针对余料表。 */
    private static final int MAX_CACHED_REMAINDERS = 4096;

    /** 同上，针对两类记号集合。 */
    private static final int MAX_NOTES = 64;

    private final Map<IPatternDetails, PatternPlan> plans = new ConcurrentHashMap<>();
    private final Map<PatternPlan.Input, Map<AEKey, Optional<AEKey>>> remainders = new IdentityHashMap<>();

    /** 配方答案已经与样板声明对不上的样板。 */
    private final Set<IPatternDetails> warned = notesSet();

    /** 缓冲里同时握着同一槽的多个损伤变体、于是让一部分订单退回逐份的样板。 */
    private final Set<IPatternDetails> damageFallbackNoted = notesSet();

    /** 已缓存的计划，没有就是 {@code null}。不触发分析。 */
    public @Nullable PatternPlan get(IPatternDetails pattern) {
        return plans.get(pattern);
    }

    /**
     * 取计划，没有就现分析一份。
     *
     * @return 分析不出来时返回 {@code null}。调用方应把该样板的工作留在队列里，而不是把整批带下去
     */
    public @Nullable PatternPlan analyse(IPatternDetails pattern, FailureReporter reporter) {
        var plan = plans.get(pattern);
        if (plan != null) {
            return plan;
        }
        try {
            plan = PatternPlan.of(pattern);
        } catch (Exception e) {
            reporter.report(pattern, e);
            return null;
        }
        plans.put(pattern, plan);
        return plan;
    }

    /**
     * 为一批样板补齐计划，缺得多的那部分交给工作线程并行分析。
     *
     * @param live    当前还在排队的样板；缓存超限时只保留它们的计划
     * @param pool    分析用的工作线程池，{@code null} 表示没有（那就全部就地分析）
     * @param reporter 分析失败的上报口
     */
    public void prepare(Collection<IPatternDetails> patterns, Collection<IPatternDetails> live,
            @Nullable ExecutorService pool, FailureReporter reporter) {
        var missing = new ArrayList<IPatternDetails>();
        for (var pattern : patterns) {
            if (!plans.containsKey(pattern)) {
                missing.add(pattern);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (plans.size() + missing.size() > MAX_CACHED_PLANS) {
            // Plans are only a speed-up, so reclaim the cache - but keep the entries the running batch can
            // still reuse instead of dropping everything.
            plans.keySet().retainAll(live);
            // The remainder map is bounded on its own, but it describes the very inputs of those dropped plans.
            remainders.clear();
        }

        if (pool == null || missing.size() < 2) {
            // A single analysis is cheaper inline than handed to a worker.
            for (var pattern : missing) {
                analyse(pattern, reporter);
            }
            return;
        }

        var submitted = new ArrayList<IPatternDetails>(missing.size());
        var futures = new ArrayList<Future<?>>(missing.size());
        for (var pattern : missing) {
            try {
                futures.add(pool.submit(() -> plans.put(pattern, PatternPlan.of(pattern))));
                submitted.add(pattern);
            } catch (RejectedExecutionException e) {
                // The pool was shut down under us (machine removed mid-tick). This pattern never reached a
                // worker, so analysing it here cannot race with anything.
                analyse(pattern, reporter);
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Cancelling keeps tasks that have not started yet from ever starting. A task that is
                // already running keeps running - interrupting a thread cannot stop a pure computation -
                // so this thread may still duplicate its work, see the safety net below.
                for (int j = i; j < futures.size(); j++) {
                    futures.get(j).cancel(true);
                }
                break;
            } catch (ExecutionException e) {
                // analyse below retries the analysis and reports a permanent failure itself.
            }
        }
        for (int i = 0; i < futures.size(); i++) {
            var future = futures.get(i);
            if (future.isCancelled() || !future.isDone()) {
                // A cancelled task may still be running; the caller picks the pattern up again on this very
                // tick if it is still missing. Only storage, grid and world access have to stay on this
                // thread - a duplicated pure analysis is harmless.
                continue;
            }
            analyse(submitted.get(i), reporter);
        }
    }

    /**
     * 某个槽位用某个具体变体消耗后的容器余料，{@code null} 表示无余料。
     *
     * <p>键是「样板的输入槽 + 实际被消耗的变体」两个身份对象：样板的输入对象是每次解码新造的实例，这里
     * 绝不能按值比较。</p>
     */
    public @Nullable AEKey remainderFor(PatternPlan.Input input, AEKey usedKey) {
        var byVariant = remainders.get(input);
        if (byVariant == null) {
            if (remainders.size() >= MAX_CACHED_REMAINDERS) {
                // Remainders only save work; a machine fed an endless variety of patterns drops the map rather
                // than growing it without limit, the same policy the plan cache follows.
                remainders.clear();
            }
            byVariant = new HashMap<>();
            remainders.put(input, byVariant);
        }
        var cached = byVariant.get(usedKey);
        if (cached != null) {
            return cached.orElse(null);
        }
        var computed = input.remainingFor(usedKey);
        byVariant.put(usedKey, Optional.ofNullable(computed));
        return computed;
    }

    /** 记一笔「这份样板同时被多个变体服务」。 */
    public void noteDamageFallback(IPatternDetails pattern) {
        if (damageFallbackNoted.add(pattern) && damageFallbackNoted.size() > MAX_NOTES) {
            damageFallbackNoted.clear();
            damageFallbackNoted.add(pattern);
        }
    }

    /** 记一笔「这份样板的配方答案与声明对不上」。 */
    public void noteWarned(IPatternDetails pattern) {
        warned.add(pattern);
        if (warned.size() > MAX_NOTES) {
            // Bounded on purpose: a machine fed an endless variety of patterns must not grow a set forever.
            warned.clear();
        }
    }

    /**
     * 丢掉全部缓存与记号。配方池被重建（磁盘换过、存档读过、配方重载过）时必须调用：同一份样板值可能解码成
     * 不同的配方，旧的产出量与余料都会骗人。
     */
    public void clear() {
        plans.clear();
        remainders.clear();
    }

    /**
     * 只丢两类记号（它们描述的是刚刚被重新解码的那些样板）。
     *
     * <p>{@code warned} 也在这里清：样板被重新解码后是一个新实例，而记号集合是身份语义，旧条目再也
     * 不会被命中，留着只会积压。</p>
     */
    public void clearNotes() {
        warned.clear();
        damageFallbackNoted.clear();
    }

    /**
     * 记号集合用身份语义：样板每次解码都是新实例，按 {@code equals} 去重会把不同的解码混成一个，而这里要
     * 认的正是「同一个实例」。
     */
    private static Set<IPatternDetails> notesSet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
