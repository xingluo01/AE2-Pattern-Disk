# AE2 Pattern Disk

An addon for [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) that adds high-capacity pattern disks, a disk-backed pattern provider, a pattern transferer, and an efficient parallel molecular assembler.

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

### Pattern Disk Provider
A pattern provider backed by physical pattern disks. Insert disks into its slots and it exposes their encoded patterns to the ME autocrafting system. It is a task source (it does not craft itself) and accepts returned products through its return inventory.

### Pattern Transferer
Moves encoded patterns between AE2 blank patterns and pattern disks. Input slots accept encoded patterns or populated disks; destination slots hold the target disks. Blank patterns produced by extraction are returned to the connected ME network. Supports speed cards.

### Efficient Molecular Assembler
A parallel molecular assembler with **eight independent execution threads**. It accepts crafting jobs pushed by AE2 pattern providers and runs them concurrently. Each thread owns a 3×3 molecular assembler grid, an output slot, and independent progress.

The GUI exposes one page per thread (mirroring ExtendedAE's (EAE) extension molecular assembler) with vertical progress only for the selected page. Accepts up to **five AE2 Speed Cards** with multipliers `1.0x / 1.3x / 1.7x / 2.0x / 2.5x / 5.0x`.

Each page also has an optional pattern slot. Inserting an encoded crafting pattern there turns that page into a self-executing unit: it pulls its own inputs from the ME network, crafts continuously while materials last, and pushes products plus container remainders to adjacent inventories or back into the network. A page running a manual pattern does not accept provider-pushed jobs, so the machine only takes pushed jobs while at least one page is idle.

### Batch Molecular Assembler

Buffers the jobs pushed by AE2 crafting CPUs inside nine private storage cell slots and executes them once the input material has actually stopped arriving.

#### Batch Window

The window is measured in game time since the last accepted push (two modes: standard 40 ticks, fast 10 ticks), so material that keeps coming simply keeps the batch waiting; the buffer adds no limit of its own beyond the capacity of the inserted cells, and a started batch runs to the end of the queue without a per-tick ceiling. Supports crafting-table, smithing-table and stonecutting recipes.

#### Output Queue

Produced outputs (including container remainders) enter a smooth-return queue and reach the network over ~20 ticks at ~5% of the accumulated total per tick; when the network cannot take them they stay queued and are retried, and the inputs are not rolled back for that reason - inputs roll back only when the cells cannot hold a push, when material is missing, or when the grid is out of power.

#### Cell Slots

Storage cells inserted here are private to the machine (never exposed to the ME network) and are locked while work is buffered; breaking the block or pressing cancel returns the buffer to the network.

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
| `ae2_pattern_disk:pattern_transferer` | Pattern Transferer | Block |
| `ae2_pattern_disk:pattern_disk_provider` | Pattern Disk Provider | Block |
| `ae2_pattern_disk:pattern_disk_assembler` | Efficient Molecular Assembler | Block |
| `ae2_pattern_disk:batch_molecular_assembler` | Batch Molecular Assembler | Block |

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
- **Pattern Disk Provider**
  - Shapeless: `ae2:pattern_provider` + `ae2:capacity_card`
- **Efficient Molecular Assembler**
  - Shaped:
    ```text
    A A A
    A B A
    A A A
    ```
    `A=ae2:molecular_assembler` `B=ae2:capacity_card`

## Guide

The mod ships a GuideME guide (in `assets/ae2_pattern_disk/ae2guide/`) covering the pattern disks (all five tiers on one page), the pattern disk provider, the pattern transferer, and the efficient molecular assembler. The three machine GUIs link to their guide page.

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

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**.
See the [LICENSE](LICENSE) file for the full license text.

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

These textures are redistributed under the terms of LGPL-3.0. No modifications have been made.

Model JSON files referencing `ae2:*` parents (`display_base`, `display_off`, `io_port`) are derivative works of AE2's model files and are also covered by LGPL-3.0.

### Third-Party Code

The EMI recipe transfer integration (`integration/emi/`) is modelled after AE2's own `EmiEncodePatternHandler`;
the JEI transfer handler follows the same design pattern as AE2's JEI handler. Both are independent implementations
that interact only with the respective recipe-viewer's public API and do not contain code copied from AE2.
