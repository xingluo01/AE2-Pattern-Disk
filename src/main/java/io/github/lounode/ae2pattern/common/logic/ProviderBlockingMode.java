package io.github.lounode.ae2pattern.common.logic;

/**
 * 样板磁盘供应器的阻挡模式（三档轮换）。
 *
 * <p>AE2 自带的 {@code Settings.BLOCKING_MODE} 只有开关两态，而且判据固定为「目标里出现任一已装样板的输入就挡」。
 * 这里把三档放进本模组自己的字段：{@link #DEFAULT} 与 {@link #ALL} 完全交回父类（同时把 AE2 的开关同步成
 * 对应值，第三方读那个设置时口径一致），{@link #SMART} 才由本类接管投递路径做自己的判据。</p>
 *
 * <p><b>常量顺序即协议</b>：这个枚举经 {@code @GuiSync} 按 ordinal 同步，重排或插队会让客户端读错档位。</p>
 */
public enum ProviderBlockingMode {
    /** 不阻挡：与 AE2 原生「关」一致。 */
    DEFAULT,
    /** 阻挡：目标里只要出现任一已装样板的原材料就换一个目标（AE2 原生「开」）。 */
    ALL,
    /**
     * 智能阻挡：只有目标内容物<b>完全</b>由本样板的原材料构成时才放行。
     *
     * <p>与 {@link #ALL} 的差别是方向相反：同配方的料可以继续往里堆（倍增要靠它），一旦混进任何不属于本样板
     * 的原料就换一个目标。空目标视为放行——否则第一份材料永远投不进去。</p>
     */
    SMART;

    public ProviderBlockingMode next() {
        return switch (this) {
            case DEFAULT -> ALL;
            case ALL -> SMART;
            case SMART -> DEFAULT;
        };
    }
}
