package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import appeng.client.gui.me.common.RepoSlot;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;

import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.network.DiskHostListPayload;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * The pattern disk management terminal's screen: a table of every disk on the grid, grouped by the machine
 * holding it, with the encoding terminal's encoding area underneath.
 *
 * <p>Layout comes from {@code Sprite-0001} (the texture this screen slices): a title strip and 17 columns of
 * 18px cells on top, then the player inventory on the left and the encoding area - the very same widgets the
 * encoding terminal builds in its constructor - on the right. The panel height (268px) is set by the style JSON's
 * {@code terminalStyle}; the current painted art ends earlier, and the extra strip at the bottom is plain background.</p>
 *
 * <p><b>Rows.</b> One row per machine (a header carrying its icon, name and disk count, plus a show/hide
 * toggle), then one row per disk: cell 0 is the disk itself, the remaining 16 cells are the patterns stored on
 * it. Disk-level clicks go through the inherited {@code onDisk*Click} handlers, so selecting, marking and
 * renaming a disk behave exactly as in the encoding terminal.</p>
 *
 * <p><b>Search.</b> The mini search box is inherited unchanged and still filters the inherited disk list; the
 * table is built from that filtered list, which keeps "what is on screen" and "what the encode button writes
 * to" the same set.</p>
 *
 * <p><b>Contents.</b> Patterns shown in the cells come from {@link PatternDiskManagementTermMenu}'s cache,
 * filled by the server in response to {@link VisibleDisksPayload}. Until a disk's contents arrive its row
 * shows just the disk; the request is re-sent whenever the visible set changes.</p>
 */
public class PatternDiskManagementTermScreen extends PatternDiskEncodingTermScreen {

    /**
     * 本屏幕的菜单是管理终端自己的那个类型，但父屏把菜单类型写死在签名里（把它泛型化会牵连编码区面板、数量子屏与
     * 两个集成适配类），所以在这里用协变返回把类型收窄：本类内部直接调管理菜单的方法，其余文件一动不动。
     */
    @Override
    public PatternDiskManagementTermMenu getMenu() {
        return (PatternDiskManagementTermMenu) super.getMenu();
    }

    private static final ResourceLocation TEXTURE = ResourceLocation
            .parse("ae2_pattern_disk:textures/guis/pattern_disk_management_terminal.png");

    // 与贴图切片对齐的几何（Sprite-0001：0,0,339,133 是表格区；17 格 × 18px）
    private static final int PANEL_WIDTH = 340;
    /** 面板高，与 style JSON 的 terminalStyle 算出的 imageHeight 一致（17+18+18+215）。 */
    private static final int PANEL_HEIGHT = 268;
    private static final int LIST_X = 1;
    private static final int LIST_Y = 1;
    private static final int LIST_WIDTH = 339;
    private static final int TITLE_HEIGHT = 17;
    private static final int ROW_HEIGHT = 18;
    private static final int COLUMNS = 17;
    private static final int VISIBLE_ROWS = 6;
    /** 视口外多要一行内容：滚一格时不至于先闪一帧空行。 */
    private static final int CONTENT_MARGIN_ROWS = 1;

    /**
     * 可见集合没变也重报一次的间隔（tick）。
     *
     * <p>没有这个“心跳”，盘内内容一变就会过期：客户端只会在自己那份「已请求」集合变化时上报，而往里写入一张
     * 样板并不改变集合。服务端每张盘比指纹、没变就不发包，所以这条心跳的常态开销只是一次空请求。</p>
     */
    private static final int CONTENT_REFRESH_INTERVAL_TICKS = 20;

    private static final int TOGGLE_SIZE = 9;

    /**
     * 磁盘槽的浅绿色底色（纯上色，不走纹理资源）。
     *
     * <p>与盘内样板的格子在视觉上分开：一眼能看出哪一格是磁盘本身。带 alpha，底下的槽位描边与棋盘格仍透着。</p>
     */
    private static final int DISK_SLOT_TINT = 0x66B9F6CA;

    private sealed interface Row permits HostRow, DiskRow {
    }

    /** 组头行：一台宿主机器。{@code diskCount} 是当前过滤/显示口径下的磁盘数。 */
    private record HostRow(String key, String name, ItemStack icon, int diskCount) implements Row {
    }

