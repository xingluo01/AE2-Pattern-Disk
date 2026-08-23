package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.interfaces.IProgressProvider;
import appeng.menu.slot.AppEngSlot;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.core.AEPatternSlotSemantics;

/**
 * Menu for the pattern disk assembler (高效分子装配室).
 *
 * <p>Exposes each of the {@link PatternDiskAssemblerBlockEntity#THREADS} execution units as a read-only
 * view: a 3x3 crafting grid + the unit's output slot (both shown through {@link AppEngSlot}s bound to the
 * unit's own {@code AppEngInternalInventory}) plus a per-unit progress bar driven by {@code @GuiSync}
 * fields. The screen arranges the units in an EAE-matrices-like scrolling list; the layout is declared in
 * the ScreenStyle JSON ({@code assets/ae2/screens/pattern_disk_assembler.json}).</p>
 *
 * <p>Note: AE2's {@code @GuiSync} supports only scalar fields (int/boolean/enum/...), so per-unit state
 * is broadcast as THREADS individual fields ({@code unitProgress0..7}, {@code unitBusy0..7}).</p>
 */
public class PatternDiskAssemblerMenu extends UpgradeableMenu<PatternDiskAssemblerBlockEntity>
        implements IProgressProvider {

    public static final MenuType<PatternDiskAssemblerMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskAssemblerMenu::new, PatternDiskAssemblerBlockEntity.class)
            .build("pattern_disk_assembler");

    private final PatternDiskAssemblerBlockEntity host;

    /** Number of units that currently have a plan (broadcast to the client for the top status line). */
    @GuiSync(0)
    public int runningThreads;

    @GuiSync(1)
    public int unitProgress0;
    @GuiSync(2)
    public int unitProgress1;
    @GuiSync(3)
    public int unitProgress2;
    @GuiSync(4)
    public int unitProgress3;
    @GuiSync(5)
    public int unitProgress4;
    @GuiSync(6)
    public int unitProgress5;
    @GuiSync(7)
    public int unitProgress6;
    @GuiSync(8)
    public int unitProgress7;

    @GuiSync(9)
    public boolean unitBusy0;
    @GuiSync(10)
    public boolean unitBusy1;
    @GuiSync(11)
    public boolean unitBusy2;
    @GuiSync(12)
    public boolean unitBusy3;
    @GuiSync(13)
    public boolean unitBusy4;
    @GuiSync(14)
    public boolean unitBusy5;
    @GuiSync(15)
    public boolean unitBusy6;
    @GuiSync(16)
    public boolean unitBusy7;

    public PatternDiskAssemblerMenu(int id, Inventory playerInv, PatternDiskAssemblerBlockEntity host) {
        super(TYPE, id, playerInv, host);
        this.host = host;
        // Register the per-unit slots here (after super) so the host is already initialised; AE2's
        // UpgradeableMenu does NOT expose a reliable per-mod slot hook before field init (super()
        // calls setupConfig() while this.host is still null).
        registerUnitSlots();
    }

    private void registerUnitSlots() {
        var assembler = getHost();
        for (int unit = 0; unit < PatternDiskAssemblerBlockEntity.THREADS; unit++) {
            var grid = assembler.getUnitGrid(unit);
            for (int i = 0; i < PatternDiskAssemblerBlockEntity.GRID_SIZE; i++) {
                addSlot(newReadOnlySlot(grid, i), AEPatternSlotSemantics.ASSEMBLER_GRID[unit]);
            }
            // Output slot lives at index GRID_SIZE in the unit's grid inventory.
            addSlot(newReadOnlySlot(grid, PatternDiskAssemblerBlockEntity.GRID_SIZE),
                    AEPatternSlotSemantics.ASSEMBLER_OUTPUT[unit]);
        }
    }

    private static Slot newReadOnlySlot(appeng.api.inventories.InternalInventory inv, int index) {
        // AE2's standard read-only slot: mayPlace / mayPickup / remove are all safe (returns/moves
        // nothing), and setNotDraggable blocks dragging. This is the same pattern AE2 uses for
        // informational slots (its {@link appeng.menu.slot.FakeSlot} keeps the child overrides).
        return new appeng.menu.slot.FakeSlot(inv, index) {
            @Override
            public int getMaxStackSize() {
                return 0; // also disable shift-click extraction of the machine output
            }
        }.setNotDraggable();
    }

    @Override
    public void broadcastChanges() {
        runningThreads = 0;
        unitProgress0 = host.getUnitProgress(0);
        unitProgress1 = host.getUnitProgress(1);
        unitProgress2 = host.getUnitProgress(2);
        unitProgress3 = host.getUnitProgress(3);
        unitProgress4 = host.getUnitProgress(4);
        unitProgress5 = host.getUnitProgress(5);
        unitProgress6 = host.getUnitProgress(6);
        unitProgress7 = host.getUnitProgress(7);
        unitBusy0 = host.isUnitBusy(0);
        unitBusy1 = host.isUnitBusy(1);
        unitBusy2 = host.isUnitBusy(2);
        unitBusy3 = host.isUnitBusy(3);
        unitBusy4 = host.isUnitBusy(4);
        unitBusy5 = host.isUnitBusy(5);
        unitBusy6 = host.isUnitBusy(6);
        unitBusy7 = host.isUnitBusy(7);
        for (int i = 0; i < PatternDiskAssemblerBlockEntity.THREADS; i++) {
            if (host.isUnitBusy(i)) {
                runningThreads++;
            }
        }
        super.broadcastChanges();
    }

    @Override
    public int getCurrentProgress() {
        // Aggregate the synced per-unit progress so the value reported to the client matches the
        // broadcast state (host.getCraftingProgressAcrossThreads() is server-side only and reads
        // stale unit fields on the client).
        int total = 0;
        for (int i = 0; i < PatternDiskAssemblerBlockEntity.THREADS; i++) {
            total += Math.min(getUnitProgress(i), 100);
        }
        return total;
    }

    @Override
    public int getMaxProgress() {
        return PatternDiskAssemblerBlockEntity.THREADS * 100;
    }

    /** Client-side per-unit progress (0..100), aggregated after sync. */
    public int getUnitProgress(int unit) {
        return switch (unit) {
            case 0 -> unitProgress0;
            case 1 -> unitProgress1;
            case 2 -> unitProgress2;
            case 3 -> unitProgress3;
            case 4 -> unitProgress4;
            case 5 -> unitProgress5;
            case 6 -> unitProgress6;
            case 7 -> unitProgress7;
            default -> 0;
        };
    }

    /** Client-side per-unit busy state. */
    public boolean isUnitBusy(int unit) {
        return switch (unit) {
            case 0 -> unitBusy0;
            case 1 -> unitBusy1;
            case 2 -> unitBusy2;
            case 3 -> unitBusy3;
            case 4 -> unitBusy4;
            case 5 -> unitBusy5;
            case 6 -> unitBusy6;
            case 7 -> unitBusy7;
            default -> false;
        };
    }

    public int getRunningThreads() {
        return runningThreads;
    }
}
