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

## Mandatory Script Boilerplate (minimal)
```java
package scripts;

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
`onStart()` → once • `poll()` → loop • `onStop()` → once • `onPaint()` → each frame • `canBreak()/canHopWorlds()` → periodic.

---

## Common Patterns
- **Navigation:** `getWalker().walkTo(Location.<dest>)` + `ScriptLogger.navigation(...)`
- **Interact Object:** use `getObjectManager()` → `Optional<RSObject>`; `interact("<Action>")`.
- **Wait:** `submitTask(() -> cond, timeoutMs)` – *never* `Thread.sleep()`.
- **Null-safety:** Always `null`/`Optional` check before use.

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

## Development Guidelines
1. **Build/Test:** `./gradlew build` before committing.
2. **Code Style:** follow Google/Java conventions, functional & immutable where sensible, minimal inline comments.
3. **Logging:** always via ScriptLogger.
4. **Utilities:** Promote reuse – ≥50 % shared logic → move to `utils/`.
5. **Error Handling:** checked vs unchecked; never swallow exceptions.
6. **Naming:** Use `handle` prefix for event handlers (e.g., `handleButtonClick`).
7. **Resources:** Always use try-with-resources for AutoCloseable objects.
8. **Modern Java:** Leverage records, pattern matching, Optional, streams where appropriate.

### Script Development Process
1. Create script under `scripts/`.
2. Start with boilerplate above.
3. Use MCP helpers to discover API calls.
4. Reuse `com.jork.utils` utilities.
5. Test in-client, refine logs.

---

## Important Notes
- Scripts mimic human mobile play; all clicks via `getFinger().tap(...)` inside `submitHumanTask`.
- OSMB API is *visual*; expect nulls & delays.
- Project follows patterns required by OSMB client to avoid detection.
- Utility classes ensure consistency & reuse.
- ./gradlew clean buildAllVariants --refresh-dependencies --rerun-tasks --no-build-cache to build all jar variants of jorkHunter