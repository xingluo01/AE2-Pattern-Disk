package io.github.lounode.ae2pattern.common.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.inventories.InternalInventory;
import appeng.blockentity.ServerTickingBlockEntity;

import io.github.lounode.ae2pattern.MeteoritePatternProviderRegistrations;
import io.github.lounode.ae2pattern.common.logic.SelfAssemblingPatternDiskProviderLogic;

/**
 * 自装配样板磁盘供应器的方块实体：磁盘槽 + 自装配逻辑。
 *
 * <p>
 * 磁盘那半与 {@link PatternDiskProviderBlockEntity} 完全同构，两边都继承 {@link AbstractDiskPatternProviderBlockEntity}
 * ——磁盘栏、终端视图、重建、掉落与内存卡的镜像语义只写了一份；差别只在逻辑类型（本设备的供应器逻辑会把磁盘展开出的
 * 配方当场自完成，而不是推给外部机器）与下面这条服务端 tick。契约照旧：{@code patternInventory} 只是磁盘的镜像。
 * </p>
 */
public class MeteoritePatternProviderBlockEntity
        extends AbstractDiskPatternProviderBlockEntity<SelfAssemblingPatternDiskProviderLogic>
        implements MeteoritePatternProviderHost, ServerTickingBlockEntity {

    public static final int DISK_SLOT_COUNT = 9;

    public MeteoritePatternProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(MeteoritePatternProviderRegistrations.BE.get(), pos, blockState, DISK_SLOT_COUNT);
    }

    @Override
    protected SelfAssemblingPatternDiskProviderLogic createLogic() {
        // 懒取磁盘栏：方块实体的父构造会在这条路径上先跑 createLogic()，那时磁盘栏字段还没赋值。
        return new SelfAssemblingPatternDiskProviderLogic(getMainNode(), this, DISK_SLOT_COUNT, this::getDiskInventory);
    }

    @Override
    protected Item item() {
        return MeteoritePatternProviderRegistrations.ITEM.get();
    }

    /**
     * 显式覆写只为一件事：给这个签名加一道编译期断言。AE2 的默认实现把<b>镜像样板栏</b>交给终端，而这里的镜像只是
     * 磁盘的副本——本方法一旦因接口改名而失去覆盖，样板访问终端会静默读到镜像（取走时不碰磁盘）。实现仍在基类。
     */
    @Override
    public InternalInventory getTerminalPatternInventory() {
        return super.getTerminalPatternInventory();
    }

    /**
     * 服务端 tick：驱动待回送产物。不用 AE2 供应器自己那份 tick（它里面那两个方法是私有的，外面接管不了），
     * 也不覆盖它——推送那半仍归它管，这里只多跑一件它不做的事。
     */
    @Override
    public void serverTick() {
        if (getLogic() instanceof SelfAssemblingPatternDiskProviderLogic logic) {
            logic.tickCraftedContents();
        }
    }
}
