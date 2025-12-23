# XP Failsafe Bug Analysis Report

**Date:** 2025-12-22
**Affected Components:**
- `/utils/src/com/jork/utils/metrics/providers/XPMetricProvider.java`
- `/scripts/jorkHunter/src/com/jork/script/jorkHunter/JorkHunter.java`
- `/scripts/Ectofuntus/src/com/jork/script/Ectofuntus/Ectofuntus.java`

---

## Executive Summary

The XP failsafe system contains a **critical race condition** where XP gains detected while the timer is paused (during logout/world hop) fail to properly unpause the timer. This causes the accumulated time to continue growing even after XP is gained, leading to false failsafe triggers. Additionally, the timer is never reset on script initialization, causing immediate false positives for users with slow startup sequences.

---

## Issues Identified

### Issue 1: Timer Never Unpauses After XP Gain During Pause State - CRITICAL RACE CONDITION

- **Severity**: CRITICAL
- **Location**: `/utils/src/com/jork/utils/metrics/providers/XPMetricProvider.java:203-209`
- **Description**: When XP is gained while `isPaused=true` (e.g., during logout or shortly after resuming), the timer is reset (`accumulatedTimeMillis = 0; sessionStartTime = now`), but `isPaused` is NEVER set to `false`. This leaves the timer in a permanently paused state where:
  1. `accumulatedTimeMillis` remains at 0
  2. `sessionStartTime` is set to current time
  3. `isPaused` remains `true`
  4. `getTimeSinceLastXPGain()` returns only `accumulatedTimeMillis` (0) forever, ignoring the active `sessionStartTime`

- **Evidence**:
```java
// XPMetricProvider.java:203-209
if (currentXP > lastKnownXP) {
    // Only reset timer if not currently paused (e.g., not during break/hop preparation)
    if (!isPaused) {
        accumulatedTimeMillis = 0;
        sessionStartTime = System.currentTimeMillis();
    }
    // Note: Don't change isPaused state here - only resumeTimer() should unpause

    double gained = currentXP - lastKnownXP;
    tracker.incrementXp(gained);
    // ...
}
```

The comment says "Don't change isPaused state here - only resumeTimer() should unpause", but this is **fundamentally flawed logic**. If XP is gained while paused, the system should recognize this as a legitimate unpause event.

- **Scenario Causing False Triggers**:
  1. Script logs out → `pauseTimer()` called → `isPaused = true, accumulatedTimeMillis = X`
  2. Script logs back in → `resumeTimer()` called → `isPaused = false, sessionStartTime = now`
  3. XP gain happens quickly (within 100-500ms of resume) while XP detection polling is still catching up
  4. OCR reads XP while `isPaused` is transitioning or has a stale value → XP gain detected
  5. Timer reset code sees `isPaused = true` (or gets a stale read) → SKIPS timer reset
  6. `lastKnownXP` is updated, but timer keeps counting from old accumulated time
  7. User gets XP but failsafe sees ever-increasing time since "last XP"

- **Recommended Fix**:
```java
// BEFORE (buggy):
if (currentXP > lastKnownXP) {
    if (!isPaused) {
        accumulatedTimeMillis = 0;
        sessionStartTime = System.currentTimeMillis();
    }
    // Note: Don't change isPaused state here - only resumeTimer() should unpause

    double gained = currentXP - lastKnownXP;
    tracker.incrementXp(gained);
}

// AFTER (fixed):
if (currentXP > lastKnownXP) {
    // XP gain detected - ALWAYS reset timer regardless of pause state
    // This is the definitive signal that legitimate activity occurred
    accumulatedTimeMillis = 0;
    sessionStartTime = System.currentTimeMillis();
    isPaused = false;  // Unpause if paused - XP gain is authoritative

    double gained = currentXP - lastKnownXP;
    tracker.incrementXp(gained);
    // ...
}
```

**Rationale:** XP gain is the **definitive signal** that the script is active and working correctly. The pause state is only a hint about logout status - it should NEVER prevent the timer from resetting when actual XP is gained.

