package io.github.lounode.ae2pattern.integration.jade.provider;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;

/**
 * Shows what the batch molecular assembler is doing, or why it is not doing it.
 *
 * <p>Which of those it is cannot be worked out on the client - the queue, the cell buffer and the grid all live
 * on the server - so the state travels with the block's server data and the tooltip only renders it.</p>
 */
public enum BatchAssemblerProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final String STATE = "state";
    private static final String QUEUED = "queued";
    private static final String WAITING = "waitingTicks";

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (!data.contains(STATE)) {
            return;
        }
        tooltip.add(Component.translatable(stateKey(data.getString(STATE))));
        if (data.contains(QUEUED) && data.getLong(QUEUED) > 0) {
            tooltip.add(Component.translatable("jade.ae2_pattern_disk.batch_assembler.queued",
                    data.getLong(QUEUED)));
        }
        if (data.contains(WAITING) && data.getInt(WAITING) > 0) {
            tooltip.add(Component.translatable("jade.ae2_pattern_disk.batch_assembler.waiting",
                    data.getInt(WAITING)));
        }
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof BatchAssemblerBlockEntity assembler)) {
            return;
        }
        data.putString(STATE, assembler.getWorkState().name());
        data.putLong(QUEUED, assembler.getQueuedJobCount());
        data.putInt(WAITING, assembler.getTicksUntilBatch());
    }

    @Override
    public ResourceLocation getUid() {
        return ResourceLocation.parse(AE2PatternDisk.MOD_ID + ":batch_assembler");
    }

    /** One key per state, derived from the state itself so the two cannot drift apart. */
    private static String stateKey(String state) {
        return "jade.ae2_pattern_disk.batch_assembler.state." + state.toLowerCase(Locale.ROOT);
    }
}
