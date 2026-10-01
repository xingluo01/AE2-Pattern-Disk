package io.github.lounode.ae2pattern.client.integration.extendedae_plus;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.style.ScreenStyle;

import com.extendedae_plus.api.upload.IPatternUploadTerminal;

import io.github.lounode.ae2pattern.client.integration.ae2wtlib.PatternDiskWirelessEncodingTermScreen;
import io.github.lounode.ae2pattern.client.integration.neoecoae.NeoECOClientIntegration;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessEncodingTermMenu;

/**
 * 无线版编码终端上的 EAE+ 上传按钮适配器：与面板版的
 * {@link ExtendedAEPlusUploadScreen} 一一对应，只是把基类换成无线屏幕，
 * 好让升级面板、终端切换按钮、热键这些无线侧的东西都还在。
 *
 * <p>接口单独落在子类上、并且必须经
 * {@code ClientExtendedAEPlusCompat.createWirelessUploadScreen} 反射创建，
 * 理由与面板版完全相同：EAE+ 缺席时 {@code IPatternUploadTerminal} 这个接口类根本不存在，
 * 而本类会在客户端注册界面时被加载。**本类与它的构造器必须保持 public。**</p>
 */
public class ExtendedAEPlusWirelessUploadScreen extends PatternDiskWirelessEncodingTermScreen
        implements IPatternUploadTerminal {

    public ExtendedAEPlusWirelessUploadScreen(PatternDiskWirelessEncodingTermMenu menu, Inventory playerInventory,
            Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    /** 与面板版同一口径：EAE+ 只在返回值 > 0 时采用，所以显式给标准的 1.0。 */
    @Override
    public float getUploadScale() {
        return 1.0F;
    }

    /**
     * @return EAE+ 上传按钮的位置（屏幕绝对坐标）；只有真挂了 ECO 上传按钮时才给锚点，
     *         否则返回 null 交给 EAE+ 自己定位。判定与父类装按钮的条件同源，免得为不存在的按钮留空。
     */
    @Nullable
    @Override
    public Rect2i getUploadAnchor() {
        if (!NeoECOClientIntegration.hasUploadTarget()) {
            return null;
        }
        // 算式与父类 neoEcoUploadButtonBounds() 同源：贴在 ECO 按钮下沿（间隙 0px），x 左移 1px 对齐底板左沿。
        var eco = neoEcoUploadButtonBounds();
        return new Rect2i(eco.getX() - 1, eco.getY() + eco.getHeight(), 0, 0);
    }
}
