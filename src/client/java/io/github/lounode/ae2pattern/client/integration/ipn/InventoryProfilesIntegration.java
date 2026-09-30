package io.github.lounode.ae2pattern.client.integration.ipn;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * Inventory Profiles Next（IPN）集成：告诉它的整理器，本模组的终端属于「只有玩家背包可整理」的那一类。
 *
 * <p>为什么需要这一步：IPN 对**它不认识的菜单**按固定集合 {@code {SORTABLE_STORAGE, RECTANGULAR,
 * WIDTH_9}} 处理，也就是把菜单里的每一个槽都当成可整理的存储。本模组的终端里恰好住着两类不该被整理的槽
 * ——无线版的升级卡槽（升级卡存在终端物品上）与样板输出槽（{@code PatternOutputSlot}，只由「编码」填入，
 * 玩家本就取不走）。整理会把它们的内容搬到光标上：槽的限制写在 {@code mayPickup} 一侧，而整理走的是
 * 直接换位，不经过那道判断。</p>
 *
 * <p>IPN 的公开登记口是 {@code ContainerTypes.addContainersSource}，登记后该菜单改按登记的类型集处理。
 * 这里登记的正是它自己 {@code playerSideOnly} 分支用的那个集合（{@code {PURE_BACKPACK, PLAYER,
 * CRAFTING}}，由 {@code ContainerTypesKt.getPlayerOnly()} 给出），即「只整理玩家背包」——与 AE2 自己的
 * 终端在它内置表里的待遇一致（那些屏幕标的是 {@code playerSideOnly: true}）。登记父菜单类即可：它按
 * {@code isInstance} 匹配，管理终端与两个无线版都是 {@link PatternDiskEncodingTermMenu} 的子类。</p>
 *
 * <p>全程反射，本模组不在编译期依赖 IPN：它只是可选前置，且只存在于客户端。任一环节缺失（类不在、方法
 * 签名变了）就安静退场，整理按钮照旧工作，只是本模组的槽仍会被整理——同「没装 IPN」时的表现。注册放在
 * 客户端模组构造器里（见 {@code AE2PatternDiskClient}），早于 IPN 消费登记表。</p>
 */
