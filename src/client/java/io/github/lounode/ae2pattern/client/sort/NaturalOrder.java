package io.github.lounode.ae2pattern.client.sort;

import java.util.Comparator;

/**
 * 名字里带数字时的比较：{@code 1k / 4k / 16k / 64k / 256k / 1M} 与 {@code 4 / 16 / 64 / 256 / 1024 /
 * 16384} 这类名字要按数值比，而按字符串比会把 {@code 16k} 排到 {@code 1k} 前面。
 *
 * <p>数字后面紧跟的 k/K/M/G/T/P/E 按 1024 的幂换算（容量就是这么写的）；两边都读得出数字就按数值比，
 * 读不出就退回逐字符的大小写不敏感比较。数值相等时继续比后面的片段，所以 {@code 4k} 与 {@code 4K}
 * 这种同值写法依然稳定可预期。</p>
 */
public final class NaturalOrder {

    private static final Comparator<String> STRINGS = NaturalOrder::compare;

    private NaturalOrder() {
    }

    public static Comparator<String> strings() {
        return STRINGS;
    }

    public static int compare(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);

            if (isDigit(a) && isDigit(b)) {
                int[] endA = { i };
                int[] endB = { j };
                long valueA = valueAt(left, endA);
                long valueB = valueAt(right, endB);
                if (valueA != valueB) {
                    return Long.compare(valueA, valueB);
                }
                i = endA[0];
                j = endB[0];
                continue;
            }

            char lowerA = Character.toLowerCase(a);
            char lowerB = Character.toLowerCase(b);
            if (lowerA != lowerB) {
                return Character.compare(lowerA, lowerB);
            }
            i++;
            j++;
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    /**
     * 从 {@code pos[0]} 起读一段「数字 + 可选单位」，返回换算后的值，并把 {@code pos[0]} 推到片段末尾。
     * 数字长到溢出时只保号（不会因为一个离谱的名字抛异常）。
     */
    private static long valueAt(String text, int[] pos) {
        int start = pos[0];
        int i = start;
        while (i < text.length() && isDigit(text.charAt(i))) {
            i++;
        }

        long value = 0;
        for (int k = start; k < i; k++) {
            long digit = text.charAt(k) - '0';
            value = value > (Long.MAX_VALUE - digit) / 10 ? Long.MAX_VALUE : value * 10 + digit;
        }

        long multiplier = 1;
        if (i < text.length()) {
            int power = unitPower(text.charAt(i));
            if (power > 0) {
                i++;
                for (int step = 0; step < power; step++) {
                    multiplier = multiplier > Long.MAX_VALUE / 1024 ? Long.MAX_VALUE : multiplier * 1024;
                }
            }
        }

        pos[0] = i;
        if (multiplier == 1) {
            return value;
        }
        if (value == 0) {
            return 0;
        }
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    /** k/K/M/G/T/P/E 各是 1024 的几次幂；其它字符返回 0，当普通字符继续按文本比。 */
    private static int unitPower(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'k' -> 1;
            case 'm' -> 2;
            case 'g' -> 3;
            case 't' -> 4;
            case 'p' -> 5;
            case 'e' -> 6;
            default -> 0;
        };
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
