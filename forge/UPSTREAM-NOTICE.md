# Unofficial NamelessMC Forge port

This is a separate, unofficial Forge 1.20.1 project. It is not an official NamelessMC release and is not endorsed by NamelessMC.

The included Nameless-Plugin shared core is derived from https://github.com/NamelessMC/Nameless-Plugin commit `1ef66b87d04513759574b6c50eae274ff0924c45` and is licensed under GPL-3.0. The root LICENSE contains the original license. Changes for the Forge adapter and the corresponding source are distributed under GPL-3.0.

The included Nameless Java API sources are from https://github.com/NamelessMC/Nameless-Java-API commit `77f736762a60b858fc55c797e026c3b8a59766b4` under LGPL-3.0. Its original license is preserved at `vendor/nameless-java-api/LICENSE` and in the mod's META-INF/licenses.

The Kyori event API sources are from https://github.com/KyoriPowered/event commit `d4df2835ced23be61eeeb05f4c60f96b9995c443` under the MIT license. Its original license and source headers are retained.

Runtime library dependencies are shaded into a private namespace to avoid conflicts with Forge and other mods: Adventure 4.26.1 (MIT), Configurate 4.2.0 (Apache-2.0), SnakeYAML (Apache-2.0), GeAnTyRef (Apache-2.0), Methanol 1.9.0 (MIT), Gson 2.14.0 (Apache-2.0), Guava 33.4.8-jre (Apache-2.0), and bStats 3.2.1 (MIT). Their upstream license files in the dependency JARs are retained by the shaded build. Additional complete license texts and original MIT copyright notices are included in `third-party-licenses`, with download URLs and SHA-256 provenance; those are also included in the mod JAR. Supporting annotation libraries (JetBrains annotations, Error Prone, JSpecify, JSR-305 and J2ObjC) are redistributed under Apache-2.0. LuckPerms and Forge are external dependencies and are not bundled.

The matching source ZIP contains the adapter sources, vendored upstream sources, Gradle build and wrapper, provenance notice and licenses. Website API keys and private server configuration must never be included in a release.
