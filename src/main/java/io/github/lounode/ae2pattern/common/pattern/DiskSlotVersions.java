package io.github.lounode.ae2pattern.common.pattern;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;

/**
 * 磁盘槽的「内容版本」快照：把一组磁盘槽压成一条可以逐项引用比较的版本序列，用来回答「磁盘内容自上次
 * 比对以来变了没有」。
 *
 * <p><b>为什么是引用而不是哈希。</b>{@code PatternDiskContents} 是 record，而它的 {@code equals}/
 * {@code hashCode} 会逐个比较槽里的 {@link ItemStack}（物品 + 全部组件）——满盘时那是九千多次比较，
 * 比它要跳过的重建还贵。而磁盘的每一次写入都整体替换那个 record（{@link io.github.lounode.ae2pattern.api.PatternDiskApi}
 * 的写入路径都返回新实例），所以「实例变了」就等于「内容变了」，一次引用比较就够。</p>
 *
 * <p>由此得到一个不对称：把内容改回原样、或原地改一个 {@link ItemStack}，都会让引用比较判定为「变了」，
 * 哈希则未必。多走一次重建是安全方向，漏掉一次才是 bug，所以这个取舍是有意的。原地修改还违反
 * {@code PatternDiskContents} 的契约（读出即用、不得原地改），调用方不得依赖它。</p>
 *
 * <p>宿主身份（位置 + identity salt）也进序列：同一根电缆上的两个面板位置相同、盐不同，只按槽内容比对
 * 会把它们认成同一台；而「新插一台没插盘的供应器」这种变化只体现在宿主名单上，只有身份进了序列才看得见。
 * 宿主在网格里被枚举出来的顺序若不稳，会退化成多同步一次，仍是安全方向。</p>
 *
 * <p>另一处刻意的取舍：快照只看磁盘的<b>内容</b>组件（{@code PatternDiskContents} 实例），不看磁盘物品的
 * 其余组件——在别处给磁盘改名而内容没动时，这里看不出来，列表要等下一次真正的内容变化、或一次强制刷新
 * 才跟上。改名的入口自己会强制刷新，所以受影响的范围仅限「内容没变的旁路改动」。</p>
 */
public final class DiskSlotVersions {

    private DiskSlotVersions() {}

    /**
     * 单台机器的磁盘槽：一槽一项。
     *
     * <p>空槽记 {@code null}；样板磁盘记它的内容实例；其余物品记物品本身（它们不进镜像，物品就是它们的
     * 全部身份）。从未写过的未定型磁盘存不下组件、{@code contents()} 每次调用都会新造一个空实例——它
     * 对镜像没有贡献，与空槽折叠成同一个值，免得它每轮都看起来「变了」。</p>
     */
    public static List<Object> ofInventory(InternalInventory diskInventory) {
        List<Object> versions = new ArrayList<>(diskInventory.size());
        appendSlots(diskInventory, versions);
        return versions;
    }

    /**
     * 网格视角：每个宿主先记一条身份，随后是它的每一个磁盘槽。
     *
     * <p>一次枚举出「哪些机器在场」与「每台机器里有什么」，比两趟分别算（宿主名单一趟、槽内容一趟）
     * 少一遍遍历，也让两者不可能各自得出不同的结论。</p>
     */
    public static List<Object> ofGrid(List<IPatternDiskHost> hosts) {
        List<Object> versions = new ArrayList<>();
        for (IPatternDiskHost host : hosts) {
            versions.add(new HostIdentity(host.getBlockPos(), host.getIdentitySalt()));
            appendSlots(host.getDiskInventory(), versions);
        }
        return versions;
    }

    /**
     * 两份快照是否描述同一批磁盘。
     *
     * <p>{@code previous} 为 {@code null} 表示还没有快照（首次调用），恒判为「不同」以便调用方走一次完整
     * 重建。</p>
     *
     * <p><b>两类元素两种比法，不能一概而论。</b>内容项（{@code PatternDiskContents} 实例）按引用比：它们每次
     * 写入都整体替换，引用就是内容版本，而 {@code equals} 会走遍全部组件，正是这里要避开的那笔账。宿主身份
     * 相反——它每一轮都是新造的 record（{@link HostIdentity}），拿引用比永远不相等，「没变就跳过」这道门会
     * 直接变成摆设；它是值语义，必须按值比。</p>
     */
    public static boolean sameAs(List<Object> previous, List<Object> current) {
        if (previous == null || previous.size() != current.size()) {
            return false;
        }
        for (int i = 0; i < previous.size(); i++) {
            Object before = previous.get(i);
            Object after = current.get(i);
            if (before == after) {
                continue;
            }
            if (before instanceof HostIdentity identity && identity.equals(after)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static void appendSlots(InternalInventory diskInventory, List<Object> versions) {
        for (int i = 0; i < diskInventory.size(); i++) {
            ItemStack stack = diskInventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                versions.add(null);
            } else if (stack.getItem() instanceof PatternDiskItem disk) {
                var contents = disk.contents(stack);
                versions.add(contents.isEmpty() ? null : contents);
            } else {
                versions.add(stack.getItem());
            }
        }
    }

    /** 宿主身份：位置 + 盐（同一根电缆上的两个面板位置相同、盐不同）。值语义，按 record 比较。 */
    private record HostIdentity(BlockPos pos, int salt) {}
}
