package io.github.lounode.ae2pattern.client.integration;

import java.lang.reflect.Method;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;

import appeng.api.stacks.GenericStack;

/**
 * 查看器的 ingredient → AE2 栈的转换。
 *
 * <p>物品与流体是内建的，不依赖任何模组。化学品这类自定义 {@code AEKeyType} 不归 AE2 也不归本模组，而是由
 * 提供该 key type 的模组自己注册转换器：AE2 的 EMI 侧用 {@code EmiStackConverters}（例：Applied Mekanistics
 * 为 {@code appmek:chemical} 注册了化学品转换器），JEI 侧则用 AE2 的 JEI 桥 {@code ae2jeiintegration} 的
 * {@code IngredientConverters}。所以这里不自己判断 ingredient 是什么类型，而是走桥的注册表——桥里有什么，
 * 就能编码什么。</p>
 *
 * <p>桥是软依赖：没装时只有物品与流体能编码，其余类型安安静静地被跳过。用反射而不是直接引用，理由与
 * {@link JechPinyin} 相同——守卫只挡执行、不挡类加载，直接引用会让没装桥的客户端崩在
 * {@code NoClassDefFoundError}。</p>
 */
public final class JeiIngredientConverters {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.jei");
    private static final String CONVERTERS_CLASS = "tamaized.ae2jeiintegration.api.integrations.jei.IngredientConverters";
    private static final String CONVERTER_CLASS = "tamaized.ae2jeiintegration.api.integrations.jei.IngredientConverter";

    /** 只探测一次：探测过（不论成败）就不再走反射。 */
    private static boolean probed;
    private static @Nullable Method getConverter;
    private static @Nullable Method getStackFromIngredient;

    /** 转换器自己报错只记一次：修不好就跳这个 ingredient，不必按导入次数刷屏。 */
    private static boolean converterFailureLogged;

    private JeiIngredientConverters() {
    }

    /**
     * 带类型的入口：内建类型直接转，其余按 {@code IIngredientType} 去桥的注册表里查转换器。
     */
    public static @Nullable GenericStack toGenericStack(IIngredientType<?> type, @Nullable Object ingredient) {
        var builtin = toBuiltinStack(ingredient);
        return builtin != null ? builtin : convertViaBridge(type, ingredient);
    }

    /** 同上，直接吃 JEI 的带类型 ingredient。 */
    public static @Nullable GenericStack toGenericStack(ITypedIngredient<?> typed) {
        return toGenericStack(typed.getType(), typed.getIngredient());
    }

    /** 物品与流体：不依赖桥的那一段。 */
    @Nullable
    private static GenericStack toBuiltinStack(@Nullable Object ingredient) {
        if (ingredient instanceof ItemStack itemStack && !itemStack.isEmpty()) {
            return GenericStack.fromItemStack(itemStack);
        }
        if (ingredient instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            return GenericStack.fromFluidStack(fluidStack);
        }
        return null;
    }

    @Nullable
    private static GenericStack convertViaBridge(IIngredientType<?> type, @Nullable Object ingredient) {
        if (ingredient == null) {
            return null;
        }
        // 先取到局部变量再调用：把两个字段同时当守卫和调用目标，万一中途被置空就会变成一次假故障。
        // lookupConverters() 顺便负责首次探测。
        var lookup = lookupConverters();
        var resolver = getStackFromIngredient;
        if (lookup == null || resolver == null) {
            return null;
        }
        try {
            var converter = lookup.invoke(null, type);
            if (converter == null) {
                return null;
            }
            // 走接口上的方法而不是实现类：泛型参数擦除后是 Object，实现类那边可能有桥接方法，
            // 直接问接口拿更稳。
            var stack = resolver.invoke(converter, ingredient);
            return stack instanceof GenericStack genericStack ? genericStack : null;
        } catch (java.lang.reflect.InvocationTargetException converterFailed) {
            // 转换器自己抛了：只跳这一个 ingredient，不把整座桥关掉——否则一个坏转换器会把其它
            // 类型的导入一起弄失效。
            reportConverterFailure(converterFailed.getCause());
            return null;
        } catch (Throwable failed) {
            // 桥在、签名也在，但调不动（版本不匹配之类）：永久降级，免得每次导入都造一次异常 + 一行日志。
            getConverter = null;
            getStackFromIngredient = null;
            LOGGER.warn("AE2's JEI ingredient conversion is unusable; only items and fluids can be encoded", failed);
            return null;
        }
    }

    private static void reportConverterFailure(@Nullable Throwable cause) {
        if (!converterFailureLogged) {
            converterFailureLogged = true;
            LOGGER.warn("An AE2 JEI ingredient converter failed; that ingredient is skipped"
                    + " (further converter failures are not logged)", cause);
        }
    }

    private static @Nullable Method lookupConverters() {
        if (!probed) {
            probed = true;
            try {
                getConverter = Class.forName(CONVERTERS_CLASS)
                        .getMethod("getConverter", IIngredientType.class);
                getStackFromIngredient = Class.forName(CONVERTER_CLASS)
                        .getMethod("getStackFromIngredient", Object.class);
            } catch (Throwable absent) {
                // 没装桥（或它改了 API）：功能降级即可，不是错误。留一条 debug，免得日后只能看到
                // 「化学品又进不去」而毫无线索。
                getConverter = null;
                getStackFromIngredient = null;
                LOGGER.debug("AE2's JEI ingredient bridge is unavailable; only items and fluids can be encoded",
                        absent);
            }
        }
        return getConverter;
    }
}
