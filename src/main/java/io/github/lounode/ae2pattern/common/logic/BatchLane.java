package io.github.lounode.ae2pattern.common.logic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 批处理装配室的一条任务通道：自己的队列、自己的静默窗口、自己的小批统计。
 *
 * <p><b>为什么要有通道。</b>机器原先只有一份「最后一次到货」的时钟，于是任何一条推送都会把整台机器的窗口
 * 往回拨：一条流式到货的大订单只要还在推，搭在同一台机器上的小订单就永远等不到窗口闭合。窗口量的是「从现在
 * 往前有多安静」，而大订单的常态恰恰是不安静。拆成两条通道之后，窗口量的是「这条道上有多安静」，两条道的
 * 时钟互不影响。</p>
 *
 * <p><b>通道只装调度状态，不碰队列里的内容。</b>件数怎么记、往不往后扣，都由调用方决定；这里只回答三个问题：
 * 这条道现在能不能跑、还差几 tick、跑完之后算不算聚到了东西。于是它可以脱离方块实体单独读懂，也可以单独
 * 检验——真正的执行仍然只发生在服务端 tick 线程上（元件与网络都不是线程安全的）。</p>
 *
 * <p><b>批龄上限。</b>静默判定有一个绕不过去的死角：一直有料进来的通道永远不会静默，于是一条稳定的供料流
 * 能把它压到地老天荒。上限兜住这一头——同一段积压持续到 {@code ageLimitTicks}，无论安不安静都放一轮。
 * 没有它，窗口越"聪明"，持续供料下的等待就越没有上界。{@code 0} 表示关掉上限（只按静默判定）。</p>
 *
 * <p><b>两个时间都要显式传入，不读世界时钟。</b>这样「现在几点」由调用方决定，检验时不用去造一个关卡。</p>
 *
 * <p>键是样板（{@code IPatternDetails}），但这里不引用它的任何成员：通道只把键当成一个可相等的标签，
 * 检验时换成字符串即可。</p>
 */
public final class BatchLane<K> {

    /**
     * 短窗：上一轮只组出少量件数时，下一轮不再等整窗。等一整窗去凑一件货只是白等。
     */
    public static final int SHORT_WINDOW_TICKS = 1;

    /** 低于这个件数的运行算「没聚到东西」，于是把下一轮的窗口换成短窗。与机器的分批口径同源。 */
    public static final long SMALL_RUN_JOBS = 8;

    /**
     * 连续小批运行到这么多次时，给整窗一次探针：一次运行大约只能捞到一个 tick 的到货，若供料已经变成流，
     * 它永远长不成那一批「够大」的运行，短窗会把流切成一次一件。探针让流有被重新识别的机会。
     */
    public static final int PROBE_EVERY_RUNS = 32;

    /** 静默这么多个窗口，就把小批分类抹掉：久停之后的订单一律从整窗开始攒。 */
    public static final int IDLE_RESET_WINDOWS = 8;

    /** 键 → 剩余件数。插入顺序就是首次到达顺序，执行时照它遍历。 */
    private final LinkedHashMap<K, Long> queue = new LinkedHashMap<>();

    /** 同一段积压放开一轮的上限 tick 数；0 表示只按静默判定。 */
    private final int ageLimitTicks;

    /** 最后一次到货的游戏时间；{@code -1} 表示这条道还没收到过东西。用 -1 而不是 0：游戏时间从 0 开始的
     * 存档里，第一 tick 的到货正好也会写成 0，两者不能共用同一个哨兵值。 */
    private long lastArrivalGameTime = -1;

    /** 当前这段积压从什么时候开始算；空道时为 0。批龄上限量的是它。 */
    private long backlogSinceGameTime;

    /** 上一轮组出多少件。0 表示还没跑过，或者上一轮一件也没成。 */
    private long lastRunJobs;

    /** 连续的小批运行次数，用来决定什么时候该给整窗一次探针。 */
    private int shortRunStreak;

    /**
     * @param ageLimitTicks 同一段积压最多等多少 tick 就强制放一轮；{@code <= 0} 关掉上限
     */
    public BatchLane(int ageLimitTicks) {
        this.ageLimitTicks = ageLimitTicks;
    }

    /**
     * 记一次到货，并返回该样板在道上的累计件数。
     *
     * <p>久停之后先抹掉小批分类再记账：上一次运行长的样子不该决定一份新订单怎么攒。判断基准是<b>这条道</b>
     * 上次到货的时间，不是整台机器的——这也正是通道存在的意义。</p>
     *
     * @param idleResetTicks 静默达到这个 tick 数就把小批分类清零（通常是窗口 × {@link #IDLE_RESET_WINDOWS}）
     */
    public long arrive(K pattern, long jobs, long now, int idleResetTicks) {
        if (queue.isEmpty()) {
            backlogSinceGameTime = now;
        }
        if (lastArrivalGameTime >= 0 && now - lastArrivalGameTime >= idleResetTicks) {
            lastRunJobs = 0;
            shortRunStreak = 0;
        }
        long remaining = queue.merge(pattern, jobs, Long::sum);
        lastArrivalGameTime = now;
        return remaining;
    }

