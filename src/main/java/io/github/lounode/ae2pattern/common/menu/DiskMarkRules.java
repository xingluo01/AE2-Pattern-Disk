package io.github.lounode.ae2pattern.common.menu;

import java.util.Locale;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import appeng.parts.encoding.EncodingMode;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;

/**
 * 磁盘标记与磁盘名的规则：编码模式怎么变成一个标记、标记怎么写回可读名、玩家给的名字怎么清洗。
 *
 * <p>标记有三种写法，它们是同一件事的三条路：导入过配方时写 {@code #<配方类别>}，手动编码留下的写
 * {@code #mode:<模式>}，两者显示与搜索时又归一成同一套名字。把归一规则放在一个地方，是为了让"看上去是
 * 两种标记"不影响玩家看到的、搜到的名字一致。</p>
 *
 * <p>磁盘名清洗是服务端侧的收紧：改包客户端可以送任意长、含格式码的串，而名字要写进物品组件。上限与
 * 原版铁砧一致。</p>
 *
 * <p><b>已知行为（不是缺陷）</b>：{@code #mode:} 这一支只覆盖 AE2 的四个常规档与雕凿（雕凿的常量直接写在这里）；
 * <b>高级档没有专属标记</b>，它继承「进入高级档之前那个常规档」的标记。已记在 {@code docs/TODO.md} 的 L 条里，
 * 后续审查不必当新问题报。</p>
 */
public final class DiskMarkRules {

    /** 磁盘名的上限，与原版铁砧一致。 */
    public static final int MAX_DISK_NAME_LENGTH = 50;

    /**
     * 雕凿档的标记。
     *
     * <p>雕凿不在 {@link EncodingMode} 里（那个枚举不可扩展）也不对应任何配方类别，所以它的标记是一个
     * 写死的模式标记——雕凿样板记的是「把谁雕成谁」，跟配方类别无关。写法与 {@link #modeMarkId} 同构，
     * 显示时走 {@code ae2_pattern_disk.mark.mode.<名>} 那一套翻译。</p>
     */
    public static final String CHISELING_MARK = "#mode:chiseling";

    /** {@link #CHISELING_MARK} 里 {@code #mode:} 之后那一段，显示与识别都用它。 */
    public static final String CHISELING_MARK_NAME = "chiseling";

    /** {@link #modeMarkId} 里 {@code #} 之后那一段。 */
    private static final String MODE_MARK_PREFIX = "mode:";

    /** 名字里不允许出现的字符：控制字符与 § 格式码。客户端送来的串不能带着它们进物品组件。 */
    private static final Pattern DISALLOWED_NAME_CHARS = Pattern.compile("[\\p{Cntrl}\u00a7]");

    private DiskMarkRules() {
    }

