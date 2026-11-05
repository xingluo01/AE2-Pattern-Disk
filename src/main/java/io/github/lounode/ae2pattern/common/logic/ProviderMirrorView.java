package io.github.lounode.ae2pattern.common.logic;

import java.util.function.Supplier;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.inventories.InternalInventory;

import io.github.lounode.ae2pattern.common.pattern.PatternDiskTier;

/**
 * 镜像槽数的推导：构造期用，磁盘栏那时还没建好，只能按「槽数 × 最大档磁盘容量」估。
 */
final class ProviderMirrorSupport {

    private ProviderMirrorSupport() {
    }

    /**
     * How many slots the mirror needs: every disk slot the machine has, times the largest disk tier.
     *
     * <p>Derived rather than fixed, because a fixed number has to be right for every machine and every
     * tier at once. The one that used to be here - 1024 - was exactly one full disk, so a provider
     * holding nine of them exposed only the first and silently dropped the rest.</p>
     *
     * <p>The slot count is a parameter rather than a read of the disk inventory: this logic is built
     * inside the host's own {@code super()} constructor, before that inventory field exists.</p>
     *
     * <p>The upper bound comes from this mod's own tiers, which is what these machines' disks can hold -
     * every write path in the mod clamps to a tier capacity. A component edited out of band to claim more
     * than the largest tier would not be covered; covering that would mean deriving the bound from the
     * disks at runtime, and this constructor runs before the disks can be read.</p>
     *
     * <p>Truncation is unreachable on the normal path: one disk holds at most the largest tier's capacity,
     * so the sum never exceeds this bound. If a future tier or an out-of-band write path breaks that,
     * {@code refreshPatternsFromDisks} degrades to a silent truncation - no log, no status report.</p>
     */
    static int mirrorCapacity(int diskSlots) {
        int perDisk = 0;
        for (PatternDiskTier tier : PatternDiskTier.values()) {
            perDisk = Math.max(perDisk, tier.capacity());
        }
        return diskSlots * perDisk;
    }
}

/**
 * 只读的镜像栏：读转发给原件（返回副本，避免调用方改到共享的 ItemStack），写与取一律拒绝。
 *
 * <p>取出也要拒绝：镜像里的每张样板都还在某张磁盘上，放行等于免费复制。AE2 内部只用私有字段
 * {@code patternInventory}，不经过 {@code getPatternInv()}，所以拒绝不影响解码与投递。</p>
 *
 * <p>日志带 5 秒冷却：AE2 的 {@code addItems} 在大库存上逐槽试写，一次上传会问上百次，逐次记一行就是刷屏。</p>
 *
 * <p>只持有镜像原件与一个「取宿主方块实体」的回调，不持有整个 logic 对象。</p>
 */
final class ProviderMirrorView implements InternalInventory {

    /** 沿用宿主的 logger 名，日志分类不变。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternDiskProviderLogic.class);

    private static final long REFUSAL_LOG_COOLDOWN_NANOS = 5_000_000_000L;

    private final InternalInventory mirror;
    private final Supplier<BlockEntity> blockEntity;
    private long lastRefusalLogNanos;

    ProviderMirrorView(InternalInventory mirror, Supplier<BlockEntity> blockEntity) {
        this.mirror = mirror;
        this.blockEntity = blockEntity;
    }

    @Override
    public int size() {
        return mirror.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= mirror.size()) {
            return ItemStack.EMPTY;
        }
        return mirror.getStackInSlot(slot).copy();
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        refuse("setItemDirect");
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 模拟不是写尝试：上传器会满仓逐槽探一遍，那一步不该占日志。
        if (!simulate) {
            refuse("insertItem");
        }
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (simulate || amount <= 0 || getStackInSlot(slot).isEmpty()) {
            return ItemStack.EMPTY;
        }
        refuse("extractItem");
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public int getSlotLimit(int slot) {
        // 样板按单张存；写又全拒，这个数只影响显示。
        return 1;
    }

    @Override
    public void clear() {
        // 逐槽走 setItemDirect 只会把冷却日志刷满，空终态也拿不到：原件只有本类能清。
        refuse("clear");
    }

    private void refuse(String action) {
        long now = System.nanoTime();
        if (now - lastRefusalLogNanos < REFUSAL_LOG_COOLDOWN_NANOS) {
            return;
        }
        lastRefusalLogNanos = now;
        LOGGER.warn("样板磁盘供应器在 {} 拒绝了外部对镜像样板栏的 {}：那栏只是磁盘的副本，写进去的样板会变成"
                + "既不在磁盘上、也不进掉落的幻影样板；写样板请走 getTerminalPatternInventory()",
                where(), action);
    }

    private String where() {
        var blockEntity = this.blockEntity.get();
        return blockEntity == null ? "未知位置" : String.valueOf(blockEntity.getBlockPos());
    }
}
