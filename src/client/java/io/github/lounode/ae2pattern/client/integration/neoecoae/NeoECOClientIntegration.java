package io.github.lounode.ae2pattern.client.integration.neoecoae;

import net.neoforged.fml.ModList;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;

/**
 * Client-side entry point for the neoecoae upload button.
 * References no neoecoae types itself — the actual button creation
 * is deferred to {@link NeoECOClientButton} which is only loaded
 * when neoecoae is confirmed present.
 */
public final class NeoECOClientIntegration {
    private NeoECOClientIntegration() {
    }

    /**
     * Adds the UploadButton to the screen if neoecoae is installed.
     * Safe to call unconditionally: when neoecoae is absent the check
     * returns false and the button is never created.
     */
    public static void addUploadButtonIfPresent(PatternDiskEncodingTermScreen screen, int left, int top) {
        if (!isLoaded()) return;
        screen.addWidget(NeoECOClientButton.createButton(left, top, () -> screen.getMenu().uploadPattern()));
    }

    private static boolean isLoaded() {
        try {
            return ModList.get().isLoaded("neoecoae");
        } catch (Throwable ignored) {
            return false;
        }
    }
}