package io.github.lounode.ae2pattern.client.gui;

import java.util.List;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.api.config.Settings;
import appeng.api.config.SortOrder;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.SettingToggleButton;

/**
 * 「附加排序」开关按钮：本模组自己的终端用它（管理终端的屏幕继承编码终端，同一枚）。
 *
 * <p>它按 mod 排序时才露面（其它档位下二级排序没有意义）。打开后 mod 组内改按名字里的数值排
 * （1k &lt; 4k &lt; 16k &lt; 64k &lt; 256k &lt; 1M），关闭则回到 AE2 原本的字面序；排序规则本身在
 * {@link io.github.lounode.ae2pattern.client.sort.NaturalSort}，这里只管界面状态。</p>
 *
 * <p>开关是纯客户端视图状态，挂在使用它的屏幕上：换屏即失效，不写服务端设置。</p>
 */
public final class NaturalSortButton {

    // states.png (96,32) = 1/4 比大小 = 数值序，(112,32) = 叉 = 关
    private static final Blitter ICON_NATURAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(96, 32, 16, 16);
    private static final Blitter ICON_LITERAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(112, 32, 16, 16);
    // states.png (208,224,36,20) 按钮背景：左半常态，右半光标选中
    private static final Blitter BACKGROUND_NORMAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(208, 224, 18, 20);
    private static final Blitter BACKGROUND_HOVER = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(226, 224, 18, 20);

    private final StatesIconButton button;
    private boolean enabled = true;
    /** 上次写进按钮的提示语状态；没有变化就不重建那几行文本。 */
    private Boolean lastTooltipState;

    /**
     * @param onChanged 开关变化后要跑的动作（重排网格），只在切换时调一次。
     */
    public NaturalSortButton(Runnable onChanged) {
        // 默认开：与「按 mod 排序」这个档位本身的取向一致，关掉才是回到字面序。
        this.button = new StatesIconButton(
                () -> this.enabled ? ICON_NATURAL : ICON_LITERAL,
                btn -> {
                    this.enabled = !this.enabled;
                    onChanged.run();
                });
        this.button.setBackground(BACKGROUND_NORMAL, BACKGROUND_HOVER);
    }

    /** 交给工具栏摆放。 */
    public StatesIconButton widget() {
        return this.button;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * 每帧一次：只在「按 mod」时露面（其它档位下它无从生效），提示语第一行报的是当前状态，
     * 后两行把两条附加规则各自说清楚——与「隐藏槽位」那几个开关一个口径。
     */
    public void update(SortOrder order) {
        this.button.setVisibility(order == SortOrder.MOD);
        if (this.lastTooltipState == null || this.lastTooltipState != this.enabled) {
            this.lastTooltipState = this.enabled;
            this.button.setTooltip(List.of(
                    Component.translatable(this.enabled
                            ? "gui.ae2_pattern_disk.sort.additional.enable"
                            : "gui.ae2_pattern_disk.sort.additional.disable"),
                    Component.translatable("gui.ae2_pattern_disk.sort.additional.rule.group"),
                    Component.translatable("gui.ae2_pattern_disk.sort.additional.rule.numeric")));
        }
    }

    /**
     * 终端左侧工具栏里那枚「排序按」按钮（AE2 用 {@code SettingToggleButton} 装排序档位）。
     * 找不到就返回 null——摆错位置是小事，不该把界面弄坏。
     */
    public static Button findSortByButton(Screen screen) {
        for (var listener : screen.children()) {
            if (listener instanceof SettingToggleButton<?> toggle && toggle.getSetting() == Settings.SORT_BY) {
                return toggle;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "NaturalSortButton[enabled=" + this.enabled + "]";
    }
}