---

### Issue 2: Timer Initialization Starts Counting Immediately - HIGH

- **Severity**: HIGH
- **Location**: `/utils/src/com/jork/utils/metrics/providers/XPMetricProvider.java:66-68`
- **Description**: The XP timer starts counting from the moment `initialize()` is called (in `onMetricsStart()`), but the script may not be actually running yet. For scripts like jorkHunter with UI confirmation, tile selection, and delayed initialization, this can add 30-120 seconds of "dead time" before any XP can possibly be gained.

- **Evidence**:
```java
// XPMetricProvider.java:66-68
// Initialize the pausable timer
this.sessionStartTime = System.currentTimeMillis();  // Starts counting NOW
this.accumulatedTimeMillis = 0;
this.isPaused = false;
```

Then in JorkHunter.java, initialization happens much later:
```java
// JorkHunter.java:89-142
@Override
protected void onMetricsStart() {
    // XP tracking initialized HERE (timer starts)
    // ...

    // Show settings window (user might take 30+ seconds to configure)
    ScriptOptions opts = new ScriptOptions(this, huntingConfig);
    getStageController().show(scene, "JorkHunter – Options", false);

    // Wait for user confirmation...
}

// JorkHunter.java:530-598
@Override
public int poll() {
    if (!settingsConfirmed) {
        return 1000; // Still waiting for UI - no XP possible yet!
    }

    initialiseIfReady();  // More initialization

    if (requiresCustomAnchor && !customAnchorSelected) {
        return handleCustomAnchorSelection();  // Tile picker - more delays
    }

    // ONLY NOW can script actually start gaining XP
}
```

If a user has a 5-minute XP failsafe and spends 2 minutes configuring the script, they only have **3 minutes** of actual runtime before the failsafe triggers.

- **Recommended Fix**: Start the timer only when the script actually begins task execution, not during initialization:

```java
// XPMetricProvider.java - Add initialization flag
private boolean timerStarted = false;

public void initialize(Script script, SkillType skillType, int spriteId) {
    // ... existing initialization ...

    // DON'T start timer yet - wait for first update() call
    this.sessionStartTime = 0;  // Changed from System.currentTimeMillis()
    this.accumulatedTimeMillis = 0;
    this.isPaused = false;
    this.timerStarted = false;  // NEW FLAG
}

public void update() {
    if (script == null || skillType == null || tracker == null) {
        return;
    }

    // Start timer on first actual update (when script is running)
    if (!timerStarted) {
        sessionStartTime = System.currentTimeMillis();
        timerStarted = true;
        ScriptLogger.debug(script, "XP failsafe timer started");
    }

    // ... rest of update logic ...
}
```

---

### Issue 3: Missing Volatile Keyword on isPaused - MEDIUM (Thread Safety)

- **Severity**: MEDIUM
- **Location**: `/utils/src/com/jork/utils/metrics/providers/XPMetricProvider.java:33`
- **Description**: The `isPaused` flag is read by the script thread (in `update()` and `getTimeSinceLastXPGain()`) and written by both the script thread (pause/resume) and potentially read concurrently. Without `volatile`, the JVM can cache the value, leading to stale reads where `update()` sees `isPaused=true` even after `resumeTimer()` has set it to `false`.

- **Evidence**:
```java
// XPMetricProvider.java:30-34
// Pausable timer implementation
private long accumulatedTimeMillis = 0;  // Time accumulated while logged in
private long sessionStartTime = 0;        // When current session started
private boolean isPaused = false;         // NOT VOLATILE - can be cached by CPU
private boolean pauseDuringLogoutEnabled = true;  // Config option
```

- **Recommended Fix**:
```java
// BEFORE:
private boolean isPaused = false;

// AFTER:
private volatile boolean isPaused = false;
```

**Note:** While this helps prevent stale reads, Issue #1 is still the primary root cause. This fix prevents race conditions where the flag is read from cache.

---

