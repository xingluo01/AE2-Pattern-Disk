package io.github.lounode.ae2pattern.client.gui;

import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.DiskRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.FreeSlotsRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.HostRow;
import static io.github.lounode.ae2pattern.client.gui.DiskTableRowModel.Row;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

/**
 * 管理终端表格的行构建与过滤：给定各机器与磁盘的当下状态，算出表格里该出现哪些行。
 *
 * <p>从 {@code PatternDiskManagementTermScreen} 抽出来时做成了<b>纯函数</b>——输入是打包好的数据
 * （{@link GroupInput} / {@link DiskInput}），输出是行列表，中间不读屏幕的任何字段。屏幕那边原本
 * 直接摸 {@code visibleDisks}、{@code serialToPatternCount}、{@code hideEmptySlots} 等状态，搬过来
 * 就得为它单开一个宿主接口；改成纯函数之后这些状态由屏幕组装成入参，这里只做判定，也就能单独测。</p>
 *
 * <p>三层过滤的语义（顺序即优先级）是这张表最容易看错的地方，原样保留：</p>
 * <ol>
 *   <li><b>磁盘搜索</b>（父类搜索框）筛过的机器，一张盘不剩时整组不进表——否则一搜满屏空机器；</li>
 *   <li><b>内容搜索</b>（本屏搜索栏）没命中样板的机器整组不进表。内容尚未下发到客户端的盘按「未知」处理，
 *       不据它下判断，免得加载中途把整台机器误隐藏；</li>
 *   <li>同样按内容搜索，<b>一张都没命中的盘整张不进表</b>（含它的槽位与续行），续行行数按「命中后的数量」
 *       算——所以不匹配的样板不会留下空槽位与多余空行。</li>
 * </ol>
 */
public final class DiskTableRowBuilder {

    /** 每行的格数。首行第 0 格被磁盘本身占掉，所以续行的起始样板序号是 {@code COLUMNS - 1}。 */
    private static final int COLUMNS = 17;

    private DiskTableRowBuilder() {}

    /** 一台宿主机器在表格里的输入数据；{@code disks} 只装当前可见（已被磁盘搜索筛过）的盘。 */
    public record GroupInput(String key, String name, ItemStack icon, List<DiskInput> disks, int emptySlots) {}

    /**
     * 一张盘在表格里的输入数据。
     *
     * @param totalPatternCount 盘里样板总数，内容搜索未生效时用它算续行行数
     * @param matchedCount      命中内容搜索的样板数；<b>-1 表示内容还没下发到客户端</b>（未知，不得据此隐藏）
     */
    public record DiskInput(long serial, ItemStack stack, int totalPatternCount, int matchedCount) {}

    /**
     * 算出一张表该有哪些行。
     *
     * @param diskSearched    父类搜索框是否在筛磁盘
     * @param contentSearched 本屏内容搜索栏是否在筛样板
     * @param hideEmptySlots  空槽是折叠成一行还是每格一行
     * @param collapseVirtualPatterns 是否把每张盘的样板只留磁盘后第一行（不生成续行）
     */
    public static List<Row> build(List<GroupInput> groups, boolean diskSearched, boolean contentSearched,
            boolean hideEmptySlots, boolean collapseVirtualPatterns) {
        var out = new ArrayList<Row>();
        for (var group : groups) {
            if (diskSearched && group.disks().isEmpty()) {
                continue;
            }

            // 内容搜索：没命中样板的机器整组不进表（隐藏工作方块）。只要有一张盘的内容未知，整组就不下判断。
            boolean anyUnknown = false;
            int matchedTotal = 0;
            for (var disk : group.disks()) {
                if (disk.matchedCount() < 0) {
                    anyUnknown = true;
                } else {
                    matchedTotal += disk.matchedCount();
                }
            }
            if (contentSearched && !anyUnknown && matchedTotal == 0) {
                continue;
            }

            out.add(new HostRow(group.key(), group.name(), group.icon(), group.disks().size()));
            for (var disk : group.disks()) {
                if (contentSearched && disk.matchedCount() == 0) {
                    continue; // 盘里一张都没命中：整张盘（含它的槽位与续行）不进表
                }
                out.add(new DiskRow(group.key(), disk.serial(), disk.stack(), 0));
                // 一张盘的内容超过一行时往下续行：首行第 0 格占给了磁盘，续行没有磁盘格，17 格全放内容。
                // 行数用「命中后的数量」算，不匹配的样板因此不会留下空槽位与多余空行。
                int rowCount = disk.matchedCount() >= 0 ? disk.matchedCount() : disk.totalPatternCount();
                // 「折叠虚拟样板」打开时只留磁盘后的第一行样板：夹到首行能装下的 16 张，续行一条也不生成。
                if (collapseVirtualPatterns) {
                    rowCount = Math.min(rowCount, COLUMNS - 1);
                }
                for (int from = COLUMNS - 1; from < rowCount; from += COLUMNS) {
                    out.add(new DiskRow(group.key(), disk.serial(), ItemStack.EMPTY, from));
                }
            }
            // 内容搜索时不补空槽行：那些行在过滤视图里就是「多余空行」。
            if (!contentSearched) {
                appendFreeSlots(out, group.key(), group.emptySlots(), hideEmptySlots);
            }
        }
        return out;
    }

    /**
     * 给一组补「剩余槽位」行。
     *
     * <p>空槽数直接取服务端在分组里报的「真正的空格数」（同名几台是它们的和）：搜索框筛掉部分盘、
     * 主机行开关隐藏整组都不会让它变化，槽位与别的物品共用（NEO ECO 把样板盘与已编码样板放在同一批槽里）
     * 也不会被算错。</p>
     *
     * <p>收起（{@code hideEmptySlots}）时整组只留一行，行上写它代表多少空槽；展开时每个空槽一行，
     * 竖着排在第一列。</p>
     */
    private static void appendFreeSlots(List<Row> out, String groupName, int empty, boolean hideEmptySlots) {
        if (empty <= 0 || groupName == null || groupName.isEmpty()) {
            return;
        }
        if (hideEmptySlots) {
            out.add(new FreeSlotsRow(groupName, empty));
            return;
        }
        // 展开：一格一行，竖着排在第一列——空槽不是“盘里的内容”，不铺满整行。
        for (int i = 0; i < empty; i++) {
            out.add(new FreeSlotsRow(groupName, 0));
        }
    }
}
