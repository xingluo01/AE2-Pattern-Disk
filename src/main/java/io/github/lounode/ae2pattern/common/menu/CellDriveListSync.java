package io.github.lounode.ae2pattern.common.menu;

import java.util.List;

import io.github.lounode.ae2pattern.network.CellHostListPayload;
import io.github.lounode.ae2pattern.network.CellHostUpdatePayload;

/**
 * 驱动器清单的同步：服务端定期扫网络，把「一台驱动器下有哪些元件格」推给客户端；客户端那份由这里存着。
 *
 * <p>布局没变时只发改动（{@link CellHostList#changes}），变了才整份重发——表格每帧都在读这份数据，整份重发
 * 会让滚动条与光标位置发抖。开界面与本地取放会绕过节拍间隔，其他玩家/自动化的变化仍按
 * {@link #INTERVAL_TICKS} 定期检查。</p>
 */
final class CellDriveListSync {

    private static final int INTERVAL_TICKS = 10;

    private final CellManagementTermMenu menu;

    /** 服务端最近一次推的清单（客户端侧即当前视图）。 */
    private List<CellHostListPayload.HostGroup> hostList = List.of();

    private List<CellHostListPayload.HostGroup> lastPushed = List.of();
    private final CellUsageCache usageCache = new CellUsageCache();
    private boolean sentOnce;
    private boolean dirty = true;
    private int cooldown;

    CellDriveListSync(CellManagementTermMenu menu) {
        this.menu = menu;
    }

    /** 本地取放改动了网络里的元件：下一次同步必须重扫。 */
    void dirty() {
        dirty = true;
    }

    void sync() {
        if (sentOnce && !dirty && --cooldown > 0) {
            return;
        }
        var grid = menu.gridOrNull();
        if (grid == null) {
            return;
        }
        usageCache.beginScan(menu.getPlayer().level().getGameTime(), sentOnce && !dirty);
        var groups = toGroups(CellDriveScanner.scan(grid, usageCache));
        if (!sentOnce || !CellHostList.sameLayout(lastPushed, groups)) {
            menu.sendToClient(new CellHostListPayload(groups));
            lastPushed = CellHostList.snapshot(groups);
        } else {
            var changes = CellHostList.changes(lastPushed, groups);
            if (!changes.isEmpty()) {
                menu.sendToClient(new CellHostUpdatePayload(menu.containerId, changes));
                lastPushed = CellHostList.snapshot(groups);
            }
        }
        sentOnce = true;
        dirty = false;
        cooldown = INTERVAL_TICKS;
    }

    private static List<CellHostListPayload.HostGroup> toGroups(List<CellDriveScanner.DriveEntry> entries) {
        return entries.stream().map(entry -> new CellHostListPayload.HostGroup(entry.key(), entry.name(),
                entry.icon(), entry.priority(), List.copyOf(entry.cells()))).toList();
    }

    List<CellHostListPayload.HostGroup> getDriveList() {
        return hostList;
    }

    void setDriveList(List<CellHostListPayload.HostGroup> groups) {
        this.hostList = List.copyOf(groups);
    }

    void updateDriveList(List<CellHostListPayload.CellSlot> changes) {
        this.hostList = CellHostList.apply(this.hostList, changes);
    }
}
