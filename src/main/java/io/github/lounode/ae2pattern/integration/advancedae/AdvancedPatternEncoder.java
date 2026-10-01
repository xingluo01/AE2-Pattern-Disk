package io.github.lounode.ae2pattern.integration.advancedae;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.neoforged.fml.ModList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.upgrades.Upgrades;

/**
 * AdvancedAE 的高级样板编码器：它装进编码终端的升级槽，高级编码模式才会出现。
 *
 * <p>形状与 {@link io.github.lounode.ae2pattern.integration.appflux.AppFluxInductionCard} 相同：AE2 的
 * {@code UPGRADES} 槽按 {@code Upgrades} 表准入（判定就是 {@code Upgrades.isUpgradeCardItem}），所以一张
 * 卡想放进去就得先登记，而 AdvancedAE 只把它当饰品（Curios），不会替本模组的终端登记。</p>
 *
 * <p>两条路要分别登记，因为无线终端不读 AE2 那张表：面板版（部件）走 {@code Upgrades.add}，无线版
 * （含 AE2WTLib 的通用终端）走它的 {@code UpgradeHelper.addUpgradeToAllTerminals}。后者用反射调用——本模组
 * 把 AE2WTLib 当运行时邻居，而这条登记失败的表现只是「无线终端上那一格放不进卡」，不该让它带崩整个初始化。</p>
 */
public final class AdvancedPatternEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.advancedae");

    private static final String MOD_ID = "advanced_ae";
    private static final String AE2WTLIB_MOD_ID = "ae2wtlib";
    private static final String UPGRADE_HELPER = "de.mari_023.ae2wtlib.api.registration.UpgradeHelper";

    private AdvancedPatternEncoder() {
    }

    /**
     * 把高级样板编码器登记到这些终端上，每种最多一枚。
     *
     * <p>要在模组初始化结束前调用（与 AE2 其他 {@code Upgrades.add} 一样，运行期再改这张表行为未定义）。</p>
     */
    public static void register(ItemLike... terminals) {
        registerFor(terminals);
        registerForWirelessTerminals();
    }

    /**
     * 给这些终端直接登记高级样板编码器，每种最多一枚。
     *
     * <p>无线终端那侧要用它：那些物品实例只在 AE2WTLib 的 {@code AddTerminalEvent} 回调里才造得出来，
     * 本模组构造期拿不到，而那条路（{@code UpgradeHelper}）走的是上游自己的终端表与 readyForUpgrades
     * 时序，本模组的两个终端在不在其中不由我们说了算。</p>
     */
    public static void registerFor(ItemLike... terminals) {
        var card = advancedEncoderCard();
        if (card == null) {
            return;
        }
        for (var terminal : terminals) {
            Upgrades.add(card, terminal, 1);
        }
        // 回读一次。登记没生效的表现是「槽在那儿、卡放不进」，而玩家那时只会以为卡坏了。
        for (var terminal : terminals) {
            if (Upgrades.getMaxInstallable(card, terminal) < 1) {
                LOGGER.warn("AdvancedAE's pattern encoder did not register for {}; its upgrade slot will show "
                        + "up but stay unusable", BuiltInRegistries.ITEM.getKey(terminal.asItem()));
            }
        }
    }

    /** AdvancedAE 已加载且它那张卡在册时返回该卡，否则 null（并留一条能对上号的日志）。 */
    private static Item advancedEncoderCard() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return null;
        }
        var card = BuiltInRegistries.ITEM.get(AdvPatternSupport.ADVANCED_ENCODER);
        if (card == Items.AIR) {
            LOGGER.debug("AdvancedAE is loaded but {} is not registered; skipping the advanced pattern encoder",
                    AdvPatternSupport.ADVANCED_ENCODER);
            return null;
        }
        return card;
    }

    /**
     * 无线终端那一侧：AE2WTLib 的 {@code addUpgradeToAllTerminals} 会把它挂到**所有** AE2WTLib 终端上——
     * 本模组的两个无线终端与它的通用终端都在内，这正是「与通用终端兼容」想要的。
     */
    private static void registerForWirelessTerminals() {
        if (!ModList.get().isLoaded(AE2WTLIB_MOD_ID)) {
            return;
        }
        var card = advancedEncoderCard();
        if (card == null) {
            return;
        }
        try {
            var helper = Class.forName(UPGRADE_HELPER);
            helper.getMethod("addUpgradeToAllTerminals", ItemLike.class, int.class).invoke(null, card, 1);
        } catch (ClassNotFoundException e) {
            // 没装 AE2WTLib：本模组的无线终端也一并缺席，正常。
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not register the advanced pattern encoder for AE2WTLib's wireless terminals", e);
        }
    }
}
