package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.implementations.PatternProviderScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.menu.SlotSemantics;

import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * Client screen for the pattern disk provider. Extends the original AE2 pattern-provider screen to
 * inherit its three toolbar buttons (blocking mode, lock crafting, show in sample access terminal);
 * the original one-stack-per-pattern slot is hidden since this provider drives its patterns from
 * inserted disks. Layout driven by the ScreenStyle JSON
 * ({@code assets/ae2/screens/ae2_pattern_disk/pattern_disk_provider.json}).
 */
public class PatternDiskProviderScreen extends PatternProviderScreen<PatternDiskProviderMenu> {

    public PatternDiskProviderScreen(PatternDiskProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/pattern_disk_provider.json"));
        // The disk-backed provider exposes its patterns via disk slots, not the original single-stack
        // pattern inventory the parent adds; keep that empty inventory out of the UI.
        setSlotsHidden(SlotSemantics.ENCODED_PATTERN, true);
    }
}
