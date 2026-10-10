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
import appeng.helpers.IPriorityHost;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import de.mari_023.ae2wtlib.api.terminal.AE2wtlibConfigManager;
import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.menu.CellDriveScanner;
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
        implements ICellManagementHost, InternalInventoryHost, IPriorityHost {

    /** 组件里两个键：编码槽内容与「正在配优先级的那一台」。 */
    private static final String ENCODE_CELL_KEY = "encodeCell";
    private static final String PRIORITY_TARGET_KEY = "priorityTarget";

    /** 编码槽内容一变就要落盘（库存在变时会叫 {@link #saveChangedInventory}）：它跟着物品走，掉线或换维度都得还在。 */
    private final AppEngInternalInventory encodeCell = new AppEngInternalInventory(this, 1);

    /**
     * 中键要配优先级的那一台（首格键）。
     *
     * <p><b>必须写进物品组件</b>：AE2 的优先级界面是用本菜单的 locator 开的，服务端把 locator 解析回
     * 「本类的一个新实例」——面板形态那边 locator 解析回的是同一个部件实例，所以它存在字段上就够，无线形态
     * 不行：不写组件的话，新实例读到的目标永远是空的，界面就成了摆设（读 0、写无声）。</p>
     *
     * <p>次生好处：屏幕一关就丢的选中态因此跟着物品走，从优先级界面返回后再中键同一台仍然对得上。</p>
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
        var tag = this.getItemStack().getOrDefault(componentType(), new CompoundTag());
        this.encodeCell.readFromNBT(tag, ENCODE_CELL_KEY, player.registryAccess());
        this.priorityTarget = tag.getString(PRIORITY_TARGET_KEY);
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
        // 读-改-写，而不是新建一整份：这个组件里还躺着别的键（优先级目标），而 set 是整个写进去的——
        // new CompoundTag() 就等于把没写回去的那个键抹掉。现在只有一个写入者，这么写是为了下一个。
        var tag = this.getItemStack().getOrDefault(componentType(), new CompoundTag()).copy();
        this.encodeCell.writeToNBT(tag, ENCODE_CELL_KEY, getPlayer().registryAccess());
        if (this.priorityTarget != null) {
            tag.putString(PRIORITY_TARGET_KEY, this.priorityTarget);
        } else {
            // 空值要主动抹掉：读-改-写会把这行旧字符串原样带过来，不抹的话将来加「清除目标」时会清不掉。
            tag.remove(PRIORITY_TARGET_KEY);
        }
        this.getItemStack().set(componentType(), tag);
    }

    @Override
    public void setPriorityTarget(@Nullable String leaderKey) {
        this.priorityTarget = leaderKey;
        // 立刻落盘：优先级界面是被 locator 重新解析出来的新实例，它只读得到组件里的值。
        markForSave();
    }

    //
    // AE2 优先级界面（PriorityMenu）对这个宿主的全部要求：数值转给那一台，返回键与图标走 AE2WTLib 的现成实现
    // （WirelessTerminalMenuHost 已经提供了 returnToMainMenu 与 getMainMenuIcon，这里只补读写）。
    //

    /**
     * 中键选中的那一台；不在网里、或没选中时为 null。
     *
     * <p>取网的方式与面板形态那边不同：那里是部件自己的节点，这里是<b>当前接入点</b>的节点
     * （{@link #getActionableNode()} 就是 AE2 无线宿主的那个实现）。超距或断电时它为空，与面板形态节点
     * 未加载时同口径——都只是拿不到目标，不假装成功。</p>
     */
    @Nullable
    private Object priorityTargetOwner() {
        if (this.priorityTarget == null || this.priorityTarget.isEmpty()) {
            return null;
        }
        var node = getActionableNode();
        if (node == null || node.getGrid() == null) {
            return null;
        }
        return CellDriveScanner.findOwner(node.getGrid(), this.priorityTarget);
    }

    @Override
    public int getPriority() {
        Object owner = priorityTargetOwner();
        if (owner instanceof IPriorityHost priorityHost) {
            return priorityHost.getPriority();
        }
        Integer stored = owner == null ? null : CellDriveScanner.readStoragePriority(owner);
        return stored != null ? stored : 0;
    }

    @Override
    public void setPriority(int newValue) {
        Object owner = priorityTargetOwner();
        if (owner instanceof IPriorityHost priorityHost) {
            priorityHost.setPriority(newValue);
            return;
        }
        // ECO 本体只给了 getter，这一句会返回 false：写不进去时保持原值，不假装成功。
        if (owner != null) {
            CellDriveScanner.setStoragePriority(owner, newValue);
        }
    }

    /**
     * 从优先级界面按返回时重开本终端，而不是回通用终端主界面。
     *
     * <p>面板形态那边自己实现了这一条（{@code CellManagementTerminalPart.returnToMainMenu} 重开元件管理终端）；
     * 无线形态如果沿用上游回调（AE2WTLib 给的是「回通用终端主界面」），同一个功能就会在两个形态上落回不同的
     * 地方——优先级界面是从本终端里开出去的，返回键应该把玩家放回原处。</p>
     */
    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        // 用 returnTo 而不是 open：这次确实是「从子菜单返回」，本仓另外几处返回同类也都走 returnTo，
        // locator 则取子菜单自己的那个（它就是当初开这个子菜单用的那个）。
        MenuOpener.returnTo(CellManagementWirelessTermMenu.TYPE, player, subMenu.getLocator());
    }

    private static DataComponentType<CompoundTag> componentType() {
        return AEPatternRegistries.WIRELESS_CELL_TERMINAL.get();
    }
}
