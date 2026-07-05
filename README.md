# BuildingGadgets2-Restocker

A NeoForge 1.21.1 mod for ATM10 that bridges **Building Gadgets 2** and **Applied Energistics 2**.
(Mod id: `bg2restocker`.)

Place the **Blueprint Restocker** block, connect it to your ME network with a cable, and insert a
BG2 **Copy-Paste Gadget**. The block reads the material list of the blueprint currently loaded in
the gadget and shows it in three sections:

- **Available** (green) — the ME network already has enough
- **Will be crafted** (yellow) — a pattern exists; the deficit will be autocrafted
- **Missing recipe** (red) — no AE2 pattern produces this item

Click **Craft All** to submit AE2 crafting jobs for every yellow item — only the deficit
(needed − in network) is requested. Crafted items stay in the ME network.

## Requirements (runtime)

- NeoForge 1.21.1 (21.1.x)
- Applied Energistics 2 (19.x) — required
- Building Gadgets 2 (1.3.x) — technically optional, but the block does nothing useful without it

## Building

Requires JDK 21 and Gradle 8.8+ (or just open the folder in IntelliJ IDEA and let it import).

```sh
gradle build
```

The jar lands in `build/libs/buildinggadgets2-restocker-1.0.0.jar`. Drop it into your ATM10 `mods/` folder.

If you have Gradle installed once, generate the wrapper (`gradle wrapper`) so subsequent builds
can use `./gradlew build`.

### Releasing

Pushing to `main` with `major:` or `minor:` anywhere in a commit message triggers
`.github/workflows/release.yml`: it bumps off the latest `v*` git tag (`minor:` → x.Y.0,
`major:` → X.0.0), builds the jar with that version, and publishes a GitHub release with
the jar attached. Commits without either keyword release nothing. The first release (no
tags yet) uses `mod_version` from `gradle.properties` as-is. A release can also be
triggered manually from the Actions tab (choose major or minor).

### Dev runs

`gradle runClient` starts a dev client with both AE2 (Maven Central) and Building Gadgets 2
(via the Curse Maven proxy) loaded, so the full flow is testable in dev. If BG2 is ever
missing, the block shows "Building Gadgets 2 is not installed" and the gadget slot rejects
all items.

To bump the BG2 version, replace the file ID in `build.gradle`
(`curse.maven:building-gadgets-298187:<fileId>`) with the numeric ID from the end of the
file's CurseForge URL.

## How it works

- **BG2 integration** (`compat/BG2Compat.java`): BG2 publishes no API, so the template is read
  via reflection. The gadget item only carries a UUID; the actual block list lives in BG2's
  `BG2Data` saved data on the overworld. Each entry's `BlockState` is converted to its block
  item and tallied. If BG2's internals change in a future version, reads fail soft: the UI shows
  "Could not read the blueprint" and logs the reason once.
- **AE2 integration** (`block/RestockerBlockEntity.java`): the block entity is a proper ME grid
  node (`IManagedGridNode`, exposed on all sides, 1 AE/t idle draw). Stock is read from the
  storage service's cached inventory; craftability via `ICraftingService.isCraftable`, which
  covers every AE2 pattern type. "Craft All" starts async crafting calculations; the block
  entity polls the futures each server tick and submits finished, non-simulated plans.
- **UI refresh**: the client requests a refresh when the screen opens; the server also pushes
  updates when the gadget slot changes and after all Craft All jobs have been submitted.
