# AE2 Pattern Disk

An addon for [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) that adds high-capacity pattern disks, a disk-backed pattern provider (block and cable-panel forms), a pattern transferer, an efficient parallel molecular assembler, a batch assembler and a pattern disk encoding terminal.

- **Loader / MC**: NeoForge 1.21.1
- **NeoForge**: 21.1.241
- **AE2**: 19.2.17
- **Java**: 21

## Features

### Pattern Disks
Store encoded AE2 patterns in a single disk. A disk is untyped while empty; the first inserted pattern determines its pattern type. A disk accepts one pattern type at a time.

| Disk | Pattern capacity |
| --- | ---: |
| 1k | 4 |
| 4k | 16 |
| 16k | 64 |
| 64k | 256 |
| 256k | 1024 |

A disk can also carry a **mark**, recording which machine or recipe type it belongs to. Marks show up in the disk list of the encoding terminal (hovering a disk adds a `Mark: ...` line, plus the raw mark ID with F3+H), never rename the disk itself, and are what that list filters on by default. See [ME Pattern Disk Encoding Terminal](#me-pattern-disk-encoding-terminal).

### ME Pattern Disk Provider
A pattern provider backed by physical pattern disks. Insert disks into its slots and it exposes their encoded patterns to the ME autocrafting system. It is a task source (it does not craft itself) and accepts returned products through its return inventory.

It comes in two forms sharing one implementation: the in-world **block**, and a **panel part**
(`cable_pattern_disk_provider`) that attaches to a cable like AE2's own cable pattern provider. The panel
form keeps the same nine disk slots and the same return inventory, just in a thinner footprint.

With ExtendedAE Plus installed, its "upload pattern to a provider" button works here too: the provider has no pattern slots of its own, so the uploaded pattern lands in a free space on one of the disks it holds.

### ME Pattern Transferer
Moves encoded patterns between AE2 blank patterns and pattern disks. Input slots accept encoded patterns or populated disks; destination slots hold the target disks. Blank patterns produced by extraction are returned to the connected ME network. Supports speed cards.

### ME Pattern Disk Encoding Terminal

A panel that mounts on an ME cable and ties pattern encoding to pattern disks. It scans the whole ME network for pattern disks; pick one and you can write the pattern you just encoded onto it, or review and tidy the patterns it already holds.

**Encoding modes.** Crafting, processing, smithing and stonecutting views, cycled with the mode button; switching swaps the recipe grid, the output area and the tools on offer. Processing mode adds secondary-output rotation, multiply/divide (click ×2, Shift ×3, Ctrl ×5, Alt ×10 — divide does nothing unless inputs and outputs both divide evenly) and same-item merging. Stonecutting shows twelve candidates at a time over three rows, with a scrollbar when there are more.

**Blank patterns** come from the ME network: the slot is a read-only mirror of how many the network holds, and the terminal says so when there are none, so there is no need to place a blank pattern by hand.

**Disk list.** With nothing in the search bar every disk is listed; while searching, the list is filtered by what you typed — by name (unmarked disks take part like any other), or by mark when prefixed with `#` (unmarked disks have no mark to match). The toggle next to the search bar keeps unmarked disks in regardless of the search. The search bar matches a disk's name, or — prefixed with `#` — its mark. Crafting, smithing and stonecutting mode marks fold onto their category name, so a hand-encoded disk and one written from an imported recipe of the same kind answer the same search; processing has no single category (each machine has its own) and keeps the name "Processing pattern".

**Mouse controls.** Left click writes the currently encoded pattern to the disk; right click with a work block picked up on the cursor overwrites the disk's mark with that work block's recipe type (holding it in your hand does not count); Shift + right click writes the search bar's text as the mark, and an empty search bar clears it; middle click renames the disk after the machine its mark stands for.

**Filter slots.** In processing mode the input and output slots are filters rather than real slots. Right
click with a stack marks that stack and its count into the slot, replacing whatever the slot held rather
than adding to it; left click clears the slot; middle click on a filled slot opens the same amount dialog
AE2's pattern encoding terminal uses. Empty-hand right click keeps AE2's behaviour of lowering the count by
one, and Shift click, dragging and double clicking keep AE2's own handling (add/subtract, and emptying for
drainable containers). Right click marks the held stack, except for containers that can be emptied: a
bucket or bottle right-clicked onto a filter sets the filter to its contents — water, lava and so on — at
the container's full content amount, instead of the container itself, as in AE2; to mark the container as
an item instead, drag it over the slot or import a recipe with `+`. Clearing a
filter and then pressing Encode drops a pattern that was never written back to a blank pattern rather than
losing it.

