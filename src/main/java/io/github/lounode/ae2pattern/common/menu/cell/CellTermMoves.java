package io.github.lounode.ae2pattern.common.menu.cell;

import net.minecraft.world.item.ItemStack;

import appeng.helpers.IPriorityHost;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.menu.implementations.PriorityMenu;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.common.part.CellManagementTerminalPart;
import io.github.lounode.ae2pattern.network.CellHostListPayload;

/**
 * 元件管理终端的「元件搬运」：表格里的元件与光标 / 背包 / 编码槽之间的取放，以及打开宿主自己的优先级界面。
 *
 * <p>驱动器里的元件不在菜单的槽位表里（表格是自绘的），所以每一格都记成「宿主键 + 宿主内格位」，服务端拿到
 * 这两个值后<b>再查一次当下的栈</b>：两次点击之间玩家可能已经把那一格挪走了。串的拼与解都在这里，菜单那边
 * 只做客户端/服务端的分流。</p>
 *
 * <p>搬运的铁律：任何一步失败都不许吞东西。取出来的元件放不回原格，就掉在玩家脚下。</p>
 */
final class CellTermMoves {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger(CellTermMoves.class);

    private final CellManagementTermMenu menu;

    CellTermMoves(CellManagementTermMenu menu) {
        this.menu = menu;
    }

    /** 一段动作参数：宿主键 + 宿主内格位。 */
    record DriveSlot(String key, int slot) {
    }

    /** 三段动作参数：源宿主键 + 源格位 + 目标首格键。 */
    record Triple(String hostKey, int hostSlot, String targetLeaderKey) {
    }

    static String encode(DriveSlot slot) {
        return slot.key() + "|" + slot.slot();
    }

    static String encode(Triple triple) {
        return triple.hostKey() + "|" + triple.hostSlot() + "|" + triple.targetLeaderKey();
    }

    /** 解「宿主键|格位」；解不开返回 null（协议错配不该把服务端带进越界取值）。 */
    @Nullable
    static DriveSlot decode(String encoded) {
        int separator = encoded.lastIndexOf('|');
        if (separator <= 0) {
            return null;
        }
        try {
            return new DriveSlot(encoded.substring(0, separator),
                    Integer.parseInt(encoded.substring(separator + 1)));
        } catch (NumberFormatException broken) {
            return null;
        }
    }

    /** 解「宿主键|格位|目标首格键」；三段都必须非空。 */
    @Nullable
    static Triple decodeTriple(String encoded) {
        int first = encoded.indexOf('|');
        int second = first < 0 ? -1 : encoded.indexOf('|', first + 1);
        if (first <= 0 || second <= 0 || second >= encoded.length() - 1) {
            return null;
        }
        try {
            return new Triple(encoded.substring(0, first),
                    Integer.parseInt(encoded.substring(first + 1, second)), encoded.substring(second + 1));
        } catch (NumberFormatException broken) {
            return null;
        }
    }

    /**
     * 把驱动器某一格上的元件收进元件编码槽。
     *
     * <p>编码槽已经有东西时不换：得先把当前那个放回去，否则另一个元件就被挤没了。</p>
     */
    void moveToEncodeSlot(String driveKey, int driveSlot) {
        var encodeInv = menu.getCellHost().getEncodeCellInventory();
        if (!encodeInv.getStackInSlot(0).isEmpty()) {
            return;
        }
        Object owner = menu.ownerOf(driveKey);
        if (owner == null) {
            return;
        }
        ItemStack taken = take(owner, driveSlot);
        if (taken.isEmpty()) {
            return;
        }
        encodeInv.setItemDirect(0, taken);
        menu.getCellHost().markForSave();
        menu.markDriveListDirty();
        menu.broadcastChanges();
    }

