package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import appeng.client.gui.StackWithBounds;
import appeng.client.gui.me.common.RepoSlot;

import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.blaze3d.platform.InputConstants;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

// 行模型已抽到 DiskTableRowModel：静态导入让 Row / HostRow / DiskRow / FreeSlotsRow 这些短名
// 在本类内照旧可用，下面的表格绘制与点击分发因此一句都没改。
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.DiskRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.FreeSlotsRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.HostRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.Row;

import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.DisabledSlot;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.SettingToggleButton;

import guideme.PageAnchor;

import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * The pattern disk management terminal's screen: a table of every disk on the grid, grouped by the machine
 * holding it, with the encoding terminal's encoding area underneath.
 *
 * <p>Layout comes from the style document: the texture slices for the table chrome live in its {@code images}
 * block (this screen fetches them by name), a title strip and 17 columns of 18px cells on
 * top, then the player inventory on the left and the encoding area - the very same widgets the
 * encoding terminal builds in its constructor - on the right. The footer entries in the style JSON are
 * {@code bottom}-anchored, so they follow the panel height that {@code terminalStyle} produces.</p>
 *
 * <p><b>Rows.</b> One row per machine (a header carrying its icon, name and disk count), then one row per
 * disk: cell 0 is the disk itself, the remaining 16 cells are the patterns stored on
 * it. A disk holding more than 16 patterns continues on the rows below, where all 17 cells are patterns.
 * Disk-level clicks go through the inherited {@code onDisk*Click} handlers, so selecting, marking and
 * renaming a disk behave exactly as in the encoding terminal.</p>
 *
 * <p><b>Search.</b> The mini search box is inherited unchanged and still filters the inherited disk list; the
 * table is built from that filtered list, which keeps "what is on screen" and "what the encode button writes
 * to" the same set.</p>
 *
 * <p><b>Contents.</b> Patterns shown in the cells come from {@link PatternDiskManagementTermMenu}'s cache,
 * filled by the server in response to {@link VisibleDisksPayload}. Until a disk's contents arrive its row
 * shows just the disk; the request is re-sent whenever the visible set changes.</p>
 *
 * <p>{@code @IPNPlayerSideOnly}：整理模组的标注，与父屏同源（它没有 {@code @Inherited}，故在此重标一份），
 * 理由见 {@code PatternDiskEncodingTermScreen} 的类注释。</p>
 *
 * <p><b>拆分</b>：表格的外观、行模型、绘制与命中测试在 {@link DiskTableWidgets}；五枚按钮、内容搜索栏与它们
 * 的视图状态 / 服务端同步在 {@link DiskToolbarWidgets}。本类只留 {@code init} / {@code render} /
 * {@code drawBG} / {@code drawFG} 等 {@code @Override} 覆写与一行转调，以及鼠标 / 槽位的动作分发。</p>
 */
@IPNPlayerSideOnly
public class PatternDiskManagementTermScreen extends PatternDiskEncodingTermScreen {

    /**
     * 本屏幕的菜单是管理终端自己的那个类型，但父屏把菜单类型写死在签名里（把它泛型化会牵连编码区面板、数量子屏与
     * 两个集成适配类），所以在这里用协变返回把类型收窄：本类内部直接调管理菜单的方法，其余文件一动不动。
     */
    @Override
    public PatternDiskManagementTermMenu getMenu() {
        return (PatternDiskManagementTermMenu) super.getMenu();
    }

