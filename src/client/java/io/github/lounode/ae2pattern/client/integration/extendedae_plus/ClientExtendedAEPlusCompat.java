package io.github.lounode.ae2pattern.client.integration.extendedae_plus;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.style.ScreenStyle;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;

/**
 * 客户端侧的适配入口：把「带契约的 EAE+ 在场」这件事变成一屏，而不把适配子类的名字带进总是加载的类。
 *
 * <p>理由与 {@code ExtendedAEPlusCompat.createUploadMenu} 完全相同，只是这边实现的是客户端接口
 * {@code IPatternUploadTerminal}：类被加载时会连带解析它 implements 的接口，而 {@code if} 守卫只挡执行、
 * 不挡加载。{@code registerEncodingTerminalScreen} 所在的类在客户端界面注册时就会被加载，所以那里只能看见
 * 本类的方法名，看不见 {@code ExtendedAEPlusUploadScreen}。0.4.0 在没装 EAE+ 的客户端上崩在
 * {@code IPatternUploadMenu} 上，就是这个坑的另一半。</p>
 *
 * <p>本类自身零 EAE+ 引用：只有目标类的字符串、以及它构造器的参数类型（均为本模组/原版/AE2 类型）。</p>
 */
public final class ClientExtendedAEPlusCompat {

    private static final String UPLOAD_SCREEN_CLASS =
            "io.github.lounode.ae2pattern.client.integration.extendedae_plus.ExtendedAEPlusUploadScreen";

    private ClientExtendedAEPlusCompat() {
    }

    /**
     * Instantiates the upload screen adapter.
     *
     * <p>Only call this once the contract probe says the interfaces are there; loading the adapter resolves
     * {@code IPatternUploadTerminal}.</p>
     */
    public static PatternDiskEncodingTermScreen createUploadScreen(
            io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        try {
            return (PatternDiskEncodingTermScreen) Class
                    .forName(UPLOAD_SCREEN_CLASS, true, ClientExtendedAEPlusCompat.class.getClassLoader())
                    .getConstructor(io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu.class,
                            Inventory.class, Component.class, ScreenStyle.class)
                    .newInstance(menu, playerInventory, title, style);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ExtendedAE Plus upload screen adapter failed to load", e);
        }
    }
}
