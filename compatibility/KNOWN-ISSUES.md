# Known loader limitations

## Forge 1.21: configuration watcher can prevent process exit

The selected official Forge **51.0.33** uses NightConfig **3.7.3**. In the local Windows/JDK 21.0.12.1 checks, the server initialized NamelessMC, executed status/help/reload commands and saved its disposable world after `stop`, but the Java process remained alive. Its full startup/commands/reload/shutdown check therefore **does not pass**.

A fresh control used the same official Forge libraries, an empty `mods` folder, fresh configuration and a fresh disposable world. It reproduced the hang without NamelessMC: every dimension finished saving, yet the process needed termination after a 90-second shutdown allowance. The remaining non-daemon scheduled worker matched NightConfig's configuration watcher. A separate thread-start recording traced that worker to the watcher before NamelessMC initialization. Sanitized control details are in [known-issues.json](known-issues.json).

The same watcher failure is described in the [upstream report](https://github.com/neoforged/NeoForge/issues/1626). The [NeoForge correction](https://github.com/neoforged/NeoForge/pull/1757) explains the lifecycle issue; it does not establish that this older Forge release contains the correction.

The Forge 1.21 mod still has its own native compilation, all 46 unit assertions and artifact audit. Those results are separate from the failed unassisted process-exit check. This port leaves the official loader and other mods' watchers alone. Its production JAR contains no forced process-exit workaround. The disposable native JUnit fixture may exit only after its server-stopped event and server-thread termination; that test-only fixture is excluded from the distributed mod.

The observation applies to the exact local environment and loader recorded above. Other platforms and loader versions are not certified by this control. Consult the [validation table](VALIDATION.md) for each exact target.

## Forge 1.21.9: conditional configuration-watcher shutdown hang

The selected Forge **59.0.5**, with NightConfig **3.7.4**, also failed its unassisted shutdown check after successful NamelessMC initialization, commands, reload and world saving. Disabling JLine for the piped test console did not resolve it. A post-stop thread snapshot retained a NightConfig watcher and its non-daemon scheduled worker; NamelessMC workers and the server thread had ended.

The control results distinguish the condition: a fresh empty-mod server stopped normally when left alone. A second fresh empty-mod server reproduced the hang after a harmless write of the existing bytes to its watched `forge-common.toml`. It saved every dimension but needed termination after 90 seconds. This matches the configuration-change trigger described in the upstream report linked above. The test did not observe an explicit configuration-reload log message, so it makes no separate claim about that callback.

This is a conditional loader limitation in the tested environment. It is recorded separately from the mod's passing 46 native assertions and is not counted as a clean process-exit result. No production forced-exit or global-watcher workaround is bundled.
