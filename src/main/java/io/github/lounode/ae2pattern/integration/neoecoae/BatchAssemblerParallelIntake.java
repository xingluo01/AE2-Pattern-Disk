package io.github.lounode.ae2pattern.integration.neoecoae;

import java.math.BigInteger;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

import cn.dancingsnow.neoecoae.api.me.provider.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider.Batch;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider.ExactPreparation;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider.Preparation;
import cn.dancingsnow.neoecoae.api.me.provider.ECOParallelCraftingProvider;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;

/**
 * Presents the batch assembler's parallel intake to NEO ECO.
 *
 * <p>An adapter rather than the machine implementing the interface itself: NEO ECO is an optional dependency,
 * so the machine must not name a type from it. Everything here forwards to the machine, which is what the
 * mixin this replaced did as well - the difference is that nothing is added to the machine's class, so the
 * machine loads identically in a pack that ships no NEO ECO.</p>
 *
 * <p>A fresh instance per lookup is fine: NEO ECO asks for the contract while dispatching, and the registered
 * factory is what keeps the per-instance lookup from needing the class to implement anything.</p>
 * <p>The job id NEO ECO passes is deliberately not used: this machine books the batch against its own buffer and
 * answers for one hand-over at a time, so it has nothing to attribute the job to. The ECO side carries the id
 * for its own accounting.</p>
 *
 * <p>Each lookup builds a new instance. The registration is keyed by class and asked for while jobs are
 * running, so there is nowhere sensible to keep one, and an instance is a machine reference and nothing else.</p>
 *
 * <p><b>Three contracts, one adapter.</b> The ordinary parallel intake below carries an {@code int} slot count,
 * which caps one hand-over at roughly 2.1 billion crafts. The two FastPath entry points carry a {@code long} and a
 * {@link BigInteger} respectively, and they are what a very large order needs - the machine's own estimate is
 * already long-valued ({@link BatchAssemblerBlockEntity#availableParallelSlotsFor}), so nothing is truncated here.</p>
 *
 * <p><b>FastPath stays dormant until NEO ECO asks the registry.</b> {@code ECOFastPathFacade} resolves providers
 * with {@code instanceof} plus two hard-coded mod bridges; it never consults {@code ECOParallelCraftingProviders}
 * (tracked as our upstream request in {@code docs/UPSTREAM_API_REQUESTS.md}, draft 4). This adapter implements
 * {@link ECOFastPathDispatchProvider} anyway, so the day that facade consults the registry these two methods start
 * being called without any change here. A partial upstream change cannot half-apply: {@code supports} alone turning
 * true still leaves {@code resolveProvider} returning null and the dispatch falling back to the ordinary path. Two
 * entry points stay outside that: {@code ECOFastPathFacade.prepareAllocated} still tests the interface directly,
 * and a CPU routing through {@code prepareParallel} keeps using the {@code int} capacity below.</p>
 */
final class BatchAssemblerParallelIntake implements ECOParallelCraftingProvider, ECOFastPathDispatchProvider {

    private final BatchAssemblerBlockEntity machine;

    BatchAssemblerParallelIntake(BatchAssemblerBlockEntity machine) {
        this.machine = machine;
    }

    // 上游 int 契约通道：空机器首次发配报 0（还没有当前派发样板可估），首批之后才回到批量，属已知行为。
    @Override
    public int eco$getAvailableParallelSlots() {
        return machine.availableParallelSlots();
    }

    @Override
    public boolean eco$pushPatternBatch(
            IPatternDetails pattern, KeyCounter[] inputTotal, long craftCount, @Nullable UUID craftingJobId) {
        return machine.acceptPatternBatch(pattern, inputTotal, craftCount);
    }

