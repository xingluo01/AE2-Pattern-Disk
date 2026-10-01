package io.github.lounode.ae2pattern.integration.ae2cs;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.Upgrades;

import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;

/**
 * AE2 Crystal Science ({@code ae2cs}) 的软依赖入口：这台「自装配样板磁盘供应器」只在它安装时存在。
 *
 * <p>
 * 本模组不编译期引用它的任何类型。设备的注册前提与它的超频卡都经这里取：前者是
 * {@link ModList#isLoaded(String)}，后者是在物品注册表里按 id 查 {@code ae2cs:overload_card}——与
 * {@code ExtendedAEPlusCompat} 同一套做法，只是那边连的是上传契约，这边连的是升级卡。
 * </p>
 */
public final class AecsSoftDep {

    /** 目标模组 id。产品代码只认这个字符串，不认它的类。 */
    public static final String MOD_ID = "ae2cs";

    /** 陨石超频卡：它自己的升级卡物品，这里只有一个 id。 */
    private static final ResourceLocation OVERLOAD_CARD_ID = ResourceLocation.parse("ae2cs:overload_card");

    private static volatile Boolean present;

    /** 卡片解析结果缓存。查一次注册表就够，但 tick 路径不查。 */
    private static volatile boolean overloadCardResolved;
    private static volatile Item overloadCard;

    private AecsSoftDep() {}

    /** 目标模组是否在场。设备的整套注册都以它为前提，见 {@code MeteoritePatternProviderRegistrations}。 */
    public static boolean isLoaded() {
        var cached = present;
        if (cached == null) {
            cached = ModList.get().isLoaded(MOD_ID);
            present = cached;
        }
        return cached;
    }

    /**
     * 给 {@code machine} 挂上陨石超频卡，上限 {@code maxCards} 张。AE2 Crystal Science 缺席、或它的卡已被
     * 改名/移除时，什么都不做。
     *
     * <p>取卡的细节（模组在场判定、注册表查询、缓存）留在这里：调用方只需要说「这台设备吃这张卡」，
     * 不必知道它是本模组之外的物品。</p>
     */
    public static void registerOverloadCard(ItemLike machine, int maxCards) {
        var card = overloadCard();
        if (card == null) {
            return;
        }
        Upgrades.add(card, machine, maxCards);
    }

    /** 给定升级库存里装了几张超频卡；未装该模组（或物品缺失）时恒为 0。 */
    public static int installedOverloadCards(IUpgradeInventory upgrades) {
        var card = overloadCard();
        return card == null ? 0 : upgrades.getInstalledUpgrades(card);
    }

    /**
     * 按 id 取超频卡物品，取不到返回 {@code null}。
     *
     * <p>
     * 不在本模组注册表里，所以可能出现「模组在、物品被改名/移除」的组合：组装期的解析失败只让这条加速退化成
     * 0 张卡，不当错误。
     * </p>
     */
    @Nullable
    public static Item overloadCard() {
        if (!isLoaded()) {
            return null;
        }
        if (!overloadCardResolved) {
            overloadCard = BuiltInRegistries.ITEM.get(OVERLOAD_CARD_ID);
            overloadCardResolved = true;
        }
        return overloadCard;
    }
}
