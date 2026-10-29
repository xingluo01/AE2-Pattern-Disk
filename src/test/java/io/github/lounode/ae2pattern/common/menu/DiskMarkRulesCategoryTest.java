package io.github.lounode.ae2pattern.common.menu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 守「标记 → 规范配方类别 id」这一层的边界。
 *
 * <p>
 * 直接起因是一次实机崩溃：候选标记里有雕凿的 {@code #mode:chiseling}，而雕凿<b>不在</b>
 * {@code EncodingMode} 里（那个枚举不可扩展，本模组给它写的是自定义模式名）。按名字反查模式得到
 * {@code null}，再把这个 null 喂给 {@link DiskMarkRules#categoryForMode} 就是 NPE——它对枚举做
 * {@code switch}，Java 编译成 {@code mode.ordinal()}。而这条链路在屏幕每帧渲染里跑，结果是开界面直接崩游戏。
 * </p>
 */
class DiskMarkRulesCategoryTest {

    /** 崩溃现场：雕凿没有对应的配方类别，必须安静地返回 null，而不是把 null 送进枚举 switch。 */
    @Test
    void chiselingMarkHasNoCategory() {
        assertNull(DiskMarkRules.categoryIdOfMark(DiskMarkRules.CHISELING_MARK),
                "雕凿标记不在 EncodingMode 里，反查模式得到 null；这个 null 绝不能流进 categoryForMode");
    }

    /** 同理，任何认不出的模式名（玩家手写 {@code #mode:xyz}）都不能崩。 */
    @Test
    void unknownModeNameHasNoCategory() {
        assertNull(DiskMarkRules.categoryIdOfMark("#mode:madeup"),
                "认不出的模式名要返回 null，不能把 null 喂给枚举 switch");
    }

    /** 模式标记归一到它的规范类别，与终端显示同一套锚点。 */
    @Test
    void modeMarkResolvesToCanonicalCategory() {
        assertEquals("minecraft:crafting", DiskMarkRules.categoryIdOfMark("#mode:crafting"));
        assertEquals("minecraft:stonecutting", DiskMarkRules.categoryIdOfMark("#mode:stonecutting"));
    }

    /** 导入配方写下的 {@code #<类别 id>} 原样就是类别。 */
    @Test
    void identifierMarkIsReturnedAsIs() {
        assertEquals("mekanism:crusher", DiskMarkRules.categoryIdOfMark("#mekanism:crusher"));
    }

    /** 处理档没有唯一类别（{@code categoryForMode} 对它返回 null），也不该崩。 */
    @Test
    void processingMarkHasNoSingleCategory() {
        assertNull(DiskMarkRules.categoryIdOfMark("#mode:processing"),
                "处理档的类别取决于具体机器，categoryForMode 本来就返回 null");
    }

    /** 不是 # 开头的、以及只有 # 的，都不是类别。 */
    @Test
    void nonIdentifierTextIsNotACategory() {
        assertNull(DiskMarkRules.categoryIdOfMark("粉碎"));
        assertNull(DiskMarkRules.categoryIdOfMark("#"));
        assertNull(DiskMarkRules.categoryIdOfMark(""));
        assertNull(DiskMarkRules.categoryIdOfMark(null));
    }
}
