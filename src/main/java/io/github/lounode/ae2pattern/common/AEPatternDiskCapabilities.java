package io.github.lounode.ae2pattern.common;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import net.minecraft.world.item.Item;

import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.items.tools.powered.powersink.PoweredItemCapabilities;

import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * 把三个无线终端接进 NeoForge 的 FE 能量能力。
 *
 * <p>AE2 侧只用自家接口 {@link IAEItemPowerStorage} 表达物品储能（AE 充能器就是按这个接口判定能否充电），
 * 而 FE 能力不是「实现了接口就自动有」——AE2 只把这层登记给自己的电力物品，第三方物品得自己补。
 * 这里复用 AE2 自己的 FE 桥 {@link PoweredItemCapabilities} 补上登记，于是 MEK 能量单元、充能台这类 FE
 * 设备也能给终端充电。</p>
 *
 * <p><b>新增无线终端时必须在这里添一枚</b>：漏了不会报错，只表现为「这一款充不上电」，而玩家看不出是少了
 * 一行登记。</p>
 *
 * <p><b>挂法上有一个咬过人的坑</b>：{@code RegisterCapabilitiesEvent} 是<b>模组总线</b>事件
 * （{@code IModBusEvent}），而 {@code @EventBusSubscriber} 的 {@code bus} 默认值是 {@code GAME}。
 * 本类最初就写成 {@code @EventBusSubscriber(modid = ...)}，于是监听器挂到了游戏总线上，永远收不到这个事件
 * ——不报错、不警告，只是三个无线终端都充不上电。现在改由 {@code AE2PatternDisk} 的模组总线构造器调用，
 * 与同文件其它能力登记同一口径；不要再改回注解挂法，除非显式写 {@code bus = MOD}。</p>
 */
public final class AEPatternDiskCapabilities {

    private AEPatternDiskCapabilities() {}

    public static void registerItemEnergy(RegisterCapabilitiesEvent event) {
        register(event,
                AEPatternRegistries.ITEM_WIRELESS_PATTERN_DISK_ENCODING_TERMINAL.get(),
                AEPatternRegistries.ITEM_WIRELESS_PATTERN_DISK_MANAGEMENT_TERMINAL.get(),
                AEPatternRegistries.ITEM_WIRELESS_CELL_MANAGEMENT_TERMINAL.get());
    }

    /**
     * 逐台把电力物品接进 FE 能力。
     *
     * <p>{@code T extends Item & IAEItemPowerStorage} 就是这道登记的护栏：把不是电力物品的东西塞进来会直接
     * 编译不过，而不是等运行时头一次查能力时才 {@code ClassCastException}；顺带那个强转也不必写了——AE2 的
     * {@link PoweredItemCapabilities} 要的正是这个接口。</p>
     */
    @SafeVarargs
    private static <T extends Item & IAEItemPowerStorage> void register(RegisterCapabilitiesEvent event,
            T... items) {
        for (T item : items) {
            event.registerItem(Capabilities.EnergyStorage.ITEM,
                    (stack, ctx) -> new PoweredItemCapabilities(stack, item), item);
        }
    }
}
