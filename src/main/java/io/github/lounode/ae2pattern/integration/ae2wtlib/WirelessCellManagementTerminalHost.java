package io.github.lounode.ae2pattern.integration.ae2wtlib;

import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.inventories.InternalInventory;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.util.IConfigManager;
import appeng.menu.ISubMenu;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import de.mari_023.ae2wtlib.api.terminal.AE2wtlibConfigManager;
import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.menu.ICellManagementHost;

/**
 * 无线版元件管理终端的宿主：与面板版的 {@code CellManagementTerminalPart} 对位——同一份菜单与屏幕代码，
 * 差别只在状态存在哪。面板把编码槽内容写进部件的 NBT，无线写进物品自己的数据组件
 * （{@link AEPatternRegistries#WIRELESS_CELL_TERMINAL}），于是终端跟着物品走：换槽位、放背包、丢地上再捡
 * 回来，回来还是上次那台。
 *
 * <p>元件升级槽不在宿主身上：那是「编码槽里那张元件」自己的库存（面板与无线同一口径，见
 * {@code EncodeCellUpgrades}），所以这里只提供编码槽本身。</p>
 */
public class WirelessCellManagementTerminalHost extends WTMenuHost
        implements ICellManagementHost, InternalInventoryHost {

    /** 编码槽内容一变就要落盘（库存在变时会叫 {@link #saveChangedInventory}）：它跟着物品走，掉线或换维度都得还在。 */
    private final AppEngInternalInventory encodeCell = new AppEngInternalInventory(this, 1);

    /**
     * 中键要配优先级的那一台（首格键）。
     *
     * <p>不写进组件：它是「当前开着的那一屏」选中的目标，屏幕一关就没有意义；面板版把它写进部件 NBT 只是
     * 顺手（那边本来就有个部件可以挂），不是它需要被记住。</p>
     *
     * <p><b>无线版目前还走不到这条</b>：菜单里那个中键入口只在宿主是面板部件时放行（那边的
     * {@code IPriorityHost} 与返回键都指得着一台部件）。面板版的中键配优先级在无线版暂时没有对应入口——
     * 这个字段先留着，接上时它就是那个目标。</p>
     */
    @Nullable
    private String priorityTarget;

    /**
     * 无线终端的配置管理器。物品那侧（{@code ItemWT.getConfigManager}）只注册了排序与视图三项，而本终端要用
     * 的「排序顺序」决定表格按优先级从大到小还是从小到大；不补注册的话读它会抛
     * {@code UnsupportedSettingException}——那正是工具栏上那枚按钮的状态来源。
     */
    private final IConfigManager configManager;

    public WirelessCellManagementTerminalHost(ItemWT item, Player player, ItemMenuHostLocator locator,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(item, player, locator, returnToMainMenu);
        this.configManager = AE2wtlibConfigManager.builder(this::getItemStack)
                .registerSetting(Settings.SORT_BY, SortOrder.NAME)
                .registerSetting(Settings.VIEW_MODE, ViewItems.ALL)
                .registerSetting(Settings.SORT_DIRECTION, SortDir.ASCENDING)
                .build();
        // 开屏即恢复：组件里那份 NBT 就是上次关屏时写下的（没有则是全新终端，得到一格空的编码槽）。
        this.encodeCell.readFromNBT(this.getItemStack().getOrDefault(componentType(), new CompoundTag()),
                "encodeCell", player.registryAccess());
    }

    @Override
    public IConfigManager getConfigManager() {
        return this.configManager;
    }

    @Override
    public InternalInventory getEncodeCellInventory() {
        return this.encodeCell;
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        // 真正的落盘在 saveChangedInventory 里（库存在改完时会叫它），这里不用再做什么。
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        markForSave();
    }

    @Override
    public boolean isClientSide() {
        return getPlayer().level().isClientSide();
    }

    @Override
    public void markForSave() {
        var tag = new CompoundTag();
        this.encodeCell.writeToNBT(tag, "encodeCell", getPlayer().registryAccess());
        this.getItemStack().set(componentType(), tag);
    }

    @Override
    public void setPriorityTarget(@Nullable String leaderKey) {
        this.priorityTarget = leaderKey;
    }

    private static DataComponentType<CompoundTag> componentType() {
        return AEPatternRegistries.WIRELESS_CELL_TERMINAL.get();
    }
}