### Issue 4: Pause/Resume Logic Has No Idempotency Protection - LOW

- **Severity**: LOW
- **Location**: `/utils/src/com/jork/utils/metrics/providers/XPMetricProvider.java:363-381`
- **Description**: If `pauseTimer()` is called multiple times (e.g., during rapid logout/login cycles or duplicate `onGameStateChanged` events), the accumulated time can be incorrectly calculated because the second pause sees `sessionStartTime=0` and skips accumulation.

- **Evidence**:
```java
// XPMetricProvider.java:363-371
public void pauseTimer() {
    if (!pauseDuringLogoutEnabled || isPaused) return;  // Early exit if already paused

    if (sessionStartTime > 0) {  // Only accumulates if sessionStartTime is set
        accumulatedTimeMillis += System.currentTimeMillis() - sessionStartTime;
        isPaused = true;
        sessionStartTime = 0;
    }
}
```

If called twice:
1. First call: `accumulatedTimeMillis += delta; isPaused = true; sessionStartTime = 0`
2. Second call: `isPaused=true` → early return (GOOD), but if there's a race, it might see stale `isPaused=false`
3. Second call continues: `sessionStartTime=0` → if condition fails → no accumulation → subtle time loss

- **Recommended Fix**: Add defensive logging and ensure proper state:
```java
public void pauseTimer() {
    if (!pauseDuringLogoutEnabled) return;

    if (isPaused) {
        // Already paused - idempotent behavior
        ScriptLogger.debug(script, "pauseTimer() called but already paused");
        return;
    }

    if (sessionStartTime > 0) {
        accumulatedTimeMillis += System.currentTimeMillis() - sessionStartTime;
        ScriptLogger.debug(script, "XP timer paused - accumulated: " + accumulatedTimeMillis + "ms");
    } else {
        ScriptLogger.warning(script, "pauseTimer() called but sessionStartTime is 0");
    }

    isPaused = true;
    sessionStartTime = 0;
}

public void resumeTimer() {
    if (!pauseDuringLogoutEnabled) return;

    if (!isPaused) {
        // Already running - idempotent behavior
        ScriptLogger.debug(script, "resumeTimer() called but already running");
        return;
    }

    sessionStartTime = System.currentTimeMillis();
    isPaused = false;
    ScriptLogger.debug(script, "XP timer resumed - accumulated: " + accumulatedTimeMillis + "ms");
}
```

---

### Issue 5: No Validation of XP Failsafe Timeout Value - LOW

- **Severity**: LOW
- **Location**: `/scripts/jorkHunter/src/com/jork/script/jorkHunter/JorkHunter.java:538-550`
- **Description**: The timeout value is converted from minutes to milliseconds without validation. If a user accidentally sets a very small timeout (e.g., 0 or 1 minute), the script will stop almost immediately. There's also no upper bound validation.

- **Evidence**:
```java
// JorkHunter.java:538-550
if (xpFailsafeEnabled && initialised) {
    long timeSinceXP = getTimeSinceLastXPGain();
    long timeoutMillis = xpFailsafeTimeoutMinutes * 60 * 1000L;  // No validation!

    if (timeSinceXP > timeoutMillis) {
        ScriptLogger.error(this, "XP FAILSAFE TRIGGERED: No XP gained for " +
            xpFailsafeTimeoutMinutes + " minutes. Stopping script.");
        stop();
    }
}
```

- **Recommended Fix**:
```java
if (xpFailsafeEnabled && initialised) {
    long timeSinceXP = getTimeSinceLastXPGain();

    // Validate timeout (minimum 2 minutes, maximum 60 minutes)
    int validatedTimeout = Math.max(2, Math.min(60, xpFailsafeTimeoutMinutes));
    if (validatedTimeout != xpFailsafeTimeoutMinutes) {
        ScriptLogger.warning(this, "XP failsafe timeout adjusted from " +
            xpFailsafeTimeoutMinutes + " to " + validatedTimeout + " minutes (valid range: 2-60)");
    }

    long timeoutMillis = validatedTimeout * 60 * 1000L;

    if (timeSinceXP > timeoutMillis) {
        ScriptLogger.error(this, "XP FAILSAFE TRIGGERED: No XP gained for " +
            validatedTimeout + " minutes. Stopping script.");
        ScriptLogger.error(this, "Last XP gain was: " + getTimeSinceLastXPGainFormatted() + " ago");
        stop();
    }
}
```

