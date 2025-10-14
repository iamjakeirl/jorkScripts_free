package com.jork.script.WineCollector.tasks;

import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.ObjectManager;
import com.osmb.api.scene.RSObject;
import com.osmb.api.visual.PixelAnalyzer;
import com.osmb.api.visual.PixelCluster;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import com.osmb.api.item.ItemGroupResult;
import java.util.Set;
import com.osmb.api.shape.Polygon;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.profile.ProfileManager;
import com.jork.utils.ScriptLogger;
import com.jork.utils.Navigation;
import com.jork.script.WineCollector.WineCollector;
import com.jork.script.WineCollector.config.WineConfig;

import java.awt.Point;
import java.util.Optional;
import java.util.Random;
import java.util.List;

public class CollectTask implements Task {

    private final WineCollector script;
    private final Random random = new Random();
    private final Navigation navigation;

    public CollectTask(WineCollector script) {
        this.script = script;
        this.navigation = new Navigation(script);
    }

    @Override
    public boolean canExecute() {
        // Execute if inventory is not full
        ItemGroupResult result = script.getWidgetManager().getInventory().search(Set.of());
        if (result == null) return true; // If can't check, assume we can collect
        return result.getOccupiedSlotCount() < WineConfig.INVENTORY_SIZE;
    }

