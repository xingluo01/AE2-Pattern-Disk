package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;

import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * Client screen for the pattern disk provider. Layout driven by the ScreenStyle JSON
 * ({@code assets/ae2/screens/pattern_disk_provider.json}).
 */
public class PatternDiskProviderScreen extends AEBaseScreen<PatternDiskProviderMenu> {

    public PatternDiskProviderScreen(PatternDiskProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/pattern_disk_provider.json"));
    }
}
