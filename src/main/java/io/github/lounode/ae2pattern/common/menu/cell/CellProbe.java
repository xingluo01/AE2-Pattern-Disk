package io.github.lounode.ae2pattern.common.menu.cell;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 反射探针：按类缓存「有没有这个方法」，再按名字调。
 *
 * <p>网络里大量机器与我们无关，每轮为它们重复创建一堆 {@code NoSuchMethodException} 既慢又刷日志；缺方法这件事
 * 与实例无关，按类缓存就够了。扫描、取放、优先级三处共用这一份缓存。</p>
 */
final class CellProbe {

    private CellProbe() {
    }

    /** 节点扫描也会遇到大量非存储机器，按类缓存缺失方法，避免每轮为它们重复创建异常。 */
    static final ClassValue<ConcurrentMap<String, Optional<Method>>> METHODS = new ClassValue<>() {
        @Override
        protected ConcurrentMap<String, Optional<Method>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /** 无参方法按名字调；没有这个方法或调用失败都返回 null（可选依赖的常态）。 */
    static Object invoke(Object target, String name) {
        try {
            var method = METHODS.get(target.getClass()).computeIfAbsent(name, key -> {
                try {
                    return Optional.of(target.getClass().getMethod(key));
                } catch (ReflectiveOperationException | RuntimeException absent) {
                    return Optional.empty();
                }
            });
            return method.isPresent() ? method.get().invoke(target) : null;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }
}
