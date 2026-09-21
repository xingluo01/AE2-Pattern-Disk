package io.github.lounode.ae2pattern.integration.neoecoae;

import java.util.function.Consumer;

import net.minecraft.world.item.ItemStack;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.dancingsnow.neoecoae.api.ECOPatternInsertionResult;
import cn.dancingsnow.neoecoae.api.ECOPreparedPattern;
import cn.dancingsnow.neoecoae.api.IECOPatternStorageService;

/**
 * Contains the upload logic that references NEO ECO types.
 * Only loaded when neoecoae is present and the integration is active.
 */
final class NeoECOUploadHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    private NeoECOUploadHandler() {
    }

    /**
     * @return whether this NEO ECO build has both upload entries - the reporting one
     *         ({@code insertPreparedPatternReporting}), which is what tells a pattern a container consumed
     *         apart from one a slot merely took in, and the replacement query the refund path needs - and the
     *         result constant the bookkeeping branches on
     */
    static boolean reportingApiPresent() {
        try {
            IECOPatternStorageService.class.getMethod(NeoECOTypes.REPORTING_ENTRY, ECOPreparedPattern.class);
            IECOPatternStorageService.class.getMethod(NeoECOTypes.BLANK_REPLACEMENT_ENTRY, ItemStack.class);
        } catch (NoSuchMethodException absent) {
            // 旧构建没有这套入口：正常退场，不是错误。
            return false;
        } catch (LinkageError broken) {
            // 与「API 缺失」分开：链接不起来是这份构建本身的问题，退场之外还得留一个原因。
            LOGGER.warn("[AE2-Pattern-Disk] NEO ECO upload API could not be linked; the upload button stays off",
                    broken);
            return false;
        }
        // 缺这个常量时上传记账的分支无从判起，当作这套接口不完整。
        return NeoECOBusAccess.insertionResult(NeoECOTypes.ALREADY_PRESENT_CONSTANT) != null;
    }

    /**
     * @return a handler that uploads the menu's encoded pattern to the NEO ECO computation cluster
     */
    public static Consumer<PatternDiskEncodingTermMenu> create() {
        return menu -> {
            var node = menu.getGridNode();
            if (node == null || !node.isActive()) return;
            IGrid grid = node.getGrid();
            if (grid == null) return;

            var encoded = menu.getEncodedPatternItem();
            if (encoded.isEmpty() || !PatternDetailsHelper.isEncodedPattern(encoded)) return;

            var service = grid.getService(IECOPatternStorageService.class);
            if (service == null) return;

            var details = PatternDetailsHelper.decodePattern(encoded, menu.getPlayer().level());
            if (details == null) return;

            // Reporting form on purpose: the plain insertPattern only answers "did something take it", which is
            // exactly the question this caller must not act on alone - see the three cases below.
            var insertion = service.insertPreparedPatternReporting(
                    new ECOPreparedPattern(encoded, details, AEItemKey.of(encoded)));

            if (insertion.result() == ECOPatternInsertionResult.ALREADY_PRESENT) {
                // The network already reaches this recipe, which does not mean this item reached it. The match
                // can come from a recipe a pattern disk absorbed - counted into the network's pattern index -
                // and then clearing the slot destroys the only copy of the item. NEO ECO's own upload path pays
                // a blank here, so this one does too.
                refundAndClear(menu, service.blankPatternReplacementFor(encoded));
                return;
            }
            if (insertion.result() != ECOPatternInsertionResult.INSERTED) {
                // NO_SPACE / NO_TARGET / INCOMPATIBLE: nothing took it, so the pattern stays where the player
                // put it and nothing is owed.
                return;
            }
            if (!insertion.consumedSource()) {
                // A slot stored the pattern as an item, so the network already holds that exact stack and the
                // source slot is cleared without anything owed. Handing a blank back here would mint one for a
                // pattern that was merely moved.
                menu.clearEncodedPatternAfterUpload(ItemStack.EMPTY);
                return;
            }
            refundAndClear(menu, insertion.blankReplacement());
        };
    }

    /**
     * 退掉这次上传应付的东西，再把编码槽清空。
     *
     * <p>对方没给出替代物时只退回一步：契约里「吸收方给不出替代物」与「报告吸收了却不给替代物」两种情形都
     * 规定源样板留在原地——上传没完成，但玩家不白丢一张。</p>
     */
    private static void refundAndClear(PatternDiskEncodingTermMenu menu, ItemStack replacement) {
        if (replacement.isEmpty()) {
            return;
        }
        menu.clearEncodedPatternAfterUpload(replacement);
    }
}
