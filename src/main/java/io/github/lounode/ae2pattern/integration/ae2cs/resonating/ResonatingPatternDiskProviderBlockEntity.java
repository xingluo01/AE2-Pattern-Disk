package io.github.lounode.ae2pattern.integration.ae2cs.resonating;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.util.SettingsFrom;

import io.github.lounode.ae2cs.common.me.logic.ResonatingPatternProviderLogic;
import io.github.lounode.ae2pattern.common.block.entity.AbstractDiskPatternProviderBlockEntity;

/**
 * 谐振样板磁盘供应器的方块实体：磁盘栏与镜像语义全在
 * {@link AbstractDiskPatternProviderBlockEntity} 里，本类只接 AECS 的谐振逻辑与宿主接口。
 *
 * <p>
 * 不继承 AECS 的 {@code ResonatingPatternProviderBlockEntity}：那台的外壳会打开 AECS 自己的菜单、把升级库存
 * 绑在 AECS 方块上；这里要的是本模组的磁盘菜单与自己的设备身份。逻辑仍继承 AECS 的
 * {@link ResonatingPatternProviderLogic}，所以谐振投递、144 槽默认目标、跨维度发送队列与绑定器全部原样保留。
 * </p>
 */
public class ResonatingPatternDiskProviderBlockEntity
        extends AbstractDiskPatternProviderBlockEntity<ResonatingPatternDiskProviderLogic>
        implements ResonatingPatternDiskProviderHost {

    public static final int DISK_SLOT_COUNT = 9;
    /** 9 张 1024 档磁盘的上限；构造期父类要用它建样板栏，所以是常量。 */
    public static final int PATTERN_CAPACITY = DISK_SLOT_COUNT * 1024;

    public ResonatingPatternDiskProviderBlockEntity(BlockPos pos, BlockState state) {
        super(ResonatingPatternDiskProviderRegistrations.BE.get(), pos, state, DISK_SLOT_COUNT);
    }

    @Override
    protected ResonatingPatternDiskProviderLogic createLogic() {
        // 懒取磁盘栏：父构造会在这条路径上先跑 createLogic()，那时磁盘栏字段还没赋值。
        return new ResonatingPatternDiskProviderLogic(getMainNode(), this, PATTERN_CAPACITY, this::getDiskInventory);
    }

    @Override
    protected Item item() {
        return ResonatingPatternDiskProviderRegistrations.ITEM.get();
    }

    @Override
    public ResonatingPatternDiskProviderLogic getResonatingLogic() {
        return (ResonatingPatternDiskProviderLogic) getLogic();
    }

    /** 磁盘版只有一种形态，AECS 的「扩展」语义（9 → 36 槽、换模型）不适用。 */
    @Override
    public boolean isExtended() {
        return false;
    }

    /** 绑定器改完默认目标后要看到新状态：方块实体走 AE2 的方块更新同步。 */
    @Override
    public void markForLogicClientUpdate() {
        markForUpdate();
    }

    /**
     * 镜像只是磁盘的副本，存档里再写一份会随磁盘容量放大（9216 格）。读档由 {@code disks.load} 按磁盘重建，
     * 所以这里把父类写进去的样板段摘掉。
     */
    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.remove(PatternProviderLogic.NBT_MEMORY_CARD_PATTERNS);
    }

    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        super.importSettings(mode, input, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            getResonatingLogic().readDefaultsFromSettings(input);
        }
    }

    @Override
    public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder, @Nullable Player player) {
        super.exportSettings(mode, builder, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            getResonatingLogic().writeDefaultsToSettings(builder);
        }
    }
}
