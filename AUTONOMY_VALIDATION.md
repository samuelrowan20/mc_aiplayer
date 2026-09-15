# Autonomy implementation and validation

Fork: [samuelrowan20/mc_aiplayer, codex/continuous-autonomy](https://github.com/samuelrowan20/mc_aiplayer/tree/codex/continuous-autonomy).

Based on upstream commit `a029fa6a3760fd0f83834c104051b041d986da60`. Minecraft remains **1.21.3**. The new mode owns a separate observe/decide/act loop and bypasses the strategic goal/task schedulers. It retains the existing player, action interfaces, restricted A* planner, and atomic runtime persistence. Manual assigned-goal mode remains available.

## Validation scope

### Ollama compatibility update (autonomy.2)

The local Ollama 0.32.13 / Qwen3 4B test exposed unsuccessful tool-call responses. Added optional `decisionFormat: "json_schema"`, preserving the default tool-call protocol. Both formats validate the same typed decision and use the same physical allowlist. Complete action/wait schema branches fixed the local grammar behavior; a schema with shared root properties beside `oneOf` did not enforce the intended decision shape in this environment.

Validation for this update:

- `gradlew --no-daemon test --tests io.github.zoyluo.aibot.autonomy.AutonomyProviderTest remapJar`: successful in 38 seconds, **10 provider tests passed**.
- Final `gradlew --no-daemon remapJar` after adding the effort override: successful in 34 seconds. Artifact SHA-256: `94a8c9b71aebe90f74966f1557dafd72f7d8c67650c4a923d4411c2c568c50c4`.
- A final standalone harness using the actual provider implementation and all **16 capability definitions** parsed a live local-model decision: **44,932 ms**, **1,308 prompt tokens**, **112 completion tokens**, a model-authored intention and bounded `inspect` action.
- Endpoint `http://localhost:11434/v1`; model alias `qwen3:4b-aibot` reuses original `qwen3:4b` weights/template with a 16,384-token context. Configuration uses JSON schema, `reasoningMode: openai`, autonomy-specific `reasoningEffort: none`, 1,024 output tokens and a 180-second timeout. The effort override bypasses legacy DeepSeek-only effort-name normalization.
- This is a live inference/parse check with synthetic observations. It does **not** establish in-game behavior with this model or overnight performance. The prior Minecraft tests below were not rerun for this provider/configuration-only change.

Changed in this update: `AutonomyProvider.java`, `AutonomyCoordinator.java`, `AutonomyProviderTest.java`, `gradle.properties`, `AUTONOMY.md`, and this report. Artifact: `build/libs/aibot-0.0.1-autonomy.2.jar`. Local evidence: `build-ollama.log`, `build-ollama-package.log`, provider JUnit XML, and the development workspace's `.tools/ollama-check/final-provider-result.log`.

### Original autonomy.1 clean build

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

## Groq budget update: autonomy.3 (2026-09-15)

- Scoped Gradle run passed all 48 autonomy tests and produced the remapped production jar. A separate legacy Groq guard test passed (49 tests total). No physical-action implementation changed, so the earlier world-backed suite was not repeated.
- Six context tests cover bounded relevant observations, memory, recent events, target retention, and screen inventory deduplication.
- Ten budget tests cover persistent reservations, settlement, rolling expiry, concurrent admission, malformed storage, and fail-closed behavior. Six provider/engine integration tests use loopback HTTP to verify pre-send reservation, actual usage refund, missing/malformed usage retention, shared admission and 429 cooldown, HTTP-date Retry-After, restart recovery, and overflow-safe deferral. The six integration tests passed again after adding recovery from an oversized request when configuration is corrected and the engine restarted.
- Two live requests to `qwen/qwen3.8-27b` using synthetic empty observations and the 16-action schema returned HTTP 429, including one retry after the first cooldown. Both persisted conservative reservations and cooldowns. Successful Groq decision parsing and in-game behavior remain unverified.
- Installed jar SHA-256: `a8c818bf7b34598931a620a150ca3e625052c6fa4fece34215c1638889365443`. The instance's old runtime and sources jars were moved to its backup folder, and the new jar's installed hash matched the build.

## Groq pacing fix: autonomy.4 (2026-09-15)

The live `.3` instance received valid Groq decisions, including movement and inspection, but repeatedly sent another request about two seconds after completion. Prompts contained roughly 4,300 tokens, so consecutive requests could exceed the documented 8K-token minute limit. The bot resumed after cooldowns; model-selected movement and look actions also encountered limited displacement or reach failures.

The `.4` provider reserves a shared 60-second interval atomically with token admission. The interval survives settlement and restart. Waiting for that interval does not consume another reservation or increment the provider-failure counter. All 49 autonomy tests passed, including the new pacing persistence test, and `remapJar` succeeded. Live validation of this pacing change remains pending. No additional paid/provider inference was used for these tests.

## Confirmed Groq output-limit correction (2026-09-15)

After `.4` pacing, a diagnostic request still returned 429 with `rate_limit_exceeded`: the account's output tokens per minute limit was 1,000 while the request allowed 1,024. `Retry-After` was absent and the combined token-limit header showed 8,000 tokens available. This confirmed an oversized output allowance as a cause of rejection; the earlier minute-spacing hypothesis did not explain this error.

The local configuration and Groq setup instructions now use `maxTokens: 512`. A live synthetic request through the actual provider and shared budget returned a valid inspect decision in 1,540 ms, with 3,178 prompt tokens and 138 completion tokens. The ledger retained prior uncertain reservations. No production Java changed for this correction; Minecraft must restart to load the new configuration. In-game behavior with the corrected allowance still needs verification.

## Rejected-request accounting fix: autonomy.5 (2026-09-15)

The local guard deferred Bob for nearly 24 hours with 34,842 confirmed tokens and 153,749 tokens reserved for unconfirmed outcomes. Eight reservations totaling 109,098 tokens were matched to recorded direct Groq 429 responses and settled as rejected requests. Three unresolved reservations and every confirmed charge were retained, leaving 120,507 tokens available. The original ledger and reconciliation receipt were preserved locally; the 200K limit was not changed.

The provider now releases reservations for explicit Groq rate-limit admission rejections. Malformed errors, unknown error types, and responses containing usage or choices retain their reservations. Explicit stop/start also clears the engine's stale deadline so durable admission can be checked again. All 50 autonomy tests passed, including rejection classification and a restart check proving a still-insufficient budget cannot send HTTP. The remapped jar was installed with matching SHA-256 `2814c8d192fd95b7f66d4c698f5964456203d58252c87f3accde2789e7f76235`. No new live inference was needed for these checks.

## Observation instructions: autonomy.6 (2026-09-15)

The live model chose two consecutive `look` actions while describing a survey of its surroundings. Both completed without translation. The observation implementation samples fixed world directions regardless of facing, so those turns did not expand the observation coverage. The system prompt and inspect/look descriptions now state that observations refresh automatically, look changes only facing, and coordinates are absolute. This change does not select a movement or survival strategy for the model. Provider regression tests and jar packaging are used for this metadata-only change; actual model action selection after the correction remains to be observed in-game.

## Local Qwen integration: autonomy.7 (2026-09-15)

The instance now uses local `qwen3:4b-aibot`, 8,192 context tokens, processing batch 128, thinking disabled, JSON-schema responses, and a 512-token output cap. Ollama uses q8 context cache and Flash Attention. The original model weights/template and Groq budget ledger were preserved. Local decision spacing is one tick after action completion; the local endpoint has no Groq quota or one-minute pacing.

A first grammar-only test produced an invalid movement magnitude. The provider now includes action descriptions, argument types/ranges and required fields in a compact text reference for JSON-schema mode; ordinary tool-call prompts remain unchanged. All 11 provider regression tests passed, including reference visibility. Two final local synthetic decisions parsed successfully: 28,699 ms with 1,065 prompt/131 output tokens, then 10,459 ms with 1,089 prompt/106 output tokens. Both selected navigation coordinates; these were protocol tests and did not prove a traversable Minecraft route. In-game speed and model judgment remain unverified. The model still spans CPU and GPU memory on this laptop.

The remapped jar was installed with matching SHA-256 `6ac717f27aff5cf560dcacd2191c598a4f5b4b92660325a4dd186b919ecda5a0`. No Groq inference tokens were used for this switch.

## Physical no-op feedback: autonomy.8 (2026-09-15)

The live local model repeatedly selected the same look target and once requested movement with both directional inputs zero and jumping disabled. Those actions were reported as successes despite no physical change. Repeated look now reports `already_facing_target`; zero movement reports `zero_movement_input`. Bounded movement measures distance traveled and reports `movement_blocked` below 0.05 blocks. These results feed the existing failure memory and repetition guard. The model still chooses its next action.

All 24 engine/provider unit tests passed. Three scoped Minecraft harness tests passed: distinct and repeated looks with zero-input rejection and jump-only acceptance; real wall collision with failure delivered to the next decision; and the existing continuous loop with actual movement and failure recovery. The harness reported no failed tests after completion. Jar packaging succeeded with SHA-256 `7ea5334b70b23f31d065e823319a89ea728fead5082ac325cfb4791dc2cfed8d`. Live model behavior with this feedback remains unverified.

## Remaining deterministic choices and limitations

A* costs, hazard exclusions, short-drop limits, steering and collision rules select the motor route to a model-selected destination. These are the closest remaining boundary between mechanics and policy: they can refuse hazardous routes. The model can request separate bounded movement. Recipe matching, screen transactions, reach checks, mining/attack timing, timeouts and retry limits also remain deterministic.

No encoded priorities choose food, shelter, tools, resources, defense, construction, corpse recovery or progression. Legacy manual code still contains those policies and some privileged helpers, but autonomous ownership excludes it. Future capabilities need the same boundary audit.

Long routes, complicated terrain/fluids, unusual recipes, merchant transactions and other mod interactions are not comprehensively validated. Perception and memory are bounded and lossy. Real provider tool compliance, latency, costs, long-run physical reliability and model judgment must be measured in the live overnight experiment.
