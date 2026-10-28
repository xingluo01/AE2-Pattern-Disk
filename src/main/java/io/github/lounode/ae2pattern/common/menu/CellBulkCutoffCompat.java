package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEKey;

/**
 * 元件编码槽里「大宗元件压缩截断」的中立入口。
 *
 * <p>菜单与屏幕在总是加载的源集里，不能出现邻居模组的类型名。具体实现由
 * {@code integration/neoecoae} 在启动时注册：新版 ECO 有公开的压缩链查询 API 时注册 API 适配器，
 * 旧版只能靠公开方法反射遍历时注册反射适配器；ECO 缺席时一个都不注册，终端不显示相关按钮。</p>
 *
 * <p><b>一张元件只开一个句柄</b>：大宗元件每解析一次库存，都要把它的压缩账本整个读一遍。聚合要看网络里
 * 每台矩阵的名单、格数与库存，逐样另开会把同一张元件解析三四遍——都走 {@link Site}。</p>
 *
 * <p><b>读走副本</b>：查链与查库存在 ECO 那边不是纯读——生效截断的查询会顺带做历史账户恢复并落盘。
 * 界面在客户端算标记，读必须落在 {@code stack.copy()} 上；写才落在真元件上。</p>
 */
public final class CellBulkCutoffCompat {

    public interface Adapter {
        /**
         * 打开这张元件的句柄。
         *
         * @return 不是该适配器认识的大宗元件时 null
         */
        @Nullable
        Site open(ItemStack cell);
    }

    /** 一张大宗元件的视图：读走副本，写落真元件；写过的内容下一次读就能看见。 */
    public interface Site {

        /** 标记，按配置槽顺序、按压缩链去重（界面用）。 */
        List<Marker> markers();

        /** 同一份名单，但<b>不</b>按链去重：每个非空标记格一条，带着它所属的链。 */
        List<Marker> rawMarkers();

        /** 现在能标多少条（含空位），随已装的扩容卡变。 */
        int capacity();

        /** 此刻实际存着哪些物品键（含压缩后的各等级）：一次读全表，别逐链问。 */
        Set<AEKey> storedKeys();

        /**
         * 把某一格的标记换成链上的上/下一级。
         *
         * @return 是否真的调了；调用方负责落盘与广播
         */
        boolean cycleCutoff(int markerSlot, int delta);

        /**
         * 整份重写标记：前 {@code markers.size()} 格依次写成这些物品，其余清空。
         *
         * @return 是否真的改了格；没改时调用方可以省下落盘与广播
         */
        boolean setMarkers(List<ItemStack> markers);
    }

    /**
     * 一个可切换的标记：配置槽、当前标记物、这条链是否真的可压缩，以及它所属的整条链。
     *
     * <p>{@code chain} 用集合而不是列表：走链的起点随元件记着的截断点变，只有成员集合是稳定的身份，
     * 跨矩阵判断「这两枚标记是同一条链」靠它。</p>
     */
    public record Marker(int slot, ItemStack item, boolean compressible, Set<AEKey> chain) {
    }

    private static volatile Adapter adapter;

    private CellBulkCutoffCompat() {
    }

    public static void register(Adapter value) {
        adapter = value;
    }

    public static boolean isAvailable() {
        return adapter != null;
    }

    @Nullable
    public static Site open(ItemStack cell) {
        Adapter current = adapter;
        return current == null ? null : current.open(cell);
    }

    /** 一次性读按链去重的名单，给界面用；批量场景自己 {@code open} 一个句柄。 */
    @Nullable
    public static List<Marker> markers(ItemStack cell) {
        Site site = open(cell);
        return site == null ? null : site.markers();
    }

    /** 去重后的名单：每个链集合只留第一条，顺序不变。 */
    public static List<Marker> dedupeByChain(List<Marker> markers) {
        var seen = new HashSet<Set<AEKey>>();
        var result = new ArrayList<Marker>(markers.size());
        for (var marker : markers) {
            if (seen.add(marker.chain())) {
                result.add(marker);
            }
        }
        return List.copyOf(result);
    }
}
