<objective>
Analyze the XP failsafe functionality in jorkHunter and the utils library to identify why the failsafe is triggering incorrectly (stopping the script when it shouldn't).

Users are reporting that the XP failsafe stops their script prematurely, even when legitimate XP gains are occurring. This analysis will identify the root causes and provide specific fix recommendations.
</objective>

<context>
First, read CLAUDE.md for project conventions and XP failsafe patterns.

The XP tracking/failsafe code spans two locations:
- **jorkHunter script**: `scripts/jorkHunter/` - uses the XP failsafe feature
- **Utils library**: `utils/src/com/jork/utils/` - likely contains shared metrics/XP tracking code

Key areas to investigate:
- XP detection and recording mechanism
- Timer pause/resume during logout/world hops
- Failsafe threshold calculations
- Thread safety between JavaFX UI and script thread
</context>

<data_sources>
Examine these files thoroughly:

@scripts/jorkHunter/src/**/*.java
@utils/src/com/jork/utils/metrics/**/*.java
@utils/src/com/jork/utils/**/*XP*.java
@utils/src/com/jork/utils/**/*Metric*.java

Also search for:
- `xpFailsafe` or `XPFailsafe` references
- `getTimeSinceLastXPGain` usage
- Timer pause/resume logic
- `onGameStateChanged` handlers that affect XP tracking
</data_sources>

<analysis_requirements>
Thoroughly analyze for these bug potential areas:

1. **Timer Logic Issues**
   - Is the XP timer properly paused during logout/world hop?
   - Does `onGameStateChanged` correctly handle all states?
   - Is `onRelog` properly resetting/resuming timers?

2. **Race Conditions / Thread Safety**
   - Are XP tracking fields properly marked `volatile`?
   - Could UI thread updates conflict with script thread reads?
   - Is there a window where XP is gained but not recorded?

3. **XP Detection Gaps**
   - How is XP gain detected? Polling? Events?
   - Could rapid XP gains be missed?
   - Are there edge cases where XP is gained but not captured?

4. **Failsafe Calculation Errors**
   - Is timeout calculated correctly (minutes to milliseconds)?
   - Does the warning threshold math add up?
   - Could integer overflow occur with long runtimes?

5. **State Management**
   - Does the failsafe account for initialization time?
   - What happens if XP tracking starts before first XP gain?
   - Are there scenarios where timer isn't properly initialized?

6. **Logout/Hop Edge Cases**
   - Is pause actually working when logged out?
   - Does world hop trigger proper state reset?
   - Could accumulated time persist incorrectly after relog?
</analysis_requirements>

<output_format>
Create a bug analysis report with the following structure:

## Executive Summary
[2-3 sentence overview of findings]

## Issues Identified

### Issue 1: [Name]
- **Severity**: Critical/High/Medium/Low
- **Location**: `file:line_number`
- **Description**: What's wrong and why it causes false failsafe triggers
- **Evidence**: Code snippet showing the problem
- **Recommended Fix**: Specific code change with before/after

### Issue 2: [Name]
[Same format]

[Continue for all issues found]

## Root Cause Analysis
[Which issue(s) are most likely causing user-reported problems]

## Recommended Fix Order
[Prioritized list of which fixes to apply first]

## Testing Recommendations
[How to verify the fixes work]

Save analysis to: `./analyses/001-xp-failsafe-bug-report.md`
</output_format>

<verification>
Before completing, verify:
- All XP-related code paths have been traced
- Thread safety has been evaluated for all shared state
- Timer logic has been checked for pause/resume correctness
- Every identified issue includes a specific fix recommendation
- The analysis covers jorkHunter's usage AND the underlying utils implementation
</verification>

<success_criteria>
- At least one root cause identified for the false failsafe triggers
- Each issue has a concrete fix recommendation with code
- Analysis distinguishes between "definitely a bug" vs "potential issue"
- Testing strategy provided to validate fixes
</success_criteria>
