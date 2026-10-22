# Changelog

## 0.2.0

Native server adapters for Forge, NeoForge and Fabric, with the same Fabric-compatible JAR tested separately on Quilt. Exact-version builds cover the 23 stable Minecraft releases from 1.20.1 through 26.3 where each loader is published.

### Added

- Shared Minecraft bindings for version-specific command callbacks, permissions, profile names and native rich-chat codecs.
- Exact-target build tooling, complete corresponding-source archives and packaged metadata/license audits.
- Native lifecycle handling across all three adapters, including reload and shutdown guards for queued work.
- Cross-version rich-chat safety and scheduler regression coverage, bringing each native suite to 46 tests.
- Separate packaged-server status, overlapping help/reload and shutdown checks for Fabric and Quilt.

### Validation

All 67 distinct artifacts passed the 46-test suite and package audit. The 90 published loader combinations produced 88 clean packaged-runtime results. Forge 1.21 and 1.21.9 retain independently reproduced configuration-watcher shutdown limitations; those checks remain failed unassisted exits. See the [validation table](../compatibility/VALIDATION.md) and [known loader limitations](../compatibility/KNOWN-ISSUES.md) for exact versions and hashes.

API connection defaults remain blank, optional modules remain disabled by default, and the configuration directory remains `config/namelessforge/` across loaders. These checks do not certify real purchases, player logins or arbitrary mod combinations.

## 0.1.1 beta

Store fulfillment correction for the unofficial Forge 1.20.1 port. The exact 0.1.1 build passed 43 automated tests, an isolated Forge runtime against a mock Store, development-server startup and a real-website automatic empty-queue fetch. Real paid-order fulfillment remains unverified; the checks recorded under 0.1.0 apply to that earlier release.

### Fixed

- Execute Store fulfillment synchronously on the server thread and acknowledge only an actual successful Brigadier completion. Queue/parse acceptance, a canceled Forge command event or a positive integer alone no longer counts as delivery. A successful zero-valued command result remains valid.
- Reject Store dispatch from a non-privileged sender, an inactive audience provider or the wrong thread. Hold missing/invalid identities, unresolved identity placeholders, malformed commands and failed or uncertain results for review.
- Use exact online identity and actual game UUID for Store substitutions, without fuzzy-name fallback or accidentally substituting a transformed website UUID.

### Added

- A bounded, durable per-connection Store delivery journal with `PREPARED`, `EXECUTED`, `ACKNOWLEDGED` and `FAILED` states, atomic state transitions and payload identity checks.
- Retry a failed website acknowledgment for a recorded executed delivery without re-executing its command. Persist failed or interrupted delivery holds across restart/reload for manual review.
- Serialize Store polling, command execution and acknowledgment; reject stale reload/shutdown callbacks.
- Store command-result and recovery regressions, plus separate `modules.yaml` setup and journal recovery instructions. No credentials or live Store settings are bundled.

### Verification

- **43 automated tests pass**, including Store completion callbacks, identity/placeholder validation and durable delivery recovery.
- The exact 0.1.1 JAR (`SHA-256: 5999f8bd33837a135068593c60e3718f90844c40953e1db6cd2a11ecee6c9f61`) passed loopback-only Forge 47.4.16 / Java 17 runtime checks with LuckPerms 5.4.102 and a disposable world.
- A real scoreboard reward and durable execution receipt existed before a successful mock acknowledgment. Invalid commands and offline customers' online-only commands received no acknowledgment.
- Duplicate pending IDs, an acknowledgment outage, reload and a clean restart across two owned JVMs did not duplicate rewards. Acknowledgment recovered without reload or command re-execution; both JVMs stopped cleanly.
- No real website, paid order, player join, existing-world edit or public/development-server change was part of the isolated fixture.
- The exact release subsequently passed startup on the development server with 496 matching JAR hashes, 33 expected dimensions, working game/voice ports, a connected API, LuckPerms availability and a settled 20-TPS empty-server sample. The other 495 JARs and stopped world/settings remained intact during replacement.
- Store connection 1 completed a real-website automatic fetch after enabling one-minute polling. Its queue was empty and no Store warnings were observed. No real purchase was created by validation and no public deployment was performed.

Store remains disabled by default. Credit-management commands remain separate and disabled. The journal cannot make Minecraft and a remote website one atomic transaction, or prove later asynchronous work inside another mod's command completed. Only one consumer may use a Store connection. Do not test against a public paid queue or delete a journal to force a retry. Websend's older executor does not gain the new Store journal behavior.

## 0.1.0 beta

Initial unofficial, server-only Forge port for **Minecraft 1.20.1 / Forge 47.4.16 / Java 17**.

### Added

- Forge lifecycle, player-event, audience, command, scheduling and server-data adapters around the Nameless-Plugin shared core.
- Account registration and verification, notifications, player reports, account information, opt-in announcements and server data.
- Optional LuckPerms integration with effective inherited groups and applicable contexts; Forge 5.4.102 is the integration test target.
- Namespaced command alternatives that preserve roots already owned by other mods.
- `/namelessforge:status` for operator-level cached status without printing credentials.
- Minecraft 1.20.1-compatible rich chat, including working account links, colors and hover text. Executable click commands become suggestions; local-file and unsupported click actions are removed.
- GPL corresponding-source bundle, vendored upstream source, provenance notice and bundled dependency licenses.

### Adapted and corrected

- Keep website requests off the server tick thread; bound asynchronous work and invalidate stale callbacks during reload/shutdown.
- Keep player lookups and message delivery safe across worker threads and reconnects.
- Bound timer and HTTP configuration, handle reload generations, and guard group-sync retry state.
- Resolve effective LuckPerms membership instead of sending denied, expired or inapplicable raw inheritance nodes.
- Correct user-information lookup output and avoid resolving API-backed fields on the tick thread.
- Handle console verification of a missing online player without dereferencing a null target.
- Correct report server-ID lookup and reporter/reported identity ordering.
- Match administrative tab completion to the actual `last_error` command.
- Separate self-service account lookup from privileged named lookups with `namelessmc.command.userinfo.others`; hide private custom profile fields from ordinary self views and withhold website-name completion without that permission.
- Keep newly generated periodic website features and optional command/log executors disabled. API URL/key remain empty.

### Verification and limits

- **28 automated tests pass**, including actual Adventure-to-native Minecraft 1.20.1 component round trips and five profile-privacy command/API regressions.
- The exact release JAR passed isolated Forge runtime tests with and without LuckPerms 5.4.102, using a local mock website for API connection, reload, server-data sending, outage/recovery and clean shutdown.
- The exact release completed startup in the 496-JAR MCrashCraft development pack; all 495 original JAR hashes remained intact, 33 expected dimensions loaded, LuckPerms was detected and the website remained unconfigured.
- No real website-account operation, client gameplay performance or public deployment result is claimed here.
- No client JAR is required.
- Bukkit PlaceholderAPI, user-sync whitelist/ban policy and Forge ban-to-website event forwarding are not implemented.
- Optional Store and Websend code remains disabled by default and unverified against real website modules.

Exact upstream commits and dependency licenses are recorded in [UPSTREAM-NOTICE.md](UPSTREAM-NOTICE.md). This release is not an official NamelessMC build.
