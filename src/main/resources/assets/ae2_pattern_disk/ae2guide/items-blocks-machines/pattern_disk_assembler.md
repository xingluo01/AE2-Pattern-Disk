---
navigation:
  parent: index.md
  title: Efficient Molecular Assembler
  icon: pattern_disk_assembler
  position: 1040
item_ids:
- ae2_pattern_disk:pattern_disk_assembler
categories:
- machines
---

# Efficient Molecular Assembler

<BlockImage id="pattern_disk_assembler" scale="8" />

The Efficient Molecular Assembler is a molecular assembler with eight independent crafting lanes. It takes crafting jobs pushed by an AE2 pattern provider and, apart from one manual pattern slot per page, does not store or manage patterns in bulk; leave bulk pattern storage to the pattern disks.

Each job goes to the first idle lane. Every lane has its own 3×3 crafting grid, its own output slot and its own progress. The interface has eight pages, one per lane, so each lane can be watched on its own.

## Acceleration cards

Up to five <ItemLink id="ae2:speed_card" />, with the same multipliers as the <ItemLink id="ae2:molecular_assembler" />.

## Output

For a job pushed by a pattern provider, products go first to the adjacent return node, the provider and its return inventory ("Returned Items" in its interface); whatever it cannot take goes into the ME network. Container remainders from crafting come back the same way; when neither the return node nor the ME network can take them, the items stay in the machine for another attempt.

## Manual patterns

Each lane's page has a pattern slot. Put an encoded crafting pattern in by hand and that page turns self-driving: it keeps drawing the materials it needs from the ME network and crafting continuously, and its products and container remainders go straight into the ME network rather than to an adjacent container. The page keeps working as long as the pattern stays in the slot and the network has materials.

Materials are drawn as a whole set: when the network cannot cover every ingredient of the recipe, the page draws nothing at all, so it never leaves half a set in its grid and never ends up with materials it cannot assemble.

A lane holding a pattern will not take jobs pushed by a pattern provider, so the machine only accepts work while at least one lane is completely idle. Take the pattern out and the lane returns whatever is left on its grid, then goes back to being idle.

## Recipe

<RecipeFor id="ae2_pattern_disk:pattern_disk_assembler" />
