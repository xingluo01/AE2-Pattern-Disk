package io.github.lounode.ae2pattern.integration.extendedae_plus;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import com.extendedae_plus.api.upload.IPatternUploadMenu;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart;

/**
 * 让 EAE+ 的「上传到供应器」链路认得本终端：实现它的菜单侧接口，把编码槽交出去。
 *
 * <p>接口单独落在子类上，是因为 EAE+ 是可选依赖，它的接口类在缺席时根本不存在，而菜单类每次开界面都会
 * 被加载——把接口写进父类签名，没装 EAE+ 的玩家一开终端就 {@code NoClassDefFoundError}。本类只由
 * {@link PatternDiskEncodingTermMenu} 的工厂在 {@link ExtendedAEPlusCompat#hasUploadContract()} 为真时
 * 实例化，那一刻接口必然在场。父类添加别的 EAE+ 接口时同样照此办理，不要直接写进父类的 implements。</p>
 */
public class ExtendedAEPlusUploadMenu extends PatternDiskEncodingTermMenu implements IPatternUploadMenu {

    public ExtendedAEPlusUploadMenu(int id, Inventory playerInventory, PatternDiskEncodingTerminalPart host) {
        super(id, playerInventory, host);
    }

    @Override
    public Slot getEncodedPatternSlot() {
        return super.getEncodedPatternSlot();
    }
}
