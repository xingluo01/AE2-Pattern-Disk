package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;

import io.github.lounode.ae2pattern.common.menu.PatternDiskAssemblerMenu;

/**
 * Client screen for the pattern disk assembler (高效分子装配室).
 * Layout driven by {@code assets/ae2/screens/pattern_disk_assembler.json}.
 */
public class PatternDiskAssemblerScreen extends UpgradeableScreen<PatternDiskAssemblerMenu> {

    public PatternDiskAssemblerScreen(PatternDiskAssemblerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/pattern_disk_assembler.json"));
    }
}
