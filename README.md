# NamelessMC Mods

Server-only NamelessMC integration for modded Minecraft. This branch contains native Forge, NeoForge and Fabric adapters, plus the Fabric-compatible route for Quilt. Standalone Bukkit, Spigot, Paper, BungeeCord, Velocity and Sponge plugin projects are removed. The cash-exchange addon is also excluded.

The mods connect a server to an existing NamelessMC website, with account commands, optional LuckPerms integration, notifications and optional website modules including Store delivery. They add no blocks or items, and clients do not need this mod.

## Loader and Minecraft versions

| Project | Purpose |
| --- | --- |
| [Forge](forge/) | Native Forge events and dedicated-server lifecycle. |
| [NeoForge](neoforge/) | Native NeoForge events; legacy NeoForge coordinate for Minecraft 1.20.1. |
| [Fabric](fabric/) | Native Fabric server events; matching Fabric API required. |
| Quilt | Uses the exact matching Fabric-compatible JAR, with separate Quilt startup validation. |

The requested range is **every stable Minecraft release from 1.20.1 through 26.3**. Each artifact requires one exact Minecraft release. [Coverage and limitations](compatibility/README.md) distinguish published loader availability, successful builds and runtime checks. Forge has no published builds for Minecraft 1.20.5 or 1.21.2. Some selected NeoForge loaders are beta releases.

**The multi-version 0.2.0 work is undergoing validation. A selectable target is not a verified compatibility claim.** Consult the [build results](https://github.com/MCrashCraft/Nameless-Mod/actions/workflows/mod-builds.yml) before installing an artifact. The earlier Forge 1.20.1 version 0.1.1 passed 43 tests and isolated mock-website/server checks; those historical results do not certify every new port.

## Build

Install Python 3.10+ and JDKs 17, 21 and 25. From the repository root:

```powershell
python scripts/build.py --loader forge --minecraft 1.20.1 --source-bundle
python scripts/build.py --loader neoforge --minecraft 1.21.1 --source-bundle
python scripts/build.py --loader fabric --minecraft 26.3 --source-bundle
```

Use `--plan` to see the exact build/runtime Java and Gradle requirements, or `--list` to list published loader combinations. `--java-home` chooses the build JDK; `--java-installations` accepts comma-separated JDK paths for toolchains. Version-specific `JAVA_HOME_17_X64`, `JAVA_HOME_21_X64` and `JAVA_HOME_25_X64` variables are also supported. Fabric's current Loom build requires JDK 25 even when producing Java 17-compatible game bytecode.

Gradle wrappers download their pinned distribution into `GRADLE_USER_HOME`. Set that environment variable to a suitable build-cache location. The modern Forge toolchain can require several GiB while preparing Minecraft; these build requirements are separate from the mod's server runtime requirements.

Outputs are isolated under `<loader>/build/<minecraft>/libs/`. Install the final `namelessmc-...-0.2.0.jar`, not a `slim`, `dev` or sources artifact. Complete corresponding source ZIPs are under `distributions/` and contain all shared and loader projects needed to rebuild. The build driver audits packaged metadata, licenses and prohibited bundled content; it does not start a server.

## Installation and private configuration

Install only the artifact matching the server's Minecraft version and loader in its `mods` directory. Fabric-compatible servers also need the corresponding Fabric API. Do not install multiple NamelessMC loader variants together.

After first startup, configure the private connection in `config/namelessforge/main.yaml`. Connection URL and API-key defaults are blank. Real website credentials and private endpoints belong only in server configuration. Optional website features require their corresponding website modules and settings. The [configuration reference](forge/CONFIGURATION.md) covers account, privacy, permissions and Store options shared by the adapters.

The mod ID and configuration directory remain `namelessforge` across loaders so configuration can be retained when moving a server. Back up the world and private configuration before changing loader or Minecraft version.

## License and attribution

[GNU GPL version 3 or later](LICENSE). Required shared NamelessMC code remains under `forge/vendor/nameless-plugin/`; the Java API and other dependencies retain their individual license notices. See [upstream attribution](forge/UPSTREAM-NOTICE.md) and [dependency licenses](forge/third-party-licenses/). Original upstream Git history is preserved. This is an independently maintained mod port.
