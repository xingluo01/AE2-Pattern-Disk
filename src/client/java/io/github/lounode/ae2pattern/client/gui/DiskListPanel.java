package io.github.lounode.ae2pattern.client.gui;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import appeng.client.Point;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.style.Blitter;

/**
 * 磁盘列表面板：从 ME 网络存储中显示样板磁盘列表，支持 3 槽滚动、点击写入配方、前缀绑定、重命名。
 * <p>
 * GUI 位置：(8,86) 24×66，对应 pattern_modes.png 磁盘覆盖层 (0,144) 24×66。
 */
public class DiskListPanel implements ICompositeWidget {

    // 覆盖层背景
    private static final Blitter DISK_OVERLAY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(0, 144, 24, 66);

    // 滚动轴轨道已烘入 DISK_OVERLAY 底图（x20-22, rel y7..60），无需独立纹理
    private static final Blitter SCROLL_BTN_NARROW_NORMAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(246, 15, 5, 15);
    private static final Blitter SCROLL_BTN_NARROW_PRESSED = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(251, 15, 5, 15);

    /** 磁盘覆盖层起点（GUI 画布坐标）。 */
    private int x, y;

    /** 可见性。 */
    private boolean visible = true;

    /** 迷你搜索文本。 */
    private String searchText = "";

    // ---- 磁盘数据 ------------------------------------------------------------

    /** 磁盘条目模型。 */
    public record DiskEntry(
            ItemStack stack,
            String displayName,
            int used,
            int capacity,
            int count,
            long serial) {
    }

    /** 当前过滤后的磁盘列表（完整列表，包含所有被过滤/搜索的条目）。 */
    private List<DiskEntry> diskEntries = List.of();

    /** 当前滚动偏移（槽位行数，0=顶部）。 */
    private int scrollOffset;

    /** 选中槽位索引（-1=无）。 */
    private int selectedSlot = -1;

    // ---- 回调 ----------------------------------------------------------------

    private Consumer<Integer> onClick;
    private Consumer<Integer> onShiftClick;
    private Consumer<Integer> onMiddleClick;

    public void setOnClick(Consumer<Integer> callback) {
        this.onClick = callback;
    }

    public void setOnShiftClick(Consumer<Integer> callback) {
        this.onShiftClick = callback;
    }

    public void setOnMiddleClick(Consumer<Integer> callback) {
        this.onMiddleClick = callback;
    }

    // ---- 布局常量 ------------------------------------------------------------

    private static final int SLOT_OFFSET_X = 1;
    private static final int SLOT_OFFSET_Y = 7;
    private static final int SLOT_SIZE = 18;
    private static final int VISIBLE_SLOTS = 3;

    private static final int TRACK_OFFSET_X = 20;
    private static final int TRACK_OFFSET_Y = 7;
    private static final int TRACK_W = 3;
    private static final int TRACK_H = 53;

    private static final int THUMB_OFFSET_X = 19;
    private static final int THUMB_OFFSET_Y = 7;
    private static final int THUMB_W = 5;
    private static final int THUMB_H = 15;

    // ---- 构造与基础方法 ------------------------------------------------------

    public DiskListPanel() {
    }

    @Override
    public void setPosition(Point position) {
        this.x = position.getX();
        this.y = position.getY();
    }

    @Override
    public void setSize(int width, int height) {
    }

