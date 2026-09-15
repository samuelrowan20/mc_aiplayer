# Autonomy tool and task audit

This audit records the inherited code inspected before autonomy implementation. Its classifications concern behavior, not class names: the existing `LOW_LEVEL` tool group is **not** a safe autonomy allowlist. The autonomous registry is separate; legacy tools remain available only to the manually controlled mode.

Paths below are relative to `src/main/java/io/github/zoyluo/aibot/`. Method names are included so references survive edits. “Bypass” means the legacy implementation must not execute on the autonomous path. “Restrict” means reuse only the mechanical portion behind explicit target, reach, visibility, duration, and resource checks.

## Most important findings

1. `task/BotTickCoordinator.tick` originally ran `NavSafetyNet`, `StuckWatcher`, `DangerWatcher`, `GoalExecutor`, `EquipAction.equipBestArmor`, and `IdleCoordinator` for every bot. Strict survival disabled privileges, **not these strategic policies**. Autonomy needs its own branch before all of them.
2. `task/TaskManager.tickAll` runs separately and calls `SurvivalGuard.check` before executing tasks. Cancelling legacy work when autonomy starts and excluding autonomous bots from this scheduler prevents a second controller from acting.
3. `brain/BrainCoordinator.scheduleContinuation` retains delayed callbacks that can submit the old tool registry. Starting autonomy must invalidate/reset the legacy decision lease as well as cancelling tasks and goals.
4. `action/ActionPack.startPathTo` silently enables digging and pillaring. Even `startSurfacePathTo` feeds `PathExecutor`, which invokes `FakePlayerMotion.jumpTo` / `stepTo` for jump/drop nodes. Those helpers call `teleport`; destination collision checks are not proof of ordinary movement. Autonomy must use physical movement and bounded path planning without those recovery helpers.
5. `task/CraftTask.plan` recursively resolves ingredients and can add a crafting-table recipe; `ensureTable` places a station. `task/SmeltTask` can craft a furnace, choose fuel/food, and fetch fuel from base containers. Their tool descriptions understate these capabilities. Neither is a primitive.
6. `mode/ObservableWorldQuery.canObserveEntity` uses distance and `canSee`, without explicitly rejecting invisible entities. `perception/PerceptionCollector` adds selected resource highlights, hostile classification, and exact other-entity health. Autonomy needs a neutral, bounded observation format with explicit invisibility filtering.
7. `action/InteractAction.attackEntity` directly attacks its argument without its own reach/visibility checks. `MiningController.tick` checks distance but not visibility, automatically equips the best tool, and reports success based on accumulated damage before independently verifying the resulting block state. Wrappers must supply the missing checks and truthful completion semantics.

## Legacy model tool registry

Evidence: `brain/ToolRegistry.registerDefaults` and its registered lambdas / `createTask` dispatch. Every registered tool is accounted for below, including tools whose utility can be recreated safely.

