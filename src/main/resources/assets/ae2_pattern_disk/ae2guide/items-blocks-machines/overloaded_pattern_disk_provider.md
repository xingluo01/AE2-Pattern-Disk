---
navigation:
  parent: index.md
  title: Overloaded Pattern Disk Provider
  icon: overloaded_pattern_disk_provider
  position: 1040
item_ids:
- ae2_pattern_disk:overloaded_pattern_disk_provider
categories:
- devices
---

# Overloaded Pattern Disk Provider

<BlockImage id="overloaded_pattern_disk_provider" scale="8" />

An <ItemLink id="ae2lt:overloaded_pattern_provider" /> that takes its patterns from pattern disks instead of pattern slots. Nine disk slots, up to 9216 patterns, and the overloaded dispatch path of AE2 Lightning Tech unchanged.

Craft it by combining an Overloaded Pattern Provider with a 16k pattern disk.

## How patterns reach the network

Each disk keeps its own type lock. A disk filled with `ae2lt:overload_pattern` patterns is the one this device is built for; a disk locked to another type still contributes nothing to it, because the overloaded dispatch path only executes overloaded patterns. The disk's contents are mirrored into the provider's own pattern slots and rebuilt whenever a disk changes - the disks remain the only place the patterns are stored.

Taking a disk out removes its patterns from the network at once. Breaking the block returns the disks, not a second copy of the patterns.

## Toolbar

The left toolbar carries the AE2 Lightning Tech set instead of this mod's three provider toggles:

- **Blocking mode** - off, normal, or same-pattern.
- **Frequency binding** - the same binding screen as the original provider.
- **Auto return** - off, auto, or eject.
- **Mode** - normal or wireless dispatch.
- **Adaptive batch** - the same toggle as the original.
- **Input filter** - accept only materials the provider expects.
- **Cog** - advanced settings: input filter and machine parallelism.

## Differences from the original

- Pattern capacity follows the disks, not a fixed 36 slots, so the paging controls are gone. The interface shows nine disk slots instead.
- The advanced settings page covers input filtering and machine parallelism; the wireless strategy and speed toggles stay on the main screen's mode and frequency controls.
- Everything else - dispatch, wireless connections, returned items, memory card settings - comes from the original provider's logic.