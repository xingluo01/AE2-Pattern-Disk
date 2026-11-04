package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import io.github.lounode.ae2pattern.common.menu.cell.CellDriveScanner;
import io.github.lounode.ae2pattern.common.menu.cell.CellFormats;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 元件管理终端的准入闸门与第三方驱动器的自动识别。
 *
 * <p>闸门有两条：AE2 标准驱动器格式的格子只收 AE2 的 cell handler 认得的元件，单格元件座只收存储矩阵。
 * 识别也不点名设备：长成 {@link IChestOrDrive} + 一个逐格读得到的内部库存就算驱动器格式。</p>
 *
 * <p>不需要真模组在场：AE2 的 handler 名单在单测里是空的，所以「AE2 认得的元件」用
 * {@code mockStatic(StorageCells)} 造，「ECO 认得的矩阵」用注册进扫描器的探针造；两边的宿主都是按同一
 * 形状搭的替身。矩阵座那条路靠反射问方法，所以替身与本类同包（跨包的成员反射默认拒访）。</p>
 */
class CellFormatGateTest {

    /** 只有探针把它当存储矩阵。 */
    private static ItemStack matrix;
    /** 只有 AE2 的 cell handler 把它当元件（在下面的 mockStatic 里声明）。 */
    private static ItemStack ae2Cell;
    /** 两家都认领的栈（ECO 的委派 handler 会接管 AE2 创意元件那类），两边都不该挡它。 */
    private static ItemStack bothClaimed;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        // 与 CellHostListTest 同一套路子：注册表 bootstrap 要在 LoadingModList 被打桩时才走得通。
        try (var loading = mockStatic(LoadingModList.class)) {
            var mods = mock(LoadingModList.class);
            when(mods.getModFiles()).thenReturn(List.of());
            loading.when(LoadingModList::get).thenReturn(mods);
            Bootstrap.bootStrap();
            // bootstrap 之前 Items.* 还是空引用，替身栈只能在这里造。
            matrix = new ItemStack(Items.GOLD_INGOT);
            ae2Cell = new ItemStack(Items.IRON_INGOT);
            bothClaimed = new ItemStack(Items.DIAMOND);
        }
    }

    /** 探针是静态注册，用完清回「没有探针」，不污染别的用例。 */
    @AfterEach void clearProbe() {
        CellFormats.registerMatrixProbe(null);
    }

    private static void probeSeesMatrix() {
        CellFormats.registerMatrixProbe(
                stack -> stack.is(matrix.getItem()) || stack.is(bothClaimed.getItem()));
    }

    /** 让 AE2 的 cell handler 只认 ae2Cell 与 bothClaimed 两张。 */
    private static MockedStatic<StorageCells> ae2HandlesCells() {
        var mocked = mockStatic(StorageCells.class);
        mocked.when(() -> StorageCells.isCellHandled(ae2Cell)).thenReturn(true);
        mocked.when(() -> StorageCells.isCellHandled(bothClaimed)).thenReturn(true);
        mocked.when(() -> StorageCells.isCellHandled(matrix)).thenReturn(false);
        return mocked;
    }

    /** 把这些宿主摆成一张网。 */
    private static IGrid gridOf(Object... owners) {
        var nodes = new ArrayList<IGridNode>();
        for (Object owner : owners) {
            IGridNode node = mock(IGridNode.class);
            when(node.getOwner()).thenReturn(owner);
            nodes.add(node);
        }
        IGrid grid = mock(IGrid.class);
        when(grid.getNodes()).thenReturn(nodes);
        return grid;
    }

    /** 第三方「AE2 标准驱动器格式」设备：实现公开契约，格数与库存长度分开交代。 */
    public static class ProbeDrive implements IChestOrDrive {
        final List<ItemStack> slots = new ArrayList<>();
        /** 契约上报的格数。与下面的库存长度分开，才造得出「箱子」那类对不齐的形状。 */
        int cellCount = 10;
        private final InternalInventory inventory = mock(InternalInventory.class);

        ProbeDrive() {
            for (int i = 0; i < cellCount; i++) {
                slots.add(ItemStack.EMPTY);
            }
            when(inventory.size()).thenAnswer(call -> slots.size());
            when(inventory.getStackInSlot(anyInt())).thenAnswer(call -> slots.get(call.getArgument(0)));
            doAnswer(call -> slots.set(call.getArgument(0), call.getArgument(1)))
                    .when(inventory).setItemDirect(anyInt(), any());
            // 宿主自己的过滤默认放行；要验「宿主拒收」那一档用 refuseHostFilter() 关掉。
            when(inventory.isItemValid(anyInt(), any())).thenReturn(true);
        }

        /** 扫描器按名字问的就是这个方法（AE2 的公开契约上没有它）。 */
        public InternalInventory getInternalInventory() {
            return inventory;
        }

        /** 让宿主自己的过滤说「不收」——AE2 驱动器库存的 filter 正是这条。 */
        void refuseHostFilter() {
            when(inventory.isItemValid(anyInt(), any())).thenReturn(false);
        }

        /** 库存比契约格数多出一格：ME 箱子那类把元件格与输入缓存格拼在一起。 */
        void addOneExtraInventorySlot() {
            slots.add(ItemStack.EMPTY);
        }

        @Override public int getCellCount() { return cellCount; }
        @Override public CellState getCellStatus(int slot) { return CellState.ABSENT; }
        @Override public boolean isPowered() { return true; }
        @Override public boolean isCellBlinking(int slot) { return false; }
        @Override public Item getCellItem(int slot) { return null; }
        @Override public MEStorage getCellInventory(int slot) { return null; }
        @Override public StorageCell getOriginalCellInventory(int slot) { return null; }
        @Override public IGridNode getActionableNode() { return null; }
    }

    /** 报 10 格却给不出 10 格库存的设备：逐格读会越界。 */
    public static final class ShortDrive extends ProbeDrive {
        @Override public InternalInventory getInternalInventory() {
            return InternalInventory.empty();
        }
    }

    /** ECO 那类单格元件座：按名字探的方法组，没有 AE2 的驱动器契约。 */
    public static final class ProbeSocket {
        ItemStack cellStack = ItemStack.EMPTY;

        public ItemStack getCellStack() { return cellStack; }

        public void setCellStack(ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                cellStack = stack;
            }
        }
    }

    @Test void probeDriveIsListedAsAStandardDrive() {
        var entries = CellDriveScanner.scan(gridOf(new ProbeDrive()), false);

        assertEquals(1, entries.size(), "实现 AE2 驱动器契约的第三方设备应当被自动列出");
        assertEquals(10, entries.get(0).cells().size(), "格数按宿主的 getCellCount 现读");
        assertFalse(entries.get(0).ecoHost(), "它不是 ECO 的存储主机");
    }

    /** 判据两头的错配形状都不列：库存比格数短（越界），以及长（箱子那类，格序错位）。 */
    @Test void driveWithMismatchedShapeIsNotListed() {
        var shortDrive = new ShortDrive();
        var chestShaped = new ProbeDrive();
        chestShaped.addOneExtraInventorySlot();

        assertTrue(CellDriveScanner.scan(gridOf(shortDrive, chestShaped), false).isEmpty(),
                "格序与契约对不上的设备整台不列");
        assertTrue(chestShaped.slots.stream().allMatch(ItemStack::isEmpty),
                "不列出的设备，格子里的东西也没被动过");
    }

    @Test void driveRejectsStorageMatrix() {
        probeSeesMatrix();
        var drive = new ProbeDrive();

        assertFalse(CellDriveScanner.insertCell(drive, 0, matrix), "存储矩阵不该进驱动器格式的格子");
        assertFalse(CellDriveScanner.canAcceptCell(drive, 0, matrix), "同一条判据在纯探上也成立");
        assertTrue(drive.slots.get(0).isEmpty(), "拒收时那一格必须还是空的");
    }

    @Test void driveStillAcceptsAe2Cell() {
        probeSeesMatrix();
        var drive = new ProbeDrive();

        try (var ignored = ae2HandlesCells()) {
            assertTrue(CellDriveScanner.insertCell(drive, 0, ae2Cell), "对照：驱动器格式收 AE2 认得的元件");
        }
        assertEquals(ae2Cell.getItem(), drive.slots.get(0).getItem());

        var reason = new java.util.concurrent.atomic.AtomicReference<CellDriveScanner.ExtractResult>();
        ItemStack taken = CellDriveScanner.extractCell(drive, 0, reason);
        assertFalse(taken.isEmpty(), "放进去了就要能取出来");
        assertEquals(CellDriveScanner.ExtractResult.OK, reason.get());
        assertTrue(drive.slots.get(0).isEmpty());
    }

    /** 格式对上了，宿主库存自己说不收，照样不写。 */
    @Test void hostFilterRejectionIsHonoured() {
        var drive = new ProbeDrive();
        drive.refuseHostFilter();
        probeSeesMatrix();

        try (var ignored = ae2HandlesCells()) {
            assertFalse(CellDriveScanner.canAcceptCell(drive, 0, ae2Cell), "宿主说不收就不放行");
            assertFalse(CellDriveScanner.insertCell(drive, 0, ae2Cell));
        }
        assertTrue(drive.slots.get(0).isEmpty());
    }

    @Test void matrixSocketRejectsAe2Cell() {
        probeSeesMatrix();
        var socket = new ProbeSocket();

        try (var ignored = ae2HandlesCells()) {
            assertFalse(CellDriveScanner.insertCell(socket, 0, ae2Cell),
                    "AE2 元件不该进存储矩阵座（对方没报 isItemValid 也要挡）");
        }
        assertTrue(socket.cellStack.isEmpty());
    }

    @Test void matrixSocketStillAcceptsMatrix() {
        probeSeesMatrix();
        var socket = new ProbeSocket();

        assertTrue(CellDriveScanner.insertCell(socket, 0, matrix), "对照：矩阵座收它那一族的矩阵");
        assertEquals(matrix.getItem(), socket.cellStack.getItem());
    }

    /** 上游主动认领的 AE2 元件不是错配：两边都不挡（挡了就把上游的能力砍掉）。 */
    @Test void cellClaimedByBothHandlersGoesEitherWay() {
        probeSeesMatrix();
        var drive = new ProbeDrive();
        var socket = new ProbeSocket();

        try (var ignored = ae2HandlesCells()) {
            assertTrue(CellDriveScanner.insertCell(drive, 0, bothClaimed), "驱动器收它");
            assertTrue(CellDriveScanner.insertCell(socket, 0, bothClaimed), "矩阵座也收它");
        }
    }

    /** 压缩统一管理靠这条预探决定动不动手，所以它必须一物不写。 */
    @Test void canAcceptCellNeverWrites() {
        probeSeesMatrix();
        var drive = new ProbeDrive();
        var socket = new ProbeSocket();

        try (var ignored = ae2HandlesCells()) {
            assertTrue(CellDriveScanner.canAcceptCell(drive, 0, ae2Cell));
            assertTrue(CellDriveScanner.canAcceptCell(socket, 0, matrix));
        }
        assertTrue(drive.slots.get(0).isEmpty(), "纯探不能留下任何写入");
        assertTrue(socket.cellStack.isEmpty(), "纯探不能留下任何写入");
    }

    @Test void bothFormatsAreStillStorageForDisplayAndEncodeSlot() {
        probeSeesMatrix();

        assertTrue(CellFormats.isStorageCell(matrix));
        assertTrue(CellFormats.isMatrixFormatCell(matrix));
        assertFalse(CellFormats.isDriveFormatCell(matrix));
        assertFalse(CellFormats.isStorageCell(new ItemStack(Items.STICK)), "木棍既不是元件也不是矩阵");

        try (var ignored = ae2HandlesCells()) {
            assertTrue(CellFormats.isStorageCell(ae2Cell));
            assertTrue(CellFormats.isDriveFormatCell(ae2Cell));
            assertFalse(CellFormats.isMatrixFormatCell(ae2Cell));
        }
    }

    @Test void emptyStackIsNeitherFormat() {
        probeSeesMatrix();
        assertFalse(CellFormats.isStorageCell(ItemStack.EMPTY));
        assertFalse(CellFormats.isDriveFormatCell(ItemStack.EMPTY));
        assertFalse(CellFormats.isMatrixFormatCell(ItemStack.EMPTY));
    }

    /** 第三方宿主读一格就抛时，扫描与取放都只丢这一台，不把异常送进点击链。 */
    @Test void throwingHostDegradesToRefusalInsteadOfCrashingTheChain() {
        probeSeesMatrix();
        var broken = new ProbeDrive() {
            @Override public int getCellCount() {
                throw new IllegalStateException("宿主读不动");
            }
        };

        assertTrue(CellDriveScanner.scan(gridOf(broken), false).isEmpty(), "扫不动就不列");
        assertFalse(CellDriveScanner.canAcceptCell(broken, 0, matrix));
        assertFalse(CellDriveScanner.insertCell(broken, 0, matrix));
        var reason = new java.util.concurrent.atomic.AtomicReference<CellDriveScanner.ExtractResult>();
        assertTrue(CellDriveScanner.extractCell(broken, 0, reason).isEmpty());
        assertEquals(CellDriveScanner.ExtractResult.REFUSED, reason.get());
    }
}
