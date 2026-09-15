# Autonomy implementation and validation

Fork: [samuelrowan20/mc_aiplayer, codex/continuous-autonomy](https://github.com/samuelrowan20/mc_aiplayer/tree/codex/continuous-autonomy).

Based on upstream commit `a029fa6a3760fd0f83834c104051b041d986da60`. Minecraft remains **1.21.3**. The new mode owns a separate observe/decide/act loop and bypasses the strategic goal/task schedulers. It retains the existing player, action interfaces, restricted A* planner, and atomic runtime persistence. Manual assigned-goal mode remains available.

## Validation scope

Final command: `.\gradlew.bat --no-daemon clean build`.

**BUILD SUCCESSFUL in 4m 53s.** JUnit: **377 passed**, zero failures/errors/skips. Minecraft GameTests: **598 passed**, zero failures (587 inherited tests and 11 new autonomy tests). Tests used Java 21, Fabric Loader 0.18.4, Fabric API 0.114.1+1.21.3, and the repository's Gradle wrapper. `git diff --cached --check` also passed.

Local evidence produced by that command:

```text
build-clean.log
build/test-results/test/TEST-*.xml
build/reports/tests/test/index.html
build/test-results/gametest/TEST-aibot-gametest.xml
build/run/gameTest/world/aibot/autonomy/
```

These generated files are ignored by Git. The committed tests reproduce the checks. Earlier world runs exposed three fixture timing errors and a real center-ray visibility bug preventing navigation over exposed ground; all were corrected before the final clean run. The exposed-ground regression assertion preserves the hidden-block boundary.

Production artifact: `build/libs/aibot-0.0.1-autonomy.1.jar`. Jar inspection found the autonomy implementation and **zero GameTest entries**.

SHA-256:

```text
06f3ee6bcfdc2663adf274d6533803be81f8e82c3a1622e0f3251134856d0d45
```

The Minecraft integration tests use a scripted decision provider. They exercise real world state, physics, vanilla damage/drops/respawn, observations, and the autonomous coordinator. They establish that the loop continues and that selected actions have the tested consequences. They do not establish that a commercial model makes useful independent decisions.

No hosted-model session or real overnight game run was performed. No provider credentials were supplied. The first live experiment is described in [AUTONOMY.md](AUTONOMY.md#first-overnight-run).

### Coverage

| Area | Evidence |
|---|---|
| Continuous decisions | Scripted provider creates an intention, waits, moves, receives an ordinary failure, and makes subsequent decisions without human goals. |
| Strategic separation | Separate capability registry; both legacy tick schedulers, brain dispatch and task assignment reject autonomous ownership. Source boundary is documented in [the audit](AUTONOMY_TOOL_AUDIT.md). |
| Strict observation | Sealed ore and invisible entity exclusions in an actual world; null-world tripwire tests ensure forbidden pathfinding cells are rejected before topology reads; unrestricted cached routes cannot bypass observation. |
| Physical mechanics | Selected visible navigation reaches its destination without relocation; deliberate waiting preserves gravity and vanilla fall damage. |
| Crafting and failure | A selected available recipe consumes present ingredients; missing ingredients do not start acquisition/recursive crafting. Blocked placement fails without mutation or consumption. Mining retains the chosen held item. |
| Death | Vanilla lethal damage drops inventory; respawn creates the managed fake-player subtype through the vanilla lifecycle; death episodes survive and decisions resume. No recovery task is chosen. |
| Cancellation | Pause/stop quiesce work; stale HTTP responses cannot apply; repeated notices do not starve pending decisions. |
| Provider | Local HTTP fixture covers structured tool calls, endpoint prefixes, supported reasoning request formats, response limits, stalled bodies, cancellation, errors and recovery. Private reasoning and raw error bodies are excluded. |
| Repetition and outages | Equivalent failed actions are suppressed after a bounded threshold; changed evidence permits reconsideration; retries use capped exponential backoff. |
| Persistence | Engine round trips preserve bounded waits, memory and failure state. World test reloads paused bots at their exact airborne/water positions with working memory. Interrupted operations receive an explicit result and are not replayed. |
| Journal | Ordered JSONL/readable records, bounded retention, observable disk errors and oversized-record rejection. World runs generate intention/action/result/death/respawn journals. |

### Accelerated soak

`AutonomyEngineTest.acceleratedTwentyFourHourSoakHasBoundedStateAndRecoversAcrossLifecycleEvents` simulates **1,728,000 ticks**, equivalent to 24 hours at 20 ticks/second. It includes failures, provider outages, deaths, pauses and snapshot restores.

- Provider decisions: **36,103**.
- Physical action starts in the harness: **31,377**.
- Journal events delivered to the harness sink: **105,675**.
- Maximum serialized context observed: **7,637 characters**.

This is an accelerated engine harness, not 24 hours of Minecraft, network traffic, or durable journal throughput. Journal rotation and HTTP failure behavior have separate focused tests. Successful-action loops and the quality of repeated intentions remain the model's responsibility; the implementation does not invent a strategy to replace them.

## Source inventory

All paths in this section are repository-relative. Java line-ending normalization adds no unrelated code changes.

### Added production source files

```text
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomyCoordinator.java
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomyDecision.java
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomyEmbodiment.java
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomyEngine.java
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomyJournal.java
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomyObservation.java
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomyProvider.java
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomySettings.java
src/main/java/io/github/zoyluo/aibot/autonomy/AutonomyState.java
src/main/java/io/github/zoyluo/aibot/autonomy/MechanicalCrafting.java
src/main/java/io/github/zoyluo/aibot/command/AIBotAutonomySubcommand.java
```

### Materially changed production source files

```text
src/main/java/io/github/zoyluo/aibot/AIBotConfig.java
src/main/java/io/github/zoyluo/aibot/action/ActionPack.java
src/main/java/io/github/zoyluo/aibot/action/MiningController.java
src/main/java/io/github/zoyluo/aibot/brain/ActionDispatcher.java
src/main/java/io/github/zoyluo/aibot/brain/BrainCoordinator.java
src/main/java/io/github/zoyluo/aibot/command/AIBotCommand.java
src/main/java/io/github/zoyluo/aibot/entity/AIPlayerEntity.java
src/main/java/io/github/zoyluo/aibot/manager/AIPlayerManager.java
src/main/java/io/github/zoyluo/aibot/mixin/PlayerManagerFakePlayerMixin.java
src/main/java/io/github/zoyluo/aibot/pathfinding/AStarPathfinder.java
src/main/java/io/github/zoyluo/aibot/pathfinding/NeighborEnumerator.java
src/main/java/io/github/zoyluo/aibot/persist/BotPersistence.java
src/main/java/io/github/zoyluo/aibot/persist/PersistedBot.java
src/main/java/io/github/zoyluo/aibot/runtime/IntentController.java
src/main/java/io/github/zoyluo/aibot/runtime/RuntimeLifecycleCoordinator.java
src/main/java/io/github/zoyluo/aibot/task/BotTickCoordinator.java
src/main/java/io/github/zoyluo/aibot/task/TaskManager.java
```

### Added tests

```text
src/test/java/io/github/zoyluo/aibot/autonomy/AutonomyEngineTest.java
src/test/java/io/github/zoyluo/aibot/autonomy/AutonomyJournalTest.java
src/test/java/io/github/zoyluo/aibot/autonomy/AutonomyProviderTest.java
src/test/java/io/github/zoyluo/aibot/pathfinding/NeighborVisibilityTest.java
src/gametest/java/io/github/zoyluo/aibot/autonomy/AutonomyGameTests.java
src/gametest/java/io/github/zoyluo/aibot/autonomy/AutonomyPhysicsGameTests.java
src/gametest/java/io/github/zoyluo/aibot/autonomy/AutonomyRestoreGameTests.java
```

Added documentation/configuration: `.gitattributes`, `AUTONOMY.md`, `AUTONOMY_TOOL_AUDIT.md`, `AUTONOMY_VALIDATION.md`.

Other material changes: `.gitignore` (track autonomy docs, ignore local build logs), `README.md` (entry link), `gradle.properties` (artifact version), `src/gametest/resources/fabric.mod.json` (test entrypoints). Existing test assertions remain unchanged. `.gitattributes` makes Java checkouts use LF because inherited source-text tests assume that line ending.

## Remaining deterministic choices and limitations

A* costs, hazard exclusions, short-drop limits, steering and collision rules select the motor route to a model-selected destination. These are the closest remaining boundary between mechanics and policy: they can refuse hazardous routes. The model can request separate bounded movement. Recipe matching, screen transactions, reach checks, mining/attack timing, timeouts and retry limits also remain deterministic.

No encoded priorities choose food, shelter, tools, resources, defense, construction, corpse recovery or progression. Legacy manual code still contains those policies and some privileged helpers, but autonomous ownership excludes it. Future capabilities need the same boundary audit.

Long routes, complicated terrain/fluids, unusual recipes, merchant transactions and other mod interactions are not comprehensively validated. Perception and memory are bounded and lossy. Real provider tool compliance, latency, costs, long-run physical reliability and model judgment must be measured in the live overnight experiment.
