package com.jork.script.Ectofuntus;

import com.jork.script.Ectofuntus.config.EctoConfig;
import com.jork.script.Ectofuntus.tasks.*;
import com.jork.script.Ectofuntus.ui.ScriptOptions;
import com.jork.utils.ExceptionUtils;
import com.jork.utils.ScriptLogger;
import com.jork.utils.metrics.AbstractMetricsScript;
import com.jork.utils.metrics.core.MetricType;
import com.jork.utils.metrics.display.MetricsPanelConfig;

import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.ui.GameState;
import com.osmb.api.visual.drawing.Canvas;

import javafx.scene.Scene;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ectofuntus Script - Automates prayer training at the Ectofuntus in Port Phasmatys.
 *
 * Features:
 * - Banking and teleporting with ectophial
 * - Slime collection in basement
 * - Bone grinding on top floor
 * - Worship at the altar
 * - XP failsafe and break handling
 *
 * @author jork
 * @version 1.0
 */
@ScriptDefinition(
    name = "Ectofuntus",
    author = "jork",
    version = 1.0,
    description = "Automates prayer training at the Ectofuntus",
    skillCategory = SkillCategory.PRAYER
)
public class Ectofuntus extends AbstractMetricsScript {

    // ───────────────────────────────────────────────────────────────────────────
    // UI Synchronization Fields
    // ───────────────────────────────────────────────────────────────────────────
    private volatile boolean settingsConfirmed = false;  // FX thread → script thread
    private boolean initialised = false;                  // Script thread internal

    private volatile EctoConfig config;  // Configuration from UI

    // ───────────────────────────────────────────────────────────────────────────
    // Task Management
    // ───────────────────────────────────────────────────────────────────────────
    private BankTask bankTask;
    private TeleportTask teleportTask;
    private CollectSlimeTask collectSlimeTask;
    private GrindBonesTask grindBonesTask;
    private WorshipTask worshipTask;

    // ───────────────────────────────────────────────────────────────────────────
    // State Flags
    // ───────────────────────────────────────────────────────────────────────────
    private boolean shouldBank = true;
    private boolean hasSlime = false;
    private boolean hasBoneMeal = false;
    private boolean isDrainingForBreak = false;

    // ───────────────────────────────────────────────────────────────────────────
    // XP Failsafe Settings
    // ───────────────────────────────────────────────────────────────────────────
    private volatile boolean xpFailsafeEnabled = true;
    private volatile int xpFailsafeTimeoutMinutes = 5;
    private volatile boolean xpFailsafePauseDuringLogout = true;
    private long lastFailsafeWarningTime = 0;

    // ───────────────────────────────────────────────────────────────────────────
    // Metrics Tracking
    // ───────────────────────────────────────────────────────────────────────────
    private final AtomicInteger bonesProcessed = new AtomicInteger(0);

    public Ectofuntus(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    protected void onMetricsStart() {
        ScriptLogger.startup(this, "1.0", "jork", "Ectofuntus Prayer Training");

        // Show settings window (NON-BLOCKING)
        ScriptOptions opts = new ScriptOptions(this);
        Scene scene = new Scene(opts);
        getStageController().show(scene, "Ectofuntus – Configuration", false);

        ScriptLogger.info(this, "Settings window opened – waiting for user confirmation…");

        // Handle window close as confirmation with defaults
        if (scene.getWindow() != null) {
            scene.getWindow().setOnHidden(e -> {
                if (!settingsConfirmed) {
                    // User closed window without confirming - use defaults
                    Map<String, Object> defaultOptions = new HashMap<>();
                    defaultOptions.put("xpFailsafeEnabled", true);
                    defaultOptions.put("xpFailsafeTimeout", 5);
                    defaultOptions.put("xpFailsafePauseDuringLogout", true);
                    onSettingsConfirmed(EctoConfig.getDefault(), defaultOptions);
                }
            });
        }
    }

    /**
     * Called from the JavaFX thread when settings are confirmed.
     * This method MUST be lightweight and avoid game API interactions.
     */
    public void onSettingsConfirmed(EctoConfig config, Map<String, Object> options) {
        this.config = config;

        // Extract XP failsafe settings
        if (options != null) {
            Object failsafeObj = options.get("xpFailsafeEnabled");
            if (failsafeObj instanceof Boolean) {
                this.xpFailsafeEnabled = (Boolean) failsafeObj;
            }

            Object timeoutObj = options.get("xpFailsafeTimeout");
            if (timeoutObj instanceof Integer) {
                this.xpFailsafeTimeoutMinutes = (Integer) timeoutObj;
            }

            Object pauseObj = options.get("xpFailsafePauseDuringLogout");
            if (pauseObj instanceof Boolean) {
                this.xpFailsafePauseDuringLogout = (Boolean) pauseObj;
            }
        }

        if (xpFailsafeEnabled) {
            ScriptLogger.info(this, "XP Failsafe ENABLED - will stop after " +
                xpFailsafeTimeoutMinutes + " minutes without XP" +
                (xpFailsafePauseDuringLogout ? " (pauses during logout)" : ""));
        } else {
            ScriptLogger.info(this, "XP Failsafe DISABLED");
        }

        this.settingsConfirmed = true;
    }

    /**
     * Initialize tasks after UI confirmation – runs on script thread
     */
    private void initialiseIfReady() {
        if (initialised || !settingsConfirmed) {
            return;
        }

        ScriptLogger.info(this, "Settings confirmed – initializing tasks");

        // Open inventory if needed
        openInventoryIfNeeded();

        // Initialize metrics
        initializeMetrics();

        // Create task instances
        bankTask = new BankTask(this);
        teleportTask = new TeleportTask(this);
        collectSlimeTask = new CollectSlimeTask(this);
        grindBonesTask = new GrindBonesTask(this);
        worshipTask = new WorshipTask(this);

        ScriptLogger.info(this, "Initialization complete. Starting tasks…");
        initialised = true;
    }

    private void openInventoryIfNeeded() {
        if (getWidgetManager().getInventory().isOpen()) {
            return;
        }

        pollFramesHuman(() -> {
            if (!getWidgetManager().getInventory().isOpen()) {
                return getWidgetManager().getInventory().open();
            }
            return true;
        }, 2000);
    }

    @Override
    public int poll() {
        // ─── XP Failsafe Check ───────────────────────────────────────
        if (xpFailsafeEnabled && initialised) {
            long timeSinceXP = getTimeSinceLastXPGain();
            long timeoutMillis = xpFailsafeTimeoutMinutes * 60 * 1000L;

            if (timeSinceXP > timeoutMillis) {
                ScriptLogger.error(this, "XP FAILSAFE TRIGGERED: No XP for " +
                    xpFailsafeTimeoutMinutes + " minutes. Stopping.");
                stop();
                return 1000;
            }

            // Warn when approaching timeout
            long warningThreshold = timeoutMillis - 60000;
            if (timeSinceXP > warningThreshold && timeSinceXP < timeoutMillis) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFailsafeWarningTime > 30000) {
                    long secondsLeft = (timeoutMillis - timeSinceXP) / 1000;
                    ScriptLogger.warning(this, "XP Failsafe: " + secondsLeft + "s until auto-stop");
                    lastFailsafeWarningTime = currentTime;
                }
            }
        }

