---
navigation:
  parent: index.md
  title: Efficient Molecular Assembler
  position: 1040
item_ids:
- ae2_pattern_disk:pattern_disk_assembler
categories:
- machines
---

# Efficient Molecular Assembler

The Efficient Molecular Assembler is a molecular assembler with eight independent crafting lanes. It takes crafting jobs pushed by an AE2 pattern provider; apart from one manual pattern slot per page it does not store or manage patterns in bulk — leave that to the pattern disks.

Each job goes to the first idle lane. Every lane has its own 3×3 crafting grid, its own output slot and its own progress. The interface has eight tabs, one per lane, so you can watch each lane at work.

## Acceleration cards

The machine takes up to five AE2 acceleration cards, for speed multipliers of 1.0×, 1.3×, 1.7×, 2.0×, 2.5× and 5.0×. The more cards, the less time each lane takes over a job.

## Output

Finished items go first to the configured container beside the machine. Whatever will not fit goes into the ME network. Containers left over from crafting come back the same way; if the other side is full they queue up where they are.

## Manual patterns

Each lane's page has a pattern slot. Put an encoded crafting pattern in by hand and that page turns self-driving: it keeps pulling the materials it needs from the ME network, crafting continuously, and sending products and leftover containers to the neighbouring container or the ME network. It keeps going as long as the pattern stays in the slot and the network has materials. Materials are drawn as a whole set: when the network cannot cover every ingredient of the recipe, the page pulls nothing at all (no half-set is taken out of the network), so it never ends up holding materials it cannot assemble.

A lane holding a pattern will not take jobs pushed by a pattern provider, so the machine only accepts work while at least one lane is completely idle. Take the pattern out and the lane returns whatever is left on its grid, then goes back to being an idle lane.

## Pairing with a pattern provider

Put the assembler next to an ME Pattern Disk Provider or any other AE2 pattern provider. The provider pushes patterns and materials, the assembler does the crafting, and the finished goods are sent back.

## Recipe

<RecipeFor id="ae2_pattern_disk:pattern_disk_assembler" />
