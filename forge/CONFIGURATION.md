# Configuring NamelessMC Mod 0.2.0

This guide covers the shared configuration for the Forge, NeoForge and Fabric-compatible server adapters. Configuration lives under **`config/namelessforge/` relative to the server's working directory**, including on Quilt. The same directory name is retained across loaders. It is separate from a world's `serverconfig` folder.

## Connect your website

On the NamelessMC website:

1. Open **StaffCP → Configuration → API**, enable the API, and obtain its API URL and key.
2. Open **StaffCP → Integrations → Minecraft → Minecraft Servers** and obtain the ID of the server this integration represents.
3. Copy those values into `config/namelessforge/main.yaml` on the Minecraft server. Use the exact API URL shown in StaffCP, including its path; a website homepage URL is insufficient.

The release's initial settings are intentionally blank:

```yaml
api:
  url: ""
  key: ""
  server-id: 0
  debug: false
  timeout: 'PT10S'
  offline-uuids: false
```

Replace the empty strings and zero with the private values for your site. Keep the key in private server configuration. Do not put it into a public modpack export, source ZIP, screenshot or chat message. Leave `debug` off for normal use.

Run **`/nlpl reload`** after saving changes. **`/namelessforge:status`** requires operator level 2 and reports one of these cached states:

- **not initialized**: the integration did not initialize; review the server's startup log.
- **not configured**: an API URL or key is missing.
- **connecting or unavailable**: configuration exists, but no working cached API connection is currently available.
- **connected**: API initialization established a working cached connection. This does not verify every account or optional module operation.

The status command never prints the key. `/nlpl last_error` prints the last recorded integration error to the server console for troubleshooting. Use `/namelessforge:nlpl` if another mod owns `/nlpl`.

Keep `offline-uuids: false` on a normal authenticated Java server. Only enable offline UUID handling when the website is deliberately configured to use the same identity scheme. Changing the scheme changes the identifiers used for website account links.

## Enable the features you want

The generated template starts these features disabled. API setup alone does not opt players into periodic notifications or group/data sending.

| `main.yaml` setting | Effect when enabled |
| --- | --- |
| `join-notifications` | Tell joining players when they have website notifications. |
| `not-registered-join-message` | Show a registration reminder to players without a linked website account. |
| `announcements.enabled` | Periodically display website announcements in chat. |
| `server-data-sender.enabled` | Send platform, version, capacity, online-player, memory and supported permission information to the website. |
| `group-sync.enabled` | Send changed effective Minecraft/LuckPerms group memberships to the website. |

Configured durations use ISO-8601 strings, such as `PT30S` for 30 seconds or `PT10M` for ten minutes. Keep sensible intervals rather than polling every tick. Group and server-data tasks begin after their configured interval; they are not evidence of failure merely because nothing is sent immediately after reload.

For the newer group-sync endpoint, the website must be NamelessMC 2.1.0 or later. Select the appropriate group-sync server under **StaffCP → Integrations → Minecraft → Minecraft Servers**, and configure mappings under **StaffCP → Configuration → API → Group sync**. This port sends Minecraft groups to the website; it does not assign LuckPerms groups from website ranks.

The LuckPerms adapter uses effective inherited groups and applicable query contexts instead of sending every raw inheritance node. Install the official LuckPerms build matching your server platform if you need this integration. The earlier Forge 1.20.1 integration checks used **LuckPerms Forge 5.4.102**; that result does not certify every loader/version combination. LuckPerms is optional and is not bundled into the mod.

The data sender does not replace NamelessMC's separate server-ping configuration. Configure website server ping/status independently where the website requires it.

## Commands and aliases

The generated `commands.yaml` contains these enabled names:

```yaml
get-notifications: 'notifications'
register: 'register'
report: 'report'
user-info: 'webinfo'
verify: 'verify'
plugin: 'nlpl'
```

Comment a line to disable that command, or change its value to choose another root. Names must be a single command name, without a leading slash, spaces or arguments. **Restart the server after changing command names or enabling/disabling commands.** `/nlpl reload` reloads feature settings and language/configuration, but does not replace an already registered command tree.

Every enabled command gets a `namelessforge:` alias. For example:

