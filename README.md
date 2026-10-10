# mimodel-tool

**English** | [Русский](README.ru.md)

A converter of Minecraft models to the **Mine-imator** format (`.mimodel` + `.png`).
Suitable for Mine-imator 2.0.x and builds based on it.

Takes models directly from:

* **mod jar** (Fabric / Forge / NeoForge / Quilt);
* **Minecraft client jar** (vanilla blocks, items, and entities);
* **resource pack** (`.zip` or folder), including OptiFine CEM (`.jem`/`.jpm`, e.g. Fresh Animations);
* individual `.bbmodel` (Blockbench) and `.geo.json` (Bedrock / GeckoLib) files.

Minecraft versions: **1.20 – 26.3**.

## What gets converted

| Type | Source | How |
|---|---|---|
| Blocks, items | `assets/*/models/**.json` | `parent` inheritance, textures, element rotations (including the new 26.x format), grass/foliage tinting; flat items (`item/generated`) -> a 3D model built from pixels |
| Minecraft entities | client jar | geometry is taken from Minecraft itself (exactly as in-game) |
| Models from mod code | `.class` in the mod jar | safe interpretation of the bytecode of `create…Layer()` / `getTexturedModelData()` (mod code is **not** executed) |
| GeckoLib / Bedrock | `*.geo.json` | bones, cubes, pivot, rotation, inflate, mirror, per-face UV |
| Blockbench | `*.bbmodel` | groups, cubes, embedded textures, box UV and per-face UV |
| OptiFine CEM | `*.jem`, `*.jpm` | resource pack models (Fresh Animations, etc.) |

If a model's UV doesn't fit Mine-imator's box unwrap (per-face UV, texture scale, hidden faces), the texture is automatically re-baked into a separate atlas - the appearance is preserved.

## Graphical interface

Run `mimodel-tool.exe`.

1. **Add files…** (or drag files into the window) - mods, Minecraft client, resource packs. You can point it at an entire `mods` folder.
2. Find the model in the tree (**Search** field). On the right is a 3D preview:
   LMB - rotate, RMB - pan, wheel - zoom, double-click - reset.
3. For entities and code-based models the texture is selected automatically; in the **Texture** list you can pick another one or your own PNG.
4. **Export selected…** (you can select multiple models or a whole folder) or **Export all (by filter)…**.
5. In Mine-imator: **Import** (Ctrl+I) -> select the `.mimodel` file.

**View -> Language** - English (default) or Russian. **View -> Theme** - dark (default), light, or system. Settings are remembered.

**MC version** - which Minecraft version to use for missing vanilla files (parent models, textures, classes). Auto - the version is taken from the mod/resource pack.
**Download Minecraft files** - if needed, the client for the required version is downloaded from Mojang's servers once and cached (`%LOCALAPPDATA%\mimodel-tool\cache`). If a Minecraft launcher is installed, files from `.minecraft` are used.

## Command line

`mimodel-tool-cli.exe` (or `java -jar mimodel-tool-all.jar …`):
