package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The shared pattern pool of the batch molecular assembler: every encoded pattern on every inserted
 * pattern disk, decoded once and reused for both exposure and execution.
 *
 * <p>Chaining recipes inside the machine is deliberately <b>not</b> implemented. The crafting CPU books
 * every pushed pattern's outputs in its waiting list, so swallowing an intermediate product and crafting
 * it further would leave that bookkeeping unsatisfied and stall the whole job. Outputs therefore always
 * go back to the ME network and the CPU re-dispatches the next step (this is the machine's own copy of
 * the recipe pool, held for display and validation purposes).</p>
 */
public final class BatchRecipePool {

    private final List<IPatternDetails> patterns = new ArrayList<>();

    /**
     * Rebuilds the pool from the encoded pattern stacks found on the machine's pattern disks. Decoding
     * needs a level; without one the previous contents are kept, so a transiently unavailable level
     * cannot silently empty the pool (and with it the patterns exposed to the crafting service).
     */
    public void rebuild(List<ItemStack> encodedPatterns, Level level) {
        if (level == null) {
            return;
        }

        patterns.clear();
        for (var stack : encodedPatterns) {
            var details = PatternDetailsHelper.decodePattern(stack, level);
            if (details instanceof IMolecularAssemblerSupportedPattern) {
                // Only crafting / smithing / stonecutting patterns can be executed by this machine.
                patterns.add(details);
            }
        }
    }

    /** Every pattern the pool currently holds, in disk order. */
    public List<IPatternDetails> all() {
        return Collections.unmodifiableList(patterns);
    }
}
