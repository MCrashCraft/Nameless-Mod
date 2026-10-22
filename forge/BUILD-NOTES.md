# Building NamelessMC Forge

Use the repository's exact-target driver rather than mixing Gradle generations:

```powershell
python scripts/build.py --loader forge --minecraft 1.20.1 --source-bundle
python scripts/build.py --loader forge --minecraft 26.3 --source-bundle --accept-test-eula
```

Add `--plan` to print toolchain requirements without building. The Forge driver uses JDK 17 for Minecraft 1.20.1–1.20.4, JDK 21 for 1.20.6–1.21.11, and JDK 25 for 26.x. Supply JDK paths through the [version-specific environment variables or explicit options](../README.md#build-from-source). Builds use a separate workspace and cache.

The legacy build uses Gradle 8.8 and ForgeGradle 6. Modern targets use Gradle 9.7.1 and ForgeGradle 7 with the matching wrapper in `build-tools/modern-gradle/`. Forge has no published target for Minecraft 1.20.5 or 1.21.2. Game and loader dependencies use the exact coordinates in [`compatibility/targets.json`](../compatibility/targets.json).

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

Compilation and unit tests are distinct from a packaged-JAR startup check. Consult the [validation table](../compatibility/VALIDATION.md) for completed checks and the [known loader limitations](../compatibility/KNOWN-ISSUES.md) for failed Forge shutdown checks. Use disposable, loopback-bound worlds for local smoke tests.

Forge 1.21 and later require initialized FML registries for the native JUnit suite. After reviewing the Minecraft EULA, pass `--accept-test-eula` to permit its disposable loopback server. `forge/loader-tests.gradle` merges the test sources into a development module, invokes the actual Jupiter engine after server startup, and requires all 46 tests to execute successfully. The development-only entry and libraries are excluded from the production JAR. A clean server exit without complete passing XML and summary is a failed test. `--skip-tests` omits that server and does not record a test pass.
