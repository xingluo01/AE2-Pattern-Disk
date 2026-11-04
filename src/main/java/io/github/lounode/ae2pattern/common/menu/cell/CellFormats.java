package io.github.lounode.ae2pattern.common.menu.cell;

import net.minecraft.world.item.ItemStack;

import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.IBasicCellItem;

import org.jetbrains.annotations.Nullable;

/**
 * 两种元件格式的判定：<b>AE2 标准驱动器格式</b>与 <b>ECO 家族的存储矩阵</b>。
 *
 * <p>这是「准入闸」的一半——另一半在 {@link CellHostAccess}（问宿主收不收）。两边的判据必须一致：驱动器格式
 * 只收 AE2 的 cell handler 认得的元件，单格元件座只收矩阵；错收一张谁也读不出内容的元件，比多拒一格麻烦得多。</p>
 *
 * <p>集成层可以注册一个 {@link CellFormatProbe} 用上游自己的 handler 回答（ECO 侧就是它的
 * {@code ECOStorageCells.isCellHandled}）；没注册时退回按接口名探——青春版的元件不在我们的编译面上，
 * 只能按名字认。</p>
 */
public final class CellFormats {

    private CellFormats() {
    }

    public static boolean isStorageCell(ItemStack stack) {
        return isDriveFormatCell(stack) || isMatrixFormatCell(stack);
    }

    /**
     * AE2 标准驱动器格式的元件——AE2 自己的 cell handler 认得的那一批。
     *
     * <p>判据与 AE2 驱动器库存的 filter 逐字相同（{@code IBasicCellItem} 或 {@code StorageCells.isCellHandled}），
     * 所以我们的闸门与驱动器自己的闸门不会给出两种答案。</p>
     */
    public static boolean isDriveFormatCell(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof IBasicCellItem || StorageCells.isCellHandled(stack);
    }

    /**
     * ECO 家族认得的存储矩阵。
     *
     * <p>先问集成层注册的 {@link CellFormatProbe}（ECO 侧的回答就是它自己的 {@code ECOStorageCells.isCellHandled}），
     * 没注册时才退回按接口名探——青春版的元件不在我们的编译面上，只能按名字认。两条都不成立就不算矩阵：
     * 多拒一格不丢东西，错收一张谁也读不出内容的元件才是真麻烦。</p>
     */
    public static boolean isMatrixFormatCell(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        CellFormatProbe probe = matrixProbe;
        if (probe != null && probe.isMatrix(stack)) {
            return true;
        }
        // 兜底按接口名：ECO 系元件的 IBasicECOCellItem 继承 AE2 的 ICellWorkbenchItem 与 ECO 的
        // IECOStorageCellItem，连接口与父接口一起比，免得某个元件只继承了其中一条名字。
        return implementsAny(stack.getItem().getClass(), ECO_CELL_INTERFACES);
    }

    /**
     * 「存储矩阵格式」的探针，由 {@code integration/} 层在邻居模组在场时注册进来。
     *
     * <p>本类在总是加载的 {@code main} 源集里，不能出现邻居模组的类型名——缺那个模组时连这个类都加载不了。
     * 注册 {@link PriorityAdapter} 用的是同一套路子。按上游的 handler 名单问比按名字猜准：ECO 搬过包也改过名，
     * 名字匹配整批漏认时，矩阵会被自己的闸门挡在矩阵座外面。</p>
     */
    public interface CellFormatProbe {

        /** 这张栈是不是该家族认得的存储矩阵。 */
        boolean isMatrix(ItemStack stack);
    }

    private static volatile CellFormatProbe matrixProbe;

    /** 由集成层注册一次；重复注册以最后一次为准，传 null 表示撤掉探针（退回按接口名的兜底）。 */
    public static void registerMatrixProbe(@Nullable CellFormatProbe probe) {
        matrixProbe = probe;
    }

    /** ECO 系存储元件的接口名（{@link #isMatrixFormatCell} 的兜底：青春版没有编译期契约，只能按名字比）。 */
    private static final java.util.Set<String> ECO_CELL_INTERFACES = java.util.Set.of(
            "IBasicECOCellItem", "IECOStorageCellItem");

    /** 类及其父类链上有没有名字落在 {@code names} 里的接口（接口的父接口也算）。 */
    private static boolean implementsAny(Class<?> type, java.util.Set<String> names) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (interfaceChainContains(iface, names)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean interfaceChainContains(Class<?> iface, java.util.Set<String> names) {
        if (iface == null) {
            return false;
        }
        if (names.contains(iface.getSimpleName())) {
            return true;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            if (interfaceChainContains(parent, names)) {
                return true;
            }
        }
        return false;
    }

}
