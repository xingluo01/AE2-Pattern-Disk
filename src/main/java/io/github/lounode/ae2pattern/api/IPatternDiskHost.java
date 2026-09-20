package io.github.lounode.ae2pattern.api;

import appeng.api.inventories.InternalInventory;

import net.minecraft.core.BlockPos;

/**
 * Implemented by machines that hold pattern disks in an inventory slot. Used by the pattern disk
 * encoding terminal to discover every disk host on the grid (pattern disk providers, batch molecular
 * assemblers, ...) instead of hard-coding concrete block entity classes.
 */
public interface IPatternDiskHost {

    /** @return The inventory whose slots may contain {@code PatternDiskItem} stacks. */
    InternalInventory getDiskInventory();

    /** @return The host's position, used as a stable identity for terminal-side fingerprinting. */
    BlockPos getBlockPos();

    /**
     * Extra identity mixed into the terminal fingerprint for hosts that share a position - several
     * cable-attached panels can sit on the same cable, and their disks must not be mistaken for one
     * another (a stale fingerprint means the terminal shows the wrong disks, or skips a refresh).
     *
     * @return A value unique among hosts at the same position; {@code 0} when the position is enough.
     */
    default int getIdentitySalt() {
        return 0;
    }

    /**
     * AE2 样板访问终端的「可见」口径：为 {@code false} 的宿主在「显示可见供应器」模式下不进表，与 AE2 那个终端
     * 共用同一个开关（取值为 {@code ShowPatternProviders}）。
     *
     * <p>默认可见：外部模组注册的宿主未必有这个概念，由它们自行覆盖；AE2 系宿主（都已实现
     * {@code PatternContainer}）会转发到 AE2 自己的开关。</p>
     */
    default boolean isVisibleInPatternAccessTerminal() {
        return true;
    }
}
