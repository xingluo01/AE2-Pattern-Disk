package io.github.lounode.ae2pattern.common.logic;

/**
 * 样板磁盘供应器的产物抽取模式（三档轮换）。
 *
 * <p>
 * <b>常量顺序即协议</b>：这个枚举经 {@code @GuiSync} 按 ordinal 同步，重排或插队会让客户端读错档位。
 * </p>
 *
 * <p>
 * 会执行抽取的两档（{@link #NORMAL}、{@link #FAST}）读取的是同一套加强视图：供应器正对的那一面之外，
 * 还查无面视图（{@code side = null}）与目标方块其余五个面，用于产物被面配置挡住的机器；两者之间只差
 * 节拍（见 {@link ProviderExtractionPacing}）。{@link #OFF} 不抽取，也不做这些查询。
 * </p>
 */
public enum ProviderExtractionMode {
    /** 不抽取：只靠目标设备自己的主档推送，不额外从目标往外拿东西。 */
    OFF,
    /** 抽取：每 {@link ProviderExtractionPacing#NORMAL_INTERVAL} tick 抽一次目标里的产物。 */
    NORMAL,
    /** 快速抽取：起始每 tick 抽一次，抽不到就退避，间隔翻倍到上限为止。 */
    FAST;

    public ProviderExtractionMode next() {
        return switch (this) {
            case OFF -> NORMAL;
            case NORMAL -> FAST;
            case FAST -> OFF;
        };
    }
}
