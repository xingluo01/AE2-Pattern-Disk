package io.github.lounode.ae2pattern.common.logic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TEMPORARY measurement aid for the batch assembler performance work.
 *
 * <p>This class is <b>not</b> part of the feature. The optimisation decisions (how much of a batch can be
 * amortised, how expensive one craft really is, which segment dominates) have to be based on numbers from the
 * running game instead of estimates, so this collects per-segment timings of the assembly hot path and prints
 * a compact summary every few thousand crafts. Remove this class and its call sites once the tuning is done.</p>
 *
 * <p>Enabled by default while the work is in progress; set {@code -Dae2pattern.batchProbe=false} to silence it,
 * or use {@code -Dae2pattern.batchProbe.every=N} to change how many crafts go into one report.</p>
 */
public final class BatchProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.batch.probe");

    /** Segment ids; keep them contiguous, they index the arrays below. */
    public static final int RESOLVE = 0;
    public static final int CONSUME = 1;
    public static final int REMAINDER = 2;
    public static final int POWER = 3;
    public static final int OUTPUTS = 4;
    public static final int SEGMENTS = 5;

    private static final String[] NAMES = { "resolve", "consume", "remainder", "power", "outputs" };

    /**
     * On in a development runtime, or when {@code -Dae2pattern.batchProbe=true} is passed explicitly. A
     * released jar therefore stays quiet unless someone asks for the numbers.
     */
    public static final boolean ENABLED = Boolean.getBoolean("ae2pattern.batchProbe")
            || !net.neoforged.fml.loading.FMLEnvironment.production;

    private static final long REPORT_EVERY_CRAFTS = Math.max(64L, Long.getLong("ae2pattern.batchProbe.every", 4096L));

    /** At most one report per this many nanoseconds, so a huge order cannot flood the log. */
    private static final long REPORT_INTERVAL_NANOS = 10_000_000_000L;

    private static long lastReportNanos;

    private static final long[] SEGMENT_NANOS = new long[SEGMENTS];
    private static long crafts;
    private static long batches;
    private static long batchNanos;
    private static long optimisedNanos;
    private static long slowCrafts;
    private static long fastCrafts;

    private BatchProbe() {
    }

    /** Opens a segment. Returns the value to hand to {@link #add(int, long)}; 0 while disabled. */
    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Closes the segment opened at {@code started}. */
    public static void add(int segment, long started) {
        if (ENABLED && started != 0L) {
            SEGMENT_NANOS[segment] += System.nanoTime() - started;
        }
    }

    /** Counts one craft finished by the per-craft path. */
    public static void slowCraft() {
        if (ENABLED) {
            crafts++;
            slowCrafts++;
        }
    }

    /** Counts {@code amount} crafts finished by the batch path. */
    public static void fastCrafts(long amount) {
        if (ENABLED && amount > 0) {
            crafts += amount;
            fastCrafts += amount;
        }
    }

    /** Records the wall time one amortised batch took, for its own per-craft figure. */
    public static void optimisedBatch(long nanos) {
        if (ENABLED && nanos > 0) {
            optimisedNanos += nanos;
        }
    }

    /** Records one finished batch and reports once enough crafts have been seen. */
    public static void batch(long nanos, int jobs) {
        if (!ENABLED || jobs <= 0) {
            return;
        }
        batches++;
        batchNanos += nanos;
        if (crafts >= REPORT_EVERY_CRAFTS) {
            report();
        }
    }

    /** Prints the accumulated numbers and starts over, at most once per {@link #REPORT_INTERVAL_NANOS}. */
    public static void report() {
        if (!ENABLED || crafts == 0) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        lastReportNanos = now;
        var line = new StringBuilder("batch assembler probe: ")
                .append(crafts).append(" crafts in ").append(batches).append(" batches, ")
                .append(String.format("%.1f", batchNanos / 1_000_000.0 / Math.max(1L, batches))).append(" ms/batch, ")
                .append(String.format("%.2f", batchNanos / 1000.0 / crafts)).append(" us/craft total (")
                .append("fast ").append(fastCrafts).append(", slow ").append(slowCrafts).append(")")
                .append(String.format(", amortised %.2f us/craft",
                        fastCrafts == 0 ? 0.0 : optimisedNanos / 1000.0 / fastCrafts));
        for (int i = 0; i < SEGMENTS; i++) {
            line.append(String.format(", %s %.2f us/craft", NAMES[i], SEGMENT_NANOS[i] / 1000.0 / crafts));
        }
        LOGGER.info(line.toString());
        for (int i = 0; i < SEGMENTS; i++) {
            SEGMENT_NANOS[i] = 0L;
        }
        crafts = 0L;
        batches = 0L;
        batchNanos = 0L;
        optimisedNanos = 0L;
        slowCrafts = 0L;
        fastCrafts = 0L;
    }
}
