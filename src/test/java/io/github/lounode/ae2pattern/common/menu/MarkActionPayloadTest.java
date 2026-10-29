package io.github.lounode.ae2pattern.common.menu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守「客户端动作的字符串参数必须是 JSON」这条 AE2 约定。
 *
 * <p>
 * AE2 的 {@code AEBaseMenu.ClientAction.handle} 拿 {@code Gson.fromJson(payload, argClass)} 解参数。
 * 而 Gson 默认宽松：{@code #} 被打头的内容当注释跳过，读到底就是 {@code null}。把磁盘标记
 * （{@code #minecraft:crafting}）裸着发出去不会报错，只会让处理函数收到一个空标记、判定「与当前相同」，
 * 表现是「点了候选格子但选不中」，日志里一行都没有。
 * </p>
 */
class MarkActionPayloadTest {

    private static final Gson GSON = new Gson();

    private static final Path SCREEN_SOURCE = Path.of("src", "client", "java", "io", "github", "lounode",
            "ae2pattern", "client", "gui", "MarkedPatternDiskProviderScreen.java");

    /** 裸标记会被宽松模式的 Gson 读成 null：这就是不编码会坏掉的直接证据。 */
    @Test
    void rawMarkDecodesToNullBecauseGsonSkipsHashAsComment() {
        assertNull(GSON.fromJson("#minecraft:crafting", String.class),
                "裸标记读成非 null 说明 AE2 的解析方式变了，本测试与 ClientActionPayload 的前提都要重看");
    }

    /** 编码后必须原样解回来，含引号、反斜杠与中文的标记也不例外。 */
    @Test
    void encodedMarkSurvivesTheRoundTrip() {
        for (var mark : List.of("#minecraft:crafting", "#mode:stonecutting", "#mekanism:crusher",
                "#铁 厂", "#with\"quote", "#back\\slash")) {
            assertEquals(mark, GSON.fromJson(ClientActionPayload.json(mark), String.class),
                    () -> "标记 " + mark + " 编码后解不回来");
        }
    }

    /** 屏幕发 selectMark 时必须走编码，不能把标记裸着塞进 payload。 */
    @Test
    void screenEncodesTheMarkBeforeSending() throws IOException {
        var source = Files.readString(SCREEN_SOURCE);
        assertTrue(source.contains("ClientActionPayload.json("),
                "屏幕发 selectMark 时必须用 ClientActionPayload.json 包一层：AE2 用 Gson 解参数，"
                        + "裸标记会被读成 null，表现为点候选格子没反应");
    }
}
