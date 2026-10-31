package io.github.lounode.ae2pattern.common.logic;

/**
 * 一台自己要每 tick 做事的磁盘供应器逻辑。
 *
 * <p>
 * 本模组的 {@link PatternDiskProviderLogic} 自己带 {@code tickServer()}，两只宿主直接认它的类型；继承第三方
 * 供应器逻辑的设备（AECS 谐振）不能继承那个类，但同样需要方块 tick 与面板世界 tick 的驱动。这个接口只表达
 * 「宿主每 tick 调我一次」，不把第三方类型带进 common 包——否则未装邻居时这里会先炸在类加载上。
 * </p>
 */
public interface DiskProviderTicker {

    void tickServer();
}
