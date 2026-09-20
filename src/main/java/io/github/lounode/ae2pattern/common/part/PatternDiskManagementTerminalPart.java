package io.github.lounode.ae2pattern.common.part;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.util.IConfigManagerBuilder;
import appeng.items.parts.PartModels;
import appeng.parts.PartModel;

import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;

/**
 * The pattern disk management terminal: a cable-attached terminal that lists every pattern disk on the grid
 * grouped by the machine holding it, next to the encoding area of the encoding terminal.
 *
 * <p>Everything that is not the table comes from {@link PatternDiskEncodingTerminalPart}: the disk-holding
 * logic, the NBT, the drops, the terminal part behaviour. Only two things differ, and both are data: which
 * menu it opens, and which models it shows. Those models carry their own copy of the panel textures (today
 * a copy of the encoding terminal's three, until dedicated artwork replaces them), so re-skinning one
 * terminal no longer changes the other; the shared monitor shell stays common.</p>
 */
public class PatternDiskManagementTerminalPart extends PatternDiskEncodingTerminalPart {

    @PartModels
    public static final ResourceLocation MODEL_OFF = ResourceLocation.parse(
            "ae2_pattern_disk:part/pattern_disk_management_terminal_off");
    @PartModels
    public static final ResourceLocation MODEL_ON = ResourceLocation.parse(
            "ae2_pattern_disk:part/pattern_disk_management_terminal_on");

    public static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, MODEL_OFF, MODEL_STATUS_OFF);
    public static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, MODEL_ON, MODEL_STATUS_ON);
    public static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, MODEL_ON,
            MODEL_STATUS_HAS_CHANNEL);

    public PatternDiskManagementTerminalPart(IPartItem<?> partItem) {
        super(partItem);
    }

    @Override
    protected void registerSettings(IConfigManagerBuilder builder) {
        super.registerSettings(builder);
        // 「显示模式」直接复用 AE2 样板访问终端的同一个设置：同一套取值、同一份语义，默认也与它一致。
        builder.registerSetting(Settings.TERMINAL_SHOW_PATTERN_PROVIDERS, ShowPatternProviders.VISIBLE);
    }

    @Override
    public MenuType<?> getMenuType(Player p) {
        return PatternDiskManagementTermMenu.TYPE;
    }

    @Override
    public IPartModel getStaticModels() {
        return this.selectModel(MODELS_OFF, MODELS_ON, MODELS_HAS_CHANNEL);
    }
}