    @Override
    public Rect2i getBounds() {
        return new Rect2i(x, y, 24, 66);
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    // ---- 数据源（由 Screen 每帧调用） ----------------------------------------

    /** 设置完整的磁盘条目列表（已排序+已搜索过滤+已前缀过滤）。 */
    public void setDiskEntries(List<DiskEntry> entries) {
        this.diskEntries = entries;
        // 滚动偏移无效时复位
        int maxOffset = Math.max(0, entries.size() - VISIBLE_SLOTS);
        if (scrollOffset > maxOffset) {
            scrollOffset = maxOffset;
        }
        if (selectedSlot >= entries.size()) {
            selectedSlot = -1;
        }
    }

    /** 设置搜索文本（由 miniSearchField responder 调用）。 */
    public void setSearchText(String text) {
        this.searchText = text;
    }

    /** 获取当前搜索文本（供 Screen 过滤时使用）。 */
    public String getSearchText() {
        return searchText;
    }

    /** 获取磁盘条目总数。 */
    public int getEntryCount() {
        return diskEntries.size();
    }

    /** 获取当前选中磁盘的索引。 */
    public int getSelectedSlot() {
        return selectedSlot;
    }

    // ---- 渲染 -----------------------------------------------------------------

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        if (!visible) return;

        int absX = bounds.getX() + x;
        int absY = bounds.getY() + y;

        // 绘制磁盘覆盖层背景
        DISK_OVERLAY.dest(absX, absY).blit(guiGraphics);

        // 绘制 3 个可见槽位
        int maxOffset = Math.max(0, diskEntries.size() - VISIBLE_SLOTS);
        int slotAreaStart = absY + SLOT_OFFSET_Y;

        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            int entryIdx = scrollOffset + i;
            int slotX = absX + SLOT_OFFSET_X;
            int slotY = slotAreaStart + i * SLOT_SIZE;

            // 槽位底图已由 DISK_OVERLAY 绘制，无需额外填充
            if (entryIdx < diskEntries.size()) {
                var entry = diskEntries.get(entryIdx);

                // 绘制物品图标
                var mc = Minecraft.getInstance();
                guiGraphics.renderItem(entry.stack(), slotX + 1, slotY + 1);
            }

            // 选中高亮
            if (entryIdx == selectedSlot) {
                guiGraphics.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE,
                        0x80FFFFFF);
            }
        }

        // 滚动轴轨道已烘入 DISK_OVERLAY 底图（x20-22, rel y7..60），无需独立绘制

        // 绘制滚动滑块
        if (maxOffset > 0) {
            float thumbRatio = (float) scrollOffset / maxOffset;
            int thumbCanvasY = y + TRACK_OFFSET_Y + (int) (thumbRatio * (TRACK_H - THUMB_H));
            int thumbCanvasX = x + THUMB_OFFSET_X;

            int thumbAbsX = absX + THUMB_OFFSET_X;
            int thumbAbsY = absY + TRACK_OFFSET_Y + (int) (thumbRatio * (TRACK_H - THUMB_H));

            // 检测鼠标（canvas 坐标）是否在滑块上
            boolean pressed = mouse.getX() >= thumbCanvasX && mouse.getX() < thumbCanvasX + THUMB_W
                    && mouse.getY() >= thumbCanvasY && mouse.getY() < thumbCanvasY + THUMB_H;
            Blitter btn = pressed ? SCROLL_BTN_NARROW_PRESSED : SCROLL_BTN_NARROW_NORMAL;
            btn.dest(thumbAbsX, thumbAbsY).blit(guiGraphics);
        }
    }

    @Override
    public void drawForegroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        if (!visible) return;

        // 检测鼠标是否在某个槽位上，绘制 tooltip
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            int entryIdx = scrollOffset + i;
            if (entryIdx >= diskEntries.size()) break;

            int slotCanvasX = x + SLOT_OFFSET_X;
            int slotCanvasY = y + SLOT_OFFSET_Y + i * SLOT_SIZE;

            if (mouse.getX() >= slotCanvasX && mouse.getX() < slotCanvasX + SLOT_SIZE
                    && mouse.getY() >= slotCanvasY && mouse.getY() < slotCanvasY + SLOT_SIZE) {
                var entry = diskEntries.get(entryIdx);
                var lines = java.util.List.<Component>of(
                        Component.literal(entry.displayName()),
                        Component.translatable("ae2_pattern_disk.tooltip.capacity",
                                entry.used(), entry.capacity()));
                guiGraphics.renderComponentTooltip(Minecraft.getInstance().font,
                        lines, mouse.getX() + bounds.getX(), mouse.getY() + bounds.getY());
            }
        }
    }

    // ---- 鼠标事件 ------------------------------------------------------------

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        if (!visible) return false;

        // 检查是否命中某个槽位
        int slotIdx = hitTestSlot(mousePos);
        if (slotIdx >= 0 && slotIdx < diskEntries.size()) {
            int globalIdx = slotIdx;
            selectedSlot = globalIdx;

            if (button == 0) {
                // 左键
                if (Screen.hasShiftDown()) {
                    if (onShiftClick != null) onShiftClick.accept(globalIdx);
                } else {
                    if (onClick != null) onClick.accept(globalIdx);
                }
                return true;
            } else if (button == 2) {
                // 中键
                if (onMiddleClick != null) onMiddleClick.accept(globalIdx);
                return true;
            }
        }

        // 点击轨道区域（点击滑块上/下空轨翻页，点击滑块本身消费事件但不翻页，未命中区域不处理）
        int trackHit = hitTestTrack(mousePos);
        if (trackHit == -1 || trackHit == 1) {
            int maxOffset = Math.max(0, diskEntries.size() - VISIBLE_SLOTS);
            if (maxOffset > 0) {
                if (trackHit == -1) { // 点击轨道上方 → 上翻一页
                    scrollOffset = Math.max(0, scrollOffset - VISIBLE_SLOTS);
                } else { // 点击轨道下方 → 下翻一页
                    scrollOffset = Math.min(maxOffset, scrollOffset + VISIBLE_SLOTS);
                }
            }
            return true;
        }
        if (trackHit == 0) { // 点击滑块本体：消费事件但不翻页
            return true;
        }

        return false;
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        if (!visible) return false;
        if (!getBounds().contains(mousePos.getX(), mousePos.getY())) return false;

        int maxOffset = Math.max(0, diskEntries.size() - VISIBLE_SLOTS);
        if (maxOffset > 0) {
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset + (delta > 0 ? -1 : 1)));
            return true;
        }
        return false;
    }

    /**
     * 返回可见槽位中命中的全局索引，或 -1。
     * mousePos 为 GUI-canvas 坐标（相对对话框原点）。
     */
    private int hitTestSlot(Point mousePos) {
        int relX = mousePos.getX() - x - SLOT_OFFSET_X;
        int relY = mousePos.getY() - y - SLOT_OFFSET_Y;

        if (relX < 0 || relX >= SLOT_SIZE) return -1;
        if (relY < 0) return -1;

        int slotRow = relY / SLOT_SIZE;
        if (slotRow < 0 || slotRow >= VISIBLE_SLOTS) return -1;

        int globalIdx = scrollOffset + slotRow;
        if (globalIdx >= diskEntries.size()) return -1;
        return globalIdx;
    }

    /**
     * 返回轨道点击区域：-1=上方，0=滑块上，1=下方，-2=未命中。
     * mousePos 为 GUI-canvas 坐标。
     */
    private int hitTestTrack(Point mousePos) {
        int relX = mousePos.getX() - x - TRACK_OFFSET_X;
        int relY = mousePos.getY() - y - TRACK_OFFSET_Y;

        if (relX < 0 || relX >= TRACK_W) return -2;
        if (relY < 0 || relY >= TRACK_H) return -2;

        // 计算滑块当前范围
        int maxOffset = Math.max(0, diskEntries.size() - VISIBLE_SLOTS);
        if (maxOffset <= 0) return -2;

        float thumbRatio = (float) scrollOffset / maxOffset;
        int thumbStart = (int) (thumbRatio * (TRACK_H - THUMB_H));
        int thumbEnd = thumbStart + THUMB_H;

        if (relY < thumbStart) return -1;
        if (relY >= thumbEnd) return 1;
        return 0;
    }

    /** 获取第 idx 个可见槽位的屏幕坐标（供外部引用）。 */
    public Point getSlotScreenPos(int idx) {
        if (idx < 0 || idx >= VISIBLE_SLOTS) {
            return new Point(0, 0);
        }
        return new Point(x + SLOT_OFFSET_X, y + SLOT_OFFSET_Y + idx * SLOT_SIZE);
    }
}