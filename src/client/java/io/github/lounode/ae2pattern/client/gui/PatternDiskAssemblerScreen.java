package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import guideme.PageAnchor;

import appeng.client.gui.Icon;
import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.ProgressBar;
import appeng.menu.slot.AppEngSlot;

import io.github.lounode.ae2pattern.common.menu.PatternDiskAssemblerMenu;

/** EAE ex_molecular_assembler-style page screen for the eight parallel CraftUnits. */
public class PatternDiskAssemblerScreen extends UpgradeableScreen<PatternDiskAssemblerMenu> {

    /** 空样板槽覆盖层：states.png (240,80,16,16)。 */
    private static final Blitter PATTERN_SLOT_OVERLAY = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(240, 80, 16, 16);

    private final ProgressBar progressBar;
    private final IconButton nextPage;
    private final IconButton previousPage;

    public PatternDiskAssemblerScreen(PatternDiskAssemblerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc("/screens/ae2_pattern_disk/pattern_disk_assembler.json"));

        this.progressBar = new ProgressBar(menu, style.getImage("progressBar"), ProgressBar.Direction.VERTICAL);
        widgets.add("progressBar", progressBar);

        this.nextPage = new IconButton(button -> menu.nextPage()) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_RIGHT;
            }
        };
        this.previousPage = new IconButton(button -> menu.previousPage()) {
            @Override
            protected Icon getIcon() {
                return Icon.ARROW_LEFT;
            }
        };
        nextPage.setMessage(Component.translatable("gui.ae2_pattern_disk.pattern_disk_assembler.next"));
        previousPage.setMessage(Component.translatable("gui.ae2_pattern_disk.pattern_disk_assembler.previous"));
        addToLeftToolbar(nextPage);
        addToLeftToolbar(previousPage);
    }

    @Override
    protected PageAnchor getHelpTopic() {
        return new PageAnchor(
                ResourceLocation.parse("ae2_pattern_disk:items-blocks-machines/pattern_disk_assembler.md"),
                null);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        getMenu().showPage();

        int page = getMenu().getPage();
        previousPage.setVisibility(page > 0);
        nextPage.setVisibility(page < getMenu().getMaxPage() - 1);
        progressBar.setFullMsg(Component.literal(getMenu().getCurrentProgress() + "%"));
    }

    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        // 空样板槽绘制自定义覆盖层（八单元样板槽同款）
        if (slot instanceof AppEngSlot appEngSlot && appEngSlot.getItem().isEmpty()
                && getMenu().getPatternSlots().contains(slot)) {
            PATTERN_SLOT_OVERLAY.dest(slot.x, slot.y).zOffset(20).blit(guiGraphics);
        }
        super.renderSlot(guiGraphics, slot);
    }

    @Override
    public void drawFG(GuiGraphics gui, int offsetX, int offsetY, int mouseX, int mouseY) {
        gui.drawString(
                getMinecraft().font,
                Component.translatable(
                        "gui.ae2_pattern_disk.pattern_disk_assembler.page",
                        getMenu().getPage() + 1,
                        getMenu().getMaxPage()),
                8,
                18,
                style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB(),
                false);
    }
}
