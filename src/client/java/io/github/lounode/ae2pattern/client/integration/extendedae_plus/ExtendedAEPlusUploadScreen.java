package io.github.lounode.ae2pattern.client.integration.extendedae_plus;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.style.ScreenStyle;

import com.extendedae_plus.api.upload.IPatternUploadTerminal;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;
import io.github.lounode.ae2pattern.client.integration.neoecoae.NeoECOClientIntegration;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * 让 EAE+ 往本终端注入它的「上传到供应器」按钮，并把按钮摆到 NEO ECO 上传按钮的正下方（间隙 0px）。
 *
 * <p>接口单独落在子类上，理由同 {@code ExtendedAEPlusUploadMenu}：EAE+ 缺席时它的接口类不存在，
 * 而屏幕类会在客户端注册界面时就被加载。本类只在 EAE+ 在场时由界面工厂实例化。</p>
 */
public class ExtendedAEPlusUploadScreen extends PatternDiskEncodingTermScreen implements IPatternUploadTerminal {

    public ExtendedAEPlusUploadScreen(PatternDiskEncodingTermMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    /**
     * 上传按钮用标准 1.0 缩放（16x16，与 NEO ECO 按钮里那张 16x16 图标同尺寸；ECO 控件本身含底板是 18x20）。
     *
     * <p>EAE+ 自己的默认值是 0.75，且它只在 {@code > 0} 时采用接口的返回值（见其
     * {@code IPatternUploadTerminal#getUploadScale} 与注入按钮的 mixin），所以必须显式给正数。</p>
     */
    @Override
    public float getUploadScale() {
        return 1.0F;
    }

    /**
     * @return EAE+ 上传按钮的位置（屏幕绝对坐标）；本终端在右侧只在挂了 ECO 上传按钮时才给锚点，
     *         没挂就返回 null，让 EAE+ 按它自己的办法（从 encodePattern 按钮推）定位。
     */
    @Nullable
    @Override
    public Rect2i getUploadAnchor() {
        if (!NeoECOClientIntegration.isLoaded()) {
            return null;
        }
        // 位置只能自己算：EAE+ 是在 AEBaseScreen.init 的 TAIL 取锚点，而本屏幕的 ECO 按钮要等
        // super.init() 返回之后才创建，那一刻取不到那个控件。算式与父类的 neoEcoUploadButtonBounds() 同源。
        var eco = neoEcoUploadButtonBounds();
        // 宽高传 0：EAE+ 按 getUploadScale()（1.0）算尺寸，即 16x16。y 取 ECO 按钮下沿，两者间隙 0px；
        // x 再左移 1px：ECO 的底板与它的控件框同宽，但画在 x-1 处（见 neoecoae 的 UploadButton），实机里
        // 对齐的是那一条左沿。
        return new Rect2i(eco.getX() - 1, eco.getY() + eco.getHeight(), 0, 0);
    }
}
