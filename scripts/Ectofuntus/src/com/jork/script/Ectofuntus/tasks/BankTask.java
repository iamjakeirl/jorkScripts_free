package com.jork.script.Ectofuntus.tasks;

import com.jork.script.Ectofuntus.Ectofuntus;
import com.jork.script.Ectofuntus.config.EctoConfig;
import com.jork.utils.ExceptionUtils;
import com.jork.utils.ScriptLogger;
import com.osmb.api.ui.bank.Bank;
import com.osmb.api.ui.WidgetManager;
import com.osmb.api.utils.UIResult;

import java.util.HashSet;
import java.util.Set;

/**
 * Handles banking operations for the Ectofuntus script.
 * Uses intelligent inventory management - only withdraws what's needed.
 * Priority: 1 (Highest)
 *
 * @author jork
 */
public class BankTask {

    // ───────────────────────────────────────────────────────────────────────────
    // Item ID Constants
    // ───────────────────────────────────────────────────────────────────────────
    private static final int ECTOPHIAL = 4251;
    private static final int EMPTY_POT = 1931;
    private static final int EMPTY_BUCKET = 1925;

    // ───────────────────────────────────────────────────────────────────────────
    // State Machine
    // ───────────────────────────────────────────────────────────────────────────
    private enum BankingState {
        OPENING_BANK,
        ANALYZING_INVENTORY,
        DEPOSITING_UNWANTED,
        SWITCHING_TAB,
        CHECKING_SUPPLIES,
        WITHDRAWING_ITEMS,
        VERIFYING_INVENTORY,
        COMPLETE
    }

    private enum SupplyCheckResult {
        SUCCESS,              // All items found
        MISSING_BONES,        // Primary bone type not found (future: try next type)
        MISSING_CONTAINERS,   // Pots/buckets not found (stop)
        MISSING_ECTOPHIAL,    // Ectophial not found (stop)
        MISSING_TELEPORT      // Teleport item not found (stop)
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Instance Fields
    // ───────────────────────────────────────────────────────────────────────────
    private final Ectofuntus script;
    private BankingState currentState = BankingState.OPENING_BANK;

    // Inventory tracking
    private int currentBoneCount = 0;
    private int currentPotCount = 0;
    private int currentBucketCount = 0;
    private boolean hasUnwantedItems = false;

    public BankTask(Ectofuntus script) {
        this.script = script;
    }

    /**
     * Check if this task can execute
     */
    public boolean canExecute() {
        return script.shouldBank();
    }

