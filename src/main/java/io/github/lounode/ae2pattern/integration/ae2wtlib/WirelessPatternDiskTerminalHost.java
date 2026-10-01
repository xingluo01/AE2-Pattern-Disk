package io.github.lounode.ae2pattern.integration.ae2wtlib;

import java.util.function.BiConsumer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import appeng.menu.ISubMenu;
import appeng.menu.locator.ItemMenuHostLocator;

import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.menu.DiskEncodingLogic;
import io.github.lounode.ae2pattern.common.menu.IPatternDiskTerminalHost;

/**
 * 无线终端的宿主：与面板版的 {@link io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart}
 * 对位——同一份 {@link DiskEncodingLogic}，同一份菜单代码，差别只在状态存在哪。
 *
 * <p>面板把编码状态写进部件的 NBT，无线写进物品自己的数据组件（{@link AEPatternRegistries#WIRELESS_TERMINAL_LOGIC}），
 * 于是终端跟着物品走：换槽位、放背包、丢地上再捡起来，回来还是上次那台。AE2WTLib 自己的无线编码终端
 * （{@code WETMenuHost}）也是这个口径，只是它用 AE2WTLib 的组件。</p>
 *
 * <p>面板形态有两种（编码 / 管理），无线形态同样两种；菜单不同、宿主与逻辑相同，所以这一个宿主类给两个
 * 无线终端共用，靠各自的 {@code WTMenuHostFactory} 交给各自的菜单。</p>
 */
public class WirelessPatternDiskTerminalHost extends WTMenuHost implements IPatternDiskTerminalHost {

    private final DiskEncodingLogic logic = new DiskEncodingLogic(this);

    /**
     * 无线终端的配置管理器。物品那侧（{@code ItemWT.getConfigManager}）只注册了排序/视图三项，
     * 而管理终端要用的「显示模式」不在其中——直接读会抛 {@code UnsupportedSettingException}，
     * 连带分组清单也发不出去（表格空白、按钮状态不回显）。所以在同一份存储（物品栈）上补注册一项，
     * 前三项与物品侧保持一致，否则档位会两边打架。
     */
    private final appeng.api.util.IConfigManager configManager;

    public WirelessPatternDiskTerminalHost(ItemWT item, Player player, ItemMenuHostLocator locator,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(item, player, locator, returnToMainMenu);
        this.configManager = de.mari_023.ae2wtlib.api.terminal.AE2wtlibConfigManager.builder(this::getItemStack)
                .registerSetting(appeng.api.config.Settings.SORT_BY, appeng.api.config.SortOrder.NAME)
                .registerSetting(appeng.api.config.Settings.VIEW_MODE, appeng.api.config.ViewItems.ALL)
                .registerSetting(appeng.api.config.Settings.SORT_DIRECTION, appeng.api.config.SortDir.ASCENDING)
                .registerSetting(appeng.api.config.Settings.TERMINAL_SHOW_PATTERN_PROVIDERS,
                        appeng.api.config.ShowPatternProviders.VISIBLE)
                .build();
        // 开屏即恢复：组件里那份 NBT 就是上次关屏时写下的（没有则是全新终端，得到一份默认状态）。
        this.logic.readFromNBT(this.getItemStack().getOrDefault(componentType(), new CompoundTag()),
                player.registryAccess());
    }

    @Override
    public appeng.api.util.IConfigManager getConfigManager() {
        return this.configManager;
    }

    @Override
    public DiskEncodingLogic getLogic() {
        return this.logic;
    }

    @Override
    public Level getLevel() {
        return getPlayer().level();
    }

    @Override
    public void markForSave() {
        var tag = new CompoundTag();
        this.logic.writeToNBT(tag, getPlayer().registryAccess());
        this.getItemStack().set(componentType(), tag);
    }

    private static DataComponentType<CompoundTag> componentType() {
        return AEPatternRegistries.WIRELESS_TERMINAL_LOGIC.get();
    }
}
