package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.level.Level;

/**
 * Host contract for the pattern disk encoding terminal part. Provides the encoding logic,
 * the world level and a save trigger — decoupled from AE2's {@code IPatternTerminalLogicHost}
 * whose {@code getLogic()} is typed to AE2's own {@code PatternEncodingLogic}.
 */
public interface IDiskEncodingLogicHost {

    DiskEncodingLogic getLogic();

    Level getLevel();

    void markForSave();
}
