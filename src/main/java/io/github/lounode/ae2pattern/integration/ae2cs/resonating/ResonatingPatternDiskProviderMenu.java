package io.github.lounode.ae2pattern.integration.ae2cs.resonating;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;

import io.github.lounode.ae2pattern.common.logic.ProviderExtractionMode;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * 谐振样板磁盘供应器的菜单：槽位布局继承 {@link PatternDiskProviderMenu}（磁盘槽 + 返回槽 + 升级槽），
 * 只补 AE2 阻挡开关与本模组产物抽取三档的状态同步。
 *
 * <p>
 * 父类那三个动作（阻挡三态、发配倍增、产物抽取）强转 {@code PatternDiskProviderLogic}，本设备的逻辑是 AECS
 * 的谐振逻辑，不能复用；产物抽取改由子类自己的动作驱动，阻挡仍用 AE2 的 {@code Settings.BLOCKING_MODE}。
 * </p>
 */
public class ResonatingPatternDiskProviderMenu extends PatternDiskProviderMenu {

    public static final MenuType<ResonatingPatternDiskProviderMenu> TYPE = MenuTypeBuilder
            .create((id, playerInventory, host) -> new ResonatingPatternDiskProviderMenu(id, playerInventory, host),
                    ResonatingPatternDiskProviderHost.class)
            .buildUnregistered(ResourceLocation.parse(
                    "ae2_pattern_disk:resonating_pattern_disk_provider"));

    /** 本模组的产物抽取三档：由谐振逻辑接管，不再走 AECS 的 PullMode。 */
    @GuiSync(11)
    public ProviderExtractionMode extractionMode = ProviderExtractionMode.OFF;

    /** AE2 的阻挡设置。与父类的 {@code blockingMode} 是两套枚举，不能混用。 */
    @GuiSync(12)
    public YesNo ae2BlockingMode = YesNo.NO;

    private final ResonatingPatternDiskProviderHost host;

    public ResonatingPatternDiskProviderMenu(int id, Inventory playerInventory, ResonatingPatternDiskProviderHost host) {
        super(TYPE, id, playerInventory, host);
        this.host = host;
        // AECS 的升级库存（1 格）：父类构造里只会加 AppFlux 那一格，这里把设备自己的那格摆出来。
        // setupUpgradesOnce 会按库存实例去重，两处拿到同一份时不会出现双胞胎槽。
        this.setupUpgradesOnce(host.getUpgrades());
        registerClientAction("cycleResonatingExtraction", this::cycleResonatingExtraction);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            extractionMode = host.getResonatingLogic().getExtractionMode();
            ae2BlockingMode = host.getLogic().getConfigManager().getSetting(Settings.BLOCKING_MODE);
        }
        super.broadcastChanges();
    }

    private void cycleResonatingExtraction() {
        if (isServerSide()) {
            host.getResonatingLogic().cycleExtractionMode();
        }
    }

    public ProviderExtractionMode getExtractionMode() {
        return extractionMode;
    }

    public YesNo getAe2BlockingMode() {
        return ae2BlockingMode;
    }
}
