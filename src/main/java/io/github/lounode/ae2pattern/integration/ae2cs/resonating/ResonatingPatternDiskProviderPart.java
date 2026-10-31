package io.github.lounode.ae2pattern.integration.ae2cs.resonating;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.items.parts.PartModels;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.PartModel;
import appeng.util.SettingsFrom;

import io.github.lounode.ae2cs.common.me.logic.ResonatingPatternProviderLogic;
import io.github.lounode.ae2pattern.common.part.AbstractDiskPatternProviderPart;

/**
 * 谐振样板磁盘供应器的面板形态：贴在线缆上，磁盘栏与镜像语义在 {@link AbstractDiskPatternProviderPart} 里，
 * 谐振逻辑与 144 槽默认目标来自 AECS 的 {@link ResonatingPatternProviderLogic}。
 *
 * <p>
 * 默认目标的搬运与 AECS 原面板一致：拆下/内存卡导入导出时走
 * {@code readDefaultsFromSettings}/{@code writeDefaultsToSettings}，拆下时再把当前目标写回掉落物。
 * 少了这三段，绑定器设好的目标会在面板拆装或内存卡复制时丢失。
 * </p>
 */
public class ResonatingPatternDiskProviderPart extends AbstractDiskPatternProviderPart<ResonatingPatternDiskProviderLogic>
        implements ResonatingPatternDiskProviderHost {

    @PartModels
    public static final ResourceLocation MODEL = ResourceLocation.parse(
            "ae2_pattern_disk:part/resonating_pattern_disk_provider_base");

    public static final IPartModel MODELS = new PartModel(MODEL);

    public ResonatingPatternDiskProviderPart(IPartItem<?> partItem) {
        super(partItem, ResonatingPatternDiskProviderBlockEntity.DISK_SLOT_COUNT);
    }

    @Override
    protected ResonatingPatternDiskProviderLogic createLogic() {
        // 懒取磁盘栏，与方块实体同款：基类构造就会调到这里，那时 logic 字段还没赋值。
        return new ResonatingPatternDiskProviderLogic(getMainNode(), this,
                ResonatingPatternDiskProviderBlockEntity.PATTERN_CAPACITY, this::getDiskInventory);
    }

    @Override
    protected IPartModel models() {
        return MODELS;
    }

    @Override
    protected void openHostMenu(Player player) {
        MenuOpener.open(ResonatingPatternDiskProviderMenu.TYPE, player, MenuLocators.forPart(this));
    }

    @Override
    public ResonatingPatternDiskProviderLogic getResonatingLogic() {
        return getLogic();
    }

    /** 磁盘版只有一种形态，AECS 的「扩展」语义不适用。 */
    @Override
    public boolean isExtended() {
        return false;
    }

    @Override
    public void markForLogicClientUpdate() {
        var host = getBlockEntity();
        if (host != null && host.getLevel() != null && !host.getLevel().isClientSide()) {
            getHost().markForUpdate();
        }
    }

    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        super.importSettings(mode, input, player);
        if (mode == SettingsFrom.DISMANTLE_ITEM || mode == SettingsFrom.MEMORY_CARD) {
            getResonatingLogic().readDefaultsFromSettings(input);
        }
    }

    @Override
    public void exportSettings(SettingsFrom mode, DataComponentMap.Builder output) {
        super.exportSettings(mode, output);
        if (mode == SettingsFrom.DISMANTLE_ITEM || mode == SettingsFrom.MEMORY_CARD) {
            getResonatingLogic().writeDefaultsToSettings(output);
        }
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        getResonatingLogic().writeDefaultsToDrops(drops, getPartItem());
    }
}