```text
/namelessforge:register player@example.test
/namelessforge:verify YOUR_WEBSITE_CODE
/namelessforge:notifications
/namelessforge:report PlayerName report reason
/namelessforge:webinfo
/namelessforge:nlpl reload
```

If a bare root is already owned by another mod, this port keeps that existing command and uses its namespaced alias. It logs the usable name and adjusts its own in-memory usage/join hints without rewriting `commands.yaml`. Unusual duplicate configured names may use a deterministic feature-key fallback, which is also identified in the startup log.

`/verify <code>` links the current player. A privileged server console can use `/verify <code> <online-player>`; a player cannot use that console-only form to verify another account. `/register <email> [username]` uses the player's Minecraft name when the optional name is omitted. The `register-custom-username` setting controls whether players may select a different website name.

`/report` currently resolves an online Minecraft player name. The website handles report acceptance and any account requirements. Ordinary players use `/webinfo` with no arguments to view their own account and public custom profile fields. Every one-argument account lookup requires `namelessmc.command.userinfo.others`, even if the typed name is the player's own; use the zero-argument form for self-service. That staff permission also allows private custom fields and website-name completion. Its default is operator level 2.

## Permissions

LuckPerms checks use these existing NamelessMC permission nodes. An explicit LuckPerms denial overrides the default. When a node is undefined or LuckPerms is unavailable, the defaults below apply.

| Permission node | Used for | Default |
| --- | --- | --- |
| `namelessmc.command.register` | Register an account | Allowed |
| `namelessmc.command.verify` | Verify the player's account link | Allowed |
| `namelessmc.command.getnotifications` | Read the player's notifications | Allowed |
| `namelessmc.command.report` | Submit a player report | Allowed |
| `namelessmc.command.userinfo` | Retrieve the current player's account and public profile fields | Allowed |
| `namelessmc.command.userinfo.others` | Named account lookups, detailed/private fields and website-name completion | Operator level 2 |
| `namelessmc.command.nameless` | `/nlpl` administrative actions | Operator level 2 |
| `namelessmc.report.notify-broadcast` | Receive staff report notifications | Operator level 2 |
| `namelessmc.command.store-view-credits` | Optional Store balance command | Allowed if the command is enabled |
| `namelessmc.command.store-view-credits.others` | View another account's Store balance | Operator level 2 |
| `namelessmc.command.store-change-credits` | Add/remove Store credits | Operator level 2 |
| `namelessmc.command.suggest` | Optional Suggestions command | Allowed if the command is enabled |

`/namelessforge:status` separately requires vanilla operator level 2. Non-player command sources need permission level 4 to be treated as the privileged console; low-privilege command blocks do not become administrative website consoles.

## Optional website modules

Store command execution, Websend command execution and Websend log sending are disabled by default. Their settings belong in **`config/namelessforge/modules.yaml`**, not `main.yaml`. Enable them only when the matching website module exists and its configuration is ready. Historical 0.1.1 Forge validation covered a mock Store and a real-website empty-queue fetch. Real paid-order fulfillment and equivalent Websend runtime coverage remain unverified; consult the [version-specific validation results](../compatibility/VALIDATION.md).

### Store fulfillment

First configure the API credentials and server ID in `main.yaml`. In the website's Store StaffCP, create or select the Minecraft command connection intended for this server and obtain its connection ID. The example below uses connection **1**; it is a configuration example, not a credential or a claim that the release is connected to a real Store.

In `modules.yaml`:

```yaml
store:
  connection-id: 1
  command-executor:
    enabled: true
    interval: PT1M
```

Save and run `/nlpl reload`. The generated release defaults remain `connection-id: 0` and `enabled: false`. Keep Websend execution/log sending disabled unless you are separately setting up that module. **Use one running consumer per Store connection.** A development server must use a separate test connection or an isolated mock; it must not consume the public server's paid queue.

Store fulfillment uses privileged server-console commands supplied by the trusted Store configuration. Restrict who can edit Store products, commands and API settings. Use commands whose game-side work completes synchronously, such as a direct item grant or scoreboard update. The executor runs each prepared command immediately on the server thread and checks Brigadier's actual completion callback before acknowledging it. A numeric result of zero can still be successful; queuing, parsing, a canceled command event, an exception or a failed execution is not successful delivery. A mod command that returns success before its own asynchronous job finishes needs a separate receipt or manual verification; this adapter cannot prove that later job completed.

