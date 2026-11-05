package io.github.lounode.ae2pattern.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.style.Blitter;
import appeng.parts.encoding.EncodingMode;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.pattern.DiskMarkRules;
import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskEncodingTermMenu;

/**
 * 编码终端的「档位 / 模式」控件组：常规四档面板的可见性、三个额外档的注册表，以及模式下拉按钮的
 * 图标 / 提示语 / 互斥都收在这里。屏幕只保留 {@code init} / 渲染覆写与一行转调。
 *
 * <p>本类不持有屏幕，构造期只收窄依赖：菜单对象、常规面板表、三个额外档面板、模式按钮本身。
 * {@code currentPatternTypeId()} 由磁盘列表控件组以 {@code Supplier} 回调注入，避免两份状态散开。
 * 详见 {@code docs/ENCODING_MODES.md}。</p>
 */
final class EncodingTierWidgets {

    // states.png (0,16,64,16) 四模式图标：合成/处理/锻造/切石
    private static final Blitter ICON_CRAFTING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(0, 16, 16, 16);
    private static final Blitter ICON_PROCESSING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(16, 16, 16, 16);
    private static final Blitter ICON_SMITHING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(32, 16, 16, 16);
    private static final Blitter ICON_STONECUTTING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(48, 16, 16, 16);

    // states.png (64,16,16,16)：高级编码模式的档位图标。装上高级样板编码器后，它才会进模式循环。
    private static final Blitter ICON_ADVANCED = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(64, 16, 16, 16);

    // states.png (80,16,16,16)：雕凿编码模式的档位图标（紧接上一格的 16px 步长）。装上雕凿样板编码器后才会进循环。
    private static final Blitter ICON_CHISELING = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(80, 16, 16, 16);

    // states.png (96,16,16,16)：过载编码模式的档位图标（同上一步长）。装上 AE2 Lightning Tech 后才会进循环。
    private static final Blitter ICON_OVERLOADED = Blitter
            .texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
            .src(96, 16, 16, 16);

    private final PatternDiskEncodingTermMenu menu;
    private final ModeDropdownButton modeButton;
    private final Map<EncodingMode, DiskEncodingModePanel> modePanels;

    /** 额外档注册表（构造器里建好，之后只读）。加一档就在这里加一项。 */
    private final List<ExtraTier> extraTiers;

    /** 上次写进模式选择钮的 tooltip 输入；变了才重建那几行文本，不必每帧新建。 */
    private EncodingMode tooltipMode;

    /**
     * 上次提示语用的是哪个额外档（{@link #activeTier()} 的序号，-1 = 常规档）。
     *
     * <p>与 {@link #tooltipMode} 一起决定何时重写提示语。以前是每档一个 boolean（高/雕两个），
     * 加第三档就得再加一个；现在收成一个序号，加档不用动这里。</p>
     */
    private int tooltipTier = -1;

    EncodingTierWidgets(PatternDiskEncodingTermMenu menu, ModeDropdownButton modeButton,
            Map<EncodingMode, DiskEncodingModePanel> modePanels,
            DiskEncodingModePanel advancedPanel,
            @Nullable DiskEncodingModePanel chiselingPanel,
            @Nullable DiskEncodingModePanel overloadedPanel) {
        this.menu = menu;
        this.modeButton = modeButton;
        this.modePanels = modePanels;

        // 额外档注册表：图标/名称/面板/可用性/是否开/怎么开，五样各一处。下拉列表、互斥、
        // 可见性、常规档高亮全从它派生（见 docs/ENCODING_MODES.md）。
        // 面板在构造器里已全部建好，所以这张表可以一次定下来；表里的 available/active 是
        // Supplier，每帧重新求值，不会因为菜单字段变化而变旧。
        this.extraTiers = List.of(
                new ExtraTier(ICON_ADVANCED,
                        "gui.ae2_pattern_disk.encoding_terminal.advanced_mode",
                        PatternDiskItem.ADVANCED_PROCESSING_PATTERN,
                        advancedPanel,
                        () -> menu.advancedModeAvailable,
                        () -> menu.advancedMode,
                        menu::setAdvancedMode),
                new ExtraTier(ICON_CHISELING,
                        "gui.ae2_pattern_disk.encoding_terminal.chiseling_mode",
                        PatternDiskItem.CHISELING_PATTERN,
                        chiselingPanel,
                        () -> menu.chiselingModeAvailable
                                && PatternDiskEncodingTermMenu.CHISELING_TIER_ENABLED,
                        () -> menu.chiselingMode,
                        menu::setChiselingMode),
                new ExtraTier(ICON_OVERLOADED,
                        "gui.ae2_pattern_disk.encoding_terminal.overloaded_mode",
                        PatternDiskItem.OVERLOAD_PATTERN,
                        overloadedPanel,
                        () -> menu.overloadedModeAvailable,
                        () -> menu.overloadedMode,
                        menu::setOverloadedMode));
    }