    /**
     * Execute the banking task
     * @return poll delay in milliseconds
     */
    public int execute() {
        switch (currentState) {
            case OPENING_BANK:
                return handleOpenBank();
            case ANALYZING_INVENTORY:
                return handleAnalyzeInventory();
            case DEPOSITING_UNWANTED:
                return handleDepositUnwanted();
            case SWITCHING_TAB:
                return handleSwitchTab();
            case CHECKING_SUPPLIES:
                return handleCheckSupplies();
            case WITHDRAWING_ITEMS:
                return handleWithdrawItems();
            case VERIFYING_INVENTORY:
                return handleVerifyInventory();
            case COMPLETE:
                return handleComplete();
            default:
                ScriptLogger.error(script, "Unknown banking state: " + currentState);
                currentState = BankingState.OPENING_BANK;
                return 1000;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // State Handlers
    // ═══════════════════════════════════════════════════════════════════════════

    private int handleOpenBank() {
        WidgetManager wm = script.getWidgetManager();
        if (wm == null) {
            ScriptLogger.warning(script, "WidgetManager not available");
            return 1000;
        }

        Bank bank = wm.getBank();
        if (bank == null) {
            ScriptLogger.warning(script, "Bank instance not available");
            return 1000;
        }

        if (!bank.isVisible()) {
            ScriptLogger.info(script, "Opening bank...");

            // TODO: Navigate to bank and open it
            // For now, assume bank is already open or accessible
            // This will be implemented when we add bank location navigation

            return 600;
        }

        ScriptLogger.info(script, "Bank is open - analyzing inventory");
        currentState = BankingState.ANALYZING_INVENTORY;
        return 200;
    }

    private int handleAnalyzeInventory() {
        EctoConfig config = script.getConfig();
        if (config == null) {
            ScriptLogger.error(script, "Config not available");
            script.stop();
            return 1000;
        }

        // Count current inventory
        currentBoneCount = countItemInInventory(config.getBoneType().getItemId());
        currentPotCount = countItemInInventory(EMPTY_POT);
        currentBucketCount = countItemInInventory(EMPTY_BUCKET);

        int ectophialCount = countItemInInventory(ECTOPHIAL);
        int teleportCount = config.getBankLocation().requiresItem()
            ? countItemInInventory(config.getBankLocation().getItemId())
            : 0;

        ScriptLogger.info(script, String.format("Current inventory - Bones: %d, Pots: %d, Buckets: %d, Ecto: %d, Teleport: %d",
            currentBoneCount, currentPotCount, currentBucketCount, ectophialCount, teleportCount));

        // Check if we have unexpected items
        int totalExpectedItems = currentBoneCount + currentPotCount + currentBucketCount + ectophialCount + teleportCount;
        int totalItems = getTotalInventoryCount();

        hasUnwantedItems = totalItems > totalExpectedItems;

        if (hasUnwantedItems) {
            ScriptLogger.info(script, "Detected unwanted items - will deposit them");
            currentState = BankingState.DEPOSITING_UNWANTED;
        } else {
            ScriptLogger.info(script, "Inventory clean - skipping deposit");
            currentState = BankingState.SWITCHING_TAB;
        }

        return 200;
    }

    private int handleDepositUnwanted() {
        Bank bank = getBankSafely();
        if (bank == null || !bank.isVisible()) {
            ScriptLogger.warning(script, "Bank not available during deposit");
            currentState = BankingState.OPENING_BANK;
            return 600;
        }

        EctoConfig config = script.getConfig();
        if (config == null) {
            ScriptLogger.error(script, "Config not available");
            script.stop();
            return 1000;
        }

        // Build ignore set - items we want to keep
        Set<Integer> ignoreItems = new HashSet<>();
        ignoreItems.add(ECTOPHIAL);
        ignoreItems.add(EMPTY_POT);
        ignoreItems.add(EMPTY_BUCKET);
        ignoreItems.add(config.getBoneType().getItemId());

        if (config.getBankLocation().requiresItem()) {
            ignoreItems.add(config.getBankLocation().getItemId());
        }

        ScriptLogger.info(script, "Depositing unwanted items...");

        try {
            boolean deposited = bank.depositAll(ignoreItems);

            if (deposited) {
                // Wait for deposit to complete
                boolean success = script.pollFramesUntil(() -> {
                    int newTotal = getTotalInventoryCount();
                    int expectedCount = countItemInInventory(ECTOPHIAL)
                        + countItemInInventory(EMPTY_POT)
                        + countItemInInventory(EMPTY_BUCKET)
                        + (config.getBankLocation().requiresItem()
                            ? countItemInInventory(config.getBankLocation().getItemId())
                            : 0);
                    return newTotal == expectedCount;
                }, 3000);

                if (success) {
                    ScriptLogger.info(script, "Deposit complete");
                    currentState = BankingState.SWITCHING_TAB;
                    return 300;
                } else {
                    ScriptLogger.warning(script, "Deposit timeout - retrying");
                    return 600;
                }
            } else {
                ScriptLogger.warning(script, "Deposit failed - retrying");
                return 600;
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfTaskInterrupted(e);
            ScriptLogger.exception(script, "Error during deposit", e);
            return 1000;
        }
    }

    private int handleSwitchTab() {
        Bank bank = getBankSafely();
        if (bank == null || !bank.isVisible()) {
            ScriptLogger.warning(script, "Bank not available during tab switch");
            currentState = BankingState.OPENING_BANK;
            return 600;
        }

        EctoConfig config = script.getConfig();
        if (config == null) {
            ScriptLogger.error(script, "Config not available");
            script.stop();
            return 1000;
        }

        int targetTab = config.getBankTab();

        try {
            UIResult<Integer> currentTabResult = bank.getSelectedTabIndex();

            if (currentTabResult.isNotVisible()) {
                ScriptLogger.warning(script, "Bank tab not visible - retrying");
                return 600;
            }

            if (currentTabResult.isNotFound()) {
                ScriptLogger.warning(script, "Could not determine current tab - assuming need to switch");
                bank.setSelectedTabIndex(targetTab);
                return 600;
            }

            int currentTab = currentTabResult.get();

            if (currentTab == targetTab) {
                ScriptLogger.info(script, "Already on tab " + targetTab);
                currentState = BankingState.CHECKING_SUPPLIES;
                return 200;
            }

            ScriptLogger.info(script, "Switching from tab " + currentTab + " to tab " + targetTab);
            boolean switched = bank.setSelectedTabIndex(targetTab);

            if (switched) {
                // Wait for tab switch
                boolean success = script.pollFramesUntil(() -> {
                    UIResult<Integer> check = bank.getSelectedTabIndex();
                    return check.isFound() && check.get() == targetTab;
                }, 3000);

                if (success) {
                    ScriptLogger.info(script, "Tab switch complete");
                    currentState = BankingState.CHECKING_SUPPLIES;
                    return 300;
                } else {
                    ScriptLogger.warning(script, "Tab switch timeout");
                    return 600;
                }
            } else {
                ScriptLogger.warning(script, "Tab switch failed - retrying");
                return 600;
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfTaskInterrupted(e);
            ScriptLogger.exception(script, "Error during tab switch", e);
            currentState = BankingState.CHECKING_SUPPLIES;
            return 800;
        }
    }

    private int handleCheckSupplies() {
        SupplyCheckResult result = checkSupplies();

        if (result == SupplyCheckResult.SUCCESS) {
            ScriptLogger.info(script, "Supply check passed");
            currentState = BankingState.WITHDRAWING_ITEMS;
            return 200;
        }

        // Handle failures
        switch (result) {
            case MISSING_BONES:
                ScriptLogger.error(script, "No bones found in bank - stopping script");
                // Future: Try next bone type here
                script.stop();
                return 1000;

            case MISSING_CONTAINERS:
                ScriptLogger.error(script, "Empty pots or buckets not found in bank - stopping script");
                script.stop();
                return 1000;

            case MISSING_ECTOPHIAL:
                ScriptLogger.error(script, "Ectophial not found in bank - stopping script");
                script.stop();
                return 1000;

            case MISSING_TELEPORT:
                ScriptLogger.error(script, "Teleport item not found in bank - stopping script");
                script.stop();
                return 1000;

            default:
                ScriptLogger.error(script, "Unknown supply check result - stopping script");
                script.stop();
                return 1000;
        }
    }

    private int handleWithdrawItems() {
        Bank bank = getBankSafely();
        if (bank == null || !bank.isVisible()) {
            ScriptLogger.warning(script, "Bank not available during withdraw");
            currentState = BankingState.OPENING_BANK;
            return 600;
        }

        EctoConfig config = script.getConfig();
        if (config == null) {
            ScriptLogger.error(script, "Config not available");
            script.stop();
            return 1000;
        }

        int targetCount = getSuppliesPerType();
        int boneId = config.getBoneType().getItemId();

        // Calculate what we need
        int bonesNeeded = targetCount - currentBoneCount;
        int potsNeeded = targetCount - currentPotCount;
        int bucketsNeeded = targetCount - currentBucketCount;

        ScriptLogger.info(script, String.format("Withdrawing - Bones: %d, Pots: %d, Buckets: %d",
            bonesNeeded, potsNeeded, bucketsNeeded));

        try {
            // Withdraw bones
            if (bonesNeeded > 0) {
                boolean success = bank.withdraw(boneId, bonesNeeded);
                if (!success) {
                    ScriptLogger.warning(script, "Failed to withdraw bones");
                    return 600;
                }
                script.pollFramesHuman(() ->
                    countItemInInventory(boneId) >= targetCount, 2000);
            }

            // Withdraw pots
            if (potsNeeded > 0) {
                boolean success = bank.withdraw(EMPTY_POT, potsNeeded);
                if (!success) {
                    ScriptLogger.warning(script, "Failed to withdraw pots");
                    return 600;
                }
                script.pollFramesHuman(() ->
                    countItemInInventory(EMPTY_POT) >= targetCount, 2000);
            }

            // Withdraw buckets
            if (bucketsNeeded > 0) {
                boolean success = bank.withdraw(EMPTY_BUCKET, bucketsNeeded);
                if (!success) {
                    ScriptLogger.warning(script, "Failed to withdraw buckets");
                    return 600;
                }
                script.pollFramesHuman(() ->
                    countItemInInventory(EMPTY_BUCKET) >= targetCount, 2000);
            }

            ScriptLogger.info(script, "Withdrawals complete - verifying inventory");
            currentState = BankingState.VERIFYING_INVENTORY;
            return 400;

        } catch (Exception e) {
            ExceptionUtils.rethrowIfTaskInterrupted(e);
            ScriptLogger.exception(script, "Error during withdrawal", e);
            return 1000;
        }
    }

    private int handleVerifyInventory() {
        EctoConfig config = script.getConfig();
        if (config == null) {
            ScriptLogger.error(script, "Config not available");
            script.stop();
            return 1000;
        }

        int targetCount = getSuppliesPerType();
        int boneId = config.getBoneType().getItemId();

        // Check final counts
        int finalBones = countItemInInventory(boneId);
        int finalPots = countItemInInventory(EMPTY_POT);
        int finalBuckets = countItemInInventory(EMPTY_BUCKET);

        ScriptLogger.info(script, String.format("Final counts - Bones: %d/%d, Pots: %d/%d, Buckets: %d/%d",
            finalBones, targetCount, finalPots, targetCount, finalBuckets, targetCount));

        // Verify inventory
        boolean verified = (finalBones == targetCount &&
                           finalPots == targetCount &&
                           finalBuckets == targetCount);

        if (!verified) {
            ScriptLogger.error(script, String.format(
                "Verification failed - Expected %d of each, got Bones:%d Pots:%d Buckets:%d",
                targetCount, finalBones, finalPots, finalBuckets));
            // Could retry here, but for safety let's stop
            script.stop();
            return 1000;
        }

        ScriptLogger.info(script, "Inventory verification successful");
        currentState = BankingState.COMPLETE;
        return 200;
    }

    private int handleComplete() {
        Bank bank = getBankSafely();
        if (bank != null && bank.isVisible()) {
            bank.close();
            ScriptLogger.info(script, "Bank closed");
        }

        // Reset script state
        script.setShouldBank(false);
        script.setHasSlime(false);
        script.setHasBoneMeal(false);

        // Reset task state for next cycle
        currentState = BankingState.OPENING_BANK;

        ScriptLogger.info(script, "Banking complete - ready for Ectofuntus");
        return 600;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Safely gets the Bank instance with null checks.
     * @return Bank instance or null if unavailable
     */
    private Bank getBankSafely() {
        WidgetManager wm = script.getWidgetManager();
        if (wm == null) {
            return null;
        }
        return wm.getBank();
    }

    /**
     * Calculates how many of each supply type to withdraw.
     * MVP: Always returns 8 (conservative)
     * Phase 2: Returns 9 when teleport is wearable
     */
    private int getSuppliesPerType() {
        return 8;  // MVP implementation

        // Future Phase 2:
        // EctoConfig config = script.getConfig();
        // if (config != null && config.getBankLocation().isWearable()) {
        //     return 9;
        // }
        // return 8;
    }

    /**
     * Checks if all required supplies are present in the bank.
     */
    private SupplyCheckResult checkSupplies() {
        Bank bank = getBankSafely();
        if (bank == null || !bank.isVisible()) {
            ScriptLogger.warning(script, "Bank not available for supply check");
            return SupplyCheckResult.MISSING_ECTOPHIAL;
        }

        EctoConfig config = script.getConfig();
        if (config == null) {
            ScriptLogger.error(script, "Config not available");
            return SupplyCheckResult.MISSING_ECTOPHIAL;
        }

        try {
            // Check ectophial
            if (!bankContains(bank, ECTOPHIAL)) {
                ScriptLogger.error(script, "Ectophial not found in bank");
                return SupplyCheckResult.MISSING_ECTOPHIAL;
            }

            // Check teleport item (if required)
            if (config.getBankLocation().requiresItem()) {
                if (!bankContains(bank, config.getBankLocation().getItemId())) {
                    ScriptLogger.error(script, "Teleport item not found in bank");
                    return SupplyCheckResult.MISSING_TELEPORT;
                }
            }

            // Check bones
            int boneId = config.getBoneType().getItemId();
            if (!bankContains(bank, boneId)) {
                ScriptLogger.warning(script, "No " + config.getBoneType().getDisplayName() + " found in bank");
                return SupplyCheckResult.MISSING_BONES;
            }

            // Check empty pots
            if (!bankContains(bank, EMPTY_POT)) {
                ScriptLogger.error(script, "Empty pots not found in bank");
                return SupplyCheckResult.MISSING_CONTAINERS;
            }

            // Check empty buckets
            if (!bankContains(bank, EMPTY_BUCKET)) {
                ScriptLogger.error(script, "Empty buckets not found in bank");
                return SupplyCheckResult.MISSING_CONTAINERS;
            }

            return SupplyCheckResult.SUCCESS;

        } catch (Exception e) {
            ExceptionUtils.rethrowIfTaskInterrupted(e);
            ScriptLogger.exception(script, "Error during supply check", e);
            return SupplyCheckResult.MISSING_ECTOPHIAL;
        }
    }

    /**
     * Checks if the bank contains a specific item.
     */
    private boolean bankContains(Bank bank, int itemId) {
        try {
            var search = bank.search(Set.of(itemId));
            return search != null && search.getAmount(itemId) > 0;
        } catch (Exception e) {
            ScriptLogger.debug(script, "Error checking bank for item " + itemId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Counts how many of a specific item are in the inventory.
     */
    private int countItemInInventory(int itemId) {
        try {
            WidgetManager wm = script.getWidgetManager();
            if (wm == null || wm.getInventory() == null) {
                return 0;
            }

            var search = wm.getInventory().search(Set.of(itemId));
            if (search == null) {
                return 0;
            }

            return search.getAmount(itemId);
        } catch (Exception e) {
            ScriptLogger.debug(script, "Error counting item " + itemId + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets total count of items in inventory.
     */
    private int getTotalInventoryCount() {
        try {
            WidgetManager wm = script.getWidgetManager();
            if (wm == null || wm.getInventory() == null) {
                return 0;
            }

            // Search for all items (empty set means scan all slots)
            var result = wm.getInventory().search(Set.of());
            if (result == null) {
                return 0;
            }

            return result.getOccupiedSlotCount();
        } catch (Exception e) {
            ScriptLogger.debug(script, "Error getting inventory count: " + e.getMessage());
            return 0;
        }
    }
}
