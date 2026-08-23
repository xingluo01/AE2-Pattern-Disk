package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;

import io.github.lounode.ae2pattern.common.menu.PatternTransfererMenu;

/**
 * Client screen for the pattern transferer. Layout is driven by the ScreenStyle JSON
 * ({@code assets/ae2/screens/pattern_transferer.json}), loaded through AE2's {@link StyleManager}.
 */
public class PatternTransfererScreen extends UpgradeableScreen<PatternTransfererMenu> {

    public PatternTransfererScreen(PatternTransfererMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/pattern_transferer.json"));
    }
}
