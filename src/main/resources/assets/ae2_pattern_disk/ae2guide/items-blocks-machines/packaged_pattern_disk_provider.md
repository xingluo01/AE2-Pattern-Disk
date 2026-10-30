---
navigation:
  parent: index.md
  title: Packaged Pattern Disk Provider
  icon: packaged_pattern_disk_provider
  position: 1050
item_ids:
- ae2_pattern_disk:packaged_pattern_disk_provider
- ae2_pattern_disk:wireless_packaged_pattern_disk_provider
categories:
- devices
---

# Packaged Pattern Disk Provider

<Row gap="20">
  <BlockImage id="packaged_pattern_disk_provider" scale="8" />
  <BlockImage id="wireless_packaged_pattern_disk_provider" scale="8" />
</Row>

An <ItemLink id="ae2ltpp:packaged_pattern_provider" /> that takes its patterns from pattern disks. The same two forms exist: wired and wireless. Both hold nine disk slots and keep the packaged provider's dispatch into other mods' crafting systems.

Craft either by combining the matching Packaged Pattern Provider with a 16k pattern disk.

## The adapter card

The packaged provider chooses its target machine family through an adapter card. This device keeps that slot: the card sits to the right of the returned-items inventory. Without an adapter card it dispatches nothing, exactly like the original.

## Patterns

Patterns are ordinary AE2 processing patterns; the packaged logic turns them into the target machine's workflow. Any disk locked to `ae2:processing_pattern` works. Overloaded patterns do not, because the packaged host is not an overloaded provider.

Disk contents are mirrored into the provider's pattern slots and rebuilt when a disk changes. Breaking the block returns the disks, not copies of the patterns.

## Toolbar

- **Frequency binding** - the same binding screen as the original.
- **Auto return** - off or auto.
- **Mode** - normal or wireless, when the form allows switching.

The wireless form differs only in its icon and title: wireless dispatch, connection limits and frequency binding all come from the original wireless provider's logic.