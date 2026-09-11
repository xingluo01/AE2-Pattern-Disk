package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.api.storage.StorageCells;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.slot.AppEngSlot;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;

/**
 * Menu of the batch molecular assembler: nine private storage-cell slots (material/output buffer) and
 * nine pattern disk slots (shared recipe pool). Speed cards occupy the inherited upgrade slots.
 */
public class BatchAssemblerMenu extends UpgradeableMenu<BatchAssemblerBlockEntity> {

    public static final MenuType<BatchAssemblerMenu> TYPE = MenuTypeBuilder
            .create(BatchAssemblerMenu::new, BatchAssemblerBlockEntity.class)
            .buildUnregistered(ResourceLocation.parse("ae2_pattern_disk:batch_molecular_assembler"));

    private final BatchAssemblerBlockEntity host;

    /** Batch delay mode mirrored to the client: false = standard (20 ticks), true = fast (5 ticks). */
    @GuiSync(9)
    public boolean fastBatchMode;

    public BatchAssemblerMenu(int id, Inventory playerInventory, BatchAssemblerBlockEntity host) {
        super(TYPE, id, playerInventory, host);
        this.host = host;

        registerClientAction("cancel_craft", host::cancelAndReturnContents);
        registerClientAction("set_batch_mode", Boolean.class, host::setFastBatchMode);

        if (isServerSide()) {
            // Full sync happens before the first broadcastChanges, so seed the field here to avoid a
            // one-tick stale tooltip/icon on the client.
            fastBatchMode = host.isFastBatchMode();
        }

        for (int i = 0; i < BatchAssemblerBlockEntity.CELL_SLOTS; i++) {
            addSlot(new CellSlot(this, host.getCellInventory(), i), AEPatternRegistries.BATCH_CELL);
        }
        for (int i = 0; i < BatchAssemblerBlockEntity.DISK_SLOTS; i++) {
            addSlot(new DiskSlot(this, host.getDiskInventory(), i), AEPatternRegistries.BATCH_DISK);
        }
    }

    /** Returns every buffered material to the ME network and clears the queued jobs. */
    public void cancelCraft() {
        sendClientAction("cancel_craft");
    }

    /**
     * Client-side request to switch the batch delay between standard (20 ticks) and fast (5 ticks).
     * Mirrors {@code PatternTransfererMenu#setTransferMode}: the client sends an absolute value rather
     * than a toggle, so duplicated clicks cannot desynchronise the mode.
     */
    public void setFastBatchMode(boolean fast) {
        if (isClientSide()) {
            sendClientAction("set_batch_mode", fast);
        } else {
            host.setFastBatchMode(fast);
        }
    }

    public boolean isFastBatchMode() {
        return fastBatchMode;
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            fastBatchMode = host.isFastBatchMode();
        }
        super.broadcastChanges();
    }

    /** Private storage-cell slot: while the machine is busy the cell is locked in place. */
    private static final class CellSlot extends AppEngSlot {
        private final BatchAssemblerMenu menu;

        private CellSlot(BatchAssemblerMenu menu, InternalInventory inventory, int slot) {
            super(inventory, slot);
            this.menu = menu;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            // Cells may not be inserted while jobs are buffered either: taking the cell out again is
            // already blocked, so allowing insertion would be an inconsistent half-lock.
            return !menu.host.hasBufferedWork() && !stack.isEmpty() && StorageCells.isCellHandled(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            // Extract must be blocked while anything is buffered: isBusy() only reports "cannot take
            // more jobs" and is false during normal operation, so it cannot guard item extraction.
            return super.mayPickup(player) && !menu.host.hasBufferedWork();
        }
    }

    /** Pattern disk slot: locked for the same reason as a cell slot once jobs are buffered. */
    private static final class DiskSlot extends AppEngSlot {
        private final BatchAssemblerMenu menu;

        private DiskSlot(BatchAssemblerMenu menu, InternalInventory inventory, int slot) {
            super(inventory, slot);
            this.menu = menu;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !menu.host.hasBufferedWork() && stack.getItem() instanceof PatternDiskItem;
        }

        @Override
        public boolean mayPickup(Player player) {
            return super.mayPickup(player) && !menu.host.hasBufferedWork();
        }
    }
}
