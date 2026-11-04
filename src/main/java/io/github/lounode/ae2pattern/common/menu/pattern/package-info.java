/**
 * 样板终端这一套：编码终端、管理终端、供应器菜单，以及它们共用的编码逻辑与磁盘索引。
 *
 * <p>{@link io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskEncodingTermMenu} 是这一层的中枢
 * （编码、空白样板记账、磁盘写入与取出、标记与分类导入都在它身上，见 {@code docs/TODO.md} 的拆分条目）。
 * {@link io.github.lounode.ae2pattern.common.menu.pattern.PatternEncodingLogic} 与
 * {@link io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic} 是它的两块状态机，
 * {@link io.github.lounode.ae2pattern.common.menu.pattern.DiskIndex} 管磁盘清单与序列号。</p>
 *
 * <p>三个供应器菜单（普通 / 标记式 / 陨石）共用 {@code PatternDiskProviderMenu} 的槽位与主机接口，只在
 * 「认哪种盘、标怎么来的」上有差别。</p>
 */
package io.github.lounode.ae2pattern.common.menu.pattern;
