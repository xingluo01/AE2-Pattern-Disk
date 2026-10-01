package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;

/**
 * 批处理装配机的执行引擎：一条作业怎么兑现成产物。
 *
 * <p>它在「摊销块」与「单件慢路径」两条路上都做同一件事——按 {%@code PatternPlan} 的声明解析材料、扣料、
 * 调配方、把产出记进平滑回送队列——差别只在一次兑现多少件。摊销块只能用在 {@link #canAmortise} 认可的情形：
 * 每个槽位都由同一个声明候选覆盖整批，于是这一批与单件的差别只剩倍率 {@code jobs}，逐件做会得到完全相同
 * 的结果 {@code jobs} 次。任何一个槽位做不到（缓冲里躺着几件磨损程度不同的工具就是典型），这一批就退回逐件。
 * </p>
 *
 * <p><b>配方是事实，样板是声明。</b>摊销块仍然只问配方一次，并把它的回答与样板声明的主产物比对；不一致
 * 就告警一次并退回逐件——第三方样板声明与实际配方不符时，机器的交付必须按配方走，而不是按声明走。</p>
 *
 * <p><b>能量的扣取时机是有意的</b>：先扣料、再扣能量、最后记账。料可以还，能量还不回来，所以任何一步失败都
 * 必须发生在还没有付出不可逆代价之前。料被退回后机器回到与运行时完全相同的状态，作业留在队列里等下一 tick。</p>
 *
 * <p>引擎不持有方块实体：缓冲、计划缓存、回送队列、世界、能量、状态回写与位置全部由构造期注入，于是它可以
 * 单独读懂，也可以在服务端线程之外被检验（真正的执行仍只在服务端 tick 线程上发生）。</p>
 */
