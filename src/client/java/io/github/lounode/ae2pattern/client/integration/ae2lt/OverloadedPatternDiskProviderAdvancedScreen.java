package io.github.lounode.ae2pattern.client.integration.ae2lt;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import appeng.client.gui.AESubScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.TabButton;

import com.moakiee.ae2lt.client.widgets.TextureToggleButton;

import io.github.lounode.ae2pattern.integration.ae2lt.overloaded.OverloadedPatternDiskProviderMenu;

/**
 * 过载样板磁盘供应器的高级设置子页：无线发配、无线探针、输入过滤、机器适配与机器并行度。
 *
 * <p>tooltip 统一两行：第一行标题，第二行当前状态或简要描述。</p>
 */
public final class OverloadedPatternDiskProviderAdvancedScreen
        extends AESubScreen<OverloadedPatternDiskProviderMenu, OverloadedPatternDiskProviderScreen> {

    private static final int LABEL_X = 38;
    private static final int STRATEGY_Y = 33;
    private static final int SPEED_Y = 57;
    private static final int FILTER_Y = 81;

    private final TextureToggleButton wirelessStrategyButton;
    private final TextureToggleButton wirelessSpeedButton;
    private final TextureToggleButton filteredImportButton;
    private final Button machineAdaptationButton;
    private final AETextField machineParallelism;
    private final Button applyParallelism;
    private int lastSyncedParallelism;

    public OverloadedPatternDiskProviderAdvancedScreen(OverloadedPatternDiskProviderScreen parent) {
        super(parent, "/screens/ae2_pattern_disk/overloaded_pattern_disk_provider_advanced.json");

        widgets.add("return", new TabButton(Icon.BACK,
                Component.translatable("gui.ae2_pattern_disk.overloaded_pattern_disk_provider"),
                btn -> returnToParent()));

        this.wirelessStrategyButton = new TextureToggleButton(
                TextureToggleButton.ButtonType.WIRELESS_STRATEGY,
                state -> menu.clientToggleWirelessDispatchMode());
        this.wirelessStrategyButton.setTooltipOn(tooltip("distribution", "distribution.even"));
        this.wirelessStrategyButton.setTooltipOff(tooltip("distribution", "distribution.single"));
        widgets.add("wirelessStrategy", this.wirelessStrategyButton);

        this.wirelessSpeedButton = new TextureToggleButton(
                TextureToggleButton.ButtonType.SPEED,
                state -> menu.clientToggleWirelessSpeedMode());
        this.wirelessSpeedButton.setTooltipOn(tooltip("probe", "probe.fast"));
        this.wirelessSpeedButton.setTooltipOff(tooltip("probe", "probe.normal"));
        widgets.add("wirelessSpeed", this.wirelessSpeedButton);

        this.filteredImportButton = new TextureToggleButton(
                TextureToggleButton.ButtonType.FILTERED_IMPORT,
                state -> menu.clientToggleFilteredImport());
        this.filteredImportButton.setTooltipOn(tooltip("filtered_import", "filtered_import.on"));
        this.filteredImportButton.setTooltipOff(tooltip("filtered_import", "filtered_import.off"));
        widgets.add("filteredImport", this.filteredImportButton);

        this.machineAdaptationButton = widgets.addButton("machineAdaptation",
                Component.empty(), menu::clientToggleMachineAdaptation);

        this.machineParallelism = widgets.addTextField("machineParallelism");
        this.machineParallelism.setMaxLength(10);
        this.machineParallelism.setFilter(this::isParallelismDraft);
        this.lastSyncedParallelism = menu.machineParallelism;
        this.machineParallelism.setValue(Integer.toString(this.lastSyncedParallelism));
        this.machineParallelism.setTooltip(Tooltip.create(
                tooltipTitle("parallelism").copy().append("\n").append(Component.translatable(
                        "ae2lt.gui.provider_advanced.machine_parallelism_tooltip"))));

        this.applyParallelism = widgets.addButton("applyParallelism",
                Component.translatable("gui.done"), this::applyParallelism);
    }

    private boolean isParallelismDraft(String text) {
        if (text.isEmpty()) {
            return true;
        }
        try {
            return text.chars().allMatch(c -> c >= '0' && c <= '9')
                    && Integer.parseInt(text) >= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private int draftedParallelism() {
        try {
            return Integer.parseInt(this.machineParallelism.getValue());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void applyParallelism() {
        int value = draftedParallelism();
        if (value > 0) {
            menu.clientSetMachineParallelism(value);
        }
    }

    @Override
    protected boolean shouldAddToolbar() {
        return false;
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();

        boolean wireless = menu.isWirelessMode();
        this.wirelessStrategyButton.setState(menu.isEvenDistribution());
        this.wirelessStrategyButton.active = wireless;
        this.wirelessSpeedButton.setState(menu.isFastSpeed());
        this.wirelessSpeedButton.active = wireless;

        this.filteredImportButton.setState(menu.isFilteredImportOn());

        this.machineAdaptationButton.setMessage(Component.translatable(menu.isMachineAdaptationOn()
                ? "ae2lt.gui.machine_adaptation.on"
                : "ae2lt.gui.machine_adaptation.off"));
        this.machineAdaptationButton.setTooltip(Tooltip.create(
                tooltipTitle("machine_adaptation").copy().append("\n").append(Component.translatable(
                        menu.isMachineAdaptationOn()
                                ? "gui.ae2_pattern_disk.overloaded.tooltip.machine_adaptation.on"
                                : "gui.ae2_pattern_disk.overloaded.tooltip.machine_adaptation.off"))));

        if (this.lastSyncedParallelism != menu.machineParallelism) {
            if (!this.machineParallelism.isFocused()
                    || draftedParallelism() == this.lastSyncedParallelism) {
                this.machineParallelism.setValue(Integer.toString(menu.machineParallelism));
            }
            this.lastSyncedParallelism = menu.machineParallelism;
        }
        this.machineParallelism.setEditable(wireless);
        this.applyParallelism.active = wireless
                && draftedParallelism() > 0
                && draftedParallelism() != menu.machineParallelism;
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);

        int color = menu.isWirelessMode() ? 0x404040 : 0x909090;
        guiGraphics.drawString(this.font,
                Component.translatable("ae2lt.gui.provider_advanced.distribution"),
                LABEL_X, STRATEGY_Y, color, false);
        guiGraphics.drawString(this.font,
                Component.translatable("ae2lt.gui.provider_advanced.probe"),
                LABEL_X, SPEED_Y, color, false);
        guiGraphics.drawString(this.font,
                Component.translatable("ae2lt.gui.provider_advanced.input_filter"),
                LABEL_X, FILTER_Y, 0x404040, false);
        guiGraphics.drawString(this.font,
                Component.translatable("ae2lt.gui.provider_advanced.machine_parallelism"),
                14, 129, color, false);
        guiGraphics.drawString(this.font,
                Component.translatable("ae2lt.gui.provider_advanced.parallelism_hint"),
                14, 171, 0x707070, false);
        if (!menu.isWirelessMode()) {
            guiGraphics.drawString(this.font,
                    Component.translatable("ae2lt.gui.provider_advanced.wireless_hint"),
                    14, 185, 0x707070, false);
        }
    }

    // ---- 两行 tooltip -------------------------------------------------------

    private static Component tooltipTitle(String title) {
        return Component.translatable("gui.ae2_pattern_disk.overloaded.tooltip." + title);
    }

    private static List<Component> tooltip(String title, String body) {
        return List.of(
                tooltipTitle(title),
                Component.translatable("gui.ae2_pattern_disk.overloaded.tooltip." + body));
    }
}