package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.DiskSlotVersions;
import io.github.lounode.ae2pattern.network.DiskListPayload;

/**
 * 磁盘索引：网格里每个磁盘槽 ↔ 一个稳定的序列号。
 *
 * <p>客户端拿序列号指代「它正在操作的那张盘」，所以序列号必须扛得住刷新——按<b>取值身份</b>（宿主位置 +
 * 身份盐 + 槽位）沿用上一次的号，而不是每轮重新分配；否则一次重发就会让飞行中的动作指向别的盘。身份用值
 * 而不是适配器对象，是因为有些宿主（NEO ECO 集成）每次收集都新建适配器，按对象比较会静默失配。</p>
 *
 * <p>刷新前先用 {@link DiskSlotVersions} 比一次快照：宿主名单与各槽内容都没动就直接跳过整轮重建——重建会
 * 复制每一个磁盘物品，而菜单每 tick 都要问一次。快照能不能按引用比、以及它对什么变化看不见，见那个类。</p>
 */
public final class DiskIndex {

    /** 一个磁盘槽：哪个宿主、宿主库存里的哪个槽。 */
    public record DiskRef(IPatternDiskHost host, int slot) {}

    /**
     * 磁盘槽的取值身份：位置 + 身份盐 + 槽位，与 {@link DiskSlotVersions} 的宿主身份同一口径。
     */
    private record DiskSlotKey(BlockPos pos, int salt, int slot) {}

    /** 序列号 → 磁盘槽（仅服务端使用）。 */
    private final Long2ObjectOpenHashMap<DiskRef> refs = new Long2ObjectOpenHashMap<>();

    /** 磁盘槽 → 它上次用的序列号，好让重发沿用同一个号。 */
    private final Map<DiskSlotKey, Long> serials = new HashMap<>();

    private static long nextSerial = Long.MIN_VALUE;

    /** 上次同步时的内容快照；{@code null} 表示还没同步过。 */
    private List<Object> lastVersions;

    /**
     * 重建索引并给出要推给客户端的清单。
     *
     * @param force 即使快照说没变也重建。客户端在读某张盘的组件之前会要这个，因为刚写入的标记可能还没传到它那边
     * @return 新的清单；快照未变且未强制时返回 {@code null}，调用方据此跳过整轮重发
     */
    public @Nullable List<DiskListPayload.DiskEntry> rebuild(List<IPatternDiskHost> hosts, boolean force) {
        var slots = new ArrayList<DiskRef>();
        for (var host : hosts) {
            collectHostDisks(host, slots);
        }

        // 宿主名单也进快照：新插一台还没插盘的供应器、或把最后一盘抽走，「磁盘槽」那部分一点没变，
        // 但表要跟着变（管理终端要能看见没插盘的机器以及它还剩多少空槽）。
        var versions = DiskSlotVersions.ofGrid(hosts);
        if (!force && DiskSlotVersions.sameAs(lastVersions, versions)) {
            return null; // unchanged: skip full resend
        }
        lastVersions = versions;

        var previousSerials = new HashMap<>(serials);
        serials.clear();
        refs.clear();
        var rebuilt = new ArrayList<DiskListPayload.DiskEntry>(slots.size());
        for (var ref : slots) {
            var key = new DiskSlotKey(ref.host().getBlockPos(), ref.host().getIdentitySalt(), ref.slot());
            var serial = previousSerials.get(key);
            if (serial == null) {
                serial = nextSerial++;
            }
            serials.put(key, serial);
            refs.put(serial, ref);
            var stack = ref.host().getDiskInventory().getStackInSlot(ref.slot());
            rebuilt.add(new DiskListPayload.DiskEntry(serial, stack.copy()));
        }
        return rebuilt;
    }

    /** 序列号对应的磁盘槽；未知返回 {@code null}。 */
    public @Nullable DiskRef refOf(long serial) {
        return refs.get(serial);
    }

    /** 序列号对应的宿主；未知返回 {@code null}。 */
    public @Nullable IPatternDiskHost hostOf(long serial) {
        var ref = refs.get(serial);
        return ref == null ? null : ref.host();
    }

    /** 序列号在宿主库存里的槽位；未知返回 -1。 */
    public int slotOf(long serial) {
        var ref = refs.get(serial);
        return ref == null ? -1 : ref.slot();
    }

    /** Appends every pattern disk currently sitting in {@code host}'s disk inventory. */
    public static void collectHostDisks(IPatternDiskHost host, List<DiskRef> out) {
        var inv = host.getDiskInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) {
                continue;
            }
            out.add(new DiskRef(host, i));
        }
    }
}
