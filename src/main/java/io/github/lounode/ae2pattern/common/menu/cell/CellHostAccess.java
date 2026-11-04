package io.github.lounode.ae2pattern.common.menu.cell;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.MEStorage;

import org.jetbrains.annotations.Nullable;

/**
 * 元件的取放：从网络里某一格取出元件、往里放元件，以及两头都要问的准入闸。
 *
 * <p>三条铁律写在这里，不要在各调用点重写：</p>
 *
 * <ul>
 *   <li><b>取放都先认宿主</b>：驱动器可能在两次点击之间被拆掉，所以每次操作都重新按身份键找本体，不用缓存引用；</li>
 *   <li><b>取件要能分辨四种结果</b>（取到 / 本来就空 / 被上游锁住 / 有东西却取不出来），
 *       玩家看到的是四种不同的回执，不能都算「没取到」；</li>
 *   <li><b>放入按格式双向收口</b>：驱动器格式只收驱动器认的元件，单格元件座只收矩阵；两边的写入口都不自带这道闸。</li>
 * </ul>
 */
final class CellHostAccess {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger("ae2_pattern_disk.cell");

    private CellHostAccess() {
    }

    /**
     * 按身份键找回驱动器本体（世界高亮、取元件这些手势要拿它操作）。
     *
     * <p>重新扫一遍而不是缓存：驱动器可能在两次点击之间被拆掉或换位置，拿旧引用去操作会改到错的方块实体。</p>
     */
    static Object findOwner(IGrid grid, String key) {
        if (grid == null) {
            return null;
        }
        for (IGridNode node : grid.getNodes()) {
            Object owner = node.getOwner();
            if (owner != null && CellDriveScanner.keyOf(owner).equals(key)) {
                return owner;
            }
        }
        return null;
    }


    /**
     * 把指定格位的元件从驱动器里取出来；取不到（格变了、驱动器没了）返回空栈。
     *
     * <p>AE2 与 EAE 走驱动器库存；ECO 家族是单格元件座，只有第 0 格，且只能整格取走。</p>
     */
    static ItemStack extractCell(Object owner, int slot, AtomicReference<CellDriveScanner.ExtractResult> reason) {
        try {
            return doExtractCell(owner, slot, reason);
        } catch (RuntimeException broken) {
            // 认出来的宿主可能来自任何模组，读它的格位就会抛：抛了只是这一台取不动，不进点击链。
            LOGGER.debug("[cell-mgmt] 取件时宿主抛了：{}", owner.getClass().getName(), broken);
            if (reason != null) {
                reason.set(CellDriveScanner.ExtractResult.REFUSED);
            }
            return ItemStack.EMPTY;
        }
    }