Only `{username}` and `{uuid}` identity substitutions are supported. A substituted username must be a valid Java Minecraft name. Online UUID resolution uses the exact Minecraft identity, without fuzzy-name fallback; `{uuid}` is the player's actual game UUID even when the website uses offline UUIDs. UUID substitutions use the canonical dashed UUID format; product commands that expect an undashed identifier must be reviewed. Offline UUID substitutions require a canonical customer UUID. Missing identities, unresolved simple placeholders, empty or multiline commands, NUL characters and commands over 4,096 characters are held rather than dispatched. Ordinary JSON/NBT braces do not themselves count as an unresolved identity placeholder.

Delivery receipts are stored privately at **`config/namelessforge/store-delivery/connection-<id>.json`**. Keep this journal with the server's private integration data and include it in server backups. It contains hashed delivery identity/payload information, not raw customer details or command text.

| Journal state | Meaning and recovery |
| --- | --- |
| `PREPARED` | Written before execution. If this remains after interruption, the result is uncertain. Hold for staff review; do not automatically grant again. |
| `EXECUTED` | Synchronous command completion succeeded and was recorded. A later poll retries the website acknowledgment without executing the command again; no reload is required. |
| `ACKNOWLEDGED` | The website acknowledgment completed and was recorded. The delivery is not executed again. |
| `FAILED` | The command returned failure or threw. It is held for staff review, since some commands can perform partial work before failing. |

**Never delete or edit the journal just to force a retry.** A crash can occur between a Minecraft effect and the journal write, or between a website acknowledgment and its local receipt. The journal fails closed on uncertain outcomes; it does not provide an atomic transaction or an exactly-once guarantee across the game server and website. Review the command's actual effects and the website's queue before any manual recovery. Corrupt/unreadable or oversized journals, changed payloads for an existing delivery identity, and oversized queue responses stop affected processing for review rather than bypassing these checks. The bounds are 256 customers and 256 commands per response, 20,000 receipts and an 8 MiB journal.

### Optional credit, Suggestions and Websend commands

The optional Store commands are commented out in `commands.yaml`:

```yaml
#store-change-credits: 'changecredits'
#store-view-credits: 'credits'
#suggest: 'suggest'
```

The credit commands are separate from product fulfillment. You do **not** need to uncomment `changecredits` or `credits` to run the Store command executor. Leave them disabled unless website-credit management is separately intended. The Suggestions command also needs the corresponding website module. Enabling a command does not install its website module. Store and Websend are not required for ordinary registration, linking or notifications. The new durable Store receipt path does not apply to the older Websend executor.

## Platform-specific options

The shared template retains settings used by other upstream platforms. The native mod adapters do not implement:

- Bukkit PlaceholderAPI, placeholder retrieval, or Bukkit placeholder leaderboards.
- `user-sync`, including website-driven whitelist changes, bans and pardons.
- `sync-ban-to-website`, because no native ban-event adapter is registered.
- Bukkit-only maintenance/plugin hooks.

Changing those inherited keys does not activate those features. The mod does not automatically change a server's whitelist or ban policy.

## Testing and troubleshooting

Version 0.2.0 has 67 native artifacts, each passing 46 tests and a package audit. The suite covers scheduling and reload boundaries, bounded API behavior, permission policy, native rich-chat conversion, profile privacy, Store command completion and durable receipt recovery. The [validation table](../compatibility/VALIDATION.md) records exact packaged-server results; the [known loader limitations](../compatibility/KNOWN-ISSUES.md) document failed Forge 1.21 and 1.21.9 shutdown checks.

Earlier Forge releases were also checked against a mock website and mock Store; their historical results are in the [changelog](CHANGELOG.md). A local build, startup or empty-queue fetch does not establish real paid-order fulfillment. Test each intended website feature after configuring the private connection, using a separate Store test connection when applicable.

For connection problems, check the API URL/key/server ID in StaffCP and on the server, the website's compatible NamelessMC version, website reachability from the server, and the cached status after reload. Keep website requests off the tick thread; do not work around a connection problem by enabling unrelated access-policy or command-execution options. Real-website registration, linking and optional modules still need testing after private API setup.
