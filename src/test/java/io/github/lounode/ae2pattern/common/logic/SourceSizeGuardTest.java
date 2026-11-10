package io.github.lounode.ae2pattern.common.logic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行数护栏两条：三个编码终端类收敛到 ≤650 行之前先钉住「不再继续变长」，以及全仓不得超过 800 行的硬线。
 *
 * <p>棘轮上限取当前实测行数。重构拆出一层后应同步下调这里的上限，让护栏跟着目标收紧，
 * 而不是等重构完成才补测试。</p>
 */
class SourceSizeGuardTest {

    /** ARCHITECTURE「文件规模与拆分深度」的硬线：超过就必须按职责拆。 */
    private static final int HARD_LIMIT = 800;

    private static final Path SRC = Path.of(System.getProperty("user.dir"), "src");

    private static final Path DIR = Path.of(System.getProperty("user.dir"),
            "src/main/java/io/github/lounode/ae2pattern/common/menu/pattern");

    private static final Map<String, Integer> CEILINGS = Map.of(
            "PatternDiskEncodingTermMenu.java", 794,
            "EncodingTermStateMenu.java", 789,
            "EncodingTierMenuState.java", 769);

    @Test
    void oversizedClassesDoNotGrow() throws IOException {
        for (var entry : CEILINGS.entrySet()) {
            Path file = DIR.resolve(entry.getKey());
            long lines = countLines(file);
            assertTrue(lines <= entry.getValue(),
                    entry.getKey() + " 涨到 " + lines + " 行，超过护栏 " + entry.getValue());
        }
    }

    @Test
    void noSourceFileExceedsTheHardLimit() throws IOException {
        List<String> oversized = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                long lines = countLines(file);
                if (lines > HARD_LIMIT) {
                    oversized.add(SRC.relativize(file) + " " + lines + " 行");
                }
            }
        }
        assertTrue(oversized.isEmpty(), "超过 " + HARD_LIMIT + " 行必须按职责拆：" + oversized);
    }

    private static long countLines(Path file) throws IOException {
        try (Stream<String> stream = Files.lines(file)) {
            return stream.count();
        }
    }
}
