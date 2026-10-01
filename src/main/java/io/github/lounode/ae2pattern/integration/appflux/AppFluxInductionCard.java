package io.github.lounode.ae2pattern.integration.appflux;

import java.lang.reflect.InvocationTargetException;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.Upgrades;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

/**
 * Applied Flux（{@code appflux}）的感应卡：让设备从 ME 网络里存的 FE 取电。
 *
 * <p>它做的事全在服务端那一半，而且不在本模组这一侧——AppFlux 的 mixin 打在 AE2 的
 * {@code PatternProviderLogic} 与 {@code PatternProviderLogicHost} 上：前者多出一格名为
 * {@code af_upgrades} 的升级库存（用宿主的终端图标当机器物品），后者被扩成 {@code IUpgradeableObject}
 * 并加出 {@code getUpgrades()}。本模组的供应器两种形态都继承那条逻辑、也都实现那个宿主接口，所以取电
 * 能力本来就长在我们身上。</p>
 *
 * <p>缺的只是两件事，都在本类里补：</p>
 *
 * <ol>
 * <li><b>卡的上限登记</b>。AE2 按 {@code Upgrades} 表决定一张卡能装几枚，没登记就是 0 枚、一张也放不
 * 进去。AppFlux 只给 AE2 自己的供应器与接口登记过，所以本模组得为自己的方块与面板各登记一次。</li>
 * <li><b>把那一格显示出来</b>。槽在宿主的升级库存里，但菜单不加槽，玩家就看不到也放不进去；
 * {@code getUpgrades()} 是 AppFlux 由接口 mixin 加出来的方法，编译期不可见，只能反射调用。</li>
 * </ol>
 *
 * <p>两件都只在 AppFlux 在场时做。缺席时 {@link #upgradesOf} 与 {@link #register} 各自安静退场，
 * 供应器的行为与没有本集成时一模一样——这正是软依赖该有的样子。</p>
 *
 * <p><b>有意不包含的自装配（陨石）供应器</b>：它继承同一条父类逻辑，所以 AppFlux 也会在它身上挂出那格
 * 库存，但本模组不给它登记卡上限、菜单也不展示它（那边展示的是 AECS 的超频卡槽）。理由很简单：那台机器的
 * 定位本来就是“自给自足”，给它接网络供电没有意义。</p>
 */
public final class AppFluxInductionCard {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.appflux");

    private static final String MOD_ID = "appflux";
    /** AppFlux 的感应卡注册名。按 id 取而不是引用它的类：编译期不依赖 AppFlux。 */
    private static final String INDUCTION_CARD_ID = "appflux:induction_card";

    private AppFluxInductionCard() {
    }

    /**
     * 把感应卡登记到这些机器上，每种最多一枚。
     *
     * <p>机器物品必须与 AppFlux 建那一格库存时用的物品一致——它用的是宿主的终端图标，也就是传进来的这些。
     * 对不上就等于没登记：库存空着，卡也放不进去。</p>
     *
     * <p>要在模组初始化结束前调用（与 AE2 其他 {@code Upgrades.add} 一样，运行时再改这张表，行为未定义）。
     * </p>
     */
    public static void register(ItemLike... machines) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        var card = inductionCard();
        if (card == null) {
            LOGGER.debug("Applied Flux is loaded but {} is not registered; skipping the induction card",
                    INDUCTION_CARD_ID);
            return;
        }
        for (var machine : machines) {
            Upgrades.add(card, machine, 1);
        }
        // 回读一次。登记没生效的表现是「槽在那儿、卡放不进」，而玩家那时只会以为卡坏了。
        for (var machine : machines) {
            if (Upgrades.getMaxInstallable(card, machine) < 1) {
                LOGGER.warn("Applied Flux's induction card did not register for {}; its upgrade slot will show "
                        + "up but stay unusable", BuiltInRegistries.ITEM.getKey(machine.asItem()));
            }
        }
    }

    /**
     * 宿主上由 AppFlux 挂出来的那一格升级库存；AppFlux 缺席、或它的接口 mixin 没生效时返回 {@code null}。
     *
     * <p>反射而不是直接调用：{@code getUpgrades()} 不是本模组编译期能看见的方法。</p>
     */
    @Nullable
    public static IUpgradeInventory upgradesOf(PatternProviderLogicHost host) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return null;
        }
        try {
            var method = PatternProviderLogicHost.class.getMethod("getUpgrades");
            return method.invoke(host) instanceof IUpgradeInventory inventory ? inventory : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | RuntimeException e) {
            // 方法不在、访问被拒、或宿主还没连上：都当作没有这一格。菜单少一格，不是错误。
            return null;
        }
    }

    @Nullable
    private static Item inductionCard() {
        var card = BuiltInRegistries.ITEM.get(ResourceLocation.parse(INDUCTION_CARD_ID));
        return card == Items.AIR ? null : card;
    }
}
