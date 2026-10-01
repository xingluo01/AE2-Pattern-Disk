package io.github.lounode.ae2pattern.common.menu;

import appeng.api.storage.ITerminalHost;
import appeng.api.upgrades.IUpgradeInventory;

/**
 * 终端的宿主契约：面板形态（部件）与无线形态（物品）都得满足的那一份。
 *
 * <p>把两件事合成一个类型，是因为两种形态没有共同的具体父类：部件走 AE2 的
 * {@link appeng.parts.reporting.AbstractTerminalPart}，无线走 AE2WTLib 的
 * {@code WTMenuHost}（它经 AE2 的 {@code WirelessTerminalMenuHost} 与 {@code IPortableTerminal} 已经
 * 满足 {@link ITerminalHost}）。菜单既需要 {@link ITerminalHost}（父类 {@code MEStorageMenu} 的构造要求），
 * 又需要 {@link IDiskEncodingLogicHost} 提供的编码逻辑。菜单只认这个接口，面板与无线各自提供自己的
 * 实现，于是两版终端共用同一份菜单代码。</p>
 */
public interface IPatternDiskTerminalHost extends ITerminalHost, IDiskEncodingLogicHost {

    /**
     * 升级库存：高级样板编码器放这儿，装上它编码终端才会多出高级编码模式。
     *
     * <p>面板版建在部件自己身上，无线版在终端物品的组件里（跟着物品走）。不是可选能力：两个宿主都得先
     * 有库存，菜单才能把那一格摆出来。</p>
     */
    IUpgradeInventory getUpgrades();
}