    /**
     * 编码模式对应的规范配方类别 id，没有公认类别时返回 null。"导入过"的盘用配方自己的类别，
     * "手动编码"的盘只能用模式，把模式映射到类别是为了让这两种盘叫同一个名字。
     */
    @Nullable
    public static String categoryForMode(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> "minecraft:crafting";
            case STONECUTTING -> "minecraft:stonecutting";
            case SMITHING_TABLE -> "minecraft:smithing";
            // 处理没有唯一的类别：不同机器各有各的 EMI 类别，只能保留模式自己的名字。
            case PROCESSING -> null;
        };
    }

    /** The mark standing for an encoding mode, for disks marked without an imported recipe. */
    public static String modeMarkId(EncodingMode mode) {
        return "#" + MODE_MARK_PREFIX + mode.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 反查：这个磁盘标记属于哪一类样板；不是档位标记（玩家 Shift+右键写的任意文本）返回 null。
     *
     * <p><b>标记与样板类型是同一件事的两种写法</b>，互转规则收在这里：{@code #mode:<模式>} 与
     * {@code #<配方类别>} 都归一到该档位编出的样板类型 id（见 {@link #patternTypeForMode}），
     * 雕凿用它自己的固定字面量。选盘时把盘的有效标记先归一到类型再比，两边就只有一个锚点。
     * 玩家随手写的那种标记（它未必是档位标记）反查不出，返回 null——不参与分组。</p>
     */
    @Nullable
    public static String patternTypeForMark(@Nullable String mark) {
        if (mark == null || mark.isEmpty() || !mark.startsWith("#")) {
            return null;
        }
        if (CHISELING_MARK.equals(mark)) {
            return PatternDiskItem.CHISELING_PATTERN;
        }
        var body = mark.substring(1);
        if (body.startsWith(MODE_MARK_PREFIX)) {
            var name = body.substring(MODE_MARK_PREFIX.length());
            for (var mode : EncodingMode.values()) {
                if (mode.name().toLowerCase(Locale.ROOT).equals(name)) {
                    return patternTypeForMode(mode);
                }
            }
            return null;
        }
        for (var mode : EncodingMode.values()) {
            if (body.equals(categoryForMode(mode))) {
                return patternTypeForMode(mode);
            }
        }
        return null;
    }

    /**
     * 该编码模式编出的样板物品 id，也就是磁盘类型锁（{@code PatternDiskContents.type}）里存的那个串。
     *
     * <p>写成 switch 而不是拼字符串：值直接取自 {@link PatternDiskItem} 那组常量（那里是类型的单一
     * 事实源），且 {@code EncodingMode} 将来多一项时编译器会当场拦下来。</p>
     */
    public static String patternTypeForMode(EncodingMode mode) {
        return switch (mode) {
            case CRAFTING -> PatternDiskItem.CRAFTING_PATTERN;
            case PROCESSING -> PatternDiskItem.PROCESSING_PATTERN;
            case SMITHING_TABLE -> PatternDiskItem.SMITHING_PATTERN;
            case STONECUTTING -> PatternDiskItem.STONECUTTING_PATTERN;
        };
    }

    /**
     * 反查：这个样板类型属于哪个常规档；不是常规四档时返回 null。
     *
     * <p>三个额外档的样板不在这里——它们不在 {@link EncodingMode} 里，调用方得先判
     * {@link PatternDiskItem#CHISELING_PATTERN} / {@link PatternDiskItem#ADVANCED_PROCESSING_PATTERN} /
     * {@link PatternDiskItem#OVERLOAD_PATTERN}，剩下的才轮到本方法。</p>
     */
    @Nullable
    public static EncodingMode modeForPatternType(@Nullable String patternTypeId) {
        if (patternTypeId == null) {
            return null;
        }
        for (var mode : EncodingMode.values()) {
            if (patternTypeForMode(mode).equals(patternTypeId)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * 这张盘该带哪个标记：刚导入过配方就用它的类别，否则用模式标记。
     *
     * <p>两套写法看起来是两种标记，但显示与搜索会把模式标记归一成对应的类别，所以玩家看到的、搜到的名字是
     * 一致的。</p>
     */
    public static String deriveMarkId(@Nullable String importedCategory, EncodingMode mode) {
        return importedCategory != null && !importedCategory.isEmpty()
                ? "#" + importedCategory
                : modeMarkId(mode);
    }

    /**
     * 清洗一个磁盘名：去掉控制字符与 § 格式码，超长截断。
     *
     * <p>返回空串表示这个名字不能用，调用方应当放弃改名而不是写一个空名（空名会让磁盘退回默认显示名，
     * 玩家看到的是"改了个没反应"）。</p>
     */
    public static String sanitizeName(String name) {
        var cleaned = DISALLOWED_NAME_CHARS.matcher(name).replaceAll("");
        return cleaned.length() > MAX_DISK_NAME_LENGTH
                ? cleaned.substring(0, MAX_DISK_NAME_LENGTH)
                : cleaned;
    }
}
