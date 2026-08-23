package io.github.lounode.ae2pattern.common.pattern;

/**
 * Capacity tiers for pattern disks, mirroring AE2's storage-cell naming.
 *
 * <p>Each tier stores a fixed number of encoded patterns (4 per "k"). The upper bound is governed by the
 * network packet limit for ItemStack sync (~32 KB): larger tiers may exceed safe menu-sync sizes and
 * should be treated as advanced archival tiers until runtime packet limits are verified.</p>
 */
public enum PatternDiskTier {

    SIZE_1K(1, 4),
    SIZE_4K(4, 16),
    SIZE_16K(16, 64),
    SIZE_64K(64, 256),
    SIZE_256K(256, 1024),
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
