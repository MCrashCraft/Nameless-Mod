# Building the unofficial Forge port

Use a Java 17 JDK. The included Gradle wrapper selects Gradle 8.8 and ForgeGradle 6.0.54. The game dependency is Forge `1.20.1-47.4.16` with official Minecraft 1.20.1 mappings.

From this directory, run `gradlew.bat test build` on Windows or `bash ./gradlew test build` on Linux. Using `bash` also works when ZIP extraction does not preserve the wrapper's executable permission. Initial dependency setup requires access to the public Forge Maven repository, Maven Central and the Gradle Plugin Portal. All three vendored source projects are included, so no Maven-local installation, Git clone, website API key or private repository is required.

The distributable mod is `build/libs/namelessmc-forge-1.20.1-0.1.0.jar`. It is shaded and reobfuscated for a production Forge server. The `-slim.jar` file does not contain the runtime libraries and is not the mod to install. LuckPerms, Minecraft and Forge classes are deliberately not bundled; the mod can run without LuckPerms, and group synchronization becomes available when the official LuckPerms mod is installed.

The source directories are:

- `src/main/java`: the native Forge adapter.
- `vendor/nameless-plugin/common/src/main`: the pinned Nameless-Plugin shared core, with the port's documented compatibility/hardening changes.
- `vendor/nameless-java-api/src/main/java`: pinned official Nameless Java API source.
- `vendor/kyori-event/api/src/main/java`: pinned Kyori event API source, avoiding an unpinned snapshot dependency.

`MavenConstants.java` is generated from the preserved upstream template during compilation. The resource defaults, translations and commands are copied from the vendored shared core. Third-party libraries are relocated under `dev.mcrashcraft.namelessforge.lib` so they cannot replace Forge's Gson, Guava or other installed mods' library packages. Dependency license files, upstream notices and the additional full license texts in `third-party-licenses` are included in the JAR.

The optional `sourceBundle` task creates a matching source archive under `build/distributions`. It excludes Git metadata, compiled outputs and Gradle caches. Source release packaging should be run only after the final reviewed changes and tests are complete. A source bundle must never include a configured server's API key, runtime configuration or world.

For a first local headless smoke test, `gradlew.bat runServer` uses the `run` folder. This is separate from the existing development and public servers. That optional task requires the normal Minecraft EULA acceptance and a disposable test world. Never point it at an existing server world.