    /** 每帧同步：模式选择钮的提示语与所有面板的可见性（均从档位注册表派生）。 */
    void updateBeforeRender() {
        // 模式选择钮的提示语：第一行就是它现在所处的档位，第二行说明点它会发生什么。
        // 额外档不在 EncodingMode 里，但它们同样是一次「当前在哪儿」，所以一并跟。
        var mode = menu.getMode();
        var tierIndex = activeTier();
        if (mode != this.tooltipMode || tierIndex != this.tooltipTier) {
            this.tooltipMode = mode;
            this.tooltipTier = tierIndex;
            this.modeButton.setTooltip(List.of(
                    tierIndex >= 0
                            ? Component.translatable(this.extraTiers.get(tierIndex).nameKey())
                            : modeName(mode),
                    Component.translatable("gui.ae2_pattern_disk.encoding_terminal.mode_cycle")));
        }

        // 根据当前模式切换面板可见性。额外档是并列的：任一开着时四个常规面板全让位。
        var currentMode = menu.getMode();
        var regular = !anyExtraTierActive();
        for (var entry : this.modePanels.entrySet()) {
            entry.getValue().setVisible(regular && entry.getKey() == currentMode);
        }
        for (var tier : this.extraTiers) {
            if (tier.panel() != null) {
                tier.panel().setVisible(tier.active().getAsBoolean());
            }
        }
    }

