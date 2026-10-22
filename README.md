# NamelessMC for Minecraft Forge 1.20.1

This branch contains MCrashCraft's server-only port of the NamelessMC plugin. It preserves the original plugin source and Git history. The upstream plugin documentation is retained in [README-UPSTREAM.md](README-UPSTREAM.md).

| Project | Version | Purpose |
| --- | --- | --- |
| [NamelessMC Forge](forge/) | 0.1.1 | Connect a Forge 1.20.1 server to an existing NamelessMC website, with account commands, LuckPerms integration and optional website modules. |

Both projects target **Minecraft 1.20.1, Forge 47.4.16 and Java 17**. Install server JARs in the server's `mods` folder. Clients do not need these server-only JARs.

## Configuration

Start with the [Forge installation guide](forge/README.md) and [configuration reference](forge/CONFIGURATION.md). API credentials belong only in the server's private configuration. Optional website features require their corresponding website modules and configuration.

## Build and validation

From `forge/`, use Java 17 and run:

```powershell
.\gradlew.bat test build sourceBundle
```

On Linux, use `bash gradlew test build sourceBundle` instead. Outputs are generated under each project's `build/` directory.

The current Forge 1.20.1 source passed **43 tests** and isolated real Forge checks using a mock website. These checks do not claim validation of real paid transactions or public-server deployment. Additional loader/version targets are being implemented and will be documented with their actual build and test results.

## License and attribution

This repository uses [GNU GPL version 3](LICENSE). Preserve the individual license notices for vendored dependencies. Each Forge project includes its own license, upstream notices and third-party license records. This is an independently maintained Forge port, not an official NamelessMC upstream release.
