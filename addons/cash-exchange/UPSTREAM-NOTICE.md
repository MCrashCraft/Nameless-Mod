# Source and provenance

This is an independent, unofficial addon for NamelessMC Forge 0.1.1+. It does not modify or redistribute the NamelessMC core JAR, the Store website module or Just Enough Drugz.

Built against Minecraft 1.20.1 / Forge 47.4.16 / Java 17. The included ForgeGradle and Gradle wrapper were retained from the independently built NamelessMC Forge adapter source. The addon source is GPL-3.0-or-later. Bundled libraries are Configurate Core/YAML 4.2.0 (Apache-2.0), GeAnTyRef 1.3.16 (Apache-2.0), Gson 2.14.0 (Apache-2.0), Error Prone annotations 2.48.0 (Apache-2.0), and Kyori Option 1.1.0 (MIT). Configurate YAML itself contains an internally relocated and patched SnakeYAML implementation (Apache-2.0).

Complete dependency license texts, including Kyori's copyright notice, are retained in `third-party-licenses/` and packaged under `META-INF/licenses/dependencies/`. These exact texts were reused from the previously verified NamelessMC Forge dependency-license set and checked against the current artifact versions/POMs and bundled classes. `third-party-licenses/provenance.json` preserves the upstream retrieval URLs, byte lengths and SHA-256 hashes, and identifies the matching components. The corresponding source bundle includes these files. No dependency license is inferred merely from the addon's GPL license.

The six Just Enough Drugz currency item IDs were verified in the official JEDZ-1.20.1-v1.2.1.jar (SHA-256 08f47767696b14fdf64e96181925c08ff430b088a83cbd09290f2ce33f02182d). The original JAR is not included in this addon.

The Store credit endpoint was checked against partydragen/Nameless-Store commit 16e204735eebc208a91777aba082c242b9aa696d, AddCreditsEndpoint.php and Customer.php. It has no exchange idempotency key; uncertain outcomes are held instead of automatically retried or refunded. The existing Java API's generic POST retry method is not used.
