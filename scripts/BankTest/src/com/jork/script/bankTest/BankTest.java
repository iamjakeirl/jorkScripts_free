package com.jork.script.bankTest;

import com.osmb.api.item.ItemID;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.ui.bank.Bank;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.utils.timing.Timer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * This is an example script that demonstrates a complete, human-like banking task.
 * When started, it will:
 * 1. Find the nearest bank if it's not already open.
 * 2. Interact with the bank to open it.
 * 3. Wait intelligently for the bank interface to appear, handling potential misclicks.
 * 4. Deposit all items except for a predefined list.
 * The script will then stop itself.
 */
@ScriptDefinition(
        name = "Bank Test",
        description = "A script for testing banking functionality",
        author = "jork",
        version = 1.0,
        skillCategory = SkillCategory.OTHER
)
public class BankTest extends Script {

    public BankTest(Object scriptCore) {
        super(scriptCore);
    }

    // --- Bank Object Identification ---

    /**
     * A list of common names for bank objects in the game. This allows our script
     * to be versatile and work in different locations (e.g., Grand Exchange, various banks).
     */
    public static final String[] BANK_NAMES = {"Bank", "Chest", "Bank booth", "Bank chest", "Grand Exchange booth", "Bank counter", "Bank table"};

    /**
     * A list of interaction options that can be used to open a bank.
     */
    public static final String[] BANK_ACTIONS = {"bank", "open", "use"};

    /**
     * This is a Predicate, which is essentially a complex filter. It's used to find valid bank objects.
     * It checks multiple conditions to ensure we're targeting a real, usable bank.
     */
    public static final Predicate<RSObject> BANK_QUERY = gameObject -> {
        // 1. Rule out objects with no name.
        if (gameObject.getName() == null) {
            return false;
        }
        // 2. Rule out objects with no interaction options.
        if (gameObject.getActions() == null) {
            return false;
        }
        // 3. Check if the object's name matches one of our known BANK_NAMES.
        if (!Arrays.stream(BANK_NAMES).anyMatch(name -> name.equalsIgnoreCase(gameObject.getName()))) {
            return false;
        }
        // 4. Check if the object has at least one of the valid BANK_ACTIONS.
        if (!Arrays.stream(gameObject.getActions()).anyMatch(action -> Arrays.stream(BANK_ACTIONS).anyMatch(bankAction -> bankAction.equalsIgnoreCase(action)))) {
            return false;
        }
        // 5. Final, crucial check: ensure the object is actually reachable by the player.
        return gameObject.canReach();
    };

    // --- State Management ---

    /**
     * An enumeration to represent the different states our script can be in.
     * Using a state machine makes the logic in the poll() method clean and easy to follow.
     */
    private enum State {
        STARTING,
        FINDING_BANK,
        OPENING_BANK,
        DEPOSITING,
        DONE
    }

    private State currentState = State.STARTING;
    private Timer timeout = new Timer(); // A timer to prevent getting stuck.

    // --- Script Main Loop ---

    /**
     * The poll() method is the heart of the script, called repeatedly.
     * The switch statement directs the script to the correct logic based on its current state.
     * @return The time in milliseconds to wait before the next poll.
     */
    @Override
    public int poll() {
        switch (currentState) {
            case STARTING:
                // At the start, we check if the bank is already open.
                if (getWidgetManager().getBank().isVisible()) {
                    log("Bank is already open. Moving to deposit.");
                    currentState = State.DEPOSITING;
                } else {
                    log("Bank is not open. Will find a nearby bank.");
                    currentState = State.FINDING_BANK;
                }
                break;

            case FINDING_BANK:
                findAndOpenBank();
                break;

            case OPENING_BANK:
                // This is the waiting state. We wait for the bank to open after clicking it.
                // We also check for a timeout in case the interaction failed.
                if (getWidgetManager().getBank().isVisible()) {
                    log("Bank has been opened.");
                    currentState = State.DEPOSITING;
                } else if (timeout.timeElapsed() > 8000) { // 8-second timeout
                    log("ERROR: Timed out waiting for bank to open. Stopping script.");
                    currentState = State.DONE;
                }
                break;

            case DEPOSITING:
                depositAllExceptSpecificItems();
                currentState = State.DONE;
                break;

            case DONE:
                // Returning -1 stops the script's poll() loop.
                log("Banking task complete. Stopping script.");
                return -1;
        }

        // Return a short delay for the next loop.
        return 300;
    }

    // --- Action Methods ---

    /**
     * Finds the closest bank using our BANK_QUERY and interacts with it.
     */
    private void findAndOpenBank() {
        log("Searching for a nearby bank...");
        List<RSObject> banksFound = getObjectManager().getObjects(BANK_QUERY);

        if (banksFound.isEmpty()) {
            log("ERROR: Could not find any reachable banks. Stopping script.");
            currentState = State.DONE;
            return;
        }

        // getClosest is a utility to find the nearest object from a list.
        RSObject bankObject = (RSObject) getUtils().getClosest(banksFound);
        log("Found bank: " + bankObject.getName() + ". Attempting to interact.");

        // Interact with the bank object using one of the valid actions.
        if (bankObject.interact(BANK_ACTIONS)) {
            // If interaction was sent, we move to a state of waiting for the bank to open.
            currentState = State.OPENING_BANK;
            timeout.reset(); // Reset the timeout timer for the waiting period.
        } else {
            log("ERROR: Failed to send interaction to the bank. Stopping script.");
            currentState = State.DONE;
        }
    }

    /**
     * Deposits all items from the inventory into the bank, except for a predefined
     * set of items. This is the final action of the script.
     */
    private void depositAllExceptSpecificItems() {
        Bank bank = getWidgetManager().getBank();

        Set<Integer> itemsToKeep = new HashSet<>();
        // Add the item IDs for the items you want to keep.
        itemsToKeep.add(ItemID.COINS_995);
        itemsToKeep.add(ItemID.TINDERBOX);
        itemsToKeep.add(ItemID.SMALL_FISHING_NET);

        log("Depositing all items except for the specified ones...");
        bank.depositAll(itemsToKeep);
    }
}