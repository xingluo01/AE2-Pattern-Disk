package io.github.lounode.ae2pattern.client.gui;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import appeng.client.Point;
import appeng.client.gui.Icon;
import appeng.client.gui.Tooltip;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.Scrollbar;
import appeng.core.localization.GuiText;
import appeng.menu.SlotSemantics;

/**
 * 切石模式编码面板。覆盖层位于 pattern_modes.png (121,72) 115×66；
 * 内部画板 (157,79) 64×54，条目按钮常态/被选择/光标选中位于 (240,72)/(240,90)/(240,108) 16×18。
 */
public final class StonecuttingEncodingPanel extends DiskEncodingModePanel {
    private static final Blitter BG = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(121, 72, 115, 66);
    private static final Blitter BG_SLOT = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(240, 72, 16, 18);
    private static final Blitter BG_SLOT_SELECTED = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(240, 90, 16, 18);
    private static final Blitter BG_SLOT_HOVER = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/pattern_modes.png"))
            .src(240, 108, 16, 18);

    /** 内部画板相对覆盖层的偏移 (157-121, 79-72)。 */
    private static final int CANVAS_OFFSET_X = 36;
    private static final int CANVAS_OFFSET_Y = 7;
    /** 画板 64×54，4 列 2 行。 */
    private static final int COLS = 4;
    private static final int ROWS = 2;
    private static final int SLOT_W = 16;
    private static final int SLOT_H = 18;
    /** 行距 = 画板高 / 行数 = 27，列距 = 画板宽 / 列数 = 16。 */
    private static final int ROW_SPACING = 27;

    private final Scrollbar scrollbar;

    public StonecuttingEncodingPanel(PatternDiskEncodingTermScreen screen, WidgetContainer widgets) {
        super(screen, widgets);
        this.scrollbar = widgets.addScrollBar("stonecuttingPatternModeScrollbar", Scrollbar.SMALL);
        this.scrollbar.setRange(0, 0, COLS);
        this.scrollbar.setCaptureMouseWheel(false);
    }

    @Override
    public void updateBeforeRender() {
        var totalRows = (menu.getStonecuttingRecipes().size() + COLS - 1) / COLS;
        scrollbar.setRange(0, Math.max(0, totalRows - ROWS), ROWS);
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        BG.dest(bounds.getX() + x, bounds.getY() + y).blit(guiGraphics);
        drawRecipes(guiGraphics, bounds, mouse);
    }

    private RegistryAccess getRegistryAccess() {
        return Objects.requireNonNull(Minecraft.getInstance().level).registryAccess();
    }

    private void drawRecipes(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        var recipes = menu.getStonecuttingRecipes();
        var startIndex = scrollbar.getCurrentScroll() * COLS;
        var endIndex = startIndex + ROWS * COLS;

        var selectedRecipe = menu.getStonecuttingRecipeId();

        for (int i = startIndex; i < endIndex && i < recipes.size(); ++i) {
            var slotBounds = getRecipeBounds(i - startIndex);

            var recipe = recipes.get(i);
            boolean selected = selectedRecipe != null && selectedRecipe.equals(recipe.id());

            Blitter blitter = BG_SLOT;
            if (selected) {
                blitter = BG_SLOT_SELECTED;
            } else if (mouse.isIn(slotBounds)) {
                blitter = BG_SLOT_HOVER;
            }

            var renderX = bounds.getX() + slotBounds.getX();
            var renderY = bounds.getY() + slotBounds.getY();
            blitter.dest(renderX, renderY).blit(guiGraphics);
            ItemStack resultItem = recipe.value().getResultItem(getRegistryAccess());
            // 槽位背景 16×18，物品 16×16：水平对齐，垂直偏移 1px 使物品在槽位内居中
            guiGraphics.renderItem(resultItem, renderX, renderY + 1);
            guiGraphics.renderItemDecorations(Minecraft.getInstance().font, resultItem, renderX, renderY + 1);
        }
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        var recipe = getRecipeAt(mousePos);
        if (recipe != null) {
            menu.setStonecuttingRecipeId(recipe.id());
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_STONECUTTER_SELECT_RECIPE, 1.0F));
            return true;
        }
        return false;
    }

    @Nullable
    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        var recipe = getRecipeAt(new Point(mouseX, mouseY));
        if (recipe != null) {
            var lines = screen.getTooltipFromContainerItem(recipe.value().getResultItem(getRegistryAccess()));
            return new Tooltip(lines);
        }
        return null;
    }

    @Nullable
    private RecipeHolder<StonecutterRecipe> getRecipeAt(Point point) {
        var recipes = menu.getStonecuttingRecipes();

        if (!recipes.isEmpty()) {
            var startIndex = scrollbar.getCurrentScroll() * COLS;
            var endIndex = startIndex + COLS * ROWS;

            for (int i = startIndex; i < endIndex && i < recipes.size(); ++i) {
                var slotBounds = getRecipeBounds(i - startIndex);
                if (point.isIn(slotBounds)) {
                    return recipes.get(i);
                }
            }
        }

        return null;
    }

    /** 返回配方条目相对屏幕的边界（面板位置 + 画板偏移 + 行列）。 */
    private Rect2i getRecipeBounds(int index) {
        var col = index % COLS;
        var row = index / COLS;
        int slotX = x + CANVAS_OFFSET_X + col * SLOT_W;
        int slotY = y + CANVAS_OFFSET_Y + row * ROW_SPACING;
        return new Rect2i(slotX, slotY, SLOT_W, SLOT_H);
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        return scrollbar.onMouseWheel(mousePos, delta);
    }

    @Override
    Icon getIcon() {
        return Icon.TAB_STONECUTTING;
    }

    @Override
    public Component getTabTooltip() {
        return GuiText.StonecuttingPattern.text();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        scrollbar.setVisible(visible);
        screen.setSlotsHidden(SlotSemantics.STONECUTTING_INPUT, !visible);
    }
}