package io.github.lounode.ae2pattern.client.integration.ae2lt;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import appeng.api.config.ActionItems;
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.ActionButton;

import com.moakiee.ae2lt.client.frequency.FrequencyBindingClient;
import com.moakiee.ae2lt.client.widgets.TextureToggleButton;

import io.github.lounode.ae2pattern.client.gui.StatesIconButton;
import io.github.lounode.ae2pattern.integration.ae2lt.overloaded.OverloadedPatternDiskProviderMenu;

/**
 * 过载样板磁盘供应器的屏幕：布局与样板磁盘供应器相同，左侧工具栏换成闪电科技过载供应器那套
 * （三态阻挡、频率绑定、自动返回、模式、自适应批量）。输入过滤在高级设置子页里。
 *
 * <p>tooltip 统一两行：第一行标题，第二行当前状态或简要描述。</p>
 */
public class OverloadedPatternDiskProviderScreen
        extends LightningProviderScreen<OverloadedPatternDiskProviderMenu> {

    /** 智能阻挡图标：与现有供应器的三态按钮同一张 states.png。 */
    private static final Blitter SMART_BLOCKING_ICON = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(48, 48, 16, 16);

    /** 工具栏按钮背景（与现有供应器同一套 18×20 底图）：常态 / 光标选中。 */
    private static final Blitter BUTTON_BG_NORMAL = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(208, 224, 18, 20);
    private static final Blitter BUTTON_BG_HOVER = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(226, 224, 18, 20);

    private final StatesIconButton blockingModeButton;
    private final TextureToggleButton autoReturnButton;
    private final TextureToggleButton modeButton;
    private final TextureToggleButton adaptiveBatchButton;

    public OverloadedPatternDiskProviderScreen(
            OverloadedPatternDiskProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc(
                "/screens/ae2_pattern_disk/overloaded_pattern_disk_provider.json"));

        this.blockingModeButton = new StatesIconButton(
                () -> switch (menu.blockingMode) {
                    case 0 -> Icon.BLOCKING_MODE_NO.getBlitter();
                    case 1 -> Icon.BLOCKING_MODE_YES.getBlitter();
                    default -> SMART_BLOCKING_ICON;
                },
                btn -> menu.clientCycleBlockingMode());
        this.blockingModeButton.setBackground(BUTTON_BG_NORMAL, BUTTON_BG_HOVER);

        this.autoReturnButton = new TextureToggleButton(
                TextureToggleButton.ButtonType.AUTO_RETURN,
                previous -> menu.clientToggleAutoReturn());
        this.autoReturnButton.setTooltipAt(0, tooltip("auto_return", "auto_return.off"));
        this.autoReturnButton.setTooltipAt(1, tooltip("auto_return", "auto_return.auto"));
        this.autoReturnButton.setTooltipAt(2, tooltip("auto_return", "auto_return.eject"));

        this.modeButton = new TextureToggleButton(
                TextureToggleButton.ButtonType.MODE,
                previous -> menu.clientToggleMode());
        this.modeButton.setTooltipOn(tooltip("mode", "mode.wireless"));
        this.modeButton.setTooltipOff(tooltip("mode", "mode.normal"));

        this.adaptiveBatchButton = new TextureToggleButton(
                TextureToggleButton.ButtonType.ADAPTIVE_BATCH,
                previous -> menu.clientToggleAdaptiveBatch());
        this.adaptiveBatchButton.setTooltipOn(tooltip("adaptive_batch", "adaptive_batch.on"));
        this.adaptiveBatchButton.setTooltipOff(tooltip("adaptive_batch", "adaptive_batch.off"));

        addLightningToolbar();
    }

    @Override
    protected void addLightningToolbar() {
        addToLeftToolbar(this.blockingModeButton);

        addToLeftToolbar(FrequencyBindingClient.createToolbarButton(this.menu));

        addToLeftToolbar(this.autoReturnButton);
        addToLeftToolbar(this.modeButton);
        addToLeftToolbar(this.adaptiveBatchButton);

        var advancedSettingsButton = new ActionButton(ActionItems.COG,
                () -> switchToScreen(new OverloadedPatternDiskProviderAdvancedScreen(this)));
        advancedSettingsButton.setMessage(Component.translatable("ae2lt.gui.provider_advanced.title"));
        addToLeftToolbar(advancedSettingsButton);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.blockingModeButton.setTooltip(List.of(
                tooltipTitle("blocking"),
                Component.translatable(switch (this.menu.blockingMode) {
                    case 0 -> "gui.ae2_pattern_disk.provider.blocking.default";
                    case 1 -> "gui.ae2_pattern_disk.provider.blocking.all";
                    default -> "gui.ae2_pattern_disk.provider.blocking.smart";
                })));
        this.autoReturnButton.setStateIndex(this.menu.returnMode);
        this.modeButton.setState(this.menu.isWirelessMode());
        this.adaptiveBatchButton.setState(this.menu.isAdaptiveBatchOn());
    }

    // ---- 两行 tooltip -------------------------------------------------------

    private static Component tooltipTitle(String title) {
        if ("blocking".equals(title)) {
            return Component.translatable("gui.ae2_pattern_disk.provider.blocking");
        }
        return Component.translatable("gui.ae2_pattern_disk.overloaded.tooltip." + title);
    }

    private static List<Component> tooltip(String title, String body) {
        return List.of(
                tooltipTitle(title),
                Component.translatable("gui.ae2_pattern_disk.overloaded.tooltip." + body));
    }
}