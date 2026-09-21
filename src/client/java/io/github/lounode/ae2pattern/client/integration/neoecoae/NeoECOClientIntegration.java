package io.github.lounode.ae2pattern.client.integration.neoecoae;

import net.minecraft.world.item.ItemStack;

import net.neoforged.fml.ModList;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;
import io.github.lounode.ae2pattern.integration.neoecoae.NeoECOTypes;

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
     * Adds the UploadButton to the screen when NEO ECO is installed and can actually take the pattern.
     * Safe to call unconditionally: without either, the button is never created.
     */
    public static void addUploadButtonIfPresent(PatternDiskEncodingTermScreen screen, int left, int top) {
        if (!hasUploadTarget()) return;
        screen.addWidget(NeoECOClientButton.createButton(left, top, () -> screen.getMenu().uploadPattern()));
    }

    /** @return neoecoae 模组是否在场；「按钮能不能真的贴上去」另见 {@link #hasUploadTarget()}。 */
    public static boolean isLoaded() {
        try {
            return ModList.get().isLoaded("neoecoae");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 服务端的 ECO 集成是否真能接上传：与 {@code NeoECOIntegration.apply()} 的闸门同一份判据。
     *
     * <p>少判一项就多一个点了没反应的按钮——尤其「有辅助存储、但没有报告式上传入口」的构建，服务端那时根本
     * 不注册处理器。按钮本体的类也在这儿：缺了它是在 {@code init()} 里崩 {@code NoClassDefFoundError}，
     * 不是「少画一个按钮」。</p>
     *
     * <p>名字一律取 {@link NeoECOTypes} 的字面串：直接引用这些类型，没装 ECO 的客户端会崩在类加载。</p>
     */
    public static boolean hasUploadTarget() {
        return isLoaded()
                && classPresent(NeoECOTypes.BUS)
                && classPresent(NeoECOTypes.AUXILIARY_STORE)
                && classPresent(NeoECOTypes.PREPARED_PATTERN)
                && classPresent(NeoECOTypes.UPLOAD_BUTTON)
                && uploadEntriesPresent();
    }

    /**
     * 服务端上传要用的两个入口在不在：报告式上传（区分「容器吃掉配方」与「槽位收下物品」）与替代物查询。
     * 缺任一个服务端都不装上传处理器，所以这里一起判。
     *
     * <p>不再单独判 "ALREADY_PRESENT" 常量：官方 v21.1.2 也有它，判它的收益不足以再往客户端的探测表里加一个类。</p>
     */
    private static boolean uploadEntriesPresent() {
        try {
            var service = Class.forName(NeoECOTypes.STORAGE_SERVICE, false, loader());
            var prepared = Class.forName(NeoECOTypes.PREPARED_PATTERN, false, loader());
            service.getMethod(NeoECOTypes.REPORTING_ENTRY, prepared);
            service.getMethod(NeoECOTypes.BLANK_REPLACEMENT_ENTRY, ItemStack.class);
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /** @return 类是否存在；{@code initialize=false} 是为了只问「在不在」，不触发对方的静态初始化 */
    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, loader());
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    private static ClassLoader loader() {
        return NeoECOClientIntegration.class.getClassLoader();
    }
}
