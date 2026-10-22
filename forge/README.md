# NamelessMC Forge — Unofficial

**0.1.1 beta** · Minecraft **1.20.1** · Forge **47.4.16** · Java **17**

A server-only Forge port of the shared [NamelessMC server plugin](https://github.com/NamelessMC/Nameless-Plugin). It connects a Minecraft server to a compatible NamelessMC v2 website through the website's API.

This is an independent, unofficial port. It is not endorsed by NamelessMC. Players do not need to install this JAR.

## Features

- Register website accounts and verify Minecraft account links using commands.
- Read website notifications, show join notifications and registration reminders when enabled.
- Submit player reports and retrieve website account information.
- Display website announcements and send server/player information when enabled.
- Send effective LuckPerms groups from Minecraft to the website, including applicable inherited groups and contexts.
- Keep website requests off the server tick thread, cancel obsolete work on reload/shutdown, and preserve rich account links in Minecraft 1.20.1 chat.
- Preserve command roots owned by other mods, with namespaced NamelessMC command alternatives.
- Optional Store fulfillment with synchronous command-result checks and a durable delivery journal that holds failed or uncertain deliveries for review.

Website API features need a configured API URL and key. The release contains neither. A successful build or server startup does not establish that a real website is connected.

## Requirements

| Component | Requirement |
| --- | --- |
| Minecraft server | Java Edition 1.20.1 |
| Loader | Forge 47.4.16; other 47.x versions have not been established by this release's tests |
| Java | 17 |
| Website | A compatible NamelessMC v2 installation with its API enabled; the newer group-sync API requires 2.1.0+ |
| Permissions | Optional LuckPerms for Forge; integration target tested is 5.4.102. Without it, self-service commands use public defaults and administrative commands use operator defaults. |
| Clients | No NamelessMC Forge JAR required |

## Install and configure

1. Put **`namelessmc-forge-1.20.1-0.1.1.jar`** in the server's `mods` folder. Use the complete JAR, not the `-slim.jar` build artifact.
2. Start the server once to generate `config/namelessforge/`.
3. Enter the website's API URL, API key and Minecraft server ID in `config/namelessforge/main.yaml`. Obtain the exact values from the NamelessMC StaffCP.
4. Run `/nlpl reload`, then `/namelessforge:status` as an operator. The status command uses cached connection state and does not make a website request itself.
5. Enable only the website features you intend to use. Periodic data sending, group sync, join messages, announcements, Store and Websend integrations are disabled by default.

See [CONFIGURATION.md](CONFIGURATION.md) for commands, permission nodes, setup and troubleshooting. Keep private server configuration and API keys out of a public modpack export or source archive.

## Default commands

| Command | Purpose |
| --- | --- |
| `/register <email> [username]` | Create a website account; complete any website/email validation it requires. |
| `/verify <code>` | Verify the current player's Minecraft integration link. A privileged console can also specify an online player name. |
| `/notifications` | Read the current player's website notifications. |
| `/report <online-player> <reason>` | Send a player report to the website. |
| `/webinfo` | View the current player's account and public profile fields. Named account lookups require the separate staff permission. |
| `/nlpl reload` | Reload configuration and restart integration tasks. |
| `/nlpl last_error` | Print the last recorded integration error to the server console. |
| `/namelessforge:status` | Show initialization, cached website state and LuckPerms availability; operator level 2 required. |

The normal commands also have `namelessforge:` aliases, for example `/namelessforge:register` and `/namelessforge:nlpl reload`. If another mod owns a bare name, the port uses its namespaced alternative. Command-name changes in `commands.yaml` require a server restart; ordinary configuration changes still use `/nlpl reload`.

`/webinfo <username>` and website-name completion require `namelessmc.command.userinfo.others`, which defaults to operator level 2. This staff permission also enables detailed profile fields. Ordinary players use `/webinfo` with no arguments and do not receive private custom profile fields.

## Feature limits in this beta

The Bukkit-only PlaceholderAPI integration and the Bukkit user-sync whitelist/ban policy have not been ported. The shared `sync-ban-to-website` option has no Forge ban-event hook. Those inherited configuration keys do not enable those features on Forge. This port makes no automatic whitelist or ban-policy changes.

Optional Store and Websend command/log integrations remain disabled by default. Store settings live in `modules.yaml`, separately from the API credentials in `main.yaml`. Store delivery checks the actual synchronous server-command completion before acknowledging a delivery to the website; accepting or queuing a command alone is insufficient. It also journals deliveries so a failed website acknowledgment can be retried without granting the item again. Failed or interrupted deliveries remain held for manual review. This is not a guarantee of exactly-once delivery across a game-server/website crash boundary, or of completion of a mod command's later asynchronous work. See the Store setup and recovery instructions in [CONFIGURATION.md](CONFIGURATION.md).

Store execution passed an isolated Forge runtime against a mock website. The same release subsequently connected to a real Store website on the development server and completed an automatic empty-queue fetch. Real paid-order fulfillment remains unverified; Websend has not received equivalent runtime coverage. Do not point a development consumer at the public Store connection's paid queue. Bukkit-specific placeholder leaderboards and plugin hooks are not Forge features.

## Verification

Version 0.1.1 passed **43 automated tests**, covering scheduler/reload boundaries, API and timer hardening, LuckPerms group semantics, permissions, command argument policy, rich-message conversion, Store command results, identity validation and durable receipt recovery. The profile-privacy regressions verify that denied named lookups send no HTTP, private profile notes stay hidden from self-service views, and detailed views/name completion require the staff permission.

The exact 0.1.1 release JAR (`SHA-256: 5999f8bd33837a135068593c60e3718f90844c40953e1db6cd2a11ecee6c9f61`) also passed an isolated, loopback-only Forge 47.4.16 / Java 17 runtime with LuckPerms 5.4.102. Actual scoreboard changes and durable `EXECUTED` receipts were observed before the mock website returned a successful acknowledgment. An unknown command and an offline customer's online-only command received no acknowledgment. Duplicate pending IDs, an acknowledgment outage, configuration reload and a clean restart across two owned JVMs did not grant the scoreboard reward again; acknowledgment recovered without a reload. Both JVMs stopped cleanly. These controlled tests used a disposable world and a mock Store, with no paid order, real website, player join, existing-world edit or public/development-server change.

The 0.1.0 release JAR also passed isolated, headless Forge 47.4.16 tests both with LuckPerms 5.4.102 and without LuckPerms. A local mock website exercised API connection, configuration reload, server-data sending, website outage/recovery and clean shutdown. Console commands remained responsive during the simulated outage. These tests did not contact a real website.

The exact 0.1.1 release completed startup in the 496-JAR MCrashCraft development server, preserving the other 495 JAR hashes and loading all 33 expected dimensions. The settled empty-server sample measured 20 TPS; the API was connected and LuckPerms available. Store connection 1 was then enabled with a one-minute interval, and its first automatic fetch succeeded with zero pending commands and no Store warnings. Private credentials remained outside the release. No client gameplay performance, real paid purchase, website account registration, account linking, website group synchronization or player join is claimed by these checks. This README does not claim installation on a public server.

## Build from source

Use a Java 17 JDK and the included Gradle wrapper. From this directory in PowerShell:

```powershell
.\gradlew.bat test build
.\gradlew.bat sourceBundle
```

The complete server mod is `build/libs/namelessmc-forge-1.20.1-0.1.1.jar`. `build/libs/*-slim.jar` lacks the bundled integration libraries and is not the installable release. The corresponding source ZIP is written to `build/distributions/` and contains the adapter, vendored source, build files, documentation and licenses.

## Source and licensing

Distributed under **GPL-3.0** with the original license and corresponding source. The vendored Nameless Java API retains its LGPL-3.0 license; the Kyori event API and other bundled libraries retain their own notices and licenses. The mod JAR includes dependency license material under `META-INF/licenses/`.

[UPSTREAM-NOTICE.md](UPSTREAM-NOTICE.md) records exact upstream commits and provenance. See [CHANGELOG.md](CHANGELOG.md) for this port's changes. This is a new Forge project and must not be represented as an official NamelessMC release.
