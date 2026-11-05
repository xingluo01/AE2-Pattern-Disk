package io.github.lounode.ae2pattern.common.logic;

import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;

/**
 * 供应器的挡位配置与它们的存档 / 内存卡读写：阻挡三档、发配倍增、产物抽取档。
 *
 * <p>这四项 AE2 的 {@code Settings} 枚举不可扩展，只能自带一段组件，否则粘贴时这三项会悄悄丢掉。
 * 配置是纯状态，宿主与其它协作类共享同一个实例：这里只管状态与它的读写，不碰网格、不碰父类能力
 * （{@code getConfigManager()} / {@code saveChanges()} 仍留在宿主入口里）。</p>
 */
final class ProviderSettings {

    /**
     * 发配倍增的合并额度。额度在 {@link BoostMergeLimit}（目前只有本机在用）：它量的是
     * 「这一批攒了多少份合成所需的输入」，单位是份（样板输入倍数），不是交接次数也不是 tick。
     */
    private BoostMergeLimit mergeLimit = new BoostMergeLimit(0);

    private ProviderBlockingMode blockingMode = ProviderBlockingMode.DEFAULT;
    private boolean boostEnabled;
    private int boostLimit;

    /** 产物抽取模式：不抽 / 每 20 tick / 带退避的快速档。 */
    private ProviderExtractionMode extractionMode = ProviderExtractionMode.OFF;

    ProviderBlockingMode blockingMode() {
        return blockingMode;
    }

    boolean boostEnabled() {
        return boostEnabled;
    }

    int boostLimit() {
        return boostLimit;
    }

    BoostMergeLimit mergeLimit() {
        return mergeLimit;
    }

    ProviderExtractionMode extractionMode() {
        return extractionMode;
    }

    /** @return 是否真的换了档（换档的副作用由宿主入口负责） */
    boolean setBlockingMode(@Nullable ProviderBlockingMode mode) {
        if (mode == null || mode == blockingMode) {
            return false;
        }
        blockingMode = mode;
        return true;
    }

    ProviderBlockingMode nextBlockingMode() {
        return blockingMode.next();
    }

    void setBoostEnabled(boolean enabled) {
        boostEnabled = enabled;
    }

    /** @return 是否真的改了上限（{@code BoostMergeLimit#withConfigured} 会自己夹到合法范围） */
    boolean setBoostLimit(int limit) {
        var next = mergeLimit.withConfigured(limit);
        if (next.configured() == boostLimit) {
            return false;
        }
        mergeLimit = next;
        boostLimit = next.configured();
        return true;
    }

    /** @return 是否真的换了档（换档后要重置抽取节拍，由宿主入口负责） */
    boolean setExtractionMode(@Nullable ProviderExtractionMode mode) {
        if (mode == null || mode == extractionMode) {
            return false;
        }
        extractionMode = mode;
        return true;
    }

    ProviderExtractionMode nextExtractionMode() {
        return extractionMode.next();
    }

    /** 写内存卡的自定义设置段：四个键，与存档同口径。 */
    void writeCustomTag(CompoundTag tag) {
        writeNbt(tag);
    }

    /** 读内存卡的自定义设置段：卡上没有这些键时回落到各档默认值。 */
    void readCustomTag(CompoundTag tag) {
        readNbt(tag);
    }

    /** 写进存档：本模组那四项设置，键是存档协议面，不能改名。 */
    void writeNbt(CompoundTag tag) {
        tag.putString(PatternDiskProviderLogic.NBT_BLOCKING_MODE, blockingMode.name());
        tag.putBoolean(PatternDiskProviderLogic.NBT_BOOST_ENABLED, boostEnabled);
        tag.putInt(PatternDiskProviderLogic.NBT_BOOST_LIMIT, boostLimit);
        tag.putString(PatternDiskProviderLogic.NBT_EXTRACTION_MODE, extractionMode.name());
    }

    /**
     * 从存档读回：档位写进存档却从不读回会让抽取档总是回落 OFF。
     *
     * <p>退避进度（冷却 / 间隔）是运行时状态，不落盘，交给宿主在读档后单独重置。</p>
     */
    void readNbt(CompoundTag tag) {
        blockingMode = parseBlockingMode(tag.getString(PatternDiskProviderLogic.NBT_BLOCKING_MODE));
        boostEnabled = tag.getBoolean(PatternDiskProviderLogic.NBT_BOOST_ENABLED);
        boostLimit = Math.max(0, Math.min(BoostMergeLimit.MAX_LIMIT,
                tag.getInt(PatternDiskProviderLogic.NBT_BOOST_LIMIT)));
        mergeLimit = new BoostMergeLimit(boostLimit);
        extractionMode = parseExtractionMode(tag.getString(PatternDiskProviderLogic.NBT_EXTRACTION_MODE));
    }

    private static ProviderBlockingMode parseBlockingMode(String name) {
        for (var mode : ProviderBlockingMode.values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return ProviderBlockingMode.DEFAULT;
    }

    private static ProviderExtractionMode parseExtractionMode(String name) {
        for (var mode : ProviderExtractionMode.values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return ProviderExtractionMode.OFF;
    }
}