public final class InventoryProfilesIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.ipn");

    /** IPN 的 modid。 */
    public static final String MOD_ID = "inventoryprofilesnext";

    private InventoryProfilesIntegration() {
    }

    /**
     * 把本模组的终端菜单登记为「只整理玩家背包」。IPN 缺席时什么都不做。
     */
    public static void registerTerminalMenus() {
        try {
            Class<?> containerTypes = Class.forName("org.anti_ad.mc.ipnext.inventory.ContainerTypes");
            Class<?> function0 = Class.forName("kotlin.jvm.functions.Function0");
            Class<?> pairClass = Class.forName("kotlin.Pair");

            Object instance = containerTypes.getField("INSTANCE").get(null);
            // 用 IPN 自己的 playerOnly 集合，而不是自己拼一个：那正是它给 playerSideOnly 屏幕用的集合，
            // 跟着它走，上游改档位时这里不用追。
            Object playerOnly = Class.forName("org.anti_ad.mc.ipnext.inventory.ContainerTypesKt")
                    .getMethod("getPlayerOnly")
                    .invoke(null);

            Object entry = pairClass.getConstructor(Object.class, Object.class)
                    .newInstance(PatternDiskEncodingTermMenu.class, playerOnly);
            Object entries = Array.newInstance(pairClass, 1);
            Array.set(entries, 0, entry);

            // Function0 在 Kotlin 侧是接口，用动态代理实现；只实现 invoke，其余三个方法按 Object 契约答。
            Object source = Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[] { function0 },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "invoke" -> entries;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "ae2_pattern_disk terminal menus";
                        default -> null;
                    });

            containerTypes.getMethod("addContainersSource", function0).invoke(instance, source);
            // 登记只是把源放进列表，表本身是在 IPN 的 init()/reset() 里由源重建的。取 INSTANCE 那一步已经
            // 触发过它的静态 init()，所以只登记不重置，这次登记要等到 IPN 下一次重置才生效；而在此之前，
            // getTypes 会把本模组的菜单按「认不出」写进它的外层缓存，按查找顺序压过这里登记的父类，
            // 只有 reset() 能纠正。所以登记完就地重置一次：登记立即生效，旧缓存也清干净。
            containerTypes.getMethod("reset").invoke(instance);
            verifyRegistered(containerTypes);
        } catch (ClassNotFoundException absent) {
            // 类找不到：要么没装 IPN，要么它的类名改了。两者都是「整理那套东西不生效」。
            LOGGER.debug("[AE2-Pattern-Disk] Inventory Profiles Next integration unavailable", absent);
        } catch (Throwable broken) {
            // 这行代码跑在客户端 mod 构造期（见 AE2PatternDiskClient），抛出去就是启动失败，而它要做的不过
            // 是给可选前置登记一条整理规则——失败就该退到「没登记」这一档，而不是把游戏带下去。
            // 宽到 Throwable 与同仓 ToolbarOrder.buttons 同一口径。
            LOGGER.warn("[AE2-Pattern-Disk] Inventory Profiles Next found but its container registry could "
                    + "not be reached; terminal slots stay sortable", broken);
        }
    }

    /**
     * 启动后回头问一句整理模组：这四个屏幕它认不认（认了才只整理玩家背包）。这是对类上
     * {@code @IPNPlayerSideOnly} 的复查——注解写错位置、或上游换了注解名，这里先说出来。
     *
     * <p>它的配置表在资源加载时才读，但注解路径不经过那张表，所以这里不会因「表还没读」而误报。
     */
    public static void reportAnnotatedScreens(Class<?>... screens) {
        try {
            Class<?> hintsManager = Class.forName("org.anti_ad.mc.ipnext.integration.HintsManagerNG");
            Object instance = hintsManager.getField("INSTANCE").get(null);
            Method isPlayerSideOnly = hintsManager.getMethod("isPlayerSideOnly", Class.class);
            List<String> unrecognised = new ArrayList<>();
            for (Class<?> screen : screens) {
                if (!(Boolean) isPlayerSideOnly.invoke(instance, screen)) {
                    unrecognised.add(screen.getSimpleName());
                }
            }
            if (unrecognised.isEmpty()) {
                LOGGER.info("[AE2-Pattern-Disk] Inventory Profiles Next recognises every terminal screen via "
                        + "@IPNPlayerSideOnly; sorting leaves their slots alone");
            } else {
                // 这是防「新加了终端屏但忘了标注解」的唯一信号，所以进 warn 而不是 debug，且一次报全。
                LOGGER.warn("[AE2-Pattern-Disk] Inventory Profiles Next does not treat {} as player-side-only; "
                        + "sorting may still move items out of them", unrecognised);
            }
        } catch (Throwable unanswered) {
            // 没装整理模组、或它还没读进配置表：这只是一次复查，答不上来不改变任何行为。
            LOGGER.debug("[AE2-Pattern-Disk] Inventory Profiles Next annotation check skipped", unanswered);
        }
    }

    /**
     * 登记后的一次自检：IPN 的容器类型表是 private 静态字段，直接读回来确认本模组的菜单真的在表里。
     * 没有这一步，「已登记」只等于「源进了列表」——而列表要等它下次重置才会被消费，两者不是一回事。
     */
    private static void verifyRegistered(Class<?> containerTypes) {
        try {
            var innerMap = containerTypes.getDeclaredField("innerMap");
            innerMap.setAccessible(true);
            if (innerMap.get(null) instanceof java.util.Map<?, ?> table
                    && table.containsKey(PatternDiskEncodingTermMenu.class)) {
                LOGGER.info("[AE2-Pattern-Disk] Inventory Profiles Next present; terminal menus are registered "
                        + "as player-backpack-only, so sorting leaves their slots alone");
            } else {
                LOGGER.warn("[AE2-Pattern-Disk] Inventory Profiles Next accepted the registration but its "
                        + "container table does not list the terminal menus; sorting may still reach their slots");
            }
        } catch (Throwable unreadable) {
            LOGGER.debug("[AE2-Pattern-Disk] Container type table could not be read back", unreadable);
        }
    }
}
