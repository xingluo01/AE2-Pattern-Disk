package io.github.lounode.ae2pattern.integration.jade;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

import io.github.lounode.ae2pattern.common.block.BatchAssemblerBlock;
import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;
import io.github.lounode.ae2pattern.integration.jade.provider.BatchAssemblerProvider;

/**
 * Jade integration: who can report and who can display, registered as two paired halves.
 *
 * <p>Jade only asks for a block's server data when a client component is registered for the same block, so the
 * two registrations are not a choice - dropping either half leaves the tooltip empty.</p>
 */
@WailaPlugin
public class AE2PatternDiskJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(BatchAssemblerProvider.INSTANCE, BatchAssemblerBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(BatchAssemblerProvider.INSTANCE, BatchAssemblerBlock.class);
    }
}
