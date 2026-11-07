# DystopicLight — Investigación total: Implementación de Paper API y Resolución de Compatibilidad

**Fecha:** 2026-08-15
**Alcance:** fork DystopicLight de Arclight (branch `FeudalKings`, MC 1.21.1)
**Objetivo:** documentar (1) cómo reemplazar la implementación Spigot/Bukkit por Paper 1.21.1 y (2) cómo diagnosticar/resolver crashes de mods/plugins (con análisis de los bugs del issue "ARCLIGHT DYSTOPIC FIXES").

---

## 1. Resumen ejecutivo

- **DystopicLight** es un fork de **Arclight**: un servidor Bukkit que corre sobre Forge/NeoForge/Fabric (Minecraft 1.21.1 en mappings oficiales/Mojang). No ejecuta el servidor vanilla de Mojang remapeado a Spigot: corre el Minecraft del *mod loader* (Mojang-mapped) y **mezcla** la implementación CraftBukkit (obc) compilada aparte.
- **El obc actual es de Spigot** (Spigot-mapped, paquete `org.bukkit.craftbukkit.v1_21_R1`) y se remapea en build a Mojang names + paquete sin versión `org.bukkit.craftbukkit.v`. Todo el código de Arclight importa desde `org.bukkit.craftbukkit.v.*`.
- **Implementar Paper tiene 3 niveles** (de menor a mayor esfuerzo):
  1. **Capa Paper API** (solo API, no servidor): añadir `io.papermc.paper:paper-api:1.21.1` + implementar los métodos que los plugins usan. Es el camino *barato* y desbloquea plugins Paper.
  2. **Reemplazar el obc** por el de Paper (compilar las fuentes OBC de Paper). Es exactamente lo que Arclight upstream planea (issues #1659/#1660/#1661). En 1.21.1 Paper ya es **Mojang-mapped y sin paquete versionado**, lo que *simplifica* el remap respecto a Spigot.
  3. **Portar los patches de servidor de Paper** (chunk system, scheduler, timings, etc.) como mixins. Fue lo que intentó `FirstAttempt` (vendoring de `ca.spottedleaf.concurrentutil` + `fullChunks`).
- **Compatibilidad**: los crashes de híbridos se concentran en ~5 categorías (API faltante, wrappers Bukkit nulos para datos de mods, eventos disparados en contexto inválido, conflictos de mixin targets, desync cliente). El documento detalla metodología y patrones de fix, y aplica a los bugs reportados.

---

## 2. Arquitectura actual (lo que hay que entender antes de tocar nada)

### 2.1 Módulos

| Módulo | Rol |
|---|---|
| `arclight-common` | Toda la lógica Bukkit→mod-loader: mixins, bridges, remapper, eventos, `bukkit.at`, `extra_mapping.tsrg`, access widener |
| `arclight-forge` / `arclight-neoforge` / `arclight-fabric` | Mixins e integración específica de cada plataforma |
| `bootstrap` | Uber-jars shaded por plataforma, tasks de ejecución (`runProdForge`…) |
| `buildSrc` | `ArclightGradlePlugin` + pipeline BuildTools→Spigot→remap |
| `installer` | Instalador del servidor |

### 2.2 Pipeline de build del Spigot (clave para migrar a Paper)

Todo ocurre en `buildSrc/` y se dispara desde `ArclightGradlePlugin.setupSpigot()`:

```
ArclightGradlePlugin.setupSpigot (buildSrc/.../ArclightGradlePlugin.groovy:59)
 ├─ 1. Descarga BuildTools.jar (hub.spigotmc.org)
 ├─ 2. SpigotBuilder.run()  →  java -jar BuildTools.jar --rev 1.21.1(4344)
 │     → spigot-1.21.1.jar (Spigot-mapped: obc en org.bukkit.craftbukkit.v1_21_R1, NMS en net.minecraft.server.v1_21_R1)
 ├─ 3. ProcessMappingTask.run()  → genera en .gradle/arclight/arclight_cache/mappings:
 │     • bukkit_srg.srg           (obc srg→nms srg, usado por Forge)
 │     • bukkit_moj.srg           (obc→Mojang, NeoForge)
 │     • bukkit_intermediary.srg  (obc→intermediary, Fabric)
 │     • inheritanceMap.txt / inheritanceMap_intermediary.txt
 │     • bukkit_at.at / bukkit_aw.aw  (access transformers generados)
 │     • srg_to_named.srg         (srg→named/Mojang)
 │     • reobf_bukkit.srg         ("PK: org/bukkit/craftbukkit/v org/bukkit/craftbukkit/v1_21_R1")
 ├─ 4. RemapSpigotTask.run()  → SpecialSource con bukkit_srg.srg → srg_to_named.srg → relocación de paquete
 │     org/bukkit/craftbukkit/v1_21_R1 → org/bukkit/craftbukkit/v  (+ AT + extra_mapping.tsrg)
 │     → spigot-1.21.1-mapped.jar (deobf, dependencia de arclight-common) y spigot-1.21.1-deobf.jar
 └─ 5. Cache en .gradle/arclight/ + local maven repo "io.izzel.arclight.generated:spigot:1.21.1:deobf"
```

Resultado importante: **el obc compilado contra arclight-common ya está en Mojang names** (los métodos que llama apuntan a `net.minecraft.*`). Eso es lo que permite que las mixins de Arclight (`@Mixin(net.minecraft.server.level.ServerPlayer.class)`) coexistan con las llamadas a `CraftEventFactory`.

### 2.3 El obc: `org.bukkit.craftbukkit.v`

- `ProcessMappingTask` reescribe `reobf_bukkit.srg` = `PK: org/bukkit/craftbukkit/v org/bukkit/craftbukkit/v1_21_R1` (línea 295).
- `RemapSpigotTask` aplica `PK: org/bukkit/craftbukkit/v1_21_R1 org/bukkit/craftbukkit/v` (línea 79).
- Todo el código fuente de Arclight importa `org.bukkit.craftbukkit.v.*` (ej. `ArclightEventFactory.java:29-35`, `CraftServerMixin`, `BukkitRegistry`).
- El runtime remapper de plugins también reescribe referencias de plugins compilados contra `org.bukkit.craftbukkit.v1_21_R1` → `org.bukkit.craftbukkit.v` (ver 2.4).

### 2.4 Remapper de plugins en runtime

- `RemappingClassLoader` + `ClassLoaderRemapper` transforman el bytecode de cada plugin al cargarlo (`mod/util/remapper/`).
- `ArclightPluginPatcher` + `IntegratedPatcher` aplican parches específicos por plugin.
- `GlobalClassRepo` / `RuntimeRepo` resuelven herencias para el remap.
- Esto es **donde Paper impacta**: hay que enseñar al remapper qué pasa con `io.papermc.paper.*` y `com.destroystokyo.paper.*` (no requieren remap de mappings, solo están como dependencia shaded) y con `org.bukkit.craftbukkit` sin versión si se adopta el paquete de Paper.

### 2.5 El intento previo: `FirstAttempt` (estado actual del Paper)

El commit `330bb78b` ("FirstAttempt — errors with imports from bukkit") vendió **`ca.spottedleaf.concurrentutil`** (la librería de concurrencia de PaperMC: `ConcurrentLong2ReferenceChainedHashTable`, `SWMRHashTable`, `PrioritisedThreadPool`, etc. bajo `arclight-common/src/main/java/ca/spottedleaf/`) y empezó a portar el **chunk system** de Paper:

- `ServerChunkCacheMixin.java`: campo `fullChunks` (`ConcurrentLong2ReferenceChainedHashTable<LevelChunk>`) + `bridge$addLoadedChunk/removeLoadedChunk/getChunkAtIfCachedImmediately/getChunkAtIfLoadedImmediately`.
- `ServerChunkProviderBridge.java`: métodos bridge para chunks cargados.
- `CraftServerMixin.java`: redirect de `TicketType.timeout` ("cap plugin loads to 1 second", portado de Paper).

**Por qué falló**: el código portado referencia clases del servidor Paper (`io.papermc.paper.configuration.*`, obc Paper, etc.) que no existen en el obc de Spigot, y el chunk system de Paper depende de muchos otros patches de Paper (distancia de simulación, `ServerChunkCache` reescrito, entity ticking por chunk…). Portarlo "a pelo" sin el resto del ecosistema Paper no compila ni es estable.

**Conclusión**: el camino correcto no es portar trozos de Paper sueltos, sino **primero** tener el obc/API de Paper como base (sección 3).

---

## 3. Implementación de Paper

### 3.1 Qué es Paper 1.21.1 (diferencias clave con Spigot)

| Aspecto | Spigot 1.21.1 | Paper 1.21.1 |
|---|---|---|
| Runtime de mappings | Spigot mappings | **Mojang mappings (moj-map)** |
| Paquete obc | `org.bukkit.craftbukkit.v1_21_R1` | **`org.bukkit.craftbukkit` (sin versión)** |
| NMS | `net.minecraft.server.v1_21_R1` | `net.minecraft` |
| API extra | Spigot-API | **`io.papermc.paper`** (`paper-api`) + legacy **`com.destroystokyo.paper`** |
| Texto/chat | `net.md_5.bungee.chat` | + **Adventure** (`net.kyori.adventure.*`) |
| Librerías | jline, log4j, gson… | + `ca.spottedleaf:concurrentutil`, `velocity-native`, `spark`, `configurate-yaml`, `AutoRenamingTool`… |
| Reobf de plugins | servidor en Spigot-mapped → plugins Spigot-mapped | servidor Mojang-mapped → plugins Spigot-mapped se **deobfusean en runtime** (`.paper-remapped`) |
| Framework | BuildTools | **Paperweight** (clona Bukkit/CraftBukkit/Spigot/BuildData en `work/`, aplica `patches/api` y `patches/server`) |

Verificado en la tag `1.21.1` de PaperMC: `build.gradle.kts` raíz usa `paperweight.core 1.7.3`, `paramMappings("net.fabricmc:yarn:1.21.1+build.3")`, `paperclip("io.papermc:paperclip:3.0.3")`, y `reobfPackagesToFix` incluye `com.destroystokyo.paper`, `io.papermc.paper`, `ca.spottedleaf`, `net.kyori.adventure.bossbar`, `org.bukkit.craftbukkit`… El `Paper-Server/build.gradle.kts` fija `craftBukkitPackageVersion = "v1_21_R1"` **solo** para reobf (compat con plugins viejos), mientras el `Main-Class` es `org.bukkit.craftbukkit.Main` (sin versión).

**Implicación para Arclight**: Paper 1.21.1 ya viene **Mojang-mapped** (como Arclight necesita). El remap `srg→named` que Arclight le hace al obc de Spigot **no sería necesario** para el obc de Paper; solo haría falta la relocación de paquete. Es la razón por la que Arclight upstream eligió 1.21.1 como objetivo "seguro" (antes del hard fork de 1.21.4+, donde Paper renombró obc a `io.papermc.paper` y dejó de tirar de Spigot).

### 3.2 El plan oficial de Arclight (referencia obligatoria)

Arclight upstream tiene el plan de Paper abierto en 3 issues (assignee **qyl27**):

- **#1659 — Stage 1: refactor del build (completado en upstream).**
  Objetivos: no re-compilar Spigot tras `gradlew clean`; auto-check de actualización de Spigot (ya implementado en el fork, ver `setupSpigot` + `spigot_version.json`); correr la versión Fabric en namespace mojmap named.
- **#1660 — Stage 2: compilar las fuentes OBC.**
  > "Copy and remap the OBC sources and compile them for the full API implementations. It should allow us to swiftly switch between the obc source forks."
  - Gestionar los patches de fuentes Spigot-Server **via Paperweight**.
  - Remapear automáticamente OBC a mojmap **via Mercury** (la misma librería ya está en `libs.versions.toml` como `cadixdev-mercury`, usada en `ProcessMappingTask`).
  - Compilar las fuentes OBC parcheadas como un **SourceSet de `arclight-common`**.
  - Usar **inyección de interfaces** para los métodos NMS parcheados por OBC (justo lo que Arclight ya hace con `bridge/` + `injected_interfaces` en `mixins.arclight.bukkit.json`).
- **#1661 — Stage 3: migrar al patch de Paper.**
  > "Move to the paper patch in 1.21.1, it is the version before massive changes to the upstream API. It is safe to directly move."
  - Tras el stage 2, basta cambiar de "source fork de obc" (Spigot→Paper) manteniendo la misma maquinaria.
  - Después de 1.21.4 Paper ya no tira de Spigot → quedarse en 1.21.1 evita mantener dos upstreams.

También hay PRs upstream ya merged que muestran el patrón de "paper API incremental": **#1763 "Port Paper block Action api"** (añade `Action#isLeftClick/isRightClick` al enum de Spigot para compat de plugins). El issue **#47/#1915** tratan el soporte de **Adventure** (parte de Paper API).

### 3.3 Tres caminos concretos para DystopicLight

#### Camino A — Capa Paper API (incremental, mínimo riesgo) — **recomendado para empezar**

Sin tocar el obc de Spigot ni el chunk system, dar a los plugins las **clases API** de Paper para que carguen y sus llamadas no fallen con `NoClassDefFoundError`:

1. **Dependencia**: añadir en `libs.versions.toml`:
   ```toml
   paper-api = { module = 'io.papermc.paper:paper-api', version = '1.21.1-R0.1-SNAPSHOT' }
   ```
   y en `arclight-common/build.gradle` `compileOnly` + shade en bootstrap. Ojo: `paper-api` trae `net.kyori.adventure`, `com.destroystokyo.paper`, etc. Hay que excluir conflictos con `net.md_5.bungee.chat` y `org.spigotmc:spigot-api` (relocations en el shade).
2. **Implementación mínima**: los plugins Paper llaman, sobre todo, a métodos añadidos en las interfaces ya existentes (`Bukkit.getServer()` devuelve `CraftServer`, que con un mixin puede implementar `PaperServer`/`Server`), `Player#sendMessage(Component)`, `player.showInvisibles()`, `world.getEntity...`, `Chunk#getChunkSnapshot`, eventos de Paper (`com.destroystokyo.paper.event.*`). Se implementan como mixins sobre `CraftServerMixin`/`CraftPlayerMixin`/etc. o delegando en Adventure.
3. **Remapper**: registrar `io.papermc.paper`/`com.destroystokyo.paper` como paquetes "API" (sin remap) en `ArclightRemapConfig`/`ClassLoaderRemapper`, igual que hoy se trata a `org.bukkit`/`org.spigotmc`.
4. **Ventajas**: compila hoy, no rompe nada, desbloquea plugins que exigen Paper API (muchos de los que pide la comunidad). **Límites**: plugins que tocan NMS Paper o clases `io.papermc.paper` de servidor seguirán fallando; no ganas las optimizaciones de Paper.

> Patrón de referencia: lo que Mohist/Banner hicieron para "implementar Paper" empezó exactamente así (shade de `paper-api` + implementaciones parciales sobre el obc de Spigot).

#### Camino B — Reemplazar el obc de Spigot por el obc de Paper (el plan de upstream)

Este es el "de verdad" y corresponde a los stages 2–3 de Arclight:

1. **Obtener las fuentes OBC de Paper 1.21.1**: clonar `PaperMC/Paper-archive` tag `1.21.1` (estructura `patches/api` + `patches/server` + `work/` con Bukkit/CraftBukkit/Spigot/BuildData) y usar **Paperweight** para generar las fuentes **Mojang-mapped** de `org.bukkit.craftbukkit.*` (+ `io.papermc.paper.server`). Alternativa: usar el artifact ya publicado `io.papermc.paper:paper-mojangapi:1.21.1-R0.1-SNAPSHOT` (dev bundle) que contiene el servidor Mojang-mapped con obc.
2. **Mapear a `org.bukkit.craftbukkit.v`** (opcional, para no tocar todo Arclight): relocación `org/bukkit/craftbukkit → org/bukkit/craftbukkit/v` + shade de `io.papermc.paper`, `com.destroystokyo.paper`, `ca.spottedleaf`, `net.kyori.adventure`.
   - *Alternativa a largo plazo*: migrar todo Arclight a `org.bukkit.craftbukkit` sin versión (más fiel a Paper; los plugins obc de Paper referencian ese paquete y no necesitarían reobf). Es un cambio mecánico (sustituir `craftbukkit.v.` → `craftbukkit.` en fuentes) + rehacer `reobf_bukkit.srg`.
3. **Ajustar la pipeline**:
   - `ProcessMappingTask`: ya no hace falta `srg_to_named.srg` para el obc (Paper es Mojang-mapped); se conservan los generadores de `inheritanceMap`, `bukkit_at.at`, `bukkit_aw.aw` y los `.srg` para Forge/NeoForge/Fabric (que ahora son identidad+extra para obc Paper).
   - `RemapSpigotTask` → un `RemapPaperTask` equivalente (SpecialSource con `-m` solo para `extra_mapping.tsrg` y `-l` para paquete; AT + AW).
   - `ArclightGradlePlugin`: `generatedSpigot` → `generatedPaper`; resolver `io.papermc.paper:paper-server`/`dev-bundle` en vez de BuildTools.
4. **Resolución de símbolos**: el obc de Paper referencia clases del servidor que **en Spigot están en el mismo jar** (obc+server bundlado). En Arclight el "server" es el Minecraft del mod loader, así que hay que mapear cada referencia obc→NMS a Mojang y **descartar** las partes de Paper que patchean vanilla (esas se convierten en mixins, Camino C).
5. **Riesgos**: muchos mixins de Arclight apuntan a métodos de NMS que en Spigot-mapped tienen un nombre y en Paper (Mojang-mapped) otro — pero como Arclight ya desarrolla en Mojang names, el obc de Paper **coincide mejor** que el de Spigot. El grueso del trabajo está en reconciliar `CraftEventFactory` de Paper (más campos/métodos, e.g. `handleEntityDamageEvent`, `callPlayerInteractEvent`) con las mixins `CraftEventFactoryMixin` de Arclight.

#### Camino C — Portar los patches de servidor de Paper como mixins

El obc de Paper asume que el servidor tiene los patches de Paper (chunk system con `fullChunks`, `ServerChunkCache` reescrito, entity ticking, scheduler `PaperScheduler`, timings, `AsyncCatcher`, redstone/flags, etc.). En un híbrido esos patches **no se pueden aplicar al vanilla** (romperían Forge/Fabric), así que se **reimplementan como mixins** en Arclight:

- Es el trabajo de `FirstAttempt` pero *ordenado*: `concurrentutil` ya está vendido → seguir con `fullChunks`, `getChunkAtIfCachedImmediately`, `ServerChunkProviderBridge` (ya en el repo).
- Cada patch de Paper debe evaluarse: los que **solo tocan obc** van gratis con el Camino B; los que **tocan vanilla** (mixin targets) son los que más conflictos dan con mods.
- **No empezar por aquí.** Hacerlo antes de B es lo que causó el fallo de `FirstAttempt`.

### 3.4 Impacto en el build: archivos concretos a tocar

| Archivo | Cambio para Camino B |
|---|---|
| `gradle/libs.versions.toml` | `spigot-api` → `paper-api`/`paper-mojangapi`; añadir adventure, concurrentutil, velocity-native |
| `build.gradle` (root) | `generatedSpigot` → `generatedPaper`; repos |
| `buildSrc/.../ArclightGradlePlugin.groovy` | `setupSpigot` → `setupPaper` (paperweight/dev-bundle en vez de BuildTools) |
| `buildSrc/.../ProcessMappingTask.groovy` | simplificar: obc ya Mojang-mapped (quitar `srg_to_named`); mantener inheritance/AT/AW |
| `buildSrc/.../RemapSpigotTask.groovy` | relocar `org.bukkit.craftbukkit`→`v` (o eliminar si se adopta paquete sin versión) |
| `arclight-common/bukkit.at`, `extra_mapping.tsrg` | revisar targets: nombres de miembros cambian si se pasa a Paper obc |
| `arclight-common/src/main/resources/arclight.accesswidener` | igual, revisar |
| Remapper runtime (`mod/util/remapper/`) | registrar paquetes Paper como API |
| `mixins.arclight.bukkit.json` | `injected_interfaces` para obc Paper |

### 3.5 Dependencias de Paper 1.21.1 (para el shade del bootstrap)

Extraídas del `paper-server/build.gradle.kts` 1.21.1 y del root build:
- `io.papermc:paperclip:3.0.3` (bundler)
- `ca.spottedleaf:concurrentutil` (ya vendido en el repo — decisión: quitar el vendoring y usar el artifact)
- `net.kyori:adventure-text-serializer-ansi`, adventure platform
- `com.velocitypowered:velocity-native` (proxy protocol)
- `me.lucko:spark-paper` / `spark-api`
- `io.papermc:AutoRenamingTool` + `reflection-rewriter` (remap de plugins en runtime)
- `org.spongepowered:configurate-yaml`
- `io.netty:netty-codec-haproxy`
- `org.apache.logging.log4j:log4j-*` (ya presente), `org.jline:jline-terminal-*` (ya presente)

### 3.6 Checklist mínimo de implementación (Camino A → B → C)

1. **A**: shade `paper-api` + implementar `Server`/`Player`/`World` paper basics + Adventure bridge + remapper de paquetes Paper. *Entregable: los plugins Paper cargan y los eventos Paper existen.*
2. **B**: generar/descargar obc de Paper Mojang-mapped; reemplazar el artifact `spigot:deobf` por `paper:deobf`; relocar paquete; arreglar los ~50 mixins de obc (`mixin/bukkit/*`) que apuntan a símbolos Spigot del obc; regenerar AT/AW/tsrg. *Entregable: el servidor corre con implementación Paper.*
3. **C**: portar chunk system (`fullChunks` ya empezado), scheduler, timings, entity-ticking por chunk, `AsyncCatcher` de Paper. *Entregable: rendimiento Paper.*

> Recomendación: hacer **A** ya (desbloquea mucho y es reversible), y planificar **B** como objetivo principal (es el plan oficial de upstream y el fork puede "montarse" en él cuando exista). **C** solo tras B.

---

## 4. Compatibilidad: cómo resolver crashes de mods/plugins

### 4.1 Por qué fallan los híbridos (categorías de fallo)

| # | Categoría | Síntoma típico | Dónde vive en Arclight |
|---|---|---|---|
| 1 | **API faltante** (plugin compilado contra Paper/Spigot más nuevo) | `NoClassDefFoundError` / `NoSuchMethodError` al cargar o al usar un evento | `arclight-api`, obc, remapper |
| 2 | **Wrapper Bukkit nulo para datos de mod** (item/block/gameevent/recipe de mod) | `NullPointerException` dentro del handler del plugin; el wrapper devuelve `AIR`/`null` | `CraftItemType.minecraftToBukkit`, `CraftGameEvent.minecraftToBukkit`, `bridge$toBukkitRecipe`… |
| 3 | **Evento disparado en contexto inválido** (worldgen, hilo no principal, entidades "fantasma") | crashes raros en generación de mundo; `Bukkit.getServer()` no inicializado | patrón `DistValidate.isValid(...)` |
| 4 | **Conflicto de mixin targets** (mod y Arclight mixinean el mismo método) | `Mixin apply failed`, `MixinTargetAlreadyError`, o comportamiento doble | mixins.core/vanilla + `extra_mapping.tsrg` |
| 5 | **Desync cliente/servidor** (entidades, riding, animaciones) | crash de cliente al montar/mutar entidades | mixins de Entity/tracker/packets |

### 4.2 Herramientas de diagnóstico (orden de uso)

1. **Crash report del servidor** (`crash-reports/`) y del **cliente** (los bugs "crashea el juego al acompañante" son del cliente → pedir el crash del cliente).
2. **`latest.log` con el stack completo** del plugin. Identificar: (a) el evento Bukkit que dispara, (b) el mixin de Arclight que lo dispara (`ArclightEventFactory`, `CraftEventFactoryMixin`, mixin core correspondiente), (c) el primer `Caused by`.
3. **Dump de mixins** para ver qué se aplicó: arrancar con `-Dmixin.debug.export=true` y `-Dmixin.debug.countInjections` (ya activado por defecto en dev según AGENTS.md) → `run_prod/*/.mixin.out/`.
4. **Reproducción mínima**: un plugin vacío con un listener del evento sospechoso. Si el listener mínimo no crashea, el problema es lo que el plugin *hace* con los datos (categoría 2), no el disparo.
5. **`ArclightServer`/`DistValidate`** tienen log con marker `EXT_LOGIC` para "worlds de lógica extra": configurable en `ArclightConfig` → útil cuando un mod provee su propio `ServerLevel` (clave para bug tipo worldgen).
6. Para rendimiento/threads: **spark** (`/spark profiler`) una vez integrado.

### 4.3 Patrones de fix en Arclight (con ejemplos del propio repo)

- **Suprimir eventos durante worldgen / mundos no-lógicos**: `DistValidate.isValid(world)` (un `ServerLevel` custom de un mod se considera lógico solo si está en la config `extra-logic-worlds`). Ejemplos: `ArclightEventFactory.callBlockFormEvent`, `CraftEventFactoryMixin.handleBlockSpreadEvent/handleBlockGrowEvent/callBlockPhysicsEvent/...`.
- **Usar `Decorate`/`Redirect`/`Inject` con `@Local` en vez de `@Overwrite` cuando sea posible** (los `@Overwrite` rompen compat con mods que tocan lo mismo). Arclight usa `@Decorate` (mixin-tools) precisamente para esto; hay `@Overwrite` justificados y documentados con `@author`/`@reason`.
- **Bridge + interface injection** para métodos NMS que el obc necesita: `injected_interfaces` en `mixins.arclight.bukkit.json` (`InjectEntityBridge`, `InjectLevelBridge`) + `bridge/` interfaces. Este es el mecanismo que el stage 2 de Paper va a explotar.
- **Capturas explícitas de contexto** (`ArclightCaptures`) para no perder contexto entre vanilla→obc: `captureContainerOwner` antes de `callInventoryOpenEvent`, `captureBlockBreakPlayer`, etc.
- **Extra mappings** (`extra_mapping.tsrg`) para apuntar mixins a la clase correcta cuando el vanilla tiene inner classes renombradas (ej. `ChestBlock$DoubleInventory`, `LecternBlockEntity$LecternInventory`).
- **Fixes de compat ya mergeados en este fork** (patrones a imitar):
  - `0769551b` "Fix Servux LevelChunk mixin compatibility" — aflojar un `@Redirect` de `LevelChunkMixin` para no chocar con Servux.
  - `89407b58` "Fix Golden Hopper inventory move crash" — corregir un target de mixin.
  - `1c4d6ae1` "Fix non-logic entity passenger spawn path" — **`DistValidate.isValid`** para el spawn de entidades con pasajeros en mundos no-lógicos (¡directamente relacionado con los mounts de Cobblemon!).
  - `6f586d61` "Fix MerchantMenu Bukkit view", `668f9f3e` "Fix Pehkui slime mixin target".

### 4.4 Guía paso a paso para resolver un crash de compatibilidad

1. **Clasificar** (sección 4.1) usando el stack.
2. Si es **API faltante**: añadir el método/clase al obc o implementarlo como mixin/interface-injection (patrón Camino A). Ver si upstream Arclight ya lo portó (PR #1763 es el ejemplo).
3. Si es **wrapper nulo** (categoría 2): buscar el `CraftX.minecraftToBukkit(...)` que devuelve null/AIR para datos de mod y añadir un fallback con la convención de Arclight (ej. `TreeType.MOD`, `Material` fallback) o re-despachar el evento con datos corregidos. Añadir también `DistValidate`/`valid` guard si aplica.
4. Si es **contexto inválido**: envolver el punto de disparo con `DistValidate.isValid` / `this.valid` (patrón de `EntityMixin.startRiding`, `arclight$startSmelt`…). Añadir el mundo del mod a `extra-logic-worlds` en la config de compat si corresponde.
5. Si es **conflicto de mixin**: usar `-Dmixin.debug.export` para ver quién más toca el método; cambiar el target (`extra_mapping.tsrg`), reducir la prioridad, o convertir `@Overwrite`→`@Redirect`/`@Decorate`/`@Inject`. Comparar con los fixes mergeados de la sección 4.3.
6. Si es **desync de cliente**: revisar el flujo de packets del evento (ej. `ClientboundBlockUpdatePacket` en `ArclightEventFactory.onBlockBreak`, `bridge$pushTeleportCause`). El fix suele ser **enviar el packet correcto** o **no cancelar** cuando el mod ya gestiona la entidad (patrón `1c4d6ae1`).
7. **Regresión**: probar cada fix en los 3 mod loaders (`runProdForge/NeoForge/Fabric`), con el plugin Y con el mod mínimo que lo reproduce.

---

## 5. Análisis de los bugs del issue "ARCLIGHT DYSTOPIC FIXES"

> Nota: los hastebin del reporte están muertos (no recuperables) y el IMG1 no está disponible, así que el análisis de los eventos ItemsAdder se basa en inspección del código del repo + categorías conocidas. Para cerrar cada bug: re-fetch de los logs con la metodología de 4.4.

### 5.1 Bugs generales

#### 5.1.1 Cobblemon — crash del acompañante al montarlo

- **Síntoma**: al montar un Cobblemon, el juego crashea **al pasajero** (del lado del Cobblemon montado). Pista del reporte: "Sospecha de que es Arclight".
- **Dónde se dispara el flujo en Arclight**: `EntityMixin.startRiding` (`mixin/core/world/entity/EntityMixin.java:780`) dispara `VehicleEnterEvent` + `EntityMountEvent`; `EntityMixin.removePassenger/removeVehicle` dispara `VehicleExitEvent` + `EntityDismountEvent` con la lógica `arclight$dismountCancelled`/`this.vehicle` para "re-montar" (líneas 804-844). El upstream ya tocó esto: `1c4d6ae1` (passenger spawn path no-lógico) y el fix de spawn de pasajeros en `ServerLevelAccessorMixin`.
- **Causas probables**:
  1. Cobblemon usa montura con **collision/riding custom** (`EntityMountable`); si un handler cancela `EntityMountEvent` (o un plugin), el cliente del pasajero queda a medias → crash de cliente. 
  2. El spawn de pasajeros en contexto no-lógico (`DistValidate`) en versiones viejas causaba `CreatureSpawnEvent` con reason null → ya parcheado.
  3. Desync del packet de pasajeros (`ClientboundSetPassengersPacket`) al cancelar `VehicleEnterEvent` sin re-sincronizar.
- **Acciones**: 
  1. Pedir el **crash-report del cliente** (no el del servidor) y verificar si es un `ClassCastException`/`NullPointer` en Cobblemon o un mixin de Arclight.
  2. Probar con el listener mínimo de `EntityMountEvent`/`VehicleEnterEvent` cancelado vs no cancelado para aislar.
  3. Revisar si el fix de `1c4d6ae1` está incluido (sí, está en el log) y si Cobblemon dispara `startRiding` fuera del hilo principal o en worldgen (aplicar `DistValidate`/`valid`).
  4. Verificar `VehicleEntityCollisionEvent` y `EntityMountEvent` contra la implementación de Paper (Paper dispara `EntityMountEvent` también desde `Entity#startRiding` con guards de `valid`).

#### 5.1.2 Cobblemon — modelo que cambia tarde en la animación de evolución

- **Síntoma**: el modelo de la evolución (y mega/dynamax) cambia **unos segundos tarde**; en Fabric puro/singleplayer es instantáneo.
- **Dónde mirar**: la sincronización de datos de entidad. Cobblemon cambia el modelo del lado cliente cuando recibe un cambio de metadata/packet (o un `ClientboundSetEntityDataPacket` / `ClientboundEntityEventPacket`). Un "retraso" = la sincronización se entrega en el siguiente batch/tick.
- **Causas probables**:
  1. El entity tracker / `ServerEntity` de Arclight difiere de vanilla (por los mixins de optimización `optimization/general/network` o por `TickingTracker`). Paper/vanilla envían la metadata inmediatamente tras el cambio; un mixin que agrupa/retrasa actualizaciones produciría exactamente este retraso.
  2. Cobblemon detecta "la entidad va a evolucionar" por un **cambio de tipo** (`Entity#getType`/registry) y Arclight lo intercepta o lo difiere.
  3. El mod convierte la entidad (`LivingEntity#convertTo`) y Arclight (mixin de `LivingEntity`/`EntityType`) interviene.
- **Acciones**: 
  1. Probar con **optimizations desactivadas** (config de Arclight) para aislar si es un mixin de `optimization/`.
  2. Usar `mixin.debug.export` y comparar el flujo con un vanilla/Fabric puro (¿qué mixin de Arclight toca `Entity#setRemoved`/`convertTo`/`refreshDimensions`/`getType`?).
  3. Buscar si hay un `@Redirect` de Arclight sobre el tracker que envía metadata en `ServerEntity`/`ChunkMap$TrackedEntity` (también está en `async_catcher.json`, señal de que Arclight toca esos métodos).
  4. Si se confirma que es el tracker: alinear la cadencia de envío de metadata con vanilla (no agrupar).

#### 5.1.3 Multiverse — lluvia constante

- **Síntoma**: lluvia constante; `mv set storm` no consigue pararla de forma estable.
- **Dónde vive el clima en Arclight**: `PrimaryLevelDataMixin.setRaining/setThundering` (`mixin/core/world/level/storage/PrimaryLevelDataMixin.java:54-84`) dispara `WeatherChangeEvent`/`ThunderChangeEvent` y cancela el cambio si un plugin lo cancela. `World.setStorm()` (obc) llama a `levelData.setRaining(...)`.
- **Causas probables**:
  1. **Otro sistema sigue poniendo lluvia**: el ciclo de clima vanilla (`ServerLevel#advanceWeatherCycle`) o un **mod** (SereneSeasons/Weather2) — en híbridos es la causa nº1: Multiverse pone `raining=false`, pero el mod/mixins del clima lo vuelven a `true` en el mismo/dos ticks.
  2. Un plugin cancela el `WeatherChangeEvent` hacia abajo (o un plugin viejo cancela siempre) → el cambio a seco nunca persiste.
  3. El `weather` se guarda en `PrimaryLevelData` pero Arclight re-crea el level y `bridge$setWorld` solo engancha el primero (`setWorld` solo asigna si `world==null`).
  4. Desync cliente: el servidor está seco pero el cliente no recibe el packet (`ClientboundGameEventPacket` rain-state).
- **Acciones**:
  1. Comprobar en servidor con `/weather clear` y `tick time` el estado real (`PrimaryLevelData.raining`).
  2. Añadir un listener de depuración de `WeatherChangeEvent` para ver quién y cuándo llama (vanilla cycle vs Multiverse).
  3. Si el ciclo vanilla lo revierte: en Paper hay config para desactivar el ciclo de clima (`weather-cycle`) — portarla como mixin/redirect a `advanceWeatherCycle`.
  4. Revisar que `setRaining` no esté disparando `WeatherChangeEvent` durante el `tick` con `Bukkit.getWorld` a medias (world null → no se dispara evento, ok).

### 5.2 Errores de ItemsAdder (eventos Bukkit)

ItemsAdder escucha eventos Bukkit para gestionar sus bloques/mobles personalizados. En Arclight el crash viene de **cómo se construye el evento** (categoría 2/3 de 4.1), no de que el evento no exista. Dónde se dispara cada uno en el repo:

| Evento | Dónde lo dispara Arclight | Riesgo con ItemsAdder |
|---|---|---|
| **Furnace** (`FurnaceBurnEvent`/`StartSmelt`/`Smelt`/`Extract`) | `AbstractFurnaceBlockEntityMixin.serverTick` y `_Vanilla` (`burn`, `startSmelt`, `createExperience`) | `CraftItemStack.asCraftMirror`/`CraftItemType.minecraftToBukkit` sobre **items de mod** (el fuel/resultado de ItemsAdder) puede devolver `AIR`/null → ItemsAdder crashea al consultar `getResult()`/`getFuel()`. `bridge$toBukkitRecipe()` puede dar null con recetas custom del mod → `CookingRecipe` null. |
| **InventoryOpenEvent** (con `/enderchest`) | `ServerPlayerMixin.openMenu*` (líneas 592-634) → `CraftEventFactory.callInventoryOpenEvent` | Al abrir inventarios modded (enderchest de ItemsAdder / menús del mod), el `InventoryView`/holder es un `ArclightModdedMenuContainer`/`ArclightModdedMenuInventory`; ItemsAdder suele castear `getInventory().getHolder()` o `getType()` → `ClassCastException`. También si el menú es de un mod y `callInventoryOpenEvent` devuelve null sin avisar. |
| **GenericGameEvent** | `ServerLevelMixin` (línea 277) y `GameEventDispatcherMixin` → `new GenericGameEvent(...)` | `CraftGameEvent.minecraftToBukkit(holder.value())` y `org.bukkit.GameEvent.getByKey(...)` devuelven **null para game events de mod** → el plugin hace `event.getEvent().getKey()` → NPE. **Fix patrón**: si es null, o bien no disparar el evento, o usar un fallback. |
| **BlockPlacement / BlockMultiPlaceEvent** | `ArclightEventFactory.onBlockPlace` → `CraftEventFactory.callBlockPlaceEvent/callBlockMultiPlaceEvent` (líneas 162-235); `BlockItemMixin` | Con bloques custom de ItemsAdder, `getBlockPlaced()`, `getBlock().getState()` o `getItemInHand()` pueden envolver datos de mod inconsistentes; o el evento se dispara con el block entity capturado incompleto (`bridge$getCapturedBlockState`). |
| **PlayerAnimationEvent** | `ServerGamePacketListenerImplMixin` (línea 1164) | Menos probable el crash (evento simple: jugador+type), pero ItemsAdder lo usa para detectar interacciones; el riesgo real es que el handler castea `event.getPlayer()` a tipos obc Paper (si ItemsAdder compila contra Paper). |
| **BlockBreakEvent** | `ArclightEventFactory.onBlockBreak` (líneas 106-160) | Igual que placement: ItemsAdder rompe sus bloques custom; si `CraftBlock.at`/`getState` no resuelve el block entity de mod o `getExpToDrop` es raro, el handler crashea. |

**Recomendaciones concretas para cerrar los items de ItemsAdder:**
1. **Conseguir los logs reales** (los hastebin están caídos): en cada crash, pedir el stack del servidor. Identificar la línea del handler de ItemsAdder y qué dato espera.
2. **Fix genérico de la categoría "game event de mod"**: en `ServerLevelMixin`/`GameEventDispatcherMixin`, si `minecraftToBukkit`/`getByKey` devuelve null, **no disparar** `GenericGameEvent` (o disparar con `GameEvent` de tipo fallback). Esto elimina el crash de ItemsAdder sin afectar vanilla.
3. **Fix genérico de "inventario modded"**: en `arclight$invOpen`, asegurar que `callInventoryOpenEvent` nunca devuelva un view cuyo `getTopInventory().getHolder()` sea null/casteable raro; usar `ArclightModdedMenuInventory` de forma consistente (ya existe en `mod/server/world/inventory/`).
4. **Furnace**: en `AbstractFurnaceBlockEntityMixin`, proteger `bridge$toBukkitRecipe()` null y, si el item de fuel/resultado es de un mod no registrado en Bukkit, no castear `CraftItemStack` a tipos que asuman vanilla (revisar `asCraftMirror`).
5. **Test de aislamiento**: plugin mínimo con listener de cada evento para confirmar el punto exacto antes de tocar los mixins.

---

## 6. Roadmap y recomendaciones

**Corto plazo (1–2 semanas)**
1. Añadir la **capa Paper API** (Camino A): `paper-api` shaded + Adventure bridge + remapper. Verificación: cargar un plugin Paper de prueba y `PaperServer` en `Bukkit.getServer()`.
2. Recuperar **logs reales** de los bugs (ItemsAdder + Cobblemon) y cerrar los de categoría 2/3 con los fixes patrón: game event null-guard, holder de inventario modded, recipe null-guard, `DistValidate` en passenger spawn.
3. Bug de **Multiverse**: listener de depuración de `WeatherChangeEvent` para ver quién revierte la lluvia; portar la config `weather-cycle` de Paper como redirect.

**Medio plazo (mes)**
4. **Seguir el plan de Arclight**: monitorizar/issues #1659–#1661 y, cuando el stage 2 exista upstream, portarlo al fork (reemplazar obc Spigot→Paper). Hacer el branch de Paper con la pipeline (sección 3.4).
5. Ir cerrando los eventos Paper de mayor uso en plugins (los de la lista de la comunidad de Arclight).

**Largo plazo**
6. Portar el **chunk system** de Paper ordenadamente (Camino C) sobre la base ya vendida de `concurrentutil` + `fullChunks` (que ya existe en el repo pero incompleto y sin compilar).

**Regla de oro**: no portar código de Paper que dependa de símbolos Paper (obc/API) hasta tener el obc de Paper como dependencia — fue el error de `FirstAttempt`.

---

## Apéndice A — Referencias

- Arclight upstream: https://github.com/IzzelAliz/Arclight
  - Issue #1659 (Stage 1 build), #1660 (Stage 2 OBC), #1661 (Stage 3 Paper patch)
  - PR #1763 (Paper block Action API backport)
- Paper 1.21.1 (archivo): `PaperMC/Paper-archive` tag `1.21.1` (`build.gradle.kts`, `build-data/paper.at`, `patches/`)
- Paperweight-userdev docs: https://docs.papermc.io/paper/dev/userdev/
- MohistMC (Forge/NeoForge + Paper/Purpur API), Banner (Fabric + Bukkit/Paper): referencias de arquitectura de híbridos con Paper.
- Este repo: `buildSrc/src/main/groovy/io/izzel/arclight/gradle/`, `arclight-common/src/main/java/io/izzel/arclight/common/{mod,mixin,bridge}/`, `gradle/libs.versions.toml`.
