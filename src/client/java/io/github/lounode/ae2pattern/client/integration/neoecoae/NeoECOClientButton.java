package io.github.lounode.ae2pattern.client.integration.neoecoae;

import net.minecraft.client.gui.components.AbstractWidget;

import cn.dancingsnow.neoecoae.gui.widget.UploadButton;

/**
 * Holder for the actual UploadButton creation.
 * This class references neoecoae types and is only loaded when neoecoae
 * is present (called from {@link NeoECOClientIntegration} after the mod
 * presence check).
 */
final class NeoECOClientButton {
    private NeoECOClientButton() {
    }

    public static AbstractWidget createButton(int left, int top, Runnable onPress) {
        return new UploadButton(left, top, b -> onPress.run());
    }
}