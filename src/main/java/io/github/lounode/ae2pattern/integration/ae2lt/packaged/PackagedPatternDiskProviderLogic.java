package io.github.lounode.ae2pattern.integration.ae2lt.packaged;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.util.inv.AppEngInternalInventory;

import com.moakiee.ae2lt.packaged.logic.PackagedPatternProviderLogic;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.logic.PatternsFromDisks;
import io.github.lounode.ae2pattern.common.logic.ReadOnlyPatternInventory;
import io.github.lounode.ae2pattern.common.pattern.DiskSlotVersions;

/**
 * 封包样板磁盘供应器的逻辑：在闪电科技封包逻辑之上，把磁盘内容镜像进它自己的样板栏。
 *
 * <p>与过载版同一套做法。上游 1.2.2 起样板栏大小取自 {@code host.getPatternSlotCount()}，所以容量
 * 由方块实体覆写即可。</p>
 */
public class PackagedPatternDiskProviderLogic extends PackagedPatternProviderLogic implements PatternsFromDisks {

    private final Supplier<AppEngInternalInventory> diskInventorySupplier;

    private List<Object> lastDiskVersions;

    /** 真正的样板栏原件：重建时写这里，getPatternInv() 对外只交只读视图。 */
    private final InternalInventory patternInventory;
    private final InternalInventory readOnlyPatternInv;

    /** 重建样板栏期间为真：逐槽写入不再触发父类的全量 updatePatterns。 */
    private boolean rebuildingMirror;

    public PackagedPatternDiskProviderLogic(
            IManagedGridNode mainNode,
            PackagedPatternDiskProviderBlockEntity host,
            Supplier<AppEngInternalInventory> diskInventorySupplier) {
        super(mainNode, host);
        this.diskInventorySupplier = diskInventorySupplier;
        this.patternInventory = super.getPatternInv();
        this.readOnlyPatternInv = new ReadOnlyPatternInventory(this.patternInventory);
    }

    @Override
    public boolean refreshPatternsFromDisks(boolean force) {
        var diskInventory = diskInventorySupplier.get();
        if (diskInventory == null) {
            return false;
        }

        var versions = DiskSlotVersions.ofInventory(diskInventory);
        if (!force && DiskSlotVersions.sameAs(lastDiskVersions, versions)) {
            return false;
        }

        List<ItemStack> patterns = new ArrayList<>();
        for (int i = 0; i < diskInventory.size(); i++) {
            var diskStack = diskInventory.getStackInSlot(i);
            if (diskStack.isEmpty() || !(diskStack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            patterns.addAll(disk.contents(diskStack).patterns());
        }

        InternalInventory patternInv = patternInventory;
        rebuildingMirror = true;
        try {
            patternInv.clear();
            int limit = Math.min(patterns.size(), patternInv.size());
            for (int i = 0; i < limit; i++) {
                patternInv.setItemDirect(i, patterns.get(i).copy());
            }
        } finally {
            rebuildingMirror = false;
        }

        updatePatterns();
        saveChanges();
        lastDiskVersions = versions;
        return true;
    }

    @Override
    public void clearMirror() {
        InternalInventory patternInv = patternInventory;
        rebuildingMirror = true;
        try {
            for (int i = 0; i < patternInv.size(); i++) {
                patternInv.setItemDirect(i, ItemStack.EMPTY);
            }
        } finally {
            rebuildingMirror = false;
        }
        saveChanges();
    }

    /**
     * 重建期间逐槽写入不触发父类的 updatePatterns：那会按槽数全量重建一次，九千槽的样板栏会变成
     * 上千万次解码。重建结束后由 refreshPatternsFromDisks 调一次。
     */
    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (rebuildingMirror) {
            saveChanges();
            return;
        }
        super.onChangeInventory(inv, slot);
    }
    @Override
    public InternalInventory getPatternInv() {
        return readOnlyPatternInv != null ? readOnlyPatternInv : super.getPatternInv();
    }
}
