package io.github.lounode.ae2pattern.integration.ae2lt;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.upgrades.Upgrades;

/**
 * AE2 Lightning Tech（modId {@code ae2lt}）的过载样板：本终端「过载」档编出来的就是它的物品，
 * 以及那枚「过载样板编码器」在升级表上的登记。
 *
 * <p>隔离在这一包里，与其它集成同一个理由：AE2LT 缺席时这些类不会被解析，本模组照常工作——那一档
 * 也就不会出现（它的可用性看升级槽里有没有那枚编码器，见 {@link #isEncoder(ItemStack)}）。</p>
 *
 * <p>过载样板带两个别处没有的东西：每个槽位声明自己是<b>输入还是输出</b>，以及该槽的<b>组件匹配模式</b>
 * （{@code MatchMode.STRICT} 连组件一起比，{@code ID_ONLY} 只比物品 id）。后者就是面板上那个「忽略组件
 * 匹配」开关——它是样板自身的一部分，随样板走，不需要本模组的磁盘另存一份。</p>
 */
public final class OverloadPatterns {

    /** AE2LT 的 modId。 */
    private static final String MOD_ID = "ae2lt";

    /**
     * 无线终端那一侧要用的 AE2WTLib 登记入口。
     *
     * <p>与高级/雕凿两枚编码器同一个理由：无线终端的升级槽不读 AE2 那张表，而这条登记失败的表观只是
     * 「无线终端上那一格放不进卡」，不该让它带崩整个初始化，所以反射调用、失败只记一条日志。</p>
     */
    private static final String AE2WTLIB_MOD_ID = "ae2wtlib";
    private static final String UPGRADE_HELPER = "de.mari_023.ae2wtlib.api.registration.UpgradeHelper";

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.ae2lt");

    // ---- 编码路径要用的类名 ----
    //
    // AE2LT 是 localRuntime：编译期刻意不依赖它（它缺席时本模组照常编译），所以下面这些调用全走反射。
    // 类名与方法名对着 2.1.1 写死；AE2LT 改了 API 时的表观是「点了编写样板、聊天栏回一句需要输入」
    // （编码返回 null 就落到那句提示），同时这里留一条 WARN。这串名字由玩家点击触发、不是热路径，
    // 所以不缓存反射结果——每次调一遍比维护一份失效缓存简单。
    private static final String OVERLOAD_ITEM_TYPE = "com.moakiee.ae2lt.item.OverloadPatternItem";
    private static final String MOD_ITEMS_TYPE = "com.moakiee.ae2lt.registry.ModItems";
    private static final String CONVERSION_SERVICE_TYPE =
            "com.moakiee.ae2lt.overload.pattern.PatternConversionService";
    private static final String PLAIN_RESOLVER_TYPE =
            "com.moakiee.ae2lt.overload.runtime.pattern.Ae2PlainPatternResolver";
    private static final String PARSED_PATTERN_TYPE =
            "com.moakiee.ae2lt.overload.runtime.pattern.ParsedPatternDefinition";
    private static final String ENCODED_PATTERN_TYPE =
            "com.moakiee.ae2lt.overload.runtime.model.EncodedOverloadPattern";
    private static final String MATCH_MODE_TYPE = "com.moakiee.ae2lt.overload.runtime.model.MatchMode";

