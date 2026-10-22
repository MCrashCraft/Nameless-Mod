# NamelessMC NeoForge

Native server-only NeoForge adapter, version 0.2.0. It uses the shared NamelessMC core, Java API and Minecraft adapter sources. See the [download guide](../RELEASES.md) for exact Minecraft and loader versions.

Build one exact published target with Java 25 running Gradle 9.2.1:

```powershell
.\gradlew.bat '-PminecraftVersion=1.21.1' test build
```

The toolchain for each target is selected from the checked-in compatibility matrix: Java 17 for 1.20.1–1.20.4, Java 21 for 1.20.5–1.21.11, and Java 25 for 26.x. Outputs are isolated under `build/<minecraftVersion>/libs/` and are marked for their exact Minecraft version. The 1.20.1 variant uses NeoForge's legacy Forge coordinate and reobfuscation; later variants use native NeoForge.

Minecraft 1.20.2, 1.20.3 and 1.20.5 predate NeoForge's ModDev Maven metadata. Their separate entry uses the official NeoGradle userdev format, version 7.0.116. Build these through `python ../scripts/build.py --loader neoforge --minecraft <version>` so the driver selects the pinned Gradle 8.14.5 wrapper; other targets use Gradle 9.2.1.

See the repository's compatibility results for completed builds and runtime checks. A published loader version or a selectable build target is not itself a compatibility claim.

Targets with NeoForge's native JUnit launch profile use it for the shared test suite, including actual Minecraft chat codecs. Earlier releases use native classes directly. The initial launcher setup downloads official game assets. `CI=true` selects ModDevGradle's official binary setup pipeline without decompiling the game.

After a successful build, the portable packaged-JAR smoke runner can check a disposable dedicated server:

```text
python ../scripts/neoforge_smoke.py 1.21.1 --work <empty-scratch-directory> --java-home <target-java-home> --accept-eula
```

This verifies the exact installer against `installer-checksums.json`, binds only loopback on an ephemeral port, creates a fresh world with blank website configuration, and checks startup, status, reload and shutdown. The explicit EULA option applies only to that disposable test. Existing servers and private configuration are never inputs.

The mod ID, command namespace and private configuration directory remain `namelessforge`, so existing settings can be retained when switching loaders. Configure the website connection on your own server; no private credentials or website endpoint are included in source. Do not install more than one loader variant of this mod on a server.

GPL-3.0-or-later. Shared upstream and dependency licenses are retained from `../forge/` and included in the release JAR.
