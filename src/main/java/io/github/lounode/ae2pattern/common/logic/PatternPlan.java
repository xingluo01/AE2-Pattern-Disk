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
 * Pre-computed execution plan of one crafting pattern (批处理分子装配室).
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
     * One input slot of the pattern.
     *
     * @param multiplier how much one run consumes from this slot (pattern parallels)
     * @param candidates variants the pattern itself declares, in the pattern's own priority order
     * @param source     the pattern's own input slot, which owns the validity and remainder rules
     */
    public record Input(long multiplier, List<AEKey> candidates, IPatternDetails.IInput source) {

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
            var candidates = new ArrayList<AEKey>();
            for (var possible : input.getPossibleInputs()) {
                if (possible == null || possible.what() == null) {
                    continue;
                }
                candidates.add(possible.what());
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
