# Building NamelessMC Forge

Use the repository's exact-target driver rather than mixing Gradle generations:

```powershell
python scripts/build.py --loader forge --minecraft 1.20.1 --source-bundle
python scripts/build.py --loader forge --minecraft 26.3 --source-bundle
```

Add `--plan` to print toolchain requirements without building. The driver uses JDK17 for Minecraft1.20.1–1.20.4, JDK21 for1.20.6–1.21.11, and JDK25 for26.x. JDK paths can be supplied through the documented version-specific environment variables or explicit options. No installed Minecraft profile or global Java setting is changed.

The legacy build uses Gradle8.8/ForgeGradle6. Modern targets use Gradle9.7.1/ForgeGradle7 and the matching wrapper in `build-tools/modern-gradle/`. Forge has no published target for Minecraft1.20.5 or1.21.2. Game/Forge dependencies use the exact coordinates in `compatibility/targets.json`.

The final artifact is `forge/build/<minecraft>/libs/namelessmc-forge-<minecraft>-0.2.0.jar`. The slim JAR is an intermediate. Legacy versions are reobfuscated as their loader requires; modern Forge builds use the modern loader's names. Never rename a JAR or broaden its metadata to imply support for another game release.

Source layout:

- `forge/src/templates/`: generated exact-version native Forge entrypoint.
- `forge/src/main/java/`: Forge data sender.
- `minecraft-adapter/src/main/java/`: shared Minecraft command, player, audience and scheduler code.
- `fabric/generate-compatibility.gradle`: exact-version Minecraft API bindings used by all loader projects.
- `forge/vendor/`: pinned NamelessMC common core, Nameless Java API and Kyori event sources, with attribution.
- `build-tools/`: native chat test support and complete corresponding-source packaging.

Dependency libraries are shaded and relocated; Minecraft, Forge and LuckPerms classes are not bundled. Private API connection defaults are blank. Licenses and attribution are included in the distributable.

The `sourceBundle` task includes all sibling sources and build tooling needed to reproduce an artifact. It excludes compiled outputs, Gradle caches, authentication data, server configuration and worlds.

Compilation and unit tests are distinct from a packaged-JAR startup check. See the compatibility documentation and workflow results for actual completed validation. Use only disposable, loopback-bound worlds for local smoke tests; never point a test launcher at an existing server world.
