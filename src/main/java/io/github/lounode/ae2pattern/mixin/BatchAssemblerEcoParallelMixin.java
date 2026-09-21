package io.github.lounode.ae2pattern.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

import cn.dancingsnow.neoecoae.api.me.provider.ECOParallelCraftingProvider;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;

/**
 * Adds NEO ECO's parallel intake contract to the batch assembler.
 *
 * <p>ECO looks the contract up with {@code instanceof} on the provider instance itself, so the interface has
 * to sit on this class - a separate wrapper would never be found. Injecting it here instead of declaring it
 * on {@link BatchAssemblerBlockEntity} is what keeps the machine free of ECO types: without ECO this mixin is
 * never applied (see {@link AEPDMixinPlugin}), so the machine class still loads in a pack that ships no ECO.</p>
 *
 * <p>Both bridges only forward to the machine; the bookkeeping stays there.</p>
 */
@Mixin(BatchAssemblerBlockEntity.class)
public abstract class BatchAssemblerEcoParallelMixin implements ECOParallelCraftingProvider {

    @Override
    public int eco$getAvailableParallelSlots() {
        return ((BatchAssemblerBlockEntity) (Object) this).availableParallelSlots();
    }

    @Override
    public boolean eco$pushPatternBatch(
            IPatternDetails pattern, KeyCounter[] inputTotal, long craftCount, UUID craftingJobId) {
        return ((BatchAssemblerBlockEntity) (Object) this).acceptPatternBatch(pattern, inputTotal, craftCount);
    }
}
