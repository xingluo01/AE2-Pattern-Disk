package io.github.lounode.ae2pattern.integration.ae2lt.packaged;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import appeng.api.inventories.InternalInventory;
import com.moakiee.ae2lt.packaged.item.MultiblockAdapterItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.AppEngSlot;

import com.moakiee.ae2lt.menu.FrequencyBindingMenu;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ProviderMode;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ReturnMode;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * 封包样板磁盘供应器的菜单：槽位与样板磁盘供应器共用，另加闪电科技封包的适配卡槽——没有它玩家选不了
 * 目标机器。
 */
public class PackagedPatternDiskProviderMenu extends PatternDiskProviderMenu implements FrequencyBindingMenu {

    /** 适配卡槽语义（封包核心选型用）。 */
    public static final SlotSemantic ADAPTER_CARD =
            SlotSemantics.register("ae2_pattern_disk:packaged_adapter_card", false);

    public static final MenuType<PackagedPatternDiskProviderMenu> TYPE = MenuTypeBuilder
            .create((id, playerInventory, host) -> new PackagedPatternDiskProviderMenu(id, playerInventory, host),
                    PatternDiskProviderHost.class)
            .buildUnregistered(ResourceLocation.parse(
                    "ae2_pattern_disk:packaged_pattern_disk_provider"));

    @GuiSync(22000)
    public int returnMode;
    @GuiSync(22001)
    public int providerMode;
    @GuiSync(22003)
    public int modeSwitchVisible = 1;
    @GuiSync(22002)
    public String titleTranslationKey = "gui.ae2_pattern_disk.packaged_pattern_disk_provider";

    private final PackagedPatternDiskProviderBlockEntity host;
    private final Slot adapterSlot;

    public PackagedPatternDiskProviderMenu(int id, Inventory playerInventory, PatternDiskProviderHost host) {
        this(TYPE, id, playerInventory, host);
    }

    protected PackagedPatternDiskProviderMenu(
            MenuType<? extends PackagedPatternDiskProviderMenu> menuType,
            int id,
            Inventory playerInventory,
            PatternDiskProviderHost host) {
        super(menuType, id, playerInventory, host);
        if (!(host instanceof PackagedPatternDiskProviderBlockEntity packagedHost)) {
            throw new IllegalArgumentException("Packaged disk provider menu opened for " + host);
        }
        this.host = packagedHost;

        var adapterSlot = new AdapterCardSlot(packagedHost.getAdapterInv(), 0);
        adapterSlot.setNotDraggable();
        adapterSlot.setEmptyTooltip(() -> List.of(
                Component.translatable("ae2ltpp.gui.adapter_slot"),
                Component.translatable("ae2ltpp.gui.adapter_slot.help")));
        this.adapterSlot = addSlot(adapterSlot, ADAPTER_CARD);

        registerClientAction("toggleAutoReturn", this::toggleAutoReturn);
        registerClientAction("toggleMode", this::toggleMode);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            returnMode = host.getReturnMode().ordinal();
            providerMode = host.getProviderMode().ordinal();
            titleTranslationKey = host.ae2lt$titleTranslationKey();
            modeSwitchVisible = host.isProviderModeSwitchable() ? 1 : 0;
        }
        super.broadcastChanges();
    }

    public void clientToggleAutoReturn() {
        sendClientAction("toggleAutoReturn");
    }

    public void clientToggleMode() {
        sendClientAction("toggleMode");
    }

    public boolean isAutoReturnEnabled() {
        return returnMode == ReturnMode.AUTO.ordinal();
    }

    public Slot getAdapterSlot() {
        return adapterSlot;
    }

    public boolean isModeSwitchVisible() {
        return modeSwitchVisible != 0;
    }

    public boolean isWirelessMode() {
        return providerMode == ProviderMode.WIRELESS.ordinal();
    }

    private void toggleAutoReturn() {
        if (!isServerSide()) {
            return;
        }
        host.setReturnMode(host.getReturnMode() == ReturnMode.AUTO ? ReturnMode.OFF : ReturnMode.AUTO);
    }

    private void toggleMode() {
        if (!isServerSide() || !host.isProviderModeSwitchable()) {
            return;
        }
        host.setProviderMode(host.getProviderMode() == ProviderMode.WIRELESS ? ProviderMode.NORMAL : ProviderMode.WIRELESS);
    }
    /** 适配卡槽：只收封包核心适配卡，一格。 */
    private static final class AdapterCardSlot extends AppEngSlot {
        private AdapterCardSlot(InternalInventory inv, int invSlot) {
            super(inv, invSlot);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof MultiblockAdapterItem && super.mayPlace(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
