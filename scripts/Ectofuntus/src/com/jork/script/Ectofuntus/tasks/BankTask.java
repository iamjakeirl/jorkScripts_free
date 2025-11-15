package com.jork.script.Ectofuntus.tasks;

import com.jork.script.Ectofuntus.Ectofuntus;
import com.jork.utils.ScriptLogger;

/**
 * Handles banking operations for the Ectofuntus script.
 * Priority: 1 (Highest)
 *
 * @author jork
 */
public class BankTask {

    private final Ectofuntus script;

    public BankTask(Ectofuntus script) {
        this.script = script;
    }

    /**
     * Check if this task can execute
     */
    public boolean canExecute() {
        // Execute when shouldBank flag is set
        return script.shouldBank();
    }

    /**
     * Execute the banking task
     * @return poll delay in milliseconds
     */
    public int execute() {
        ScriptLogger.info(script, "BankTask - Stub implementation");

        // TODO: Implement banking logic
        // - Open bank
        // - Deposit all except ectophial and teleport items
        // - Withdraw bones, empty pots, empty buckets
        // - Verify supplies
        // - Set shouldBank to false
        // - Reset hasSlime and hasBoneMeal flags

        return 1000;
    }
}
