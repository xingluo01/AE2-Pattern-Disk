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
import io.github.lounode.ae2pattern.AEPatternRegistries;

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

    /** Pattern slots of the eight CraftUnits, indexed by unit id (for slot overlay rendering). */
    public List<AppEngSlot> getPatternSlots() {
        return List.copyOf(patternSlots);
    }

    /** Progress of the selected CraftUnit, synchronized like EAE's ex assembler. */
    @GuiSync(4)
    public int craftProgress;

    /** Selected CraftUnit page, in the range 0..7. */
    @GuiSync(7)
    public int page;

    /**
     * True while the selected page executes a server-side plan (provider-pushed) that the client cannot
     * decode; such pages stay undimmed instead of marking every empty grid slot as disabled.
     */
    @GuiSync(8)
    public boolean pageExecuting;

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
                addSlot(new AssemblerInputSlot(this, grid, i, unit), AEPatternRegistries.ASSEMBLER_GRID[unit]);
            }
            outputs.add((AppEngSlot) addSlot(
                    new OutputSlot(grid, PatternDiskAssemblerBlockEntity.GRID_SIZE, null),
                    SlotSemantics.MACHINE_OUTPUT));
            // Per-unit encoded-pattern slot: accepts only molecular-assembler patterns; a manually
            // inserted pattern turns this page into a self-executing unit (AE2 ENCODED_PATTERN-style).
            var patternSlot = new RestrictedInputSlot(
                    RestrictedInputSlot.PlacableItemType.MOLECULAR_ASSEMBLER_PATTERN,
                    host.getUnitPatternInv(unit),
                    0);
            // 空槽背景由 Screen 的 states.png (240,80) 覆盖层负责，禁用内建图标避免同位叠绘
            patternSlot.setIcon(null);
            patternSlots.add((AppEngSlot) addSlot(
                    patternSlot,
                    AEPatternRegistries.ASSEMBLER_PATTERN[unit]));
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
            for (var slot : getSlots(AEPatternRegistries.ASSEMBLER_GRID[unit])) {
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
        pageExecuting = host.isUnitExecuting(page);
        standardDetectAndSendChanges();
    }

    /**
     * 客户端每次收到服务端字段同步时，先把当前页应用一遍。
     *
     * <p>槽位的启用状态只由 {@link #showPage()} 维护，而它原先只靠渲染帧调用（Screen.updateBeforeRender）。
     * 服务端的字段包先于槽位包到达，槽位包在客户端 tick 里落地时 {@code AppEngSlot#set} 会因“这个槽不在当前页”
     * 把内容丢掉；而服务端此时已经把物品记进 remoteSlots，不会再补发——那一页的物品就只能等关开界面才回来。
     * 这个钩子在字段包处理时同步执行，正好抢在槽位包之前把启用状态摆好。</p>
     */
    @Override
    public void onServerDataSync(it.unimi.dsi.fastutil.shorts.ShortSet updatedFields) {
        super.onServerDataSync(updatedFields);
        showPage();
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
        private final int unitIndex;

        private AssemblerInputSlot(PatternDiskAssemblerMenu menu, InternalInventory inventory, int slot, int unitIndex) {
            super(inventory, slot);
            this.menu = menu;
            this.unitIndex = unitIndex;
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
            // All eight pages share the same JSON slot position, so drawing every page's background would
            // stack seven extra 20%-alpha overlays on the selected page and wash out the disabled-slot
            // shading. Only the selected page renders its own slot background.
            return menu.page == unitIndex;
        }

        @Override
        public boolean isSlotEnabled() {
            if (!super.isSlotEnabled()) {
                return false;
            }
            if (!getInventory().getStackInSlot(getSlotIndex()).isEmpty()) {
                return true;
            }
            // Both sides can decode the manual pattern (the client decodes the synced pattern slot),
            // so the disabled-slot overlay shows exactly which grid slots the current pattern uses.
            var pattern = menu.getHost().getCurrentPattern(menu.page);
            if (pattern == null) {
                // A provider-pushed plan is server-side state the client cannot decode: keep such pages
                // undimmed instead of falsely marking every empty grid slot as disabled.
                return menu.pageExecuting;
            }
            return getSlotIndex() >= 0
                    && getSlotIndex() < PatternDiskAssemblerBlockEntity.GRID_SIZE
                    && pattern.isSlotEnabled(getSlotIndex());
        }

        @Override
        public Point getBackgroundPos() {
            return new Point(x - 1, y - 1);
        }
    }
}
