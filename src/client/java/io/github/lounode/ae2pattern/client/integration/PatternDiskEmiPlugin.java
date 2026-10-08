package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.menu.slot.FakeSlot;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

import io.github.lounode.ae2pattern.client.gui.AbstractPatternDiskTermScreen;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessEncodingTermMenu;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessManagementTermMenu;

/**
 * EMI 配方导入插件：注册样板磁盘编码终端的配方编码 handler，以及元件管理终端标记区的拖放。
 * 与原版 AE2 编码终端一致，EMI 会自动扫描 {@code @EmiEntrypoint} 注解并调用。
 *
 * <p>拖放这一条为什么本模组要自己写：EMI 那侧给 AE2 屏幕的支持是按 <b>AE2 自己的屏</b>挂的，本模组的屏不在
 * 其中，标记区那批 {@code FakeSlot} 于是接不到拖进来的东西。JEI 那条在
 * {@link JeiEncodingGhostHandler} 里，两边规则一致：拖放目标都取当前活动着的假槽，能不能收由槽自己回答。</p>
 *
 * <p><b>EMI 与 JEI 的一处机制差异</b>：JEI 查处理器会回溯父类，EMI 的拖放表按<b>精确类</b>查；所以这里用通用
 * 登记一次覆盖全部屏，再在 handler 里按基类收窄——逐类登记的话，以后每加一个终端屏都要回来补一条，漏了不报错、
 * 只表现为那一个屏拖不动。</p>
 */
@EmiEntrypoint
public class PatternDiskEmiPlugin implements EmiPlugin {

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void register(EmiRegistry registry) {
        registry.addRecipeHandler(PatternDiskEncodingTermMenu.TYPE,
                new DiskEncodePatternHandler());
        // 管理菜单有自己的 TYPE；它继承编码菜单，转移逻辑同一套，只是登记键要单独一条。
        registry.addRecipeHandler((MenuType) PatternDiskManagementTermMenu.TYPE,
                (StandardRecipeHandler) new DiskEncodePatternHandler(PatternDiskManagementTermMenu.class));
        // 无线版两个菜单同样各有自己的 TYPE（MenuType 之间没有父子关系，EMI 按实例查表），与 JEI 那份清单
        // （PatternDiskJeiPlugin）保持一致。
        registry.addRecipeHandler((MenuType) PatternDiskWirelessEncodingTermMenu.TYPE,
                (StandardRecipeHandler) new DiskEncodePatternHandler(PatternDiskWirelessEncodingTermMenu.class));
        registry.addRecipeHandler((MenuType) PatternDiskWirelessManagementTermMenu.TYPE,
                (StandardRecipeHandler) new DiskEncodePatternHandler(PatternDiskWirelessManagementTermMenu.class));

        // 元件管理终端的标记区（元件分区）：拖进来的物品设成那一格的标记。
        registry.addGenericDragDropHandler((EmiDragDropHandler) new EmiDragDropHandler.SlotBased<>(
                PatternDiskEmiPlugin::markerSlots, PatternDiskEmiPlugin::dropIntoMarker));
    }

    /** 拖放目标：当前活动的那些假槽（标记区的每一格都是一个 {@code FakeSlot}）。 */
    private static List<Slot> markerSlots(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof AbstractPatternDiskTermScreen<?> terminal)) {
            return List.of();
        }
        var out = new ArrayList<Slot>();
        for (var slot : terminal.getMenu().slots) {
            if (slot.isActive() && slot instanceof FakeSlot) {
                out.add(slot);
            }
        }
        return out;
    }

    /**
     * 落到某一格：取这一叠里第一个收得下的物品设成标记。
     *
     * <p><b>只认物品</b>：EMI 的 {@link EmiIngredient} 也能表示流体、化学品之类的自定义类型，而那要把 AE2 的
     * 桥（{@code GenericStack} 包装）搬过来才解得开——JEI 那条走了那套桥，这一条暂时没有，所以流体元件的分区
     * 在 EMI 下还拖不进去（用 JEI 拖或手动设置都正常）。收不收由 {@code FakeSlot} 自己回答：拖到不收它的格子
     * 上就是什么都不做，与在别处拖放的手感一致。</p>
     */
    private static void dropIntoMarker(AbstractContainerScreen<?> screen, Slot slot, EmiIngredient ingredient) {
        if (!(slot instanceof FakeSlot fakeSlot)) {
            return;
        }
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            ItemStack stack = emiStack.getItemStack();
            if (stack.isEmpty() || !fakeSlot.canSetFilterTo(stack)) {
                continue;
            }
            fakeSlot.setFilterTo(stack);
            return;
        }
    }
}
