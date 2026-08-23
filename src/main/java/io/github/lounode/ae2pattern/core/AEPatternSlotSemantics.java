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
}
