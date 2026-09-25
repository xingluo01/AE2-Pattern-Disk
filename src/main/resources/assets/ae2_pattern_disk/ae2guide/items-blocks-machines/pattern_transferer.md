---
navigation:
  parent: index.md
  title: ME Pattern Transferer
  icon: pattern_transferer
  position: 1030
item_ids:
- ae2_pattern_disk:pattern_transferer
categories:
- machines
---

# ME Pattern Transferer

<BlockImage id="pattern_transferer" scale="8" />

The ME Pattern Transferer moves encoded patterns both ways between AE2 blank patterns and pattern disks.

The input side takes encoded patterns or disks already holding patterns; the target slot takes the disk to write to. Taking a pattern out of a disk returns the blank pattern it freed to the connected ME network.

A disk accepts one pattern type at a time and refuses patterns whose primary output it already holds.

## Recipe

<RecipeFor id="ae2_pattern_disk:pattern_transferer" />
