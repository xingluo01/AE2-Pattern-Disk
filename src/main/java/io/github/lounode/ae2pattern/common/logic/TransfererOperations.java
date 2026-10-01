package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.core.definitions.AEItems;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.api.PatternDiskContents;
import io.github.lounode.ae2pattern.api.PatternClassifier;
import io.github.lounode.ae2pattern.common.block.entity.PatternTransfererBlockEntity;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.TransferMode;

/**
 * 转存器的四种搬运操作：磁盘→磁盘（移动）、磁盘→磁盘（复制）、编码样板→磁盘、以及输入槽反查。
 *
 * <p>它们共用的只有「库存布局」一件事——输入槽、应用槽、输出槽的位置都由
 * {@link PatternTransfererBlockEntity} 的静态索引给出，本类不自己数索引。其余三件各说各的：移动要回写
 * 源盘（未安置的留着）、复制绝不碰源盘、提取要往输出槽放一张空样板。把它们从方块实体里拿出来，是因为
 * 这些是纯粹的物品搬运，不依赖网络、能量或 tick 状态，只有「库存」与「世界」两个输入。</p>
 */
public final class TransfererOperations {

    /** 每秒的 tick 数；搬运速率按它折算。 */
    private static final double TICKS_PER_SECOND = 20.0;

    private final AppEngInternalInventory inventory;
    private final IUpgradeInventory upgrades;
    private final IActionSource actionSource;
    private final Supplier<IGrid> grid;
    private final Supplier<Level> level;

    /** 上一个搬运周期留下的零头；每 tick 按速率累加。 */
    private double accumulator = 0;

    public TransfererOperations(AppEngInternalInventory inventory, IUpgradeInventory upgrades,
            IActionSource actionSource, Supplier<IGrid> grid, Supplier<Level> level) {
        this.inventory = inventory;
        this.upgrades = upgrades;
        this.actionSource = actionSource;
        this.grid = grid;
        this.level = level;
    }

    /**
     * 跑一个搬运周期：按速率卡折算本次能搬多少个，先把空样板送回网络，再按模式处理输入槽。
     *
     * @return 本次是否有改动（调用方据此决定要不要存档）
     */
    public boolean tick(TransferMode mode) {
        int speedCards = installedSpeedCards();
        double perTick = Math.pow(2, 2 * speedCards + 1) / TICKS_PER_SECOND;

        accumulator += perTick;
        int toProcess = (int) accumulator;
        if (toProcess <= 0) {
            return false;
        }
        accumulator -= toProcess;

        boolean changed = returnBlanksToNetwork();
        changed |= process(mode, toProcess, level.get());
        return changed;
    }

    /** 把搬运余量写进存档（与库存同一份标签）。 */
    public void writeToNbt(CompoundTag tag, String key) {
        tag.putDouble(key, accumulator);
    }

    /** 读回搬运余量。 */
    public void readFromNbt(CompoundTag tag, String key) {
        accumulator = tag.getDouble(key);
    }

    private int installedSpeedCards() {
        return upgrades.getInstalledUpgrades(AEItems.SPEED_CARD);
    }

    /**
     * Moves blank patterns from the output slot back into the connected ME network.
     */
    private boolean returnBlanksToNetwork() {        ItemStack output = inventory.getStackInSlot(PatternTransfererBlockEntity.OUTPUT_SLOT);
        if (output.isEmpty() || !PatternClassifier.isBlankPattern(output)) {
            return false;
        }
        IGrid connected = grid.get();
        if (connected == null) {
            return false;
        }
        var storage = connected.getStorageService();
        if (storage == null) {
            return false;
        }
        var blankKey = AEItemKey.of(output);
        long toSend = output.getCount();
        long inserted = storage.getInventory().insert(blankKey, toSend, Actionable.MODULATE, actionSource);
        if (inserted > 0) {
            output.shrink((int) inserted);
            if (output.isEmpty()) {
                inventory.setItemDirect(PatternTransfererBlockEntity.OUTPUT_SLOT, ItemStack.EMPTY);
            }
            return true;
        }
        return false;
    }

