/**
 * 装配室的菜单：高效分子装配室（{@link io.github.lounode.ae2pattern.common.menu.assembler.PatternDiskAssemblerMenu}）
 * 与批处理装配室（{@link io.github.lounode.ae2pattern.common.menu.assembler.BatchAssemblerMenu}）。
 *
 * <p>两台的槽位都在构造器里一次建好，区别在「批处理那台多一组并行车道」和「谁在读磁盘」——执行逻辑不在这里，
 * 在 {@code common/logic} 与各自的方块实体里。</p>
 */
package io.github.lounode.ae2pattern.common.menu.assembler;
