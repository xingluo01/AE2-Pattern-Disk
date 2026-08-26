package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.client.gui.Icon;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.menu.slot.AppEngSlot;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.core.AEPatternSlotSemantics;

/**
 * Menu for the pattern disk provider: shows the disk slots. Layout is driven by the ScreenStyle JSON
 * ({@code assets/ae2/screens/ae2_pattern_disk/pattern_disk_provider.json}).
 */
public class PatternDiskProviderMenu extends PatternProviderMenu {

    public static final MenuType<PatternDiskProviderMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskProviderMenu::new, PatternDiskProviderBlockEntity.class)
            .buildUnregistered(
                    net.minecraft.resources.ResourceLocation.parse("ae2_pattern_disk:pattern_disk_provider"));

    private final PatternDiskProviderBlockEntity host;

    public PatternDiskProviderMenu(int id, Inventory playerInv, PatternDiskProviderBlockEntity host) {
        super(PatternProviderMenu.TYPE, id, playerInv, host);
        this.host = host;

        // The parent already added its player/return slots; the original one-stack-per-pattern
        // inventory is hidden on the client (PatternDiskProviderScreen) since this provider drives
        // its patterns from inserted disks rather than directly placed sample stacks.

        var inv = host.getDiskInventory();
        for (int i = 0; i < PatternDiskProviderBlockEntity.DISK_SLOT_COUNT; i++) {
            this.addSlot(new DiskSlot(inv, i), AEPatternSlotSemantics.PROVIDER_DISK);
        }
    }

    public PatternDiskProviderBlockEntity getProvider() {
        return host;
    }

    private static class DiskSlot extends AppEngSlot {
        DiskSlot(InternalInventory inventory, int index) {
            super(inventory, index);
            // Pattern icon background, mirroring the original sample-provider's pattern slot styling.
            setIcon(Icon.BACKGROUND_ENCODED_PATTERN);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() instanceof PatternDiskItem;
        }
    }
}
