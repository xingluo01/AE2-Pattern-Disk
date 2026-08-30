package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.client.Point;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.interfaces.IProgressProvider;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.IOptionalSlot;
import appeng.menu.slot.OutputSlot;
import appeng.menu.slot.RestrictedInputSlot;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.core.AEPatternSlotSemantics;

/**
 * EAE-style page menu for the parallel molecular assembler.
 *
 * <p>All eight execution units are registered against the same JSON position. Only the selected
 * unit is enabled, so the page behaves like EAE's ex_molecular_assembler without mixing in provider
 * or pattern-management slots.</p>
 */
public class PatternDiskAssemblerMenu extends UpgradeableMenu<PatternDiskAssemblerBlockEntity>
        implements IProgressProvider {

    public static final int MAX_PAGE = PatternDiskAssemblerBlockEntity.THREADS;

    public static final MenuType<PatternDiskAssemblerMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskAssemblerMenu::new, PatternDiskAssemblerBlockEntity.class)
            .buildUnregistered(
                    net.minecraft.resources.ResourceLocation.parse("ae2_pattern_disk:pattern_disk_assembler"));

    private final PatternDiskAssemblerBlockEntity host;
    private final List<AppEngSlot> outputs = new ArrayList<>();
    private final List<AppEngSlot> patternSlots = new ArrayList<>();

    /** Progress of the selected CraftUnit, synchronized like EAE's ex assembler. */
    @GuiSync(4)
    public int craftProgress;

    /** Selected CraftUnit page, in the range 0..7. */
    @GuiSync(7)
    public int page;

    public PatternDiskAssemblerMenu(int id, Inventory playerInv, PatternDiskAssemblerBlockEntity host) {
        super(TYPE, id, playerInv, host);
        this.host = host;
        registerClientAction("next_page", () -> setPage(page + 1));
        registerClientAction("previous_page", () -> setPage(page - 1));
        registerUnitSlots();
        showPage();
    }

    private void registerUnitSlots() {
        for (int unit = 0; unit < MAX_PAGE; unit++) {
            var grid = host.getUnitGrid(unit);
            for (int i = 0; i < PatternDiskAssemblerBlockEntity.GRID_SIZE; i++) {
                addSlot(new AssemblerInputSlot(this, grid, i), AEPatternSlotSemantics.ASSEMBLER_GRID[unit]);
            }
            outputs.add((AppEngSlot) addSlot(
                    new OutputSlot(grid, PatternDiskAssemblerBlockEntity.GRID_SIZE, null),
                    SlotSemantics.MACHINE_OUTPUT));
            // Per-unit encoded-pattern slot: accepts only molecular-assembler patterns; a manually
            // inserted pattern turns this page into a self-executing unit (AE2 ENCODED_PATTERN-style).
            patternSlots.add((AppEngSlot) addSlot(
                    new RestrictedInputSlot(
                            RestrictedInputSlot.PlacableItemType.MOLECULAR_ASSEMBLER_PATTERN,
                            host.getUnitPatternInv(unit),
                            0),
                    AEPatternSlotSemantics.ASSEMBLER_PATTERN[unit]));
        }
    }

    public boolean isValidItemForSlot(int slotIndex, ItemStack stack) {
        var pattern = host.getCurrentPattern(page);
        return pattern != null
                && pattern.isItemValid(slotIndex, AEItemKey.of(stack), host.getLevel());
    }

    /** Clamp and apply a page, then enable exactly one unit's input/output slots. */
    public void setPage(int requestedPage) {
        page = Math.max(0, Math.min(MAX_PAGE - 1, requestedPage));
        showPage();
    }

    public void nextPage() {
        sendClientAction("next_page");
    }

    public void previousPage() {
        sendClientAction("previous_page");
    }

    /** Enables the selected unit and hides the other seven units. */
    public void showPage() {
        for (int unit = 0; unit < MAX_PAGE; unit++) {
            boolean enabled = page == unit;
            for (var slot : getSlots(AEPatternSlotSemantics.ASSEMBLER_GRID[unit])) {
                if (slot instanceof AppEngSlot appEngSlot) {
                    appEngSlot.setSlotEnabled(enabled);
                }
            }
            outputs.get(unit).setSlotEnabled(enabled);
            patternSlots.get(unit).setSlotEnabled(enabled);
        }
    }

    @Override
    public void broadcastChanges() {
        page = Math.max(0, Math.min(MAX_PAGE - 1, page));
        craftProgress = host.getUnitProgress(page);
        standardDetectAndSendChanges();
    }

    @Override
    public int getCurrentProgress() {
        return craftProgress;
    }

    @Override
    public int getMaxProgress() {
        return 100;
    }

    public PatternDiskAssemblerBlockEntity getAssembler() {
        return host;
    }

    public int getPage() {
        return page;
    }

    public int getMaxPage() {
        return MAX_PAGE;
    }

    /** Input slot matching EAE's page-local molecular assembler slot validation. */
    private static final class AssemblerInputSlot extends AppEngSlot implements IOptionalSlot {
        private final PatternDiskAssemblerMenu menu;

        private AssemblerInputSlot(PatternDiskAssemblerMenu menu, InternalInventory inventory, int slot) {
            super(inventory, slot);
            this.menu = menu;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isSlotEnabled() && super.mayPlace(stack) && menu.isValidItemForSlot(getSlotIndex(), stack);
        }

        @Override
        protected boolean getCurrentValidationState() {
            var stack = getItem();
            return stack.isEmpty() || mayPlace(stack);
        }

        @Override
        public boolean isRenderDisabled() {
            return true;
        }

        @Override
        public boolean isSlotEnabled() {
            if (!super.isSlotEnabled()) {
                return false;
            }
            if (!getInventory().getStackInSlot(getSlotIndex()).isEmpty()) {
                return true;
            }
            // The plan is server-side execution state and is not mirrored into the client BE. Keep
            // empty page slots visible on the client; the server remains authoritative in mayPlace.
            if (menu.getAssembler().isClientSide()) {
                return true;
            }
            var pattern = menu.getHost().getCurrentPattern(menu.page);
            return pattern != null
                    && getSlotIndex() >= 0
                    && getSlotIndex() < PatternDiskAssemblerBlockEntity.GRID_SIZE
                    && pattern.isSlotEnabled(getSlotIndex());
        }

        @Override
        public Point getBackgroundPos() {
            return new Point(x - 1, y - 1);
        }
    }

    /** A zero-capacity {@link InternalInventory} used as the backing store of display-only slots. */
    private static final class EmptyDiskInventory implements InternalInventory {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItemDirect(int index, ItemStack stack) {
        }
    }
}
