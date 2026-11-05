package io.github.lounode.ae2pattern.common.menu.pattern;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import appeng.api.config.ShowPatternProviders;

import io.github.lounode.ae2pattern.network.DiskContentPayload;
import io.github.lounode.ae2pattern.network.DiskHostListPayload;

/**
 * 管理终端的客户端视图镜像：服务端下发的分组清单、显示状态六元组与按需推送的磁盘内容都缓存在这里。
 *
 * <p>这一整块状态纯属客户端只读镜像，对菜单零引用：元组的编解码是静态纯函数，「沿用当前显示档 / 折叠开关」
 * 由调用方以快照参数传进来。客户端动作名与发包口仍留在菜单类，本类只收/存/编解码。</p>
 */
final class DiskClientView {

    /** 视图状态解码结果：一次视图状态上报。 */
    record ViewState(boolean hideEmptySlots, long selectedSerial,
            DiskEncodingLogic.SearchScope searchScope, boolean naturalSort,
            ShowPatternProviders shownProviders, boolean collapseVirtualPatterns) {
    }

    /** 客户端侧：服务端推送的分组清单（Screen 每帧读）。 */
    private List<DiskHostListPayload.HostGroup> hostList = List.of();

    /** 客户端侧：服务端最近一次是按哪种显示模式筛的清单，屏幕用它回显按钮图标。 */
    private ShowPatternProviders shownProviders = ShowPatternProviders.VISIBLE;
    /** 服务端下发的“是否隐藏空槽位”当前值（屏幕开关的初值，不再由客户端自行默认）。 */
    private boolean viewHideEmptySlots = true;
    /** 服务端下发的「折叠虚拟样板」当前值（屏幕开关的初值，默认关闭＝正常展开）。 */
    private boolean viewCollapseVirtualPatterns;
    /** 服务端下发的当前选中盘序列号，0 = 未选中。 */
    private long viewSelectedSerial;
    /** 服务端下发的样板内容搜索范围（新搜索栏那枚轮换按钮的当前档）。 */
    private DiskEncodingLogic.SearchScope viewSearchScope = DiskEncodingLogic.SearchScope.BOTH;
    /** 服务端下发的「附加排序」开关当前值。 */
    private boolean viewNaturalSort = true;

    /** 客户端侧：按序列号缓存的磁盘内容（Screen 每帧读）。 */
    private final Long2ObjectOpenHashMap<List<ItemStack>> diskContents = new Long2ObjectOpenHashMap<>();

    void receiveHostList(List<DiskHostListPayload.HostGroup> hosts, ShowPatternProviders shownProviders,
            boolean hideEmptySlots, long selectedSerial, DiskEncodingLogic.SearchScope searchScope,
            boolean naturalSort, boolean collapseVirtualPatterns) {
        this.hostList = List.copyOf(hosts);
        this.shownProviders = shownProviders;
        this.viewHideEmptySlots = hideEmptySlots;
        this.viewSelectedSerial = selectedSerial;
        this.viewSearchScope = searchScope;
        this.viewNaturalSort = naturalSort;
        this.viewCollapseVirtualPatterns = collapseVirtualPatterns;
    }

    /** 屏幕开屏时读它恢复「附加排序」开关。 */
    boolean viewNaturalSort() {
        return this.viewNaturalSort;
    }

    /** 屏幕开屏时读它恢复样板内容搜索范围。 */
    DiskEncodingLogic.SearchScope viewSearchScope() {
        return this.viewSearchScope;
    }

    /** 屏幕开屏时读它初始化「隐藏空槽位」开关，保证重开回到上次的值。 */
    boolean viewHideEmptySlots() {
        return this.viewHideEmptySlots;
    }

    /** 屏幕开屏时读它初始化「折叠虚拟样板」开关，保证重开回到上次的值。 */
    boolean viewCollapseVirtualPatterns() {
        return this.viewCollapseVirtualPatterns;
    }

    /** 屏幕开屏时读它恢复选中的那张盘（0 = 未选中）。 */
    long viewSelectedSerial() {
        return this.viewSelectedSerial;
    }

