# NamelessMC NeoForge

Native server-only NeoForge adapter, version 0.2.0. It uses the shared NamelessMC core, Java API and Minecraft adapter sources; it contains no cash-exchange addon.

Build one exact published target with Java 25 running Gradle 9.2.1:

```powershell
.\gradlew.bat -PminecraftVersion=1.21.1 test build
```

The toolchain for each target is selected from the checked-in compatibility matrix: Java 17 for 1.20.1–1.20.4, Java 21 for 1.20.5–1.21.11, and Java 25 for 26.x. Outputs are isolated under `build/<minecraftVersion>/libs/` and are marked for their exact Minecraft version. The 1.20.1 variant uses NeoForge's legacy Forge coordinate and reobfuscation; later variants use native NeoForge.

See the repository's compatibility results for completed builds and runtime checks. A published loader version or a selectable build target is not itself a compatibility claim.

The mod ID, command namespace and private configuration directory remain `namelessforge`, so existing settings can be retained when switching loaders. Configure the website connection on your own server; no private credentials or website endpoint are included in source. Do not install more than one loader variant of this mod on a server.

GPL-3.0-or-later. Shared upstream and dependency licenses are retained from `../forge/` and included in the release JAR.