**Write feedback.** Every write reports back in chat: which disk the pattern went to, or why it was refused — the disk is full, locked to another pattern type, already holds a recipe with the same output, the pattern's type cannot be resolved, or the target is no longer listed. When the search bar narrows the list to exactly one disk, pressing Encode writes the pattern straight to it rather than leaving it in the encoded slot.

**NEO ECO integration.** With NEO ECO AE Extension installed, an upload button appears that sends the encoded pattern to its computation cluster and returns a blank pattern in the order network → inventory → encoded slot.

**ExtendedAE Plus integration.** With ExtendedAE Plus installed, its "upload pattern to a provider" button is placed
directly below the NEO ECO button with no gap when NEO ECO is installed; without NEO ECO it falls back to ExtendedAE
Plus' own spot beside the encode button. The button reads this terminal's encoded slot, and a successful upload clears
that slot without returning a blank pattern, exactly as in AE2's own terminal. ExtendedAE Plus' automatic uploads
(Shift + Encode, and pushing straight to an assembler matrix after encoding) hang off AE2's terminal classes, so they
do not apply here — use the button. The slot and the terminal are handed over through a subclass that is only loaded
when EAE+ is present, so an install without it loads neither the interfaces nor the button.

### Efficient Molecular Assembler
A parallel molecular assembler with **eight independent execution threads**. It accepts crafting jobs pushed by AE2 pattern providers and runs them concurrently. Each thread owns a 3×3 molecular assembler grid, an output slot, and independent progress. Like AE2's own molecular assembler it takes **no channel**; it still draws power from the grid.

