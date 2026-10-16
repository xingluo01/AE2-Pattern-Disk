package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.minecraft.world.item.ItemStack;

import io.github.lounode.ae2pattern.network.CellHostListPayload.CellSlot;
import io.github.lounode.ae2pattern.network.CellHostListPayload.HostGroup;

/** 元件表的快照与差量：拓扑改变发全表，原有格位的内容或占用度改变只发那些格。 */
public final class CellHostList {
    private CellHostList() {}

    private record Address(String host, int slot) {
        Address(CellSlot cell) { this(cell.hostKey(), cell.hostSlot()); }
    }

    public static boolean sameLayout(List<HostGroup> before, List<HostGroup> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i);
            var b = after.get(i);
            if (!a.key().equals(b.key()) || !a.name().equals(b.name()) || !a.priority().equals(b.priority())
                    || !ItemStack.matches(a.icon(), b.icon()) || a.cells().size() != b.cells().size()) return false;
            for (int j = 0; j < a.cells().size(); j++) {
                var left = a.cells().get(j);
                var right = b.cells().get(j);
                if (!new Address(left).equals(new Address(right)) || !left.leaderKey().equals(right.leaderKey())) return false;
            }
        }
        return true;
    }

    /** 调用前已确认布局一致。 */
    public static List<CellSlot> changes(List<HostGroup> before, List<HostGroup> after) {
        var result = new ArrayList<CellSlot>();
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i).cells();
            var b = after.get(i).cells();
            for (int j = 0; j < a.size(); j++) {
                var left = a.get(j);
                var right = b.get(j);
                if (Float.compare(left.typeFill(), right.typeFill()) != 0
                        || Float.compare(left.byteFill(), right.byteFill()) != 0
                        || !ItemStack.matches(left.stack(), right.stack())) result.add(right);
            }
        }
        return List.copyOf(result);
    }

    public static List<HostGroup> apply(List<HostGroup> before, List<CellSlot> changes) {
        var byAddress = new HashMap<Address, CellSlot>();
        for (var cell : changes) byAddress.put(new Address(cell), cell);
        var result = new ArrayList<HostGroup>(before.size());
        for (var group : before) {
            var cells = new ArrayList<CellSlot>(group.cells().size());
            boolean changed = false;
            for (var cell : group.cells()) {
                var replacement = byAddress.get(new Address(cell));
                if (replacement != null && replacement.leaderKey().equals(cell.leaderKey())) {
                    cells.add(replacement);
                    changed = true;
                } else cells.add(cell);
            }
            result.add(changed ? new HostGroup(group.key(), group.name(), group.icon(), group.priority(), List.copyOf(cells)) : group);
        }
        return List.copyOf(result);
    }

    /** 服务端栈会原地修改组件；镜像必须拷贝，才能检测后续取放和内容变化。 */
    public static List<HostGroup> snapshot(List<HostGroup> groups) {
        return groups.stream().map(group -> new HostGroup(group.key(), group.name(), group.icon().copy(), group.priority(),
                group.cells().stream().map(cell -> new CellSlot(cell.stack().copy(), cell.typeFill(), cell.byteFill(),
                        cell.hostKey(), cell.hostSlot(), cell.leaderKey())).toList())).toList();
    }
}
