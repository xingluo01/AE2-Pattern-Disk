package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.LockCraftingMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.GenericStack;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.AppEngSlot;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.integration.appflux.AppFluxInductionCard;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Menu for the ME pattern disk provider: shows the disk slots plus the three toolbar toggles shared with
 * the original pattern provider (blocking mode, lock crafting, show in sample access terminal). These
 * are re-implemented as plain {@link GuiSync} fields read from the logic's config manager, mirroring
 * how AE2 Crystal Science does it in {@code UpgradeablePatternProviderMenu}. Slot layout stays fully
 * deterministic (disk slots + storage + player) so the server/client never drift.
 *
 * <p>The host is typed as {@link PatternDiskProviderHost} - the interface both the in-world block and
 * the panel part implement - so this single menu serves both forms.</p>
 */
public class PatternDiskProviderMenu extends AEBaseMenu {

    public static final MenuType<PatternDiskProviderMenu> TYPE = MenuTypeBuilder
            // 显式三参 lambda：父类还有一个接受 MenuType 的受保护构造，方法引用会同时匹配两种工厂形状。
            .create((id, playerInventory, host) -> new PatternDiskProviderMenu(id, playerInventory, host),
                    PatternDiskProviderHost.class)
            .buildUnregistered(
                    net.minecraft.resources.ResourceLocation.parse("ae2_pattern_disk:pattern_disk_provider"));

    private final PatternDiskProviderHost host;

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

    public PatternDiskProviderMenu(int id, Inventory playerInv, PatternDiskProviderHost host) {
        this(TYPE, id, playerInv, host);
    }

    /**
     * 供子类用自己的菜单类型打开同一套槽位。类型参数放宽到通配，是因为子类那份菜单类型带的是子类类型——
     * 共享的是布局，不是类型本身。
     */
    protected PatternDiskProviderMenu(MenuType<? extends PatternDiskProviderMenu> menuType, int id,
                                      Inventory playerInv, PatternDiskProviderHost host) {
        super(menuType, id, playerInv, host);
        this.host = host;

        var logic = host.getLogic();

        // Disk slots (our own): the source of patterns for this provider.
        var inv = host.getDiskInventory();
        for (int i = 0; i < inv.size(); i++) {
            this.addSlot(new DiskSlot(inv, i), AEPatternRegistries.PROVIDER_DISK);
        }

        // Return inventory slots, mirroring AE2's provider.
        var returnInv = logic.getReturnInv().createMenuWrapper();
        for (int i = 0; i < PatternProviderReturnInventory.NUMBER_OF_SLOTS && i < returnInv.size(); i++) {
            this.addSlot(new AppEngSlot(returnInv, i), SlotSemantics.STORAGE);
        }

        // Applied Flux 的感应卡槽（软依赖）：它给 AE2 的供应器逻辑挂了一格升级库存、并把宿主接口扩成
        // IUpgradeableObject，这里把那格显示出来。取不到就没有这一格，供应器与从前一模一样。
        this.setupUpgradesOnce(AppFluxInductionCard.upgradesOf(host));

        this.createPlayerInventorySlots(playerInv);
    }

    /**
     * 加一格升级槽，但已经加过同一个库存就不再加第二次。
     *
     * <p>单看父类这一处用不上它；用得上的是子类：自装配（陨石）版的宿主自己也实现 {@code getUpgrades()}
     * （转发 AECS 的升级库存），而 {@link AppFluxInductionCard#upgradesOf} 是反射调的同一方法——两边拿到
     * 的是同一个库存，各加一次就会在屏幕上摆出两个指向同一格、内容同步的双胞胎槽。</p>
     */
    protected final void setupUpgradesOnce(@Nullable IUpgradeInventory upgrades) {
        if (upgrades == null) {
            return;
        }
        for (var slot : getSlots(SlotSemantics.UPGRADE)) {
            if (slot instanceof AppEngSlot appEngSlot && appEngSlot.getInventory() == upgrades) {
                return;
            }
        }
        setupUpgrades(upgrades);
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

    public PatternDiskProviderHost getProvider() {
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
