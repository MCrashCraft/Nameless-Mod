# NamelessMC Mod

Connect a modded Minecraft server to your NamelessMC website. Register and link accounts in game, deliver notifications, synchronize permission groups and fulfill Store orders through a server-only integration for **Forge, NeoForge, Fabric and Quilt**.

[Download guide](RELEASES.md) · [Download 0.2.0](https://github.com/MCrashCraft/Nameless-Mod/releases/tag/v0.2.0) · [Configuration](forge/CONFIGURATION.md) · [Compatibility](compatibility/README.md) · [Validation](compatibility/VALIDATION.md) · [License](LICENSE)

Players do not need to install the mod. It adds no blocks or items. This is an independently maintained, unofficial port of the [NamelessMC server integration](https://github.com/NamelessMC/Nameless-Plugin).

## Features

- Website account registration, Minecraft account verification, notifications and player reports.
- Optional join messages, website announcements and server-data updates.
- Optional LuckPerms group synchronization using effective memberships and applicable contexts.
- Permission-aware account information and namespaced commands that preserve commands owned by other mods.
- Optional Store fulfillment with synchronous command-result checks and a durable delivery journal.
- Website requests run away from the server tick thread; reload and shutdown invalidate obsolete work.

Periodic website features and optional module executors start disabled. API URL and key defaults are blank; configure the connection privately after installation.

## Loader and Minecraft versions

| Project | Purpose |
| --- | --- |
| [Forge](forge/) | Native Forge events and dedicated-server lifecycle. |
| [NeoForge](neoforge/) | Native NeoForge events; legacy NeoForge coordinate for Minecraft 1.20.1. |
| [Fabric](fabric/) | Native Fabric server events; matching Fabric API required. |
| Quilt | Uses the exact matching Fabric-compatible JAR, with separate Quilt startup validation. |

The version matrix covers **every stable Minecraft release from 1.20.1 through 26.3**. Each artifact requires one exact Minecraft release. [Coverage and limitations](compatibility/README.md) distinguish published loader availability, successful builds and runtime checks. Forge has no published builds for Minecraft 1.20.5 or 1.21.2. Some selected NeoForge loaders are beta releases.

Version **0.2.0** has **67 locally compiled artifacts**, each passing all 46 native tests and its package audit. Exact packaged-server checks completed with **88 clean results across 90 published loader combinations**, with two failed Forge shutdown checks documented below. Consult the [validation table](compatibility/VALIDATION.md) for exact versions, artifact hashes and limits. These checks do not certify real website purchases, player logins or compatibility with every other mod. The [build workflow](https://github.com/MCrashCraft/Nameless-Mod/actions/workflows/mod-builds.yml) can repeat the checks when GitHub Actions is available; no cloud success is claimed.

Forge 1.21 and 1.21.9 have reproduced configuration-watcher shutdown limitations, including empty-mod control cases. They remain failed unassisted process-exit checks; see [known loader limitations](compatibility/KNOWN-ISSUES.md).

## Installation

1. Use the [download table](RELEASES.md) to choose the JAR for the server's **exact Minecraft version and loader**. All 67 JARs are together in the [0.2.0 release](https://github.com/MCrashCraft/Nameless-Mod/releases/tag/v0.2.0). Quilt uses the matching Fabric-compatible JAR.
2. Put that JAR in the server's `mods` directory. Fabric and Quilt also require the corresponding Fabric API listed in the [version matrix](compatibility/targets.json). Install one NamelessMC loader variant per server.
3. Start the server once, then enter the website API URL, API key and Minecraft server ID in `config/namelessforge/main.yaml`.
4. Run `/nlpl reload`, then `/namelessforge:status` as an operator. Enable optional website features only when their website modules and settings are ready.

Use Java 17 for Minecraft 1.20.1–1.20.4, Java 21 for 1.20.5–1.21.11, and Java 25 for 26.x. The matching Minecraft loader must also be installed. LuckPerms is optional and must match the server platform.

The [configuration guide](forge/CONFIGURATION.md) covers commands, permissions, privacy and Store setup across the adapters. The mod ID and configuration directory remain `namelessforge` across loaders. Keep real website credentials and private endpoints in server configuration, and back up the world and private configuration before changing Minecraft version or loader.

## Build from source

Install Python 3.10+ and JDKs 17, 21 and 25. From the repository root:

```powershell
python scripts/build.py --loader forge --minecraft 1.20.1 --source-bundle
python scripts/build.py --loader neoforge --minecraft 1.21.1 --source-bundle
python scripts/build.py --loader fabric --minecraft 26.3 --source-bundle
```

Use `--plan` to see the exact build/runtime Java and Gradle requirements, or `--list` to list published loader combinations. `--java-home` chooses the build JDK; `--java-installations` accepts comma-separated JDK paths for toolchains. Version-specific `JAVA_HOME_17_X64`, `JAVA_HOME_21_X64` and `JAVA_HOME_25_X64` variables are also supported. Fabric's current Loom build requires JDK 25 even when producing Java 17-compatible game bytecode.

Gradle wrappers download their pinned distribution into `GRADLE_USER_HOME`. Set that environment variable to a suitable build-cache location. The modern Forge toolchain can require several GiB while preparing Minecraft; these build requirements are separate from the mod's server runtime requirements.

Forge 1.21 and later run the native unit suite after the real Forge loader initializes in a disposable loopback server. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA), then add `--accept-test-eula` to those build commands. This acceptance applies only to the test server. `--skip-tests` builds without starting it and does not count as a verified test pass. Existing servers and worlds are never test inputs.

Outputs are isolated under `<loader>/build/<minecraft>/libs/`. Install the final `namelessmc-...-0.2.0.jar`, not a `slim`, `dev` or sources artifact. Complete corresponding source ZIPs are under `distributions/` and contain all shared and loader projects needed to rebuild. The build driver audits packaged metadata, licenses and prohibited bundled content.

## License and attribution

[GNU GPL version 3 or later](LICENSE). Shared NamelessMC code is under `forge/vendor/nameless-plugin/`; the Java API and other dependencies retain their individual license notices. One complete corresponding-source ZIP in the [0.2.0 release](https://github.com/MCrashCraft/Nameless-Mod/releases/tag/v0.2.0) covers all 67 JARs. See [upstream attribution](forge/UPSTREAM-NOTICE.md) and [dependency licenses](forge/third-party-licenses/). Original upstream Git history is preserved.