    @Override
    public int execute() {
        // Step 1: Check current floor
        WorldPosition currentPos = script.getWorldPosition();
        if (currentPos == null) {
            ScriptLogger.warning(script, "Cannot determine current position");
            return WineConfig.POLL_DELAY_LONG;
        }
        
        int currentPlane = currentPos.getPlane();
        
        // Step 2: Navigate to ladder if on ground floor
        if (currentPlane == WineConfig.GROUND_FLOOR_PLANE) {
            if (!WineConfig.LADDER_AREA.contains(currentPos)) {
                ScriptLogger.info(script, "Moving to ladder area");
                boolean navigating = navigation.navigateTo(WineConfig.LADDER_AREA);
                return navigating ? WineConfig.POLL_DELAY_MEDIUM : WineConfig.POLL_DELAY_LONG;
            }
            
            // Step 3: Climb first ladder from ground to second floor
            ScriptLogger.actionAttempt(script, "Climbing first ladder up");
            RSObject ladder = script.getObjectManager().getClosestObject(WineConfig.LADDER_NAME);
            
            if (ladder == null) {
                ScriptLogger.warning(script, "Cannot find ladder");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            boolean interacted = ladder.interact(WineConfig.LADDER_UP_ACTION);
            if (!interacted) {
                ScriptLogger.warning(script, "Failed to interact with first ladder");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            // Wait for climb to second floor
            boolean climbed = script.pollFramesHuman(() -> {
                WorldPosition pos = script.getWorldPosition();
                return pos != null && pos.getPlane() == WineConfig.SECOND_FLOOR_PLANE;
            }, WineConfig.LADDER_CLIMB_TIMEOUT);
            
            if (climbed) {
                ScriptLogger.actionSuccess(script, "Climbed to second floor");
            } else {
                ScriptLogger.actionFailure(script, "First ladder climb", 1, 1);
            }
            
            return WineConfig.POLL_DELAY_MEDIUM;
        }
        
        // Step 3b: If on second floor, climb to top floor
        if (currentPlane == WineConfig.SECOND_FLOOR_PLANE) {
            ScriptLogger.actionAttempt(script, "Climbing second ladder to top floor");
            
            // Get all ladders and select the furthest one
            List<RSObject> ladders = script.getObjectManager().getObjects(obj -> 
                obj != null && obj.getName() != null && 
                obj.getName().equals(WineConfig.LADDER_NAME) && 
                obj.canReach()
            );
            
            if (ladders == null || ladders.isEmpty()) {
                ScriptLogger.warning(script, "No ladders found on second floor");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            // Sort by distance (furthest first)
            ladders.sort((a, b) -> 
                Double.compare(
                    b.distance(currentPos), 
                    a.distance(currentPos)
                )
            );
            
            RSObject furthestLadder = ladders.get(0);
            ScriptLogger.debug(script, "Using ladder at distance: " + furthestLadder.distance(currentPos));
            
            boolean interacted = furthestLadder.interact(WineConfig.LADDER_UP_ACTION);
            if (!interacted) {
                ScriptLogger.warning(script, "Failed to interact with second ladder");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            // Wait for climb to top floor
            boolean climbed = script.pollFramesHuman(() -> {
                WorldPosition pos = script.getWorldPosition();
                return pos != null && pos.getPlane() == WineConfig.TOP_FLOOR_PLANE;
            }, WineConfig.LADDER_CLIMB_TIMEOUT);
            
            if (climbed) {
                ScriptLogger.actionSuccess(script, "Climbed to top floor");
            } else {
                ScriptLogger.actionFailure(script, "Second ladder climb", 1, 1);
            }
            
            return WineConfig.POLL_DELAY_MEDIUM;
        }
        
        // Step 4: Check for wine (we should be on top floor)
        if (currentPlane != WineConfig.TOP_FLOOR_PLANE) {
            ScriptLogger.warning(script, "Not on top floor, current plane: " + currentPlane);
            return WineConfig.POLL_DELAY_LONG;
        }
        
        ScriptLogger.debug(script, "Checking for wine at spawn position");
        
        // Get tile cube for wine spawn position
        Polygon tileCube = script.getSceneProjector().getTileCube(
            WineConfig.WINE_SPAWN_POSITION, 
            WineConfig.TILE_CUBE_HEIGHT
        );
        
        if (tileCube == null) {
            ScriptLogger.debug(script, "Wine spawn position not visible on screen");
            // Try to move closer to wine spawn position
            navigation.simpleMoveTo(WineConfig.WINE_SPAWN_POSITION, 5000, 5);
            return WineConfig.POLL_DELAY_LONG;
        }
        
        // Check for wine using pixel detection
        boolean wineDetected = wineDetected(tileCube);
        
        if (wineDetected) {
            // Step 5: Pick up wine
            ScriptLogger.actionAttempt(script, "Picking up Eclipse Red");
            
            Point tapPoint = getRandomPointInPolygon(tileCube);
            if (tapPoint == null) {
                ScriptLogger.warning(script, "Failed to get tap point in tile cube");
                return WineConfig.POLL_DELAY_MEDIUM;
            }
            
            // Tap to pick up wine
            boolean tapped = script.getFinger().tap(tapPoint.x, tapPoint.y);
            if (!tapped) {
                ScriptLogger.warning(script, "Failed to tap wine location");
                return WineConfig.POLL_DELAY_MEDIUM;
            }
            
            // Wait for pickup with human-like delay
            ItemGroupResult initialResult = script.getWidgetManager().getInventory().search(Set.of(WineConfig.WINE_ID));
            int initialCount = initialResult != null ? initialResult.getAmount(WineConfig.WINE_ID) : 0;
            boolean pickedUp = script.pollFramesHuman(() -> {
                ItemGroupResult currentResult = script.getWidgetManager().getInventory().search(Set.of(WineConfig.WINE_ID));
                int currentCount = currentResult != null ? currentResult.getAmount(WineConfig.WINE_ID) : 0;
                return currentCount > initialCount;
            }, WineConfig.PICKUP_TIMEOUT);
            
            if (pickedUp) {
                script.incrementWineCount();
                ScriptLogger.actionSuccess(script, "Wine picked up successfully");
            } else {
                ScriptLogger.actionFailure(script, "Wine pickup", 1, 1);
            }
        } else {
            ScriptLogger.debug(script, "No wine detected at spawn position");
        }
        
        // Step 6: World hop
        ScriptLogger.info(script, "Preparing to hop worlds");
        
        // Add human-like delay before hopping
        int hopDelay = RandomUtils.weightedRandom(WineConfig.HOP_DELAY_MIN, WineConfig.HOP_DELAY_MAX);
        script.pollFramesHuman(() -> false, hopDelay);
        
        ProfileManager profileManager = script.getProfileManager();
        if (profileManager != null && profileManager.hasHopProfile()) {
            boolean hopInitiated = profileManager.forceHop();
            if (hopInitiated) {
                ScriptLogger.info(script, "World hop initiated");
            } else {
                ScriptLogger.warning(script, "Failed to initiate world hop");
            }
        } else {
            ScriptLogger.warning(script, "No hop profile configured");
        }
        
        return WineConfig.POLL_DELAY_WORLD_HOP;
    }

    private boolean wineDetected(Polygon area) {
        if (area == null) {
            return false;
        }
        
        try {
            PixelAnalyzer analyzer = script.getPixelAnalyzer();
            if (analyzer == null) {
                ScriptLogger.warning(script, "PixelAnalyzer not available");
                return false;
            }
            
            // Create searchable pixel for wine color
            SearchablePixel winePixel = new SearchablePixel(
                WineConfig.WINE_PIXEL_COLOR,
                new SingleThresholdComparator(WineConfig.PIXEL_COLOR_TOLERANCE),
                ColorModel.RGB
            );
            
            // Create cluster query for wine colors
            PixelCluster.ClusterQuery query = new PixelCluster.ClusterQuery(
                WineConfig.PIXEL_COLOR_TOLERANCE,  // max distance
                WineConfig.MIN_CLUSTER_SIZE,       // min size
                new SearchablePixel[] { winePixel } // searchable pixels
            );
            
            // Find clusters in the tile cube area
            PixelCluster.ClusterSearchResult result = analyzer.findClusters(area, query);
            
            if (result != null) {
                List<PixelCluster> clusters = result.getClusters();
                boolean found = clusters != null && !clusters.isEmpty();
                
                if (found) {
                    ScriptLogger.debug(script, "Wine detected: " + clusters.size() + " pixel clusters found");
                }
                
                return found;
            }
        } catch (Exception e) {
            ScriptLogger.exception(script, "wine detection", e);
        }
        
        return false;
    }

    private Point getRandomPointInPolygon(Polygon poly) {
        if (poly == null) {
            return null;
        }
        
        try {
            // Get the bounds of the polygon
            com.osmb.api.shape.Rectangle shapeBounds = poly.getBounds();
            if (shapeBounds == null) {
                return null;
            }
            
            // Convert to awt Rectangle for random point generation
            java.awt.Rectangle bounds = new java.awt.Rectangle(
                shapeBounds.getX(), 
                shapeBounds.getY(), 
                shapeBounds.getWidth(), 
                shapeBounds.getHeight()
            );
            
            // Try up to 100 times to find a point inside the polygon
            for (int i = 0; i < 100; i++) {
                int x = bounds.x + random.nextInt(bounds.width);
                int y = bounds.y + random.nextInt(bounds.height);
                Point point = new Point(x, y);
                
                if (poly.contains(point)) {
                    return point;
                }
            }
            
            // Fallback to center of bounds if we can't find a point inside
            return new Point(
                bounds.x + bounds.width / 2,
                bounds.y + bounds.height / 2
            );
        } catch (Exception e) {
            ScriptLogger.exception(script, "getting random point in polygon", e);
            return null;
        }
    }
}