    /** 服务端最近一次筛清单用的显示模式；屏幕拿它回显按钮图标。 */
    ShowPatternProviders shownProviders() {
        return shownProviders;
    }

    List<DiskHostListPayload.HostGroup> getHostList() {
        return hostList;
    }

    void receiveDiskContents(List<DiskContentPayload.Entry> entries) {
        for (var entry : entries) {
            diskContents.put(entry.serial(), List.copyOf(entry.patterns()));
        }
        // 清单里已经没有的盘丢掉缓存，否则一张被换走的盘会一直留着旧内容。
        var live = new LongOpenHashSet();
        for (var group : hostList) {
            for (var disk : group.disks()) {
                live.add(disk.serial());
            }
        }
        diskContents.keySet().removeIf(serial -> !live.contains(serial));
    }

    /** @return the cached patterns of {@code serial}, or {@code null} when the server has not sent them yet. */
    @Nullable
    List<ItemStack> getDiskContents(long serial) {
        return diskContents.get(serial);
    }

    /**
     * 客户端：把显示状态编成六元组回传。格式与 {@link #decodeViewState} 配对，不能改。
     *
     * <p>第 6 段「折叠虚拟样板」是后加的，只追加在末尾：服务端解旧口径的四/五元组时按缺段处理。</p>
     */
    static String encodeViewState(boolean hideEmptySlots, long selectedSerial,
            DiskEncodingLogic.SearchScope searchScope, boolean naturalSort,
            ShowPatternProviders shownProviders, boolean collapseVirtualPatterns) {
        return hideEmptySlots + ";" + selectedSerial + ";" + searchScope.name() + ";" + naturalSort
                + ";" + shownProviders.name() + ";" + collapseVirtualPatterns;
    }

    /**
     * 服务端侧：解析 {@link PatternDiskManagementTermMenu#sendViewStateToServer} 发来的六元组；
     * 旧口径的四元组（无显示模式）、五元组（无折叠开关）照样收。
     *
     * @param current         服务端当前显示档：四元组或第 5 段是坏值时沿用它，不退回写死的默认档
     * @param currentCollapse 服务端当前「折叠虚拟样板」值：五元组（缺第 6 段）或该段是坏值时沿用它
     */
    static @Nullable ViewState decodeViewState(String encoded, ShowPatternProviders current,
            boolean currentCollapse) {
        var parts = encoded.split(";", -1);
        if (parts.length < 4) {
            return null;
        }
        var scope = DiskEncodingLogic.SearchScope.BOTH;
        try {
            scope = DiskEncodingLogic.SearchScope.valueOf(parts[2]);
        } catch (IllegalArgumentException ignored) {
            // 档位名读不回来就用默认，不让一个坏值把状态卡死。
        }
        long serial = 0L;
        try {
            serial = Long.parseLong(parts[1]);
        } catch (NumberFormatException ignored) {
            // 同上。
        }
        // 旧口径的四元组（或第 5 段是坏值）一律**沿用当前档**，不要退回写死的默认档——
        // 否则混版时每一条视图动作都会把玩家已选的 ALL / NOT_FULL 静默重置成 VISIBLE。
        var shownProviders = current;
        if (parts.length > 4) {
            try {
                shownProviders = ShowPatternProviders.valueOf(parts[4]);
            } catch (IllegalArgumentException ignored) {
                // 读不回来就继续沿用当前档。
            }
        }
        // 第 6 段同理：缺段或坏值沿用服务端现值，不让旧客户端的一次视图动作重置玩家已开的折叠。
        var collapseVirtualPatterns = currentCollapse;
        if (parts.length > 5 && (parts[5].equalsIgnoreCase("true") || parts[5].equalsIgnoreCase("false"))) {
            collapseVirtualPatterns = Boolean.parseBoolean(parts[5]);
        }
        return new ViewState(Boolean.parseBoolean(parts[0]), serial, scope,
                Boolean.parseBoolean(parts[3]), shownProviders, collapseVirtualPatterns);
    }
}
