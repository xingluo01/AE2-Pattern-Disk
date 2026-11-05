package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.blockentity.inventory.AppEngCellInventory;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2pattern.api.PatternDiskTerminalView;
import io.github.lounode.ae2pattern.common.logic.CellBuffer;
import io.github.lounode.ae2pattern.common.logic.SmoothReturnQueue;
import io.github.lounode.ae2pattern.common.util.DropStacks;

/**
 * 批处理装配室的存档与掉落：NBT 读写、拆机掉落、以及清空内容。
 *
 * <p>这一块拥有 {@code priority}（合成优先级）这份存档状态——它被读档写入、被存档读出，又被优先级界面
 * 读写，留在方块实体里只会让协作类反过来摸字段。存档键字面量（{@code cell0..8} / {@code disks} /
 * {@code upgrades} / {@code fastBatchMode} / {@code priority} / {@code pendingOutputs}）是协议，一字不动。</p>
 *
 * <p>四个 {@code super} 调用（{@code saveAdditional} / {@code loadTag} / {@code clearContent} /
 * {@code addAdditionalDrops}）都留在方块实体的覆写入口里：这里只负责它们之外的那半段。
 * {@code addAdditionalDrops} 的 {@code super} 夹在「回送队列」与「元件 / 磁盘 / 升级掉落」之间，
 * 由宿主传进来的 {@code Runnable} 在原位插入。</p>
 */
