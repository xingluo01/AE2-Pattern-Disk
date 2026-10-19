package io.github.lounode.ae2pattern.common.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.inventories.InternalInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic;

/**
 * 样板磁盘供应器的方块实体：带 {@link #DISK_SLOT_COUNT} 个磁盘槽，把磁盘上编码好的样板全部公开给 ME 自动合成。
 *
 * <p>磁盘那半与那条「样板栏只是磁盘镜像」的约定都在 {@link AbstractDiskPatternProviderBlockEntity}，
 * 面板形态与自装配供应器用的是同一份；本类只有方块实体类型、逻辑类型与注册物品这三样。</p>
 */
public class PatternDiskProviderBlockEntity extends AbstractDiskPatternProviderBlockEntity<PatternDiskProviderLogic>
        implements PatternDiskProviderHost {

    public static final int DISK_SLOT_COUNT = 9;

    public PatternDiskProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_PROVIDER.get(), pos, blockState, DISK_SLOT_COUNT);
    }

    @Override
    protected PatternDiskProviderLogic createLogic() {
        // 懒取磁盘栏：方块实体的父构造会先跑 createLogic()，那时磁盘栏字段还没赋值，所以只传一个刷新时才解析的
        // supplier。
        return new PatternDiskProviderLogic(getMainNode(), this, DISK_SLOT_COUNT, this::getDiskInventory);
    }

    @Override
    protected Item item() {
        return AEPatternRegistries.ITEM_PROVIDER.get();
    }

    /**
     * 显式覆写只为一件事：给这个签名加一道编译期断言。AE2 的默认实现把<b>镜像样板栏</b>交给终端，而这里的镜像只是
     * 磁盘的副本——本方法一旦因接口改名而失去覆盖，样板访问终端会静默读到镜像（取走时不碰磁盘）。实现仍在基类。
     */
    @Override
    public InternalInventory getTerminalPatternInventory() {
        return super.getTerminalPatternInventory();
    }
}