| Existing tools | Class | Autonomous disposition and reason |
| --- | --- | --- |
| `look_at`, `select_hotbar`, `inventory` | Mechanical | Reuse underlying look/slot/inventory mechanics through new neutral schemas. No legacy descriptions are imported. |
| `move_to` | Mixed | Bypass wrapper: generic pathfinding permits dig/pillar recovery and a straight-line fallback. Restrict navigation to a model-selected known/visible destination and ordinary motion. |
| `mine_block` | Mixed | Restrict to one explicitly selected visible block within vanilla reach, using the model-selected held item, with a finite deadline and verified result. |
| `place_block` | Mechanical with legacy wrapper concerns | Reuse `BuildAction.placeBlock` with exact visible support face and vanilla interaction. Do not expose operator direct placement fallback or its tutorial-bearing tool description. |
| `equip_best_tool`, `equip_armor` | Mixed | Bypass automatic rankings; let the model select an inventory slot and equipment destination. |
| `plan_craft`, `craft` | Mixed/strategic | Bypass recursive plans and acquisition-source hints. A new single-recipe operation may consume only present ingredients at a currently usable station; missing requirements return failure. |
| `eat` | Mixed | Bypass `EatTask` food selection. Model selects item/slot and invokes ordinary item use. |
| `smelt` | Strategic | Bypass `SmeltTask`; explicit furnace interaction and slot transfers allow vanilla ticking to perform smelting. No station creation or fuel selection. |
| `gather`, `forage`, `provision_food` | Strategic | Bypass resource quotas, source choice, searching, and acquisition chains. |
| `strip_mine`, `mine_vein`, `mine_ore`, `achieve_goal` | Strategic | Bypass mining/progression planners, prerequisite tools, ore discovery, and vein expansion. |
| `harvest_crop`, `farm`, `harvest`, `breed` | Strategic | Bypass area/crop/animal selection and compound task state machines. Explicit single-block/item/entity interactions remain possible. |
| `achieve_armor`, `achieve_workstation`, `build_house` | Strategic | Bypass predetermined equipment sets, stations, blueprint templates, resource acquisition, site selection, and construction sequencing. |
| `set_base`, `deposit_all`, `stockpile` | Strategic/mixed | Bypass storage policies and reserved-item priorities. Neutral model-authored memory and explicitly selected slot transfers replace these. |
| `find_container`, `deposit`, `withdraw` | Mixed | Bypass automatic nearest-container choice, travel, and “keep tools” policy. Inspect/transfer only through a selected reachable open container. |
| `fish`, `trade` | Mixed | Bypass automatic water/villager/offer selection and repeat loops. Ordinary item use and selected entity interaction are mechanical; complete merchant transaction support is a separate capability. |
| `attack`, `guard` | Strategic | Bypass target selection, best-weapon/armor selection, retreat policy, and return-to-guard-point behavior. |
| `attack_entity` | Mixed | Replace type-based nearest-target selection with an observed entity ID; revalidate reach, visibility, target existence, and normal attack mechanics. |
| `sleep`, `light_area` | Strategic/mixed | Bypass bed search/placement and deterministic lighting decisions. Interacting with an agent-selected visible bed invokes vanilla sleep rules; no clock mutation. |
| `follow`, `hold` | Mixed | Bypass indefinite target tracking/holding with safety-policy interruptions. Explicit bounded navigation or purposeful wait replaces them. |
| `stop`, `pause`, `resume`, `cancel_all`, `abort_task`, `get_task_status` | Mechanical control over legacy strategy | Bypass legacy task controls; autonomy has its own pause/resume/stop/status lifecycle. |
| `post_job`, `list_jobs`, `tell_bot` | Coordination / strategic dispatch | Bypass shared jobs and routes back into legacy bot brains. |
| `say` | Mechanical communication | Not needed for physical embodiment; concise intention/purpose goes into the journal/status instead. |
| `remember`, `recall`, `forget`, `mark_place` | Mechanical memory | Reuse the concept, with bounded autonomy-owned memory and no strategy seed; no legacy registry import. |
| `goto_place` | Mixed | Bypass `MoveTask`; navigation to legitimately remembered coordinates uses the restricted primitive. |
| `resume_mining`, `mine_and_stockpile`, `recover_drops` | Strategic | Bypass automatic return, continued resource priorities, and corpse recovery. Death is an observation for a new model decision. |
| `set_goal`, `advance_goal`, `goal_status`, `assign_task` | Strategic control interface | Bypass entirely. Model-owned intention is free text/state, not a typed progression goal or deterministic task assignment. |

## Goal and task families

