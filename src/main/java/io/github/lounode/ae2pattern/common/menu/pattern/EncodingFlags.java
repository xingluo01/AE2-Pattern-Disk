package io.github.lounode.ae2pattern.common.menu.pattern;

/**
 * 过载档那两张并行表的读写：{@code overloadedSides}（每行是输入 0 / 输出 1）与
 * {@code overloadedMatchModes}（每行是否忽略组件匹配）。
 *
 * <p>它们是一组<b>必须等长、按位对应</b>的字符串（逗号分隔的 0/1）。这组不变量以前散在菜单的三四个方法里，
 * 每次都得两边一起改；现在只在这里解释：越界或格式不对一律读成「没配置过」的默认态，写入时自动补齐长度。</p>
 *
 * <p>纯函数，不碰菜单状态：调用方把字符串传进来、拿新的出去。</p>
 */
final class EncodingFlags {

    private EncodingFlags() {
    }

    /** 逗号分隔的 0/1 表里取第 {@code index} 位；越界或格式不对一律 false。 */
    static boolean flagAt(String flags, int index) {
        if (index < 0 || flags == null || flags.isEmpty()) {
            return false;
        }
        var parts = flags.split(",");
        if (index >= parts.length) {
            return false;
        }
        try {
            return Integer.parseInt(parts[index].trim()) != 0;
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    /** 把第 {@code index} 位改成 {@code value}（不够长就补齐），其余保持。 */
    static String withFlag(String flags, int index, boolean value) {
        if (index < 0) {
            return flags == null ? "" : flags;
        }
        var parts = flags == null || flags.isEmpty() ? new String[0] : flags.split(",");
        var size = Math.max(index + 1, parts.length);
        var out = new StringBuilder();
        for (int i = 0; i < size; i++) {
            var current = i < parts.length && "1".equals(parts[i].trim());
            if (i == index) {
                current = value;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(current ? 1 : 0);
        }
        return out.toString();
    }

    /**
     * 两张表各追加一行：记下它是输入(0)还是输出(1)，组件匹配一律默认「启用」（写 0）。
     *
     * @return 下一行的行号
     */
    static int appendRow(StringBuilder sides, StringBuilder modes, int row, boolean output) {
        if (sides.length() > 0) {
            sides.append(',');
            modes.append(',');
        }
        sides.append(output ? 1 : 0);
        modes.append(0);
        return row + 1;
    }
}
