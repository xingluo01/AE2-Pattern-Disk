package io.github.lounode.ae2pattern.integration.ae2wtlib;

import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import appeng.api.features.GridLinkables;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import appeng.items.tools.powered.WirelessTerminalItem;

import de.mari_023.ae2wtlib.api.gui.Icon;
import de.mari_023.ae2wtlib.api.registration.AddTerminalEvent;


/**
 * 三个无线终端在 AE2WTLib 那边的整套登记：终端名、宿主工厂、菜单类型、物品、图标，以及升级卡的补挂。
 *
 * <p>从 {@code AEPatternRegistries} 抽出来，与 {@code MeteoritePatternProviderRegistrations}
 * 同一个口径——一台设备的登记集中在自己的类里，注册中心只留注册表字段与 {@code register} 的编排。</p>
 *
 * <p><b>时机是这里唯一的坑</b>：{@link AddTerminalEvent#register} 的回调由 AE2WTLib 在 ITEM 的
 * {@code RegisterEvent} 里执行。那一刻注册表已开放、物品工厂能返回实例，但 {@code DeferredHolder}
 * 尚未绑定——所以回调里只能走 {@link io.github.lounode.ae2pattern.AEPatternRegistries#wirelessEncodingItem()}
 * 这类工厂，写 {@code ITEM_....get()} 会抛 unbound 的 NPE（本项目在模组构造期栽过一次）。</p>
 */
public final class WirelessTerminalRegistrations {

    /**
     * 图标取自 {@code states.png} 的 y=64 那一行：左侧 (0,64) 是编码终端、(16,64) 是管理终端、(32,64) 是元件
     * 管理终端，各 16&times;16。之前用的图标玩家分不清哪个才是本模组的终端。
     */
    private static final Icon ICON_ENCODING = wirelessIcon(0);
    private static final Icon ICON_MANAGEMENT = wirelessIcon(16);
    private static final Icon ICON_CELL_MANAGEMENT = wirelessIcon(32);
    private static Icon wirelessIcon(int x) {
        return new Icon(x, 64, 16, 16,
                new Icon.Texture(
                        ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"),
                        256, 256));
    }

    private WirelessTerminalRegistrations() {}

    /**
     * 把三个无线终端登记进 AE2WTLib，并在登记完成后补挂升级卡。
     *
     * <p><b>三个参数必须是 Supplier，不能收现成的实例</b>：本方法的调用点在模组构造期
     * （{@code AEPatternRegistries.register}），而物品构造器要往注册表写 intrusive holder——那只允许在
     * 注册表「正在注册」时进行，构造期注册表已经冻结，此时 {@code new} 会抛
     * {@code IllegalStateException: Registry is already frozen}。传 Supplier 之后，物品要等
     * {@link AddTerminalEvent#register} 的回调（由 AE2WTLib 在 ITEM 的 RegisterEvent 里执行）才被造出来，
     * 那一刻注册表是开放的。</p>
     */
    public static void register(Supplier<WirelessPatternDiskTerminalItem> encoding,
            Supplier<WirelessPatternDiskTerminalItem> management,
            Supplier<WirelessPatternDiskTerminalItem> cellManagement) {
        AddTerminalEvent.register(event -> {
            // 到这里才构造物品：回调跑在 ITEM 的 RegisterEvent 里，注册表开放，工厂返回的与注册进去的是同一个。
            var encodingItem = encoding.get();
            var managementItem = management.get();
            var cellManagementItem = cellManagement.get();
            // 终端名（name）不带 _terminal 后缀：AE2WTLib 会把热键名拼成 "wireless_" + name + "_terminal"，
            // 组件名拼成 "has_" + name + "_terminal"（上游自己的 name 就是 pattern_encoding 这种写法）。
            // 带上后缀会拼出 wireless_pattern_disk_encoding_terminal_terminal，热键翻译键也对不上。
            //
            // upgradeCount 是升级槽位数。AE2WTLib 的默认值也是 2，这里仍显式写出：它决定 UpgradeHelper
            // 给「所有终端」挂卡时的上限（取 Math.min(卡自己的 max, 槽位数)），显式声明后上游改默认值时
            // 本模组的槽位数不会跟着漂。2 个槽够放量子桥卡 1 张 + 能源卡 2 张。
            event.builder("pattern_disk_encoding",
                    WirelessPatternDiskTerminalHost::new,
                    PatternDiskWirelessEncodingTermMenu.TYPE,
                    encodingItem,
                    ICON_ENCODING)
                    .upgradeCount(2)
                    .addTerminal();
            event.builder("pattern_disk_management",
                    WirelessPatternDiskTerminalHost::new,
                    PatternDiskWirelessManagementTermMenu.TYPE,
                    managementItem,
                    ICON_MANAGEMENT)
                    .upgradeCount(2)
                    .addTerminal();
            // 第三个终端：元件管理。图标用它自己在 states.png y=64 那一行的第三格 (32,64)。
            event.builder("cell_management",
                    WirelessCellManagementTerminalHost::new,
                    CellManagementWirelessTermMenu.TYPE,
                    cellManagementItem,
                    ICON_CELL_MANAGEMENT)
                    .upgradeCount(2)
                    .addTerminal();

            // 升级与链接登记（全部用回调里刚落地的物品实例）：DeferredHolder 此刻尚未绑定，
            // 所以不能走 AEPatternRegistries 的 ITEM_*.get()，只能拿眼前这三个实例。
            registerTerminalItems(encodingItem, managementItem, cellManagementItem);

            // 补挂量子桥卡：wtlib 的 UpgradeHelper.addUpgrades() 紧随 AddTerminalEvent.run() 执行，本模组此刻
            // 已在 WTDefinition 里，理论上也会被它挂上；这里显式登记同值（1），不把行为押在上游的遍历时机上。
            addUpgradeCards(encodingItem, managementItem, cellManagementItem);
        });
    }

