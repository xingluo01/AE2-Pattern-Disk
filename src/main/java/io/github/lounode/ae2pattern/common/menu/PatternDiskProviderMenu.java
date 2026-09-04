package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.LockCraftingMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.GenericStack;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.AppEngSlot;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Menu for the pattern disk provider: shows the disk slots plus the three toolbar toggles shared with
 * the original pattern provider (blocking mode, lock crafting, show in sample access terminal). These
 * are re-implemented as plain {@link GuiSync} fields read from the logic's config manager, mirroring
 * how AE2 Crystal Science does it in {@code UpgradeablePatternProviderMenu}. Slot layout stays fully
 * deterministic (disk slots + storage + player) so the server/client never drift.
 */
public class PatternDiskProviderMenu extends AEBaseMenu {

    public static final MenuType<PatternDiskProviderMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskProviderMenu::new, PatternDiskProviderBlockEntity.class)
            .buildUnregistered(
                    net.minecraft.resources.ResourceLocation.parse("ae2_pattern_disk:pattern_disk_provider"));

    private final PatternDiskProviderBlockEntity host;

    @GuiSync(3)
    public YesNo blockingMode = YesNo.NO;
    @GuiSync(4)
    public YesNo showInAccessTerminal = YesNo.YES;
    @GuiSync(5)
    public LockCraftingMode lockCraftingMode = LockCraftingMode.NONE;
    @GuiSync(6)
    public LockCraftingMode craftingLockedReason = LockCraftingMode.NONE;
    @GuiSync(7)
    public GenericStack unlockStack = null;

    public PatternDiskProviderMenu(int id, Inventory playerInv, PatternDiskProviderBlockEntity host) {
        super(TYPE, id, playerInv, host);
        this.host = host;

        var logic = host.getLogic();

        // Disk slots (our own): the source of patterns for this provider.
        var inv = host.getDiskInventory();
        for (int i = 0; i < PatternDiskProviderBlockEntity.DISK_SLOT_COUNT; i++) {
            this.addSlot(new DiskSlot(inv, i), AEPatternRegistries.PROVIDER_DISK);
        }

        // Return inventory slots, mirroring AE2's provider.
        var returnInv = logic.getReturnInv().createMenuWrapper();
        for (int i = 0; i < PatternProviderReturnInventory.NUMBER_OF_SLOTS && i < returnInv.size(); i++) {
            this.addSlot(new AppEngSlot(returnInv, i), SlotSemantics.STORAGE);
        }

        this.createPlayerInventorySlots(playerInv);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            var logic = host.getLogic();
            blockingsModeFromLogic(logic);
        }
        super.broadcastChanges();
    }

    private void blockingsModeFromLogic(appeng.helpers.patternprovider.PatternProviderLogic logic) {
        blockingMode = logic.getConfigManager().getSetting(Settings.BLOCKING_MODE);
        showInAccessTerminal = logic.getConfigManager().getSetting(Settings.PATTERN_ACCESS_TERMINAL);
        lockCraftingMode = logic.getConfigManager().getSetting(Settings.LOCK_CRAFTING_MODE);
        craftingLockedReason = logic.getCraftingLockedReason();
        unlockStack = logic.getUnlockStack();
    }

    public GenericStackInv getReturnInv() {
        return host.getLogic().getReturnInv();
    }

    public YesNo getBlockingMode() {
        return blockingMode;
    }

    public YesNo getShowInAccessTerminal() {
        return showInAccessTerminal;
    }

    public LockCraftingMode getLockCraftingMode() {
        return lockCraftingMode;
    }

    public LockCraftingMode getCraftingLockedReason() {
        return craftingLockedReason;
    }

    public GenericStack getUnlockStack() {
        return unlockStack;
    }

    public PatternDiskProviderBlockEntity getProvider() {
        return host;
    }

    /**
     * 供应器磁盘槽：仅接受样板磁盘；空槽底图由 Screen 用 states.png (240,16,16,16) 自绘。
     */
    public static class DiskSlot extends AppEngSlot {
        DiskSlot(InternalInventory inventory, int index) {
            super(inventory, index);
            // 背景覆盖层由 Screen 使用 states.png (240,16,16,16) 自绘，不依赖 AE2 内置槽图标
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() instanceof PatternDiskItem;
        }
    }
}
