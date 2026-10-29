package io.github.lounode.ae2pattern.common.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import appeng.api.inventories.InternalInventory;
import net.minecraft.world.level.block.state.BlockState;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.logic.MarkedPatternDiskProviderLogic;

/**
 * 标记式样板磁盘供应器的方块形态。
 *
 * <p>磁盘槽数是 0：它的样板来自网格上别人的磁盘，本机不存盘。其余（镜像语义、掉落、内存卡）仍走
 * {@link AbstractDiskPatternProviderBlockEntity}，与普通供应器同一份。</p>
 */
public class MarkedPatternDiskProviderBlockEntity
        extends AbstractDiskPatternProviderBlockEntity<MarkedPatternDiskProviderLogic>
        implements MarkedPatternDiskProviderHost {

    public MarkedPatternDiskProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_MARKED_PROVIDER.get(), pos, blockState, 0);
    }

    @Override
    protected MarkedPatternDiskProviderLogic createLogic() {
        // 懒取磁盘栏：父构造先跑这里，那时 disks 字段还没赋值。
        return new MarkedPatternDiskProviderLogic(getMainNode(), this, this::getDiskInventory);
    }

    @Override
    protected Item item() {
        return AEPatternRegistries.ITEM_MARKED_PROVIDER.get();
    }

    /**
     * 终端视图来自本机磁盘栏（0 槽），不是镜像：镜像出来的样板只在网络上参与合成，不进任何磁盘终端。
     *
     * <p>方法体走基类；这次显式覆写同时是一道编译期断言，接口改名时不会静默回落到 AE2 的默认实现。</p>
     */
    @Override
    public InternalInventory getTerminalPatternInventory() {
        return super.getTerminalPatternInventory();
    }
}
