# Local validation results

These results distinguish native compilation and unit tests from dedicated-server startup checks. The local runs use the packaged mod, blank website configuration, fresh disposable worlds and the exact game Java version. Status, overlapping help/reload commands and clean shutdown are checked. They do not certify paid purchases, player logins, every other mod or a production deployment.

Only a current 46-test pass with no failures and a matching tested JAR hash qualifies as complete. Older startup-only receipts do not qualify. Pending targets remain unverified.

| Minecraft | Java | Forge | NeoForge | Fabric | Quilt |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 | 17 | 46 tests + runtime pass | 46 tests + runtime pass | 46 tests + runtime pass | 46 tests + runtime pass |
| 1.20.2 | 17 | 46 tests + runtime pass | 46 tests + runtime pass | Pending | Pending |
| 1.20.3 | 17 | 46 tests + runtime pass | 46 tests + runtime pass | Pending | Pending |
| 1.20.4 | 17 | 46 tests + runtime pass | 46 tests + runtime pass | Pending | Pending |
| 1.20.5 | 21 | No published loader | 46 tests + runtime pass | Pending | Pending |
| 1.20.6 | 21 | 46 tests + runtime pass | 46 tests + runtime pass | Pending | Pending |
| 1.21 | 21 | [46 tests; loader shutdown issue](KNOWN-ISSUES.md) | 46 tests + runtime pass | Pending | Pending |
| 1.21.1 | 21 | 46 tests + runtime pass | 46 tests + runtime pass | Pending | Pending |
| 1.21.2 | 21 | No published loader | 46 tests; runtime pending | 46 tests + runtime pass | 46 tests + runtime pass |
| 1.21.3 | 21 | 46 tests; runtime pending | 46 tests; runtime pending | 46 tests; runtime pending | 46 tests; runtime pending |
| 1.21.4 | 21 | Pending | 46 tests; runtime pending | 46 tests + runtime pass | 46 tests + runtime pass |
| 1.21.5 | 21 | 46 tests + runtime pass | 46 tests; runtime pending | Pending | Pending |
| 1.21.6 | 21 | 46 tests + runtime pass | 46 tests; runtime pending | 46 tests + runtime pass | 46 tests + runtime pass |
| 1.21.7 | 21 | 46 tests + runtime pass | 46 tests; runtime pending | 46 tests + runtime pass | 46 tests + runtime pass |
| 1.21.8 | 21 | 46 tests + runtime pass | 46 tests; runtime pending | Pending | Pending |
| 1.21.9 | 21 | 46 tests; runtime pending | 46 tests; runtime pending | Pending | Pending |
| 1.21.10 | 21 | Pending | 46 tests; runtime pending | Pending | Pending |
| 1.21.11 | 21 | Pending | 46 tests + runtime pass | Pending | Pending |
| 26.1 | 25 | Pending | 46 tests + runtime pass | Pending | Pending |
| 26.1.1 | 25 | Pending | Pending | Pending | Pending |
| 26.1.2 | 25 | Pending | Pending | Pending | Pending |
| 26.2 | 25 | Pending | Pending | Pending | Pending |
| 26.3 | 25 | 46 tests; runtime pending | Pending | 46 tests + runtime pass | 46 tests + runtime pass |

Recorded: 2026-10-22T13:17:12+00:00. SHA-256 hashes and exact loader versions are in [validation.json](validation.json).

The [known loader issue](KNOWN-ISSUES.md) remains a failed unassisted shutdown check. An empty-mod control reproduced it; it is not counted as a clean runtime pass.

GitHub Actions was unavailable for these local runs. The checked-in workflow can repeat the matrix when the repository can run Actions jobs. No cloud test success is claimed.
