package io.github.lounode.ae2pattern.common.menu.cell;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 光标往格子里放元件时的份数账：拒收一物不吞，收下只交一份。
 *
 * <p>宿主收不收由 {@code CellFormatGateTest} 钉，这里只钉搬运侧的账，所以把 {@code CellDriveScanner} 的写入
 * 打成桩。光标用一叠多份的替身栈：元件本身不可堆叠，但整栈交出去时多出来的份数会静默消失，账要对得上。</p>
 */
class CellTermMovesTest {

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

    private static CellManagementTermMenu menuHolding(ItemStack carried, Object owner) {
        var menu = mock(CellManagementTermMenu.class);
        when(menu.getCarried()).thenReturn(carried);
        when(menu.ownerOf("host")).thenReturn(owner);
        return menu;
    }

    @Test
    void refusalLeavesTheCarriedStackIntact() {
        var carried = new ItemStack(Items.DIAMOND, 4);
        var menu = menuHolding(carried, new Object());

        try (var scanner = mockStatic(CellDriveScanner.class)) {
            scanner.when(() -> CellDriveScanner.insertCell(any(), eq(0), any())).thenReturn(false);
            new CellTermMoves(menu).putCell("host", 0);
        }

        assertEquals(4, carried.getCount(), "拒收不许消耗光标上的元件");
        verify(menu, never()).setCarried(any());
        verify(menu, never()).afterCellMoved(any());
    }

    @Test
    void successHandsOverExactlyOneAndKeepsTheRest() {
        var carried = new ItemStack(Items.DIAMOND, 4);
        Object owner = new Object();
        var menu = menuHolding(carried, owner);

        try (var scanner = mockStatic(CellDriveScanner.class)) {
            scanner.when(() -> CellDriveScanner.insertCell(eq(owner), eq(0), any())).thenReturn(true);
            new CellTermMoves(menu).putCell("host", 0);

            var handed = ArgumentCaptor.forClass(ItemStack.class);
            scanner.verify(() -> CellDriveScanner.insertCell(eq(owner), eq(0), handed.capture()));
            assertEquals(1, handed.getValue().getCount(), "整栈交过去会让多出来的份数静默消失");
        }

        var rest = ArgumentCaptor.forClass(ItemStack.class);
        verify(menu).setCarried(rest.capture());
        assertEquals(3, rest.getValue().getCount());
        assertEquals(carried.getItem(), rest.getValue().getItem());
        assertEquals(4, carried.getCount(), "原栈不该被就地改写");
        verify(menu).afterCellMoved(owner);
    }

    @Test
    void missingOwnerDoesNotConsumeTheCarriedStack() {
        var carried = new ItemStack(Items.DIAMOND, 4);
        var menu = menuHolding(carried, null);

        try (var scanner = mockStatic(CellDriveScanner.class)) {
            new CellTermMoves(menu).putCell("host", 0);
            scanner.verifyNoInteractions();
        }

        assertEquals(4, carried.getCount());
        verify(menu, never()).setCarried(any());
    }

    @Test
    void emptyCarriedDoesNotTouchTheHost() {
        var menu = menuHolding(ItemStack.EMPTY, new Object());

        new CellTermMoves(menu).putCell("host", 0);

        verify(menu, never()).ownerOf(any());
        verify(menu, never()).setCarried(any());
    }
}