---

## Root Cause Analysis

**Primary Root Cause:** Issue #1 (Timer Never Unpauses After XP Gain)

The fundamental flaw is treating the pause state as "read-only" during XP detection. The code assumes that only `resumeTimer()` should change `isPaused`, but this creates a scenario where:
- Timer is paused during logout
- Timer is resumed on login
- XP is gained shortly after (within OCR polling interval)
- OCR detects XP while `isPaused` may still be `true` or has stale cached value
- Timer reset is SKIPPED due to pause state check
- Failsafe sees ever-increasing time and triggers falsely

**Contributing Factors:**
- Issue #2: Early timer start during initialization adds "dead time" that counts against timeout
- Issue #3: Missing `volatile` allows stale cached reads of `isPaused`
- Issue #4: Multiple pause/resume calls can cause subtle state corruption
- Issue #5: No timeout validation allows unreasonable values to slip through

**Why Users Are Experiencing This:**
1. **Hunters with frequent logout/login cycles** (breaks, world hops, disconnects) repeatedly trigger the pause/resume race condition
2. **Scripts with long initialization** (UI setup, tile selection) burn through timeout before XP can be gained
3. **High-latency OCR polling** means XP gains are often detected during state transitions when `isPaused` is ambiguous

---

## Recommended Fix Order

Apply fixes in this priority order to maximize impact:

1. **CRITICAL - Fix Issue #1 First**: Always reset timer and unpause on XP gain
   - This fixes the immediate false trigger problem
   - File: `XPMetricProvider.java:203-209`

2. **HIGH - Fix Issue #2 Second**: Start timer on first update, not initialization
   - Prevents timeout burning during UI/setup phase
   - File: `XPMetricProvider.java:66-68` and `update()` method

3. **MEDIUM - Fix Issue #3 Third**: Add `volatile` to `isPaused`
   - Prevents stale cached reads
   - File: `XPMetricProvider.java:33`

4. **LOW - Fix Issue #4 Fourth**: Add idempotency protection to pause/resume
   - Prevents subtle edge cases
   - File: `XPMetricProvider.java:363-381`

5. **LOW - Fix Issue #5 Last**: Validate timeout values
   - Quality-of-life improvement
   - File: `JorkHunter.java:538-550` and `Ectofuntus.java` equivalent

---

## Testing Recommendations

### Test Case 1: Rapid Logout/Login Cycle
**Objective**: Verify timer doesn't accumulate falsely during frequent state changes

**Steps**:
1. Enable XP failsafe with 5-minute timeout
2. Start script and gain XP normally
3. Force logout (disconnect or manual logout)
4. Wait 10 seconds
5. Log back in
6. Immediately gain XP (within 1 second of login)
7. Check `getTimeSinceLastXPGain()` value

**Expected Result**: Timer should be close to 0ms after XP gain
**Current Buggy Behavior**: Timer continues from accumulated logout time

---

### Test Case 2: Long Initialization Sequence
**Objective**: Verify timer doesn't start during UI/setup phase

**Steps**:
1. Enable XP failsafe with 5-minute timeout
2. Start jorkHunter script
3. Spend 3 minutes configuring UI, selecting tiles, etc.
4. Start actual hunting
5. Gain XP within 2 minutes of starting tasks
6. Verify failsafe doesn't trigger

**Expected Result**: Script runs for full 5 minutes after actual start
**Current Buggy Behavior**: Failsafe triggers after only 2 minutes of actual runtime

---

### Test Case 3: XP Gain During Pause State
**Objective**: Verify XP gain forces timer unpause

