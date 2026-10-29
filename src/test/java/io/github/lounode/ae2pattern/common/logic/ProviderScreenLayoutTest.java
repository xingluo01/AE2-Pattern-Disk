package io.github.lounode.ae2pattern.common.logic;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守「同一个屏幕类被多份布局文档复用」这个坑。
 *
 * <p>
 * {@code PatternDiskProviderScreen} 是两份设备共用的：普通供应器与自装配（陨石）供应器。布局拆成三层：
 * 基底 {@code pattern_disk_provider_base.json}（背景、槽位、共用字样、控件）+ 两份派生文档（只写
 * {@code helpTopic} 与对话框标题）。控件（如 {@code boostLimit}）写在基底里，派生文档就会一起拿到。
 * </p>
 *
 * <p>
 * 屏幕构造里的 {@code widgets.addTextField("boostLimit")} 在文档缺键时会抛
 * {@code IllegalStateException: Screen is missing required widget}，而这个异常发生在
 * 「打开带有高级数据的屏幕」的网络处理器里——结果不是画错，是玩家被踢下线。本测试不启动客户端，
 * 沿 include 链合并后比对必需 widget。
 * </p>
 */
class ProviderScreenLayoutTest {

    /** 屏幕类取控件的文档顶端：两份派生文档都 include 同一份基底。 */
    private static final List<String> PROVIDER_LAYOUTS = List.of(
            "/assets/ae2/screens/ae2_pattern_disk/pattern_disk_provider.json",
            "/assets/ae2/screens/ae2_pattern_disk/meteorite_pattern_provider.json",
            "/assets/ae2/screens/ae2_pattern_disk/marked_pattern_disk_provider.json");

    /** 标记式供应器自己那份完整文档，与它独有的控件。 */
    private static final String MARKED_LAYOUT =
            "/assets/ae2/screens/ae2_pattern_disk/marked_pattern_disk_provider.json";

    private static final List<String> MARK_GRID_WIDGETS =
            List.of("markSearch", "markScrollbar", "showMode", "clearMark");

    /**
     * 屏幕构造里用 {@code widgets.add/addTextField} 按 id 取的控件。
     *
     * <p>
     * 只列真正走文档的那些：工具栏按钮（阻挡、发配倍增）由 {@code addToLeftToolbar} 摆放，不在文档里。
     * </p>
     */
    private static final List<String> REQUIRED_WIDGETS = List.of("boostLimit", "lockReason");

    /**
     * 标记式供应器的屏幕还按 id 取走候选区那几枚控件。它用的是自己那份完整文档（不走基底/派生两层），
     * 所以单独守：漏一个键，开界面时同样是被踢下线。
     */
    @Test
    void markedProviderLayoutDeclaresTheMarkGridWidgets() throws IOException {
        var widgets = loadWidgets(MARKED_LAYOUT);
        for (var widget : MARK_GRID_WIDGETS) {
            assertTrue(widgets.contains(widget),
                    () -> MARKED_LAYOUT + " 缺少 widget \""+ widget + "\"：屏幕构造会抛 "
                            + "Screen is missing required widget，玩家开界面时被踢下线");
        }
    }

    @Test
    void everyProviderLayoutDeclaresTheWidgetsTheScreenAsksFor() throws IOException {
        for (var layout : PROVIDER_LAYOUTS) {
            var widgets = loadWidgets(layout);
            for (var widget : REQUIRED_WIDGETS) {
                assertTrue(widgets.contains(widget),
                        () -> layout + " 缺少 widget \"" + widget + "\"：屏幕构造会抛 "
                                + "Screen is missing required widget，玩家开界面时被踢下线");
            }
        }
    }

    /**
     * 按 AE2 的语义把 includes 合并进来再取 widget id：控件可以写在被 include 的公共文档里，
     * 只看主文件会把合法的布局误报成缺键。
     */
    private static java.util.Set<String> loadWidgets(String resource) throws IOException {
        var ids = new java.util.HashSet<String>();
        collectWidgets(resource, ids, new java.util.HashSet<>());
        return ids;
    }

    private static void collectWidgets(String resource, java.util.Set<String> ids, java.util.Set<String> seen)
            throws IOException {
        if (!seen.add(resource)) {
            // AE2 的 StyleManager 在同一次加载里重复遇到同一文件就抛 Recursive style includes，
            // 所以「已 include 的公共文档」只能在基底里写一次，派生文档不得再写一次。
            throw new AssertionError("include 图里重复引入同一文件：" + resource
                    + "（AE2 会抛 IllegalStateException: Recursive style includes）");
        }
        var root = readJson(resource);
        // include 进来的文档不一定带 widgets（调色板之类只管颜色），缺段就是空集，不是错误。
        if (root.has("widgets") && root.get("widgets").isJsonObject()) {
            root.getAsJsonObject("widgets").keySet().forEach(ids::add);
        }
        if (root.has("includes") && root.get("includes").isJsonArray()) {
            for (var include : root.getAsJsonArray("includes")) {
                collectWidgets(resolve(resource, include.getAsString()), ids, seen);
            }
        }
    }

    /** 把 {@code ../common/x.json} 这类相对路径解到资源路径上。 */
    private static String resolve(String base, String relative) {
        var dir = base.substring(0, base.lastIndexOf('/') + 1);
        var parts = new java.util.ArrayList<String>();
        for (var segment : (dir + relative).split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (parts.isEmpty()) {
                    throw new AssertionError("布局 include 越出资源根：" + relative + "（相对 " + base + "）");
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(segment);
        }
        return "/" + String.join("/", parts);
    }

    private static JsonObject readJson(String resource) throws IOException {
        try (InputStream in = ProviderScreenLayoutTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("布局文档不存在：" + resource);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