    /** 左键一格元件：光标空就取到光标上，光标有元件就放进这一格。 */
    void takeCell(String driveKey, int driveSlot) {
        // 光标上有东西就不取：取来的元件没地方放，硬顶上去会把玩家手上的东西弄丢。
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        Object owner = menu.ownerOf(driveKey);
        if (owner == null) {
            return;
        }
        ItemStack taken = take(owner, driveSlot);
        if (taken.isEmpty()) {
            return;
        }
        menu.setCarried(taken);
        menu.afterCellMoved(owner);
    }

    /** 左键一格空格：把光标上的元件放进去（驱动器拒收就不动）。 */
    void putCell(String driveKey, int driveSlot) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        Object owner = menu.ownerOf(driveKey);
        if (owner == null) {
            return;
        }
        // 只交一份：元件不可堆叠，但对方若在内部 copyWithCount(1)，整栈交过去会让多出来的份数静默消失。
        if (!CellDriveScanner.insertCell(owner, driveSlot, carried.copyWithCount(1))) {
            // 失败原因就四种（格式不符 / 宿主过滤拒收 / 格位已占 / 回读没生效），AE2 原生驱动器槽也是静默拒收。
            LOGGER.debug("[cell-mgmt] 放入被拒：宿主 {} 格 {}，丢弃这次放入", driveKey, driveSlot);
            return;
        }
        ItemStack rest = carried.copy();
        rest.shrink(1);
        menu.setCarried(rest);
        menu.afterCellMoved(owner);
    }

    /** Shift+左键：把这一格的元件直收进玩家背包；光标上有东西时先收光标那个。 */
    void quickMoveCell(String driveKey, int driveSlot) {
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            if (menu.getPlayer().getInventory().add(carried)) {
                menu.setCarried(ItemStack.EMPTY);
                menu.broadcastChanges();
            }
            return;
        }
        Object owner = menu.ownerOf(driveKey);
        if (owner == null) {
            return;
        }
        ItemStack taken = take(owner, driveSlot);
        if (taken.isEmpty()) {
            return;
        }
        if (!menu.getPlayer().getInventory().add(taken)) {
            // 背包满：先试着放回原格；驱动器可能刚被拆，那也不能凭空吞掉——掉在玩家脚下。
            LOGGER.info("[cell-mgmt] 背包满：取出的元件已放回原格或掉在玩家脚下");
            if (!CellDriveScanner.insertCell(owner, driveSlot, taken)) {
                menu.getPlayer().drop(taken, false);
            }
            return;
        }
        menu.afterCellMoved(owner);
    }

    /** 把背包里的存储元件存进所选首格单的第一个空位。 */
    void storeCellIntoDrive(String leaderKey, int inventorySlot) {
        var inventory = menu.getPlayer().getInventory();
        if (inventorySlot < 0 || inventorySlot >= inventory.getContainerSize()) {
            // 客户端报来的格位在当前容器里不存在：多半是两次点击之间背包变了。
            LOGGER.info("[cell-mgmt] 存入的源格位已失效（报来 {}，当前 {} 格），丢弃这次存入",
                    inventorySlot, inventory.getContainerSize());
            return;
        }
        ItemStack source = inventory.getItem(inventorySlot);
        if (!CellDriveScanner.isStorageCell(source)) {
            LOGGER.info("[cell-mgmt] 源格位里不是存储元件（{}），丢弃这次存入", source.getItem());
            return;
        }
        var grid = menu.gridOrNull();
        if (grid == null) {
            return;
        }
        var slots = CellDriveScanner.slotsOfBlock(grid, leaderKey);
        for (CellHostListPayload.CellSlot slot : slots) {
            if (!slot.stack().isEmpty()) {
                continue;
            }
            Object owner = CellDriveScanner.findOwner(grid, slot.hostKey());
            // 交的是副本：源栈接下来要减一，不能让驱动器里那份跟着变。
            if (owner == null || !CellDriveScanner.insertCell(owner, slot.hostSlot(), source.copyWithCount(1))) {
                continue;
            }
            ItemStack remaining = source.copy();
            remaining.shrink(1);
            // 背包那份是活的 Inventory 对象，不在槽位包自动同步的范围里，得主动推一次。
            inventory.setItem(inventorySlot, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
            inventory.setChanged();
            menu.afterCellMoved(owner);
            return;
        }
    }

    /**
     * 把一个元件格里的元件移到选中的那台驱动器。
     *
     * <p>目标先确认有空位才去取源：取出来再发现放不进去，那一下已经把元件抱在手里了，多一次回填就多一次
     * 出错的机会。</p>
     */
    void moveToSelectedDrive(String hostKey, int hostSlot, String targetLeaderKey) {
        var grid = menu.gridOrNull();
        if (grid == null) {
            return;
        }
        Object source = CellDriveScanner.findOwner(grid, hostKey);
        if (source == null) {
            return;
        }
        var slots = CellDriveScanner.slotsOfBlock(grid, targetLeaderKey);
        if (slots.stream().noneMatch(slot -> slot.stack().isEmpty())) {
            return;
        }
        ItemStack taken = take(source, hostSlot);
        if (taken.isEmpty()) {
            return;
        }
        for (CellHostListPayload.CellSlot slot : slots) {
            if (!slot.stack().isEmpty()) {
                continue;
            }
            Object owner = CellDriveScanner.findOwner(grid, slot.hostKey());
            if (owner == null || !CellDriveScanner.insertCell(owner, slot.hostSlot(), taken)) {
                continue;
            }
            menu.afterCellMoved(source, owner);
            return;
        }
        if (!CellDriveScanner.insertCell(source, hostSlot, taken)) {
            // 回填也失败（驱动器可能刚被拆）：不能把元件凭空吞掉。
            menu.getPlayer().drop(taken, false);
        }
        menu.notice(CellManagementTermMenu.NOTICE + "move_failed");
    }

    /** 把元件编码槽里那张元件上传到选中的那一台驱动器。成功了才清编码槽，反过来会把元件弄丢。 */
    void storeEncodeCellIntoDrive(String leaderKey) {
        var encodeInv = menu.getCellHost().getEncodeCellInventory();
        ItemStack cell = encodeInv.getStackInSlot(0);
        if (cell.isEmpty()) {
            return;
        }
        var grid = menu.gridOrNull();
        if (grid == null) {
            return;
        }
        for (CellHostListPayload.CellSlot slot : CellDriveScanner.slotsOfBlock(grid, leaderKey)) {
            if (!slot.stack().isEmpty()) {
                continue;
            }
            Object owner = CellDriveScanner.findOwner(grid, slot.hostKey());
            if (owner == null || !CellDriveScanner.insertCell(owner, slot.hostSlot(), cell)) {
                continue;
            }
            encodeInv.setItemDirect(0, ItemStack.EMPTY);
            menu.getCellHost().markForSave();
            menu.afterCellMoved(owner);
            return;
        }
    }

    /**
     * 中键某一台驱动器首格：打开 AE2 自己的存储优先级界面配那一台。
     *
     * <p>只要能<b>写</b>优先级就放行：AE2/EAE 驱动器走 {@link IPriorityHost}，ECO 各级存储与青春版 L1 驱动器
     * 走控制器上的 {@code setStoragePriority(int)}；只有读没有写的不放行（ECO 本体就是），那个界面不读回也
     * 不报错，放进去只会让玩家以为改成了。</p>
     *
     * <p>面板形态指本部件，无线形态用菜单自己的 locator（宿主是物品）。locator 解析出的是宿主类的
     * <b>另一个实例</b>，所以目标键得先写进物品组件。</p>
     */
    void openPriorityGui(String leaderKey) {
        var grid = menu.gridOrNull();
        if (grid == null) {
            return;
        }
        Object owner = CellDriveScanner.findOwner(grid, leaderKey);
        if (owner == null) {
            return;
        }
        if (!CellDriveScanner.canSetStoragePriority(owner)) {
            menu.notice(CellManagementTermMenu.NOTICE + "priority_unsupported");
            return;
        }
        var host = menu.getCellHost();
        host.setPriorityTarget(leaderKey);
        MenuHostLocator locator = host instanceof CellManagementTerminalPart part
                ? MenuLocators.forPart(part)
                : menu.getLocator();
        if (!MenuOpener.open(PriorityMenu.TYPE, menu.getPlayer(), locator)) {
            // 宿主解析不出来时 AE2 那边只静默返回 false，这里补一句，免得玩家以为点坏了。
            menu.notice(CellManagementTermMenu.NOTICE + "priority_unsupported");
        }
    }

    /**
     * 从一格取元件；取不到时按原因给玩家一句反馈（真的空着就不出声）。
     *
     * <p>四个入口（收进编码槽 / 取到光标 / 收进背包 / 移到另一台）都是「取 → 失败提示 → 各自处理」，
     * 提示的口径只在这里写一次。</p>
     */
    @Nullable
    private ItemStack take(Object owner, int slot) {
        var reason = new java.util.concurrent.atomic.AtomicReference<CellDriveScanner.ExtractResult>();
        ItemStack taken = CellDriveScanner.extractCell(owner, slot, reason);
        if (taken.isEmpty()) {
            notifyTakeFailure(reason.get());
        }
        return taken;
    }

    /**
     * 取不到元件时该报哪一句；<b>真的空着时不出声</b>（AE2 原生点空格也是静默的）。
     *
     * <p>两态出声：格里有东西但被锁住时说锁，格里有东西却不知为何没取走时照实说「取不出来」。</p>
     */
    private void notifyTakeFailure(CellDriveScanner.ExtractResult result) {
        String key = switch (result) {
            case LOCKED -> "cell_locked";
            case REFUSED -> "cell_refused";
            default -> null;
        };
        if (key == null) {
            // 正常误点也会走到这里（点到空格），所以是 debug：排障时能看出「两端视图不一致」这种异常。
            LOGGER.debug("[cell-mgmt] 取件时目标格为空，客户端与服务端视图可能不一致");
            return;
        }
        menu.notice(CellManagementTermMenu.NOTICE + key);
    }

    // ---- 动作参数入口：注册给 AE2 的那些回调只到得了服务端 ----

    void moveToEncodeSlotEncoded(String encoded) {
        var slot = decode(encoded);
        if (slot == null) {
            warnBadPayload(encoded);
            return;
        }
        moveToEncodeSlot(slot.key(), slot.slot());
    }

    void takeCellEncoded(String encoded) {
        var slot = decode(encoded);
        if (slot == null) {
            warnBadPayload(encoded);
            return;
        }
        takeCell(slot.key(), slot.slot());
    }

    void putCellEncoded(String encoded) {
        var slot = decode(encoded);
        if (slot == null) {
            warnBadPayload(encoded);
            return;
        }
        putCell(slot.key(), slot.slot());
    }

    void quickMoveCellEncoded(String encoded) {
        var slot = decode(encoded);
        if (slot == null) {
            warnBadPayload(encoded);
            return;
        }
        quickMoveCell(slot.key(), slot.slot());
    }

    void storeCellIntoDriveEncoded(String encoded) {
        var slot = decode(encoded);
        if (slot == null) {
            warnBadPayload(encoded);
            return;
        }
        storeCellIntoDrive(slot.key(), slot.slot());
    }

    void moveToSelectedEncoded(String encoded) {
        var triple = decodeTriple(encoded);
        if (triple == null) {
            warnBadPayload(encoded);
            return;
        }
        moveToSelectedDrive(triple.hostKey(), triple.hostSlot(), triple.targetLeaderKey());
    }

    private static void warnBadPayload(String encoded) {
        LOGGER.warn("[cell-mgmt] 收到解不开的动作参数：{}", encoded);
    }
}
