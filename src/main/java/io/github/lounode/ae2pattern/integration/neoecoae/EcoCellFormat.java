package io.github.lounode.ae2pattern.integration.neoecoae;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;

import cn.dancingsnow.neoecoae.api.storage.ECOStorageCells;

import io.github.lounode.ae2pattern.common.menu.cell.CellDriveScanner;
import io.github.lounode.ae2pattern.common.menu.cell.CellFormats;

/**
 * 用 NEO ECO 的公开 API 回答「这张栈是不是存储矩阵」。
 *
 * <p>注册进 {@link CellFormats.CellFormatProbe}，元件管理终端才能按格式双向收口放入：ME 驱动器那类不收
 * 矩阵，矩阵座只收它那一族认得的矩阵。判据取 {@code api.storage.ECOStorageCells#isCellHandled}——它问的是
 * ECO 自己的 handler 名单，比扫描器里那条按接口名的兜底准：上游搬过包也改过名，名字漏认时矩阵会被挡在
 * 矩阵座外，玩家看到的是「明明是能用的盘却放不进去」。</p>
 *
 * <p>ECO 主动认领的 AE2 元件（创意元件那类由它自己的委派 handler 接管）在这里同样返回 true，所以照旧能进
 * 矩阵座——那是上游的选择，不是要挡的错配。</p>
 *
 * <p><b>本类只在 ECO 在场时加载</b>（{@code NeoECOIntegration} 由 ECO 的 IntegrationManager 发现并调起），
 * 所以可以直接引用上游类型；缺 ECO 时这个类连同契约都不会被加载。上游查询抛异常时退化成「不算矩阵」：
 * 准入闸门宁可多拒一次，也不能把两边的 Provider 都读不出来的东西写进格子。</p>
 */
public final class EcoCellFormat implements CellFormats.CellFormatProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    private EcoCellFormat() {
    }

    /** 由 {@code NeoECOIntegration#apply()} 调用一次；接住加载期的错配，不让启动跟着炸。 */
    public static void register() {
        try {
            CellFormats.registerMatrixProbe(new EcoCellFormat());
            LOGGER.info("[AE2-Pattern-Disk] 存储矩阵格式改由 ECO 的 cell handler 名单判定");
        } catch (LinkageError mismatch) {
            LOGGER.warn("[AE2-Pattern-Disk] 接不上 ECO 的 cell handler 名单，矩阵判定退回接口名探测", mismatch);
        }
    }

    @Override
    public boolean isMatrix(ItemStack stack) {
        try {
            return ECOStorageCells.isCellHandled(stack);
        } catch (RuntimeException | LinkageError broken) {
            // 表格与准入是按帧问的，抛出去就是每刷一遍抛一遍；这里按「读不出来」处理。
            LOGGER.debug("[AE2-Pattern-Disk] ECO 的 isCellHandled 失败，这张栈按「不是矩阵」处理", broken);
            return false;
        }
    }
}
