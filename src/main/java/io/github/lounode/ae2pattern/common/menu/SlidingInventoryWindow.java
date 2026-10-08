package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;

/**
 * 把一段定长窗口架在一份更长的后备库存上，窗口起点可挪。
 *
 * <p>用在元件标记区：后备 63 格（7 列 &times; 9 行），屏幕只摆 21 个槽位（7 列 &times; 3 行），滚动时挪
 * {@link #setOffset(int)}，槽位对象本身不动。反过来做——滚动时逐个改 {@code Slot.x/y}——等于绕开 AE2
 * 的样式定位（语义→坐标在 {@code ScreenStyle} 里解析一次，`positionSlots` 只在构造期跑），每帧都得
 * 手工重定位，还容易与 {@code IOptionalSlot} 的背景绘制错位。</p>
 *
 * <p>窗口不持有物品快照，每次读写都落到后备库存上，所以服务端与客户端看到的始终是同一份数据；
 * 越界一律当作空格/丢弃，不抛异常——滚到边缘时槽位仍会被画一次，那里读到的就该是空。</p>
 */
public final class SlidingInventoryWindow implements InternalInventory {

    private final InternalInventory backing;
    private final int windowSize;
    private int offset;

    public SlidingInventoryWindow(InternalInventory backing, int windowSize) {
        this.backing = backing;
        this.windowSize = windowSize;
    }

    /** 把窗口起点挪到 {@code offset}，自动夹在 {@code [0, backing.size() - windowSize]} 内。 */
    public void setOffset(int offset) {
        this.offset = Math.clamp(offset, 0, Math.max(0, backing.size() - windowSize));
    }

    public int getOffset() {
        return offset;
    }

    /** 整份后备库存的长度（屏幕用它算滚动范围）。 */
    public int getBackingSize() {
        return backing.size();
    }

    public int getWindowSize() {
        return windowSize;
    }

    @Override
    public int size() {
        return windowSize;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        int index = toBackingIndex(slot);
        return index < 0 ? ItemStack.EMPTY : backing.getStackInSlot(index);
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        int index = toBackingIndex(slot);
        if (index >= 0) {
            backing.setItemDirect(index, stack);
        }
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        int index = toBackingIndex(slot);
        return index >= 0 && backing.isItemValid(index, stack);
    }

    @Override
    public void sendChangeNotification(int slot) {
        int index = toBackingIndex(slot);
        if (index >= 0) {
            backing.sendChangeNotification(index);
        }
    }

    /** @return 后备库存里对应的下标；窗口外或后备变更后越界时返回 -1 */
    private int toBackingIndex(int slot) {
        if (slot < 0 || slot >= windowSize) {
            return -1;
        }
        int index = offset + slot;
        return index < backing.size() ? index : -1;
    }
}
