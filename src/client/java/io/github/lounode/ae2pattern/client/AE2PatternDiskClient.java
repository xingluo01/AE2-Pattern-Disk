package io.github.lounode.ae2pattern.client;

import net.minecraft.client.resources.model.ModelResourceLocation;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import appeng.client.gui.style.StyleManager;

import io.github.lounode.ae2pattern.client.render.PatternDiskAssemblerRenderer;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;
import io.github.lounode.ae2pattern.client.gui.PatternDiskAssemblerScreen;
import io.github.lounode.ae2pattern.client.gui.PatternDiskProviderScreen;
import io.github.lounode.ae2pattern.client.gui.PatternTransfererScreen;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Client-side entry point for AE2 Pattern Disk.
 */
@Mod(value = AE2PatternDisk.MOD_ID, dist = Dist.CLIENT)
public class AE2PatternDiskClient {

    public AE2PatternDiskClient(IEventBus modBus) {
        modBus.addListener(this::registerScreens);
        modBus.addListener(this::clientSetup);
        modBus.addListener(this::registerBlockRenderers);
        modBus.addListener(this::registerAdditionalModels);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(InitPatternDiskProperties::init);
    }

    private void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(PatternDiskAssemblerRenderer.LIGHTS_MODEL);
    }

    private void registerBlockRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(AEPatternRegistries.BE_ASSEMBLER.get(),
                PatternDiskAssemblerRenderer::new);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(AEPatternRegistries.MENU_TRANSFERER.get(), PatternTransfererScreen::new);
        event.register(AEPatternRegistries.MENU_PROVIDER.get(), PatternDiskProviderScreen::new);
        event.register(AEPatternRegistries.MENU_ASSEMBLER.get(), PatternDiskAssemblerScreen::new);
        registerEncodingTerminalScreen(event);
    }

    private void registerEncodingTerminalScreen(RegisterMenuScreensEvent event) {
        var type = AEPatternRegistries.MENU_PATTERN_DISK_ENCODING_TERMINAL.get();
        event.register(type,
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<PatternDiskEncodingTermMenu, PatternDiskEncodingTermScreen>() {
                    @Override
                    public PatternDiskEncodingTermScreen create(PatternDiskEncodingTermMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        appeng.client.gui.style.ScreenStyle style = appeng.client.gui.style.StyleManager
                                .loadStyleDoc("/screens/ae2_pattern_disk/pattern_disk_encoding_terminal.json");
                        return new PatternDiskEncodingTermScreen(menu, playerInventory, title, style);
                    }
                });
    }
}
