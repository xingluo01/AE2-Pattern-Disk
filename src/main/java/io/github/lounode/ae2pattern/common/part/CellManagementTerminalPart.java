package io.github.lounode.ae2pattern.common.part;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.helpers.IPriorityHost;
import appeng.items.parts.PartModels;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.PartModel;
import appeng.parts.reporting.AbstractTerminalPart;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2pattern.common.menu.CellDriveScanner;
import io.github.lounode.ae2pattern.common.menu.ICellManagementHost;
import io.github.lounode.ae2pattern.common.menu.CellManagementTermMenu;
import io.github.lounode.ae2pattern.common.menu.EncodeCellUpgrades;

/**
 * 元件管理终端：挂在电缆上的终端，表格每行首格是一个驱动器、后面是它里面的存储元件，
 * 右下角是「元件编码槽 + 元件升级槽 + 元件标记区」。
 *
 * <p>与样板终端那条继承链（{@code PatternDiskEncodingTerminalPart}）刻意无关：两者的库存、槽位数量与
 * 菜单都不是一回事，共用一个基类只会让两边互相牵制。这里只保留面板终端的最小共性——
 * {@link AbstractTerminalPart} 的网络接口、模型与落地成掉落的收口。</p>
 *
 * <p><b>库存只有一份，建在部件自己身上</b>（元件编码槽），不是物品组件：它得跟着这台装在世界里的终端走，
 * 拆下来时连带掉出，所以三条收口（{@link #addAdditionalDrops}、{@link #clearContent}、NBT 读写）必须同时
 * 点全——漏一处就是丢东西或复制东西。元件标记区与元件升级槽不在这里：它们直接读写编码槽里那张元件自己的
 * 分区与升级库存，不在部件上存第二份。</p>
 *
 * <p><b>外观已独立</b>：这台终端有自己的一份外壳贴图与模型（{@code models/part/cell_management_terminal_off}、
 * {@code _on} 与它们引用的 {@code part/cell_management_terminal_bright/medium/dark}），不再是指向管理终端那
 * 两只的别名——两边以后各改各的，不会再牵动对方。四张通用监视器底壳贴图（{@code monitor_sides/back/front/
 * colored}）仍共用：那是监视器外形的公共件，不属于任何一台终端的外观。</p>
 */
public class CellManagementTerminalPart extends AbstractTerminalPart implements ICellManagementHost,
        IPriorityHost {

    @PartModels
    public static final ResourceLocation MODEL_OFF = ResourceLocation.parse(
            "ae2_pattern_disk:part/cell_management_terminal_off");
    @PartModels
    public static final ResourceLocation MODEL_ON = ResourceLocation.parse(
            "ae2_pattern_disk:part/cell_management_terminal_on");

    public static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, MODEL_OFF, MODEL_STATUS_OFF);
    public static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, MODEL_ON, MODEL_STATUS_ON);
    public static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, MODEL_ON,
            MODEL_STATUS_HAS_CHANNEL);


    /** 正在编辑的元件：右键元件存储槽把它移进来，标记区改的就是这一格的元件。 */
    private final AppEngInternalInventory encodeCell = new AppEngInternalInventory(this, 1) {
        @Override
        protected void onContentsChanged(int slot) {
            markForSave();
        }
    };

    /**
     * 中键要配优先级的那一台（首格键），只在服务端写。
     *
     * <p>优先级界面用的是 AE2 自己的那一张，而它的宿主就是一个 {@link IPriorityHost}：本部件当那个宿主，
     * 读写都转给这一台。为什么不直接把驱动器当宿主：AE2 子菜单的返回键走宿主的
     * {@link #returnToMainMenu}，拿驱动器当宿主的话那个按钮会把玩家带去驱动器自己的界面，而不是回到本终端。</p>
     */
    @Nullable
    private String priorityTarget;

    public CellManagementTerminalPart(IPartItem<?> partItem) {
        super(partItem);
    }

    /**
     * 本终端自己没有升级槽。
     *
     * <p>面板上那条六格升级槽改的是<b>编码槽里那张元件</b>自己的升级（{@link EncodeCellUpgrades}），
     * 卡插的是元件，元件拿走时卡跟着走。如果这里返回一个实打实的库存，{@code MEStorageMenu} 会另外
     * 给终端建出一套 {@code UPGRADE} 槽——那是另一套语义，而且与屏幕画的东西对不上。所以这里交空，
     * 从源头让它一格都不建。</p>
     */
    @Override
    public IUpgradeInventory getUpgrades() {
        return UpgradeInventories.empty();
    }

    @Override
    public AppEngInternalInventory getEncodeCellInventory() {
        return encodeCell;
    }

    @Override
    public MenuType<?> getMenuType(Player p) {
        return CellManagementTermMenu.TYPE;
    }

    @Override
    public IPartModel getStaticModels() {
        return this.selectModel(MODELS_OFF, MODELS_ON, MODELS_HAS_CHANNEL);
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        for (var is : this.encodeCell) {
            if (!is.isEmpty()) drops.add(is);
        }
        // 收完就清：与两个样板部件同一个写法，两步做成幂等的（重复收集时不会掉两份）。
        clearContent();
    }

    @Override
    public void clearContent() {
        super.clearContent();
        this.encodeCell.clear();
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.readFromNBT(data, registries);
        this.encodeCell.readFromNBT(data, "encodeCell", registries);
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.writeToNBT(data, registries);
        this.encodeCell.writeToNBT(data, "encodeCell", registries);
    }

    @Override
    public void markForSave() {
        getHost().markForSave();
    }

    @Override
    public void setPriorityTarget(@Nullable String leaderKey) {
        this.priorityTarget = leaderKey;
    }

    /**
     * 中键要配优先级的那一台；不在网里、或没选中时为 null。
     *
     * <p>这里**不再要求它实现 {@code IPriorityHost}**：AE2 与 EAE 的驱动器实现了那个接口，而 NEO ECO 各级
     * 存储与青春版的 L1 驱动器都没有——它们的优先级挂在各自的存储控制器上。所以按能力取，两条路各走各的。</p>
     */
    private Object priorityTargetOwner() {
        var gridNode = getGridNode();
        if (priorityTarget == null || gridNode == null) {
            return null;
        }
        return CellDriveScanner.findOwner(gridNode.getGrid(), priorityTarget);
    }

    // 下面三个就是 AE2 优先级界面对宿主的全部要求：数值转给那一台，返回键重开本终端。

    @Override
    public int getPriority() {
        Object owner = priorityTargetOwner();
        if (owner instanceof IPriorityHost host) {
            return host.getPriority();
        }
        Integer stored = owner == null ? null : CellDriveScanner.readStoragePriority(owner);
        return stored != null ? stored : 0;
    }

    @Override
    public void setPriority(int newValue) {
        Object owner = priorityTargetOwner();
        if (owner instanceof IPriorityHost host) {
            host.setPriority(newValue);
            return;
        }
        // ECO 本体目前只给了 getter，这一句会返回 false：写不进去时保持原值，不假装成功。
        if (owner != null) {
            CellDriveScanner.setStoragePriority(owner, newValue);
        }
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.open(CellManagementTermMenu.TYPE, player, MenuLocators.forPart(this));
    }

    /** 返回按钮上的图标：本终端部件自己的物品（两侧都拿得到，不依赖只有服务端才有的优先级目标）。 */
    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(getPartItem());
    }
}