public final class BatchSynthesisEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.batch_assembler");

    /** At most this many remainder slots are read from one recipe answer; a legal grid has far fewer. */
    private static final int MAX_BATCH_REMAINDER_SLOTS = 16;

    private final CellBuffer cells;
    private final PatternPlanCache plans;
    private final SmoothReturnQueue returns;
    private final Supplier<Level> level;
    private final Supplier<BlockPos> position;
    /** 扣一次执行能量的判断（取网格能量服务并真正扣取），由方块实体提供。 */
    private final BooleanSupplier power;
    /** 状态回写：失败要说出是哪一种失败，而状态住在方块实体上。 */
    private final Consumer<BatchWorkState> state;

    public BatchSynthesisEngine(CellBuffer cells, PatternPlanCache plans, SmoothReturnQueue returns,
            Supplier<Level> level, Supplier<BlockPos> position, BooleanSupplier power,
            Consumer<BatchWorkState> state) {
        this.cells = cells;
        this.plans = plans;
        this.returns = returns;
        this.level = level;
        this.position = position;
        this.power = power;
        this.state = state;
    }

    /**
     * The largest part of {@code remaining} crafts that still runs on one variant per slot, or 0 when not even
     * two crafts can share a variant.
     *
     * <p>Exists so that a mixed buffer does not collapse straight to one craft at a time: when only part of the
     * order is covered by a single damage variant - say four fresh tools next to twenty worn ones - the covered
     * part is still amortised and only the rest is run craft by craft. The search is a binary one because
     * coverage only improves as the chunk shrinks: if {@code k} crafts are covered, so is every smaller number.</p>
     *
     * <p>{@code ceiling} carries the previous block's size back in. A run only ever consumes material, so the
     * covered part cannot grow from one block to the next and the search never has to look above the last
     * answer again. That matters because the search is the one cost a block pays on top of its single
     * operation: without the bound, a buffer made of many tiny variants would spend more on searches than the
     * blocks themselves save.</p>
     */
    public long uniformChunk(PatternPlan plan, long remaining, long ceiling) {
        if (remaining <= 1) {
            return 0;
        }
        long hi = Math.min(remaining, Math.max(1L, ceiling));
        if (hi >= remaining && canAmortise(plan, remaining)) {
            return remaining;
        }
        long lo = 1;
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            if (canAmortise(plan, mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo >= 2 ? lo : 0;
    }

    private boolean canAmortise(PatternPlan plan, long jobs) {
        if (jobs <= 1) {
            // Nothing to amortise, and short-circuiting keeps a batch of one from paying the simulation twice.
            return false;
        }
        var world = level.get();
        if (world == null) {
            return false;
        }
        for (var input : plan.inputs()) {
            if (input == null || input.isEmpty()) {
                continue;
            }
            long needed = input.multiplier();
            if (needed <= 0 || needed > Long.MAX_VALUE / jobs) {
                return false;
            }
            long total = needed * jobs;
            boolean covered = false;
            for (var candidate : input.candidates()) {
                if (cells.extract(candidate, total, Actionable.SIMULATE) >= total) {
                    if (needed > 1 && plans.remainderFor(input, candidate) != null) {
                        // A slot that wants more than one item and leaves a container behind is answered by two
                        // different conventions: the per-slot API scales the remainder with the slot's demand,
                        // while the whole-grid API answers per grid position. Running those crafts one at a time
                        // is the only way to keep the machine's return exactly equal to what the CPU expects.
                        return false;
                    }
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                // Either the buffer holds damage variants that do not add up to one key, or the material is
                // simply not there yet. Both are the per-craft path's business.
                return false;
            }
        }
        return true;
    }

    /**
     * Runs {@code jobs} crafts of one pattern as a single unit: one variant lookup, one verified recipe call,
     * one consumption, one output booking.
     *
     * <p>Only reached through {@link #canAmortise}, which guarantees that every slot is served by one declared
     * candidate for the whole batch - so all of these crafts consume the same keys, yield the same outputs and
     * leave the same container remainders. With that, the batch differs from a single craft by nothing but the
     * multiplier {@code jobs} (exactly how NEO ECO's parallel hand-over describes it), and the per-craft work
     * would have produced the identical result {@code jobs} times.</p>
     *
     * <p>The recipe itself is still asked once, and its answer is compared against the plan's declared outputs:
     * the plan is a declaration, the recipe is the truth. A mismatch - a third-party pattern whose recipe
     * returns something other than what it declares, or an input whose template amount the machine does not
     * model - is warned about once per pattern and falls back to one craft at a time.</p>
     *
     * @return {@code true} when the whole batch was consumed and booked
     */
    public boolean amortise(IPatternDetails patternDetails, PatternPlan plan, long jobs) {
        var world = level.get();
        if (world == null || jobs <= 0) {
            return false;
        }
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern supported)) {
            return false;
        }

        var resolved = resolveUniformInputs(plan, jobs);
        if (resolved == null) {
            return false;
        }

        var grid = buildCraftingGrid(supported, plan, resolved);
        if (grid == null) {
            return false;
        }

        // Work out the recipe's answer before spending anything. Nothing is consumed, booked or paid yet, so a
        // rejecting recipe or a mismatching declaration costs this batch only its fast path - and the grid and
        // the answer describe the same crafts either way.
        GenericStack sampleOutput;
        NonNullList<ItemStack> remainders;
        try {
            sampleOutput = GenericStack.fromItemStack(supported.assemble(grid, world));
            remainders = supported.getRemainingItems(grid);
        } catch (RuntimeException broken) {
            LOGGER.warn("Could not run the recipe of pattern {} at {}; falling back to one craft at a time",
                    patternDetails.getClass().getName(), position.get(), broken);
            return false;
        }
        if (!outputMatchesDeclaration(plan, sampleOutput)) {
            noteWarned(patternDetails);
            return false;
        }

        // Take the material first: a shortfall can be handed straight back with rollbackInputs, so the machine
        // never keeps half a batch. Energy is taken after the material and before anything is booked, because a
        // spent extraction can be returned but spent energy cannot.
        var consumed = new LinkedHashMap<AEKey, Long>();
        for (int i = 0; i < plan.inputs().size(); i++) {
            var input = plan.inputs().get(i);
            var usedKey = resolved.get(i);
            if (input == null || usedKey == null) {
                continue;
            }
            long needed = input.multiplier() * jobs;
            if (cells.extract(usedKey, needed, Actionable.MODULATE) < needed) {
                rollbackInputs(consumed);
                state.accept(BatchWorkState.INPUTS_UNAVAILABLE);
                return false;
            }
            consumed.merge(usedKey, needed, Long::sum);
        }

        if (!power.getAsBoolean()) {
            // Nothing has been booked yet, so handing the material back leaves the machine exactly as it was.
            rollbackInputs(consumed);
            state.accept(BatchWorkState.NO_POWER);
            return false;
        }

        // Book what leaves the machine: the verified main output and the pattern's own container remainders,
        // both scaled by the batch size. The power is already paid and the material already taken, so from
        // here on the batch cannot fail.
        addBookedOutput(sampleOutput.what(), sampleOutput.amount() * jobs);
        int remainderSlots = Math.min(remainders.size(), MAX_BATCH_REMAINDER_SLOTS);
        for (int i = 0; i < remainderSlots; i++) {
            var stack = GenericStack.fromItemStack(remainders.get(i));
            if (stack != null) {
                addBookedOutput(stack.what(), stack.amount() * jobs);
            }
        }

        state.accept(BatchWorkState.WORKING);
        return true;
    }

    /**
     * Resolves one variant per slot for the whole batch: only the candidates the pattern itself declares, and
     * only when a single one of them still covers all {@code jobs} crafts on its own. That is the same test as
     * {@link #canAmortise}, repeated here so the batch path cannot be entered on a stale decision.
     *
     * @return the key per input slot ({@code null} for holes), or {@code null} when the batch cannot be sized
     */
    private @Nullable List<AEKey> resolveUniformInputs(PatternPlan plan, long jobs) {
        var inputs = plan.inputs();
        var resolved = new ArrayList<AEKey>(inputs.size());
        for (var input : inputs) {
            if (input == null || input.isEmpty()) {
                resolved.add(null);
                continue;
            }
            long needed = input.multiplier();
            if (needed <= 0 || needed > Long.MAX_VALUE / jobs) {
                return null;
            }
            long total = needed * jobs;
            AEKey covered = null;
            for (var candidate : input.candidates()) {
                if (cells.extract(candidate, total, Actionable.SIMULATE) >= total) {
                    covered = candidate;
                    break;
                }
            }
            if (covered == null) {
                return null;
            }
            resolved.add(covered);
        }
        return resolved;
    }

    /** Builds a nine-slot grid for one craft of the pattern, using the resolved variant of every slot. */
    private @Nullable CraftingInput buildCraftingGrid(
            IMolecularAssemblerSupportedPattern supported, PatternPlan plan, List<AEKey> resolved) {
        var table = new KeyCounter[resolved.size()];
        for (int i = 0; i < resolved.size(); i++) {
            table[i] = new KeyCounter();
            var key = resolved.get(i);
            var input = plan.inputs().get(i);
            if (key != null && input != null) {
                // One craft's worth for the slot: the amount the pattern declares for this input.
                table[i].add(key, input.multiplier());
            }
        }
        var items = new ItemStack[9];
        for (int i = 0; i < items.length; i++) {
            items[i] = ItemStack.EMPTY;
        }
        try {
            supported.fillCraftingGrid(table, (slot, stack) -> {
                if (slot >= 0 && slot < items.length) {
                    items[slot] = stack.copy();
                }
            });
            return CraftingInput.of(3, 3, List.of(items));
        } catch (RuntimeException broken) {
            // Third-party fill implementations: a broken one must not take the tick down with it.
            LOGGER.warn("Could not build a crafting grid for pattern {} at {}; falling back to one craft at a time",
                    plan.getClass().getName(), position.get(), broken);
            return null;
        }
    }

    /**
     * Whether the batch's own recipe answer agrees with what the pattern declares for a single main output.
     * A pattern declaring anything other than exactly one main output cannot be checked this way.
     */
    private boolean outputMatchesDeclaration(PatternPlan plan, GenericStack sampleOutput) {
        var outputs = plan.baseOutputs();
        if (sampleOutput == null || outputs.size() != 1) {
            return false;
        }
        var declared = outputs.entrySet().iterator().next();
        return declared.getKey().equals(sampleOutput.what()) && declared.getValue() == sampleOutput.amount();
    }

    /**
     * 把一份产出记进平滑回送队列。保留这个方法名而不让调用点直接调 {@code returns.add}：两处是「产出已产出、
     * 网络还没收到」的记账语义，与队列自己的入队不是同一层的话。
     */
    private void addBookedOutput(AEKey key, long amount) {
        returns.add(key, amount);
    }

    /**
     * Notes once per pattern that only part of its order shared one damage variant, so the covered part was
     * assembled as a batch and the rest one craft at a time. Purely informational - it is the expected outcome
     * for a buffer that holds tools of several damage levels.
     */
    public void notePartialFallback(IPatternDetails patternDetails) {
        plans.noteDamageFallback(patternDetails);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Pattern {} at {} is served by several variants at once; the covered part runs as a "
                    + "batch and the rest one craft at a time", patternDetails.getClass().getName(), position.get());
        }
    }

    /** Warns once per pattern that its recipe disagrees with what it declares, then keeps the slow path. */
    private void noteWarned(IPatternDetails patternDetails) {
        plans.noteWarned(patternDetails);
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn("Pattern {} at {} does not assemble to what it declares; running it one craft at a time",
                    patternDetails.getClass().getName(), position.get());
        }
    }

    /**
     * Executes one pattern from the cell buffer: inputs are consumed first, then the whole output set
     * (main outputs + container remainders) enters the smooth-return queue. Inputs are rolled back, and
     * the run deferred, only when the machine cannot run at all (no power) or when the buffer cannot
     * supply the material - never because the network is full, since outputs queue up instead. Internal
     * chaining is deliberately not performed - see {@link BatchRecipePool}.
     */
    public boolean craftOnce(PatternPlan plan, KeyCounter buffer) {
        var resolved = resolveInputs(plan, buffer);
        if (resolved == null) {
            state.accept(BatchWorkState.INPUTS_UNAVAILABLE);
            return false;
        }
        var consumed = consumeInputs(plan, resolved);
        if (consumed == null) {
            state.accept(BatchWorkState.INPUTS_UNAVAILABLE);
            return false;
        }

        // Collect every produced key (main outputs + container remainders) before touching the network.
        // The main outputs arrive pre-aggregated from the plan, so they are not walked again per run.
        var outputs = new LinkedHashMap<AEKey, Long>(plan.baseOutputs());
        var inputs = plan.inputs();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            if (input == null || input.isEmpty()) {
                continue;
            }
            // Container remainders are computed against the variant actually consumed (the inputs may
            // accept substitutes whose containers differ).
            var usedKey = consumed.usedByInput().get(i);
            if (usedKey == null) {
                continue;
            }
            try {
                // Container remainders are derived from the variant actually consumed - buckets, bottles
                // and other containers must be handed back, otherwise they vanish and the crafting CPU
                // keeps waiting for its expected container items forever. The same call is what returns a
                // worn tool to the network: AE2 derives the remainder by re-running the recipe for the
                // variant that was actually consumed.
                var remaining = plans.remainderFor(input, usedKey);
                long consumedCount = input.multiplier();
                if (remaining != null && consumedCount > 0) {
                    // AE2 books one remaining item per occupied slot.
                    outputs.merge(remaining, consumedCount, Long::sum);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to compute container remainder for pattern slot {} at {}; rolling back",
                        i, position.get(), e);
                rollbackInputs(consumed.byKey());
                state.accept(BatchWorkState.REMAINDER_FAILED);
                return false;
            }
        }

        // Defer (keeping the inputs in the cell buffer) when the machine cannot run at all.
        if (!power.getAsBoolean()) {
            rollbackInputs(consumed.byKey());
            state.accept(BatchWorkState.NO_POWER);
            return false;
        }
        // Outputs enter the smooth-return queue instead of hitting the network storage in one burst:
        // the owner hands them over on the next tick.
        for (var entry : outputs.entrySet()) {
            returns.add(entry.getKey(), entry.getValue());
        }
        return true;
    }

    /**
     * Resolves the variant to consume for every input slot, in AE2's own order: the variants the pattern
     * spells out first, then any same-item variant the pattern accepts. That second step is what lets a
     * slot take a tool that already lost durability - AE2's crafting patterns accept such a variant through
     * {@code IInput.isValid} as long as the pattern was encoded with substitution enabled, and a pattern
     * encoded without it rejects everything that is not an exact match.
     *
     * @return the key per input slot ({@code null} for holes), or {@code null} when a slot cannot be filled
     */
    private @Nullable List<AEKey> resolveInputs(PatternPlan plan, KeyCounter buffer) {
        var world = level.get();
        var inputs = plan.inputs();
        var resolved = new ArrayList<AEKey>(inputs.size());
        for (var input : inputs) {
            if (input == null) {
                resolved.add(null);
                continue;
            }
            var key = resolveVariant(input, world, buffer);
            if (key == null) {
                return null;
            }
            resolved.add(key);
        }
        return resolved;
    }

    private @Nullable AEKey resolveVariant(PatternPlan.Input input, @Nullable Level world, KeyCounter buffer) {
        long needed = input.multiplier();
        if (needed < 0) {
            // Malformed slot: a negative requirement must never reach the cells as a negative extract.
            return null;
        }
        for (var candidate : input.candidates()) {
            if (cells.extract(candidate, needed, Actionable.SIMULATE) >= needed) {
                return candidate;
            }
        }
        if (world == null) {
            return null;
        }
        // Nothing the pattern listed is available: look for a same-item variant sitting in the buffer. The
        // pattern itself decides which variants it accepts, so this cannot widen a pattern's contract.
        for (var candidate : input.candidates()) {
            if (!(candidate instanceof appeng.api.stacks.AEItemKey itemCandidate)) {
                continue;
            }
            for (var variant : buffer.findFuzzy(itemCandidate, FuzzyMode.IGNORE_ALL)) {
                var key = variant.getKey();
                if (key.equals(candidate) || !input.accepts(key, world)) {
                    continue;
                }
                if (cells.extract(key, needed, Actionable.SIMULATE) >= needed) {
                    return key;
                }
            }
        }
        return null;
    }

    /**
     * Consumes the resolved variants for one run. Returns the per-key totals (for rollback) plus the
     * variant consumed per input slot, or {@code null} when a slot turned out to be empty in the meantime
     * - in that case everything this run already took is put back.
     */
    private @Nullable Consumption consumeInputs(PatternPlan plan, List<AEKey> resolved) {
        var byKey = new LinkedHashMap<AEKey, Long>();
        var inputs = plan.inputs();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            var usedKey = resolved.get(i);
            if (input == null || usedKey == null) {
                continue;
            }
            long needed = input.multiplier();
            if (cells.extract(usedKey, needed, Actionable.MODULATE) < needed) {
                // The simulation and the real extraction disagreed (something else emptied the slot in
                // between): hand back what this run took and leave the job queued.
                rollbackInputs(byKey);
                return null;
            }
            byKey.merge(usedKey, needed, Long::sum);
        }
        return new Consumption(byKey, resolved);
    }

    /** Per-run consumption record: {@code byKey} drives rollbacks, {@code usedByInput} drives container remainders. */
    private record Consumption(LinkedHashMap<AEKey, Long> byKey, List<AEKey> usedByInput) {
    }

    /** Puts rolled-back inputs (and only those) back into the cell buffer they came from. */
    private void rollbackInputs(LinkedHashMap<AEKey, Long> consumed) {
        for (var entry : consumed.entrySet()) {
            cells.insert(entry.getKey(), entry.getValue(), Actionable.MODULATE);
        }
    }
}
