package io.github.lounode.ae2pattern.common.logic;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;

/**
 * 样板栏的只读视图：读转发给原件并复制，写与取一律拒绝。
 *
 * <p>镜像设备的样板栏只是磁盘内容的副本。外部上传器若直接写进来，样板会进合成清单却没有任何磁盘背书，
 * 磁盘一变就被重建抹掉，拆机也不掉落——即「网络看得见、磁盘没有」的幻影样板。取走则等于免费复制。
 * 写样板必须走 {@code getTerminalPatternInventory()}。</p>
 */
public final class ReadOnlyPatternInventory implements InternalInventory {

    private final InternalInventory delegate;

    public ReadOnlyPatternInventory(InternalInventory delegate) {
        this.delegate = delegate;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= delegate.size()) {
            return ItemStack.EMPTY;
        }
        return delegate.getStackInSlot(slot).copy();
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        // 拒绝
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    @Override
    public void clear() {
        // 拒绝
    }
}