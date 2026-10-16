package io.github.lounode.ae2pattern.common.menu;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.registries.RegistryManager;

import io.github.lounode.ae2pattern.network.CellHostListPayload.CellSlot;
import io.github.lounode.ae2pattern.network.CellHostListPayload.HostGroup;
import io.github.lounode.ae2pattern.network.CellHostUpdatePayload;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CellHostListTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        try (var loading = mockStatic(LoadingModList.class)) {
            var mods = mock(LoadingModList.class);
            when(mods.getModFiles()).thenReturn(List.of());
            loading.when(LoadingModList::get).thenReturn(mods);
            Bootstrap.bootStrap();
        }
    }

    private static CellSlot cell(String host, int slot, ItemStack stack, float fill) {
        return new CellSlot(stack, fill, fill, host, slot, host);
    }

    private static List<HostGroup> group(CellSlot... cells) {
        return List.of(new HostGroup("drive@0", "drive", new ItemStack(Items.CHEST), "0", List.of(cells)));
    }

    @Test void unchangedStackCopiesProduceNoUpdate() {
        var before = group(cell("a", 0, new ItemStack(Items.STONE), .5f));
        var after = CellHostList.snapshot(before);
        assertTrue(CellHostList.sameLayout(before, after));
        assertTrue(CellHostList.changes(before, after).isEmpty());
    }

    @Test void takingCellUpdatesOnlyItsPhysicalSlot() {
        var before = group(cell("a", 0, new ItemStack(Items.STONE), .5f),
                cell("b", 0, new ItemStack(Items.DIRT), .25f));
        var after = group(cell("a", 0, ItemStack.EMPTY, 0), before.getFirst().cells().get(1));
        assertTrue(CellHostList.sameLayout(before, after));
        var changes = CellHostList.changes(before, after);
        assertEquals(1, changes.size());
        var applied = CellHostList.apply(before, changes);
        assertTrue(applied.getFirst().cells().getFirst().stack().isEmpty());
        assertSame(before.getFirst().cells().get(1), applied.getFirst().cells().get(1));
    }

    @Test void insertingCellAndUsageOnlyUpdatesRoundTrip() {
        var before = group(cell("a", 0, ItemStack.EMPTY, 0));
        var inserted = group(cell("a", 0, new ItemStack(Items.STONE), .5f));
        var applied = CellHostList.apply(before, CellHostList.changes(before, inserted));
        assertTrue(CellHostList.changes(applied, inserted).isEmpty());
        var used = group(cell("a", 0, inserted.getFirst().cells().getFirst().stack(), .75f));
        assertEquals(1, CellHostList.changes(applied, used).size());
    }

    @Test void snapshotDetectsInPlaceStackChanges() {
        var stack = new ItemStack(Items.STONE);
        var live = group(cell("a", 0, stack, 0));
        var snapshot = CellHostList.snapshot(live);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        assertEquals(1, CellHostList.changes(snapshot, live).size());
        assertNull(snapshot.getFirst().cells().getFirst().stack().get(DataComponents.CUSTOM_NAME));
    }

    @Test void driveRemovalAndReorderingRequireFullSnapshot() {
        var a = cell("a", 0, ItemStack.EMPTY, 0);
        var b = cell("b", 0, ItemStack.EMPTY, 0);
        assertFalse(CellHostList.sameLayout(group(a, b), group(a)));
        assertFalse(CellHostList.sameLayout(group(a, b), group(b, a)));
    }

    @Test void obsoleteAddressCannotWriteToAnotherDrive() {
        var before = group(cell("a", 0, ItemStack.EMPTY, 0));
        var applied = CellHostList.apply(before, List.of(cell("b", 0, new ItemStack(Items.STONE), 1)));
        assertSame(before.getFirst(), applied.getFirst());
    }

    @Test void deltaPacketPreservesMenuAndCellComponents() {
        var stack = new ItemStack(Items.STONE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("cell"));
        var packet = new CellHostUpdatePayload(42, List.of(cell("a", 3, stack, .5f)));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        // 独立测试未运行注册表同步配置；只补上加载期标记，仍用真实注册表与物品组件编解码。
        try (var registrySync = mockStatic(RegistryManager.class)) {
            registrySync.when(() -> RegistryManager.isNonSyncedBuiltInRegistry(any())).thenReturn(false);
            CellHostUpdatePayload.STREAM_CODEC.encode(buffer, packet);
            var decoded = CellHostUpdatePayload.STREAM_CODEC.decode(buffer);
            assertEquals(42, decoded.menuId());
            assertEquals("a", decoded.cells().getFirst().hostKey());
            assertEquals(3, decoded.cells().getFirst().hostSlot());
            assertEquals(.5f, decoded.cells().getFirst().byteFill());
            assertTrue(ItemStack.matches(stack, decoded.cells().getFirst().stack()));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
