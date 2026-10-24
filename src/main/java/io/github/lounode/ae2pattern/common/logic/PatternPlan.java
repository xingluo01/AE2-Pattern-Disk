package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

/**
 * Pre-computed execution plan of one crafting pattern (批处理装配室).
 *
 * <p>A plan holds the parts of a pattern that never change: which variants each input slot declares, how
 * much of them one run consumes, and the aggregated main outputs. None of it touches the cell buffer, the
 * grid or the world, which is what makes it safe to build a plan on a worker thread while the server keeps
 * ticking; the storage IO that follows stays on the server thread.</p>
 *
 * <p>Container remainders are deliberately <em>not</em> cached: which variant ends up in a slot is only
 * known at run time (a tool may already have lost durability), and AE2 answers that question by re-running
 * the recipe for the exact variant. Delegating to {@link Input#remainingFor} keeps that semantics and keeps
 * recipe code off the worker threads.</p>
 */
public final class PatternPlan {

    /**
     * One variant the pattern declares for a slot, together with how much of it one template holds.
     *
     * <p>AE2 的输入契约是「{@code getMultiplier()} 份模板，每份 {@code getPossibleInputs()[i].amount()} 个」：
     * 合成样板里的物品模板恒为 1 件，而流体替换会把桶槽换成「一整桶」的流体模板——这份的量取自容器实际容量
     * （原版桶是 1000 mB，自定义桶是多少就是多少），而不是这个物品本身的件数。所以「一份模板的量」必须
     * 跟着候选一起记住——只看模板数会按 1 件去扣一整桶水。</p>
     *
     * @param key    样板声明的变体
     * @param amount 一份模板里含多少个 {@code key}（至少 1）
     */
    public record Candidate(AEKey key, long amount) {
    }

    /**
     * One input slot of the pattern.
     *
     * @param multiplier how many templates one run consumes from this slot (pattern parallels)
     * @param candidates variants the pattern itself declares, in the pattern's own priority order
     * @param source     the pattern's own input slot, which owns the validity and remainder rules
     */
    public record Input(long multiplier, List<Candidate> candidates, IPatternDetails.IInput source) {

        /**
         * The container item the given variant leaves behind, or {@code null} when it leaves none.
         *
         * <p>Asked of the pattern instead of being cached per variant: the variant that was actually
         * consumed may be one the pattern never listed (a worn tool), and for crafting patterns AE2
         * derives the remainder by re-running the recipe with that variant in the grid.</p>
         */
        public @Nullable AEKey remainingFor(AEKey usedKey) {
            return source.getRemainingKey(usedKey);
        }

        /**
         * Whether the pattern accepts this variant in the slot. This is AE2's own substitution gate: a
         * pattern encoded without substitution only accepts the exact variant it was built with.
         */
        public boolean accepts(AEKey key, Level level) {
            return source.isValid(key, level);
        }

        /** True when the pattern declares no variant at all for this slot. */
        public boolean isEmpty() {
            return candidates.isEmpty();
        }

        /**
         * How much of the given variant one run consumes from this slot: the template count times the amount
         * one template holds.
         *
         * <p>Returns 0 when the product cannot be represented or the pattern describes no usable amount, which
         * every caller treats as "this slot cannot be served" rather than as a free craft.</p>
         */
        public long neededFor(AEKey usedKey) {
            long perTemplate = amountPerTemplate(usedKey);
            if (multiplier <= 0 || perTemplate <= 0 || perTemplate > Long.MAX_VALUE / multiplier) {
                return 0L;
            }
            return multiplier * perTemplate;
        }

        /**
         * How much of the given variant one template holds.
         *
         * <p>A variant the pattern never listed can still be consumed: AE2 accepts same-item variants (a worn
         * tool) through {@link #accepts}. Those differ from a listed candidate only in secondary components, so
         * they are measured by the candidate they substitute for.</p>
         */
        private long amountPerTemplate(AEKey usedKey) {
            for (var candidate : candidates) {
                if (candidate.key().equals(usedKey)) {
                    return candidate.amount();
                }
            }
            var plain = usedKey.dropSecondary();
            for (var candidate : candidates) {
                if (java.util.Objects.equals(candidate.key().dropSecondary(), plain)) {
                    return candidate.amount();
                }
            }
            // Not derivable from the declaration: fall back to the bare template count, which is what an
            // item-shaped slot means anyway.
            return 1L;
        }
    }

    private final List<Input> inputs;
    private final Map<AEKey, Long> baseOutputs;

    private PatternPlan(List<Input> inputs, Map<AEKey, Long> baseOutputs) {
        this.inputs = inputs;
        this.baseOutputs = baseOutputs;
    }

    /**
     * Analyses a pattern. Pure: reads immutable pattern data only, never the cell buffer, the grid or the
     * world, so it may run on a worker thread.
     */
    public static PatternPlan of(IPatternDetails pattern) {
        var patternInputs = pattern.getInputs();
        var inputs = new ArrayList<Input>(patternInputs.length);
        for (var input : patternInputs) {
            if (input == null) {
                // Kept as a hole so the list stays index-aligned with the consumed-variant list.
                inputs.add(null);
                continue;
            }
            var candidates = new ArrayList<Candidate>();
            for (var possible : input.getPossibleInputs()) {
                if (possible == null || possible.what() == null) {
                    continue;
                }
                // A template always holds at least one unit; an amount of zero would otherwise turn the
                // slot into a free craft.
                candidates.add(new Candidate(possible.what(), Math.max(1L, possible.amount())));
            }
            inputs.add(new Input(input.getMultiplier(), List.copyOf(candidates), input));
        }

        var outputs = new LinkedHashMap<AEKey, Long>();
        for (var output : pattern.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0) {
                outputs.merge(output.what(), output.amount(), Long::sum);
            }
        }

        // Collections.unmodifiableList, not List.copyOf: the holes above are legitimate null elements.
        return new PatternPlan(Collections.unmodifiableList(inputs), Collections.unmodifiableMap(outputs));
    }

    /** Input slots consumed by one run, in pattern order; entries may be {@code null} for malformed slots. */
    public List<Input> inputs() {
        return inputs;
    }

    /** Main outputs of one run, already aggregated per key. Container remainders are added per run. */
    public Map<AEKey, Long> baseOutputs() {
        return baseOutputs;
    }
}
