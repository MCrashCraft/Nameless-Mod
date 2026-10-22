# NamelessMC for Minecraft Forge 1.20.1

This branch contains MCrashCraft's server-only Forge port of the NamelessMC plugin and a separate optional cash-exchange addon. It preserves the original plugin source and Git history. The upstream plugin documentation is retained in [README-UPSTREAM.md](README-UPSTREAM.md).

| Project | Version | Purpose |
| --- | --- | --- |
| [NamelessMC Forge](forge/) | 0.1.1 | Connect a Forge 1.20.1 server to an existing NamelessMC website, with account commands, LuckPerms integration and optional website modules. |
| [Cash Exchange addon](addons/cash-exchange/) | 0.1.0 | Optionally exchange Just Enough Drugz physical dollar bills for Nameless Store credits. |

Both projects target **Minecraft 1.20.1, Forge 47.4.16 and Java 17**. Install server JARs in the server's `mods` folder. Clients do not need these server-only JARs. The cash-exchange addon is a separate mod; installing it does not replace or modify NamelessMC Forge or Just Enough Drugz.

## Configuration

Start with the [Forge installation guide](forge/README.md) and [configuration reference](forge/CONFIGURATION.md). API credentials belong only in the server's private configuration. Optional website features require their corresponding website modules and configuration.

The cash-exchange addon is **disabled by default**. Its rate is **$1 in eligible Just Enough Drugz bills = 1 Store credit**. It accepts plain $1, $5, $10, $20, $50 and $100 bills from the player's hotbar/main inventory, requires an exact amount and a verified website account linked to the actual Minecraft UUID, and does not create change. See the [addon guide](addons/cash-exchange/README.md) and [configuration reference](addons/cash-exchange/CONFIGURATION.md) before enabling it.

Ambiguous cash exchanges are held for operator review. They are never automatically retried or refunded. The website's existing add-credits endpoint does not provide an idempotency key, so this is not a claim of distributed exactly-once delivery.

## Build and validation

Each project is an independent Gradle project. From `forge/` or `addons/cash-exchange/`, use Java 17 and run:

```powershell
.\gradlew.bat test build sourceBundle
```

On Linux, use `bash gradlew test build sourceBundle` instead. Outputs are generated under each project's `build/` directory.

The current sources passed **43 core tests** and **30 addon tests**. The addon also passed an isolated real Forge runtime check using a mock website, real Just Enough Drugz bills, normal LuckPerms initialization and native player-data saving. These checks used synthetic accounts and test exchanges; they do not claim validation of real paid transactions or public-server deployment.

## License and attribution

This repository uses [GNU GPL version 3](LICENSE). Preserve the individual license notices for vendored dependencies. Each Forge project includes its own license, upstream notices and third-party license records. This is an independently maintained Forge port, not an official NamelessMC upstream release.
