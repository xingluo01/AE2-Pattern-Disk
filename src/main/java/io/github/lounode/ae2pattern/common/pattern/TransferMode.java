package io.github.lounode.ae2pattern.common.pattern;

/**
 * 样板转存器的工作模式。
 *
 * <p>两种模式决定输入槽的处理方式：</p>
 * <ul>
 *   <li>{@link #STORE} 样板存储：把编码样板提取到磁盘；把有内容磁盘中的配方<b>移动</b>到右侧目标磁盘（源盘被清空）。</li>
 *   <li>{@link #COPY} 样板复写：把有内容磁盘中的配方<b>复制</b>到右侧目标磁盘（源盘内容保留）。</li>
 * </ul>
 */
public enum TransferMode {
    STORE,
    COPY,
}
