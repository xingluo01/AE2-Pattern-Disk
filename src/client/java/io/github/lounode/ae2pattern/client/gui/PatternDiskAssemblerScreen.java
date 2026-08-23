package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.menu.PatternDiskAssemblerMenu;

/**
 * Client screen for the pattern disk assembler (高效分子装配室).
 *
 * <p>Layout is fully declared in the ScreenStyle JSON ({@code assets/ae2/screens/pattern_disk_assembler.json}):
 * one row per execution unit (9 crafting-grid slots + output slot), all positioned statically. The
 * screen paints an aggregate progress bar along the top and a thin per-row progress bar over each
 * unit's grid using the {@code @GuiSync}-broadcast per-unit progress.</p>
 */
public class PatternDiskAssemblerScreen extends UpgradeableScreen<PatternDiskAssemblerMenu> {

    private static final int THREADS = PatternDiskAssemblerBlockEntity.THREADS;
    private static final int ROW_START_Y = 24;
    private static final int ROW_STEP = 18;
    private static final int ROW_STATUS_X = 30;
    private static final int GRID_WIDTH = 9 * 18;

    public PatternDiskAssemblerScreen(PatternDiskAssemblerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/pattern_disk_assembler.json"));
    }

    /**
     * Paints the top status line (running threads) and a thin progress bar under each unit grid,
     * reflecting the live per-unit progress synced by the menu.
     */
    @Override
    public void drawFG(GuiGraphics gui, int mouseX, int mouseY, int guiLeft, int guiTop) {
        super.drawFG(gui, mouseX, mouseY, guiLeft, guiTop);

        var menu = getMenu();
        int running = menu.getRunningThreads();
        // Status line on its own row below the title to avoid overlapping the (longer) English title.
        Component status = Component.literal("(" + running + "/" + THREADS + ")")
                .withStyle(ChatFormatting.GRAY);
        gui.drawString(getMinecraft().font, status, guiLeft + 8, guiTop + 15, 0xFFFFFF, false);

        // Per-row progress bar overlays, one per execution unit.
        for (int row = 0; row < THREADS; row++) {
            int y = ROW_START_Y + row * ROW_STEP;
            int pct = menu.getUnitProgress(row);
            if (!menu.isUnitBusy(row)) {
                pct = 0;
            }
            // y+16 keeps the 2px bar within the row's 18px slot line (avoids the 1px overlap below).
            drawProgressBar(gui, guiLeft + ROW_STATUS_X, guiTop + y + 16, GRID_WIDTH, 2, pct, 100);
        }
    }

    /**
     * Draws a small horizontal progress bar at (x, y) with the given size, filled to {@code cur/max}.
     */
    private void drawProgressBar(GuiGraphics gui, int x, int y, int width, int height, int cur, int max) {
        int border = 0xFF000000;
        int bg = 0xFF2A2A2A;
        int fill = 0xFF00C853;
        gui.fill(x, y, x + width, y + height, border);
        int innerW = Math.max(0, width - 2);
        gui.fill(x + 1, y + 1, x + 1 + innerW, y + height - 1, bg);
        if (cur > 0 && max > 0) {
            int fw = (int) ((long) innerW * cur / max);
            gui.fill(x + 1, y + 1, x + 1 + fw, y + height - 1, fill);
        }
    }
}
