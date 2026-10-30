package io.github.lounode.ae2pattern.common.logic;

/**
 * 一个能从磁盘重建样板列表的供应器逻辑。
 *
 * <p>磁盘栏组件（{@link DiskHostSupport}）只需要这两件事，不关心逻辑的父类是谁：本模组自己的
 * {@link PatternDiskProviderLogic} 实现它，继承闪电科技供应器逻辑的那几台设备也实现它。两个方法都是
 * 既有公开方法，接口只用来收窄依赖面。</p>
 */
public interface PatternsFromDisks {

    /**
     * @param force 磁盘没动也强制重建（内存卡导入会改写镜像而磁盘原地不动）
     * @return 是否真的重建了
     */
    boolean refreshPatternsFromDisks(boolean force);

    /** 清空镜像样板栏而不触发逐槽通知；掉落与导入前调用。 */
    void clearMirror();
}