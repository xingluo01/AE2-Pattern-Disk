package io.github.lounode.ae2pattern.common.pattern;

/**
 * Capacity tiers for pattern disks, mirroring AE2's storage-cell naming (1k/4k/16k/64k).
 *
 * <p>Each tier stores a fixed number of encoded patterns (4 per "k"). The upper bound is governed by the
 * network packet limit for ItemStack sync (~32 KB): a disk must serialize below that to be usable in
 * inventories and menus. The 64k tier (256 patterns, ~128 KB serialized) therefore exceeds the safe
 * single-packet limit and is intended only for static archival, not for network/menu interaction.</p>
 */
public enum PatternDiskTier {

    SIZE_1K(1, 4),
    SIZE_4K(4, 16),
    SIZE_16K(16, 64),
    SIZE_64K(64, 256),
    ;

    private final int sizeK;
    private final int capacity;

    PatternDiskTier(int sizeK, int capacity) {
        this.sizeK = sizeK;
        this.capacity = capacity;
    }

    public int sizeK() {
        return sizeK;
    }

    public int capacity() {
        return capacity;
    }

    /** Resource-path suffix used for item registration, e.g. {@code pattern_disk_1k}. */
    public String pathSuffix() {
        return sizeK + "k";
    }
}
