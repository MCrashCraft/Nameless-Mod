# NamelessMC Mod for Forge

**0.2.0** · Server-only integration · Exact-version native Forge builds

Native builds cover published Forge releases within Minecraft **1.20.1–26.3**. Use the [download table](../RELEASES.md) to select an exact target from the [0.2.0 release](https://github.com/MCrashCraft/Nameless-Mod/releases/tag/v0.2.0), and consult the [coverage matrix](../compatibility/README.md) and [validation results](../compatibility/VALIDATION.md). Forge has no published loader for Minecraft 1.20.5 or 1.21.2.

A server-only Forge port of the shared [NamelessMC server plugin](https://github.com/NamelessMC/Nameless-Plugin). It connects a Minecraft server to a compatible NamelessMC v2 website through the website's API.

This is an independent, unofficial port. It is not endorsed by NamelessMC. Players do not need to install this JAR.

## Features

- Register website accounts and verify Minecraft account links using commands.
- Read website notifications, show join notifications and registration reminders when enabled.
- Submit player reports and retrieve website account information.
- Display website announcements and send server/player information when enabled.
- Send effective LuckPerms groups from Minecraft to the website, including applicable inherited groups and contexts.
- Keep website requests off the server tick thread, cancel obsolete work on reload/shutdown, and preserve rich account links using the exact target game's chat schema.
- Preserve command roots owned by other mods, with namespaced NamelessMC command alternatives.
- Optional Store fulfillment with synchronous command-result checks and a durable delivery journal that holds failed or uncertain deliveries for review.

Website API features need a configured API URL and key. The release contains neither. A successful build or server startup does not establish that a real website is connected.

## Requirements

| Component | Requirement |
| --- | --- |
| Minecraft server | One exact Java Edition release with a published Forge target in the compatibility matrix |
| Loader | Matching Forge version; consult the generated dependency minimum and completed runtime tests |
| Java | 17 through 1.20.4; 21 from 1.20.5 through 1.21.11; 25 for 26.x |
| Website | A compatible NamelessMC v2 installation with its API enabled; the newer group-sync API requires 2.1.0+ |
| Permissions | Optional LuckPerms for Forge; integration target tested is 5.4.102. Without it, self-service commands use public defaults and administrative commands use operator defaults. |
| Clients | No NamelessMC Forge JAR required |

## Install and configure

1. Put the final **`namelessmc-forge-<minecraft>-0.2.0.jar`** matching your exact game version in the server's `mods` folder. Use the complete JAR, not the `-slim.jar` build artifact. Check the validation results before installation.
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

## Feature availability

The Bukkit-only PlaceholderAPI integration and the Bukkit user-sync whitelist/ban policy have not been ported. The shared `sync-ban-to-website` option has no Forge ban-event hook. Those inherited configuration keys do not enable those features on Forge. This port makes no automatic whitelist or ban-policy changes.

Optional Store and Websend command/log integrations remain disabled by default. Store settings live in `modules.yaml`, separately from the API credentials in `main.yaml`. Store delivery checks the actual synchronous server-command completion before acknowledging a delivery to the website; accepting or queuing a command alone is insufficient. It also journals deliveries so a failed website acknowledgment can be retried without granting the item again. Failed or interrupted deliveries remain held for manual review. This is not a guarantee of exactly-once delivery across a game-server/website crash boundary, or of completion of a mod command's later asynchronous work. See the Store setup and recovery instructions in [CONFIGURATION.md](CONFIGURATION.md).

Store fulfillment was exercised against a mock website in the earlier 0.1.1 Forge release; its historical validation also included a real-website empty-queue fetch. Those checks do not certify real paid-order fulfillment or every 0.2.0 target. Websend has not received equivalent runtime coverage. Keep development consumers on separate test connections. Detailed historical results are in [CHANGELOG.md](CHANGELOG.md).

## Validation

Every published Forge target in version 0.2.0 passed its native 46-test suite and package audit. Packaged-JAR checks exercise initialization, status, overlapping help/reload commands and shutdown using the exact game and loader in a disposable world. The [validation table](../compatibility/VALIDATION.md) records each tested artifact hash.

Forge 1.21 and 1.21.9 have independently reproduced configuration-watcher shutdown limitations, including empty-mod control cases. Their unassisted process-exit checks remain failed; see [known loader limitations](../compatibility/KNOWN-ISSUES.md). Native unit-test success does not override those results.

These checks do not establish real purchases, player logins or compatibility with every other mod. Earlier 0.1.0 and 0.1.1 mock-website and Store results are preserved in the [changelog](CHANGELOG.md) as historical evidence.

## Build from source

Use the repository's exact-target build driver with the JDKs shown by `--plan`. From the repository root:

```powershell
python scripts/build.py --loader forge --minecraft 1.20.1 --source-bundle
python scripts/build.py --loader forge --minecraft 26.3 --source-bundle --accept-test-eula
```

The complete server mod is `forge/build/<minecraft>/libs/namelessmc-forge-<minecraft>-0.2.0.jar`. A `-slim.jar` lacks bundled integration libraries and is not the installable release. The corresponding source ZIP is under `forge/build/<minecraft>/distributions/` and includes every shared and loader project needed to rebuild.

For Minecraft 1.21 and later, the native JUnit suite requires initialized Forge registries and runs after a disposable loopback server starts. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA) before passing `--accept-test-eula`. The loader-aware task requires all 46 tests to execute successfully, with no failures, skipped or aborted tests. The test entry, its metadata and JUnit libraries stay outside the production mod JAR. A separate packaged-JAR startup check validates the distributed artifact. `--skip-tests` builds without running that test server and does not certify a test pass.

## Source and licensing

Distributed under [GPL-3.0](../LICENSE) with the original license and corresponding source. The vendored Nameless Java API retains its LGPL-3.0 license; the Kyori event API and other bundled libraries retain their own notices and licenses. The mod JAR includes dependency license material under `META-INF/licenses/`.

[UPSTREAM-NOTICE.md](UPSTREAM-NOTICE.md) records exact upstream commits and provenance. See [CHANGELOG.md](CHANGELOG.md) for release changes. This is an independently maintained port and is not an official NamelessMC release.
