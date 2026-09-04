package io.github.lounode.ae2pattern.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.api.config.ActionItems;
import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.GuiText;
import appeng.menu.SlotSemantics;

public class ProcessingEncodingPanel extends DiskEncodingModePanel {
    private static final Blitter BG = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(121, 0, 115, 66);

    // states.png (0,0,16,8)：左侧为启用同物品合并，右侧为禁用同物品合并
    private static final Blitter MERGE_ON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(0, 0, 8, 8);
    private static final Blitter MERGE_OFF = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(8, 0, 8, 8);

    // states.png (0,8,8,8)：主产物轮换（主产物 → 第一个副产物，原主产物排到末尾）
    private static final Blitter ROTATE_PRIMARY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(0, 8, 8, 8);

    // states.png (16,8,16,8)：左侧 8x8 = 倍乘，右侧 8x8 = 倍除
    private static final Blitter MULTIPLY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(16, 8, 8, 8);
    private static final Blitter DIVIDE = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(24, 8, 8, 8);

    private final ActionButton clearBtn;
    private final StatesToggleButton mergeItemsBtn;
    private final StatesIconButton rotatePrimaryBtn;
    private final StatesIconButton multiplyBtn;
    private final StatesIconButton divideBtn;
    private final ToggleButton substitutionsBtn;
    private final Scrollbar scrollbar;

