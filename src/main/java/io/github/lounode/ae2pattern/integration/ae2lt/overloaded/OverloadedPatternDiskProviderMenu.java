package io.github.lounode.ae2pattern.integration.ae2lt.overloaded;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;

import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity.BlockingMode;
import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity.ProviderMode;
import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity.ReturnMode;
import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity.WirelessDispatchMode;
import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity.WirelessSpeedMode;
import com.moakiee.ae2lt.menu.FrequencyBindingMenu;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * 过载样板磁盘供应器的菜单：槽位与样板磁盘供应器共用，设置区是闪电科技过载供应器的按钮状态。
 *
 * <p>状态同步与动作照上游 {@code OverloadedPatternProviderMenu}；分页、样板槽不在这里（本设备样板来自
 * 磁盘，没有样板槽可翻）。阻挡是三态：0 关、1 普通、2 同样板。</p>
 */
public class OverloadedPatternDiskProviderMenu extends PatternDiskProviderMenu implements FrequencyBindingMenu {

    public static final MenuType<OverloadedPatternDiskProviderMenu> TYPE = MenuTypeBuilder
            .create((id, playerInventory, host) -> new OverloadedPatternDiskProviderMenu(id, playerInventory, host),
                    PatternDiskProviderHost.class)
            .buildUnregistered(ResourceLocation.parse(
                    "ae2_pattern_disk:overloaded_pattern_disk_provider"));

    @GuiSync(22000)
    public int providerMode;
    @GuiSync(22001)
    public int returnMode;
    @GuiSync(22002)
    public int filteredImport;
    /** 0 = 关，1 = 普通阻挡，2 = 同样板阻挡。 */
    @GuiSync(22003)
    public int blockingMode;
    @GuiSync(22004)
    public int adaptiveBatchEnabled;
    @GuiSync(22005)
    public int machineParallelism = 1;
    @GuiSync(22006)
    public int wirelessDispatchMode;
    @GuiSync(22007)
    public int wirelessSpeedMode;
    @GuiSync(22008)
    public int machineAdaptationEnabled = 1;

    private final OverloadedPatternDiskProviderBlockEntity host;