| Component / evidence | Class | Disposition |
| --- | --- | --- |
| `goal/Goal`, `GoalPlanner.plan`, `GoalExecutor.submit/tickBot`, `GoalStep`, predicates and snapshot collectors | Strategic | Bypass the whole goal pipeline. `GoalPlanner` contains food, torches, armor, spare picks, ore-layer and source-selection policy. Completion checks do not make the plan neutral. |
| `task/MoveTask` (`digging`, `digTick`) | Mixed | Bypass. Its separate fallback mines toward a destination after ordinary navigation fails. |
| `task/MineTask`, `GatherQuotaTask`, `OreDigTask`, `StripMineTask`, `MiningServiceTask` | Strategic | Bypass block-type searches, quotas, preparation, vein/expedition policies. A selected single-block break is the replacement. |
| `mining/OreScan`, `OreProspector`, `MiningChain`, `MiningFoodReserve`, budgets/cursors | Strategic or strategy-specific support | Do not expose or invoke for autonomous resource choice. Pure timing budgets are reusable as a concept, not their acquisition policies. |
| `task/CraftTask` + `craft/CraftingHelper.Planner.ensureItem/ensureIngredient` | Mixed | Bypass recursive planning. Recipe metadata can support one explicit currently satisfiable crafting operation. |
| `task/SmeltTask`, `craft/SmeltChain`, `AcquisitionHints` | Strategic | Bypass, including fuel preferences and advice on how missing resources are obtained. |
| `task/BuildTask`, `BlueprintLoader`, `BlueprintSchema`, `SiteFinder`, `PlaceStationsTask` | Strategic | Bypass predefined structures, flattening/site selection and station policies. Keep selected-block placement mechanics. |
| `task/FarmTask`, `IrrigateTask`, `RaidCropsTask`, `BreedTask`, `AcquireWaterTask`, `CreateObsidianTask`, `MilkCowTask` | Mixed/strategic | Bypass compound state machines. Selected-target block/entity/item interactions can reproduce individual physical steps when the model requests them. |
| `task/HuntTask`, `FishTask`, `TradeTask` | Mixed/strategic | Bypass searching/target choice, quota loops and automatic offer selection. |
| `task/CombatTask`, `CombatCore`, `GuardTask`, `EvadeTask`, `CreeperDefenseTask`, `LavaEscapeTask`, `EmergencyShelterTask`, `MiningBarricadeTask` | Strategic | Bypass automatic defense, retreat, shelter and terrain modification. No hidden survival reflex chooses an alternate goal. |
| `task/EatTask`, `SleepTask`, `LightAreaTask`, `ResupplyTask`, `StockpileTask`, `RecoverDropsTask` | Strategic/mixed | Bypass automatic selections and sequencing. In particular `SleepTask.placeBed` directly writes two block states; `waitMorning` itself respects the ordinary quorum. |
| `task/ContainerTask` | Mixed | Bypass searching/navigation and item-retention policy. Reuse no remote inventory resolution without open-container validation. |
| `task/FollowTask`, `HoldTask`, `DigDownTask`, `DescendToYTask` | Mixed/strategic | Bypass indefinite following/holding, descending excavation and associated infrastructure choices. |
| `task/AbstractTask`, `Task`, `TaskState`, `TaskStatus`, `CheckpointableTask` | Mechanical scaffolding | Types are not inherently strategic, but the autonomous executor does not need the legacy scheduler or task instances. |
| `task/DangerWatcher.scanBot`, `SurvivalGuard.check`, `NavSafetyNet.tickBot`, `StuckWatcher.tickBot`, `coordination/IdleCoordinator.tickBot` | Strategic/mixed | Exclude from autonomy. DangerWatcher handles death and assigns `RecoverDropsTask`; idle policy can resume/claim work. New mechanical timeout/failure reporting replaces recovery strategies. |

## Reusable mechanical code and restrictions

| API | Assessment |
| --- | --- |
| `action/LookAction`, `MovementAction.setForward/setStrafing/setSneaking/setSprinting/jumpOnce`, `ActionPack` movement setters / stop methods | Mechanical. Use finite durations and ordinary physics. Clearing controller input is cancellation, not a chosen strategy. |
| `action/WalkToController.tick` | Mechanical local steering, sprint/jump/sidle competence with a timeout. Retain a model-selected destination; report failure without choosing a new destination. |
| `pathfinding/AStarPathfinder`, `NeighborEnumerator`, `Standability`, `CostModel`, `DangerCheck` | Mechanical geometry planning with important restrictions: no digging/pillaring; no unknown-cell terrain oracle; no strategic resource rewards. Avoid exposing internal hazard classifications as model advice. Legacy caches must not bypass the restricted observation domain. |
| `pathfinding/PathExecutor` | Bypass legacy executor: jump/drop use direct fake-player relocation, digging/pillaring can edit terrain, and recovery can snap position. Reuse path nodes with physical steering instead. |
| `mode/FakePlayerMotion` | Do not use relocation helpers for autonomy. `stepTo`, `jumpTo`, swim/edge/centering methods have direct position writes despite being outside the privileged capability matrix. |
| `action/BuildAction.placeBlock` | Reusable: proves an exact visible support-face ray hit and reach, then calls vanilla `interactBlock`. No material/station acquisition. Require explicit held block and target. |
| `action/MiningController` | Restrict: one chosen target, explicit selected tool, LOS/reach on every tick, timeout, post-break verification, normal abort. No automatic tool selection or pickup. |
| `action/InteractAction` | Restrict entity actions to observed entity ID and current reach/visibility. Air use via `interactionManager.interactItem` is mechanical; sustained use needs bounded lifecycle handling. |
| `action/InventoryAction.selectHotbar/equipFromSlot/dropSlot` | Reusable after input/slot checks. `equipFromSlot` may move a selected stack into the hotbar as interface mechanics. Do not call `findFoodSlot`, `dropJunk`, `dropJunkUntilFreeSlots`, or `giveItem`. |
| `action/EquipAction`, `ToolSelector` | Do not use “best” selectors. They encode item rankings. Equip an explicitly chosen slot instead. |
| `action/ContainerAction.resolve/depositOne/withdrawOne` | Not safe directly: resolve reads arbitrary block entities and generates loot; chest resolution passes `ignoreBlocked=true`. Use vanilla open-screen validation and selected slot movement, including furnace slot insertion rules. |
| `action/EatAction`, `FarmAction`, `HarvestCore`, `BucketAction`, `MilkCowAction`, `DigNav`, `BlockMiner`, `MaterialPalette` | Not autonomous entry points. Prefer generic selected item/block/entity interactions; any future reuse requires its own reach, resource-choice, mutation and fallback audit. |

