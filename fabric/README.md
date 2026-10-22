# NamelessMC — Fabric-compatible server adapter

GPL-3.0. This is a native dedicated-server adapter sharing the audited NamelessMC common core and Minecraft bridge with the Forge and NeoForge projects. It adds no items, blocks, client code or custom network channel. Each build targets one exact Minecraft release; the release matrix is in `../compatibility/targets.json`.

Use JDK 25 for the Gradle build JVM. From this directory run `gradlew.bat "-PminecraftVersion=1.20.1" test build` (or `bash gradlew ...` on Linux). Generated game bytecode targets Java 17, 21 or 25 as required by that exact Minecraft release. The complete runtime JAR is `build/<minecraft-version>/libs/namelessmc-fabric-<minecraft-version>-0.2.0.jar`; `slim` and `dev-shadow` files are intermediate artifacts.

Install the exact-target runtime JAR on a dedicated Fabric server with the matching Fabric API. The metadata is server-only. Optional LuckPerms uses the same permission policy as the Forge adapter. All API connection defaults remain blank; configure only the server's private `config/namelessforge/main.yaml` after first startup.

Quilt Loader uses the same Fabric-compatible artifact. Actual tested Quilt combinations are recorded separately; metadata compatibility alone is not proof of runtime support. Older Quilt installations may need the corresponding Quilted Fabric API instead of plain Fabric API. QSL/QFAPI are retired for 26.1 onward, so this adapter avoids a separate QSL codepath. Quilt 0.30.1 provides Fabric Loader API version 0.19.3; this mod requires only the older stable loader APIs it actually uses. The selected Fabric API may impose additional requirements.

The portable `../scripts/fabric_smoke.py` runner installs an isolated server from official loader installers, uses a fresh loopback world, checks status and reload commands, then stops the server. It takes `fabric` or `quilt`, an exact Minecraft version, `--work` pointing to empty scratch storage, and `--java-home` for that game's Java version. Read the Minecraft EULA before adding `--accept-eula`. It never starts or changes an existing server. Result receipts include the tested mod's SHA-256; these are startup and command checks, not a multiplayer feature acceptance test.

Build and runtime results must be consulted before claiming compatibility. The existence of a matrix entry does not mark the mod tested on that target.
