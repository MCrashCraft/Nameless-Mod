# Minecraft and loader coverage

NamelessMC Mod 0.2.0 targets every stable Minecraft release from **1.20.1 through 26.3** for Forge, NeoForge, Fabric and Quilt. Each mod JAR names and requires one exact Minecraft version. This matrix covers stable Minecraft releases; selected loader versions may themselves be beta releases.

[`targets.json`](targets.json) records published official loader and Fabric API coordinates, with Java requirements and beta flags. Its entries describe build targets, not completed tests. The matrix contains 23 Minecraft releases and 90 published loader combinations. Forge has no published loader for Minecraft **1.20.5** or **1.21.2**; those two combinations cannot be built.

The native Forge, NeoForge and Fabric projects produce 67 distinct artifacts. Quilt uses the matching Fabric-compatible mod artifact, with its own startup test. No QSL-only dependency is added. Successful Fabric compilation does not certify Quilt runtime compatibility.

All 67 native artifacts passed their 46-test suites and package audits. Packaged-server checks recorded **88 clean results across 90 published loader combinations**. The two remaining combinations have documented Forge shutdown limitations. The [validation table](VALIDATION.md) and [machine-readable results](validation.json) identify the exact tested versions and artifact hashes. These local results are independent of GitHub Actions availability.

The [known loader limitations](KNOWN-ISSUES.md) include independently reproduced Forge 1.21 and 1.21.9 configuration-watcher shutdown hangs. A successful native build does not override those failed process-exit checks.

NeoForge's selected loaders for 1.20.3, 1.20.5, 1.21.2, 1.21.6, 1.21.7, 1.21.9, 26.1 and 26.1.1 are beta releases. This is recorded in each target, not hidden behind a broad version range.

Builds use native loader APIs and actual Minecraft classes. Shared bindings account for command callbacks, profile names, rich-chat codecs and permission APIs across game versions. Tests check native rich-chat serialization, command safety, permissions, reload generations, privacy and Store delivery receipts. Package audits check exact game metadata, license material and dependency boundaries; game and loader classes are not bundled.

## Choose a download

Select the [release](https://github.com/MCrashCraft/Nameless-Mod/releases) for your exact Minecraft version and server loader. Forge, NeoForge and Fabric have separate native artifacts; Quilt uses the Fabric-compatible artifact for the same game version. Fabric and Quilt need the corresponding Fabric API recorded in [`targets.json`](targets.json).

Use Java 17 for Minecraft 1.20.1–1.20.4, Java 21 for 1.20.5–1.21.11, and Java 25 for 26.x. Install the runtime JAR from the release assets. The accompanying source ZIP is for building the project and does not belong in `mods`.

The [mod build workflow](../.github/workflows/mod-builds.yml) compiles and tests every distinct published target independently. A failed job produces no successful release artifact. Its test reports and build receipt distinguish compilation/unit tests from runtime verification. A passing compile is not evidence of a paid purchase, player login or production deployment.

## Primary source records

- [Mojang release manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [Forge published versions](https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml)
- [NeoForge published versions](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml)
- [Fabric game and loader metadata](https://meta.fabricmc.net/v2/versions)
- [Quilt metadata](https://meta.quiltmc.org/v3/versions)
- [Quilt's changes for unobfuscated Minecraft](https://quiltmc.org/en/blog/2026-02-03-non-obfuscated-updates/)
