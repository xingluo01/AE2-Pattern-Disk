package io.github.lounode.ae2pattern.integration.advancedae;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * AdvancedAE 高级处理样板的探针与读写桥。
 *
 * <p>高级处理样板给每个输入各记一个接入面，投递时用那张表决定「这份材料从哪一面接进邻居」。本模组有两处
 * 要碰它：供应器投递时要读（{@link #accessSide}），编码终端的高级编码模式要读还要改（{@link #sparseInputs}、
 * {@link #directionMap}、{@link #rewriteSides}）。而 AdvancedAE 没有公开 API——样板与供应器逻辑都在
 * {@code common} 包，它的 {@code api} 包只有与本主题无关的几个类——所以这里全程走反射。</p>
 *
 * <p><b>探不到就当作普通样板</b>，这是整套写法的价值所在：AdvancedAE 缺席、版本变了、方法改名了，本模组
 * 的行为都只是退回默认（供应器单目标投递、终端不显示高级编码模式），不会崩。反射结果缓存一次，之后每次
 * 调用只做一次 {@code isInstance}。</p>
 *
 * <p>对照 AdvancedAE 的 {@code forge/1.21.1} 分支，用到的都是 {@code IAdvPatternDetails} 与其实现类
 * {@code AdvProcessingPattern} 上的成员：{@code directionalInputsSet()}、{@code getDirectionSideForInputKey(AEKey)}、
 * {@code getSparseInputs()}、{@code getDirectionMap()}、以及静态的 {@code encode(ItemStack, List, List, HashMap)}。
 * 1.6.11 与 1.6.12 两份都在（签名逐字一致）。</p>
 */
public final class AdvPatternSupport {

    /** AdvancedAE 的高级样板编码器：它装进终端的升级槽，高级编码模式才出现。 */
    public static final ResourceLocation ADVANCED_ENCODER = ResourceLocation.parse(
            "advanced_ae:adv_pattern_encoder");

    private static final String DETAILS_TYPE = "net.pedroksl.advanced_ae.common.patterns.IAdvPatternDetails";
    private static final String PATTERN_TYPE = "net.pedroksl.advanced_ae.common.patterns.AdvProcessingPattern";
    private static final String ENCODER_TYPE = "net.pedroksl.advanced_ae.common.patterns.AdvPatternDetailsEncoder";

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.advancedae");

    /** 探测结果三态：null = 还没探过，{@code ABSENT} = 探过且不在场。 */
    private static final Class<?> ABSENT = void.class;

    private static volatile Class<?> detailsType;
    private static volatile Method directionalInputsSet;
    private static volatile Method directionForInputKey;
    private static volatile Method sparseInputs;
    private static volatile Method directionMap;
    private static volatile Method encodeProcessing;

    private AdvPatternSupport() {
    }

    /** 这张样板是不是「每个输入各指定一个接入面」的高级处理样板。 */
    public static boolean hasDirectionalInputs(@Nullable IPatternDetails details) {
        if (details == null || !isPattern(details)) {
            return false;
        }
        try {
            return (boolean) directionalInputsSet.invoke(details);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 运行期抛错一律当没有：这条路径的结果与「AdvancedAE 不在场」完全相同，投递每 tick 都可能走到，
            // 不值得为它刷日志。
            return false;
        }
    }

    /** 这张栈能不能当高级编码模式的对象——它是高级处理样板，且方向表可读、可重新编码。 */
    public static boolean isEditable(@Nullable IPatternDetails details) {
        return details != null && isPattern(details)
                && sparseInputs != null && directionMap != null && encodeProcessing != null;
    }

    /**
     * 这个输入声明的接入面；没声明、或探不到 AdvancedAE 时返回 {@code null}。
     *
     * <p>{@code null} 在这里不是错误值，而是「交给调用方决定」：照 AE2 自己的做法用「邻居对着我」的那一面。
     * AdvancedAE 自身也允许 {@code null}（内存卡导入路径会把面写成 -1，读回时还原成 null）。</p>
     */
    @Nullable
    public static Direction accessSide(@Nullable IPatternDetails details, @Nullable AEKey key) {
        if (details == null || key == null || !isPattern(details)) {
            return null;
        }
        try {
            return (Direction) directionForInputKey.invoke(details, key);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 这张样板真正的输入材料（稀疏表），与方向表一一对应；不可读时返回 {@code null}。
     *
     * <p>不用 {@code getInputs()}：那个返回的是「每个输入槽的候选模板」，倍数与替代品都在里面；编码回写要的
     * 是已经摊开的实际材料。</p>
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static List<GenericStack> sparseInputs(@Nullable IPatternDetails details) {
        if (!isEditable(details)) {
            return null;
        }
        try {
            return (List<GenericStack>) sparseInputs.invoke(details);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 这张样板声明的「输入 → 接入面」；不可读时返回 {@code null}。
     *
     * <p>返回的表里允许 {@code null} 值：那是「没指定面」，等价于「相邻」。</p>
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static Map<AEKey, Direction> directionMap(@Nullable IPatternDetails details) {
        if (!isEditable(details)) {
            return null;
        }
        try {
            return (Map<AEKey, Direction>) directionMap.invoke(details);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * 把「输入 + 输出 + 每个输入的接入面」编成一张高级处理样板。
     *
     * <p>走的是 AdvancedAE 自己的编码入口 {@code AdvPatternDetailsEncoder.encodeProcessingPattern}，它返回
     * 新的样板栈（不是原地写）；比它底部那个 {@code AdvProcessingPattern.encode(void)} 省事，也不依赖调用方
     * 先准备一只空白高级样板。</p>
     *
     * <p>面表里允许缺项或 {@code null}：那表示这个输入「相邻就行」，与 AdvancedAE 自己的口径一致。</p>
     *
     * @return 编好的样板；不可用或编码失败时返回 {@code null}（调用方退回普通处理样板）。
     */
    @Nullable
    public static ItemStack encodeProcessing(List<GenericStack> inputs, List<GenericStack> outputs,
            Map<AEKey, Direction> sides) {
        if (encodeProcessing == null || inputs.isEmpty()) {
            return null;
        }
        try {
            return (ItemStack) encodeProcessing.invoke(null, inputs, outputs, new HashMap<>(sides));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not encode an advanced processing pattern", e);
            return null;
        }
    }

    private static boolean isPattern(IPatternDetails details) {
        var type = detailsType();
        return type != null && type.isInstance(details);
    }

    /** AdvancedAE 在场且样板接口可达时返回它，否则返回 {@code null}。结果只解析一次。 */
    @Nullable
    private static Class<?> detailsType() {
        var cached = detailsType;
        if (cached == ABSENT) {
            return null;
        }
        if (cached != null) {
            return cached;
        }
        synchronized (AdvPatternSupport.class) {
            if (detailsType == ABSENT) {
                return null;
            }
            if (detailsType == null) {
                try {
                    var type = Class.forName(DETAILS_TYPE);
                    var implementation = Class.forName(PATTERN_TYPE);
                    var encoder = Class.forName(ENCODER_TYPE);
                    directionalInputsSet = type.getMethod("directionalInputsSet");
                    directionForInputKey = type.getMethod("getDirectionSideForInputKey", AEKey.class);
                    sparseInputs = implementation.getMethod("getSparseInputs");
                    directionMap = implementation.getMethod("getDirectionMap");
                    encodeProcessing = encoder.getMethod("encodeProcessingPattern", List.class, List.class,
                            HashMap.class);
                    detailsType = type;
                } catch (ClassNotFoundException e) {
                    // 没装：这是常态，不记日志。
                    detailsType = ABSENT;
                    return null;
                } catch (LinkageError e) {
                    // 装了、但这个类连不上（多半是 AdvancedAE 自己缺前置）：另一种病因，分开说，
                    // 否则文案会把人拉去对版本。
                    LOGGER.info("{} could not be linked; a dependency of AdvancedAE may be missing. "
                            + "Multi-face delivery stays disabled", DETAILS_TYPE, e);
                    detailsType = ABSENT;
                    return null;
                } catch (ReflectiveOperationException e) {
                    // 装了但对不上：记一条。不记的话功能静默死亡，用户只会看到「和没装一模一样」，
                    // 而这两件事的排查方式差很远。
                    LOGGER.info("AdvancedAE is present but {} no longer exposes the expected directional-input "
                            + "members; multi-face delivery stays disabled", DETAILS_TYPE, e);
                    detailsType = ABSENT;
                    return null;
                }
            }
            return detailsType;
        }
    }
}