    /** 磁盘行：首格是磁盘本身，其余 16 格是它里面的样板。 */
    private record DiskRow(String hostKey, long serial, ItemStack disk) implements Row {
    }

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> hiddenHosts = new HashSet<>();
    private final LongSet requestedContents = new LongOpenHashSet();

    private int scrollOffset;
    private int contentRefreshCooldown = CONTENT_REFRESH_INTERVAL_TICKS;

    public PatternDiskManagementTermScreen(PatternDiskManagementTermMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Override
    protected boolean usesDiskListPanel() {
        return false;
    }

    @Override
    public void init() {
        super.init();
        // MEStorageScreen.init() 给终端网格加了 RepoSlot；我们用自定义表格，不需要它们。
        this.menu.slots.removeIf(slot -> slot instanceof RepoSlot);
    }

    // ---- 行模型 ----

    /**
     * 依据「父类过滤后的磁盘清单」与「服务端的分组清单」重塑行模型。
     *
     * <p>磁盘来自父类那份列表，所以搜索框筛掉谁，表里就没有谁——编码按钮的写盘目标也是同一条口径。
     * 分组只提供名字、图标与分组键；一台机器被隐藏时它整组都不进表。</p>
     */
    private void rebuildRows() {
        var menu = getMenu();

        var serialToGroup = new HashMap<Long, DiskHostListPayload.HostGroup>();
        for (var group : menu.getHostList()) {
            for (var disk : group.disks()) {
                serialToGroup.put(disk.serial(), group);
            }
        }

        var diskCounts = new HashMap<String, Integer>();
        var rebuilt = new ArrayList<Row>();
        String currentHost = null;
        for (int i = 0; super.diskEntryAt(i) != null; i++) {
            var entry = super.diskEntryAt(i);
            var group = serialToGroup.get(entry.serial());
            var key = group == null ? "" : group.key();
            if (hiddenHosts.contains(key)) {
                continue;
            }

            diskCounts.merge(key, 1, Integer::sum);
            if (!key.equals(currentHost)) {
                currentHost = key;
                rebuilt.add(new HostRow(key,
                        group == null ? Component.translatable("gui.ae2_pattern_disk.management_terminal.unknown_host").getString() : group.name(),
                        group == null ? ItemStack.EMPTY : group.icon(),
                        0));
            }
            rebuilt.add(new DiskRow(key, entry.serial(), entry.stack()));
        }

        // 组头的张数要等本组数完才知道，回填一遍（行数很少，代价可以忽略）。
        for (int i = 0; i < rebuilt.size(); i++) {
            if (rebuilt.get(i) instanceof HostRow host) {
                rebuilt.set(i, new HostRow(host.key(), host.name(), host.icon(),
                        diskCounts.getOrDefault(host.key(), 0)));
            }
        }

        if (!rows.equals(rebuilt)) {
            rows.clear();
            rows.addAll(rebuilt);
            clampScroll();
        }
        requestVisibleContents(false);
    }

    private void clampScroll() {
        int max = Math.max(0, rows.size() - VISIBLE_ROWS);
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
    }

    /** @return 当前表里磁盘行的张数（不含被隐藏的机器），用于「只剩一张就自动落到它」的判断。 */
    private int visibleDiskCount() {
        int count = 0;
        for (var row : rows) {
            if (row instanceof DiskRow) {
                count++;
            }
        }
        return count;
    }

    private long soleVisibleDisk() {
        long found = 0;
        for (var row : rows) {
            if (row instanceof DiskRow disk) {
                if (found != 0) {
                    return 0;
                }
                found = disk.serial();
            }
        }
        return found;
    }

    /**
     * 上报当前视口（多要一行余量）里的磁盘序列号。
     *
     * <p>只在集合真的变了才发包：滚动一屏发一次，而不是每帧一次。</p>
     */
    private void requestVisibleContents(boolean force) {
        var wanted = new LongOpenHashSet();
        int first = Math.max(0, scrollOffset - CONTENT_MARGIN_ROWS);
        int last = Math.min(rows.size(), scrollOffset + VISIBLE_ROWS + CONTENT_MARGIN_ROWS);
        for (int i = first; i < last; i++) {
            if (rows.get(i) instanceof DiskRow disk) {
                wanted.add(disk.serial());
            }
        }
        if (!force && wanted.equals(requestedContents)) {
            return;
        }
        requestedContents.clear();
        requestedContents.addAll(wanted);
        if (!wanted.isEmpty()) {
            PacketDistributor.sendToServer(new VisibleDisksPayload(List.copyOf(wanted)));
        }
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        rebuildRows();
        if (--contentRefreshCooldown <= 0) {
            contentRefreshCooldown = CONTENT_REFRESH_INTERVAL_TICKS;
            requestVisibleContents(true);
        }
        // 写盘目标按表里的口径算：父类算的是整份列表，这里把被隐藏的机器刨掉。
        getMenu().setClientAutoDisk(visibleDiskCount(), soleVisibleDisk());
    }

    // ---- 绘制 ----

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        // 不调 super.drawBG：父类的终端样式链会把 lastRow/bottom 推到面板高之外（row srcRect 高 1000 的
        // hack 会导致 2 行之后跳 +2017px），改用固定贴图直接 blit。
        guiGraphics.blit(TEXTURE, offsetX, offsetY, 0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        int x = offsetX + LIST_X;
        int y = offsetY + LIST_Y;
        guiGraphics.blit(TEXTURE, x, y, 0, 0, LIST_WIDTH, TITLE_HEIGHT);
        // 贴图只画了一行，按行高重复出整片滚动区；磁盘行首格再压一层浅绿
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int rowY = y + TITLE_HEIGHT + i * ROW_HEIGHT;
            guiGraphics.blit(TEXTURE, x, rowY, 0, TITLE_HEIGHT, LIST_WIDTH, ROW_HEIGHT);
            int rowIndex = scrollOffset + i;
            if (rowIndex < rows.size() && rows.get(rowIndex) instanceof DiskRow) {
                guiGraphics.fill(x + 1, rowY + 1, x + 1 + 16, rowY + 1 + 16, DISK_SLOT_TINT);
            }
        }
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);