    public ProcessingEncodingPanel(PatternDiskEncodingTermScreen screen, WidgetContainer widgets) {
        super(screen, widgets);

        clearBtn = new ActionButton(ActionItems.S_CLOSE, act -> menu.clear());
        clearBtn.setHalfSize(true);
        clearBtn.setDisableBackground(true);
        widgets.add("processingClearPattern", clearBtn);

        // 同物品合并切换（states.png 左启右禁）
        this.mergeItemsBtn = new StatesToggleButton(MERGE_ON, MERGE_OFF, menu::setMergeSameItems);
        this.mergeItemsBtn.setHalfSize(true);
        // 常态/选中保持同一水平高度，不做 hover 下压动画
        this.mergeItemsBtn.setPressAnimation(false);
        this.mergeItemsBtn.setTooltipOn(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.merge_same_items_on"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.merge_same_items_desc_on")));
        this.mergeItemsBtn.setTooltipOff(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.merge_same_items_off"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.merge_same_items_desc_off")));
        widgets.add("processingMergeItems", this.mergeItemsBtn);

        // 主产物轮换（states.png 0,8,8,8）：主产物更换为第一个副产物，原主产物排列到最后。单形态按钮：
        // 常态与悬停保持同一水平高度，不做 hover 下压动画。
        this.rotatePrimaryBtn = new StatesIconButton(() -> ROTATE_PRIMARY, act -> menu.cycleProcessingOutput());
        this.rotatePrimaryBtn.setHalfSize(true);
        this.rotatePrimaryBtn.setPressAnimation(false);
        this.rotatePrimaryBtn.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.rotate_primary"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.rotate_primary_desc")));
        widgets.add("processingRotatePrimary", this.rotatePrimaryBtn);

        // 倍乘按钮：默认×2，Shift=×3，Ctrl=×5，Alt=×10；作用于全部输入与输出
        this.multiplyBtn = new StatesIconButton(() -> MULTIPLY, act -> menu.multiplyOutput(
                multiplierFromModifiers()));
        this.multiplyBtn.setHalfSize(true);
        this.multiplyBtn.setPressAnimation(false);
        this.multiplyBtn.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.multiply"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.multiply_line1"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.multiply_line2"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.multiply_line3"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.multiply_line4")));
        widgets.add("processingMultiply", this.multiplyBtn);

        // 倍除按钮：默认÷2，Shift=÷3，Ctrl=÷5，Alt=÷10；任一物品无法整除时整体不处理
        this.divideBtn = new StatesIconButton(() -> DIVIDE, act -> menu.divideOutput(
                multiplierFromModifiers()));
        this.divideBtn.setHalfSize(true);
        this.divideBtn.setPressAnimation(false);
        this.divideBtn.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.divide"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.divide_line1"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.divide_line2"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.divide_line3"),
                Component.translatable("gui.ae2_pattern_disk.encoding_terminal.divide_line4")));
        widgets.add("processingDivide", this.divideBtn);

        // 可替换启用按钮（从合成覆盖层复制，位于清空按钮垂直对齐下侧 2px）
        this.substitutionsBtn = new ToggleButton(
                Icon.S_SUBSTITUTION_ENABLED,
                Icon.S_SUBSTITUTION_DISABLED,
                menu::setSubstitute);
        this.substitutionsBtn.setHalfSize(true);
        this.substitutionsBtn.setDisableBackground(true);
        this.substitutionsBtn.setTooltipOn(List.of(
                ButtonToolTips.SubstitutionsOn.text(),
                ButtonToolTips.SubstitutionsDescEnabled.text()));
        this.substitutionsBtn.setTooltipOff(List.of(
                ButtonToolTips.SubstitutionsOff.text(),
                ButtonToolTips.SubstitutionsDescDisabled.text()));
        widgets.add("processingSubstitutions", this.substitutionsBtn);

        this.scrollbar = widgets.addScrollBar("processingPatternModeScrollbar", Scrollbar.SMALL);
        this.scrollbar.setRange(0, menu.getProcessingInputSlots().length / 3 - 3, 3);
        this.scrollbar.setCaptureMouseWheel(false);
    }

    @Override
    public void updateBeforeRender() {
        screen.repositionSlots(SlotSemantics.PROCESSING_INPUTS);
        screen.repositionSlots(SlotSemantics.PROCESSING_OUTPUTS);

        this.mergeItemsBtn.setState(this.menu.isMergeSameItems());
        this.substitutionsBtn.setState(this.menu.isSubstitute());

        for (int i = 0; i < menu.getProcessingInputSlots().length; i++) {
            var slot = menu.getProcessingInputSlots()[i];
            var effectiveRow = (i / 3) - scrollbar.getCurrentScroll();
            slot.setActive(effectiveRow >= 0 && effectiveRow < 3);
            slot.y -= scrollbar.getCurrentScroll() * 18;
        }
        for (int i = 0; i < menu.getProcessingOutputSlots().length; i++) {
            var slot = menu.getProcessingOutputSlots()[i];
            var effectiveRow = i - scrollbar.getCurrentScroll();
            slot.setActive(effectiveRow >= 0 && effectiveRow < 3);
            slot.y -= scrollbar.getCurrentScroll() * 18;
        }
        updateTooltipVisibility();
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        BG.dest(bounds.getX() + x, bounds.getY() + y).blit(guiGraphics);
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        return scrollbar.onMouseWheel(mousePos, delta);
    }

    private void updateTooltipVisibility() {
        widgets.setTooltipAreaEnabled("processing-primary-output", visible && scrollbar.getCurrentScroll() == 0);
        widgets.setTooltipAreaEnabled("processing-optional-output1", visible && scrollbar.getCurrentScroll() > 0);
        widgets.setTooltipAreaEnabled("processing-optional-output2", visible);
        widgets.setTooltipAreaEnabled("processing-optional-output3", visible);
    }

    @Override
    Icon getIcon() {
        return Icon.TAB_PROCESSING;
    }

    @Override
    public Component getTabTooltip() {
        return GuiText.ProcessingPattern.text();
    }

    /**
     * 根据鼠标修饰键选择倍率：默认 2，Shift=3，Ctrl=5，Alt=10。
     */
    private int multiplierFromModifiers() {
        if (Screen.hasShiftDown()) {
            return 3;
        } else if (Screen.hasControlDown()) {
            return 5;
        } else if (Screen.hasAltDown()) {
            return 10;
        }
        return 2;
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);

        scrollbar.setVisible(visible);
        clearBtn.setVisibility(visible);
        mergeItemsBtn.setVisibility(visible);
        // 主产物轮换按钮常态显示（存在任何输出即可点击，无副产物时无操作）
        rotatePrimaryBtn.setVisibility(visible);
        multiplyBtn.setVisibility(visible);
        divideBtn.setVisibility(visible);
        substitutionsBtn.setVisibility(visible);

        screen.setSlotsHidden(SlotSemantics.PROCESSING_INPUTS, !visible);
        screen.setSlotsHidden(SlotSemantics.PROCESSING_OUTPUTS, !visible);

        updateTooltipVisibility();
    }
}