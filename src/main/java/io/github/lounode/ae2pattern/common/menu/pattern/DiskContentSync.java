package io.github.lounode.ae2pattern.common.menu.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongToIntFunction;

import net.minecraft.world.item.ItemStack;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.network.DiskContentPayload;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * 服务端侧：客户端当前可见的磁盘集合与按需内容推送。
 *
 * <p>一块盘能装 1024 张样板，清单里不带内容：客户端用 {@link VisibleDisksPayload} 报它在看哪些序列号，
 * 本类只推这些盘、且用指纹跳过内容没变的盘。状态（{@code visibleDisks} 与 {@code sentContentFingerprints}）
 * 整体归本类；菜单只通过三个回调提供「按序列号找宿主 / 找槽位 / 推包给客户端」，本类不持有菜单。</p>
 */
final class DiskContentSync {

    /** 服务端侧：客户端当前可见的磁盘，由 {@link VisibleDisksPayload} 上报。 */
    private final LongSet visibleDisks = new LongOpenHashSet();

    /** 服务端侧：上次推送内容时的指纹，用来避免滚动一下就把整盘重发一遍。 */
    private final Long2IntOpenHashMap sentContentFingerprints = new Long2IntOpenHashMap();

    private final LongFunction<IPatternDiskHost> hostOf;
    private final LongToIntFunction slotOf;
    private final Consumer<DiskContentPayload> sendToClient;

    DiskContentSync(LongFunction<IPatternDiskHost> hostOf, LongToIntFunction slotOf,
            Consumer<DiskContentPayload> sendToClient) {
        this.hostOf = hostOf;
        this.slotOf = slotOf;
        this.sendToClient = sendToClient;
    }

    void receiveVisibleDisks(List<Long> serials) {
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

    /** 清单变了（换盘、加机器）：内容指纹作废，免得新盘拿旧内容顶数。 */
    void clearFingerprints() {
        sentContentFingerprints.clear();
    }

    void syncVisibleDiskContents() {
        if (visibleDisks.isEmpty()) {
            return;
        }

        var entries = new ArrayList<DiskContentPayload.Entry>();
        for (var iterator = visibleDisks.iterator(); iterator.hasNext();) {
            long serial = iterator.nextLong();
            var host = hostOf.apply(serial);
            int slot = slotOf.applyAsInt(serial);
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
            sendToClient.accept(new DiskContentPayload(entries));
        }
    }

    private static int contentFingerprint(List<ItemStack> patterns) {
        int hash = patterns.size();
        for (var stack : patterns) {
            hash = hash * 31 + ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount();
        }
        return hash;
    }
}