        int baseX = offsetX + LIST_X;
        int baseY = offsetY + LIST_Y + TITLE_HEIGHT;
        int textColor = 0xFF404040;

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int rowIndex = scrollOffset + i;
            if (rowIndex >= rows.size()) {
                break;
            }

            int rowY = baseY + i * ROW_HEIGHT;
            switch (rows.get(rowIndex)) {
                case HostRow host -> {
                    if (!host.icon().isEmpty()) {
                        guiGraphics.renderItem(host.icon(), baseX + 1, rowY + 1);
                    }
                    var label = host.diskCount() > 1
                            ? host.name() + " (" + host.diskCount() + ")"
                            : host.name();
                    guiGraphics.drawString(font, font.plainSubstrByWidth(label, 16 * 18 - TOGGLE_SIZE - 22),
                            baseX + 21, rowY + 5, textColor, false);
                    drawHostToggle(guiGraphics, baseX, rowY, host.key());
                }
                case DiskRow disk -> drawDiskRow(guiGraphics, baseX, rowY, disk);
            }
        }
    }

    /** 磁盘行：第 0 格是磁盘，第 1..16 格是盘内样板（内容到达前只画磁盘）。 */
    private void drawDiskRow(GuiGraphics guiGraphics, int baseX, int rowY, DiskRow row) {
        guiGraphics.renderItem(row.disk(), baseX + 1, rowY + 1);
        guiGraphics.renderItemDecorations(font, row.disk(), baseX + 1, rowY + 1);

        var patterns = getMenu().getDiskContents(row.serial());
        if (patterns == null) {
            return;
        }

        var level = Minecraft.getInstance().level;
        for (int i = 0; i < patterns.size() && i < COLUMNS - 1; i++) {
            var pattern = patterns.get(i);
            if (pattern.isEmpty()) {
                continue;
            }

            int cellX = baseX + (i + 1) * 18 + 1;
            int cellY = rowY + 1;
            guiGraphics.renderItem(pattern, cellX, cellY);
            guiGraphics.renderItemDecorations(font, pattern, cellX, cellY);

            // 解不出来的样板标红：与样板管理终端同一个提示口径，一眼看出哪张盘里有坏样板。
            if (level != null && appeng.api.crafting.PatternDetailsHelper.decodePattern(pattern, level) == null) {
                guiGraphics.fill(cellX, cellY, cellX + 16, cellY + 16, 0x7fff0000);
            }
        }
    }

    /** 组头的显示/隐藏开关：一个小方块，隐藏时画成暗底。 */
    private void drawHostToggle(GuiGraphics guiGraphics, int baseX, int rowY, String key) {
        int x = baseX + LIST_WIDTH - TOGGLE_SIZE - 3;
        int y = rowY + (ROW_HEIGHT - TOGGLE_SIZE) / 2;
        boolean shown = !hiddenHosts.contains(key);
        guiGraphics.fill(x, y, x + TOGGLE_SIZE, y + TOGGLE_SIZE, shown ? 0xff9a9a9a : 0xff4a4a4a);
        guiGraphics.fill(x + 1, y + 1, x + TOGGLE_SIZE - 1, y + TOGGLE_SIZE - 1, shown ? 0xffcfcfcf : 0xff2a2a2a);
    }

    // ---- 交互 ----

    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        int rowIndex = rowIndexAt(xCoord, yCoord);
        if (rowIndex < 0) {
            return super.mouseClicked(xCoord, yCoord, btn);
        }

        var row = rows.get(rowIndex);
        if (row instanceof HostRow host) {
            if (btn == 0 && hostToggleAt(xCoord, yCoord)) {
                if (!hiddenHosts.remove(host.key())) {
                    hiddenHosts.add(host.key());
                }
                return true;
            }
            return true;
        }

        if (row instanceof DiskRow disk && columnAt(xCoord) == 0) {
            int index = indexOfDisk(disk.serial());
            // 列表口径与父类不一致时（例如磁盘刚被换走）不动作，也不把这一下漏给底下的控件。
            if (index < 0) {
                return true;
            }
            if (btn == 0) {
                onDiskClick(index);
            } else if (btn == 1 && hasShiftDown()) {
                onDiskShiftRightClick(index);
            } else if (btn == 1) {
                onDiskRightClick(index);
            } else if (btn == 2) {
                onDiskMiddleClick(index);
            }
            return true;
        }

        return true;
    }

    @Override
    public boolean mouseScrolled(double xCoord, double yCoord, double scrollX, double scrollY) {
        int max = Math.max(0, rows.size() - VISIBLE_ROWS);
        if (max > 0 && scrollY != 0) {
            scrollOffset = Math.max(0, Math.min(max, scrollOffset - (int) Math.signum(scrollY)));
            requestVisibleContents(false);
            return true;
        }
        return super.mouseScrolled(xCoord, yCoord, scrollX, scrollY);
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int x, int y) {
        int rowIndex = rowIndexAt(x, y);
        if (rowIndex >= 0) {
            var stack = itemAt(rowIndex, columnAt(x));
            if (stack != null && !stack.isEmpty()) {
                guiGraphics.renderTooltip(font, stack, x, y);
                return;
            }
        }
        super.renderTooltip(guiGraphics, x, y);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    // ---- 命中测试（面板内坐标 → 行列） ----

    private int rowIndexAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - leftPos - LIST_X;
        int relY = (int) mouseY - topPos - LIST_Y - TITLE_HEIGHT;
        if (relX < 0 || relX >= LIST_WIDTH || relY < 0) {
            return -1;
        }
        int slotRow = relY / ROW_HEIGHT;
        if (slotRow < 0 || slotRow >= VISIBLE_ROWS) {
            return -1;
        }
        int rowIndex = scrollOffset + slotRow;
        return rowIndex < rows.size() ? rowIndex : -1;
    }

    private int columnAt(double mouseX) {
        return ((int) mouseX - leftPos - LIST_X) / 18;
    }

    private boolean hostToggleAt(double mouseX, double mouseY) {
        int relX = (int) mouseX - leftPos - LIST_X;
        int rowIndex = rowIndexAt(mouseX, mouseY);
        if (rowIndex < 0 || relX < LIST_WIDTH - TOGGLE_SIZE - 3) {
            return false;
        }
        return rows.get(rowIndex) instanceof HostRow;
    }

    @Nullable
    private ItemStack itemAt(int rowIndex, int column) {
        if (rowIndex < 0 || rowIndex >= rows.size() || column < 0 || column >= COLUMNS) {
            return null;
        }
        if (rows.get(rowIndex) instanceof DiskRow disk) {
            if (column == 0) {
                return disk.disk();
            }
            var patterns = getMenu().getDiskContents(disk.serial());
            if (patterns != null && column - 1 < patterns.size()) {
                return patterns.get(column - 1);
            }
        }
        return null;
    }
}
