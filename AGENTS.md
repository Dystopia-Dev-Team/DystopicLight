# AGENTS.md — DystopicLight (Arclight fork)

## Project

Bukkit server implementation running on Forge / NeoForge / Fabric mod loaders (Minecraft 1.21.1).
Fork of [Arclight](https://github.com/IzzelAliz/Arclight). Built with Architectury Loom + custom buildSrc plugin.

## Build

- **Java 21**, Gradle multi-module
- Version: `1.0.2-SNAPSHOT`, versionName `feudal-kings`
- Lombok is used throughout — annotate new classes with `@Data`/`@Getter`/`@Setter`/`@UtilityClass` etc.
- Heavy build: the ArclightGradlePlugin downloads BuildTools and builds Spigot + remaps mappings at configuration time. First build is slow; cache is in `.gradle/arclight/`.

### Full CI build (push)
```
./gradlew cleanBuild build collect uploadFiles --no-daemon --stacktrace
```

### PR check
```
./gradlew cleanBuild build collect --no-daemon --stacktrace
```

### Build jars only
```
./gradlew cleanBuild build collect
```
Output in `build/libs/` — three jars: `arclight-forge-{mc}-{ver}`, `arclight-neoforge-{mc}-{ver}`, `arclight-fabric-{mc}-{ver}`.

### AppVeyor (legacy, branch 1.19-era)
Due to a MixinGradle bug, builds in CI run twice:
```
gradlew cleanBuild idea remapSpigotJar --no-daemon -i --stacktrace --refresh-dependencies
gradlew build --no-daemon -i --stacktrace
gradlew build collect --no-daemon -i --stacktrace
```

## Modules

| Module | Role |
|---|---|
| `arclight-common` | Shared Bukkit → mod loader bridge logic, mixins, AT (`bukkit.at`), extra mappings (`extra_mapping.tsrg`), access widener (`arclight.accesswidener`) |
| `arclight-forge` | Forge platform |
| `arclight-neoforge` | NeoForge platform |
| `arclight-fabric` | Fabric platform |
| `bootstrap` | Shaded uber-jars per platform, production run tasks |
| `installer` | Server installer |
| `i18n-config` | i18n config (HOCON) |

## Key entrypoints

- **Server launcher** — `io.izzel.arclight.server.Launcher` (bootstrap)
- **Mixin connector** — `io.izzel.arclight.common.mod.ArclightConnector`
- **Common mod init** — `io.izzel.arclight.common.mod.ArclightCommon`

## Running a server (dev)

```
./gradlew :bootstrap:runProdForge
./gradlew :bootstrap:runProdNeoforge
./gradlew :bootstrap:runProdFabric
```

Respective working dirs: `run_prod/{forge,neoforge,fabric}` (set via `ARCLIGHT_PROD_DIR` env).
Defaults: `nogui`, 4G heap, mixin debug export enabled.

## Mappings

- Mojang official + Parchment for development
- Custom Spigot → mod-loader remapping pipeline in `buildSrc/` (ProcessMappingTask, RemapSpigotTask)
- Per-platform mapping tasks generate `.srg` and inheritance maps at build time: `forgeMappings`, `fabricMappings`, `neoforgeMappings`

## Spigot dependency

Generated Spigot jar is resolved as `io.izzel.arclight.generated:spigot:{mcVersion}:deobf`.
The custom Gradle plugin handles BuildTools + remap; agents should never modify this pipeline.

## Style notes

- All mod-loader platforms share the common module — add Bukkit API logic there.
- Platform-specific mixins and integration go in the respective platform module.
- Access transformation: `arclight-common/bukkit.at` (AT) + `arclight-common/extra_mapping.tsrg` (extra SRG).
- Access widener: `arclight-common/src/main/resources/arclight.accesswidener`.
- Use `@UtilityClass` for static-utility classes (Lombok convention).
- No tests framework is configured; `test: off` in CI.
