package io.github.lounode.ae2pattern.integration.neoecoae;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.dancingsnow.neoecoae.api.integration.Integration;
import cn.dancingsnow.neoecoae.api.me.provider.ECOParallelCraftingProviders;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * Integration entry point for NEO ECO AE Extension ({@code neoecoae}).
 *
 * <p>NEO ECO's {@code IntegrationManager} discovers this class at startup through
 * {@code @Integration("ae2_pattern_disk")} and calls {@link #apply()}. Each feature is wired in its own step
 * and each step is wrapped on its own: this runs inside NEO ECO's constructor, so a throw from one step must
 * not take the other - or the game - down with it.</p>
 *
 * <h2>The parallel intake contract</h2>
 *
 * <p>NEO ECO's CPU hands a provider that answers its parallel contract a whole batch instead of one craft at
 * a time, and the batch assembler can honour that. It must not name the ECO type itself - NEO ECO is an
 * optional dependency - so it is registered here, keyed by its class, and the contract is reached through
 * {@link BatchAssemblerParallelIntake}. This replaces a mixin that added the interface to the machine
 * directly: that reached the same lookup, but only by touching the machine's class, which then depended on
 * when and whether the mixin was applied. A registration is the same lookup without the class surgery.</p>
 *
 * <h2>The encoding terminal's upload button</h2>
 *
 * <p>NEO ECO's pattern storage reports how an upload ended, which is what lets that button tell "a disk took
 * it" from "nothing happened". Without the report an answer could only be guessed, and a wrong guess hands a
 * blank pattern back for one that was merely moved. So the button stays off when the report is absent -
 * the same rule as before, now checked on its own rather than behind another feature's guard.</p>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>The disk side is not wired here. NEO ECO compiles against this mod's {@code PatternDiskApi} and
 * registers its own disk holders and terminal views, so the reflection this class used to carry - the bus
 * accessor, the auxiliary store it called back into, the terminal hook - has been deleted along with the
 * store the bus used to look for.</p>
 */
@Integration("ae2_pattern_disk")
public class NeoECOIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    /**
     * Called once NEO ECO has loaded and this mod is present.
     *
     * <p>Each feature is wired on its own and wrapped on its own, because this runs inside NEO ECO's mod
     * constructor: a throw from here is not a lost feature but a failed game start.</p>
     */
    public void apply() {
        guarded("the encoding terminal upload", NeoECOIntegration::wireEncodingTerminalUpload);
        guarded("the parallel intake registration", NeoECOIntegration::registerParallelIntake);
    }

    /** Runs one wiring step, keeping a failure inside it from reaching the mod loader. */
    private static void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException broken) {
            LOGGER.warn("[AE2-Pattern-Disk] {} could not be wired; that feature stays off", what, broken);
        }
    }

    /**
     * Hands NEO ECO the batch assembler's parallel intake.
     *
     * <p>Keyed by class rather than by instance: providers are looked up while a crafting job runs, so the
     * registration has to answer for machines that are created later.</p>
     */
    private static void registerParallelIntake() {
        ECOParallelCraftingProviders.register(BatchAssemblerBlockEntity.class,
                provider -> new BatchAssemblerParallelIntake((BatchAssemblerBlockEntity) provider));
        LOGGER.info("[AE2-Pattern-Disk] Batch assembler registered for NEO ECO parallel intake");
    }

    private static void wireEncodingTerminalUpload() {
        PatternDiskEncodingTermMenu.uploadHandler = NeoECOUploadHandler.create();
        LOGGER.info("[AE2-Pattern-Disk] Encoding terminal upload button wired");
    }

    /** Client-side initialisation: the terminal screen adds its upload button on its own. */
    public void applyClient() {
    }
}
