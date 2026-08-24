# AE2 Pattern Disk

An addon for [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) that adds high-capacity pattern disks, a disk-backed pattern provider, a pattern transferer, and an efficient parallel molecular assembler.

- **Loader / MC**: NeoForge 1.21.1
- **NeoForge**: 21.1.248
- **AE2**: 19.2.8
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
Moves encoded patterns between AE2 blank patterns and pattern disks. Input slots accept encoded patterns or populated disks; application slots hold the target disks. Blank patterns produced by extraction are returned to the connected ME network. Supports speed cards.

### Efficient Molecular Assembler
A parallel molecular assembler with **eight independent execution threads**. It accepts crafting jobs pushed by AE2 pattern providers and runs them concurrently. Each thread owns a 3×3 molecular assembler grid, an output slot, and independent progress.

The GUI exposes one page per thread (mirroring EAE's extension molecular assembler) with vertical progress only for the selected page. Accepts up to **five AE2 Speed Cards** with multipliers `1.0x / 1.3x / 1.7x / 2.0x / 2.5x / 5.0x`.

## Blocks & Items

| ID | Type |
| --- | --- |
| `ae2_pattern_disk:pattern_disk_1k` | Item |
| `ae2_pattern_disk:pattern_disk_4k` | Item |
| `ae2_pattern_disk:pattern_disk_16k` | Item |
| `ae2_pattern_disk:pattern_disk_64k` | Item |
| `ae2_pattern_disk:pattern_disk_256k` | Item |
| `ae2_pattern_disk:pattern_transferer` | Block |
| `ae2_pattern_disk:pattern_disk_provider` | Block |
| `ae2_pattern_disk:pattern_disk_assembler` | Block |

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

The mod ships an AE2 / GuideME guide (in `assets/ae2_pattern_disk/ae2guide/`) covering the pattern disks (all five tiers on one page), the pattern disk provider, the pattern transferer, and the efficient molecular assembler. The three machine GUIs link to their guide page.

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

See `LICENSE` if present; review upstream AE2 assets (LGPL/GPL) if you redistribute any copied resource.
