package io.github.lounode.ae2pattern.common.logic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闪电科技三台磁盘版供应器的配方形状：都按「上游原设备 + 16K 样板磁盘」这一条口径写。
 *
 * <p>钉两件事：本仓三份形状一致，以及每份引用的上游物品正是上游那份配方的产物——上游改名或本仓写错 id
 * 都会在这里断。</p>
 */
class LightningTechRecipeShapeTest {

    private static final Path RECIPES = Path.of(System.getProperty("user.dir"),
            "src/main/resources/data/ae2_pattern_disk/recipe");

    private static final List<String> NAMES = List.of(
            "overloaded_pattern_disk_provider",
            "packaged_pattern_disk_provider",
            "wireless_packaged_pattern_disk_provider");

    @Test
    void threeRecipesFollowTheSameShape() throws IOException {
        for (String name : NAMES) {
            var json = JsonParser.parseString(Files.readString(RECIPES.resolve(name + ".json")))
                    .getAsJsonObject();
            assertEquals("minecraft:crafting_shapeless", json.get("type").getAsString(), name);

            var ingredients = json.getAsJsonArray("ingredients");
            assertEquals(2, ingredients.size(), name);
            String upstream = ingredients.get(0).getAsJsonObject().get("item").getAsString();
            assertEquals("ae2_pattern_disk:pattern_disk_16k",
                    ingredients.get(1).getAsJsonObject().get("item").getAsString(), name);
            assertEquals("ae2_pattern_disk:" + name,
                    json.getAsJsonObject("result").get("id").getAsString(), name);

            var conditions = json.getAsJsonArray("neoforge:conditions");
            assertEquals(1, conditions.size(), name);
            var condition = conditions.get(0).getAsJsonObject();
            assertEquals("neoforge:mod_loaded", condition.get("type").getAsString(), name);
            int colon = upstream.indexOf(':');
            assertTrue(colon > 0, name + " 的上游物品缺命名空间：" + upstream);
            assertEquals(upstream.substring(0, colon), condition.get("modid").getAsString(), name);
        }
    }

    /** 上游配方住在 compileOnly 的 jar 里；test 类路径继承 main 的编译类路径，按资源路径就取得到。 */
    @Test
    void ingredientsAreTheUpstreamRecipeOutputs() throws IOException {
        for (String name : NAMES) {
            var json = JsonParser.parseString(Files.readString(RECIPES.resolve(name + ".json"))).getAsJsonObject();
            String upstream = json.getAsJsonArray("ingredients").get(0).getAsJsonObject().get("item").getAsString();
            int colon = upstream.indexOf(':');
            String resource = "data/" + upstream.substring(0, colon) + "/recipe/lightning_assembly/"
                    + upstream.substring(colon + 1) + ".json";
            var url = getClass().getClassLoader().getResource(resource);
            assertNotNull(url, "上游 jar 里没有 " + resource + "：物品改名或配方路径变了（" + name + "）");
            try (var stream = url.openStream()) {
                var upstreamJson = JsonParser
                        .parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(upstream, upstreamJson.getAsJsonObject("result").get("id").getAsString(),
                        name + " 的材料应当正是上游那份配方的产物");
            }
        }
    }
}
