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
 * <p><b>四份数据都跟着样板实例走。</b>{@code plans} 按样板自己的 equals 做键（AE2 那几种以定义里的物品为
 * 身份），其余三份按实例引用：记号要认的正是「同一个实例」，按值比较会把不同解码混成一个。四者同生共死：
 * 计划里存着产出量与余料，记号讲的是「这份样板的配方答案已经出过问题了」——样板一被重新解码，四者就都
 * 失去意义，必须一起清。</p>
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

    /** 同上，针对三份记号集合（两类警告 + 分析失败）。 */
    private static final int MAX_NOTES = 64;

    /**
     * 分析失败重试上限（按调用计，同一轮里 prepare 与 runLane 会各试一次，所以 3 大约等于两轮）。
     * 第一次失败就该记住，但第三方样板可能只是暂时读不出来（配方还没就位），所以留几次重试；
     * 到了上限才彻底停手，等记号被清掉。
     */
    private static final int MAX_ANALYSIS_ATTEMPTS = 3;

    /**
     * 各样板分析失败的次数。键是实例：重新解码后就是另一份样板，计数从头开始。
     */
    private final Map<IPatternDetails, Integer> analysisFailures = new IdentityHashMap<>();
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
            if (!analysisFailures.isEmpty()) {
                // 计划已经有了，计数就不该留着：工作线程成功时计划是直接 put 进去的，之后 analyse 会从这条早退。
                analysisFailures.remove(pattern);
            }
            return plan;
        }
        int failures = analysisFailures.getOrDefault(pattern, 0);
        if (failures >= MAX_ANALYSIS_ATTEMPTS) {
            // Already given up on this one: do not pay the exception again every round. The caller keeps the
            // job queued, which is the same outcome as a fresh failure - and a re-decode (disk change, reload)
            // starts the count over, so a pattern that heals later is picked up again.
            return null;
        }
        try {
            plan = PatternPlan.of(pattern);
        } catch (Exception e) {
            if (analysisFailures.size() > MAX_NOTES) {
                // Bounded like the note sets: a machine fed endless broken patterns must not grow a map forever.
                analysisFailures.clear();
            }
            analysisFailures.put(pattern, failures + 1);
            reporter.report(pattern, e);
            return null;
        }
        plans.put(pattern, plan);
        // 成功一次就把失败计数抹掉：计数只该反映「连续失败到停手」，不该成为以后再失败一次的余量。
        analysisFailures.remove(pattern);
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
            if (!plans.containsKey(pattern)
                    && analysisFailures.getOrDefault(pattern, 0) < MAX_ANALYSIS_ATTEMPTS) {
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

    /**
     * 记一笔「这份样板同时被多个变体服务」。
     *
     * @return 第一次为这份样板记账时返回 {@code true}：调用方据此只打一次日志，而不是每轮打一次
     */
    public boolean noteDamageFallback(IPatternDetails pattern) {
        if (!damageFallbackNoted.add(pattern)) {
            return false;
        }
        trimNotes(damageFallbackNoted, pattern);
        return true;
    }

    /**
     * 记一笔「这份样板的配方答案与声明对不上」。
     *
     * @return 第一次为这份样板记账时返回 {@code true}：调用方据此只在这时打日志
     */
    public boolean noteWarned(IPatternDetails pattern) {
        if (!warned.add(pattern)) {
            return false;
        }
        trimNotes(warned, pattern);
        return true;
    }

    /**
     * 记号集合的共同上限：超了就整表清空，只留刚记下的这条。
     *
     * <p>有界是刻意的——机器被喂进无穷多种样板时，集合不能无限长。代价是清空之后旧样板会被重新记一次，
     * 那只是一条多出来的日志。</p>
     */
    private static void trimNotes(Set<IPatternDetails> notes, IPatternDetails keep) {
        if (notes.size() > MAX_NOTES) {
            notes.clear();
            notes.add(keep);
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
     * 丢掉全部记号（它们描述的是刚刚被重新解码的那些样板）。
     *
     * <p>三份都按实例记，而样板一被重新解码就是新实例：旧条目再也不会被命中，留着只会积压。
     * 失败计数也在这里清——清完如果还是坏的，会重新试几次再报，那是对的。</p>
     */
    public void clearNotes() {
        warned.clear();
        damageFallbackNoted.clear();
        analysisFailures.clear();
    }

    /**
     * 记号集合用身份语义：样板每次解码都是新实例，按 {@code equals} 去重会把不同的解码混成一个，而这里要
     * 认的正是「同一个实例」。
     */
    private static Set<IPatternDetails> notesSet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
