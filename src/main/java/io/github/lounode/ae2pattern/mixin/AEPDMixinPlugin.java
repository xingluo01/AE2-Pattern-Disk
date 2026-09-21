package io.github.lounode.ae2pattern.mixin;

import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Picks the mixins the current pack can actually carry.
 *
 * <p>{@link BatchAssemblerEcoParallelMixin} names NEO ECO types, so it may only be applied - and with that
 * only be loaded - while ECO is present and still exposes the contract it implements. Applying it without
 * ECO would break the machine's class load, which is exactly the hard dependency this mod must not have:
 * {@code neoecoae} is declared optional in {@code neoforge.mods.toml}, and this plugin keeps it optional at
 * runtime as well.</p>
 *
 * <p>Every failure path answers "do not apply": a missing or older ECO simply keeps AE2's one-craft push
 * path, and nothing about the machine changes.</p>
 *
 * <p>The one rule this file follows is <b>never define a class</b>. It runs in mixin config preparation, before
 * mod classes may be defined, so naming any of them here - even through {@code KeyCounter[].class} in a
 * reflective lookup - would load that class ahead of every other mod's mixin for it and abort startup with
 * {@code MixinTargetAlreadyLoadedException}.</p>
 */
public final class AEPDMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.mixin");

    private static final String ECO_MOD_ID = "neoecoae";
    private static final String ECO_PARALLEL_PROVIDER =
            "cn.dancingsnow.neoecoae.api.me.provider.ECOParallelCraftingProvider";
    private static final String ECO_PARALLEL_MIXIN =
            "io.github.lounode.ae2pattern.mixin.BatchAssemblerEcoParallelMixin";

    /** Cached verdict: true/false once it is certain, null while the pack metadata is still unreadable. */
    private Boolean ecoReady;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!ECO_PARALLEL_MIXIN.equals(mixinClassName)) {
            return true;
        }
        if (ecoReady == null) {
            ecoReady = decideEco();
        }
        return Boolean.TRUE.equals(ecoReady);
    }

    /**
     * Decides whether the parallel intake mixin may be applied.
     *
     * @return {@code TRUE} or {@code FALSE} once that is certain, {@code null} while the pack metadata cannot be
     *         read yet - a maybe must never be remembered as a no, or one early question would switch the
     *         integration off for the rest of the session
     */
    private static @Nullable Boolean decideEco() {
        Boolean declared = modDeclared();
        if (declared == null) {
            LOGGER.warn("NEO ECO presence is not readable yet, the parallel intake mixin is not offered for this class");
            return null;
        }
        if (!declared) {
            LOGGER.debug("NEO ECO is not loaded, the batch assembler keeps AE2's one-craft push path");
            return Boolean.FALSE;
        }
        if (!contractPresent()) {
            LOGGER.info("NEO ECO is present without the parallel intake contract, keeping the one-craft push path");
            return Boolean.FALSE;
        }
        LOGGER.info("NEO ECO parallel intake enabled for the batch assembler");
        return Boolean.TRUE;
    }

    /**
     * Whether the pack loads NEO ECO at all. Mixins are applied while mod loading is still under way, so the
     * frozen {@link ModList} can be empty at this point: the metadata list read during discovery is the source
     * that is already populated.
     *
     * @return {@code null} when neither source can answer yet
     */
    private static @Nullable Boolean modDeclared() {
        try {
            var loading = LoadingModList.get();
            if (loading != null) {
                return loading.getModFileById(ECO_MOD_ID) != null;
            }
        } catch (Throwable unreadable) {
            // Fall through to the frozen list, which is only readable once mod loading is done.
        }
        try {
            return ModList.get().isLoaded(ECO_MOD_ID);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /**
     * Whether NEO ECO's contract class file is reachable at all.
     *
     * <p>Only its presence is checked, by asking the class loaders for the class file as a resource - which
     * defines nothing. Resolving the type or its method signature is deliberately not done here: mentioning
     * {@code KeyCounter[]} would define AE2's {@code KeyCounter} while other mods still have mixins pending for
     * it, and resolving ECO's interface would make it impossible for anyone else to mix into that later.</p>
     */
    private static boolean contractPresent() {
        var resource = ECO_PARALLEL_PROVIDER.replace('.', '/') + ".class";
        var own = AEPDMixinPlugin.class.getClassLoader();
        if (own != null && own.getResource(resource) != null) {
            return true;
        }
        // Some loaders do not serve other mods' resources from their own level; the context loader does.
        var context = Thread.currentThread().getContextClassLoader();
        return context != null && context.getResource(resource) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