    public OverloadedPatternDiskProviderMenu(int id, Inventory playerInventory, PatternDiskProviderHost host) {
        super(TYPE, id, playerInventory, host);
        if (!(host instanceof OverloadedPatternDiskProviderBlockEntity overloadedHost)) {
            throw new IllegalArgumentException("Overloaded disk provider menu opened for " + host);
        }
        this.host = overloadedHost;

        registerClientAction("toggleMode", this::toggleMode);
        registerClientAction("toggleAutoReturn", this::toggleAutoReturn);
        registerClientAction("toggleFilteredImport", this::toggleFilteredImport);
        registerClientAction("cycleBlockingMode", this::cycleBlockingMode);
        registerClientAction("toggleAdaptiveBatch", this::toggleAdaptiveBatch);
        registerClientAction("toggleWirelessDispatchMode", this::toggleWirelessDispatchMode);
        registerClientAction("toggleWirelessSpeedMode", this::toggleWirelessSpeedMode);
        registerClientAction("toggleMachineAdaptation", this::toggleMachineAdaptation);
        registerClientAction("setMachineParallelism", Integer.class, this::setMachineParallelism);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            providerMode = host.getProviderMode().ordinal();
            returnMode = host.getReturnMode().ordinal();
            filteredImport = host.isFilteredImport() ? 1 : 0;
            blockingMode = blockingState();
            adaptiveBatchEnabled = host.isAdaptiveBatchEnabled() ? 1 : 0;
            machineParallelism = host.getMachineParallelism();
            wirelessDispatchMode = host.getWirelessDispatchMode().ordinal();
            wirelessSpeedMode = host.getWirelessSpeedMode().ordinal();
            machineAdaptationEnabled = host.isMachineAdaptationEnabled() ? 1 : 0;
        }
        super.broadcastChanges();
    }

    // ---- 客户端动作 ---------------------------------------------------------

    public void clientToggleMode() {
        sendClientAction("toggleMode");
    }

    public void clientToggleAutoReturn() {
        sendClientAction("toggleAutoReturn");
    }

    public void clientToggleFilteredImport() {
        sendClientAction("toggleFilteredImport");
    }

    public void clientCycleBlockingMode() {
        sendClientAction("cycleBlockingMode");
    }

    public void clientToggleAdaptiveBatch() {
        sendClientAction("toggleAdaptiveBatch");
    }

    public void clientToggleWirelessDispatchMode() {
        sendClientAction("toggleWirelessDispatchMode");
    }

    public void clientToggleWirelessSpeedMode() {
        sendClientAction("toggleWirelessSpeedMode");
    }

    public void clientToggleMachineAdaptation() {
        sendClientAction("toggleMachineAdaptation");
    }

    public void clientSetMachineParallelism(int value) {
        sendClientAction("setMachineParallelism", value);
    }

    public boolean isWirelessMode() {
        return providerMode == ProviderMode.WIRELESS.ordinal();
    }

    public boolean isAutoReturnEnabled() {
        return returnMode == ReturnMode.AUTO.ordinal();
    }

    public boolean isBlockingOn() {
        return blockingMode > 0;
    }

    public boolean isBlockingSamePattern() {
        return blockingMode == 2;
    }

    public boolean isAdaptiveBatchOn() {
        return adaptiveBatchEnabled != 0;
    }

    public boolean isFilteredImportOn() {
        return filteredImport != 0;
    }

    public boolean isEvenDistribution() {
        return wirelessDispatchMode == WirelessDispatchMode.EVEN_DISTRIBUTION.ordinal();
    }

    public boolean isFastSpeed() {
        return wirelessSpeedMode == WirelessSpeedMode.FAST.ordinal();
    }

    public boolean isMachineAdaptationOn() {
        return machineAdaptationEnabled != 0;
    }

    // ---- 服务端处理 ---------------------------------------------------------

    private void toggleMode() {
        if (!isServerSide()) {
            return;
        }
        var current = host.getProviderMode();
        host.setProviderMode(current == ProviderMode.NORMAL ? ProviderMode.WIRELESS : ProviderMode.NORMAL);
    }

    private void toggleAutoReturn() {
        if (!isServerSide()) {
            return;
        }
        var values = ReturnMode.values();
        host.setReturnMode(values[(host.getReturnMode().ordinal() + 1) % values.length]);
    }

    private void toggleFilteredImport() {
        if (isServerSide()) {
            host.setFilteredImport(!host.isFilteredImport());
        }
    }

    private void cycleBlockingMode() {
        if (!isServerSide()) {
            return;
        }
        int next = (blockingState() + 1) % 3;
        host.getLogic().getConfigManager().putSetting(
                Settings.BLOCKING_MODE,
                next == 0 ? YesNo.NO : YesNo.YES);
        host.setBlockingMode(next == 2 ? BlockingMode.SAME_PATTERN : BlockingMode.NORMAL);
    }

    private int blockingState() {
        if (!host.getLogic().isBlocking()) {
            return 0;
        }
        return host.getBlockingMode() == BlockingMode.SAME_PATTERN ? 2 : 1;
    }

    private void toggleAdaptiveBatch() {
        if (isServerSide()) {
            host.setAdaptiveBatchEnabled(!host.isAdaptiveBatchEnabled());
        }
    }

    private void toggleWirelessDispatchMode() {
        if (!isServerSide()) {
            return;
        }
        var values = WirelessDispatchMode.values();
        host.setWirelessDispatchMode(values[(host.getWirelessDispatchMode().ordinal() + 1) % values.length]);
    }

    private void toggleWirelessSpeedMode() {
        if (!isServerSide()) {
            return;
        }
        var values = WirelessSpeedMode.values();
        host.setWirelessSpeedMode(values[(host.getWirelessSpeedMode().ordinal() + 1) % values.length]);
    }

    private void toggleMachineAdaptation() {
        if (isServerSide()) {
            host.setMachineAdaptationEnabled(!host.isMachineAdaptationEnabled());
        }
    }

    private void setMachineParallelism(int value) {
        if (isServerSide()) {
            host.setMachineParallelism(Math.max(1, Math.min(1024, value)));
        }
    }
}