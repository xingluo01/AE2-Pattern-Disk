package io.github.lounode.ae2pattern.integration.ae2lt;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.upgrades.Upgrades;

/**
 * AE2 Lightning Tech（modId {@code ae2lt}）的过载样板：本终端「过载」档编出来的就是它的物品，
 * 以及那枚「过载样板编码器」在升级表上的登记。
 *
 * <p>隔离在这一包里，与其它集成同一个理由：AE2LT 缺席时这些类不会被解析，本模组照常工作——那一档
 * 也就不会出现（它的可用性看升级槽里有没有那枚编码器，见 {@link #isEncoder(ItemStack)}）。</p>
 *
 * <p>过载样板带两个别处没有的东西：每个槽位声明自己是<b>输入还是输出</b>，以及该槽的<b>组件匹配模式</b>
 * （{@code MatchMode.STRICT} 连组件一起比，{@code ID_ONLY} 只比物品 id）。后者就是面板上那个「忽略组件
 * 匹配」开关——它是样板自身的一部分，随样板走，不需要本模组的磁盘另存一份。</p>
 */
public final class OverloadPatterns {

    /** AE2LT 的 modId。 */
    private static final String MOD_ID = "ae2lt";

    /**
     * 无线终端那一侧要用的 AE2WTLib 登记入口。
     *
     * <p>与高级/雕凿两枚编码器同一个理由：无线终端的升级槽不读 AE2 那张表，而这条登记失败的表观只是
     * 「无线终端上那一格放不进卡」，不该让它带崩整个初始化，所以反射调用、失败只记一条日志。</p>
     */
    private static final String AE2WTLIB_MOD_ID = "ae2wtlib";
    private static final String UPGRADE_HELPER = "de.mari_023.ae2wtlib.api.registration.UpgradeHelper";

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.ae2lt");

    /** AE2LT 的「过载样板编码器」（{@code item.ae2lt.overload_pattern_encoder}）。 */
    private static final ResourceLocation OVERLOAD_ENCODER =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "overload_pattern_encoder");

    private OverloadPatterns() {
    }

    /**
     * 这一叠升级卡里是否装着过载样板编码器——那一档的可用性就按它判，与高级档同口径（升级槽里那张卡）。
     *
     * <p>只查物品 id，不碰 AE2LT 的任何类：注册表按 id 查，模组不在场时同样安全（查不到就是 false），
     * 所以这里不会把缺席的可选模组拖进「加载期定义它的类」那个老坑。</p>
     */
    public static boolean isEncoder(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(OVERLOAD_ENCODER);
    }

    /**
     * 把过载样板编码器登记到这些终端上，每种最多一枚。
     *
     * <p><b>不登记就等于放不进去。</b>升级槽的准入（{@code TerminalUpgradeSlot.mayPlace}）除 AE2 那道
     * {@code instanceof UpgradeCardItem} 外，回退那一闸就是查 {@code Upgrades} 表：
     * {@code upgrades.getMaxInstalled(item) > 0}。过载编码器不是 {@code UpgradeCardItem} 的子类，所以它
     * 只能靠这张表进去——只看 {@link #isEncoder(ItemStack)} 是不够的，那个判定管的是「档位可不可用」，
     * 管不着「卡能不能放进去」。</p>
     *
     * <p>要在模组初始化结束前调用（与 AE2 其他 {@code Upgrades.add} 一样，运行期再改这张表行为未定义）。</p>
     */
    public static void register(ItemLike... terminals) {
        registerFor(terminals);
        registerForWirelessTerminals();
    }

    /** 给这些终端直接登记，每种最多一枚。无线那侧要用它，理由同高级编码器。 */
    public static void registerFor(ItemLike... terminals) {
        var card = encoder();
        if (card == null) {
            return;
        }
        for (var terminal : terminals) {
            Upgrades.add(card, terminal, 1);
        }
        // 回读一次。登记没生效的表观是「槽在那儿、卡放不进」，而玩家那时只会以为卡坏了。
        for (var terminal : terminals) {
            if (Upgrades.getMaxInstallable(card, terminal) < 1) {
                LOGGER.warn("AE2LT's overload pattern encoder did not register for {}; its upgrade slot will "
                        + "show up but stay unusable", BuiltInRegistries.ITEM.getKey(terminal.asItem()));
            }
        }
    }

    /** AE2LT 已加载且那件物品在册时返回它，否则 null（并留一条能对上号的日志）。 */
    @Nullable
    private static Item encoder() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return null;
        }
        var card = BuiltInRegistries.ITEM.get(OVERLOAD_ENCODER);
        if (card == Items.AIR) {
            LOGGER.debug("AE2LT is loaded but {} is not registered; skipping the overload pattern encoder",
                    OVERLOAD_ENCODER);
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
        var card = encoder();
        if (card == null) {
            return;
        }
        try {
            var helper = Class.forName(UPGRADE_HELPER);
            helper.getMethod("addUpgradeToAllTerminals", ItemLike.class, int.class).invoke(null, card, 1);
        } catch (ClassNotFoundException e) {
            // 没装 AE2WTLib：本模组的无线终端也一并缺席，正常。
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not register the overload pattern encoder for AE2WTLib's wireless terminals", e);
        }
    }
}
