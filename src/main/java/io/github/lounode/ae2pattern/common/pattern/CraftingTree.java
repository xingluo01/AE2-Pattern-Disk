package io.github.lounode.ae2pattern.common.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

/**
 * A lightweight crafting-tree index over the patterns stored on a set of pattern disks.
 *
 * <p>Builds a {@code output -> patterns} map so we can resolve, from a target output, which patterns can
 * produce it and (bounded by the disk tree) how many of each intermediate pattern a chain needs. This is
 * the "build a tree from the disk recipes and analytically expand each step" layer that drives
 * demand-aware parallel dispatches at the assembler.</p>
 *
 * <p>All methods are read-only and cheap; the index is rebuilt when the disk contents change (see the
 * provider logic's fingerprint caching) — rebuilding a few hundred patterns is a one-shot cost, and pattern
 * decoding uses {@link PatternClassifier#decode} which reuses AE2's {@code PatternDetailsHelper}.</p>
 */
public final class CraftingTree {

    private final Map<AEKey, List<IPatternDetails>> byOutput = new HashMap<>();
    private final List<IPatternDetails> allPatterns = new ArrayList<>();

    private CraftingTree() {
    }

    /** Builds a tree from an iterable of encoded-pattern stacks. */
    public static CraftingTree fromPatternStacks(Iterable<ItemStack> stacks, Level level) {
        var tree = new CraftingTree();
        for (ItemStack stack : stacks) {
            if (!PatternClassifier.isEncodedPatternStack(stack)) {
                continue;
            }
            IPatternDetails details = PatternClassifier.decode(stack, level);
            if (details == null) {
                continue;
            }
            tree.add(details);
        }
        return tree;
    }

    private void add(IPatternDetails details) {
        allPatterns.add(details);
        for (GenericStack output : details.getOutputs()) {
            byOutput.computeIfAbsent(output.what(), k -> new ArrayList<>()).add(details);
        }
    }

    /** Patterns that can produce the given output key. */
    public List<IPatternDetails> patternsFor(AEKey output) {
        return byOutput.getOrDefault(output, List.of());
    }

    /** All patterns in the tree. */
    public List<IPatternDetails> allPatterns() {
        return Collections.unmodifiableList(allPatterns);
    }

    /**
     * Resolves how many times the chain needs the given intermediate output, by walking upstream from a
     * target output. Counts the units of {@code target} required to craft one unit of {@code goal} (via the
     * patterns that produce {@code goal}). Bounded by {@code maxSteps} for safety on deep/cyclic chains.
     */
    public long requiredOf(AEKey goal, AEKey target, int maxSteps) {
        return requiredOf(goal, target, maxSteps, new java.util.HashSet<>());
    }

    private long requiredOf(AEKey goal, AEKey target, int remainingSteps, java.util.Set<AEKey> visited) {
        if (remainingSteps <= 0 || goal.equals(target)) {
            return goal.equals(target) ? 1 : 0;
        }
        if (!visited.add(goal)) {
            return 0; // cycle guard
        }
        long total = 0;
        for (IPatternDetails details : patternsFor(goal)) {
            for (var input : details.getInputs()) {
                long needed = requiredOf(input.getPossibleInputs()[0].what(), target, remainingSteps - 1, visited);
                if (needed > 0) {
                    total += needed * input.getPossibleInputs()[0].amount() * input.getMultiplier();
                }
            }
        }
        return total;
    }
}
