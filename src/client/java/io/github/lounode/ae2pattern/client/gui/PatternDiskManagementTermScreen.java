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

import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.core.localization.ButtonToolTips;

import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.network.DiskHostListPayload;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * The pattern disk management terminal's screen: a table of every disk on the grid, grouped by the machine
 * holding it, with the encoding terminal's encoding area underneath.
 *
 * <p>Layout comes from {@code Sprite-0001} (the texture this screen slices): a title strip and 17 columns of
 * 18px cells on top, then the player inventory on the left and the encoding area - the very same widgets the
 * encoding terminal builds in its constructor - on the right. Every position in the style JSON is
 * {@code top}-anchored, so the panel height (see the style JSON's {@code terminalStyle}) no longer moves
 * anything on screen.</p>
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

    /**
     * 贴图的真实像素尺寸。
     *
     * <p>本模组其余 GUI 贴图都是 256×256，而这张是 512×512：AE2 的 {@code Blitter} 与
     * {@code GuiGraphics.blit} 的简写重载都按 256 算 UV（`srcRect / 256`），不声明真实尺寸的话
     * 同一块 srcRect 会到 2 倍坐标处取样，整块背景都是错的。</p>
     */
    private static final int TEXTURE_SIZE = 512;

    /** 「显示模式」按钮：与 AE2 样板访问终端共用同一个服务端设置。 */
    private final ServerSettingToggleButton<ShowPatternProviders> showProvidersButton;

    // 表格区几何：按贴图实测（描边带 x0..7，填充区从 x8 开始；表头 y0..16）。
    // 行分配同 AE2 的样板访问终端（PatternAccessTermScreen.drawBG）：一行 18px，按「行类型」从贴图取行带——
    // 文本带 y17/53/89（无格子框，给主机名这类纯文本行）与物品带 y35/71/107（有 17 格框，给磁盘/样板行），
    // 三份分别对应可见窗口的首行/中间行/末行（该屏的 ROW_TEXT_/ROW_INVENTORY_TOP|MIDDLE|BOTTOM_BBOX 同值）。
    // 落点口径同 AE2 槽位：格子的 x/y 就是「物品左上角」，底图画在它 −1 处，所以绘制时统一 +1——
    // LIST_X 取 7 时物品落在贴图实测的 x=8。
    private static final int PANEL_WIDTH = 340;
    /**
     * 面板高，与 style JSON 的 terminalStyle 算出的 imageHeight 一致（header 17 + firstRow 18 + lastRow 18 +
     * bottom 167 = 220）。贴图实际画到 y=219，面板按它收，底部不留空白。
     */
    private static final int PANEL_HEIGHT = 220;
    private static final int LIST_X = 7;
    private static final int LIST_Y = 0;

    /** 填充区宽：17 格 × 18px。贴图填充区实测到 x=311，再右是滚动条区（本屏只用滚轮，不画它）。 */
    private static final int LIST_WIDTH = 306;

    /** 表头高：贴图 y0..16，与 AE2 的 GUI_HEADER_HEIGHT（17）同值。 */
    private static final int TITLE_HEIGHT = 17;
    /** 行高：与 AE2 一致的一行 18px（贴图里每种行带也都是 18px 高）。 */
    private static final int ROW_HEIGHT = 18;

    /**
     * 行内「内容线」的纵向起点：物品带上沿在 +1、内部从 +2 起（贴图实测物品带内部 37..51，带顶 35）；横向只
     * 有 1px 边框，所以横向内缩另计（见绘制处的 +1）。
     */
    private static final int CELL_Y_INSET = 2;
    private static final int COLUMNS = 17;
    /** 可见行数：表格区 y17..124 共 108px ÷ 18 = 6 行；滚动由滚轮驱动。 */
    private static final int VISIBLE_ROWS = 6;
    /** 视口外多要一行内容：滚一格时不至于先闪一帧空行。 */
    private static final int CONTENT_MARGIN_ROWS = 1;

    // 静态 Blitter：UV 按 512 算（见 TEXTURE_SIZE），每帧不新建对象。
    // 注意它们是可变对象：每次使用必须紧接 dest(...) + blit(...)，不要缓存引用到别处再画。
    private static final Blitter BACKGROUND = Blitter.texture(TEXTURE, TEXTURE_SIZE, TEXTURE_SIZE)
            .src(0, 0, PANEL_WIDTH, PANEL_HEIGHT);

    // 六条行带，与 AE2 的 ROW_TEXT_/ROW_INVENTORY_TOP|MIDDLE|BOTTOM_BBOX 同一分区（本屏贴图与它同源）。
    private static final Blitter ROW_TEXT_TOP = rowBand(17);
    private static final Blitter ROW_INVENTORY_TOP = rowBand(35);
    private static final Blitter ROW_TEXT_MIDDLE = rowBand(53);
    private static final Blitter ROW_INVENTORY_MIDDLE = rowBand(71);
    private static final Blitter ROW_TEXT_BOTTOM = rowBand(89);
    private static final Blitter ROW_INVENTORY_BOTTOM = rowBand(107);

    private static Blitter rowBand(int srcY) {
        return Blitter.texture(TEXTURE, TEXTURE_SIZE, TEXTURE_SIZE).src(LIST_X, srcY, LIST_WIDTH, ROW_HEIGHT);
    }

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

        // 「显示模式」与 AE2 样板访问终端共用同一个服务端设置：同一套取值、同一份持久化。按钮的图标与提示由
        // AE2 的 SettingToggleButton 静态注册表提供，本模组不需要自带资源。
        this.showProvidersButton = new ServerSettingToggleButton<>(
                Settings.TERMINAL_SHOW_PATTERN_PROVIDERS, ShowPatternProviders.VISIBLE);
        addToLeftToolbar(this.showProvidersButton);
    }

    @Override
    protected boolean usesDiskListPanel() {
        return false;
    }

    @Override
    protected boolean usesNeoEcoUploadButton() {
        // 管理终端走标记路线，不挂 NEO ECO 的上传按钮（EAE+ 那条本来就不在管理类型下）。
        return false;
    }

    @Override
    public void init() {
        super.init();
        // MEStorageScreen.init() 给终端网格加了 RepoSlot；我们用自定义表格，不需要它们。
        this.menu.slots.removeIf(slot -> slot instanceof RepoSlot);
        // 父类把初始焦点给了 ME 搜索框，但本屏不显示物品网格，那个框在 JSON 里被移出面板；
        // 玩家打字应该进磁盘表的搜索框，否则键会走进一个看不见的输入框。
        setInitialFocus(miniSearchField());
        hideIrrelevantToolbarButtons();
    }

    /**
     * 隐藏 AE2 标准终端工具栏里对本屏无意义的按钮：「排序顺序」（本表顺序由服务端的宿主分组决定）与
     * 「终端设置」（它的设置页全是物品网格的项）。
     *
     * <p>AE2 对这两枚都是无条件添加：字段 private、按钮条（{@code VerticalButtonBar}）只有 add 没有移除接口、
     * 也没有可覆写的开关，所以在 super.init() 之后按控件身份精确匹配再关掉——排序顺序读
     * {@link SettingToggleButton#getSetting()}，终端设置比对它自己的 tooltip 常量
     * （{@link ButtonToolTips#TerminalSettings}，AE2 自带的语言键）。本屏自己的「编码」「清空」按钮虽然也是
     * {@link ActionButton}，但 tooltip 是 Encode / ClearSettings，不会被误伤。</p>
     *
     * <p>{@code setVisibility(false)} 同时关掉 visible 与 active，TAB 焦点路径也取不到它；按钮条只排布可见
     * 按钮，隐藏后不留空位。</p>
     */
    private void hideIrrelevantToolbarButtons() {
        // 比字符串而非 Component：按钮的消息是已解析的字面文本，语言键形式的 Component 永远不等于它。
        // 两边都走同一份语言文件，中英任何一种语言下都成立。
        var terminalSettings = ButtonToolTips.TerminalSettings.text().getString();
        for (var listener : this.children()) {
            if (!(listener instanceof IconButton button)) {
                continue;
            }
            boolean isSortDirection = button instanceof SettingToggleButton<?> toggle
                    && toggle.getSetting() == Settings.SORT_DIRECTION;
            boolean isTerminalSettings = button.getTooltipMessage().stream()
                    .anyMatch(line -> line.getString().contains(terminalSettings));
            if (isSortDirection || isTerminalSettings) {
                button.setVisibility(false);
            }
        }
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
        // 显示模式按钮的档位回显：换档后服务端会重推分组清单，档位跟着清单回来。
        this.showProvidersButton.set(getMenu().shownProviders());
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
        // hack 会导致 2 行之后跳 +2017px），改用固定贴图直接 blit。跳过它的代价是 AE2 物品网格的 pinned
        // 行覆盖层与那次手写 searchField.render——本屏不显示那个网格，而搜索框仍由 widget 容器正常渲染。
        blit(BACKGROUND, guiGraphics, offsetX, offsetY);

        int x = offsetX + LIST_X;
        int y = offsetY + LIST_Y + TITLE_HEIGHT;

        // 行分配同 AE2：每行先铺「文本带」作底，磁盘/样板行再叠「物品带」（带高 18，宽度只到 17 格）。
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            boolean firstLine = i == 0;
            boolean lastLine = i == VISIBLE_ROWS - 1;
            int rowY = y + i * ROW_HEIGHT;

            blit(selectRowBand(false, firstLine, lastLine), guiGraphics, x, rowY);
            int rowIndex = scrollOffset + i;
            if (rowIndex < rows.size() && rows.get(rowIndex) instanceof DiskRow) {
                blit(selectRowBand(true, firstLine, lastLine), guiGraphics, x, rowY);
                // 磁盘行首格再压一层浅绿，位置与 drawFG 的磁盘图标同一点。
                guiGraphics.fill(x + 1, rowY + CELL_Y_INSET, x + 1 + 16, rowY + CELL_Y_INSET + 16, DISK_SLOT_TINT);
            }
        }
    }

    /**
     * 行带的选择与 AE2 的 {@code PatternAccessTermScreen#selectRowBackgroundBox} 同口径：可见窗口的首行取 TOP、
     * 末行取 BOTTOM、其余取 MIDDLE。
     */
    private static Blitter selectRowBand(boolean inventoryLine, boolean firstLine, boolean lastLine) {
        if (inventoryLine) {
            return firstLine ? ROW_INVENTORY_TOP : lastLine ? ROW_INVENTORY_BOTTOM : ROW_INVENTORY_MIDDLE;
        }
        return firstLine ? ROW_TEXT_TOP : lastLine ? ROW_TEXT_BOTTOM : ROW_TEXT_MIDDLE;
    }

    private static void blit(Blitter blitter, GuiGraphics guiGraphics, int destX, int destY) {
        blitter.dest(destX, destY).blit(guiGraphics);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);

        // renderLabels 的 pose 已由 vanilla 平移到 (leftPos, topPos)，这里必须用裸局部坐标：
        // 再加 offsetX/offsetY 会把整个 GUI 原点算第二遍，表格内容整体右下偏移一格到数格（AE2 自家
        // drawFG 同样用裸坐标，如 VibrationChamberScreen 的 dest(80, 20 + ...)）。
        // 注意传入的 mouseX/mouseY 是绝对屏幕坐标（命中测试因此要减 leftPos/topPos，本类已如此）。
        int baseX = LIST_X;
        int baseY = LIST_Y + TITLE_HEIGHT;
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
                        guiGraphics.renderItem(host.icon(), baseX + 1, rowY + CELL_Y_INSET);
                    }
                    var label = host.diskCount() > 1
                            ? host.name() + " (" + host.diskCount() + ")"
                            : host.name();
                    guiGraphics.drawString(font, font.plainSubstrByWidth(label, 16 * 18 - TOGGLE_SIZE - 22),
                            baseX + 21, rowY + CELL_Y_INSET + 4, textColor, false);
                    drawHostToggle(guiGraphics, baseX, rowY, host.key());
                }
                case DiskRow disk -> drawDiskRow(guiGraphics, baseX, rowY, disk);
            }
        }
    }

    /** 磁盘行：第 0 格是磁盘，第 1..16 格是盘内样板（内容到达前只画磁盘）。 */
    private void drawDiskRow(GuiGraphics guiGraphics, int baseX, int rowY, DiskRow row) {
        guiGraphics.renderItem(row.disk(), baseX + 1, rowY + CELL_Y_INSET);
        guiGraphics.renderItemDecorations(font, row.disk(), baseX + 1, rowY + CELL_Y_INSET);

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
            int cellY = rowY + CELL_Y_INSET;

            // 显示主产物，而不是样板本体：与 AE2 样板访问终端同口径（它的 PatternSlot.getDisplayStack 用
            // EncodedPatternItem#getOutput 换掉槽位显示）。任何类型的产物都直接显示——那个方法对流体等
            // 非物品产出会包一层伪物品；取不到时回退到样板本体。玩家因此不必按住 Shift 才知道样板做什么。
            var output = patternOutputOf(pattern);
            var icon = output.isEmpty() ? pattern : output;
            guiGraphics.renderItem(icon, cellX, cellY);
            guiGraphics.renderItemDecorations(font, icon, cellX, cellY);

            // 解不出来的样板标红：与样板访问终端同一个提示口径，一眼看出哪张盘里有坏样板。
            if (level != null && appeng.api.crafting.PatternDetailsHelper.decodePattern(pattern, level) == null) {
                guiGraphics.fill(cellX, cellY, cellX + 16, cellY + 16, 0x7fff0000);
            }
        }
    }

    /**
     * 样板在终端里该显示的主产物；不是 AE2 样板物品、或取不到主产物时返回空堆。
     *
     * <p>直接用 AE2 的 {@code EncodedPatternItem#getOutput}：它对非物品产出（流体等）会包一层伪物品，
     * 所以任何类型的产物都能直接显示；它也自带缓存，逐帧调用不会反复解码。该方法在“解出的样板报告零产出”
     * 时会在内部越界，而渲染路径不能因此炸掉整帧，所以在边界收口一次。</p>
     */
    private static ItemStack patternOutputOf(ItemStack pattern) {
        if (!(pattern.getItem() instanceof appeng.crafting.pattern.EncodedPatternItem encodedPattern)) {
            return ItemStack.EMPTY;
        }
        try {
            return encodedPattern.getOutput(pattern);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** 组头的显示/隐藏开关：一个小方块，隐藏时画成暗底。 */
    private void drawHostToggle(GuiGraphics guiGraphics, int baseX, int rowY, String key) {
        int x = baseX + LIST_WIDTH - TOGGLE_SIZE - 3;
        // 行带内部自 CELL_Y_INSET 起、底部留 1px 边框，开关在这段里居中。
        int y = rowY + CELL_Y_INSET + (ROW_HEIGHT - CELL_Y_INSET - 1 - TOGGLE_SIZE) / 2;
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
