package io.github.lounode.ae2pattern.core;

import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

/**
 * Custom slot semantics for the pattern transferer, using our mod-id prefix as required by AE2's
 * {@link SlotSemantics#register(String, boolean)} contract.
 */
public final class AEPatternSlotSemantics {

    private AEPatternSlotSemantics() {
    }

    /** Six input slots: accept encoded patterns or populated pattern disks. */
    public static final SlotSemantic TRANSFER_INPUT = SlotSemantics.register("ae2_pattern_disk:transfer_input", false);

    /** The target pattern disk slot that receives transferred patterns. */
    public static final SlotSemantic TRANSFER_APPLICATION = SlotSemantics.register(
            "ae2_pattern_disk:transfer_application", false);

    /** Temporary output slot for blank patterns before they are returned to the ME network. */
    public static final SlotSemantic TRANSFER_BLANK_OUTPUT = SlotSemantics.register(
            "ae2_pattern_disk:transfer_blank_output", false);

    /** Disk slots on the pattern disk provider. */
    public static final SlotSemantic PROVIDER_DISK = SlotSemantics.register(
            "ae2_pattern_disk:provider_disk", false);

    /**
     * Crafting grid slots of each assembler unit (each unit gets its own semantic so the ScreenStyle
     * JSON can lay out every unit's 3x3 grid at the shared EAE page position).
     */
    public static final SlotSemantic[] ASSEMBLER_GRID = new SlotSemantic[8];

    /**
     * Per-unit encoded-pattern display slot (mirrors what the page is currently assembling), one slot per
     * CraftUnit so each page can show its own sample pattern independently.
     */
    public static final SlotSemantic[] ASSEMBLER_PATTERN = new SlotSemantic[8];

    static {
        for (int i = 0; i < 8; i++) {
            ASSEMBLER_GRID[i] = SlotSemantics.register("ae2_pattern_disk:assembler_grid_" + i, false);
            ASSEMBLER_PATTERN[i] = SlotSemantics.register("ae2_pattern_disk:assembler_pattern_" + i, false);
        }
    }
}