        // ─── Break Handling ──────────────────────────────────────────
        if (getProfileManager().isDueToBreak()) {
            if (!isDrainingForBreak) {
                ScriptLogger.info(this, "Break due - entering drain mode");
                isDrainingForBreak = true;
            }
        }

        // ─── Wait for Settings Confirmation ──────────────────────────
        if (!settingsConfirmed) {
            return 1000;
        }

        // ─── Deferred Initialization ─────────────────────────────────
        initialiseIfReady();
        if (!initialised) {
            return 1000;
        }

        // ─── Task Execution (Priority Order) ─────────────────────────
        // Priority 1: Banking
        if (bankTask.canExecute()) {
            return bankTask.execute();
        }

        // Priority 2: Teleport to Ectofuntus
        if (teleportTask.canExecute()) {
            return teleportTask.execute();
        }

        // Priority 3/4: Slime and Bones (randomized order handled in tasks)
        if (collectSlimeTask.canExecute()) {
            return collectSlimeTask.execute();
        }

        if (grindBonesTask.canExecute()) {
            return grindBonesTask.execute();
        }

        // Priority 5: Worship
        if (worshipTask.canExecute()) {
            return worshipTask.execute();
        }

        // Default poll rate
        return 500;
    }

    @Override
    public boolean canBreak() {
        // Only allow break when we're in a safe state (at bank without resources)
        boolean canBreakNow = shouldBank && !hasSlime && !hasBoneMeal;

        if (canBreakNow && isDrainingForBreak) {
            ScriptLogger.info(this, "Drain complete - allowing break");
            isDrainingForBreak = false;
        }

        return canBreakNow;
    }

    @Override
    public void onGameStateChanged(GameState newGameState) {
        super.onGameStateChanged(newGameState);

        if (newGameState != null && newGameState != GameState.LOGGED_IN) {
            ScriptLogger.warning(this, "Logout detected");

            if (xpFailsafePauseDuringLogout && xpFailsafeEnabled) {
                pauseXPFailsafeTimer();
            }
        } else if (newGameState == GameState.LOGGED_IN) {
            ScriptLogger.info(this, "Logged in - detecting current state");
            detectAndRecoverState();

            if (xpFailsafePauseDuringLogout && xpFailsafeEnabled) {
                resumeXPFailsafeTimer();
            }
        }
    }

    @Override
    public void onRelog() {
        ScriptLogger.info(this, "Relog detected - detecting current state");
        detectAndRecoverState();
    }

    /**
     * Detects current state based on inventory and location after login/relog.
     * This ensures we don't lose progress if we get logged out mid-cycle.
     */
    private void detectAndRecoverState() {
        if (!initialised || config == null) {
            ScriptLogger.debug(this, "Script not initialized yet, skipping state detection");
            return;
        }

        try {
            // Check inventory for key items
            int boneMealCount = countItemInInventory(1854);  // Bonemeal
            int slimeCount = countItemInInventory(4286);     // Bucket of slime
            int emptyPotCount = countItemInInventory(1931);  // Empty pot
            int emptyBucketCount = countItemInInventory(1925); // Empty bucket
            int boneCount = countItemInInventory(config.getBoneType().getItemId());

            ScriptLogger.info(this, "State detection - Bones: " + boneCount +
                ", Bonemeal: " + boneMealCount + ", Slime: " + slimeCount +
                ", EmptyPots: " + emptyPotCount + ", EmptyBuckets: " + emptyBucketCount);

            // Determine state flags based on inventory
            hasBoneMeal = boneMealCount > 0;
            hasSlime = slimeCount > 0;

            // Determine if we should bank
            boolean hasResourcesForWorship = boneMealCount > 0 && slimeCount > 0;
            boolean hasPartialResources = boneMealCount > 0 || slimeCount > 0;
            boolean hasSupplies = boneCount > 0 || emptyPotCount > 0 || emptyBucketCount > 0;

            if (hasResourcesForWorship) {
                // We have both resources ready - continue to worship
                shouldBank = false;
                ScriptLogger.info(this, "State: Ready to worship (" + boneMealCount + " bonemeal, " + slimeCount + " slime)");
            } else if (hasPartialResources || hasSupplies) {
                // We have partial resources or supplies - continue processing
                shouldBank = false;
                ScriptLogger.info(this, "State: Mid-cycle, continuing tasks");
            } else {
                // Empty inventory (or just ectophial/teleports) - need to bank
                shouldBank = true;
                ScriptLogger.info(this, "State: No resources, will bank");
            }

            // Reset drain flag on login
            isDrainingForBreak = false;

        } catch (Exception e) {
            ScriptLogger.warning(this, "Error during state detection: " + e.getMessage());
            // Fallback to safe state (bank)
            hasSlime = false;
            hasBoneMeal = false;
            shouldBank = true;
        }
    }

    /**
     * Counts how many of a specific item are in the inventory.
     */
    private int countItemInInventory(int itemId) {
        try {
            if (getWidgetManager() == null || getWidgetManager().getInventory() == null) {
                return 0;
            }

            var searchResult = getWidgetManager().getInventory().search(Set.of(itemId));
            if (searchResult == null) {
                return 0;
            }

            return searchResult.getAmount(itemId);
        } catch (Exception e) {
            ScriptLogger.debug(this, "Error counting item " + itemId + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Initialize metrics tracking
     */
    private void initializeMetrics() {
        // Activity label
        registerMetric("Activity", () -> getCurrentActivity(), MetricType.TEXT);

        // Enable Prayer XP tracking (sprite ID for Prayer skill)
        enableXPTracking(SkillType.PRAYER, 210);  // 210 = Prayer sprite

        // Bones processed
        registerMetric("Bones Used", bonesProcessed::get, MetricType.NUMBER);
        registerMetric("Bones /h", bonesProcessed::get, MetricType.RATE);

        // Time since XP (if failsafe enabled)
        if (xpFailsafeEnabled) {
            registerMetric("Since XP", () -> {
                long ms = getTimeSinceLastXPGain();
                return (ms < 60000) ? (ms / 1000) + "s" : (ms / 60000) + "m";
            }, MetricType.TEXT);

            if (xpFailsafePauseDuringLogout) {
                configureXPFailsafeTimerPause(true);
            }
        }
    }

    @Override
    protected MetricsPanelConfig createMetricsConfig() {
        MetricsPanelConfig panelConfig = MetricsPanelConfig.darkTheme();
        panelConfig.setCustomPosition(10, 110);
        panelConfig.setMinWidth(180);
        panelConfig.setBackgroundColor(new java.awt.Color(0, 0, 0, 220));
        return panelConfig;
    }

    @Override
    protected void onMetricsPaint(Canvas canvas) {
        // Custom overlay drawing if needed
    }

    private String getCurrentActivity() {
        if (!initialised) return "Initializing";
        if (shouldBank) return "Banking";
        if (!hasSlime && !hasBoneMeal) return "Teleporting";
        if (!hasSlime) return "Collecting Slime";
        if (!hasBoneMeal) return "Grinding Bones";
        return "Worshipping";
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Getters for Tasks
    // ───────────────────────────────────────────────────────────────────────────

    public EctoConfig getConfig() {
        return config;
    }

    public boolean shouldBank() {
        return shouldBank;
    }

    public void setShouldBank(boolean shouldBank) {
        this.shouldBank = shouldBank;
    }

    public boolean hasSlime() {
        return hasSlime;
    }

    public void setHasSlime(boolean hasSlime) {
        this.hasSlime = hasSlime;
    }

    public boolean hasBoneMeal() {
        return hasBoneMeal;
    }

    public void setHasBoneMeal(boolean hasBoneMeal) {
        this.hasBoneMeal = hasBoneMeal;
    }

    public boolean isDrainingForBreak() {
        return isDrainingForBreak;
    }

    public void incrementBonesProcessed() {
        bonesProcessed.incrementAndGet();
    }
}