The GUI exposes one page per thread (mirroring ExtendedAE's (EAE) extension molecular assembler) with vertical progress only for the selected page. Accepts up to **five AE2 Speed Cards** with multipliers `1.0x / 1.3x / 1.7x / 2.0x / 2.5x / 5.0x`.

Each page also has an optional pattern slot. Inserting an encoded crafting pattern there turns that page into a self-executing unit: it pulls its own inputs from the ME network, crafts continuously while materials last, and pushes products plus container remainders to adjacent inventories or back into the network. A page running a manual pattern does not accept provider-pushed jobs, so the machine only takes pushed jobs while at least one page is idle.

### Batch Assembler

Buffers the jobs pushed by AE2 crafting CPUs inside nine private storage cell slots and executes them once the input material has actually stopped arriving. It takes **one channel**, because it exposes its patterns to autocrafting, and draws power from the grid.

#### Batch Window

The window is measured in game time since the last accepted push (two modes: standard 40 ticks, fast 10 ticks), so material that keeps coming simply keeps the batch waiting; the buffer adds no limit of its own beyond the capacity of the inserted cells (plan analysis keeps its own separate 1024-entry cache), and a started batch runs to the end of the queue without a per-tick ceiling. Supports crafting-table, smithing-table and stonecutting recipes.

A batch that comes out small - fewer than eight jobs - means the window gathered next to nothing, so the machine starts the next one after a single quiet tick instead of serving another full window. Every 32 such runs it serves the full window once more, which is how a supply that has turned into a steady stream gets noticed rather than run push by push forever; the same reset happens after eight windows of silence, so a small run cannot follow orders around forever. Runs that do gather a batch, and a machine that has not run yet, always serve the full window - that is what keeps a bulk order accumulating the way it always did. The window state is runtime only, like the job queue: after a reload the machine starts on the full window again.

#### Output Queue

Produced outputs (including container remainders) enter a smooth-return queue and reach the network over ~20 ticks at ~5% of the accumulated total per tick; when the network cannot take them they stay queued and are retried, and the inputs are not rolled back for that reason - inputs roll back only when the cells cannot hold a push, when material is missing, or when the grid is out of power. Keys the network is waiting for right now - the crafting CPU books them, or a queued job consumes them, which is what a recycled container does - skip that trickle and go back at once: at least 512 items per tick (all of it when that is less), and never less than the smooth rate, so the ceiling for a large batch stays where it was while a catalyst-sized amount arrives the same tick.

#### Cell Slots

Storage cells inserted here are private to the machine (never exposed to the ME network) and are locked while work is buffered; breaking the block or pressing cancel returns the buffer to the network. Leftovers do not stay locked up either: once the machine has been idle - nothing queued and nothing left to return - for ten ticks, whatever the cells still hold goes back to the network, and what the network refuses stays in the cells until the machine has something to do again.

Queued jobs live in memory only. After a reload the machine holds none of them, and the material they brought in is returned to the network by the idle flush above; a crafting CPU job that was waiting on that work has to be cancelled by hand.

#### Speed Cards

Up to **four AE2 Speed Cards** add worker threads (2 / 4 / 8 / 16): those threads analyse newly queued patterns in parallel, while every cell and network access stays on the server thread because AE2 storage is not thread-safe. Total output always matches the ordered amount.

## Blocks & Items

| ID | Display Name | Type |
| --- | --- | --- |
| `ae2_pattern_disk:pattern_disk_1k` | 1k Pattern Disk | Item |
| `ae2_pattern_disk:pattern_disk_4k` | 4k Pattern Disk | Item |
| `ae2_pattern_disk:pattern_disk_16k` | 16k Pattern Disk | Item |
| `ae2_pattern_disk:pattern_disk_64k` | 64k Pattern Disk | Item |
| `ae2_pattern_disk:pattern_disk_256k` | 256k Pattern Disk | Item |
| `ae2_pattern_disk:pattern_disk_encoding_terminal` | ME Pattern Disk Encoding Terminal | Item (part) |
| `ae2_pattern_disk:pattern_transferer` | ME Pattern Transferer | Block |
| `ae2_pattern_disk:pattern_disk_provider` | ME Pattern Disk Provider | Block |
| `ae2_pattern_disk:cable_pattern_disk_provider` | ME Pattern Disk Provider | Item (part) |
| `ae2_pattern_disk:pattern_disk_assembler` | Efficient Molecular Assembler | Block |
| `ae2_pattern_disk:batch_molecular_assembler` | Batch Assembler | Block |

All items are available in the dedicated creative tab **AE2 Pattern Disk**.

## Recipes

- **Pattern Disk 1k**
  - Shapeless: `ae2:blank_pattern` + `ae2:item_cell_housing`
  - Shaped (AE2-style, center = blank pattern):
    ```text
    A B A
    B C B
    D E D
    ```
    `A=ae2:quartz_glass` `B=minecraft:redstone` `C=ae2:blank_pattern` `D=minecraft:iron_ingot` `E=minecraft:copper_ingot`
- **Pattern Disk 4k / 16k / 64k / 256k**
  - Shaped:
    ```text
    A B A
    C D C
    A C A
    ```
    `B=ae2:calculation_processor` `C=lower-tier disk` `D=ae2:quartz_glass`
    `A=minecraft:redstone` (4k) / `minecraft:glowstone_dust` (16k, 64k) / `ae2:sky_dust` (256k)
- **ME Pattern Disk Provider**
  - Shapeless: `ae2:pattern_provider` + `ae2:capacity_card`
  - Panel form (`ae2_pattern_disk:cable_pattern_disk_provider`): shapeless 1:1 conversion with the block
    form in both directions, no extra material
- **Efficient Molecular Assembler**
  - Shaped:
    ```text
    A A A
    A B A
    A A A
    ```
    `A=ae2:molecular_assembler` `B=ae2:capacity_card`
- **ME Pattern Transferer**
  - Shaped:
    ```text
    A B A
    D C D
    A E A
    ```
    `A=ae2:quartz_glass` `B=ae2_pattern_disk:pattern_disk_1k` `C=ae2:item_cell_housing` `D=ae2:logic_processor` `E=ae2:engineering_processor`
- **Batch Assembler**
  - Shaped:
    ```text
    A B A
    D C D
    A B A
    ```
    `A=ae2:quartz_glass` `B=ae2:calculation_processor` `C=ae2_pattern_disk:pattern_disk_assembler` `D=ae2_pattern_disk:pattern_disk_64k`
- **Pattern Disk Encoding Terminal**
  - Shapeless: `ae2:pattern_encoding_terminal` + `ae2_pattern_disk:pattern_disk_1k`

## Guide

The mod ships a GuideME guide (in `assets/ae2_pattern_disk/ae2guide/`) covering the pattern disks (all five tiers on one page), the pattern disk provider (block and panel forms), the pattern transferer, the efficient molecular assembler, the batch assembler and the pattern disk encoding terminal. Of the five machine GUIs, only the encoding terminal declares a `helpTopic` in its screen JSON, so it is the only one that links to its guide page.

## Dependencies

- `neoforge` (required)
- `minecraft` (required)
- `ae2` (required, `[19.0.0,)`)
- `guideme` (provided at build; required for the guide pages)

## Building

Requires **JDK 21** and a Gradle 9 wrapper.

```bash
./gradlew build
```

The resulting jar is written to `build/libs/`.

### Verifying the recipe-viewer integration (JEI / EMI)

The recipe-transfer integration — the "+" button on a recipe page, and the disk marks it feeds — has two
optional implementations, so the dev runtime loads one viewer at a time. **JEI is the default**:

```bash
./gradlew runClient --no-configuration-cache      # JEI loaded (run_client.bat does the same)
./gradlew runClient -Pemi --no-configuration-cache # EMI loaded instead
```

EMI stays a compile-time dependency either way (`clientCompileOnly(libs.emi)`); only the runtime viewer
switches, so the EMI path keeps compiling and shipping unchanged.

JEI is declared as an optional dependency with range `[19.56.0,)`: the version both this mod and ExtendedAE
Plus compile against. The transfer-listener hook the disk-mark lookup reads its recipe category from has
been there since 19.52.0, but the floor stays at the compile baseline so that two mods in one pack cannot
disagree. An older JEI next to this mod is refused at load instead of quietly losing marks.

Manual check list with JEI loaded and EMI absent:

1. Open the ME Pattern Disk Encoding Terminal, open a recipe page in JEI and press "+". The pattern must
   be encoded as before.
2. Right-click a disk in the disk list: its mark becomes that recipe's category, and hovering the disk
   must show a readable category name (for example "Smoking") instead of the raw id.
3. Middle-click a disk: it is renamed after the machine of its mark (for example "Smoker").
4. Type `#` plus that name into the disk search bar: the disk must stay listed.
5. Shift + right-click a disk and the search bar text still writes/clears marks by hand, with no viewer
   involved.
6. With JEI absent the mod must still load and encode; the seven vanilla work blocks are still identified,
   and a right click with no work block on the cursor and no imported recipe writes no mark (it says so in chat).

With `-Pemi` the same list applies with EMI loaded and JEI absent; the names in steps 2 and 3 then come
from EMI's recipe categories instead.

### Development dependency: Neo ECO AE Extension

The source compiles against a locally built **Neo ECO AE Extension** jar, referenced from `libs/`.
That jar is not tracked in git. To produce it, check out the fork carrying the integration hooks
(branch `feature/pattern-disk-v21.1.2` of [xingluo01/NeoECOAEExtension](https://github.com/xingluo01/NeoECOAEExtension), upstream PR
[DancingSnow0517/NeoECOAEExtension#100](https://github.com/DancingSnow0517/NeoECOAEExtension/pull/100)),
build it, then copy the result in:

```bash
cd ../NeoECOAEExtension
./gradlew build -x test
cp build/libs/neoecoae-21.2.0-beta3.jar ../AE2-Pattern-Disk/libs/
```

The FD Smart Pattern Bus integration (upload-to-ECO button, disk-aware insertion, pattern access
terminal view, encoding-terminal disk list) needs the hooks added by that PR. Against a stock NEO ECO
build the hooks are absent, the integration logs a warning and the rest of the mod behaves normally.

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**.
See the [LICENSE](LICENSE) file for the full license text. Source: [github.com/xingluo01/AE2-Pattern-Disk](https://github.com/xingluo01/AE2-Pattern-Disk).

Assets marked ARR are the author's own works and are **not** covered by this LGPL-3.0 grant — currently
the batch assembler block textures and its glow-shell model (see the batch assembler entry under Upstream
Attribution below).

### Upstream Attribution

This mod is an addon for **[Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)** (AE2), which is also licensed under LGPL-3.0.

**The following assets are sourced from AE2 (LGPL-3.0):**

| File | AE2 Source |
|------|-----------|
| `assets/ae2_pattern_disk/textures/part/pattern_disk_encoding_terminal_bright.png` | `assets/ae2/textures/part/pattern_encoding_terminal_bright.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_encoding_terminal_medium.png` | `assets/ae2/textures/part/pattern_encoding_terminal_medium.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_encoding_terminal_dark.png` | `assets/ae2/textures/part/pattern_encoding_terminal_dark.png` |
| `assets/ae2_pattern_disk/textures/part/monitor_front.png` | `assets/ae2/textures/part/monitor_front.png` |
| `assets/ae2_pattern_disk/textures/part/monitor_sides.png` | `assets/ae2/textures/part/monitor_sides.png` |
| `assets/ae2_pattern_disk/textures/part/monitor_back.png` | `assets/ae2/textures/part/monitor_back.png` |
| `assets/ae2_pattern_disk/textures/part/monitor_colored.png` | `assets/ae2/textures/part/monitor_colored.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_provider.png` | `assets/ae2/textures/part/pattern_provider.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_provider_back.png` | `assets/ae2/textures/part/pattern_provider_back.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_provider_sides.png` | `assets/ae2/textures/part/pattern_provider_sides.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_provider_sides_status.png` | `assets/ae2/textures/part/monitor_sides_status.png` |

**The following block textures are copied from AE2 or locally reworked from AE2 textures (LGPL-3.0):**

| File | AE2 Source | Relation |
|------|-----------|----------|
| `assets/ae2_pattern_disk/textures/block/molecular_assembler.png` | `assets/ae2/textures/block/molecular_assembler.png` | locally edited derivative, 2026-09 (~44% of opaque pixels differ) |
| `assets/ae2_pattern_disk/textures/block/pattern_provider.png` | `assets/ae2/textures/block/pattern_provider.png` | locally edited derivative, 2026-09 (~39% differ) |
| `assets/ae2_pattern_disk/textures/block/pattern_provider_alternate.png` | `assets/ae2/textures/block/pattern_provider_alternate.png` | locally edited derivative, 2026-09 (~39% differ) |
| `assets/ae2_pattern_disk/textures/block/pattern_provider_alternate_arrow.png` | `assets/ae2/textures/block/pattern_provider_alternate_arrow.png` | locally edited derivative, 2026-09 (~39% differ) |
| `assets/ae2_pattern_disk/textures/block/pattern_provider_alternate_front.png` | `assets/ae2/textures/block/pattern_provider_alternate_front.png` | locally edited derivative, 2026-09 (~33% differ) |
| `assets/ae2_pattern_disk/textures/block/pattern_transferer_front.png` | `assets/ae2/textures/block/io_port_front_off.png` | locally edited derivative, 2026-09 (~22% differ) |
| `assets/ae2_pattern_disk/textures/block/pattern_transferer_top.png` | `assets/ae2/textures/block/io_port_top_off.png` | pixel-identical (PNG bytes differ) |
| `assets/ae2_pattern_disk/textures/block/pattern_transferer_side.png` | `assets/ae2/textures/block/generics/side.png` | byte-identical copy |
| `assets/ae2_pattern_disk/textures/block/pattern_transferer_back.png` | `assets/ae2/textures/block/generics/back.png` | byte-identical copy |
| `assets/ae2_pattern_disk/textures/block/pattern_transferer_bottom.png` | `assets/ae2/textures/block/generics/bottom.png` | byte-identical copy |
| `assets/ae2_pattern_disk/textures/block/pattern_transferer_front_on.png` | `assets/ae2/textures/block/io_port_front.png` | locally edited derivative, 2026-09 (~28% differ) |
| `assets/ae2_pattern_disk/textures/block/pattern_transferer_top_on.png` | `assets/ae2/textures/block/io_port_top.png` | locally edited derivative, 2026-09 (~3% differ) |

These textures are redistributed under the terms of LGPL-3.0. They began as copies of their AE2
counterparts and were partially reworked in 2026-09; the relation column records a pixel comparison
against AE2 19.2.17, which is why the reworked rows remain listed even though they are no longer
byte-identical.

**The batch assembler's block textures are not AE2-derived.** Its three grid textures and the
glow-shell geometry of its lit model are the mod author's own work - neither AE2's nor AdvancedAE's -
and the author keeps all rights to them (**ARR**, all rights reserved), so they are not covered by the
LGPL-3.0 grant the AE2-derived assets above carry. They ship with the mod because the same author made
them, so they stay inside the author's own works and no third-party attribution applies here. The
block's GUI sheet is a separate case: it is an AE2-derived texture and stays under LGPL-3.0 (see the GUI
table below).

| File | Role |
|------|------|
| `assets/ae2_pattern_disk/textures/block/batch_assembler_grid.png` (+ `.mcmeta`) | unpowered shell |
| `assets/ae2_pattern_disk/textures/block/batch_assembler_grid_on.png` (+ `.mcmeta`) | powered core, particle texture |
| `assets/ae2_pattern_disk/textures/block/batch_assembler_grid_on_light.png` (+ `.mcmeta`) | powered emissive shell |
| `assets/ae2_pattern_disk/models/block/batch_assembler.json` | unpowered `cube_all` model over the shell texture |
| `assets/ae2_pattern_disk/models/block/batch_assembler_on.json` | powered glow-shell geometry and display transforms |

The block's registry ID is `batch_molecular_assembler`, so its blockstate and item model files carry
that name while the model and texture files are named `batch_assembler*`.

An older io_port-style set of seven `textures/block/batch_assembler_*.png` files (byte-identical copies of
the `pattern_transferer_*` files above) was deleted once the grid set replaced it and nothing referenced it
any more; those files are no longer part of this distribution.

**The following GUI textures were compared against AE2's GUI sheets (LGPL-3.0), pixel-by-pixel on the same canvas:**

| File | AE2 Source | Relation |
|------|-----------|----------|
| `assets/ae2_pattern_disk/textures/guis/pattern_provider.png` | `assets/ae2/textures/guis/pattern_provider.png` | pixel-identical (PNG bytes differ) |
| `assets/ae2_pattern_disk/textures/guis/ae2_pattern_disk.png` | `assets/ae2/textures/guis/io_port.png` | locally modified derivative, 2026-09 (~1% of opaque pixels differ) |
| `assets/ae2_pattern_disk/textures/guis/ex_molecular_assembler.png` | `assets/ae2/textures/guis/molecular_assembler.png` | locally modified derivative, 2026-09 (~2% of opaque pixels differ) |
| `assets/ae2_pattern_disk/textures/guis/pattern.png` | `assets/ae2/textures/guis/pattern.png` | locally modified derivative, 2026-09 (~9% of opaque pixels differ) |
| `assets/ae2_pattern_disk/textures/guis/batch_molecular_assembler.png` | `assets/ae2/textures/guis/molecular_assembler.png` | locally modified derivative, 2026-09 (~43% of opaque pixels differ) |
| `assets/ae2_pattern_disk/textures/guis/pattern_modes.png` | `assets/ae2/textures/guis/pattern_modes.png` | same canvas; ~69% of the opaque pixels differ; provenance unconfirmed |
| `assets/ae2_pattern_disk/textures/guis/states.png` | `assets/ae2/textures/guis/states.png` | same canvas; ~58% of the opaque pixels differ; provenance unconfirmed |

The remaining textures under `textures/` (everything in `textures/item/`, plus
`textures/block/pattern_disk_assembler_lights.png`) were compared against AE2 19.2.17 both byte-wise
(against AE2's item and block textures) and pixel-wise (same canvas, same size): the closest same-size
AE2 match for each item sheet differs in at least 62% of its opaque pixels, and
`pattern_disk_assembler_lights.png` is a 12-frame emissive overlay built on the already-listed
`block/molecular_assembler.png` copy. No same-canvas AE2 match reproduces more than 38% of any of
these files, so none of them is a copy of an AE2 texture.

`models/block/pattern_transferer.json` and `models/block/pattern_transferer_on.json` are derived from
AE2's `assets/ae2/models/block/io_port.json` / `io_port_on.json` (same element geometry and display
transforms, textures repointed to the local copies above), and are therefore also covered by
LGPL-3.0. `models/block/batch_assembler.json` is a plain `cube_all` wrapper, while
`batch_assembler_on.json` carries the author's own glow-shell geometry (see above); the item model
points at the `_on` variant so the inventory icon keeps its display transforms. Which batch
model is used is driven by the block's `powered` state, mirroring AE2's IO port: on while the ME node
is online, so the glow means "connected and powered", not "currently crafting". The remaining model
JSON files referencing `ae2:*` parents (`display_base`, `display_off`, `cable_interface`) are derivative
works of AE2's model files as well. `models/part/pattern_disk_provider_base.json` copies the panel
geometry of AE2's `part/pattern_provider_base` (by Sea_Kerman), and
`models/item/cable_pattern_disk_provider.json` inherits `ae2:item/cable_interface` - both listed here for
the same reason.

### Third-Party Code

The EMI recipe transfer integration (`integration/emi/`) is modelled after AE2's own `EmiEncodePatternHandler`;
the JEI transfer handler follows the same design pattern as AE2's JEI handler. Both are independent implementations
that interact only with the respective recipe-viewer's public API and do not contain code copied from AE2.
The craftable "+" indicator in the pattern disk encoding terminal (and its tooltip line) follows AE2's own
pattern encoding terminal the same way: same slot semantics, same placement, no AE2 code copied.