    /**
     * 按模式处理六个输入槽，本次最多搬运 {@code limit} 个配方。
     *
     * @param mode  存储（移动）或复写（复制）
     * @param limit 本次最多搬运的配方数
     * @param level 归属世界；未加载时为 {@code null}，此时什么也做不了
     * @return 本次是否有任何改动
     */
    private boolean process(TransferMode mode, int limit, @Nullable Level level) {
        if (level == null) {
            return false;
        }
        int processed = 0;
        for (int i = 0; i < PatternTransfererBlockEntity.INPUT_SLOT_COUNT && processed < limit; i++) {
            ItemStack input = inventory.getStackInSlot(PatternTransfererBlockEntity.inputSlot(i));
            if (input.isEmpty()) {
                continue;
            }

            int transferred = 0;
            if (input.getItem() instanceof PatternDiskItem disk) {
                if (mode == TransferMode.STORE) {
                    // 样板存储：磁盘 → 磁盘（移动：源盘配方转出，目标盘接收）
                    transferred = storeDiskInto(input, disk, limit - processed, level);
                } else if (mode == TransferMode.COPY) {
                    // 样板复写：磁盘 → 磁盘（复制：源盘内容保留）
                    transferred = copyDiskInto(input, disk, limit - processed, level);
                }
            } else if (PatternClassifier.isEncodedPatternStack(input)) {
                if (mode == TransferMode.STORE) {
                    // 样板存储：编码样板 → 磁盘
                    transferred = extractPatternInto(input, level) ? 1 : 0;
                }
            }
            processed += transferred;
        }
        return processed > 0;
    }

    /**
     * 移动语义：将源磁盘中的配方依次移入目标应用磁盘，源盘已移出的配方被移除。
     * 每个配方优先写入第一个满足条件的目标盘：类型匹配（未定型或同类型）、未满、
     * 且不含产生相同主输出的配方（同输出互斥）。
     * 无法安置的配方（类型不匹配/全满/互斥）保留在源盘中，不丢弃。
     *
     * @param limit 本次最多移动的配方数量
     * @return 本次实际移动的配方数
     */
    private int storeDiskInto(ItemStack source, PatternDiskItem sourceDisk, int limit, Level level) {
        var sourceContents = sourceDisk.contents(source);
        if (sourceContents.isEmpty() || limit <= 0) {
            return 0;
        }
        var sourceType = sourceContents.type();
        // 损坏数据防御：有配方却未定型（type=null）的源盘无法安全移动，跳过
        if (sourceType == null) {
            return 0;
        }

        int moved = 0;
        var remaining = new ArrayList<ItemStack>();
        for (var candidate : sourceContents.patterns()) {
            boolean placed = false;
            if (moved < limit) {
                for (int slot = 0; slot < PatternTransfererBlockEntity.APPLICATION_SLOT_COUNT; slot++) {
                    ItemStack appDisk = inventory.getStackInSlot(PatternTransfererBlockEntity.applicationSlot(slot));
                    if (appDisk.isEmpty() || !(appDisk.getItem() instanceof PatternDiskItem app)) {
                        continue;
                    }
                    // 容量/锁定类型/同主产物互斥均由 PatternDiskItem.tryInsert 统一把关
                    if (!app.tryInsert(appDisk, candidate, level)) {
                        continue;
                    }
                    moved++;
                    placed = true;
                    break; // 该配方已移出，处理下一个
                }
            }
            if (!placed) {
                remaining.add(candidate); // 本次未安置：保留在源盘
            }
        }

        if (moved > 0) {
            if (remaining.isEmpty()) {
                source.remove(AEPatternRegistries.DISK_CONTENTS.get());
            } else {
                source.set(AEPatternRegistries.DISK_CONTENTS.get(),
                        new PatternDiskContents(sourceType, sourceContents.capacity(), remaining));
            }
        }
        return moved;
    }

