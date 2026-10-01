package io.github.lounode.ae2pattern.common.logic;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.crafting.IPatternDetails;

/**
 * 新入队样板的分析线程池：谁在分析、池子多大、什么时候关，以及分析失败往哪报。
 *
 * <p><b>尺寸由速度卡决定</b>（{@code 1 << 卡数}，四张卡就是 16 线程）。这是速度卡在这里唯一能买到真并行的
 * 地方：元件缓冲与 ME 网络都不是线程安全的，每一次元件访问、网格调用与世界访问都留在服务端线程上。没有
 * 速度卡就没有值得递出去的东西，机器保持单线程——{@code pool()} 返回 {@code null} 就是「别开线程，就地做」。</p>
 *
 * <p><b>线程是 daemon 且最低优先级</b>：关服或区块卸载不该被一次分析拖住，分析也不该跟服务端线程抢 CPU。
 * 池子的寿命长于 tick，但不长于方块实体——{@link #shutdown()} 必须挂在 {@code setRemoved} 上，否则每台被
 * 拆掉或卸载的机器都会留下一组线程。</p>
 */
public final class BatchPatternAnalyser {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.batch_assembler");

    private final IntSupplier speedCards;
    private final Supplier<BlockPos> position;

    /** Worker threads used to analyse newly queued patterns in parallel; null while there are none. */
    private ExecutorService workers;
    private int workerCount;

    /**
     * @param speedCards 当前装着的速度卡数量（惰性读取：本类会在方块实体的升级库存初始化之前就构造好）
     * @param position   机器位置，只用于日志定位
     */
    public BatchPatternAnalyser(IntSupplier speedCards, Supplier<BlockPos> position) {
        this.speedCards = speedCards;
        this.position = position;
    }

    /**
     * 取一个用于分析新入队样板的线程池，或 {@code null} 表示没有可并行的余地（应当就地分析）。
     *
     * <p>卡数变了就换池：旧池的线程不该空转，而每张卡代表的线程数必须与实际线程数一致，否则「速度卡没
     * 生效」会变成一个查不出来的现象。</p>
     */
    public ExecutorService pool() {
        int wanted = 1 << speedCards.getAsInt();
        if (wanted <= 1) {
            shutdown();
            return null;
        }
        if (workers == null || workers.isShutdown() || workerCount != wanted) {
            shutdown();
            workers = Executors.newFixedThreadPool(wanted, runnable -> {
                var thread = new Thread(runnable, "ae2-pattern-disk-batch-assembler");
                // Daemon so a shutdown or a chunk unload is never held up by a running analysis.
                thread.setDaemon(true);
                // Analysis must never compete with the server thread for CPU time.
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            workerCount = wanted;
        }
        return workers;
    }

    /**
     * Drops the pool when the speed-card count changed, so the threads match the cards again.
     *
     * <p>Called from the upgrade callback. The workerCount guard keeps a machine that never had cards from
     * having to touch its inventory, and a no-op when the count is unchanged.</p>
     */
    public void onSpeedCardsChanged(int cards) {
        if (workerCount > 0 && (1 << cards) != workerCount) {
            shutdown();
        }
    }

    public void shutdown() {
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
            workerCount = 0;
        }
    }

    /**
     * 分析失败的上报口：缓存不认识方块实体，日志与定位得由这里给。
     */
    public void reportFailure(IPatternDetails pattern, Exception cause) {
        // Log the pattern's class rather than its definition: reading the definition is third-party code
        // and must not be able to throw out of this catch block.
        LOGGER.warn("Could not analyse pattern {} for the batch molecular assembler at {}; leaving the job queued",
                pattern.getClass().getName(), position.get(), cause);
    }
}