    /** 取件的实际过程；异常收口在 {@link #extractCell} 那一层。 */
    static ItemStack doExtractCell(Object owner, int slot, AtomicReference<CellDriveScanner.ExtractResult> reason) {
        if (reason != null) {
            reason.set(CellDriveScanner.ExtractResult.EMPTY);
        }
        CellDriveScanner.DriveView drive = CellDriveScanner.driveViewOf(owner);
        if (drive != null) {
            if (slot < 0 || slot >= drive.cellCount()) {
                return ItemStack.EMPTY;
            }
            ItemStack current = drive.stackInSlot(slot);
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            drive.setStackDirect(slot, ItemStack.EMPTY);
            boolean taken = drive.stackInSlot(slot).isEmpty();
            if (reason != null) {
                // 格里有东西却没变空同样是「取不出来」，不能报成「这一格没有元件」。
                reason.set(taken ? CellDriveScanner.ExtractResult.OK : CellDriveScanner.ExtractResult.REFUSED);
            }
            return taken ? current : ItemStack.EMPTY;
        }
        if (slot != 0) {
            return ItemStack.EMPTY;
        }
        Object current = CellProbe.invoke(owner, "getCellStack");
        if (!(current instanceof ItemStack stack) || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 单格座：清格用 null——ECO 的 setCellStack 对空栈会静默拒收（它的 isCellHandled(EMPTY) 是 false），
        // 它自己清格用的就是 null。清完必须回读：没真变空就是没取到（不能算「取了但原件还在」的复制）。
        clearCellStack(owner);
        Object after = CellProbe.invoke(owner, "getCellStack");
        if (after instanceof ItemStack left && !left.isEmpty()) {
            // 回读发现格子里还是原来那张：格里有东西却没取走。能问到锁的（ECO 无限存储迁移/成型）报 LOCKED，
            // 问不到的报 REFUSED——两者都比默认的 EMPTY 诚实：默认值会让玩家听到「这一格没有元件」，而格
            // 子里明明有。
            if (reason != null) {
                reason.set(canExtract(owner) ? CellDriveScanner.ExtractResult.REFUSED : CellDriveScanner.ExtractResult.LOCKED);
            }
            return ItemStack.EMPTY;
        }
        if (reason != null) {
            reason.set(CellDriveScanner.ExtractResult.OK);
        }
        return stack;
    }

    /**
     * 这台宿主现在允不允许取出元件；探不到这个方法就算允许。
     *
     * <p>只对报了 {@code canExtractCell()} 的宿主有意义：ECO 的驱动器在无限存储迁移中、或主机开启无限模式
     * 而该矩阵尚未成型时返回 false，它的 {@code setCellStack} 第一句就是 {@code if (!canExtractCell()) return;}
     * ——静默拒收，回读只能看出「没取到」，看不出「为什么」。纯探测，不做任何写入。</p>
     */
    static boolean canExtract(Object owner) {
        Object value = CellProbe.invoke(owner, "canExtractCell");
        return !(value instanceof Boolean allowed) || allowed;
    }

    /**
     * 把单格元件座清空：三种口径都试一遍，成败一律由调用方回读决定。
     *
     * <p>为什么不在返回值上判：这几家的回报各不相同——ECO 的 {@code setCellStack} 返回 void 且会对空栈静默
     * 拒收，青春版的 {@code removeCell()} 是<b>零参</b>（按 {@code ItemStack} 形参调用会永远匹配不上）且空手
     * 时返回空栈而不是 null。按返回值判就要为每家写一套，回读一句就都覆盖了。</p>
     *
     * <p>失败也不会弄丢东西：回读发现格子里还是原来那张时，取件直接返回 EMPTY。</p>
     */
    private static void clearCellStack(Object owner) {
        setCellStack(owner, null);
        setCellStack(owner, ItemStack.EMPTY);
        CellProbe.invoke(owner, "removeCell");
    }

    /**
     * 这台宿主收不收这一类元件到这一格：只看格式与宿主自己的过滤，<b>不</b>看这一格空不空。纯探，不写。
     *
     * <p>给「先取出、改写、再放回」的批量操作用（压缩统一管理就是这么走的）：那种地方目标格在放回前
     * 必然已被自己腾空，要问的是「这一类东西它到底收不收」——取出来放不回去就是丢东西。</p>
     *
     * <p>它只保证「格式对得上、宿主自己的过滤也没说不收」这一层，<b>不</b>保证那一格此刻仍空、也不保证
     * 里面仍是这张栈：写入侧 {@link #insertCell} 还会再问一次格位并回读确认。预探通过不等于写回必然成功。</p>
     */
    static boolean canAccept(Object owner, int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        try {
            CellDriveScanner.DriveView drive = CellDriveScanner.driveViewOf(owner);
            if (drive != null) {
                // 驱动器格式只收 AE2 认得的元件。写入要用 setItemDirect（写完能原样回读），而它按定义绕过
                // 宿主库存的 filter；ECO 的存储矩阵同样过得了 isStorageCell——不在这里挡一次，矩阵就停在
                // ME 驱动器的格子里，两边的存储 Provider 都不认它，谁也读不出那格里放着什么。
                return slot >= 0 && slot < drive.cellCount() && CellDriveScanner.isDriveFormatCell(stack)
                        && drive.accepts(slot, stack);
            }
            // 反选：单格元件座只收它那一族认得的存储矩阵。判据落在 ECO 的公开 API 上（见 registerMatrixProbe），
            // 不靠对方恰好报了 isItemValid——青春版那条没报，而 AE2 的元件一旦停进矩阵座同样是读不出来的东西。
            // 对方报了就听它的（ECO 用它挡无限模式的成员矩阵）；问不到才放行，写完仍然要回读确认。
            return slot == 0 && CellDriveScanner.isMatrixFormatCell(stack) && accepts(owner, stack);
        } catch (RuntimeException broken) {
            // 读第三方格位就会抛：抛了按「不收」处理，不把异常送进点击链，也不让预探变成一次写入的邀请。
            LOGGER.debug("[cell-mgmt] 问宿主收不收这张元件时它抛了：{}", owner.getClass().getName(), broken);
            return false;
        }
    }

    /** 把元件放进指定的格位；这一格已被占、格式不对或对方没收下返回 false。 */
    static boolean insert(Object owner, int slot, ItemStack stack) {
        if (!canAccept(owner, slot, stack)) {
            return false;
        }
        try {
            CellDriveScanner.DriveView drive = CellDriveScanner.driveViewOf(owner);
            if (drive != null) {
                if (!drive.stackInSlot(slot).isEmpty()) {
                    return false;
                }
                drive.setStackDirect(slot, stack);
                return ItemStack.isSameItemSameComponents(drive.stackInSlot(slot), stack);
            }
            Object current = CellProbe.invoke(owner, "getCellStack");
            // 空位在 ECO 那边是 null（它的字段初值就是 null，清格也用 null），空栈只是另一位面的口径：
            // 两者都算空，只有「真有一个元件」时才拒。
            if (current instanceof ItemStack existing && !existing.isEmpty()) {
                return false;
            }
            // 写完必须回读：ECO 的 setCellStack 返回 void 且会静默拒收，只看返回值会把东西弄丢。
            if (!setCellStack(owner, stack)
                    && !Boolean.TRUE.equals(invokeItem(owner, "insertCell", stack))) {
                // 青春版的 L1 驱动器没有 setCellStack，用的是 insertCell(ItemStack)：收下返回 true。
                return false;
            }
            Object after = CellProbe.invoke(owner, "getCellStack");
            return after instanceof ItemStack placed && ItemStack.isSameItemSameComponents(placed, stack);
        } catch (RuntimeException broken) {
            LOGGER.debug("[cell-mgmt] 放入时宿主抛了：{}", owner.getClass().getName(), broken);
            return false;
        }
    }

    /** 这台单格座收不收这张元件：问它自己的校验方法，问不到（第三方换名字）就放行。 */
    private static boolean accepts(Object owner, ItemStack stack) {
        try {
            Method method = owner.getClass().getMethod("isItemValid", ItemStack.class);
            Object accepted = method.invoke(owner, stack);
            return !(accepted instanceof Boolean value) || value;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return true;
        }
    }

    /**
     * 单格元件座的写入口（按名字探）。
     *
     * <p><b>不是每家都有这个方法</b>：ECO 各级存储的驱动器有 {@code setCellStack}，而青春版的 L1 驱动器
     * 没有——它用的是 {@code insertCell(ItemStack)} / {@code removeCell()} 一对。清格与放入各自先试这里、
     * 失败了再试那一对（见 {@link #clearCellStack} 与 {@link #insertCell}）。</p>
     *
     * <p>传入 {@code null} 表示清格（ECO 用它自己的判空口径）；返回的只是「方法存在且没抛异常」，
     * 真有没有生效由调用方回读确认——ECO 的 setCellStack 是会静默拒收的。</p>
     *
     * <p>形参类型必须保持 {@code ItemStack}：{@code invoke} 收的是「一个 ItemStack 参数」，
     * 若把它改成 {@code Object} 或直接写字面量 {@code null}，反射会退化成「零参数调用」而抛异常，
     * 清格就静默失效了（取件会被回读挡下，不会复制，但会变成取不了）。</p>
     */
    private static boolean setCellStack(Object owner, @Nullable ItemStack stack) {
        try {
            Method method = owner.getClass().getMethod("setCellStack", ItemStack.class);
            method.invoke(owner, stack);
            return true;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /** 按名字带一个 {@link ItemStack} 形参调用（用于 {@code insertCell}/{@code removeCell} 那一对）；失败返回 null。 */
    private static Object invokeItem(Object target, String name, @Nullable ItemStack arg) {
        try {
            Method method = target.getClass().getMethod(name, ItemStack.class);
            return method.invoke(target, arg);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /** 探到的元件库存只有实现了 {@link MEStorage} 才能读占用；探不到就当作读不了。 */
    static MEStorage asStorage(Object candidate) {
        return candidate instanceof MEStorage storage ? storage : null;
    }

}
