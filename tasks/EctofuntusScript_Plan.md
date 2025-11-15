# Ectofuntus Script - Development Plan

**Script Name:** Ectofuntus
**Author:** jork
**Category:** Prayer Training
**Status:** Planning Phase
**Last Updated:** 2025-11-13

---

## Table of Contents
1. [Overview](#overview)
2. [Script Logic Flow](#script-logic-flow)
3. [File Structure](#file-structure)
4. [Core Features](#core-features)
5. [Task Breakdown](#task-breakdown)
6. [Configuration System](#configuration-system)
7. [Safety Features](#safety-features)
8. [Metrics & UI](#metrics--ui)
9. [Development Roadmap](#development-roadmap)
10. [Technical Implementation Notes](#technical-implementation-notes)

---

## Overview

The Ectofuntus script automates prayer training at the Ectofuntus in Port Phasmatys. It manages the entire loop of banking, teleporting, collecting slime, grinding bones, worshipping, and returning to the bank while respecting OSMB best practices such as null safety, manager instance usage, and modern polling helpers.

### Basic Logic Loop

1. **Bank Setup:** Inventory contains ectophial, teleport method if configured, and matching counts of bones, empty pots, and empty buckets (or pre-filled slime buckets if opted in).
2. **Teleport:** Use the ectophial to travel to the Ectofuntus, validating location changes via pollFramesUntil.
3. **Resource Collection:** Randomly decide which half of the prep comes first when both slime and bone tasks are pending. Complete both slime collection in the basement and bone grinding on the top floor.
4. **Worship:** Combine bonemeal and slime on the altar until inventory is drained, keeping a running count of bones processed.
5. **Return to Bank:** Either teleport (Ring of Dueling to Castle Wars) or walk to Port Phasmatys depending on the configuration.
6. **Repeat:** Loop until supplies deplete, XP failsafe trips, or a break/log-out event occurs.

### Location Layout

- **Basement Floor:** Slime pool.
- **Middle Floor:** Ectofuntus altar where the ectophial teleport lands.
- **Top Floor:** Bone grinder assembly (hopper, grinder wheel, bin).

---

## Script Logic Flow

- Start in banking mode: validate supplies, restock inventory, and clear flags.
- Teleport via ectophial: confirm arrival and choose the resource order.
- Run slime and bone tasks in whichever order is pending, ensuring both complete.
- Worship once both resources are ready.
- Return to the bank (teleport or walk) and repeat.

---

## File Structure

- `scripts/Ectofuntus/` root module with its own `build.gradle.kts` that mirrors the repo standard: apply Java plugin, depend on `project(":utils")`, configure `sourceSets` so `src` is the java root, and copy the produced jar into `~/.osmb/Scripts` inside `jar { doLast { ... } }` exactly as the jorkHunter module does.
- `src/com/jork/script/Ectofuntus/Ectofuntus.java` main class extending `AbstractMetricsScript` with `@ScriptDefinition` metadata.
- `config/` for `EctoConfig`, enums (`BoneType`, `BankLocation`, optional `TeleportMethod`).
- `tasks/` for discrete task classes with a priority-based manager.
- `ui/` for a JavaFX configuration panel hooking into the standard script options workflow.
- Resources (images, config defaults) belong under `src/resources` or `src/main/resources` per repo norms.

---

## Core Features

### Phase 1 (MVP)

- Single bone type selection via UI drop-down.
- Banking using Ring of Dueling to Castle Wars plus an option to walk to Port Phasmatys when teleports are disabled.
- Task manager that enforces the priority order listed in the logic flow.
- Prayer XP tracking, bones processed, and runtime metrics through `AbstractMetricsScript`.
- XP failsafe (auto-stop when no Prayer XP for configurable minutes, pausing the timer during logout or breaks).
- Supply checking that halts gracefully when bones or containers run out in the bank.
- Randomized decision regarding slime-first vs bones-first whenever both tasks are pending to mimic human variability.
- Metrics panel with XP/hour, bones/hour, time since last XP, and current activity labels.
- Break handling that drains the current inventory cycle before allowing `canBreak()` to return true.

### Phase 2 (Future Enhancements)

- Additional bank teleports: Camelot, Drakan's Medallion, Amulet of the Eye, Amulet of Glory, Ring of Wealth, etc.
- Advanced bone selection (priority lists, auto-detect available bones, multi-bone rotations).
- QoL: profit tracking, death detection, slime bucket banking, auto-refill of ectophial, more metrics.

---

## Task Breakdown

### Task Priority System

`poll()` remains a light dispatcher: evaluate each task’s `canExecute()` in priority order (bank → teleport → slime → bones → worship → refill/cleanup) and run the first eligible one. Each task returns an appropriate poll delay based on the action performed, typically 200–1000 ms, with longer waits after banking and shorter waits after navigation taps.

### 1. BankTask (Priority 1)

- **Conditions:** `shouldBank` flag set, inventory missing required sets, or returning from worship with empty inventory.
- **Logic:**
  - Open bank via manager instance methods only after verifying null safety on widgets and player position.
  - Deposit everything except ectophial/teleports, then withdraw matched sets of bones, empty pots, and buckets. Respect `BoneType` configuration for item IDs.
  - Run supply checks: ensure bones available in bank, containers available, ectophial present; stop script with ScriptLogger error if anything is missing.
  - Set `hasSlime`/`hasBoneMeal` flags to false and clear `shouldBank` when inventory is set.
  - Use `pollFramesHuman` to wait for bank state changes and `pollFramesUntil` for inventory validation. Avoid `Thread.sleep` entirely.

### 2. TeleportTask (Priority 2)

- **Conditions:** Player is at bank with a loaded inventory and `teleportReady` flag true.
- **Logic:**
  - Use the ectophial item (via standard item-on-object helper) while wrapping UI interactions in `submitHumanTask` only if the API requires a quick legacy compatibility shim; otherwise prefer `pollFramesHuman` for click/wait cycles.
  - Confirm teleport success by null-safe reading of `getWorldPosition()` and verifying the plane/region matches Ectofuntus. Retry once if needed, then escalate to logging and walking fallback.
  - After arrival, decide the processing order (slime first vs bones first) randomly if neither resource has been processed yet. Log the decision for debugging.

### 3. CollectSlimeTask (Priority 3/4)

- **Conditions:** Inventory contains empty buckets, `hasSlime` false, and either slime-first is chosen or bonemeal already ready.
- **Logic:**
  - Navigate to basement using the Navigation utility plus `navigateToPlane` helper (see Technical Notes) instead of bespoke walker loops.
  - Interact with the slime pool by selecting an empty bucket, confirming the target selector activates, and tapping the pool via `getFinger().tapGameScreen` using scaled tile cubes (rectangles are fragile at Ectofuntus). Re-validate each tap until bucket counts equal target amounts.
  - Keep per-interaction null checks on world position, RSObject handles, and bounds. Use `pollFramesUntil` to verify bucket-of-slime count increments before moving on.
  - Set `hasSlime` true when counts align and log success.

### 4. GrindBonesTask (Priority 3/4)

- **Conditions:** Inventory holds bones and empty pots, `hasBoneMeal` false, and either bones-first was selected or slime already ready.
- **Logic:**
  - Navigate to the top floor (plane 2) via the shared helper; log warnings if stairs/ladder objects cannot be resolved after retries.
  - Use the standard item-on-object flow for bones on hopper, operate the grinder wheel, and collect bonemeal from the bin while respecting UI interactions and walker fallback when objects are off-screen.
  - Validate that bonemeal count equals pot count and set `hasBoneMeal` true. If counts mismatch, re-run the grinder cycle or log a recoverable warning.

### 5. WorshipTask (Priority 5)

- **Conditions:** Both `hasSlime` and `hasBoneMeal` true and inventory holds matching counts of bonemeal and slime.
- **Logic:**
  - Return to the altar floor if needed, step within interaction range, and repeatedly use bonemeal on the altar followed by slime usage logic until inventory is empty. Always tap using tile cubes and `pollFramesHuman` for menu confirmations.
  - Update bones processed metrics, monitor XP gains (fail-safe needs accurate `getTimeSinceLastXPGain()`), and log success before setting `shouldBank` true for the next loop.

### 6. RefillEctophialTask / Cleanup

- **Conditions:** Detect empty ectophial or manual refill request.
- **Logic:**
  - Flagged as a low-priority task that routes the player to the refill shrine when necessary, ensuring break handling doesn’t leave the player stranded without teleport options.

---

## Configuration System

- JavaFX UI collects: bone type, bank method, xp failsafe minutes, enable/disable slime bucket banking, teleport fallback preference, debug logging toggle, and break-drain behavior.
- UI communicates selections via thread-safe `volatile` fields and `onSettingsConfirmed(EctoConfig config)` callback similar to JorkHunter. The script thread waits for `settingsConfirmed` before initializing tasks.
- Provide sensible defaults: XP failsafe enabled at 5 minutes, Ring of Dueling banking, dragon bones, expedite features off.

---

## Safety Features

1. **XP Failsafe:** Using `AbstractMetricsScript` timers, halt if no Prayer XP gained for X minutes (configurable). Log warnings one minute prior to timeout. Pause/resume timers on logout/break events.
2. **Supply Checking:** Verify bones, pots, buckets, and ectophial presence before every cycle. Stop the script with clear logging when supplies run out instead of looping indefinitely.
3. **Break Handling:** Monitor `getProfileManager().isDueToBreak()`. When true, enter a “drain mode” flag that stops laying new work, finishes the cycle, empties inventory, then allows `canBreak()` to return true only when inventory is clear and location is safe. Optionally trigger expedited worship completion using a probability slider similar to JorkHunter’s expedite logic.
4. **Logout Recovery:** On logout or relog, reset internal flags (`hasSlime`, `hasBoneMeal`, `shouldBank`) and re-validate inventory to avoid stale state.

---

## Metrics & UI

- **Metrics:** Activity label (based on active task), Prayer XP, Prayer XP/hour, Bones Used, Bones/hour, Time Since XP (in seconds/minutes), and runtime. Register via `registerMetric` on `initializeMetrics()` and enable XP tracking for `SkillType.PRAYER`.
- **Panel Config:** Use `MetricsPanelConfig.darkTheme()` with a custom position (e.g., 10x110) and width large enough for all labels. Optional logo asset can be added later.
- **UI Layout:** Simple grouped sections: Bone selection, Banking method, Teleport options, XP failsafe settings, Slime/Bone handling toggles, Debug logging, and Break handling. All interactions should be lightweight (no game API calls) and simply set config fields plus a `settingsConfirmed` flag.

---

## Development Roadmap

### Phase 0: Preparation (Day 0-1)
- Confirm requirements, review existing OSMB patterns, gather item IDs, and capture screenshots/layout notes for UI.

### Phase 1: MVP Skeleton (Day 2-10)
- Create module structure and Gradle wiring.
- Implement `Ectofuntus.java` skeleton with `@ScriptDefinition`, `onStart`, `poll`, `onStop`, and metric initialization.
- Build configuration model + UI stub, thread-safe communication, and basic task manager stub.

### Phase 2: Core Tasks (Day 11-20)
- Flesh out Bank, Teleport, CollectSlime, GrindBones, Worship tasks with full logic, null-safety, and polling helpers.
- Integrate Navigation helper methods for multi-floor movement and local walking.
- Ensure break handling and XP failsafe operate within real task flow.

### Phase 3: Polish & Safety (Day 21-25)
- Add extensive ScriptLogger coverage, error recovery, and supply checks.
- Validate interactions on real client (pairing with AI assistant) to tune poll delays.
- Confirm metrics and UI behave as expected.

### Phase 4: Testing & Documentation (Day 26-28)
- Run repeated end-to-end cycles, test logout/relog recovery, bank depletion, break entry/exit, xp failsafe triggering, and manual interventions.
- Update plan with findings, document configuration instructions.

### Phase 5: Release (Day 29-30)
- Build release jar via `./gradlew :scripts:Ectofuntus:jar`.
- Prepare release notes, changelog, and announcement once validation passes.

Future phases cover extended banking teleports, advanced bone handling, slime bucket banking, profit tracking, and death recovery.

---

## Technical Implementation Notes

1. **Null Safety:** Every access to `getWorldPosition()`, `getWidgetManager()` derivatives, inventory/bank search results, and object bounds must guard against `null`. If data is unavailable, log a warning and return a safe poll delay instead of risking NPEs.
2. **Polling Helpers:** Use `pollFramesUntil` for pure state checks and `pollFramesHuman` for UI/interaction confirmation. Reserve `submitHumanTask` only when compatibility with existing helper functions demands it, otherwise favor the modern helpers described in the OSMB guide.
3. **Screen Interaction Pattern:** Prefer `getFinger().tapGameScreen` with scaled tile cubes or polygons (as in `TrapInteractionHandler`) instead of tapping bare `Rectangle` bounds. This improves reliability when interacting with the slime pool, hopper, grinder, bin, and altar.
4. **Navigation Utility:** Reuse `com.jork.utils.Navigation` for walker logic and obstacle handling rather than recreating pathfinding loops. Add thin wrappers for “navigate to plane” flows that call this utility and only encapsulate staircase/ladder handling specifics.
5. **Build Configuration:** Follow the same jar packaging approach as `scripts/jorkHunter/build.gradle`: include utils output, exclude variant-specific classes (none for MVP), copy the jar to `~/.osmb/Scripts`, and use Java 17 toolchain. Remember to add `include("scripts:Ectofuntus")` in `settings.gradle.kts`.
6. **Logging Standards:** Use `ScriptLogger.startup`, `navigation`, `actionAttempt`, `success`, `warning`, `error`, `state`, and `exception` consistently. Provide concise context (e.g., which object failed to resolve) to speed up debugging during pair-coding sessions.
7. **Thread Safety:** Mark UI-shared fields as `volatile` and avoid any game API calls from JavaFX threads. Initialization happens lazily inside `poll()` once `settingsConfirmed` is true.
8. **Metrics Integration:** Call `enableXPTracking(SkillType.PRAYER, spriteId)` early, register metrics for bones processed/rate, and expose `getTimeSinceLastXPGain()` data in the panel so the failsafe timer is visible.

---

**Document Version:** 2.0 (Text-only outline per request)
**Author:** jork
**Date:** 2025-11-13
