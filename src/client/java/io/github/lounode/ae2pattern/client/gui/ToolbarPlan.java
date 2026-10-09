package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.widgets.VerticalButtonBar;

/**
 * 左侧工具栏的「白名单 + 顺序」调度。
 *
 * <p>AE2 那根 {@link VerticalButtonBar} 只提供「加」：没有插入、也没有移除接口，所以按钮的先后等于挂载顺序，
 * 而这个顺序是三方混出来的——AE2 自己在 {@code init()} 里挂一批、本模组挂几枚、其它模组（AE2WTLib 的切换
 * 终端、闪电科技的频率卡）再往同一条栏追几枚。谁先谁后、哪一枚该出现，光看代码看不出来。</p>
 *
 * <p>于是这里换一种管法：给每个屏幕一份<b>清单</b>（按钮的槽位名，按想要的先后排列），再把栏里每个按钮认成
 * 一个槽位名。清单里有的按清单顺序排好，<b>清单里没有的一律隐藏并从栏里摘掉</b>。于是「顺序」与「显隐」由同一
 * 份清单决定，不在这份清单里的东西不可能悄悄冒出来——包括以后新装的模组往里追的按钮。</p>
 *
 * <p>两个已知代价，都是刻意的：清单是白名单，某天某个被隐藏的按钮确实有用时，症状是「它不见了」而不是报错；
 * 认不出身份的按钮按「不在清单里」处理（隐藏），所以这里要留一行 debug 日志，免得将来无从查起。</p>
 */
public final class ToolbarPlan {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolbarPlan.class);
    private static final String TOOLBAR_FIELD = "verticalToolbar";
    private static final String BUTTONS_FIELD = "buttons";

    private ToolbarPlan() {
    }

    /**
     * 按清单排布这根工具栏。调用点必须是 {@code init()} 里 {@code super.init()} 之后——栏是那时才装好的。
     *
     * @param label 日志里用的屏幕名
     * @param plan  想要的槽位名序列。同一个名字写两次表示「这一类有两枚、按它们在栏里的先后各取一枚」
     *              （闪电科技那两枚就是这么分辨的：我们对它们的句柄不稳定，只有先后是确定的）。
     * @param slotOf 按钮 → 槽位名；认不出来返回 null（按不在清单里处理）
     */
    public static void apply(Screen screen, String label, List<String> plan, Function<Button, String> slotOf) {
        buttons(screen, toolbar -> {
            var wanted = new ArrayList<Button>();
            for (var slot : plan) {
                var found = false;
                for (var button : toolbar) {
                    if (wanted.contains(button) || !slot.equals(slotOf.apply(button))) {
                        continue;
                    }
                    wanted.add(button);
                    found = true;
                    break;
                }
                if (!found) {
                    // 计划里有、栏里没有：可预期（某个上游按钮只在它的前置在场时才出现），但也可能是身份认法
                    // 跟不上上游改名——接一条 debug，排查工具栏时能一眼看出是哪一项没对上。
                    LOGGER.debug("[toolbar] {} has no button for slot {}", label, slot);
                }
            }
            var dropped = new ArrayList<Button>();
            for (var button : toolbar) {
                if (!wanted.contains(button)) {
                    dropped.add(button);
                }
            }
            for (var button : dropped) {
                // 认得出身份的按名字记，认不出的记类名——后者是「以后新装的模组追了一枚按钮」的主要形态。
                LOGGER.debug("[toolbar] {} hides {}", label, button.getClass().getName());
                if (button instanceof appeng.client.gui.widgets.IconButton iconButton) {
                    // AE2 的按钮：一次关掉 visible 与 active，TAB 焦点路径也取不到它。
                    iconButton.setVisibility(false);
                } else {
                    button.visible = false;
                    button.active = false;
                }
            }
            dropped.clear();
            toolbar.clear();
            // 只留清单里认出来的：被摘掉的那些不会再被 populateScreen 装回屏上。
            toolbar.addAll(wanted);
        });
    }

    private static void buttons(Screen screen, java.util.function.Consumer<List<Button>> change) {
        try {
            var barField = AEBaseScreen.class.getDeclaredField(TOOLBAR_FIELD);
            barField.setAccessible(true);
            if (!(barField.get(screen) instanceof VerticalButtonBar bar)) {
                return;
            }
            var buttonsField = VerticalButtonBar.class.getDeclaredField(BUTTONS_FIELD);
            buttonsField.setAccessible(true);
            if (buttonsField.get(bar) instanceof List<?> raw) {
                @SuppressWarnings("unchecked")
                var list = (List<Button>) raw;
                change.accept(list);
            }
        } catch (Throwable e) {
            // 摆位置这种小事不该把界面弄坏：AE2 改内部结构时按钮仍在，只是回到默认顺序与显隐。
            LOGGER.debug("[toolbar] reordering skipped", e);
        }
    }
}
