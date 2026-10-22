# Minecraft and loader coverage

The requested scope is every stable Minecraft release from **1.20.1 through 26.3**, for Forge, NeoForge, Fabric and Quilt. Snapshots and Minecraft prereleases are excluded. Each mod JAR names and requires one exact Minecraft version.

[`targets.json`](targets.json) records published official loader and Fabric API coordinates, with Java requirements and beta flags. Its entries describe build targets, not completed tests. The matrix contains 23 Minecraft releases and 90 published loader combinations. Forge has no published loader for Minecraft **1.20.5** or **1.21.2**; those two combinations cannot be built.

The native Forge, NeoForge and Fabric projects produce 67 distinct artifacts. Quilt uses the matching Fabric-compatible mod artifact, with its own startup test. No QSL-only dependency is added. Successful Fabric compilation does not certify Quilt runtime compatibility.

The [local validation table](VALIDATION.md) records completed native suites and packaged-server checks. Its [machine-readable receipts](validation.json) include the exact tested artifact hashes. Pending entries are not verified support claims. These local results are independent of GitHub Actions availability.

The [known loader limitations](KNOWN-ISSUES.md) include independently reproduced Forge 1.21 and 1.21.9 configuration-watcher shutdown hangs. A successful native build does not override those failed process-exit checks.

NeoForge's selected loaders for 1.20.3, 1.20.5, 1.21.2, 1.21.6, 1.21.7, 1.21.9, 26.1 and 26.1.1 are beta releases. This is recorded in each target, not hidden behind a broad version range.

Builds use the project's native loader APIs and actual Minecraft classes. The shared bindings account for command callbacks, profile names, rich-chat codecs and permission APIs across game versions. Tests check actual native rich-chat serialization, command safety, permissions, reload generations, privacy and Store delivery receipts. An artifact audit checks exact game metadata, licenses, absence of the removed addon and absence of bundled game/loader classes.

The [mod build workflow](../.github/workflows/mod-builds.yml) compiles and tests every distinct published target independently. A failed job produces no successful release artifact. Its test reports and build receipt distinguish compilation/unit tests from runtime verification. A passing compile is not evidence of a paid purchase, player login or production deployment.

## Primary source records

- [Mojang release manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [Forge published versions](https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml)
- [NeoForge published versions](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml)
- [Fabric game and loader metadata](https://meta.fabricmc.net/v2/versions)
- [Quilt metadata](https://meta.quiltmc.org/v3/versions)
- [Quilt's changes for unobfuscated Minecraft](https://quiltmc.org/en/blog/2026-02-03-non-obfuscated-updates/)
