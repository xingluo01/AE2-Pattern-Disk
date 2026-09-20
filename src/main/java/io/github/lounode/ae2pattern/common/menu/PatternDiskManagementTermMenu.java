package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import appeng.menu.implementations.MenuTypeBuilder;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.part.PatternDiskManagementTerminalPart;
import io.github.lounode.ae2pattern.network.DiskContentPayload;
import io.github.lounode.ae2pattern.network.DiskHostListPayload;
import io.github.lounode.ae2pattern.network.DiskListPayload;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * The pattern disk management terminal's menu: the encoding terminal's menu plus the data its table needs.
 *
 * <p>The parent already does the hard part - finding every disk host on the grid, assigning stable serials and
 * pushing a flat list. On top of that this menu adds two things:</p>
 *
 * <ul>
 *   <li><b>Grouping.</b> {@link #onDiskListRebuilt} turns the flat list into
 *       {@link DiskHostListPayload.HostGroup}s using the serial → host mapping the parent keeps, so the table
 *       can print a header per machine without the server having to send host objects.</li>
 *   <li><b>Contents on demand.</b> A disk holds up to 1024 patterns, and the table draws many disks at once,
 *       so contents are not part of the list. The client reports which serials it is showing
 *       ({@link VisibleDisksPayload}) and the server pushes those disks' patterns, skipping any disk whose
 *       contents have not changed since the last push ({@link DiskContentPayload}).</li>
 * </ul>
 *
 * <p>Everything else - marks, renaming, the encoding area, the auto-write target - is inherited unchanged, so
 * both terminals act on disks exactly the same way.</p>
 */
public class PatternDiskManagementTermMenu extends PatternDiskEncodingTermMenu {

    // 不可用 build()：理由同父类，单通道注册。
    public static final MenuType<PatternDiskManagementTermMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskManagementTermMenu::new, PatternDiskManagementTerminalPart.class)
            .buildUnregistered(ResourceLocation.parse("ae2_pattern_disk:pattern_disk_management_terminal"));

    /** 客户端侧：服务端推送的分组清单（Screen 每帧读）。 */
    private List<DiskHostListPayload.HostGroup> hostList = List.of();

    /** 客户端侧：按序列号缓存的磁盘内容（Screen 每帧读）。 */
    private final Long2ObjectOpenHashMap<List<ItemStack>> diskContents = new Long2ObjectOpenHashMap<>();

    /** 服务端侧：客户端当前可见的磁盘，由 {@link VisibleDisksPayload} 上报。 */
    private final LongSet visibleDisks = new LongOpenHashSet();

    /** 服务端侧：上次推送内容时的指纹，用来避免滚动一下就把整盘重发一遍。 */
    private final Long2IntOpenHashMap sentContentFingerprints = new Long2IntOpenHashMap();

    public PatternDiskManagementTermMenu(int id, Inventory ip, PatternDiskManagementTerminalPart host) {
        super(id, ip, host);
    }

    @Override
    protected void onDiskListRebuilt(List<DiskListPayload.DiskEntry> entries) {
        // 父类的扁平列表按「序列号 → 宿主」重新分组：分组键取宿主的方块坐标与身份盐，同一根线缆上的
        // 多块面板靠盐区分，与父类的指纹口径一致。
        var builders = new LinkedHashMap<String, HostBuilder>();
        for (var entry : entries) {
            var host = diskHostOf(entry.serial());
            if (host == null) {
                continue;
            }
            var key = hostKey(host);
            var builder = builders.get(key);
            if (builder == null) {
                builder = new HostBuilder(key, describeHost(host), iconOf(host));
                builders.put(key, builder);
            }
            builder.disks.add(new DiskHostListPayload.Entry(entry.serial(), entry.stack()));
        }

        var groups = new ArrayList<DiskHostListPayload.HostGroup>(builders.size());
        for (var builder : builders.values()) {
            groups.add(new DiskHostListPayload.HostGroup(builder.key, builder.name, builder.icon,
                    List.copyOf(builder.disks)));
        }
        sendPacketToClient(new DiskHostListPayload(groups));

        // 清单变了（换盘、加机器）：内容指纹作废，免得新盘拿旧内容顶数。
        sentContentFingerprints.clear();
        if (!isClientSide()) {
            syncVisibleDiskContents();
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!isClientSide()) {
            syncVisibleDiskContents();
        }
    }

    // ---- 客户端侧：接收 ----

    public void receiveHostList(List<DiskHostListPayload.HostGroup> hosts) {
        this.hostList = List.copyOf(hosts);
    }

    public List<DiskHostListPayload.HostGroup> getHostList() {
        return hostList;
    }

    public void receiveDiskContents(List<DiskContentPayload.Entry> entries) {
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
    public List<ItemStack> getDiskContents(long serial) {
        return diskContents.get(serial);
    }

    // ---- 服务端侧：可见集合与内容推送 ----

    public void receiveVisibleDisks(List<Long> serials) {
        visibleDisks.clear();
        for (long serial : serials) {
            if (visibleDisks.size() >= VisibleDisksPayload.MAX_SERIALS) {
                break;
            }
            visibleDisks.add(serial);
        }
        // 不再可见的盘丢掉指纹：它再回到视野里时要重发一次，而不是拿旧内容顶数。
        sentContentFingerprints.keySet().removeIf(serial -> !visibleDisks.contains(serial));
    }

    private void syncVisibleDiskContents() {
        if (visibleDisks.isEmpty()) {
            return;
        }

        var entries = new ArrayList<DiskContentPayload.Entry>();
        for (var iterator = visibleDisks.iterator(); iterator.hasNext();) {
            long serial = iterator.nextLong();
            var host = diskHostOf(serial);
            int slot = diskSlotOf(serial);
            if (host == null || slot < 0) {
                continue;
            }

            var stack = host.getDiskInventory().getStackInSlot(slot);
            if (!(stack.getItem() instanceof PatternDiskItem diskItem)) {
                continue;
            }

            var patterns = diskItem.contents(stack).patterns();
            int fingerprint = contentFingerprint(patterns);
            int stored = sentContentFingerprints.get(serial);
            if (sentContentFingerprints.containsKey(serial) && stored == fingerprint) {
                continue;
            }
            sentContentFingerprints.put(serial, fingerprint);
            entries.add(new DiskContentPayload.Entry(serial, List.copyOf(patterns)));
        }

        if (!entries.isEmpty()) {
            sendPacketToClient(new DiskContentPayload(entries));
        }
    }

    private static int contentFingerprint(List<ItemStack> patterns) {
        int hash = patterns.size();
        for (var stack : patterns) {
            hash = hash * 31 + ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount();
        }
        return hash;
    }

    // ---- 服务端侧：宿主的分组键、名字与图标 ----

    private static String hostKey(IPatternDiskHost host) {
        return host.getBlockPos().asLong() + "#" + host.getIdentitySalt();
    }

    /**
     * 宿主的显示名：方块自己的名字 + 坐标。
     *
     * <p>{@link IPatternDiskHost} 只说得出位置，说不出自己叫什么，所以名字取自该位置的方块；坐标后缀是为了
     * 同一种机器摆了好几台时能分清是哪一台——组头就靠它区分，光写方块名会看到两行一模一样的标题。</p>
     */
    private String describeHost(IPatternDiskHost host) {
        var pos = host.getBlockPos();
        var level = getPlayer().level();
        var name = level.getBlockState(pos).getBlock().getName().getString();
        return name + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /** 组头图标：方块对应的物品；方块没有物品形态（比如某些线缆面板）时留空，屏幕会退回只画名字。 */
    private ItemStack iconOf(IPatternDiskHost host) {
        var level = getPlayer().level();
        var item = level.getBlockState(host.getBlockPos()).getBlock().asItem();
        if (item == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    /** 分组清单在构造过程中的可变形态，建完就转成不可变的 {@link DiskHostListPayload.HostGroup}。 */
    private static final class HostBuilder {
        private final String key;
        private final String name;
        private final ItemStack icon;
        private final List<DiskHostListPayload.Entry> disks = new ArrayList<>();

        private HostBuilder(String key, String name, ItemStack icon) {
            this.key = key;
            this.name = name;
            this.icon = icon;
        }
    }
}
