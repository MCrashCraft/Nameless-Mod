# Known loader limitations

## Forge 1.21: configuration watcher can prevent process exit

The selected official Forge **51.0.33** uses NightConfig **3.7.3**. In the local Windows/JDK 21.0.12.1 checks, the server initialized NamelessMC, executed status/help/reload commands and saved its disposable world after `stop`, but the Java process remained alive. Its full startup/commands/reload/shutdown check therefore **does not pass**.

A fresh control used the same official Forge libraries, an empty `mods` folder, fresh configuration and a fresh disposable world. It reproduced the hang without NamelessMC: every dimension finished saving, yet the process needed termination after a 90-second shutdown allowance. The remaining non-daemon scheduled worker matched NightConfig's configuration watcher. A separate thread-start recording traced that worker to the watcher before NamelessMC initialization. Sanitized control details are in [known-issues.json](known-issues.json).

The same watcher failure is described in the [upstream report](https://github.com/neoforged/NeoForge/issues/1626). The [NeoForge correction](https://github.com/neoforged/NeoForge/pull/1757) explains the lifecycle issue; it does not establish that this older Forge release contains the correction.

The Forge 1.21 mod still has its own native compilation, all 46 unit assertions and artifact audit. Those results are separate from the failed unassisted process-exit check. This port leaves the official loader and other mods' watchers alone. Its production JAR contains no forced process-exit workaround. The disposable native JUnit fixture may exit only after its server-stopped event and server-thread termination; that test-only fixture is excluded from the distributed mod.

The observation applies to the exact local environment and loader recorded above. Other platforms and loader versions are not certified by this control. Consult the [validation table](VALIDATION.md) for each exact target.