    /** 这条道现在能不能跑一轮。空道永远不能。 */
    public boolean isDue(long now, int fullWindowTicks) {
        if (queue.isEmpty()) {
            return false;
        }
        if (ageLimitTicks > 0 && now - backlogSinceGameTime >= ageLimitTicks) {
            // 一直有料进来就没有静默可言，上限是唯一的上界。
            return true;
        }
        return now - lastArrivalGameTime >= effectiveWindowTicks(fullWindowTicks);
    }

    /** 还差几个 tick 才能跑；已经能跑时返回 0。展示用，与 {@link #isDue} 同源。 */
    public int ticksUntilRun(long now, int fullWindowTicks) {
        if (queue.isEmpty()) {
            return 0;
        }
        if (ageLimitTicks > 0 && now - backlogSinceGameTime >= ageLimitTicks) {
            return 0;
        }
        long waiting = effectiveWindowTicks(fullWindowTicks) - (now - lastArrivalGameTime);
        return (int) Math.max(0, waiting);
    }

    /**
     * 这一轮实际生效的静默窗口。聚到了东西的通道、以及还没跑过的通道，照整窗走——这正是大订单能攒起来的原因；
     * 小批运行改用 {@link #SHORT_WINDOW_TICKS}，每 {@link #PROBE_EVERY_RUNS} 次给整窗一次探针。
     */
    public int effectiveWindowTicks(int fullWindowTicks) {
        if (shortRunStreak > 0 && shortRunStreak % PROBE_EVERY_RUNS == 0) {
            return fullWindowTicks;
        }
        return lastRunJobs > 0 && lastRunJobs < SMALL_RUN_JOBS ? SHORT_WINDOW_TICKS : fullWindowTicks;
    }

    /**
     * 记一轮运行的结果。
     *
     * @param assembledJobs 这一轮组出的件数
     * @param now           当前游戏时间：积压还在就重置批龄起点，这样上限量的是「距上一轮」而不是「距这条道
     *                      第一次到货」——后者会让一条长订单在第一批之后每 tick 都被强制放行
     */
    public void recordRun(long assembledJobs, long now) {
        lastRunJobs = assembledJobs;
        if (assembledJobs > 0 && assembledJobs < SMALL_RUN_JOBS) {
            // 饱和而不是允许溢出：溢出成负数会让短窗永远回不来。
            if (shortRunStreak < Integer.MAX_VALUE) {
                shortRunStreak++;
            }
        } else {
            shortRunStreak = 0;
        }
        backlogSinceGameTime = queue.isEmpty() ? 0 : now;
    }

    /**
     * 把一条样板整份移出通道（分流迁移用）。
     *
     * <p>道空了就把积压起点也清掉：下一份订单从自己的到货时刻重新数批龄，不继承上一份的。</p>
     */
    public void remove(K pattern) {
        queue.remove(pattern);
        if (queue.isEmpty()) {
            backlogSinceGameTime = 0;
        }
    }

    /** 该样板在道上的剩余件数；不在道上时为 0。 */
    public long remainingOf(K pattern) {
        return queue.getOrDefault(pattern, 0L);
    }

    public boolean contains(K pattern) {
        return queue.containsKey(pattern);
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    /** 道上还剩多少件待组装。 */
    public long queuedJobs() {
        long total = 0;
        for (long remaining : queue.values()) {
            total += remaining;
        }
        return total;
    }

    /** 只读的键集合，供分析器与需求方遍历。 */
    public Set<K> patterns() {
        return Collections.unmodifiableSet(queue.keySet());
    }

    /**
     * 执行时的遍历视图：插入顺序。调用方可以直接改剩余件数（{@code entry.setValue}），也可以把跑完的样板
     * 从迭代器里摘掉；两者都不会碰窗口统计——那些由 {@link #recordRun} 在整轮结束后统一记。
     */
    public Map<K, Long> entries() {
        return queue;
    }

    /**
     * 清空这条道。
     *
     * <p>窗口统计一并归零：队列没了，下一份订单必须重新走一遍完整的窗口，不能继承上一份的短窗判定。</p>
     */
    public void clear() {
        queue.clear();
        lastArrivalGameTime = -1;
        backlogSinceGameTime = 0;
        lastRunJobs = 0;
        shortRunStreak = 0;
    }
}