    /**
     * 复制语义：将源磁盘中的每个配方复制到目标应用磁盘，源磁盘内容保持不变。
     * 每个配方优先写入第一个满足条件的目标盘：类型匹配（未定型或同类型）、未满、
     * 且不含产生相同主输出的配方（同输出互斥，天然避免重复复制）。
     *
     * @param limit 本次最多复制的配方数量
     * @return 本次实际复制的配方数
     */
    private int copyDiskInto(ItemStack source, PatternDiskItem sourceDisk, int limit, Level level) {
        var sourceContents = sourceDisk.contents(source);
        if (sourceContents.isEmpty() || limit <= 0) {
            return 0;
        }

        int copied = 0;
        // 只读遍历源盘配方，绝不修改源盘（复制而非移动）
        for (var candidate : sourceContents.patterns()) {
            if (copied >= limit) {
                break;
            }
            for (int slot = 0; slot < PatternTransfererBlockEntity.APPLICATION_SLOT_COUNT; slot++) {
                ItemStack appDisk = inventory.getStackInSlot(PatternTransfererBlockEntity.applicationSlot(slot));
                if (appDisk.isEmpty() || !(appDisk.getItem() instanceof PatternDiskItem app)) {
                    continue;
                }
                // 容量/锁定类型/同主产物互斥均由 PatternDiskItem.tryInsert 统一把关
                if (!app.tryInsert(appDisk, candidate, level)) {
                    continue;
                }
                copied++;
                break; // 该配方已复制，处理下一个配方
            }
        }
        return copied;
    }

    /**
     * Extracts an encoded pattern from an input slot into an application disk, producing a blank pattern
     * into the output slot.
     */
    private boolean extractPatternInto(ItemStack input, Level level) {
        String type = PatternClassifier.typeOf(input, level);
        if (type == null) {
            return false;
        }

        int targetSlot = -1;
        PatternDiskItem targetDisk = null;
        for (int slot = 0; slot < PatternTransfererBlockEntity.APPLICATION_SLOT_COUNT; slot++) {
            ItemStack appDisk = inventory.getStackInSlot(PatternTransfererBlockEntity.applicationSlot(slot));
            if (appDisk.isEmpty() || !(appDisk.getItem() instanceof PatternDiskItem app)) {
                continue;
            }
            var contents = app.contents(appDisk);
            if (contents.isFull()) {
                continue;
            }
            if (!contents.isTyped() || contents.type().equals(type)) {
                targetSlot = slot;
                targetDisk = app;
                break;
            }
        }
        if (targetSlot < 0) {
            return false;
        }

        // Room, locked type and same-result exclusivity are enforced by PatternDiskItem.tryInsert.
        ItemStack appDisk = inventory.getStackInSlot(PatternTransfererBlockEntity.applicationSlot(targetSlot));
        if (!targetDisk.tryInsert(appDisk, input, level)) {
            return false;
        }
        inventory.setItemDirect(inputSlotOf(input), ItemStack.EMPTY);

        // Produce a blank pattern into the output slot.
        ItemStack blank = AEPatternRegistries.blankPattern();
        ItemStack output = inventory.getStackInSlot(PatternTransfererBlockEntity.OUTPUT_SLOT);
        if (output.isEmpty()) {
            inventory.setItemDirect(PatternTransfererBlockEntity.OUTPUT_SLOT, blank);
        } else if (output.is(blank.getItem())) {
            output.grow(1);
        }
        return true;
    }

    /** 反查这个物品对象所在的输入槽；理论上必然命中，兜底给第一个输入槽。 */
    private int inputSlotOf(ItemStack input) {
        for (int i = 0; i < PatternTransfererBlockEntity.INPUT_SLOT_COUNT; i++) {
            int slot = PatternTransfererBlockEntity.inputSlot(i);
            if (inventory.getStackInSlot(slot) == input) {
                return slot;
            }
        }
        return PatternTransfererBlockEntity.INPUT_START;
    }
}
