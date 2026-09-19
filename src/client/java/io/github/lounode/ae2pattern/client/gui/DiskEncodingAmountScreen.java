package io.github.lounode.ae2pattern.client.gui;

import java.util.function.Consumer;

import com.google.common.primitives.Longs;

import appeng.api.stacks.GenericStack;
import appeng.client.gui.AESubScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.NumberEntryType;
import appeng.client.gui.me.common.ClientDisplaySlot;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.client.gui.widgets.TabButton;
import appeng.core.localization.GuiText;
import appeng.menu.SlotSemantics;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * 精确设置处理模式过滤槽数量的子屏，外观与交互沿用 AE2 样板编码终端的同款对话框
 * （共享 {@code /screens/set_stock_amount.json} 的布局，只换本模组的样式路径）。
 *
 * <p>子屏不新建菜单：它复用父屏的 {@link PatternDiskEncodingTermMenu}，返回时由
 * {@link AESubScreen#returnToParent()} 清掉客户端槽位并切回父屏。</p>
 */
public class DiskEncodingAmountScreen
        extends AESubScreen<PatternDiskEncodingTermMenu, PatternDiskEncodingTermScreen> {

    private final NumberEntryWidget amount;
    private final GenericStack currentStack;
    private final Consumer<GenericStack> setter;

    public DiskEncodingAmountScreen(PatternDiskEncodingTermScreen parentScreen, GenericStack currentStack,
            Consumer<GenericStack> setter) {
        super(parentScreen, "/screens/ae2_pattern_disk/set_processing_pattern_amount.json");

        this.currentStack = currentStack;
        this.setter = setter;

        widgets.addButton("save", GuiText.Set.text(), this::confirm);

        var icon = getMenu().getHost().getMainMenuIcon();
        var back = new TabButton(Icon.BACK, icon.getHoverName(), btn -> returnToParent());
        widgets.add("back", back);

        this.amount = widgets.addNumberEntryWidget("amountToStock", NumberEntryType.of(currentStack.what()));
        this.amount.setLongValue(currentStack.amount());
        this.amount.setMaxValue(getMaxAmount());
        this.amount.setTextFieldStyle(style.getWidget("amountToStockInput"));
        this.amount.setMinValue(0);
        this.amount.setHideValidationIcon(true);
        this.amount.setOnConfirm(this::confirm);

        addClientSideSlot(new ClientDisplaySlot(currentStack), SlotSemantics.MACHINE_OUTPUT);
    }

    @Override
    protected void init() {
        super.init();

        // 这个对话框用不到终端工具栏，但父菜单有它的槽位，留着会画出来。
        setSlotsHidden(SlotSemantics.TOOLBOX, true);
    }

    private void confirm() {
        this.amount.getLongValue().ifPresent(newAmount -> {
            var clamped = Longs.constrainToRange(newAmount, 0, getMaxAmount());
            // 0 表示清空这个槽位，与右键清空同一条路径。
            setter.accept(clamped <= 0 ? null : new GenericStack(currentStack.what(), clamped));
            returnToParent();
        });
    }

    private long getMaxAmount() {
        return 999999 * (long) currentStack.what().getAmountPerUnit();
    }
}
