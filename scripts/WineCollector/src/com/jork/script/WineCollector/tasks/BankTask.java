package com.jork.script.WineCollector.tasks;

import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.ui.bank.Bank;
import com.osmb.api.item.ItemGroupResult;
import java.util.Set;
import java.util.List;
import com.jork.utils.ScriptLogger;
import com.jork.utils.Navigation;
import com.jork.script.WineCollector.WineCollector;
import com.jork.script.WineCollector.config.WineConfig;

public class BankTask implements Task {

    private final WineCollector script;
    private final Navigation navigation;

    public BankTask(WineCollector script) {
        this.script = script;
        this.navigation = new Navigation(script);
    }

    @Override
    public boolean canExecute() {
        // Execute if inventory is full of wines
        ItemGroupResult result = script.getWidgetManager().getInventory().search(Set.of(WineConfig.WINE_ID));
        if (result == null) return false;
        int wineCount = result.getAmount(WineConfig.WINE_ID);
        return wineCount >= WineConfig.INVENTORY_SIZE;
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
        
        // Step 2: If on top floor, climb down to second floor
        if (currentPlane == WineConfig.TOP_FLOOR_PLANE) {
            ScriptLogger.actionAttempt(script, "Climbing down from top floor");
            
            // Get all ladders and select the furthest one
            List<RSObject> ladders = script.getObjectManager().getObjects(obj -> 
                obj != null && obj.getName() != null && 
                obj.getName().equals(WineConfig.LADDER_NAME) && 
                obj.canReach()
            );
            
            if (ladders == null || ladders.isEmpty()) {
                ScriptLogger.warning(script, "No ladders found on top floor");
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
            
            boolean interacted = furthestLadder.interact(WineConfig.LADDER_DOWN_ACTION);
            if (!interacted) {
                ScriptLogger.warning(script, "Failed to interact with ladder");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            // Wait for climb to second floor
            boolean climbed = script.pollFramesHuman(() -> {
                WorldPosition pos = script.getWorldPosition();
                return pos != null && pos.getPlane() == WineConfig.SECOND_FLOOR_PLANE;
            }, WineConfig.LADDER_CLIMB_TIMEOUT);
            
            if (climbed) {
                ScriptLogger.actionSuccess(script, "Climbed down to second floor");
            } else {
                ScriptLogger.actionFailure(script, "Ladder climb down from top", 1, 1);
            }
            
            return WineConfig.POLL_DELAY_MEDIUM;
        }
        
        // Step 3: If on second floor, navigate to ladder area and climb down to ground
        if (currentPlane == WineConfig.SECOND_FLOOR_PLANE) {
            if (!WineConfig.LADDER_AREA.contains(currentPos)) {
                ScriptLogger.info(script, "Moving to ladder area to go down");
                boolean navigating = navigation.navigateTo(WineConfig.LADDER_AREA);
                return navigating ? WineConfig.POLL_DELAY_MEDIUM : WineConfig.POLL_DELAY_LONG;
            }
            
            // Climb down to ground floor - use furthest ladder
            ScriptLogger.actionAttempt(script, "Climbing down to ground floor");
            
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
            WorldPosition secondFloorPos = script.getWorldPosition();
            ladders.sort((a, b) -> 
                Double.compare(
                    b.distance(secondFloorPos), 
                    a.distance(secondFloorPos)
                )
            );
            
            RSObject ladder = ladders.get(0);
            ScriptLogger.debug(script, "Using ladder at distance: " + ladder.distance(secondFloorPos));
            
            if (ladder == null) {
                ScriptLogger.warning(script, "Cannot find ladder");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            boolean interacted = ladder.interact(WineConfig.LADDER_DOWN_ACTION);
            if (!interacted) {
                ScriptLogger.warning(script, "Failed to interact with ladder");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            // Wait for climb to ground floor
            boolean climbed = script.pollFramesHuman(() -> {
                WorldPosition pos = script.getWorldPosition();
                return pos != null && pos.getPlane() == WineConfig.GROUND_FLOOR_PLANE;
            }, WineConfig.LADDER_CLIMB_TIMEOUT);
            
            if (climbed) {
                ScriptLogger.actionSuccess(script, "Climbed down ladder successfully");
            } else {
                ScriptLogger.actionFailure(script, "Ladder climb down", 1, 1);
            }
            
            return WineConfig.POLL_DELAY_MEDIUM;
        }
        
        // Step 4: Navigate to bank (should be on ground floor)
        if (currentPlane != WineConfig.GROUND_FLOOR_PLANE) {
            ScriptLogger.warning(script, "Not on ground floor, current plane: " + currentPlane);
            return WineConfig.POLL_DELAY_LONG;
        }
        if (!WineConfig.BANK_AREA.contains(currentPos)) {
            ScriptLogger.info(script, "Moving to bank area");
            boolean navigating = navigation.navigateTo(WineConfig.BANK_AREA);
            return navigating ? WineConfig.POLL_DELAY_MEDIUM : WineConfig.POLL_DELAY_LONG;
        }
        
        // Step 5: Open bank if not open
        Bank bank = script.getWidgetManager().getBank();
        if (bank == null) {
            ScriptLogger.warning(script, "Bank interface not available");
            return WineConfig.POLL_DELAY_LONG;
        }
        
        if (!bank.isVisible()) {
            ScriptLogger.actionAttempt(script, "Opening bank chest");
            RSObject bankChest = script.getObjectManager().getClosestObject(WineConfig.BANK_CHEST_NAME);
            
            if (bankChest == null) {
                ScriptLogger.warning(script, "Cannot find bank chest");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            boolean interacted = bankChest.interact(WineConfig.BANK_USE_ACTION);
            if (!interacted) {
                ScriptLogger.warning(script, "Failed to interact with bank chest");
                return WineConfig.POLL_DELAY_LONG;
            }
            
            // Wait for bank to open
            boolean opened = script.pollFramesHuman(() -> {
                Bank b = script.getWidgetManager().getBank();
                return b != null && b.isVisible();
            }, WineConfig.BANK_OPEN_TIMEOUT);
            
            if (opened) {
                ScriptLogger.actionSuccess(script, "Bank opened successfully");
            } else {
                ScriptLogger.actionFailure(script, "Bank open", 1, 1);
                return WineConfig.POLL_DELAY_LONG;
            }
            
            return WineConfig.POLL_DELAY_MEDIUM;
        }
        
        // Step 6: Deposit wines
        ScriptLogger.actionAttempt(script, "Depositing wines");
        
        // Get count before deposit
        ItemGroupResult invResult = script.getWidgetManager().getInventory().search(Set.of(WineConfig.WINE_ID));
        int winesInInventory = invResult != null ? invResult.getAmount(WineConfig.WINE_ID) : 0;
        
        // Deposit all items except nothing (deposit everything)
        boolean deposited = bank.depositAll(Set.of());  // Empty set means deposit all
        
        if (deposited) {
            // Wait for deposit to complete
            boolean depositComplete = script.pollFramesHuman(() -> {
                ItemGroupResult checkResult = script.getWidgetManager().getInventory().search(Set.of(WineConfig.WINE_ID));
                int currentWines = checkResult != null ? checkResult.getAmount(WineConfig.WINE_ID) : 0;
                return currentWines == 0;
            }, 3000);
            
            if (depositComplete) {
                ScriptLogger.actionSuccess(script, "Deposited " + winesInInventory + " wines");
                
                // Close bank
                bank.close();
                
                // Wait for bank to close
                script.pollFramesHuman(() -> {
                    Bank b = script.getWidgetManager().getBank();
                    return b == null || !b.isVisible();
                }, 2000);
                
                ScriptLogger.info(script, "Banking complete, returning to collection");
            } else {
                ScriptLogger.warning(script, "Deposit may not have completed fully");
            }
        } else {
            ScriptLogger.actionFailure(script, "Wine deposit", 1, 1);
        }
        
        return WineConfig.POLL_DELAY_LONG;
    }
}