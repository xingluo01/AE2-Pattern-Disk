package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;

import io.github.lounode.ae2pattern.common.block.entity.PatternTransfererBlockEntity;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternClassifier;
import io.github.lounode.ae2pattern.common.pattern.TransferMode;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Menu for the pattern transferer.
 *
 * <p>Slot layout is defined externally via the AE2 ScreenStyle JSON ({@code assets/ae2/screens/ae2_pattern_disk/pattern_transferer.json}):
 * 6 input slots (2x3), 6 application slots (2x3), and 1 blank output slot.</p>
 */
public class PatternTransfererMenu extends UpgradeableMenu<PatternTransfererBlockEntity> {

    public static final MenuType<PatternTransfererMenu> TYPE = MenuTypeBuilder
            .create(PatternTransfererMenu::new, PatternTransfererBlockEntity.class)
            .buildUnregistered(
                    net.minecraft.resources.ResourceLocation.parse("ae2_pattern_disk:pattern_transferer"));

    private final PatternTransfererBlockEntity host;

    @GuiSync(90)
    public TransferMode transferMode = TransferMode.STORE;

    public PatternTransfererMenu(int id, Inventory playerInv, PatternTransfererBlockEntity host) {
        super(TYPE, id, playerInv, host);
        this.host = host;
        this.transferMode = host.getMode();
        registerClientAction("setTransferMode", TransferMode.class, this::setTransferMode);
    }

    public void setTransferMode(TransferMode mode) {
        if (isClientSide()) {
            sendClientAction("setTransferMode", mode);
        } else {
            host.setMode(mode);
            this.transferMode = mode;
            broadcastChanges();
        }
    }

    public TransferMode getTransferMode() {
        return transferMode;
    }

    @Override
    protected void setupInventorySlots() {
        var host = getHost();
        var inv = host.getInventory().toContainer();

        // 6 input slots.
        for (int i = 0; i < PatternTransfererBlockEntity.INPUT_SLOT_COUNT; i++) {
            this.addSlot(new InputSlot(inv, PatternTransfererBlockEntity.inputSlot(i)),
                    AEPatternRegistries.TRANSFER_INPUT);
        }

        // 6 application slots.
        for (int i = 0; i < PatternTransfererBlockEntity.APPLICATION_SLOT_COUNT; i++) {
            this.addSlot(new DiskOnlySlot(inv, PatternTransfererBlockEntity.applicationSlot(i)),
                    AEPatternRegistries.TRANSFER_APPLICATION);
        }

        // Blank output slot.
        this.addSlot(new BlankOutputSlot(inv, PatternTransfererBlockEntity.OUTPUT_SLOT),
                AEPatternRegistries.TRANSFER_BLANK_OUTPUT);
    }

    public PatternTransfererBlockEntity getTransferer() {
        return host;
    }

    /**
     * Input slot: accepts encoded patterns or populated pattern disks.
     */
    private static class InputSlot extends Slot {
        InputSlot(Container container, int index) {
            super(container, index, 0, 0);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (stack.isEmpty()) {
                return false;
            }
            return stack.getItem() instanceof PatternDiskItem || PatternClassifier.isEncodedPatternStack(stack);
        }
    }

    /**
     * Application slot: accepts only pattern disks.
     */
    private static class DiskOnlySlot extends Slot {
        DiskOnlySlot(Container container, int index) {
            super(container, index, 0, 0);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() instanceof PatternDiskItem;
        }
    }

    /**
     * Blank output slot: only blank patterns may be placed (though written programmatically).
     */
    private static class BlankOutputSlot extends Slot {
        BlankOutputSlot(Container container, int index) {
            super(container, index, 0, 0);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && PatternClassifier.isBlankPattern(stack);
        }
    }
}
