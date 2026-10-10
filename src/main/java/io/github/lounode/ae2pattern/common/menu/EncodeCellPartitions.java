package io.github.lounode.ae2pattern.common.menu;


import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.util.ConfigInventory;
import appeng.util.ConfigMenuInventory;

/**
 * 「元件标记区」的库存：不跟着终端走，跟着<b>编码槽里那张元件的分区</b>走。
 *
 * <p>面板上那一片标记槽改的是元件的分区配置，所以槽位不能挂在部件自己的某个库存上（那样只有部件里的
 * 副本在动，元件分区没人碰，屏上永远是空的）。这里每次读写都现问编码槽里当前那张元件要它的
 * {@code getConfigInventory}，再包成 AE2 给菜单用的 {@link ConfigMenuInventory}（ItemStack 视角）——
 * 这正是 AE2 元件工作台的面板与自己那张元件的连线方式。</p>
 *
 * <p>格数上限为 {@link #SIZE}（63 = 7 列 &times; 9 行）；元件实际能标记多少格由它自己的分区库存说了算
 * （见 {@link #slotCount()}）——部分元件给的上限比 63 小，超出的格读回空栈、写入被拒，屏那边也不画。
 * 编码槽为空或元件不支持分区时整片格位不可用（槽底由图层的显隐逻辑画成暗色）。</p>
 *
 * <p><b>缓存只按引用比较</b>：分区必须写在编码槽里那张真实的元件栈上，所以缓存的判据是「还是不是同一个栈
 * 对象」（{@code !=}），不是内容相等；元件被换掉时引用变，缓存跟着重建。分区内容变化不会换对象，
 * 因此也不会误判。</p>
 */
public class EncodeCellPartitions implements InternalInventory {

    /** 分区格数上限：与贴图上标记区的容量一致（7 列 &times; 9 行）。 */
    public static final int SIZE = 63;

    private final ICellManagementHost host;

    /** 上次解析时那张元件栈（只比引用，不拷内容）、它对应的分区库存，以及分区在菜单侧的样子。 */
    private ItemStack cachedCell;
    private ConfigInventory cachedConfig;
    private ConfigMenuInventory cached;

    public EncodeCellPartitions(ICellManagementHost host) {
        this.host = host;
    }

    /**
     * 缓存的内容还跟元件组件一致吗；不一致就丢掉重建。
     *
     * <p>缓存是「每个菜单实例一份」：同一台终端被两个玩家同时打开时，两份缓存彼此看不见，一方改完元件后，
     * 另一方手上那份就是旧的——它一旦通过窗口写下任何一格，就会把整份旧内容盖回去（把对方刚做的标记静默
     * 回退）。所以每拍比一次内容：分区最多 63 项，一次读组件的代价可以忽略。</p>
     */
    public void refreshIfStale() {
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (this.cachedConfig == null || this.cachedCell != cell
                || !(cell.getItem() instanceof ICellWorkbenchItem workbenchItem)) {
            return;
        }
        if (!workbenchItem.getConfigInventory(cell).toList().equals(this.cachedConfig.toList())) {
            this.cachedConfig = null;
            this.cached = null;
            this.cachedCell = null;
        }
    }

    private ConfigMenuInventory current() {
        return currentConfig() == null ? null : this.cached;
    }

    private ConfigInventory currentConfig() {
        ItemStack cell = host.getEncodeCellInventory().getStackInSlot(0);
        if (cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem workbenchItem)) {
            this.cachedCell = null;
            this.cachedConfig = null;
            this.cached = null;
            return null;
        }
        if (this.cached == null || this.cachedCell != cell) {
            this.cachedCell = cell;
            this.cachedConfig = workbenchItem.getConfigInventory(cell);
            this.cached = new ConfigMenuInventory(this.cachedConfig);
        }
        return this.cachedConfig;
    }

    /**
     * 这个标记窗口当前用的那个分区库存——就是 {@link #currentConfig()} 缓存的那一个。
     *
     * <p>菜单侧的批量写入（分区存储 / 清除 / 标记收藏）<b>必须</b>走这里：分区库存是「创建时从元件读进
     * 内存、改动时整份写回」的，另开一个 {@code getConfigInventory} 去写，窗口手上那份就停在旧内容上，
     * 之后通过窗口做任何改动都会把旧内容整份盖回去——表现为「刚加上的标记去不掉」，直到元件被取出来再放
     * 回去（栈对象变了，缓存重建）才恢复。</p>
     */
    @Nullable
    public ConfigInventory config() {
        return currentConfig();
    }

    @Override
    public int size() {
        return SIZE;
    }

    /**
     * 这张元件<b>实际</b>能标记多少格：它自己的分区库存格数，上限 {@link #SIZE}；没有元件时 0。
     *
     * <p>不能假定 63：分区库存是元件自己建的，元件可以选择比 63 少——超出的格写不进去、读回也是空，
     * 所以屏那边要按这个数决定画几格、能滚多远。</p>
     */
    public int slotCount() {
        ConfigMenuInventory partitions = current();
        return partitions == null ? 0 : Math.min(partitions.size(), SIZE);
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        ConfigMenuInventory partitions = current();
        if (partitions == null || slot < 0 || slot >= partitions.size()) {
            return ItemStack.EMPTY;
        }
        return partitions.getStackInSlot(slot);
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        // 写之前先复核一次：分区库是「建时读进内存、改动时整份写回」的，而这份缓存是每个菜单实例一份——
        // 同一台终端被两个玩家开着时，另一份刚改过而我们没看见，这一写就会把对方的改动整份盖回去。
        refreshIfStale();
        ConfigMenuInventory partitions = current();
        if (partitions == null || slot < 0 || slot >= partitions.size()) {
            return;
        }
        partitions.setItemDirect(slot, stack);
        // 分区写在元件栈的组件里：元件栈本身得被通知变了才会落盘，也和元件工作台一样要标一次保存。
        host.getEncodeCellInventory().sendChangeNotification(0);
        host.markForSave();
    }

    /** 只转发准入判定：能不能放进这一格由元件自己的分区库存说了算。 */
    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        ConfigMenuInventory partitions = current();
        if (partitions == null || slot < 0 || slot >= partitions.size()) {
            return false;
        }
        return partitions.isItemValid(slot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        ConfigMenuInventory partitions = current();
        if (partitions == null || slot < 0 || slot >= partitions.size()) {
            return 0;
        }
        return partitions.getSlotLimit(slot);
    }
}
