package io.github.lounode.ae2pattern.client.gui;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;

import appeng.api.client.AEKeyRendering;
import appeng.api.config.LockCraftingMode;
import appeng.api.stacks.AmountFormat;
import appeng.client.Point;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.Icon;
import appeng.client.gui.Tooltip;
import appeng.core.localization.GuiText;
import appeng.core.localization.InGameTooltip;

import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * Shows why crafting is currently locked, next to the lock-crafting toggle of the provider GUI.
 *
 * <p>AE2 ships the same widget as {@code PatternProviderLockReason}, but its constructor requires the
 * screen to be a {@code PatternProviderScreen}, which this screen cannot be: that screen is typed
 * against AE2's {@code PatternProviderMenu}, and inheriting that menu would put the provider's
 * <em>mirrored</em> pattern inventory into the GUI as if it were the real one. The drawing below is the
 * same, driven by the getters this project's menu exposes.</p>
 */
public class LockReasonWidget implements ICompositeWidget {

    private final PatternDiskProviderMenu menu;

    private boolean visible;
    private int x;
    private int y;

    public LockReasonWidget(PatternDiskProviderMenu menu) {
        this.menu = menu;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setPosition(Point position) {
        this.x = position.getX();
        this.y = position.getY();
    }

    @Override
    public void setSize(int width, int height) {
        // The reason is one line of text; the style's bounds only carry its position.
    }

    @Override
    public Rect2i getBounds() {
        return new Rect2i(x, y, 126, 16);
    }

    @Override
    public void drawForegroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        Icon icon;
        Component lockStatusText;
        if (menu.getCraftingLockedReason() == LockCraftingMode.NONE) {
            icon = Icon.UNLOCKED;
            lockStatusText = GuiText.CraftingLockIsUnlocked.text()
                    .setStyle(Style.EMPTY.withColor(Mth.color(125 / 255f, 169 / 255f, 210 / 255f)));
        } else {
            icon = Icon.LOCKED;
            lockStatusText = GuiText.CraftingLockIsLocked.text()
                    .setStyle(Style.EMPTY.withColor(Mth.color(193 / 255f, 66 / 255f, 75 / 255f)));
        }

        icon.getBlitter().dest(x, y).blit(guiGraphics);
        guiGraphics.drawString(Minecraft.getInstance().font, lockStatusText, x + 15, y + 5, -1, false);
    }

    @Nullable
    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        var tooltip = switch (menu.getCraftingLockedReason()) {
            case NONE -> null;
            case LOCK_UNTIL_PULSE -> InGameTooltip.CraftingLockedUntilPulse.text();
            case LOCK_WHILE_HIGH -> InGameTooltip.CraftingLockedByRedstoneSignal.text();
            case LOCK_WHILE_LOW -> InGameTooltip.CraftingLockedByLackOfRedstoneSignal.text();
            case LOCK_UNTIL_RESULT -> {
                var stack = menu.getUnlockStack();
                Component stackName;
                Component stackAmount;
                if (stack != null) {
                    stackName = AEKeyRendering.getDisplayName(stack.what());
                    stackAmount = Component.literal(stack.what().formatAmount(stack.amount(), AmountFormat.FULL));
                } else {
                    // Locked until a result, but the server has not sent which one yet.
                    stackName = Component.literal("ERROR");
                    stackAmount = Component.literal("ERROR");
                }
                yield InGameTooltip.CraftingLockedUntilResult.text(stackName, stackAmount);
            }
        };

        return tooltip != null ? new Tooltip(tooltip) : null;
    }
}
