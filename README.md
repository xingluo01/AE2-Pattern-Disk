# AE2 Pattern Disk

An addon for [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) that adds high-capacity pattern disks, a disk-backed pattern provider (block and cable-panel forms), a pattern transferer, an efficient parallel molecular assembler, a batch assembler, and a pattern disk encoding and management terminal.

- **Loader / MC**: NeoForge 1.21.1
- **NeoForge**: 21.1.241 up to 21.2.0
- **AE2**: 19 or newer (built against 19.2.18)
- **Java**: 21

## API

Addons that carry pattern disks or serve the patterns stored on them compile against
`io.github.lounode.ae2pattern.api` alone; the package depends on nothing else in this mod. Everything
reachable from `PatternDiskApi` is versioned surface, so assert the version once during setup:

```java
if (PatternDiskApi.API_VERSION < 9) {
    throw new IllegalStateException("AE2 Pattern Disk API 9 is required");
}
```

The facade covers what an addon usually needs:

- `isPatternDisk` / `contents` — inspect a disk without naming its item class.
- `canAccept` / `insert` / `removeAt` — check, write and remove a pattern. `insert` answers
  `ItemStack.EMPTY` on success. Pass a `BlankPatternSink` to hand the blank pattern a write frees back
  to the ME network, or `null` when there is no network.
- `decodePattern` / `decodePatterns` / `patternType` — turn an encoded pattern into its inputs, outputs
  and kind. The plural form memoizes per contents snapshot; use it when walking a disk repeatedly.
- `terminalView` — expose slots holding disks as an inventory whose rows are the patterns on those
  disks, the shape AE2's pattern access terminal reads. Server side only.
- `diskHosts` / `maxPatterns` — list the disk hosts on a grid, or measure a host's capacity from the
  disks it actually holds.
- `registerDiskHost` — add a machine from another mod to the encoding terminal's disk list.

`ExternalUploadPolicy` and members marked `@ApiStatus.Internal` are this mod's own wiring, not surface.

## Guide

The mod ships a GuideME guide (in `assets/ae2_pattern_disk/ae2guide/`) covering the pattern disks (all five tiers on one page), the pattern disk provider (block and panel forms), the pattern transferer, the efficient molecular assembler, the batch assembler, the pattern disk encoding terminal and the pattern disk management terminal. Of the six machine GUIs, the encoding and management terminals declare a `helpTopic` in their screen JSON, so those two link to their guide pages straight from the screen; three more screens cover the same help hook in code, and the encoding terminal does both, so the guide button is reachable from five of those six screens; the batch assembler GUI has no guide entry.

The pattern disk page's type table uses the 35 images under `ae2_pattern_disk/ae2guide/assets/pattern_disk_types/`, cut from the 5x7 grid of the source render kept as `ae2_pattern_disk/ae2guide/assets/pattern_disk_types.png` (rows: untyped, crafting, processing, smithing, stonecutting, AdvancedAE's advanced processing, Rechiseled: Applied Energistics' chiseling; columns: 1k, 4k, 16k, 64k, 256k). Each cell is resized to 128x128, because GuideME draws inline images at a quarter of their size - these show as 32x32. Slice names follow the model and lang-key suffix, so `smithing` maps to `pattern_disk_type_smithing_table.png` and `adv_processing` to `pattern_disk_type_adv.png`.

Guide image references must not go up more directory levels than the page has, since relative paths are resolved against the page id - and that id is language-neutral, the `_zh_cn/` directory only supplies a translated copy of the same page. Use a path relative to the guide content root instead, such as `/assets/pattern_disk_types/pattern_disk_crafting_1k.png` - the leading slash only takes the reference out of page-relative resolution, and GuideME itself prefixes the content root (`ae2guide`), so it never depends on the depth of the containing page.

## Dependencies

Declared in `neoforge.mods.toml`. Ranges are the load-time bounds; the version each one is compiled
against can differ and is listed in `gradle/libs.versions.toml`.

**Required**

- `neoforge` — `[21.1.241,21.2.0)`
- `minecraft` — `[1.21.1,1.22)`
- `ae2` — `[19.0.0,)`
- `guideme` — `*`; provided at build, required for the guide pages
- `ae2wtlib` — `[19.5.0,)`; the two wireless terminals are built on its terminal API (item base class,
  menu host, terminal registration)

**Optional**

