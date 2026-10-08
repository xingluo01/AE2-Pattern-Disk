package io.github.lounode.ae2pattern.client.integration.neoecoae;

import net.neoforged.fml.ModList;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;

/**
 * Client-side entry point for the neoecoae upload button.
 *
 * <p>References no neoecoae types itself: the button's own class is deferred to {@link NeoECOClientButton},
 * which is only loaded once neoecoae is confirmed present. That split is about the optional dependency rather
 * than about versions - a mod that is simply absent must not crash a client that never asked for it.</p>
 */
public final class NeoECOClientIntegration {

    private NeoECOClientIntegration() {
    }

    /**
     * Adds the UploadButton to the screen when NEO ECO is installed.
     * Safe to call unconditionally: without it, the button is never created.
     */
    public static void addUploadButtonIfPresent(PatternDiskEncodingTermScreen screen, int left, int top) {
        if (!hasUploadTarget()) return;
        screen.addWidget(NeoECOClientButton.createButton(left, top, () -> screen.getMenu().uploadPattern()));
    }

    /** @return neoecoae 模组是否在场。 */
    public static boolean isLoaded() {
        try {
            return ModList.get().isLoaded("neoecoae");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 屏幕该不该为上传按钮留位置。
     *
     * <p>与 {@link #isLoaded()} 同义，但这个名字是调用方读得懂的那个：EAE+ 的两个上传屏靠它决定要不要留空
     * （见 {@code ExtendedAEPlusUploadScreen}）。版本门槛由 {@code neoforge.mods.toml} 的依赖声明把关，
     * 不在客户端复判一遍。</p>
     */
    public static boolean hasUploadTarget() {
        return isLoaded();
    }
}
