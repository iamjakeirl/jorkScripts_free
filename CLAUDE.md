# JorkScripts – OSMB Script Development Project

## Project Overview
Automation scripts for the Old-School Mobile Bot (OSMB) RuneScape client. Built with **Java 17** + **Gradle**.

---

## OSMB MCP Cheat-Sheet *(search helpers)*

| Phase | Helper | Example |
|-------|--------|---------|
| 🔍 **Discover** | `search_packages(q)`, `search_methods(q)` | `search_methods("deposit")` |
| 📋 **Browse**   | `get_package_info(pkg)`, `get_related_classes(cls)` | `get_package_info("com.osmb.api.ui.bank")` |
| 🔬 **Detail**   | `get_class_info(fqcn)`, `get_method_info(fqmn)` | `get_method_info("com.osmb.api.ui.bank.Bank.deposit")` |

**Tips**
- Search simple names first, then drill into full FQNs.
- Wrap all UI actions in `submitHumanTask`; use `submitTask` for checks.
- See `reference/osmbmcp_usage_optimized.md` for complete MCP tool reference.

---

## Mandatory Folder Structure

**CRITICAL:** Scripts **must** follow this exact folder structure:
```
scripts/YourScriptName/
└── src/
    └── com/
        └── jork/
            └── script/
                └── YourScriptName/
                    └── YourScriptName.java
```
Package declaration **must** match: `package com.jork.script.YourScriptName;`

Add to `settings.gradle.kts`: `include("scripts:YourScriptName")`

---

## Mandatory Script Boilerplate (minimal)
```java
package com.jork.script.YourScriptName;  // MUST match folder structure

import com.osmb.api.script.*;
import com.jork.utils.ScriptLogger;

@ScriptDefinition(
    name = "<Title>", author = "<You>", version = 1.0,
    description = "<What it does>", skillCategory = SkillCategory.OTHER)
public class <ScriptName> extends Script {
    public <ScriptName>(Object core) { super(core); }

    @Override public void onStart() {
        ScriptLogger.startup(this, "1.0", "<You>", "<Activity>");
    }

    @Override public int poll() {
        // dispatch states here
        return random(200, 400);
    }

    @Override public void onStop() {
        ScriptLogger.shutdown(this, "User stopped script");
    }
}
```

### Lifecycle Reference
`onStart()` → once • `poll()` → loop • `onStop()` → once • `onPaint(Canvas)` → each frame • `canBreak()/canHopWorlds()` → periodic • `onGameStateChanged(GameState)` → on login/logout/hop • `onRelog()` → after disconnect/hop.

---

## Common Patterns

### Modern Polling (Preferred)
**NEVER use `Thread.sleep()`** – use modern polling methods instead:

```java
// pollFramesUntil - Pure condition checking (no delay after)
// Use for: animations, position changes, state validation
boolean arrived = pollFramesUntil(() ->
    getWorldPosition().distanceTo(target) <= 2, 5000
);

// pollFramesHuman - With human-like reaction delay after condition met
// Use for: UI interactions (clicking, opening interfaces)
boolean bankOpened = pollFramesHuman(() ->
    getBank().isOpen(), 3000
);

// With interrupt handling
try {
    boolean success = pollFramesUntil(() ->
        isAnimating(), 5000, true  // true = allow interrupts
    );
} catch (TaskInterruptedException e) {
    ScriptLogger.warning(this, "Task interrupted");
}
```

**Legacy methods (still work but avoid in new code):**
- `submitTask(() -> condition, timeout)` ≈ `pollFramesUntil`
- `submitHumanTask(() -> condition, timeout)` ≈ `pollFramesHuman`

### Navigation & Interaction
```java
// Navigation
ScriptLogger.navigation(this, "Varrock West Bank");
getWalker().walkTo(Location.VARROCK_WEST_BANK);

// Object Interaction (with null safety)
Optional<RSObject> rock = getObjectManager().getObject(obj ->
    obj.getName().equals("Copper rock") && obj.canReach()
);
if (rock.isPresent()) {
    ScriptLogger.actionAttempt(this, "Mining copper rock");
    rock.get().interact("Mine");
} else {
    ScriptLogger.warning(this, "Could not find reachable copper rock");
}
```

### Null-Safety (CRITICAL)
Many API calls return `null` or `Optional`. **Always check before use:**
```java
WorldPosition pos = getWorldPosition();
if (pos == null) {
    ScriptLogger.warning(this, "Position unavailable");
    return 1000; // Wait before retry
}
// Now safe to use pos
```

---

