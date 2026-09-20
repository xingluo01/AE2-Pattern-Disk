package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.widgets.VerticalButtonBar;

/**
 * 左侧工具栏里按钮的先后顺序。
 *
 * <p>AE2 那根 {@link VerticalButtonBar} 只给了「加」：没有插入、没有移除，而本模组需要「附加排序」紧跟在
 * 「排序按」后面、自家几枚按钮再按固定次序收尾，所以这里直接改动那根 bar 内部的按钮表。bar 的排布每帧按
 * 这张表的先后走，改表即改顺序。</p>
 *
 * <p>改不动（字段被改名、被加了限制）就什么都不做：按钮仍在，只是回到默认顺序——摆位置这种小事不该把
 * 界面弄坏。因此只用反射取两个字段，失败时留一条 debug 日志。</p>
 */
public final class ToolbarOrder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolbarOrder.class);
    private static final String TOOLBAR_FIELD = "verticalToolbar";
    private static final String BUTTONS_FIELD = "buttons";

    private ToolbarOrder() {
    }

    /** 把 {@code button} 挪到 {@code anchor} 后面一位；两者有一个不在栏里就什么都不做。 */
    public static void placeAfter(Screen screen, Button button, Button anchor) {
        buttons(screen, toolbar -> {
            int anchorIndex = indexOf(toolbar, anchor);
            if (anchorIndex < 0 || !toolbar.remove(button)) {
                return;
            }
            toolbar.add(Math.min(anchorIndex + 1, toolbar.size()), button);
        });
    }

    /** 把这枚按钮挪到末尾（其余按钮的相对次序不变）。 */
    public static void placeAtEnd(Screen screen, Button button) {
        placeAtEnd(screen, List.of(button));
    }

    /**
     * 把这几枚按钮挪到末尾，并按传入的先后排列。本来就不在这根栏里的按钮不会被硬塞回来（例如某个屏
     * 根本不挂它），因此只回收真的从表里取出来的那些。
     */
    public static void placeAtEnd(Screen screen, List<Button> order) {
        buttons(screen, toolbar -> {
            var removed = new ArrayList<Button>();
            for (var button : order) {
                if (toolbar.remove(button)) {
                    removed.add(button);
                }
            }
            if (removed.isEmpty()) {
                return;
            }
            toolbar.addAll(removed);
        });
    }

    private static int indexOf(List<Button> toolbar, Button button) {
        for (int i = 0; i < toolbar.size(); i++) {
            if (toolbar.get(i) == button) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
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
                change.accept((List<Button>) raw);
            }
        } catch (Throwable e) {
            LOGGER.debug("Toolbar reordering skipped", e);
        }
    }
}
