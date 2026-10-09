package io.github.lounode.ae2pattern.client.integration.megacells;

import java.lang.reflect.Method;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.components.Button;
import net.minecraft.world.item.ItemStack;

/**
 * MEGA Cells 那枚「大宗压缩截断」按钮的构造与刷新，全部走反射。
 *
 * <p>为什么不自己画一枚：MEGA 的 {@code gripe._90.megacells.client.screen.CompressionCutoffButton} 已经是
 * 一枚带图标、带 tooltip（{@code gui.tooltips.megacells.CompressionCutoff}）并会画出截断物本身的
 * {@code IconButton}。它挂在哪由 MEGA 自己的 mixin 决定（只挂在元件工作台），所以本模组在元件管理终端上
 * 自己 new 一枚同样的按钮，外观与行为才与那边一致——照抄一份图标与提示反而会跟它各自漂移。</p>
 *
 * <p>它是真实存在的公开类（包名 {@code gripe._90.megacells} 是 MEGA 作者的固定命名，不是逐构建混淆出来的），
 * 但 MEGA 只进运行时依赖，编译面里不能出现它的名字，因此这里用反射拿。</p>
 */
public final class MegaCutoffButtonFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.megacells.client");

    private static final String BUTTON_CLASS = "gripe._90.megacells.client.screen.CompressionCutoffButton";

    private MegaCutoffButtonFactory() {}

    /**
     * 建一枚 MEGA 的压缩截断按钮；MEGA 不在场（或它哪天改了类名）返回 null，调用方按「没有这枚按钮」处理。
     */
    @Nullable
    public static Button create(Button.OnPress onPress) {
        try {
            Class<?> type = Class.forName(BUTTON_CLASS);
            return (Button) type.getConstructor(Button.OnPress.class).newInstance(onPress);
        } catch (Throwable absent) {
            LOGGER.debug("[megacells] compression cutoff button unavailable", absent);
            return null;
        }
    }

    /** 把按钮上的图标设成当前截断物（MEGA 自己每帧也这么刷）。 */
    public static void setItem(Button button, ItemStack stack) {
        try {
            Method method = button.getClass().getMethod("setItem", ItemStack.class);
            method.invoke(button, stack);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            LOGGER.debug("[megacells] cutoff button setItem unavailable", absent);
        }
    }

    /**
     * 显隐：MEGA 的按钮是 AE2 的 {@code IconButton}，AE2 那套把 visible 与 active 一起关（并让 TAB 焦点跳开）；
     * 万一哪天不是了，退回 vanilla 的两个字段。
     */
    public static void setVisible(Button button, boolean visible) {
        if (button instanceof appeng.client.gui.widgets.IconButton iconButton) {
            iconButton.setVisibility(visible);
        } else {
            button.visible = visible;
            button.active = visible;
        }
    }
}
