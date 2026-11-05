package io.github.lounode.ae2pattern.common.menu.pattern;

import org.junit.jupiter.api.Test;

import appeng.api.config.ShowPatternProviders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守「视图状态六元组」的编解码：第 6 段（折叠虚拟样板）只追加在末尾，旧口径的四/五元组服务端照样收，
 * 且缺段时沿用服务端现值——否则混版时旧客户端的一次视图动作会把玩家已开的折叠静默重置掉。
 */
class DiskClientViewStateTest {

    private static final ShowPatternProviders CURRENT = ShowPatternProviders.VISIBLE;

    @Test
    void roundTripCarriesCollapseVirtualPatterns() {
        var encoded = DiskClientView.encodeViewState(false, -42L,
                DiskEncodingLogic.SearchScope.OUTPUT, true, ShowPatternProviders.ALL, true);
        var state = DiskClientView.decodeViewState(encoded, CURRENT, false);
        assertTrue(state.collapseVirtualPatterns());
        assertEquals(ShowPatternProviders.ALL, state.shownProviders());
        assertEquals(-42L, state.selectedSerial());
        assertEquals(DiskEncodingLogic.SearchScope.OUTPUT, state.searchScope());

        var off = DiskClientView.decodeViewState(
                DiskClientView.encodeViewState(true, 0L, DiskEncodingLogic.SearchScope.BOTH, false,
                        CURRENT, false),
                CURRENT, true);
        assertFalse(off.collapseVirtualPatterns());
    }

    @Test
    void legacyFourAndFivePartStatesAreAccepted() {
        var four = DiskClientView.decodeViewState("true;-7;BOTH;true", CURRENT, true);
        assertTrue(four.hideEmptySlots());
        assertTrue(four.collapseVirtualPatterns(), "四元组缺第 6 段时沿用服务端现值");
        assertEquals(CURRENT, four.shownProviders(), "四元组缺第 5 段时沿用服务端当前档");

        var five = DiskClientView.decodeViewState("true;-7;BOTH;true;ALL", CURRENT, true);
        assertTrue(five.collapseVirtualPatterns(), "五元组缺第 6 段时沿用服务端现值");
        assertEquals(ShowPatternProviders.ALL, five.shownProviders());
    }

    @Test
    void badSixthPartFallsBackToServerValue() {
        var state = DiskClientView.decodeViewState("true;-7;BOTH;true;VISIBLE;garbage", CURRENT, true);
        assertTrue(state.collapseVirtualPatterns(), "第 6 段是坏值时沿用服务端现值");
        assertNull(DiskClientView.decodeViewState("true;-7", CURRENT, true), "不足四段直接丢弃");
    }
}
