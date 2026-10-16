package io.github.lounode.ae2pattern.common.part;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

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
        // 有意只调 super：**不要**把「显示模式」（AE2 的 Settings.TERMINAL_SHOW_PATTERN_PROVIDERS）
        // 注册进来。AE2 的 MEStorageMenu.broadcastChanges 会遍历宿主配置管理器里的每个设置、再去它自己那份
        // 客户端设置（clientCM，只认 SORT_BY / VIEW_MODE / SORT_DIRECTION）取同名项——多一项，开屏时
        // initMenu → broadcastChanges 必抛 UnsupportedSettingException，而该异常被服务端「suppressing error」
        // 吞掉，首帧同步发不出去，表现就是终端打开后看不到网络物品。
        // 档位改由本模组自己的 DiskEncodingLogic#shownProviders 持有（面板存部件 NBT），
        // 随 DiskHostListPayload 下发、随 patternDiskViewState 回传。
        super.registerSettings(builder);
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
