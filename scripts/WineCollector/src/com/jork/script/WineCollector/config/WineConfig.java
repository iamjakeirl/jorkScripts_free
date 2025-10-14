package com.jork.script.WineCollector.config;

import com.osmb.api.item.ItemID;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.location.area.impl.RectangleArea;

public class WineConfig {
    
    // Item Constants
    public static final int WINE_ID = ItemID.ECLIPSE_RED;
    public static final int WINE_VALUE = 700;
    public static final String WINE_NAME = "Eclipse red";
    
    // Location Areas
    // RectangleArea constructor: (x, y, width, height, plane)
    public static final RectangleArea LADDER_AREA = new RectangleArea(
        1553, 3034,  // x, y position
        3, 2,        // width, height
        0            // plane (ground floor)
    );
    
    public static final RectangleArea BANK_AREA = new RectangleArea(
        1542, 3039,  // x, y position
        2, 1,        // width, height
        0            // plane (ground floor)
    );
    
    public static final RectangleArea UPSTAIRS_AREA = new RectangleArea(
        1000, 1000,  // x, y position
        20, 20,      // width, height
        1            // plane (upstairs)
    );
    
    // Wine Spawn Configuration
    public static final WorldPosition WINE_SPAWN_POSITION = new WorldPosition(
        1005, 1005, 2  // x, y, plane (2 = top floor)
    );
    // Floor plane constants
    public static final int GROUND_FLOOR_PLANE = 0;
    public static final int SECOND_FLOOR_PLANE = 1;
    public static final int TOP_FLOOR_PLANE = 2;
    public static final int WINE_SPAWN_PLANE = 2;  // Top floor (third floor)
    
    // Pixel Detection Configuration
    // Eclipse Red wine pixel colors - these need to be sampled from the actual item
    // These are placeholder values - replace with actual sampled colors
    // Using a single color value for the ClusterQuery constructor
    public static final int WINE_PIXEL_COLOR = 0xFFDC143C;  // Crimson red (placeholder)
    
    public static final int MIN_CLUSTER_SIZE = 10;  // Minimum pixels to confirm wine presence
    public static final int TILE_CUBE_HEIGHT = 50;  // Height for tile cube projection
    
    // Timing Configuration
    public static final int PICKUP_TIMEOUT = 3000;      // 3 seconds to pick up wine
    public static final int HOP_DELAY_MIN = 800;        // Minimum delay before hopping
    public static final int HOP_DELAY_MAX = 1200;       // Maximum delay before hopping
    public static final int LADDER_CLIMB_TIMEOUT = 5000; // 5 seconds to climb ladder
    public static final int BANK_OPEN_TIMEOUT = 5000;    // 5 seconds to open bank
    public static final int NAVIGATION_TIMEOUT = 20000;  // 20 seconds for navigation
    
    // Additional Configuration
    public static final int INVENTORY_SIZE = 28;
    public static final String LADDER_NAME = "Ladder";
    public static final String BANK_CHEST_NAME = "Bank chest";
    public static final String LADDER_UP_ACTION = "Climb-up";
    public static final String LADDER_DOWN_ACTION = "Climb-down";
    public static final String BANK_USE_ACTION = "Use";
    
    // Pixel tolerance for color matching
    public static final int PIXEL_COLOR_TOLERANCE = 10;
    
    // Delay configurations
    public static final int POLL_DELAY_SHORT = 300;
    public static final int POLL_DELAY_MEDIUM = 500;
    public static final int POLL_DELAY_LONG = 1000;
    public static final int POLL_DELAY_WORLD_HOP = 3000;
}