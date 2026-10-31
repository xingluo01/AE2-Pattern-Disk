# 资产授权清单

本文件是 `README.md` License 一节的明细：哪些资产来自 Applied Energistics 2（LGPL-3.0），哪些是本模组作者自己的作品（ARR），以及模型文件如何从 AE2 派生。表格中的路径相对 `src/main/resources/`；正文里的 `models/...`、`textures/...` 相对 `assets/ae2_pattern_disk/`。

## 授权分划

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**.

Assets marked ARR are the author's own works and are **not** covered by this LGPL-3.0 grant — currently
the batch assembler block textures and its glow-shell model (see the batch assembler entry under Upstream
Attribution below).

## Upstream Attribution

This mod is an addon for **[Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)** (AE2), which is also licensed under LGPL-3.0.

**The following assets are sourced from AE2 (LGPL-3.0):**

| File | AE2 Source |
|------|-----------|
| `assets/ae2_pattern_disk/textures/part/pattern_disk_encoding_terminal_bright.png` | `assets/ae2/textures/part/pattern_encoding_terminal_bright.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_encoding_terminal_medium.png` | `assets/ae2/textures/part/pattern_encoding_terminal_medium.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_encoding_terminal_dark.png` | `assets/ae2/textures/part/pattern_encoding_terminal_dark.png` |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_management_terminal_bright.png` | locally edited (2026-09-21) derivative of this mod's `pattern_disk_encoding_terminal_bright.png` (row above) |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_management_terminal_medium.png` | byte-identical copy of this mod's `pattern_disk_encoding_terminal_medium.png` (row above) |
| `assets/ae2_pattern_disk/textures/part/pattern_disk_management_terminal_dark.png` | locally edited (2026-09-21) derivative of this mod's `pattern_disk_encoding_terminal_dark.png` (row above) |
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
| `assets/ae2_pattern_disk/textures/guis/pattern_modes.png` | `assets/ae2/textures/guis/pattern_modes.png` | hand-drawn original asset (same canvas as AE2's; ~69% of the opaque pixels differ) |
| `assets/ae2_pattern_disk/textures/guis/states.png` | `assets/ae2/textures/guis/states.png` | hand-drawn original asset (same canvas as AE2's; ~58% of the opaque pixels differ) |
| `assets/ae2_pattern_disk/textures/guis/checkbox.png` | `assets/ae2/textures/guis/checkbox.png` | byte-identical copy (verified against 19.2.17) |

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
the same reason. Their two counterparts for the self-assembling provider's panel form are
`models/part/meteorite_pattern_provider_base.json` (the panel model supplied by the texture artist) and
`models/item/cable_meteorite_pattern_provider.json` (its listing icon). Both inherit AE2 parents for their
geometry (`ae2:part/pattern_provider_base` and `ae2:item/cable_interface`), and both point their faces at
AE2 Crystal Science's own textures (`ae2cs:block/meteorite_pattern_provider/base` and `.../back`). That is
deliberate rather than a leftover: this device only exists while AE2 Crystal Science is installed, so its
two forms are meant to look like that mod's block. The one face this mod still supplies itself is the panel
model's `front`, `textures/block/meteorite_pattern_provider.png` (used together with the local
`..._alternate*.png` by the block form's models).

**The following assets are copied from AE2 Crystal Science (CC BY-NC-SA 3.0):**

| File | Source |
|------|--------|
| `assets/ae2_pattern_disk/textures/block/resonating_pattern_disk_provider.png` | `assets/ae2cs/textures/block/resonating_pattern_provider/base.png` |
| `assets/ae2_pattern_disk/textures/block/resonating_pattern_disk_provider_back.png` | `assets/ae2cs/textures/block/resonating_pattern_provider/back.png` |
| `assets/ae2_pattern_disk/textures/block/resonating_pattern_disk_provider_alternate.png` | `assets/ae2cs/textures/block/resonating_pattern_provider/alternate.png` |
| `assets/ae2_pattern_disk/textures/block/resonating_pattern_disk_provider_alternate_arrow.png` | `assets/ae2cs/textures/block/resonating_pattern_provider/alternate_arrow.png` |
| `assets/ae2_pattern_disk/textures/block/resonating_pattern_disk_provider_alternate_front.png` | `assets/ae2cs/textures/block/resonating_pattern_provider/alternate_front.png` |

These copies are redistributed under CC BY-NC-SA 3.0, not under this project's LGPL-3.0 grant. Attribution:
Applied Energistics: Crystal Science - Frostbite, Lounode, QiuYe, MHanHanBing, TedXenon, LuckyBlock.
The block models use vanilla parents (`cube_all` / `cube`); the panel model mirrors the self-assembling
provider's panel model and inherits AE2's `part/pattern_provider_base`, while the listing icon inherits
`ae2:item/cable_interface`. Our panel model keeps this mod's own `pattern_disk_provider_sides*` for the
status light; no Crystal Science model geometry is copied.

The marked provider's panel form follows the same rule: `models/part/marked_pattern_disk_provider_base.json`
copies the panel geometry of AE2's `part/pattern_provider_base` (by Sea_Kerman), and
`models/item/cable_marked_pattern_disk_provider.json` inherits `ae2:item/cable_interface`. Both still point
their faces at this mod's own `pattern_disk_provider*` textures - that is a deliberate placeholder until the
device gets its own art, not a leftover.