    /**
     * 打开管理终端自己的指南页：父屏把 getHelpTopic 写死成编码终端那一页，本类不覆写就会被它遮蔽。
     * 无线版的样式虽然 includes 了面板样式，同样以这里为准，所以两个管理终端都落到这一页。
     */
    @Override
    protected PageAnchor getHelpTopic() {
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/pattern_disk_management_terminal.md"),
                null);
    }

    /** 按钮组与它们的视图状态（显示模式 / 隐藏槽位 / 折叠虚拟样板 / 内容搜索 / 搜索范围）。 */
    private final DiskToolbarWidgets toolbar;

    /** 表格本体（几何、贴图、行模型、滚动、绘制、命中测试）。 */
    private final DiskTableWidgets table;

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.management_terminal");

    public PatternDiskManagementTermScreen(PatternDiskManagementTermMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        // 五枚按钮与搜索栏：顺序即控件清单顺序，不能重排（见 DiskToolbarWidgets 的构造注释）。
        this.toolbar = new DiskToolbarWidgets(menu, widgets, this::addToLeftToolbar,
                this.naturalSortButton, this::naturalSortEnabled);
        // 表格几何、贴图与它自己的滚动条。样式读键、排序口径与父类搜索 / 候选兜底都以窄回调注入，
        // 协作类不持有本屏；断线遮罩仍走本屏继承来的 renderDisconnectedOverlay。
        this.table = new DiskTableWidgets(style, menu, widgets, this.toolbar,
                this::getSortBy, this::getSortDir, this::naturalSortEnabled,
                this::diskEntryAt, this::isDiskSearchActive, this::diskSearchQuery,
                this::tierGroupByUsage, PatternDiskEncodingTermScreen::capCandidates,
                this::renderDisconnectedOverlay);

        // 关掉物品网格那枚的滚轮捕获：它的范围会被父类在开屏/缩放/外部搜索时重新喂（不经过 repo），
        // 所以只换 repo 的监听器不够；它排在 widget 表更前、又默认 captureMouseWheel，一旦有 range 就会
        // 把整屏滚轮吃掉（而它 park 在屏外，玩家看不到任何反应）。
        var gridScrollbar = parentScrollbar(this);
        if (gridScrollbar != null) {
            gridScrollbar.setCaptureMouseWheel(false);
        }
    }

    /**
     * 取父类那枚物品网格滚动条（字段 private：AE2 没给读法，ModAccessor 又只在运行时改名、
     * 编译期看不见，所以只能反射）。取不到就作罢：最坏是表格滚轮不灵，不该因此把界面弄坏。
     */
    @Nullable
    private static Scrollbar parentScrollbar(MEStorageScreen<?> screen) {
        try {
            var field = MEStorageScreen.class.getDeclaredField("scrollbar");
            field.setAccessible(true);
            return field.get(screen) instanceof Scrollbar bar ? bar : null;
        } catch (Throwable e) {
            LOGGER.debug("Could not reach AE2's grid scrollbar; table scrolling may ignore the wheel", e);
            return null;
        }
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

        // 行数由父类按 style 的 terminalStyle 算好（imageHeight 就是它的产物：表头 + 行×18 + 尾饰），这里反推
        // 回来用，保证行带与父类摆好的槽位/控件用的是同一个值——自己再算一遍公式只会引入偏差。
        table.computeVisibleRows(imageHeight);

        // MEStorageScreen.init() 给终端网格加了 RepoSlot；我们用自定义表格，不需要它们。
        this.menu.slots.removeIf(slot -> slot instanceof RepoSlot);
        // 父类把初始焦点给了 ME 搜索框，但本屏不显示物品网格，那个框在 JSON 里被移出面板——让它拿着焦点
        // 等于把玩家的按键送进一个看不见的输入框。所以清掉初始焦点：开局谁也不选中，要用搜索框自己点。
        //
        // 用 setFocused(null) 而不是 setInitialFocus(null)：后者的实现要先拿参数解引用去算焦点路径，传 null
        // 直接 NPE，而且抛在 init() 里会连带把整个开屏打断（NeoForge 报 "Failed to handle advanced open
        // screen from server"，客户端被断开）。setFocused 才是 null 安全的那个。
        setFocused(null);

        // 风格档位可能把面板改矮：清单没变时 rebuildRows 不会夹偏移，这里补一次，免得顶部留白。
        // （带高与 terminalStyle 的 header/row/bottom 同源：两者都在本屏的样式文档里，不存在跨文件同步。）
        table.clampScroll();
    }

    /**
     * 左侧工具栏的清单：顺序即清单顺序，不在清单里的一律隐藏（包括 AE2 那枚「终端设置」——它的设置页
     * 全是物品网格的项，而本屏把网格整个藏了）。
     *
     * <p>基类在它自己的 {@code init()} 里读这份清单，本屏不自己应用：父屏（编解码屏）也定义了清单，
     * 两边各应用一次的话本屏这几枚会被父屏那份摘掉。</p>
     */
    @Override
    protected List<String> toolbarPlan() {
        return TOOLBAR;
    }

    private static final List<String> TOOLBAR = List.of(
            "guide", "sortBy", "naturalSort", "sortOrder", "showProviders", "hideSlots", "collapsePatterns",
            "mode", "terminalSwitch", "frequencyCard", "frequencyAutoConnect");

    /** 本屏多出来的三枚：显示模式（AE2 的样板访问终端开关）、隐藏槽位与折叠虚拟样板（后两枚是本屏自己的行模型开关）。 */
    @Override
    protected String toolbarSlot(Button button) {
        var slot = super.toolbarSlot(button);
        if (slot != null) {
            return slot;
        }
        return toolbar.toolbarSlot(button);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        // 显示模式 / 隐藏槽位 / 选中盘 / 搜索范围 / 附加排序的服务端镜像跟随与按钮回显，都收在 toolbar 里。
        toolbar.syncFromServer();
        table.syncScrollbar();
        table.rebuildRows();
        table.syncScrollbar();
        table.tickContentRefresh();
        // 右键选中的那张盘被取走、或搜索把它筛出去了，选择跟着失效。
        toolbar.validateSelection(table::containsDisk);
        // 写盘目标：选中的那张盘优先，写不进时顺位到同一容器内的其他盘（候选顺序就是意图顺序）。
        getMenu().setClientAutoDisks(table.buildAutoDiskCandidates());
    }

    // ---- 绘制 ----

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        // 表头带 + 可见行带 + 尾饰带的拼法、行带选择与磁盘首格上色都在 DiskTableWidgets.drawBG。
        table.drawBG(guiGraphics, offsetX, offsetY);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        // 逐行绘制（裸局部坐标）与断线遮罩都在 DiskTableWidgets.drawFG，遮罩回调仍落回本屏的
        // renderDisconnectedOverlay。
        table.drawFG(guiGraphics, font, offsetX, offsetY, mouseX, mouseY);
    }

    // ---- 交互 ----

    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        // 展开的模式列表要先抢这一下：本屏的行表格排在 children 前面，行带与列表条目的第二列重叠时
        // 会把点击吃掉。本方法在行内不调 super，所以这个拦截必须自己先做一次。
        if (btn == InputConstants.MOUSE_BUTTON_LEFT && interceptModeMenuClick(xCoord, yCoord)) {
            return true;
        }

        // 内容搜索栏的右键清空（与 AE2 顶部搜索框、父屏磁盘搜索栏同款手势）。清空后不 return：
        // 这一下照旧往下走，让搜索框拿到焦点。
        // 过滤读的是本屏自己的 contentSearchText（不是 AE2 的 searchText），所以除了改控件值再显式
        // 清一遍那个字段：AE2 的右键清空也是「控件值 + 过滤文本」成对写的，这里跟同一口径，
        // 不依赖 responder 是否被回调。
        if (btn == InputConstants.MOUSE_BUTTON_RIGHT && toolbar.isContentSearchFieldOver(xCoord, yCoord)) {
            toolbar.clearContentSearch();
        }

        int rowIndex = table.rowIndexAt(xCoord, yCoord, leftPos, topPos);
        if (rowIndex < 0) {
            return super.mouseClicked(xCoord, yCoord, btn);
        }

        var row = table.rowAt(rowIndex);
        if (row instanceof HostRow) {
            // 组头行只是一条分隔 + 标签，没有可点的东西：吃掉这一下，别漏给底下的控件。
            return true;
        }

        // 空磁盘槽格：左键＝把光标上那张放进去（服务端挑第一个空槽）。
        // 「把背包里的盘存进某台容器」不挂在这里，而是 Shift+左键背包里的那张盘（见 slotClicked）。
        if (row instanceof FreeSlotsRow free) {
            if (btn == 0 && !hasShiftDown() && holdingDisk()) {
                getMenu().insertDisk(new PatternDiskManagementTermMenu.InsertDiskRequest(free.groupName()));
            }
            return true;
        }

        // 磁盘格只在首行存在：续行的第 0 格是样板，不该把选中/标记/改名这些磁盘动作落到它头上。
        if (row instanceof DiskRow disk && disk.from() == 0 && table.columnAt(xCoord, leftPos) == 0) {
            int index = indexOfDisk(disk.serial());
            // 列表口径与父类不一致时（例如磁盘刚被换走）不动作，也不把这一下漏给底下的控件。
            if (index < 0) {
                return true;
            }
            if (btn == 0 && hasShiftDown()) {
                getMenu().extractDisk(disk.serial(), PatternDiskEncodingTermMenu.ExtractTarget.INVENTORY);
            } else if (btn == 0) {
                getMenu().extractDisk(disk.serial(), PatternDiskEncodingTermMenu.ExtractTarget.CURSOR);
            } else if (btn == 1 && hasShiftDown()) {
                overwriteMarkFromSearch(index);
            } else if (btn == 1) {
                toolbar.toggleSelectedSerial(disk.serial());
                toolbar.sendToServer();
            }
            return true;
        }

        // 样板格（含续行整行）：左键取到光标、Shift+左键取到背包、右键填进样板编辑槽。
        if (row instanceof DiskRow disk) {
            int pattern = table.patternIndexAt(table.rowIndexAt(xCoord, yCoord, leftPos, topPos),
                    table.columnAt(xCoord, leftPos));
            if (pattern >= 0) {
                var target = btn == 1
                        ? PatternDiskEncodingTermMenu.ExtractTarget.ENCODED_SLOT
                        : (hasShiftDown() ? PatternDiskEncodingTermMenu.ExtractTarget.INVENTORY
                                : PatternDiskEncodingTermMenu.ExtractTarget.CURSOR);
                if (btn == 0 || btn == 1) {
                    getMenu().extractPattern(disk.serial(), pattern, target);
                }
                return true;
            }
        }

        return true;
    }

    /** 光标上是不是拿着一块样板磁盘（只影响要不要发动作，真伪由服务端再判一次）。 */
    private boolean holdingDisk() {
        return PatternDiskApi.isPatternDisk(getMenu().getCarried());
    }

    /**
     * Shift+左键**背包里**的样板磁盘：把它存进「右键选中那张盘」所在的容器。
     *
     * <p>拦在 {@code super} 之前：这一下原本会走普通的快捷移动——本屏已经把进网络那条堵了，而 AE2 还有一条
     * “没目标槽位就塞进空 FakeSlot”的回退，会往合成格里放一份不消耗原物的鬼影；拦下来既避免了那一下，
     * 也把这条手势拿来做正事。目标容器不取鼠标下的位置——此刻鼠标在背包上——而取选中的那张盘：
     * 这条手势的意思就是「跟它放一起」。</p>
     */
    @Override
    protected void slotClicked(@Nullable Slot slot, int slotIdx, int mouseButton, ClickType clickType) {
        if (clickType == ClickType.QUICK_MOVE && slot != null && !(slot instanceof DisabledSlot)
                && slot.getItem().getItem() instanceof PatternDiskItem && isPlayerSideSlot(slot)) {
            if (toolbar.selectedSerial() == 0) {
                return;
            }
            getMenu().storeInventoryDisk(new PatternDiskManagementTermMenu.StoreInventoryDiskRequest(
                    toolbar.selectedSerial(), slot.getContainerSlot()));
            return;
        }
        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    /** 这个槽位是不是玩家背包那批（背包容量的槽号在服务端才用得着）。 */
    private boolean isPlayerSideSlot(Slot slot) {
        var semantic = getMenu().getSlotSemantic(slot);
        return semantic == SlotSemantics.PLAYER_INVENTORY || semantic == SlotSemantics.PLAYER_HOTBAR;
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int x, int y) {
        int rowIndex = table.rowIndexAt(x, y, leftPos, topPos);
        if (rowIndex >= 0) {
            var row = table.rowAt(rowIndex);
            // 空磁盘槽格不画提示：左键把光标上的盘放进去、Shift+左键把背包里的盘存进选中盘所在的容器，都是
            // 容器里最基础的取放，不需要额外注释。这一格仍要吃掉 hover，免得落到下面按物品名取提示的分支。
            if (row instanceof FreeSlotsRow) {
                return;
            }
            // 磁盘格（首行第 0 格）：给与编码终端磁盘列表同一套信息（容量、标记、手势），而不是只报物品名。
            if (row instanceof DiskRow disk && disk.from() == 0 && table.columnAt(x, leftPos) == 0) {
                table.renderDiskTooltip(guiGraphics, font, disk, x, y);
                return;
            }
            var stack = table.itemAt(rowIndex, table.columnAt(x, leftPos));
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

    /**
     * 交给切片浏览器的那一格：命中判定与栈的组装在 {@link DiskTableWidgets#stackUnderMouse}，没命中就
     * 转回父类（背包与快捷栏那些真实槽仍走它）。
     */
    @Nullable
    @Override
    public StackWithBounds getStackUnderMouse(double mouseX, double mouseY) {
        var result = table.stackUnderMouse(mouseX, mouseY, leftPos, topPos);
        return result != null ? result : super.getStackUnderMouse(mouseX, mouseY);
    }
}
