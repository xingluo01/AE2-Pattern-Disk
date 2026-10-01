package io.github.lounode.ae2pattern.integration.rechiseledae;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.neoforged.fml.ModList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.upgrades.Upgrades;

/**
 * Rechiseled: Applied Energistics 的雕凿样板编码器：它装进编码终端的升级槽，雕凿编码模式才会出现。
 *
 * <p>与 {@link io.github.lounode.ae2pattern.integration.advancedae.AdvancedPatternEncoder 高级样板编码器}
 * 同一套做法，两点不同值得记下来：</p>
 *
 * <ul>
 * <li><b>它是方块物品</b>。上游用 {@code registerBlock} + {@code registerItem} 两条注册（core lib 的
 * {@code registerBlock} 不生成 BlockItem），而那个模组只导出了方块字段、没导出物品字段，所以取卡只能走
 * 注册表 + 物品 id，拿不到编译期常量。</li>
 * <li><b>它自己没有任何「装进槽位就生效」的行为</b>：功能全在世界里的方块实体上（右击开它自己的 GUI）。
 * 所以这枚卡在本模组里纯粹是一把钥匙——本模组看见它，就放出雕凿编码模式；它自己不会做任何事。
 * （AdvancedAE 那把至少还是 Curios 饰品，这把连饰品都不是。）</li>
 * </ul>
 */
public final class ChiselingPatternEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.rechiseledae");

    private static final String MOD_ID = "rechiseledae";
    private static final String AE2WTLIB_MOD_ID = "ae2wtlib";
    private static final String UPGRADE_HELPER = "de.mari_023.ae2wtlib.api.registration.UpgradeHelper";

    /** 雕凿样板编码器（方块物品）。它装进终端的升级槽，雕凿编码模式才出现。 */
    public static final ResourceLocation CHISELING_ENCODER = ResourceLocation.parse(
            "rechiseledae:chiseling_pattern_encoder");

    private ChiselingPatternEncoder() {
    }

    /**
     * 把雕凿编码器登记到这些终端上，每种最多一枚。
     *
     * <p>要在模组初始化结束前调用（与 AE2 其他 {@code Upgrades.add} 一样，运行期再改这张表行为未定义）。</p>
     */
    public static void register(ItemLike... terminals) {
        registerFor(terminals);
        registerForWirelessTerminals();
    }

    /** 给这些终端直接登记，每种最多一枚。无线那侧要用它，理由同高级编码器。 */
    public static void registerFor(ItemLike... terminals) {
        var card = chiselingEncoder();
        if (card == null) {
            return;
        }
        for (var terminal : terminals) {
            Upgrades.add(card, terminal, 1);
        }
        // 回读一次。登记没生效的表现是「槽在那儿、卡放不进」，而玩家那时只会以为卡坏了。
        for (var terminal : terminals) {
            if (Upgrades.getMaxInstallable(card, terminal) < 1) {
                LOGGER.warn("RechiseledAE's chiseling pattern encoder did not register for {}; its upgrade slot "
                        + "will show up but stay unusable", BuiltInRegistries.ITEM.getKey(terminal.asItem()));
            }
        }
    }

    /** 这张栈是不是雕凿样板编码器。升级槽的准入与模式的可用性都问它。 */
    public static boolean isEncoder(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(CHISELING_ENCODER);
    }

    /** RechiseledAE 已加载且那件物品在册时返回它，否则 null（并留一条能对上号的日志）。 */
    private static Item chiselingEncoder() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return null;
        }
        var card = BuiltInRegistries.ITEM.get(CHISELING_ENCODER);
        if (card == Items.AIR) {
            LOGGER.debug("RechiseledAE is loaded but {} is not registered; skipping the chiseling pattern encoder",
                    CHISELING_ENCODER);
            return null;
        }
        return card;
    }

    /**
     * 无线终端那一侧：AE2WTLib 的 {@code addUpgradeToAllTerminals} 会把它挂到**所有** AE2WTLib 终端上——
     * 本模组的两个无线终端与它的通用终端都在内。
     */
    private static void registerForWirelessTerminals() {
        if (!ModList.get().isLoaded(AE2WTLIB_MOD_ID)) {
            return;
        }
        var card = chiselingEncoder();
        if (card == null) {
            return;
        }
        try {
            var helper = Class.forName(UPGRADE_HELPER);
            helper.getMethod("addUpgradeToAllTerminals", ItemLike.class, int.class).invoke(null, card, 1);
        } catch (ClassNotFoundException e) {
            // 没装 AE2WTLib：本模组的无线终端也一并缺席，正常。
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not register the chiseling pattern encoder for AE2WTLib's wireless terminals", e);
        }
    }
}
