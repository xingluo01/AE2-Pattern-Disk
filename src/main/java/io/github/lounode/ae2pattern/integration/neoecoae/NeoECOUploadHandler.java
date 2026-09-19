package io.github.lounode.ae2pattern.integration.neoecoae;

import java.util.function.Consumer;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

import cn.dancingsnow.neoecoae.api.IECOPatternStorageService;

/**
 * Contains the upload logic that references NEO ECO types.
 * Only loaded when neoecoae is present and the integration is active.
 */
final class NeoECOUploadHandler {
    private NeoECOUploadHandler() {
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
            if (service != null && service.getPatternStorage().insertPattern(encoded.copy())) {
                menu.clearEncodedPatternAndReturnBlank();
            }
        };
    }
}