    /** AE2LT 的「过载样板编码器」（{@code item.ae2lt.overload_pattern_encoder}）。 */
    private static final ResourceLocation OVERLOAD_ENCODER =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "overload_pattern_encoder");

    private OverloadPatterns() {
    }

    /**
     * 这一叠升级卡里是否装着过载样板编码器——那一档的可用性就按它判，与高级档同口径（升级槽里那张卡）。
     *
     * <p>只查物品 id，不碰 AE2LT 的任何类：注册表按 id 查，模组不在场时同样安全（查不到就是 false），
     * 所以这里不会把缺席的可选模组拖进「加载期定义它的类」那个老坑。</p>
     */
    public static boolean isEncoder(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(OVERLOAD_ENCODER);
    }

    /**
     * 把过载样板编码器登记到这些终端上，每种最多一枚。
     *
     * <p><b>不登记就等于放不进去。</b>升级槽的准入（{@code TerminalUpgradeSlot.mayPlace}）除 AE2 那道
     * {@code instanceof UpgradeCardItem} 外，回退那一闸就是查 {@code Upgrades} 表：
     * {@code upgrades.getMaxInstalled(item) > 0}。过载编码器不是 {@code UpgradeCardItem} 的子类，所以它
     * 只能靠这张表进去——只看 {@link #isEncoder(ItemStack)} 是不够的，那个判定管的是「档位可不可用」，
     * 管不着「卡能不能放进去」。</p>
     *
     * <p>要在模组初始化结束前调用（与 AE2 其他 {@code Upgrades.add} 一样，运行期再改这张表行为未定义）。</p>
     */
    public static void register(ItemLike... terminals) {
        registerFor(terminals);
        registerForWirelessTerminals();
    }

    /** 给这些终端直接登记，每种最多一枚。无线那侧要用它，理由同高级编码器。 */
    public static void registerFor(ItemLike... terminals) {
        var card = encoder();
        if (card == null) {
            return;
        }
        for (var terminal : terminals) {
            Upgrades.add(card, terminal, 1);
        }
        // 回读一次。登记没生效的表观是「槽在那儿、卡放不进」，而玩家那时只会以为卡坏了。
        for (var terminal : terminals) {
            if (Upgrades.getMaxInstallable(card, terminal) < 1) {
                LOGGER.warn("AE2LT's overload pattern encoder did not register for {}; its upgrade slot will "
                        + "show up but stay unusable", BuiltInRegistries.ITEM.getKey(terminal.asItem()));
            }
        }
    }

    /** AE2LT 已加载且那件物品在册时返回它，否则 null（并留一条能对上号的日志）。 */
    @Nullable
    private static Item encoder() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return null;
        }
        var card = BuiltInRegistries.ITEM.get(OVERLOAD_ENCODER);
        if (card == Items.AIR) {
            LOGGER.debug("AE2LT is loaded but {} is not registered; skipping the overload pattern encoder",
                    OVERLOAD_ENCODER);
            return null;
        }
        return card;
    }

    /**
     * 把输出栏那张样板按面板上的逐行设定编成过载样板；AE2LT 缺席、API 对不上、或这张样板它解不开时
     * 返回 {@code null}（调用方会回一句提示）。
     *
     * <p>对应 AE2LT 自己那个 {@code OverloadPatternEncoderMenu.encodeResult()} 的三步：解析源样板 →
     * 按逐槽 {@code MatchMode} 组装 {@code EncodedOverloadPattern} → 交给
     * {@code PatternConversionService.createOverloadPatternStack} 落成物品。差别只有一处：它只让玩家改
     * MatchMode（输入/输出哪一边由源样板固定），而本面板还让玩家逐行改「这一行算输入还是输出」，所以
     * 这里不照搬它的 edit state，直接组装 encoded——槽号仍取源样板解析出的
     * {@code slotIndex()}，放进哪一边听面板的。</p>
     *
     * @param sourcePattern        源样板（终端输出栏那一张，与摊行同源）
     * @param rowIsOutput          每行是否算输出；行序与摊行一致（先是源样板的输入，再是它的输出）
     * @param rowIgnoresComponents 每行是否忽略组件匹配（true → {@code MatchMode.ID_ONLY}）
     * @param level                解析源样板要用（{@code Ae2PlainPatternResolver} 按它认配方）
     */
    @Nullable
    public static ItemStack encode(
            ItemStack sourcePattern,
            boolean[] rowIsOutput,
            boolean[] rowIgnoresComponents,
            Level level) {
        if (!ModList.get().isLoaded(MOD_ID) || sourcePattern.isEmpty() || rowIsOutput.length == 0) {
            return null;
        }
        try {
            return encodeReflectively(sourcePattern, rowIsOutput, rowIgnoresComponents, level);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not encode an overloaded pattern; AE2LT's API may have changed", e);
            return null;
        }
    }

    private static ItemStack encodeReflectively(
            ItemStack sourcePattern,
            boolean[] rowIsOutput,
            boolean[] rowIgnoresComponents,
            Level level) throws ReflectiveOperationException {
        var overloadItemType = Class.forName(OVERLOAD_ITEM_TYPE);
        // ModItems.OVERLOAD_PATTERN 是 DeferredItem，.get() 才是物品本身。
        var deferred = Class.forName(MOD_ITEMS_TYPE).getField("OVERLOAD_PATTERN").get(null);
        var overloadItem = deferred.getClass().getMethod("get").invoke(deferred);
        if (!overloadItemType.isInstance(overloadItem)) {
            LOGGER.warn("AE2LT's OVERLOAD_PATTERN is not an OverloadPatternItem; skipping");
            return null;
        }

        // 解析源样板：用 AE2LT 自己的解析器，免得在这里重写一遍「哪张样板能当过载源」的判断。
        var resolver = Class.forName(PLAIN_RESOLVER_TYPE).getConstructor(Level.class).newInstance(level);
        var parsedType = Class.forName(PARSED_PATTERN_TYPE);
        var parsed = resolver.getClass().getMethod("resolve", ItemStack.class).invoke(resolver, sourcePattern);
        if (parsed == null) {
            return null;
        }
        var inputs = (List<?>) parsedType.getMethod("inputs").invoke(parsed);
        var outputs = (List<?>) parsedType.getMethod("outputs").invoke(parsed);

        var matchModeType = Class.forName(MATCH_MODE_TYPE);
        var matchModeValueOf = matchModeType.getMethod("valueOf", String.class);
        var strict = matchModeValueOf.invoke(null, "STRICT");
        var idOnly = matchModeValueOf.invoke(null, "ID_ONLY");

        var encodedType = Class.forName(ENCODED_PATTERN_TYPE);
        var builder = encodedType.getMethod("builder").invoke(null);
        var builderType = builder.getClass();
        var input = builderType.getMethod("input", int.class, matchModeType);
        var output = builderType.getMethod("output", int.class, matchModeType);

        // 行数与源样板解出的元素数必须相等：摊行时会跳过解不出物品的项，而这里纯按下标取，一旦两边
        // 数目不同，行上的「输入/输出」与「忽略组件」就会套到别的槽上——静默错编，比不编更糟。
        if (rowIsOutput.length != inputs.size() + outputs.size()) {
            LOGGER.warn("Overload rows ({}) do not match the source pattern's {} inputs + {} outputs; "
                    + "refusing to encode", rowIsOutput.length, inputs.size(), outputs.size());
            return null;
        }
        int rows = rowIsOutput.length;
        for (int row = 0; row < rows; row++) {
            var element = row < inputs.size() ? inputs.get(row) : outputs.get(row - inputs.size());
            var slotIndex = (int) element.getClass().getMethod("slotIndex").invoke(element);
            var mode = rowIgnoresComponents[row] ? idOnly : strict;
            (rowIsOutput[row] ? output : input).invoke(builder, slotIndex, mode);
        }
        var encoded = builderType.getMethod("build").invoke(builder);

        var serviceType = Class.forName(CONVERSION_SERVICE_TYPE);
        var service = serviceType.getConstructor().newInstance();
        var stack = serviceType
                .getMethod("createOverloadPatternStack", overloadItemType, parsedType, encodedType)
                .invoke(service, overloadItem, parsed, encoded);
        return stack instanceof ItemStack itemStack ? itemStack : null;
    }

    /**
     * 无线终端那一侧：AE2WTLib 的 {@code addUpgradeToAllTerminals} 会把它挂到**所有** AE2WTLib 终端上——
     * 本模组的两个无线终端与它的通用终端都在内。
     */
    private static void registerForWirelessTerminals() {
        if (!ModList.get().isLoaded(AE2WTLIB_MOD_ID)) {
            return;
        }
        var card = encoder();
        if (card == null) {
            return;
        }
        try {
            var helper = Class.forName(UPGRADE_HELPER);
            helper.getMethod("addUpgradeToAllTerminals", ItemLike.class, int.class).invoke(null, card, 1);
        } catch (ClassNotFoundException e) {
            // 没装 AE2WTLib：本模组的无线终端也一并缺席，正常。
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not register the overload pattern encoder for AE2WTLib's wireless terminals", e);
        }
    }
}