    /**
     * AE2 与 AE2WTLib 两张表上的终端登记：无线接入点收不收这台终端，以及它吃几张能源卡。
     *
     * <p>两张表都在 AE2 的运行时登记表里，与物品的 {@code DeferredHolder} 无关——但登记时机仍在这里，
     * 因为它们要的是「物品实例」，而实例正是在这个回调里才第一次造出来的。</p>
     */
    private static void registerTerminalItems(WirelessPatternDiskTerminalItem encodingItem,
            WirelessPatternDiskTerminalItem managementItem,
            WirelessPatternDiskTerminalItem cellManagementItem) {
        // 无线接入点的「终端绑定槽」按 GridLinkables 注册表决定收不收：AE2 自己只登记了它的两个无线终端
        // （InitGridLinkables），第三方终端不自己登记就会被拒收。LINKABLE_HANDLER 是 AE2 给无线终端用的
        // 现成 handler，直接复用。
        GridLinkables.register(encodingItem, WirelessTerminalItem.LINKABLE_HANDLER);
        GridLinkables.register(managementItem, WirelessTerminalItem.LINKABLE_HANDLER);
        GridLinkables.register(cellManagementItem, WirelessTerminalItem.LINKABLE_HANDLER);

        // 能源卡：上限得由本模组自己登记——AE2WTLib 登记升级卡时只挂量子桥卡与磁卡，从不挂能源卡，而 AE2 的
        // Upgrades.getMaxInstallable 在无人登记某个卡时返回 0（等于装不上）。2 与 AE2 自家的无线终端一致。
        // 那张表先到先得（取首个匹配的上限），而能源卡只有本模组登记，所以这个上限与登记时机无关。
        Upgrades.add(AEItems.ENERGY_CARD, encodingItem, 2);
        Upgrades.add(AEItems.ENERGY_CARD, managementItem, 2);
        Upgrades.add(AEItems.ENERGY_CARD, cellManagementItem, 2);
    }

    /** 给三个无线终端补挂量子桥卡（各 1 张）。 */
    private static void addUpgradeCards(WirelessPatternDiskTerminalItem encodingItem,
            WirelessPatternDiskTerminalItem managementItem,
            WirelessPatternDiskTerminalItem cellManagementItem) {
        // 只补挂量子桥卡：磁卡不需要注册到本模组的终端（按上游登记表 wtlib 也不给它们挂磁卡）。
        for (var entry : Map.of("ae2wtlib:quantum_bridge_card", 1).entrySet()) {
            var card = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getKey()));
            if (card == null) {
                continue;
            }
            // 磁卡不在这里挂：按上游的登记表，wtlib 只把量子桥卡统一挂给所有终端，磁卡是登记给它自家终端的，
            // 所以物品侧那层 ExcludedUpgradeInventory 目前不会触发（留着作第三方 blanket 挂卡的保险）。
            appeng.api.upgrades.Upgrades.add(card, encodingItem, entry.getValue());
            appeng.api.upgrades.Upgrades.add(card, managementItem, entry.getValue());
            appeng.api.upgrades.Upgrades.add(card, cellManagementItem, entry.getValue());
        }
    }
}