    /** 常规四档的图标。 */
    private static Blitter iconFor(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> ICON_CRAFTING;
            case PROCESSING -> ICON_PROCESSING;
            case SMITHING_TABLE -> ICON_SMITHING;
            case STONECUTTING -> ICON_STONECUTTING;
        };
    }

    /**
     * 一个额外档的全部声明。顺序 = 下拉列表里额外档的排列顺序。
     *
     * <p>{@code panel} 为 null 时该档不进下拉（面板缺席）；{@code available}/{@code active} 用
     * Supplier 而不是即时值，因为它们是菜单字段，每帧都可能在变（升级槽里的卡被拿走、服务端回读等）。</p>
     */
    private record ExtraTier(
            Blitter icon,
            String nameKey,
    /**
     * 该档编出的样板物品 id，也就是磁盘类型锁里存的那个串（{@code PatternDiskContents.type}）。
     *
     * <p>用来判「这张盘固定住的类型就是当前档要编的那一类」——那比标记硬：写进去走的
     * {@code PatternDiskContents.acceptsType} 就是同一个比较，类型锁相同就一定收得下。
     * 三个额外档各有各的样板物品；常规四档不用填，它们的 id 由模式推得（见
     * {@code DiskMarkRules.patternTypeForMode}）。</p>
     *
     * <p>值全部取自 {@code PatternDiskItem} 那组常量——那里是类型的单一事实源（“类型 ↔ tooltip
     * 名字”那张表也用它），不要在这里另写一遍字面量。</p>
     */
            @Nullable String patternTypeId,
            @Nullable DiskEncodingModePanel panel,
            BooleanSupplier available,
            BooleanSupplier active,
            Consumer<Boolean> setter) {
    }

    /** 当前开着的是第几个额外档；都没开时返回 -1（即停在常规档）。 */
    private int activeTier() {
        for (int i = 0; i < this.extraTiers.size(); i++) {
            if (this.extraTiers.get(i).active().getAsBoolean()) {
                return i;
            }
        }
        return -1;
    }

    /** 模式钮上画哪个图标：停在额外档就用那一档的图标，否则用常规档的。 */
    Blitter currentModeIcon() {
        var tier = activeTier();
        return tier >= 0 ? this.extraTiers.get(tier).icon() : iconFor(menu.getMode());
    }

    List<ModeDropdownButton.Choice> modeChoices() {
        var currentMode = menu.getMode();
        // 常规档是否高亮：只要**没有任何**额外档开着。不要展开成 !a && !b && !c——加一档必漏，
        // 漏了就不是「哪个高亮」的问题：过载档下常规档会同时显示选中，看上去像两档叠着开。
        var regular = !anyExtraTierActive();

        var choices = new ArrayList<ModeDropdownButton.Choice>();
        for (var mode : EncodingMode.values()) {
            choices.add(new ModeDropdownButton.Choice(
                    iconFor(mode),
                    modeName(mode),
                    regular && currentMode == mode,
                    () -> pickMode(mode)));
        }
        // 额外档在四个常规档后面，顺序就是 extraTiers 里的顺序；面板缺席或当前不可用的不进列表。
        for (int i = 0; i < this.extraTiers.size(); i++) {
            var tier = this.extraTiers.get(i);
            if (tier.panel() == null || !tier.available().getAsBoolean()) {
                continue;
            }
            var index = i;
            choices.add(new ModeDropdownButton.Choice(
                    tier.icon(),
                    Component.translatable(tier.nameKey()),
                    tier.active().getAsBoolean(),
                    () -> pickExtraTier(index)));
        }
        return choices;
    }

    /**
     * 直接点到某个常规档。
     *
     * <p>档位与那两个额外开关要在**同一帧内**全部落定，否则屏幕会看见一个中间态：{@code mode} 是
     * {@code @GuiSync} 的服务端权威字段，{@code setMode} 在客户端只发包、不本地改，要等一个往返才切过去；
     * 而 {@code advancedMode} / {@code chiselingMode} 在客户端是立即生效的。两者不同步，从额外档切到常规档
     * 时就会先回落到上一次的常规档去（等往返才到目标），看起来像「闪了一下」。所以最后补一句本地赋值。
     * AE2 自己不必补，是因为它四个档全走 {@code mode}，切换只是单次延迟、不存在中间态。</p>
     *
     * <p>赋值必须在 {@code setMode} <b>之后</b>：那个方法内部要读 {@code this.mode != mode} 来决定是否
     * 重算切石配方，先改本地值会把那一步跳过。</p>
     */
    private void pickMode(EncodingMode mode) {
        closeAllExtraTiers();
        menu.setMode(mode);
        // 等一个往返才切过去的话，从额外档回来时会先回落到上一次的常规档，中间那一下看着像「闪了一下」。
        menu.mode = mode;
    }

    /**
     * 切到某一个额外档。
     *
     * <p>互斥由 {@link #closeAllExtraTiers()} 统一收：**不要**在这一档里手动去清另外两个
     * ——那是 O(n²)，加一档必漏（上一版就漏了回常规档与进高级档两处，前者让编辑区一片空白、
     * 后者让两块同坐标的面板叠画）。</p>
     */
    private void pickExtraTier(int index) {
        closeAllExtraTiers();
        this.extraTiers.get(index).setter().accept(true);
    }

    /** 收掉所有开着的额外档。只对真正开着的那个发关闭，免得平白多发一次客户端动作。 */
    private void closeAllExtraTiers() {
        for (var tier : this.extraTiers) {
            if (tier.active().getAsBoolean()) {
                tier.setter().accept(false);
            }
        }
    }

    /** 当前是否停在某个额外档上（常规档的高亮与可见性都看它）。 */
    private boolean anyExtraTierActive() {
        return activeTier() >= 0;
    }

    /** 当前所属的配方类型名；模式轮换按钮拿它当提示语的第一行。 */
    private static Component modeName(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> Component.translatable("ae2_pattern_disk.tooltip.type.crafting");
            case PROCESSING -> Component.translatable("ae2_pattern_disk.tooltip.type.processing");
            case SMITHING_TABLE -> Component.translatable("ae2_pattern_disk.tooltip.type.smithing");
            case STONECUTTING -> Component.translatable("ae2_pattern_disk.tooltip.type.stonecutting");
        };
    }

    /**
     * 当前档位编出的样板物品 id——本档判「哪张盘属于我这一组」的锚点。
     *
     * <p>额外档各问自己注册表里那一格（雕凿/高级/过载各有各的样板物品，且它们的 {@code mode} 停在
     * 进入本档之前那个常规档，直接问 {@code getMode()} 会答错）；常规四档由模式推，值与映射都在
     * {@code DiskMarkRules}。</p>
     */
    String currentPatternTypeId() {
        // 额外档优先：雕凿/高级/过载档下 mode 仍停着「进入本档之前那个常规档」，直接问它会答错。
        var tier = activeTier();
        return tier >= 0
                ? this.extraTiers.get(tier).patternTypeId()
                : DiskMarkRules.patternTypeForMode(menu.getMode());
    }
}
