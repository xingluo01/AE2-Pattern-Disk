---
navigation:
  parent: index.md
  title: Self-Assembling Pattern Disk Provider
  icon: meteorite_pattern_provider
  position: 1025
item_ids:
- ae2_pattern_disk:meteorite_pattern_provider
- ae2_pattern_disk:cable_meteorite_pattern_provider
categories:
- devices
---

# Self-Assembling Pattern Disk Provider

<Row gap="20">
  <BlockImage id="meteorite_pattern_provider" scale="8" />
  <ItemImage id="cable_meteorite_pattern_provider" scale="4" />
</Row>

A [pattern disk provider](pattern_disk_provider.md) that can also run its own patterns: on top of offering
what is on its disks, any pattern a molecular assembler could run is completed inside this device and its
product is sent straight back to the ME network.

Both forms exist only while **AE2 Crystal Science** is installed; without it neither is in the game.

## What it does with a pushed pattern

- **A pattern a molecular assembler could run**: the materials are consumed, the product is produced on the
  spot and returned to the network. Nothing is pushed to a neighbouring machine.
- **Anything else** (processing patterns, and patterns no assembler could run): pushed like an ordinary
  pattern provider.
- **Crafting lock first**: while the provider is locked by redstone or by "until result", nothing is
  self-assembled - the lock wins.

## Energy and speed

One self-assembled craft costs **50 AE** from the network, doubling per speed card.

- **Speed cards**: up to 4. Each doubles the energy per craft and raises the per-round limit
  (8 → 16 → 32 → 64 → 128). A round is one return cycle: without an overload card it comes around every
  5 ticks.
- **<ItemLink id="ae2cs:overload_card" fallback="Overload Card" />**: up to 4. Any number of them makes
  speed cards stop helping, adds **128** to the per-round limit each, and shortens the return cycle - one
  card returns every 4 ticks, two or more every tick.

Both kinds of card share the same four upgrade slots.

## Forms, direction and uploads

Disks, direction handling and the upload button are all the same as the
[ME Pattern Disk Provider](pattern_disk_provider.md): a wrench walks the push direction, the block and panel
forms convert into each other with a shapeless craft, and uploading a pattern lands on one of the disks.

## Recipes

<RecipesFor id="ae2_pattern_disk:meteorite_pattern_provider" />

<RecipeFor id="ae2_pattern_disk:cable_meteorite_pattern_provider" />