- `jei` — `[19.52.0,20.0.0)`, client; recipe transfer, and the recipe categories disk marks read names from
- `emi` — `[1.1.22,)`, client; supplies those category names when JEI is absent
- `ae2jeiintegration` — `*`, client; this is the AE2↔JEI bridge that registers AE2's JEI recipe categories
- `jade` — `[15.10.6,)`, client; the top-of-screen display integration
- `polymorph` — `*`; pick which result to encode when one set of ingredients matches several recipes
- `neoecoae` — `*`; its smart pattern bus takes these disks, and its upload button is installed here
- `extendedae_plus` — `*`; its terminal upload contract is implemented here
- `ae2cs` — `*`; with it installed, the self-assembling pattern disk provider is registered

**Detected at runtime, not declared**

These have code on our side but no dependency entry, because the integration stands down on its own
when the classes are missing. Install them for extra disk types and encoding tiers; with none of them
present, nothing about the mod changes.

- AdvancedAE — advanced encoding, and face-by-face input delivery
- Applied Flux — an induction card slot on the provider
- AE2 Lightning Tech — overloaded encoding
- Rechiseled: Applied Energistics — the chiseling disk type

One more optional mod has no dependency entry either: Inventory Profiles Next. Left alone, its handling of
menus it does not recognise would treat every slot in these terminals as sortable storage and move items
out of them; registering the menus as inventory-only keeps its sorting off them, the same treatment AE2's
own terminals get.

(There is also a placeholder entry for Useless Mod's "omniversal" disk type: the type table and its icon
are in place, but the encoding path is not, so that type cannot be written yet.)

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

JEI is declared as an optional dependency with range `[19.52.0,20.0.0)`. The floor is 19.52.0, where the
transfer-listener hook the disk-mark lookup reads its recipe category from first appeared, so a pack is
free to sit anywhere from there up. The ceiling is the 19.x line itself: 20.0.0 targets another Minecraft
version and replaces `RecipeType` with `IRecipeType`, which the mark lookup still uses. What this mod is
compiled against is 19.56.0, which is a build-time choice and not a floor for players. An older JEI next
to this mod is refused at load instead of quietly losing marks.

Manual check list with JEI loaded and EMI absent:

1. Open the ME Pattern Disk Encoding Terminal, open a recipe page in JEI and press "+". The pattern must
   be encoded as before.
2. Hover a disk in the disk list: a mark written from an imported recipe must show a readable category
   name (for example "Smoking") instead of the raw id.
3. Type `#` plus that name into the disk search bar: the disk must stay listed.
4. Shift + right-click a disk and the search bar text still writes/clears marks by hand, with no viewer
   involved.
5. With JEI absent the mod must still load and encode; a category mark then falls back to its raw id, and
   Shift + right-click still writes the search bar's text.

With `-Pemi` the same list applies with EMI loaded and JEI absent; the names in step 2 then come
from EMI's recipe categories instead.

### Development dependency: Neo ECO AE Extension

The source compiles against **Neo ECO AE Extension 21.2.1** from CurseForge - no local jar is needed:

```groovy
compileOnly 'curse.maven:neo-eco-ae-extension-1460639:9095600'
```

That release (2026-10-08) is the first on CurseForge to carry the parts this mod hooks into: the
parallel-intake registry the batch assembler registers against, and the reporting upload. Its changelog
names **AE2 Pattern Disk 0.7.1** as a floor. `21.2.1` is also the floor declared by **Neo ECO Prototype**,
the L1 add-on loaded at runtime.

The FD Smart Pattern Bus integration (upload-to-ECO button, disk-aware insertion, pattern access
terminal view, encoding-terminal disk list) needs the hooks that arrived in NEO ECO 21.2.1, and the mod
declares that as a dependency floor: an older NEO ECO is refused at load time with a message naming the
required range, rather than starting with a few features quietly missing.

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**.
See the [LICENSE](LICENSE) file for the full license text. Source: [github.com/xingluo01/AE2-Pattern-Disk](https://github.com/xingluo01/AE2-Pattern-Disk).

Which assets come from AE2 (LGPL-3.0), which are the author's own works (ARR), and how the models derive from AE2's are listed in [docs/ASSET_LICENSES.md](docs/ASSET_LICENSES.md).

### Third-Party Code

The EMI recipe transfer integration (`integration/emi/`) is modelled after AE2's own `EmiEncodePatternHandler`;
the JEI transfer handler follows the same design pattern as AE2's JEI handler. Both are independent implementations
that interact only with the respective recipe-viewer's public API and do not contain code copied from AE2.
The craftable "+" indicator in the pattern disk encoding terminal (and its tooltip line) follows AE2's own
pattern encoding terminal the same way: same slot semantics, same placement, no AE2 code copied.