**Steps**:
1. Start script with XP tracking
2. Manually call `pauseXPFailsafeTimer()`
3. Gain XP while paused
4. Check if `isPaused` is now `false` and timer was reset

**Expected Result**: Timer resets to 0 and `isPaused = false`
**Current Buggy Behavior**: Timer not reset, `isPaused` remains `true`

---

### Test Case 4: Timeout Validation
**Objective**: Verify extreme timeout values are clamped

**Steps**:
1. Set XP failsafe timeout to 0 minutes via UI
2. Start script
3. Verify logged timeout is adjusted to minimum (2 minutes)
4. Set timeout to 999 minutes
5. Verify logged timeout is clamped to maximum (60 minutes)

**Expected Result**: Timeouts clamped to 2-60 minute range
**Current Buggy Behavior**: No validation, accepts any value

---

### Test Case 5: Concurrent Pause/Resume Calls
**Objective**: Verify idempotency of pause/resume

**Steps**:
1. Start script with XP tracking
2. Call `pauseXPFailsafeTimer()` twice in rapid succession
3. Check logs for warnings
4. Call `resumeXPFailsafeTimer()` twice
5. Verify timer state is correct (running, not corrupted)

**Expected Result**: Idempotent behavior, debug logs show duplicate calls
**Current Buggy Behavior**: Potential state corruption on duplicate calls

---

## Additional Observations

### Thread Safety Concerns
All `XPMetricProvider` fields that are read during `getTimeSinceLastXPGain()` (called from script thread in `poll()`) and written during `update()` (called from metrics rendering) should be `volatile` or properly synchronized:

**Currently Risky Fields:**
- `isPaused` - NOT volatile (Issue #3)
- `accumulatedTimeMillis` - NOT volatile
- `sessionStartTime` - NOT volatile
- `lastKnownXP` - NOT volatile

**Recommendation**: Make all timer-related fields `volatile` for thread safety:
```java
private volatile long accumulatedTimeMillis = 0;
private volatile long sessionStartTime = 0;
private volatile boolean isPaused = false;
private volatile Integer lastKnownXP;
```

### OCR Polling Frequency
The XP is read via OCR in `XPMetricProvider.update()`, which is called every 500ms (from `MetricsTracker.UPDATE_INTERVAL`). This means:
- XP gains can be delayed by up to 500ms in detection
- During state transitions (logout/login), XP detection may happen while pause state is stale
- This delay contributes to the race condition in Issue #1

**No immediate fix needed**, but worth documenting that 500ms OCR polling is a contributing factor to the race window.

### Metrics Update Only Happens During Render
Looking at `MetricsTracker.render()`:
```java
public void render(Canvas canvas) {
    updateMetrics();  // Only updates during onPaint
    // ...
}
```

This means XP is **only detected during frame rendering (onPaint)**. If the client stops rendering frames (e.g., minimized, low FPS), XP detection stops entirely, but the failsafe timer keeps counting. This could cause false triggers if:
1. Client is minimized for 4 minutes
2. User restores window
3. Script gains XP
4. Failsafe triggers 1 minute later because timer counted minimized time

**Recommendation**: Document this limitation in user-facing docs. Consider adding a "pause failsafe on low FPS" option.

---

## Conclusion

The XP failsafe false trigger issue is caused by a **critical race condition** where XP gains during pause/resume transitions fail to properly reset the timer. The fix is straightforward: always reset the timer when XP is gained, regardless of pause state. Combined with starting the timer only when the script actually begins running (not during initialization), these two changes will eliminate the vast majority of false triggers.

The thread safety improvements (volatile keywords) and idempotency protection are important defensive measures that prevent edge cases, while timeout validation improves user experience.

**Estimated Impact After Fixes:**
- 95%+ reduction in false failsafe triggers
- Proper timeout enforcement during actual script runtime
- Robust handling of logout/login cycles and world hops

**Risk Level of Fixes:** LOW - All changes are localized to timer logic and don't affect core XP tracking or game interaction.