## Project Structure
```
jorkscripts/
├─ scripts/        # Main script implementations
├─ utils/          # Reusable helpers (ScriptLogger, Navigation, DebugDrawer, tasks/)
├─ reference/      # Guides (OSMB_SCRIPT_GUIDE.md, osmbmcp_usage_optimized.md)
└─ tasks/          # PRDs & task lists
```

---

## Key Components
### ScriptLogger
Central logging (`com.jork.utils.ScriptLogger`) – info, warn, error, debug + action/state helpers.

### Task Management
`Task` interface + `TaskManager` orchestrate modular tasks (e.g., `BankTask`, `RestockTask`).

---

## Advanced Patterns (from JorkHunter)

### UI Thread Synchronization
When JavaFX UI writes to fields read by script thread, use **volatile**:
```java
// Written by JavaFX thread, read by script thread
private volatile boolean settingsConfirmed = false;
private volatile String selectedTarget = "Unknown";
private volatile Map<String, Object> strategyOptions = null;

// Script-thread internal guards (no volatile needed)
private boolean initialised = false;

public void onSettingsSelected(String target, ...) {
    // Called from JavaFX - lightweight, no game API calls
    this.selectedTarget = target;
    this.settingsConfirmed = true;  // Signal to script thread
}

@Override
public int poll() {
    if (!settingsConfirmed) {
        return 1000; // Wait for UI confirmation
    }
    initialiseIfReady();  // Deferred initialization on script thread
    // ... rest of poll logic
}
```

### Break Management with Cleanup
Override `canBreak()` to prevent breaks during critical actions:
```java
private boolean isDrainingForBreak = false;

@Override
public int poll() {
    // Proactively check if break is due
    if (getProfileManager().isDueToBreak() && !isDrainingForBreak) {
        ScriptLogger.info(this, "Break due - entering drain mode");
        isDrainingForBreak = true;
        // Signal tasks to clear state (e.g., collect traps)
    }
    // ... normal logic
}

@Override
public boolean canBreak() {
    // Only allow break when cleanup complete
    boolean hasActiveState = checkActiveState();  // e.g., traps still out
    boolean hasPendingTransitions = checkPendingActions();

    boolean canBreakNow = !hasActiveState && !hasPendingTransitions;

    if (canBreakNow && isDrainingForBreak) {
        ScriptLogger.info(this, "Cleanup complete - allowing break");
        isDrainingForBreak = false;
    }

    return canBreakNow;
}
```

### Game State Change Handling
Clean up tracking state on logout/hop (all game objects lost):
```java
@Override
public void onGameStateChanged(GameState newGameState) {
    super.onGameStateChanged(newGameState);  // CRITICAL: call parent

    if (newGameState != null && newGameState != GameState.LOGGED_IN) {
        // Logged out - clear all game object tracking
        ScriptLogger.warning(this, "Logout detected - clearing state");
        clearAllTrackedObjects();
        resetFlags();

        // Pause failsafe timers if enabled
        if (xpFailsafePauseDuringLogout) {
            pauseXPFailsafeTimer();
        }
    } else if (newGameState == GameState.LOGGED_IN) {
        // Logged back in - resume timers
        if (xpFailsafePauseDuringLogout) {
            resumeXPFailsafeTimer();
        }
    }
}

@Override
public void onRelog() {
    ScriptLogger.info(this, "Relog detected - clearing state");
    clearAllTrackedObjects();
    resetFlags();
}
```

### Metrics Tracking with AbstractMetricsScript
Extend `AbstractMetricsScript` for built-in metrics & XP tracking:
```java
import com.jork.utils.metrics.AbstractMetricsScript;
import com.jork.utils.metrics.core.MetricType;
import com.jork.utils.metrics.display.MetricsPanelConfig;

public class MyScript extends AbstractMetricsScript {
    private final AtomicInteger successCount = new AtomicInteger(0);

    public MyScript(Object core) { super(core); }

    @Override
    protected void onMetricsStart() {
        // Replaces onStart()
        initializeMetrics();
    }

    private void initializeMetrics() {
        // Register text metric (displays at top)
        registerMetric("Activity", () -> "Mining Copper", MetricType.TEXT);

        // Enable XP tracking with skill sprite ID
        enableXPTracking(SkillType.MINING, 210);  // 210 = Mining sprite

        // Register custom metrics
        registerMetric("Total Mined", successCount::get, MetricType.NUMBER);
        registerMetric("Mined /h", successCount::get, MetricType.RATE);
        registerMetric("Success Rate", this::calcSuccessRate, MetricType.PERCENTAGE);

        // Time since XP (if failsafe enabled)
        registerMetric("Since XP", () -> {
            long ms = getTimeSinceLastXPGain();
            return (ms < 60000) ? (ms/1000) + "s" : (ms/60000) + "m";
        }, MetricType.TEXT);
    }

    @Override
    protected MetricsPanelConfig createMetricsConfig() {
        MetricsPanelConfig config = MetricsPanelConfig.darkTheme();
        config.setCustomPosition(10, 110);
        config.setMinWidth(180);
        config.setLogoImage("mylogo.png", 30);  // From JAR resources
        return config;
    }

    @Override
    protected void onMetricsPaint(Canvas canvas) {
        // Custom overlay drawing (optional)
        drawDebugInfo(canvas);
    }
}
```

