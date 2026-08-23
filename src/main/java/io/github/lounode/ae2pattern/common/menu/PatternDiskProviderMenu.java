package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.MenuTypeBuilder;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.core.AEPatternSlotSemantics;

/**
 * Menu for the pattern disk provider: shows the disk slots. Layout is driven by the ScreenStyle JSON
 * ({@code assets/ae2/screens/pattern_disk_provider.json}).
 */
public class PatternDiskProviderMenu extends AEBaseMenu {

    public static final MenuType<PatternDiskProviderMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskProviderMenu::new, PatternDiskProviderBlockEntity.class)
            .build("pattern_disk_provider");

    private final PatternDiskProviderBlockEntity host;

    public PatternDiskProviderMenu(int id, Inventory playerInv, PatternDiskProviderBlockEntity host) {
        super(TYPE, id, playerInv, host);
        this.host = host;

        var inv = host.getDiskInventory().toContainer();
        for (int i = 0; i < PatternDiskProviderBlockEntity.DISK_SLOT_COUNT; i++) {
            this.addSlot(new DiskSlot(inv, i), AEPatternSlotSemantics.PROVIDER_DISK);
        }

        // Return inventory slots (products returned by the crafting machines), mirroring AE2's provider.
        var returnInv = host.getLogic().getReturnInv();
        if (returnInv != null) {
            var returnWrapper = returnInv.createMenuWrapper();
            for (int i = 0; i < returnWrapper.size(); i++) {
                this.addSlot(new appeng.menu.slot.AppEngSlot(returnWrapper, i), SlotSemantics.STORAGE);
            }
        }

        for (int i = 0; i < 9; i++) {
            this.addSlot(new Slot(playerInv, i, 0, 0), SlotSemantics.PLAYER_HOTBAR);
        }
        for (int i = 0; i < 27; i++) {
            this.addSlot(new Slot(playerInv, i + 9, 0, 0), SlotSemantics.PLAYER_INVENTORY);
        }
    }

    public PatternDiskProviderBlockEntity getProvider() {
        return host;
    }

    private static class DiskSlot extends Slot {
        DiskSlot(Container container, int index) {
            super(container, index, 0, 0);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() instanceof PatternDiskItem;
        }
    }
}
