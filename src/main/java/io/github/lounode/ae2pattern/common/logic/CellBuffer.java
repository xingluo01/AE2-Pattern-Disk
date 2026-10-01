package io.github.lounode.ae2pattern.common.logic;

import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.api.storage.cells.StorageCell;
import appeng.blockentity.inventory.AppEngCellInventory;

/**
 * 元件缓冲：机器私有的一组存储元件槽，材料与产出都在这里中转，而不是直接进 ME 网络。
 *
 * <p>把这一层单独拿出来，是因为「元件槽里到底有什么、还能吃多少」是一件事，而机器拿这个答案去做的调度、
 * 合成与回送是另外几件事。原先它们挤在同一个类里，元件相关的每一次读写都要先读懂周围的批处理状态。</p>
 *
 * <p><b>元件表是懒解析的。</b>库存里放的是元件物品，能用的是它的 {@link StorageCell} 视图；扫描一次要读
 * 每个槽的物品与组件，所以只在被标记为脏之后重算，其余时候复用。换元件、清空、读档都要 {@link #markDirty()}
 * ——漏掉一次，机器就会拿着已经离场的元件继续读写。</p>
 *
 * <p><b>元件容量是共享的。</b>总字节与「每类型字节」都是所有元件合起来算，所以按 key 单独模拟插入会高估
 * （每个 key 都以为自己能独占剩余空间）。单 key 的模拟只用来判定「能不能再吃下一批」，真正的整批摄入由
 * 调用方按实际结果回滚。</p>
 */
public final class CellBuffer {

    /** 把缓冲交还给网络的去向。缓冲自己不认识网络，交出这一动作由调用方定义。 */
    public interface Sink {

        /** 交给 ME 网络，返回没被接收的数量。 */
        long push(AEKey key, long amount);

        /** 网络不收、元件也没能收回的部分：记进回送队列，等下一轮再试。 */
        void requeue(AEKey key, long amount);
    }

    private final AppEngCellInventory inventory;
    private final IActionSource actionSource;
    private final StorageCell[] cells;
    private boolean dirty = true;

    /**
     * @param inventory    元件槽所在的库存（同时也是元件内容的持久化载体）
     * @param actionSource 读写元件时的操作来源，用于 AE2 的权限与记账
     */
    public CellBuffer(AppEngCellInventory inventory, IActionSource actionSource) {
        this.inventory = inventory;
        this.actionSource = actionSource;
        this.cells = new StorageCell[inventory.size()];
    }

    /**
     * 声明元件表已过期：换过元件、清空过、或刚从存档读回来。
     *
     * <p>只是标记，不立即重算——调用点常常是「批量清空」这类刚改完还要接着改的地方，立刻重算等于白扫。</p>
     */
    public void markDirty() {
        dirty = true;
    }

    /** 模拟或真实地塞进所有元件槽，返回被接收的数量。 */
    public long insert(AEKey key, long amount, Actionable mode) {
        long remaining = amount;
        for (StorageCell cell : resolved()) {
            if (cell == null || remaining <= 0) {
                continue;
            }
            remaining -= cell.insert(key, remaining, mode, actionSource);
        }
        return amount - remaining;
    }

    /** 模拟或真实地从所有元件槽取出，返回实际取到的数量。 */
    public long extract(AEKey key, long amount, Actionable mode) {
        long remaining = amount;
        for (StorageCell cell : resolved()) {
            if (cell == null || remaining <= 0) {
                continue;
            }
            remaining -= cell.extract(key, remaining, mode, actionSource);
        }
        return amount - remaining;
    }

    /**
     * 缓冲当前持有的全部内容快照。
     *
     * <p>只用来发现候选变体：真正的消费前每一份都会拿实时缓冲再核一次，所以这里不必与后续读到的状态一致。</p>
     */
    public KeyCounter contents() {
        var contents = new KeyCounter();
        for (StorageCell cell : resolved()) {
            if (cell != null) {
                cell.getAvailableStacks(contents);
            }
        }
        return contents;
    }

    /** 是否有任何一个元件槽里还有东西。 */
    public boolean hasContents() {
        for (StorageCell cell : resolved()) {
            if (cell == null) {
                continue;
            }
            var contents = new KeyCounter();
            cell.getAvailableStacks(contents);
            if (!contents.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 是否至少装着可用元件（没元件就没地方放材料，整批业务都不该接）。 */
    public boolean hasAnyCell() {
        for (StorageCell cell : resolved()) {
            if (cell != null) {
                return true;
            }
        }
        return false;
    }

    /** 一个尚未被持有的 key，光「类型条目」就要占掉的容量（按 {@code key} 的类型换算成物品数）。 */
    public long typeEntryUnits(AEKey key) {
        long units = 0L;
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            units += typeUnitsOf(key.getType(), stack);
        }
        return units;
    }

    /**
     * 把元件里还留着的东西交还给网络，一件不留：元件在还有活要干时拒绝抽取，而机器一旦空转就再没别的
     * 东西会来取它了。
     *
     * <p>网络不收的，先放回<b>刚刚交出它的那个元件</b>——那里的空间是确定的，中间不会丢；连元件也放不回的
     * 部分交给 {@link Sink#requeue}，下一轮再试，而不是丢掉。</p>
     *
     * @return 是否交出了任何东西
     */
    public boolean flushTo(Sink sink) {
        boolean any = false;
        for (StorageCell cell : resolved()) {
            if (cell == null) {
                continue;
            }
            var contents = new KeyCounter();
            cell.getAvailableStacks(contents);
            for (var entry : contents) {
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                long taken = cell.extract(entry.getKey(), amount, Actionable.MODULATE, actionSource);
                if (taken <= 0) {
                    continue;
                }
                any = true;
                long leftover = sink.push(entry.getKey(), taken);
                if (leftover > 0) {
                    long back = cell.insert(entry.getKey(), leftover, Actionable.MODULATE, actionSource);
                    sink.requeue(entry.getKey(), leftover - back);
                }
            }
        }
        return any;
    }

    private StorageCell[] resolved() {
        if (dirty) {
            rebuild();
        }
        return cells;
    }

    /**
     * 按库存里的元件物品重建元件表。
     *
     * <p>先把每个槽的句柄拆掉（{@code setHandler(i, null)}）再重新解析：换过元件的槽上留着的旧句柄会
     * 继续往一个已经离场的栈里写。元件内容本身不会因此丢失——{@code AppEngCellInventory} 在槽位被读写时
     * 已把缓存内容写回物品。</p>
     */
    private void rebuild() {
        for (int i = 0; i < cells.length; i++) {
            cells[i] = null;
            inventory.setHandler(i, null);
            var stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            var cell = StorageCells.getCellInventory(stack, null);
            if (cell != null) {
                inventory.setHandler(i, cell);
                cells[i] = cell;
            }
        }
        dirty = false;
    }

    /**
     * 一个尚未插入的 key 在每个元件里要占的类型条目字节，换算成物品数。
     *
     * <p>换算按「每字节多少物品」而非直接比字节，是因为调用方要拿它与物品数相减；元件不上报类型成本的
     * （不是基本元件物品，或类型字节为 0）按 0 计。</p>
     */
    private static long typeUnitsOf(AEKeyType keyType, ItemStack stack) {
        if (!(stack.getItem() instanceof IBasicCellItem basicCell)) {
            return 0L;
        }
        long bytesPerType = basicCell.getBytesPerType(stack);
        if (bytesPerType <= 0) {
            return 0L;
        }
        return bytesPerType * Math.max(1L, keyType.getAmountPerByte());
    }
}
