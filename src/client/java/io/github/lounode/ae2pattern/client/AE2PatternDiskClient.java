package io.github.lounode.ae2pattern.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.client.gui.PatternDiskAssemblerScreen;
import io.github.lounode.ae2pattern.client.gui.PatternDiskProviderScreen;
import io.github.lounode.ae2pattern.client.gui.PatternTransfererScreen;
import io.github.lounode.ae2pattern.client.render.PatternDiskAssemblerRenderer;
import io.github.lounode.ae2pattern.core.AEPatternBlockEntities;
import io.github.lounode.ae2pattern.core.AEPatternMenus;

/**
 * Client-side entry point for AE2 Pattern Disk.
 */
@Mod(value = AE2PatternDisk.MOD_ID, dist = Dist.CLIENT)
public class AE2PatternDiskClient {

    public AE2PatternDiskClient(IEventBus modBus) {
        modBus.addListener(this::registerScreens);
        modBus.addListener(this::clientSetup);
        modBus.addListener(this::registerBlockRenderers);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(InitPatternDiskProperties::init);
    }

    private void registerBlockRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(AEPatternBlockEntities.PATTERN_DISK_ASSEMBLER.get(),
                PatternDiskAssemblerRenderer::new);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(AEPatternMenus.PATTERN_TRANSFERER.get(), PatternTransfererScreen::new);
        event.register(AEPatternMenus.PATTERN_DISK_PROVIDER.get(), PatternDiskProviderScreen::new);
        event.register(AEPatternMenus.PATTERN_DISK_ASSEMBLER.get(), PatternDiskAssemblerScreen::new);
    }
}