## Perception and strict survival boundary

`CapabilityPolicy.decide` denies every `PrivilegedCapability` in `STRICT_SURVIVAL`; the enum covers hidden scans, emergency teleport, forced pickup and manual teleport. This is a useful foundation but is not a proof that all direct world mutations, time changes, or relocation helpers are gated.

`ObservableWorldQuery.canObserveBlock` performs range-limited face raycasts. `canObserveCell` permits visible air cells. These are useful building blocks when the strict profile is enforced independently of provider arguments. Invisible entities need an additional filter. Neutral block/entity facts should not inherit `PerceptionCollector.addHighlights` categories such as nearest tree/ore/bed or the `Monster` danger classification. Exact non-player entity health is not an ordinary visual observation.

Any remembered destination must originate in a previous legitimate observation, actual player position, or model-authored note based on those facts. A model-supplied coordinate is not proof that the bot has seen it. Navigation may use immediate collision geometry as motor competence, but must not return hidden ores, unseen structure information, unexplored chunk contents, or routes inferred from a hidden-world search. Visibility masking before search is stronger than filtering the final path afterward.

## Death, cancellation and thread ownership

`AIPlayerEntity.tick` executes vanilla entity/player ticks and `ActionPack.onUpdate`; no strategic choice is made there. `AIBotMod` runs `TaskManager.tickAll` before `BotTickCoordinator`. Both scheduling points matter.

Legacy `AIPlayerManager.respawnDeadBot` revives the same entity and teleports it to world spawn in strict mode. It does not implement the full vanilla player replacement / bed-or-anchor respawn lifecycle. Autonomous death handling must use the normal lifecycle and must not call `DangerWatcher`'s automatic recovery assignment. `RuntimeLifecycleCoordinator.onBotDeath` also suspends legacy goals for later continuation; autonomous state must be separated from that recovery policy.

All world reads, inventory transactions, controller ticks and result application belong on the server thread. Only immutable observations and bounded request payloads should enter provider workers. Cancellation must invalidate pending provider responses as well as stop action inputs, breaking progress, and sustained item use.

## Evidence and validation limits

This is a source audit, not a live-world proof. Inspected files include the registry/dispatcher, goal planner, crafting planner/tasks, movement/mining/interaction/inventory/container/build actions, observation collector and policy, path executor, tick/task coordinators, player entity, manager respawn, lifecycle coordinator, and selected task implementations named above. Families classified from registry dispatch or call sites were not each executed in Minecraft. No test suite was run for this read-only audit; implementation and runtime validation results belong in `AUTONOMY.md` and the final test report.

The remaining deterministic knowledge permitted below the model is interface/motor competence: recipe matching for a model-selected available recipe, legal slot transactions, collision geometry, attack/mining timing, and block interaction rules. Item priorities, resource acquisition, retreat/defense choice, construction plans, and recovery of death drops are excluded.

## Implemented boundary following the audit

`autonomy/AutonomyEmbodiment` is a separate explicit action allowlist. It reuses `WalkToController`, exact-face `BuildAction`, selected-slot `InventoryAction`, and `MiningController` with automatic tool selection disabled. `MechanicalCrafting` operates one named vanilla recipe through the current crafting screen. Containers and furnaces use ordinary screen slot transactions. It never instantiates legacy `Task` or `Goal` objects.

The observation-scoped `AStarPathfinder` constructor accepts a readable-cell predicate. It forces no digging/pillaring, checks exact endpoints without snapping, and bypasses the shared path result cache. `NeighborEnumerator` proves permission for feet, head, support and transition cells **before** querying their topology. A per-search cache bounds repeated visibility raycasts. The executor follows these nodes with physical walking; it never uses `PathExecutor` or `FakePlayerMotion` relocation. The preexisting `Standability` hazard exclusions, short-drop limit, and ordinary movement costs remain deterministic motor constraints. They can prevent the agent from choosing a hazardous navigation route; direct bounded movement remains a separate model-selected capability. No hazard recommendation or objective priority is returned to the model.

New `NeighborVisibilityTest` cases use a null world as an access tripwire: denied cells and endpoints must return without touching world state. `AutonomyGameTests` adds actual server-world tests for observation filtering, restricted path-cache isolation, primitive failures without mutation, chosen-item mining, direct available crafting versus missing prerequisites, normal death/respawn, and the continuing wait/action/failure loop with a scripted provider. Merely adding these tests is not evidence that they passed; consult the recorded build/GameTest results.
