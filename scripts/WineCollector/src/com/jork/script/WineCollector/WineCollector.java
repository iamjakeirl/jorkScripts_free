package com.jork.script.WineCollector;

import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.visual.drawing.Canvas;
import com.jork.utils.ScriptLogger;
import com.jork.utils.metrics.core.MetricsTracker;
import com.jork.utils.metrics.core.MetricType;
import com.jork.script.WineCollector.tasks.*;
import com.jork.script.WineCollector.config.WineConfig;

@ScriptDefinition(
    name = "Wine Collector",
    author = "jork",
    version = 1.0,
    description = "Collects Eclipse Red wines in Varlamore Hunting Guild",
    skillCategory = SkillCategory.OTHER
)
public class WineCollector extends Script {

    private TaskManager taskManager;
    private MetricsTracker metricsTracker;
    private int wineCount = 0;
    private long startTime;
    private int winesPerHour = 0;

    public WineCollector(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public void onStart() {
        startTime = System.currentTimeMillis();
        
        ScriptLogger.startup(this, "1.0", "jork", "Eclipse Red Wine Collection");
        
        // Initialize TaskManager
        taskManager = new TaskManager(this);
        
        // Create and add tasks
        CollectTask collectTask = new CollectTask(this);
        BankTask bankTask = new BankTask(this);
        taskManager.addTasks(bankTask, collectTask); // Bank task has priority when inventory is full
        
        // Initialize MetricsTracker
        metricsTracker = new MetricsTracker(this);
        
        // Register runtime metrics
        metricsTracker.registerRuntimeMetrics();
        
        // Register wine collection metrics
        metricsTracker.register("Wines Collected", 
            () -> wineCount, 
            MetricType.NUMBER);
        
        metricsTracker.register("Wines/Hour", 
            () -> calculateWinesPerHour(), 
            MetricType.RATE,
            "%,d/hr");
        
        metricsTracker.register("Total Value", 
            () -> wineCount * WineConfig.WINE_VALUE, 
            MetricType.NUMBER,
            "%,d gp");
        
        ScriptLogger.info(this, "Initialization complete. Starting wine collection...");
    }

    @Override
    public int poll() {
        return taskManager.executeNextTask();
    }

    @Override
    public void onPaint(Canvas canvas) {
        // Update wines per hour calculation
        winesPerHour = calculateWinesPerHour();
        
        // Render metrics display
        if (metricsTracker != null) {
            metricsTracker.render(canvas);
        }
    }

    public void onStop() {
        ScriptLogger.shutdown(this, "Script stopped by user");
        
        // Log final statistics
        int totalValue = wineCount * WineConfig.WINE_VALUE;
        ScriptLogger.info(this, "Final Statistics:");
        ScriptLogger.info(this, "Wines collected: " + wineCount);
        ScriptLogger.info(this, "Total value: " + totalValue + " gp");
        
        long runtime = System.currentTimeMillis() - startTime;
        long hours = runtime / 3600000;
        long minutes = (runtime % 3600000) / 60000;
        ScriptLogger.info(this, "Runtime: " + hours + " hours, " + minutes + " minutes");
    }

    /**
     * Called by tasks when a wine is collected
     */
    public void incrementWineCount() {
        wineCount++;
        ScriptLogger.info(this, "Wine collected! Total: " + wineCount);
    }

    /**
     * Calculate wines collected per hour
     */
    private int calculateWinesPerHour() {
        if (wineCount == 0 || startTime == 0) {
            return 0;
        }
        
        long runtime = System.currentTimeMillis() - startTime;
        if (runtime < 1000) { // Less than 1 second
            return 0;
        }
        
        double hoursRun = runtime / 3600000.0;
        return (int) (wineCount / hoursRun);
    }

    /**
     * Get the current wine count
     */
    public int getWineCount() {
        return wineCount;
    }
}