package io.github.lounode.ae2pattern.common.menu.cell;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.LoadingModList;

import io.github.lounode.ae2pattern.network.CellHostListPayload.CellSlot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * ECO 主机下矩阵座的排列顺序：按 {@code BlockPos.asLong()} 数值升序，与上游界面分配槽位的顺序一致。
 *
 * <p>钉住两层：顺序键优先取 ECO 界面自己的槽位序号（拿不到才退坐标数值序），以及最终按数值升序
 * 排格子。旧实现拿 hostKey 字符串字典序排会把 {@code @100} 排在 {@code @20} 前，坐标数值序又与
 * 上游主机界面的行列布局整批相反，这里两条都不再成立。</p>
 */
class SubsystemCellOrderTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        try (var loading = mockStatic(LoadingModList.class)) {
            var mods = mock(LoadingModList.class);
            when(mods.getModFiles()).thenReturn(List.of());
            loading.when(LoadingModList::get).thenReturn(mods);
            Bootstrap.bootStrap();
        }
    }

    private static CellSlot slot(String hostKey, int hostSlot) {
        return new CellSlot(ItemStack.EMPTY, 0f, 0f, hostKey, hostSlot, "host@0");
    }

    private static List<String> keys(List<CellSlot> cells) {
        return cells.stream().map(CellSlot::hostKey).toList();
    }

    @Test
    void numericOrderBeatsStringOrder() {
        var cells = List.of(slot("dim@100", 0), slot("dim@20", 0), slot("dim@3", 0));
        assertEquals(List.of("dim@3", "dim@20", "dim@100"),
                keys(CellDriveScanner.orderSubsystemCells(cells, List.of(100L, 20L, 3L))));
    }

    @Test
    void negativeCoordinatesStayBeforePositive() {
        var cells = List.of(slot("dim@5", 0), slot("dim@-9", 0), slot("dim@0", 0));
        assertEquals(List.of("dim@-9", "dim@0", "dim@5"),
                keys(CellDriveScanner.orderSubsystemCells(cells, List.of(5L, -9L, 0L))));
    }

    @Test
    void unknownPositionFallsToTheEndAndTiesUseSlotThenKey() {
        var cells = List.of(slot("dim@b", 2), slot("dim@unknown", 0),
                slot("dim@a", 0), slot("dim@0", 0));
        var ordered = CellDriveScanner.orderSubsystemCells(cells,
                List.of(1L, Long.MAX_VALUE, 1L, 0L));
        assertEquals(List.of("dim@0", "dim@a", "dim@b", "dim@unknown"), keys(ordered));
        assertEquals(List.of(0, 0, 2, 0), ordered.stream().map(CellSlot::hostSlot).toList());
    }

    @Test
    void missingPositionKeyDoesNotDropCells() {
        var cells = List.of(slot("dim@2", 0), slot("dim@1", 0));
        var ordered = CellDriveScanner.orderSubsystemCells(cells, List.of(7L));
        assertEquals(2, ordered.size());
        assertEquals(List.of("dim@2", "dim@1"), keys(ordered));
    }

    @Test
    void uiSlotOrderWinsOverCoordinateOrder() {
        var hostKeys = List.of("dim@a", "dim@b", "dim@c");
        var ui = Map.of("dim@a", 2L, "dim@b", 0L, "dim@c", 1L);
        assertEquals(List.of(2L, 0L, 1L),
                CellDriveScanner.subsystemOrderKeys(hostKeys, ui, List.of(10L, 20L, 30L)));
    }

    @Test
    void missingUiEntryFallsToTheEnd() {
        assertEquals(List.of(0L, Long.MAX_VALUE),
                CellDriveScanner.subsystemOrderKeys(List.of("dim@a", "dim@b"),
                        Map.of("dim@a", 0L), List.of(10L, 20L)));
    }

    @Test
    void noUiOrderFallsBackToCoordinates() {
        assertEquals(List.of(30L, 10L),
                CellDriveScanner.subsystemOrderKeys(List.of("dim@a", "dim@b"),
                        Map.of(), List.of(30L, 10L)));
    }

    @Test
    void uiOrderDrivesTheVisibleCells() {
        var cells = List.of(slot("dim@a", 0), slot("dim@b", 0), slot("dim@c", 0));
        var order = CellDriveScanner.subsystemOrderKeys(
                List.of("dim@a", "dim@b", "dim@c"),
                Map.of("dim@a", 2L, "dim@b", 0L, "dim@c", 1L),
                List.of(10L, 20L, 30L));
        assertEquals(List.of("dim@b", "dim@c", "dim@a"),
                keys(CellDriveScanner.orderSubsystemCells(cells, order)));
    }

    @Test
    void columnMajorOrderTurnsRowsIntoColumns() {
        assertEquals(List.of(0L, 3L, 1L, 4L, 2L, 5L),
                java.util.stream.IntStream.range(0, 6)
                        .mapToObj(i -> CellDriveScanner.columnMajorOrder(i, 2)).toList());
    }

    @Test
    void uiReadingOrderPlacesFirstColumnFirst() {
        var names = List.of("r0c0", "r0c1", "r1c0", "r1c1", "r2c0", "r2c1");
        var ui = Map.of("r0c0", 0L, "r0c1", 3L, "r1c0", 1L,
                "r1c1", 4L, "r2c0", 2L, "r2c1", 5L);
        var cells = names.stream().map(name -> slot(name, 0)).toList();
        var order = CellDriveScanner.subsystemOrderKeys(names, ui,
                List.of(10L, 20L, 30L, 40L, 50L, 60L));
        assertEquals(List.of("r0c0", "r1c0", "r2c0", "r0c1", "r1c1", "r2c1"),
                keys(CellDriveScanner.orderSubsystemCells(cells, order)));
    }
}
