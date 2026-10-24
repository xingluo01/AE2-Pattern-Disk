package io.github.lounode.ae2pattern.client.integration.neoecoae;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.components.AbstractWidget;
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
     * 创建 NEO ECO 的 UploadButton，NEO ECO 不在场时返回 null。
     *
     * <p>坐标由调用方（{@code RightButtonColumn}）统一排，所以这里给 0,0。返回控件而不再由本类
     * {@code addWidget}：右侧按钮列要把它们当成自己的槽来摆，并靠列的 bounds 自动进 JEI 排除区。</p>
     */
    @Nullable
    public static AbstractWidget createUploadButtonIfPresent(PatternDiskEncodingTermScreen screen) {
        if (!hasUploadTarget()) {
            return null;
        }
        return NeoECOClientButton.createButton(0, 0, () -> screen.getMenu().uploadPattern());
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
