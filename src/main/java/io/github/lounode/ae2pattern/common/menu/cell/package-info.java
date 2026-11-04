/**
 * 元件管理终端（cell management terminal）这一套：菜单、驱动器扫描、存储元件取放、两个管理域、分区与升级库存。
 *
 * <p>按职责再分一层，每个类只做一件事：</p>
 *
 * <ul>
 *   <li>{@link io.github.lounode.ae2pattern.common.menu.cell.CellManagementTermMenu} —— 槽位、同步与动作分流，
 *       客户端发、服务端做的那道岔口只在这里。</li>
 *   <li>{@link io.github.lounode.ae2pattern.common.menu.cell.CellTermMoves} —— 光标 / 背包 / 编码槽之间的搬运。</li>
 *   <li>{@link io.github.lounode.ae2pattern.common.menu.cell.EncodeCellEditor} —— 编码槽那张元件的分区与专有属性。</li>
 *   <li>{@link io.github.lounode.ae2pattern.common.menu.cell.CellBulkDomain} —— <b>压缩管理域</b>，一条链算一项。</li>
 *   <li>{@link io.github.lounode.ae2pattern.common.menu.cell.CellMarkDomain} 与
 *       {@link io.github.lounode.ae2pattern.common.menu.cell.CellQuickMark} —— <b>标记管理域</b>，按单个键判重。</li>
 *   <li>{@link io.github.lounode.ae2pattern.common.menu.cell.CellDriveScanner} 与
 *       {@link io.github.lounode.ae2pattern.common.menu.cell.CellUsageCache} —— 扫网络、读元件用量。</li>
 *   <li>{@link io.github.lounode.ae2pattern.common.menu.cell.CellHostSwap} —— 两个管理域共用的「取出 → 改副本 →
 *       放回 → 失败回填」，改一处两边都跟着改。</li>
 * </ul>
 *
 * <p>这一层的类互相之间大量使用包内访问器（{@code gridOrNull()}、{@code ownerOf()}、{@code afterCellMoved()}、
 * {@code notice()}）。<b>搬家时要么整组搬，要么把这些访问器改成 public</b>——单独挪一个出去会直接编不过。</p>
 */
package io.github.lounode.ae2pattern.common.menu.cell;
