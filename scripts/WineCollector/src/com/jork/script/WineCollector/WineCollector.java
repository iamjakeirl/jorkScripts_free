package com.jork.script.WineCollector;

import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import com.jork.utils.metrics.AbstractMetricsScript;
import com.jork.utils.metrics.core.MetricType;
import com.jork.utils.ScriptLogger;
import com.jork.script.WineCollector.tasks.*;
import com.jork.script.WineCollector.config.WineConfig;
import com.osmb.api.item.ItemGroupResult;

import java.awt.Point;
import java.util.Set;

@ScriptDefinition(
    name = "Wine Collector",
    author = "jork",
    version = 1.0,
    description = "Collects Eclipse Red wines in Varlamore Hunting Guild",
    skillCategory = SkillCategory.OTHER
)
public class WineCollector extends AbstractMetricsScript {

    private TaskManager taskManager;
    private int wineCount = 0;
    private boolean shouldBank = false;

    public WineCollector(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    protected void onMetricsStart() {
        // Check inventory on startup to set initial shouldBank flag
        ItemGroupResult startupInventory = getWidgetManager().getInventory().search(Set.of());
        if (startupInventory != null && startupInventory.getOccupiedSlotCount() >= WineConfig.INVENTORY_SIZE) {
            shouldBank = true;
            ScriptLogger.info(this, "Inventory full on startup - will navigate to bank");
        } else {
            shouldBank = false;
            ScriptLogger.info(this, "Inventory has space on startup - will navigate to collection area");
        }

        taskManager = new TaskManager(this);

        NavigateTask navigateTask = new NavigateTask(this);
        BankTask bankTask = new BankTask(this);
        CollectTask collectTask = new CollectTask(this);
        taskManager.addTasks(navigateTask, bankTask, collectTask);

        registerMetric("Wines Collected", () -> wineCount, MetricType.NUMBER);
        registerMetric("Wines/Hour", () -> wineCount, MetricType.RATE, "%,d/hr");
        registerMetric("Total Value", () -> wineCount * WineConfig.WINE_VALUE, MetricType.NUMBER, "%,d gp");
    }

    @Override
    public int poll() {
        return taskManager.executeNextTask();
    }

    @Override
    protected void onMetricsPaint(Canvas canvas) {
        try {
            // Draw wine spawn tile cube visualization
            drawWineSpawnCube(canvas);
        } catch (Exception e) {
            // Silently catch painting errors to avoid disrupting the script
        }
    }

    /**
     * Draws debug visualization for the wine spawn position.
     * Shows the tile cube in cyan, filled with green if wine is detected.
     */
    private void drawWineSpawnCube(Canvas canvas) {
        // Get tile cube for wine spawn position
        Polygon wineCube = getSceneProjector().getTileCube(
            WineConfig.WINE_SPAWN_POSITION,
            WineConfig.WINE_CUBE_HEIGHT
        );

        if (wineCube == null) {
            return;  // Wine position not on screen
        }

        // Resize to match detection logic
        Polygon resizedCube = wineCube.getResized(WineConfig.WINE_CUBE_RESIZE_FACTOR);

        // Check if wine is detected
        boolean wineDetected = detectWineForDebug(resizedCube);

        if (wineDetected) {
            // Fill with semi-transparent green if wine found
            canvas.fillPolygon(resizedCube, 0x00FF00, 0.3);  // Green with 30% opacity
            canvas.drawPolygon(resizedCube, 0x00FF00, 1.0);  // Bright green outline
        } else {
            // Fill with semi-transparent cyan if no wine
            canvas.fillPolygon(resizedCube, 0x00FFFF, 0.2);  // Cyan with 20% opacity
            canvas.drawPolygon(resizedCube, 0x00FFFF, 1.0);  // Bright cyan outline
        }

        // Draw text label
        Rectangle bounds = resizedCube.getBounds();
        if (bounds != null) {
            int centerX = bounds.x + bounds.width / 2;
            int centerY = bounds.y + bounds.height / 2;

            String statusText = wineDetected ? "WINE FOUND" : "NO WINE";
            int textColor = wineDetected ? 0x00FF00 : 0xFFFFFF;

            canvas.drawText(statusText, centerX - 30, centerY - 10, textColor,
                new java.awt.Font("Arial", java.awt.Font.BOLD, 12));
        }
    }

    /**
     * Helper method to detect wine for debug painting (simplified version).
     * @param resizedCube The resized tile cube to check
     * @return true if wine color is detected
     */
    private boolean detectWineForDebug(Polygon resizedCube) {
        try {
            SearchablePixel winePixel = new SearchablePixel(
                WineConfig.WINE_BOTTLE_COLOR,
                new SingleThresholdComparator(WineConfig.WINE_COLOR_TOLERANCE),
                ColorModel.HSL
            );

            Point foundPixel = getPixelAnalyzer().findPixel(resizedCube, winePixel);
            return foundPixel != null;
        } catch (Exception e) {
            return false;  // Return false if detection fails
        }
    }

    @Override
    protected void onMetricsStop() {
    }

    public void incrementWineCount() {
        wineCount++;
    }

    public int getWineCount() {
        return wineCount;
    }

    public void setShouldBank(boolean shouldBank) {
        this.shouldBank = shouldBank;
    }

    public boolean shouldBank() {
        return shouldBank;
    }
}