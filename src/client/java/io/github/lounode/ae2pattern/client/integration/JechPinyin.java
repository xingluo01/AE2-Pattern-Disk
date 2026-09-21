package io.github.lounode.ae2pattern.client.integration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JECH（Just Enough Characters，拼音搜索）的软依赖适配。
 *
 * <p>磁盘搜索要用拼音匹配中文名，但这不该把 JECH 变成硬依赖：没装 JECH 的整合包必须照常加载，这时退化成
 * 小写子串匹配。所以这里按类名反射取一次
 * {@code me.towdium.jecharacters.utils.Match#contains(CharSequence, CharSequence)}，取不到就整条链路降级。</p>
 *
 * <p>用反射而不是 {@code ModList} 守卫 + 直接引用：JVM 在类被加载时就解析引用，守卫只挡执行、不挡加载，
 * 那会让没装 JECH 的客户端崩在 {@code NoClassDefFoundError}（本项目对 ExtendedAE Plus 踩过同一个坑）。</p>
 */
public final class JechPinyin {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.jech");

    private static final String MATCH_CLASS = "me.towdium.jecharacters.utils.Match";

    /** 只探测一次：探测过（不论成败）就不再走反射。 */
    private static boolean probed;
    private static @Nullable Method contains;

    private JechPinyin() {
    }

    /**
     * {@code text} 是否包含 {@code query}：装了 JECH 时按拼音/首字母匹配，否则只比小写子串。
     *
     * <p>两个入参都按小写比较——调用方传进来的 {@code query} 通常已经小写，磁盘名则是原样，这里统一处理。</p>
     */
    public static boolean contains(String text, String query) {
        var lowerText = text.toLowerCase(Locale.ROOT);
        var lowerQuery = query.toLowerCase(Locale.ROOT);
        var method = matchMethod();
        if (method != null) {
            try {
                return Boolean.TRUE.equals(method.invoke(null, lowerText, lowerQuery));
            } catch (Throwable failed) {
                // 走到这里说明「类在、方法签名也在，但调不动」（例如它不是静态方法，或 JECH 自己的词典还没就绪）。
                // 搜索是按字符触发的热路径，绝不能每次敲键都造一次异常 + 打一行日志，所以这里直接永久降级。
                contains = null;
                LOGGER.warn("JECH pinyin match is unusable; falling back to literal disk search", failed);
            }
        }
        return lowerText.contains(lowerQuery);
    }

    private static @Nullable Method matchMethod() {
        if (!probed) {
            probed = true;
            try {
                var method = Class.forName(MATCH_CLASS)
                        .getMethod("contains", CharSequence.class, CharSequence.class);
                // 探测只能证明「类与方法签名存在」，证明不了它是静态方法；非静态时反射调用必然失败，
                // 与其每次敲键都试一次，不如这里就当作不可用（留一行日志，免得玩家装了 JECH 却只看到
                // 「搜不出来」）。
                if (Modifier.isStatic(method.getModifiers())) {
                    contains = method;
                } else {
                    contains = null;
                    LOGGER.info("JECH Match#contains is not static; disk search stays literal");
                }
            } catch (Throwable absent) {
                // 没装 JECH（或它改了 API）：功能降级即可，不是错误。
                contains = null;
            }
        }
        return contains;
    }
}