    /**
     * Resolves the {@code long} capacity, plus the commit target that moves a whole batch in at once.
     *
     * <p>Preparation touches nothing: the capacity comes from the same simulated probe the ordinary path uses,
     * and the dispatch closure is only handed over. All material movement happens inside it.</p>
     */
    @Override
    public @Nullable Preparation eco$prepareFastPath(ECOBatchDispatchContext context) {
        long capacity = machine.availableParallelSlotsFor(context.pattern());
        if (capacity <= 0L) {
            // No usable cell, or not even one complete craft fits: declining lets the CPU try another provider
            // instead of probing this one every tick for a hand-over it could never accept.
            return null;
        }
        return new Preparation(capacity, null, false, batch -> dispatchTotals(context, batch));
    }

    /**
     * Accepts a batch that arrives as flat totals.
     *
     * <p>Declines exact-input batches on purpose: those carry a {@code Map<AEKey, BigInteger>} that can exceed what
     * a long-booked cell buffer can express, and {@link Batch#inputTotal()} would throw while trying to narrow it.
     * Returning false is the documented refusal - the CPU restores the inputs and falls back to the ordinary path.</p>
     */
    private boolean dispatchTotals(ECOBatchDispatchContext context, Batch batch) {
        if (!batch.exactInputTotal().isEmpty()) {
            return false;
        }
        var totals = new KeyCounter();
        for (var stack : batch.inputTotal()) {
            if (stack == null || stack.what() == null || stack.amount() <= 0) {
                continue;
            }
            totals.add(stack.what(), stack.amount());
        }
        // One counter holding every key: the intake walks keys, not pattern slots, so the flat total is exactly
        // what it wants. The array itself is ours, which also keeps the intake from touching NEO ECO's lists.
        return machine.acceptPatternBatch(context.pattern(), new KeyCounter[] { totals }, batch.craftCount());
    }

    /**
     * Resolves an exact batch size for a big order.
     *
     * <p>The count is capped at the cell buffer's own room, never above what the CPU asked for: exceeding the
     * request throws inside NEO ECO's planner, while a smaller count is allowed and settled at the lower size.</p>
     */
    @Override
    public @Nullable ExactPreparation eco$prepareExactFastPath(ECOBatchDispatchContext context, BigInteger requested) {
        if (requested.signum() <= 0) {
            return null;
        }
        long capacity = machine.availableParallelSlotsFor(context.pattern());
        if (capacity <= 0L) {
            return null;
        }
        var crafts = requested.min(BigInteger.valueOf(capacity));
        if (crafts.signum() <= 0) {
            return null;
        }
        return new ExactPreparation(crafts, () -> dispatchScaled(context, crafts));
    }

    /**
     * Accepts an exact batch by scaling the pattern's one-copy inputs up to the agreed count.
     *
     * <p>The dispatch is a bare {@link java.util.function.BooleanSupplier}, so the totals have to be produced here
     * from {@link ECOBatchDispatchContext#inputCounters()} - which hands back a fresh set every call, so scaling
     * it cannot disturb NEO ECO's own state.</p>
     */
    private boolean dispatchScaled(ECOBatchDispatchContext context, BigInteger crafts) {
        // Local guards rather than trusting the caller: a non-positive count would divide by zero in the
        // saturation check below.
        if (crafts.signum() <= 0) {
            return false;
        }
        // A count that cannot be held in a long cannot be scaled into a long-booked buffer, and the cells can
        // never hold that many copies anyway. Decline rather than truncate.
        long count;
        try {
            count = crafts.longValueExact();
        } catch (ArithmeticException exceedsLong) {
            return false;
        }
        var perCopy = context.inputCounters();
        var totals = new KeyCounter[perCopy.length];
        for (int slot = 0; slot < perCopy.length; slot++) {
            var counter = new KeyCounter();
            for (var entry : perCopy[slot]) {
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                // Saturating instead of throwing: an overflow means the batch cannot fit in the cells, and a
                // refusal is what the CPU can act on. A throw would read as an indeterminate acceptance.
                if (amount > Long.MAX_VALUE / count) {
                    return false;
                }
                counter.add(entry.getKey(), amount * count);
            }
            totals[slot] = counter;
        }
        return machine.acceptPatternBatch(context.pattern(), totals, count);
    }
}
