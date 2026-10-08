package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.network.CellHostListPayload;

/**
 * 元件管理终端表格的行模型：标题栏按「一种方块 + 一个优先级」合并，格按<b>首格单</b>排。
 *
 * <p><b>首格单是一段格的领队</b>，由每格自带的 {@code leaderKey} 说明：AE2 与 ExtendedAE 的驱动器里，一台驱动器
 * 就是一个首格单（首列画那台的图标）；ECO 家族里一个主控制器是一个首格单，它旗下最多 33 个存储矩阵都算它的格。
 * 每个首格单都从<b>首列</b>起排，装不满一行的格尾是放不进去的位置。</p>
 *
 * <p>点首格的手势都只针对那一个首格单：左键 = 世界里圈出它 + 聊天栏坐标；右键 = 选中它（存入与移元件的目标）；
 * Shift+右键 = 配它的存储优先级。</p>
 *
 * <p>{@link Row} 是 sealed 的：行就这两种，加第三种时编译器会把所有分支点出来，免得漏掉某处绘制。</p>
 */
public final class CellTableRows {

    /** 每行的格数（首格也算一格）。 */
    public static final int COLUMNS = 17;

    /** 优先级排序用：数字大的在前；识别不出优先级的排最后。 */
    private static final int UNKNOWN_PRIORITY_SORT_KEY = Integer.MIN_VALUE;

    private CellTableRows() {}

    /** 表格里的一行。 */
    public sealed interface Row permits HeaderRow, DriveRow {

        /** 这一行属于哪一组驱动器的身份键（一组 = 一种方块 + 一个优先级）。 */
        String driveKey();
    }

    /**
     * 驱动器标题栏：一组共用一条。
     *
     * @param name 驱动器名字（识别不出时的兜底文本由服务端给）
     * @param priority 存储优先级文本（识别不出时是「—」）
     * @param cellCount 这一组里插了几个元件（空格不算）
     */
    public record HeaderRow(String driveKey, String name, String priority, int cellCount) implements Row {}

    /**
     * 元件行：某个首格单的一段，永远从首列起排；不足 {@value #COLUMNS} 格的行尾是放不进去的位置。
     */
    public record DriveRow(String driveKey, List<TableCell> cells) implements Row {}

    /**
     * 一格。
     *
     * @param leaderKey 这一格归属的首格单键（一台驱动器 / 一个主机）：首格用它定位那一台，元件格用它判断
     *                  「这一格是不是在选中的那一台上」
     * @param cell 这一格对应的元件槽；为 null 表示这是那一段的首格（图标格）
     */
    public record TableCell(String leaderKey, @Nullable CellHostListPayload.CellSlot cell) {

        /** 这一格是不是某个首格单的图标格。 */
        public boolean isLeader() {
            return cell == null;
        }
    }

    /**
     * 把整张表算出来：每组一条标题栏，栏下每个首格单从新的一行起排，多的往下续行。
     *
     * <p>顺序先按优先级、再按名字。优先级高的先被存，所以「从大到小」是默认那一档（{@code descending}）；
     * 反过来排只把优先级这一项翻过来，名字与键的次序不动——它们只在优先级相同时才起作用。</p>
     *
     * @param descending true = 优先级从大到小（高优先级在前），false = 从小到大
     */
    public static List<Row> build(List<CellHostListPayload.HostGroup> drives, boolean descending) {
        var ordered = new ArrayList<>(drives);
        Comparator<CellHostListPayload.HostGroup> byPriority = Comparator
                .comparingInt((CellHostListPayload.HostGroup d) -> sortKey(d.priority()));
        ordered.sort((descending ? byPriority.reversed() : byPriority)
                .thenComparing(CellHostListPayload.HostGroup::name)
                .thenComparing(CellHostListPayload.HostGroup::key));

        var out = new ArrayList<Row>();
        for (var group : ordered) {
            // 元件数要数「真的插了元件的格」：cells 是稠密的（每个物理格一条，空格也在），
            // 直接拿 size 会把空格一起算进去。
            int filled = (int) group.cells().stream().filter(cell -> !cell.stack().isEmpty()).count();
            out.add(new HeaderRow(group.key(), group.name(), group.priority(), filled));
            appendBlockRows(out, group);
        }
        return out;
    }

    /**
     * 按首格单切段并折断成行。
     *
     * <p>首格键一变就是新的一段，先把上一段收尾——所以每台 AE2 / EAE 驱动器、每个 ECO 主机都从首列开始，
     * 段的尾巴（不满一行的那几列）在屏幕上当作放不进去的位置盖一层空白槽位。</p>
     */
    private static void appendBlockRows(List<Row> out, CellHostListPayload.HostGroup group) {
        var row = new ArrayList<TableCell>(COLUMNS);
        String leaderKey = null;
        for (var cell : group.cells()) {
            if (!cell.leaderKey().equals(leaderKey)) {
                if (!row.isEmpty()) {
                    out.add(new DriveRow(group.key(), List.copyOf(row)));
                    row.clear();
                }
                row.add(new TableCell(cell.leaderKey(), null));
                leaderKey = cell.leaderKey();
            }
            if (row.size() == COLUMNS) {
                out.add(new DriveRow(group.key(), List.copyOf(row)));
                row.clear();
            }
            row.add(new TableCell(cell.leaderKey(), cell));
        }
        if (!row.isEmpty()) {
            out.add(new DriveRow(group.key(), List.copyOf(row)));
        }
    }

    /** 优先级文本转排序键：不是数字（含「—」）的一律排最后。 */
    private static int sortKey(String priority) {
        try {
            return Integer.parseInt(priority);
        } catch (NumberFormatException notANumber) {
            return UNKNOWN_PRIORITY_SORT_KEY;
        }
    }
}