final class BatchPersistence {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchAssemblerBlockEntity.class);

    private final BatchHost host;
    private final BatchLaneRunner runner;
    private final BatchOutputDelivery delivery;
    private final AppEngCellInventory cellInv;
    private final AppEngInternalInventory diskInv;
    private final IUpgradeInventory upgrades;
    private final PatternDiskTerminalView terminalView;
    private final CellBuffer cellBuffer;
    private final SmoothReturnQueue returns;

    /**
     * 本机的样板优先级（AE2 供应器界面里那个）。
     *
     * <p>两处作用：一是 {@code getPatternPriority()} 把它交给 AE2 的合成计算——同一产物的多条样板里优先
     * 用优先级高的，这是真正生效的那一环；二是 AE2 自带的优先级界面（本机屏上那枚按钮）读写这个值。</p>
     *
     * <p>与「元件存储优先级」（驱动器决定谁先被写入）不是同一件事，两组数字互不影响。</p>
     */
    private int priority;

    BatchPersistence(BatchHost host, BatchLaneRunner runner, BatchOutputDelivery delivery,
            AppEngCellInventory cellInv, AppEngInternalInventory diskInv, IUpgradeInventory upgrades,
            PatternDiskTerminalView terminalView, CellBuffer cellBuffer, SmoothReturnQueue returns) {
        this.host = host;
        this.runner = runner;
        this.delivery = delivery;
        this.cellInv = cellInv;
        this.diskInv = diskInv;
        this.upgrades = upgrades;
        this.terminalView = terminalView;
        this.cellBuffer = cellBuffer;
        this.returns = returns;
    }

    int priority() {
        return priority;
    }

    void setPriority(int newValue) {
        priority = newValue;
    }

    // ---- persistence ---------------------------------------------------------

    /** {@code super.saveAdditional} 之后的那半段：把三个库存、模式、优先级与回送队列写进存档。 */
    void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        for (int i = 0; i < BatchAssemblerBlockEntity.CELL_SLOTS; i++) {
            // getStackInSlot persists the cached cell content into the item before saving.
            tag.put("cell" + i, cellInv.getStackInSlot(i).saveOptional(registries));
        }
        diskInv.writeToNBT(tag, "disks", registries);
        upgrades.writeToNBT(tag, "upgrades", registries);
        tag.putBoolean("fastBatchMode", runner.isFastBatchMode());
        tag.putInt("priority", priority);
        // Persist the smooth-return queue so a save/load cannot lose already-produced outputs.
        returns.writeToNbt(tag, registries, "pendingOutputs");
    }

    /** {@code super.loadTag} 之后的那半段：读回三个库存、模式、优先级与回送队列，并重建配方池。 */
    void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        for (int i = 0; i < BatchAssemblerBlockEntity.CELL_SLOTS; i++) {
            cellInv.setItemDirect(i, ItemStack.parseOptional(registries, tag.getCompound("cell" + i)));
        }
        diskInv.readFromNBT(tag, "disks", registries);
        // readFromNBT writes the slots straight in and raises no change notification, so a terminal view built
        // before this load would keep serving the pre-load contents - an empty list, with nothing left to
        // trigger a rebuild. Invalidating keeps loading symmetric with a slot change.
        terminalView.invalidate(); // invalidate the pattern access terminal view
        upgrades.readFromNBT(tag, "upgrades", registries);
        runner.setFastBatchModeLoaded(tag.getBoolean("fastBatchMode"));
        priority = tag.getInt("priority");
        returns.readFromNbt(tag, registries, "pendingOutputs");
        cellBuffer.markDirty();
        host.rebuildRecipePool();
    }

    /**
     * 拆机掉落：先交还、再走宿主的 {@code super.addAdditionalDrops}、最后把三个库存里的东西掉出来。
     *
     * @param superCall 宿主在原位插入的 {@code super.addAdditionalDrops(level, pos, drops)}
     */
    void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops, Runnable superCall) {
        // Anything still buffered belongs to jobs the CPU already accounts for; hand it back before the
        // cells (and therefore their contents) leave the machine. Queued jobs are not the only case: the
        // smooth-return queue may still hold finished outputs (or be stuck on a full network), and those are
        // just as lost if the block goes away without flushing them.
        if (runner.hasBufferedWork() || !returns.isEmpty()) {
            delivery.cancelAndReturnContents();
        }
        // Whatever neither the network nor a cell would take is still the player's: it leaves with the block
        // as drops, because after this method the block entity and its queues are gone.
        for (var entry : returns.snapshot().entrySet()) {
            long amount = entry.getValue();
            var key = entry.getKey();
            if (amount <= 0) {
                continue;
            }
            if (!(key instanceof AEItemKey itemKey)) {
                // Fluids and other non-item keys have no item stack to be dropped as.
                LOGGER.warn("Batch assembler leftover {} x{} cannot be dropped as an item", key, amount);
                continue;
            }
            // Split into normal stacks, but stop at a ceiling: a machine holding a very large leftover would
            // otherwise spawn one entity per stack - millions of them - and take the server down with it.
            // Anything past the ceiling is carried in oversized stacks instead, which keeps the material
            // rather than dropping it and yields a handful of entries rather than millions.
            long oversized = DropStacks.add(itemKey, amount, drops, BatchTuning.MAX_LEFTOVER_DROP_STACKS);
            if (oversized > 0) {
                LOGGER.warn("Batch assembler leftover {} x{} exceeds {} drop stacks; carrying the rest in "
                                + "oversized stacks so the world is not flooded with entities",
                        key, oversized, BatchTuning.MAX_LEFTOVER_DROP_STACKS);
            }
        }
        returns.clear();
        superCall.run();
        for (int i = 0; i < BatchAssemblerBlockEntity.CELL_SLOTS; i++) {
            var cell = cellInv.getStackInSlot(i);
            if (!cell.isEmpty()) {
                drops.add(cell);
            }
        }
        for (int i = 0; i < BatchAssemblerBlockEntity.DISK_SLOTS; i++) {
            var disk = diskInv.getStackInSlot(i);
            if (!disk.isEmpty()) {
                drops.add(disk);
            }
        }
        for (int i = 0; i < upgrades.size(); i++) {
            var upgrade = upgrades.getStackInSlot(i);
            if (!upgrade.isEmpty()) {
                drops.add(upgrade);
            }
        }
    }

    /** {@code super.clearContent} 之后的那半段：清回送队列、三个库存、通道与元件缓冲的脏标记。 */
    void clearContent() {
        returns.clear();
        for (int i = 0; i < BatchAssemblerBlockEntity.CELL_SLOTS; i++) {
            cellInv.setItemDirect(i, ItemStack.EMPTY);
        }
        diskInv.clear();
        // Clearing the slots notifies per slot, but the view is dropped explicitly as well so
        // correctness does not hinge on that notification surviving future changes.
        terminalView.invalidate();
        upgrades.clear();
        runner.clearLanes();
        cellBuffer.markDirty();
    }
}