### XP Failsafe Pattern
Auto-stop after X minutes without XP gains:
```java
private volatile boolean xpFailsafeEnabled = true;
private volatile int xpFailsafeTimeoutMinutes = 5;
private volatile boolean xpFailsafePauseDuringLogout = true;

@Override
public int poll() {
    // Check failsafe (after initialization)
    if (xpFailsafeEnabled && initialised) {
        long timeSinceXP = getTimeSinceLastXPGain();  // From AbstractMetricsScript
        long timeoutMillis = xpFailsafeTimeoutMinutes * 60 * 1000L;

        if (timeSinceXP > timeoutMillis) {
            ScriptLogger.error(this, "XP FAILSAFE: No XP for " +
                xpFailsafeTimeoutMinutes + " minutes - stopping");
            stop();
            return 1000;
        }

        // Warn when approaching timeout
        long warningThreshold = timeoutMillis - 60000;  // 1 min before
        if (timeSinceXP > warningThreshold) {
            long secondsLeft = (timeoutMillis - timeSinceXP) / 1000;
            ScriptLogger.warning(this, "XP Failsafe: " + secondsLeft + "s until auto-stop");
        }
    }
    // ... rest of poll
}

// Configure pause during logout
if (xpFailsafePauseDuringLogout) {
    configureXPFailsafeTimerPause(true);
}
```

### Initialization Sequence Pattern
Complex initialization after UI confirmation:
```java
@Override
public int poll() {
    // 1. Highest priority: one-time setups (zoom, camera, etc)
    if (!zoomSet) {
        setZoom();  // Set once at start
        return 0;   // Immediate retry
    }

    // 2. Wait for UI confirmation
    if (!settingsConfirmed) {
        return 1000;
    }

    // 3. Deferred initialization (on script thread, not JavaFX)
    initialiseIfReady();
    if (!initialised) {
        return 1000;
    }

    // 4. Custom anchor/tile selection (if needed)
    if (requiresCustomAnchor && !customAnchorSelected) {
        return handleCustomAnchorSelection();
    }

    // 5. Normal task execution
    return taskManager.executeNextTask();
}

private void initialiseIfReady() {
    if (initialised || !settingsConfirmed) return;

    openInventoryIfNeeded();
    calculateMaxTraps();
    showHotkeysPanel();
    initializeMetrics();

    ScriptLogger.info(this, "Initialization complete");
    initialised = true;
}
```

### Region Prioritization (Performance)
Override `regionsToPrioritise()` to pre-load game regions:
```java
@Override
public int[] regionsToPrioritise() {
    // Return region IDs based on target activity
    // Improves performance by loading game data early
    if (targetActivity.contains("mining")) {
        return new int[] { 12342, 12343 };  // Mining area regions
    }
    return new int[] {};  // Empty if no target set
}
```

### Visual Debugging (onPaint)
Draw debug overlays for development:
```java
@Override
protected void onMetricsPaint(Canvas canvas) {
    if (!initialised) return;

    // Draw tracked object positions
    for (WorldPosition pos : trackedPositions) {
        Polygon tilePoly = getSceneProjector().getTilePoly(pos);
        if (tilePoly != null) {
            canvas.fillPolygon(tilePoly, 0xFF6600, 0.4);  // Orange with 40% opacity
            canvas.drawPolygon(tilePoly, 0xFF3300, 1.0);  // Bright outline
        }
    }

    // Draw anchor/center position
    if (anchorPosition != null) {
        Polygon anchorPoly = getSceneProjector().getTilePoly(anchorPosition);
        if (anchorPoly != null) {
            canvas.fillPolygon(anchorPoly, 0x00FF00, 0.3);  // Green
            Rectangle bounds = anchorPoly.getBounds();
            if (bounds != null) {
                int cx = bounds.x + bounds.width / 2;
                int cy = bounds.y + bounds.height / 2;
                canvas.drawText("ANCHOR", cx - 25, cy - 10, 0x00FF00,
                    new java.awt.Font("Arial", java.awt.Font.BOLD, 12));
            }
        }
    }
}
```

---

## Manager Access Patterns

**CRITICAL:** Always use instance methods, NEVER static access:

