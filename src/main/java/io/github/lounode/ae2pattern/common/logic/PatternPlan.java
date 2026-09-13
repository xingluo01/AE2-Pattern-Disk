package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

/**
 * Pre-computed execution plan of one crafting pattern (批处理分子装配室).
 *
 * <p>A plan holds everything that can be derived from the pattern alone: which variants each input slot
 * accepts, how many of them one run consumes, the container remainder every variant leaves behind, and
 * the aggregated main outputs. None of it touches the cell buffer, the grid or the world, which is what
 * makes it safe to build a plan on a worker thread while the server keeps ticking; the storage IO that
 * follows stays on the server thread.</p>
 *
 * <p>All fields are immutable, so a plan can be handed from an analysis worker to the tick thread without
 * any further synchronisation. Building one per pattern also keeps the per-run hot path free of repeated
 * {@code getPossibleInputs()} array walks and {@code getRemainingKey()} container lookups.</p>
 */
public final class PatternPlan {

    /**
     * One input slot of the pattern.
     *
     * @param multiplier           how much one run consumes from this slot (pattern parallels)
     * @param candidates           variants that can satisfy the slot, in the pattern's own priority order
     * @param remainingByCandidate container remainder per variant; a {@code null} value means the variant
     *                             leaves no container behind
     */
    public record Input(long multiplier, List<AEKey> candidates, Map<AEKey, AEKey> remainingByCandidate) {

        /**
         * The container item the given variant leaves behind, or {@code null} when it leaves none.
         * The key has to be the variant that was actually consumed - substitutes may carry different
         * containers even though they fill the same slot.
         */
        public AEKey remainingFor(AEKey usedKey) {
            return remainingByCandidate.get(usedKey);
        }

        /** True when no variant can ever satisfy this slot. */
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
            var remainders = new HashMap<AEKey, AEKey>();
            for (var possible : input.getPossibleInputs()) {
                if (possible == null || possible.what() == null) {
                    continue;
                }
                candidates.add(possible.what());
                remainders.put(possible.what(), input.getRemainingKey(possible.what()));
            }
            inputs.add(new Input(input.getMultiplier(),
                    List.copyOf(candidates),
                    // Map.copyOf rejects null values, and "leaves no container" is a legitimate null here.
                    Collections.unmodifiableMap(remainders)));
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
