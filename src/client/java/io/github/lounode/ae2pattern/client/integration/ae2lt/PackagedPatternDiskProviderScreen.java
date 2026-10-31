package io.github.lounode.ae2pattern.client.integration.ae2lt;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import guideme.PageAnchor;
import net.minecraft.world.inventory.Slot;


import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.UpgradesPanel;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;

import com.moakiee.ae2lt.client.frequency.FrequencyBindingClient;
import com.moakiee.ae2lt.client.widgets.TextureToggleButton;

import io.github.lounode.ae2pattern.client.gui.StatesIconButton;
import io.github.lounode.ae2pattern.integration.ae2lt.packaged.PackagedPatternDiskProviderMenu;

/**
 * 封包样板磁盘供应器的屏幕：布局与样板磁盘供应器相同，工具栏是封包那套（自动回收、频率绑定、模式）。
 * 无线版继承本类并换一份布局文档。
 */
public class PackagedPatternDiskProviderScreen<M extends PackagedPatternDiskProviderMenu>
        extends LightningProviderScreen<M> {

    /** 封包核心空槽底图：直接引用上游资源，不复制。 */
    private static final ResourceLocation CORE_SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ae2ltpp", "textures/gui/packaged_core_slot.png");

    private static final ResourceLocation AUTO_RETURN_OFF =
            ResourceLocation.fromNamespaceAndPath("ae2lt", "textures/gui/buttons/auto_input_off.png");
    private static final ResourceLocation AUTO_RETURN_ON =
            ResourceLocation.fromNamespaceAndPath("ae2lt", "textures/gui/buttons/auto_input_on.png");

    private static final int WIRELESS_HINT_Y = 218;
    private static final int PINK = 0xFFFF85C1;

    private final StatesIconButton autoReturnButton;
    private final TextureToggleButton modeButton;

    public PackagedPatternDiskProviderScreen(
            M menu, Inventory playerInventory, Component title) {
        this(menu, playerInventory, title, StyleManager.loadStyleDoc(
                "/screens/ae2_pattern_disk/packaged_pattern_disk_provider.json"));
    }

    protected PackagedPatternDiskProviderScreen(
            M menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        this.autoReturnButton = new StatesIconButton(
                () -> menu.isAutoReturnEnabled()
                        ? Blitter.texture(AUTO_RETURN_ON, 16, 16).src(0, 0, 16, 16)
                        : Blitter.texture(AUTO_RETURN_OFF, 16, 16).src(0, 0, 16, 16),
                btn -> menu.clientToggleAutoReturn());
        this.autoReturnButton.setBackground(
                Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter(),
                Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER.getBlitter());
        this.modeButton = new TextureToggleButton(
                TextureToggleButton.ButtonType.MODE,
                previous -> menu.clientToggleMode());
        this.modeButton.setTooltipOn(List.of(Component.translatable("ae2lt.gui.provider_mode.wireless")));
        this.modeButton.setTooltipOff(List.of(Component.translatable("ae2lt.gui.provider_mode.normal")));

        var adapterSlot = this.menu.getAdapterSlot();
        if (adapterSlot != null) {
            this.widgets.add("adapterCard", new UpgradesPanel(List.of(adapterSlot), List::of));
        }

        addLightningToolbar();
    }

    @Override
    protected void addLightningToolbar() {
        addToLeftToolbar(FrequencyBindingClient.createToolbarButton(this.menu));
        addToLeftToolbar(this.autoReturnButton);
        if (this.menu.isModeSwitchVisible()) {
            addToLeftToolbar(this.modeButton);
        }
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.autoReturnButton.setTooltip(List.of(
                Component.translatable("gui.ae2_pattern_disk.overloaded.tooltip.auto_return"),
                Component.translatable(this.menu.isAutoReturnEnabled()
                        ? "gui.ae2_pattern_disk.overloaded.tooltip.auto_return.auto"
                        : "gui.ae2_pattern_disk.overloaded.tooltip.auto_return.off")));
        this.modeButton.visible = this.menu.isModeSwitchVisible();
        this.modeButton.setState(this.menu.isWirelessMode());
    }

    @Override
    public void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        if (slot == this.menu.getAdapterSlot() && slot.isActive() && !slot.hasItem()) {
            guiGraphics.blit(CORE_SLOT_TEXTURE, slot.x, slot.y, 0, 0, 16, 16, 16, 16);
        }
        super.renderSlot(guiGraphics, slot);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);
        if (this.menu.isWirelessMode()) {
            var hint = Component.translatable("ae2ltpp.gui.wireless_connection_hint");
            int textWidth = this.font.width(hint);
            guiGraphics.drawString(this.font, hint,
                    (this.imageWidth - textWidth) / 2, WIRELESS_HINT_Y, PINK, true);
        }
    }

    @Override
    protected PageAnchor getHelpTopic() {
        return new PageAnchor(ResourceLocation.parse(
                "ae2_pattern_disk:items-blocks-machines/packaged_pattern_disk_provider.md"), null);
    }
}