| Method | Returns | Purpose |
|--------|---------|---------|
| `getWalker()` | `Walker` | Pathfinding, character movement |
| `getWidgetManager()` | `WidgetManager` | UI components (bank, inventory, settings) |
| `getObjectManager()` | `ObjectManager` | Game objects, NPCs, ground items |
| `getSceneManager()` | `SceneManager` | Tile info, scene geometry |
| `getUtils()` | `Utils` | General utilities (`getClosest`, etc) |
| `getFinger()` | `Finger` | Mobile-style tap interactions |
| `getSceneProjector()` | `SceneProjector` | World ↔ Screen coordinate conversion |
| `getWorldPosition()` | `WorldPosition` | Player's current position |
| `getProfileManager()` | `ProfileManager` | Break handling (`isDueToBreak()`) |
| `getStageController()` | `StageController` | JavaFX window management |

### Mobile Interaction Pattern
All UI interactions **must** use `getFinger().tap()` wrapped in `submitHumanTask`:
```java
// CORRECT: Mobile-style tap with human task
submitHumanTask(() -> {
    Rectangle bounds = component.getBounds();
    return getFinger().tap(bounds);
}, 2000);

// WRONG: Direct interaction without human task
component.interact("Click");  // May not work for all UI elements
```

---

## Development Guidelines
1. **Build/Test:** `./gradlew build` before committing.
2. **Code Style:** follow Google/Java conventions, functional & immutable where sensible, minimal inline comments.
3. **Logging:** always via ScriptLogger.
4. **Utilities:** Promote reuse – ≥50 % shared logic → move to `utils/`.
5. **Error Handling:** checked vs unchecked; never swallow exceptions.
6. **Naming:** Use `handle` prefix for event handlers (e.g., `handleButtonClick`).
7. **Resources:** Always use try-with-resources for AutoCloseable objects.
8. **Modern Java:** Leverage records, pattern matching, Optional, streams where appropriate.
9. **Instance Methods:** NEVER use static access for game API - always use instance methods from Script base class.
10. **Thread Safety:** Use `volatile` for fields written by JavaFX/read by script thread.

### Script Development Process
1. Create script under `scripts/`.
2. Start with boilerplate above.
3. Use MCP helpers to discover API calls.
4. Reuse `com.jork.utils` utilities.
5. Test in-client, refine logs.

---

## Critical Observations & Anti-Patterns

### DO's ✅
- **DO** use `pollFramesUntil`/`pollFramesHuman` for waiting (NEVER `Thread.sleep()`)
- **DO** use instance methods (`getWidgetManager()`, etc) - NEVER static access
- **DO** null-check everything from API calls (positions, managers, components)
- **DO** use `volatile` for fields written by JavaFX thread / read by script thread
- **DO** call `super.onGameStateChanged()` when overriding lifecycle methods
- **DO** clear all tracked game objects on logout/hop (they're all lost)
- **DO** wrap UI interactions in `submitHumanTask` with `getFinger().tap()`
- **DO** return fast from `poll()` - delegate to helper methods
- **DO** use `ScriptLogger` for ALL logging (never raw `System.out`)
- **DO** use `ExceptionUtils.rethrowIfTaskInterrupted(e)` in catch blocks

### DON'Ts ❌
- **DON'T** use `Thread.sleep()` - breaks task interruption
- **DON'T** do long-running work directly in `poll()` method
- **DON'T** interact with game API from JavaFX thread (only set flags)
- **DON'T** forget to check `null` before using API results
- **DON'T** forget to call `super.onGameStateChanged()` when overriding
- **DON'T** track game objects across logout/world hops (they're all destroyed)
- **DON'T** use static access to managers - always use instance methods
- **DON'T** swallow exceptions - use `ScriptLogger.exception()` for logging
- **DON'T** use shared mutable state between tasks without synchronization

### Visual API Considerations
- OSMB API is **vision-based** - objects must be visible on screen
- Expect `null` returns when objects are off-screen or obscured
- Components may not be immediately available after UI state changes
- Always verify component bounds before interactions (`getBounds() != null`)
- Use retry logic with delays for unreliable visual detection

### Build Commands
```bash
# Standard build
./gradlew build

# Build specific script
./gradlew :scripts:YourScriptName:jar

# Clean build with all variants (for multi-variant scripts like jorkHunter)
./gradlew clean buildAllVariants --refresh-dependencies --rerun-tasks --no-build-cache
```

---

## Important Notes
- Scripts mimic human mobile play; all clicks via `getFinger().tap(...)` inside `submitHumanTask`.
- OSMB API is *visual*; expect nulls & delays.
- Project follows patterns required by OSMB client to avoid detection.
- Utility classes ensure consistency & reuse.
- See `reference/OSMB_SCRIPT_GUIDE.md` for comprehensive API documentation.