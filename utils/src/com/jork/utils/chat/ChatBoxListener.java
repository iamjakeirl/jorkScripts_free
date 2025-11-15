package com.jork.utils.chat;

import com.jork.utils.ScriptLogger;
import com.osmb.api.script.Script;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.chatbox.ChatboxFilterTab;
import com.osmb.api.utils.CachedObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instance-based chatbox listener that monitors game messages and triggers handlers.
 *
 * <p>Features:
 * <ul>
 *   <li>Fluent API for registering message handlers</li>
 *   <li>Automatic delay management (minimenu overlap, tap detection)</li>
 *   <li>Integration with ScriptLogger for debugging</li>
 *   <li>Thread-safe state management</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * public class MyScript extends Script {
 *     private ChatBoxListener chatListener;
 *
 *     @Override
 *     public void onStart() {
 *         chatListener = new ChatBoxListener(this)
 *             .on("you catch", msg -> fishCaught++)
 *             .on("you fail to", msg -> handleFailure())
 *             .enableDebugLogging();
 *     }
 *
 *     @Override
 *     public void onNewFrame() {
 *         chatListener.update();
 *     }
 * }
 * }</pre>
 */
public class ChatBoxListener {
    private final Script script;
    private final ChatBoxDelay readDelay;
    private final Map<String, ChatBoxMessageHandler> handlers;
    private final List<String> previousChatboxLines;

    private volatile boolean debugLogging;
    private volatile long tapDelayMillis;
    private volatile long lastChatBoxRead;
    private volatile long lastChatBoxChange;

    /**
     * Creates a new ChatBoxListener for the given script.
     *
     * @param script The script instance (used to access game API)
     */
    public ChatBoxListener(Script script) {
        this.script = script;
        this.readDelay = new ChatBoxDelay(1500);
        this.handlers = new LinkedHashMap<>();
        this.previousChatboxLines = new ArrayList<>();
        this.debugLogging = false;
        this.tapDelayMillis = 1500;
        this.lastChatBoxRead = 0;
        this.lastChatBoxChange = 0;
    }

    /**
     * Registers a message handler for messages containing the given pattern (case-insensitive).
     * Handlers are executed in the order they are registered.
     *
     * @param pattern The substring pattern to match
     * @param handler The handler to execute when a matching message is found
     * @return This listener instance for method chaining
     */
    public ChatBoxListener on(String pattern, ChatBoxMessageHandler handler) {
        if (pattern == null || handler == null) {
            throw new IllegalArgumentException("Pattern and handler must not be null");
        }
        handlers.put(pattern.toLowerCase(), handler);
        if (debugLogging) {
            ScriptLogger.debug(script, "Registered chatbox handler for pattern: '" + pattern + "'");
        }
        return this;
    }

    /**
     * Enables debug logging for chatbox events.
     * When enabled, all new messages and handler executions will be logged.
     *
     * @return This listener instance for method chaining
     */
    public ChatBoxListener enableDebugLogging() {
        this.debugLogging = true;
        ScriptLogger.debug(script, "ChatBox debug logging enabled");
        return this;
    }

    /**
     * Disables debug logging.
     *
     * @return This listener instance for method chaining
     */
    public ChatBoxListener disableDebugLogging() {
        this.debugLogging = false;
        return this;
    }

    /**
     * Sets the delay duration after a tap over the chatbox.
     * Default is 1500ms.
     *
     * @param millis The delay in milliseconds
     * @return This listener instance for method chaining
     */
    public ChatBoxListener setTapDelay(long millis) {
        this.tapDelayMillis = millis;
        return this;
    }

    /**
     * Main update method - should be called from the script's onNewFrame() method.
     * Checks for new chatbox messages and triggers registered handlers.
     */
    public void update() {
        updateChatBoxLines();
    }

    /**
     * Ensures the chatbox is visible and set to the GAME filter tab.
     * Call this before waiting for specific chatbox messages.
     */
    public void ensureChatboxVisible() {
        if (script.getWidgetManager().getDialogue().getDialogueType() == null
                && script.getWidgetManager().getChatbox().getActiveFilterTab() != ChatboxFilterTab.GAME) {
            script.getWidgetManager().getChatbox().openFilterTab(ChatboxFilterTab.GAME);
            if (debugLogging) {
                ScriptLogger.debug(script, "Opened GAME chat filter tab");
            }
        }
    }

    /**
     * Gets the time in milliseconds since the last chatbox read.
     *
     * @return Milliseconds since last read, or 0 if never read
     */
    public long getTimeSinceLastRead() {
        if (lastChatBoxRead == 0) {
            return 0;
        }
        return System.currentTimeMillis() - lastChatBoxRead;
    }

    /**
     * Gets the time in milliseconds since the last chatbox change (new message).
     *
     * @return Milliseconds since last change, or 0 if no changes detected
     */
    public long getTimeSinceLastChange() {
        if (lastChatBoxChange == 0) {
            return 0;
        }
        return System.currentTimeMillis() - lastChatBoxChange;
    }

    /**
     * Clears all registered handlers.
     */
    public void clearHandlers() {
        handlers.clear();
        if (debugLogging) {
            ScriptLogger.debug(script, "Cleared all chatbox handlers");
        }
    }

    /**
     * Clears the chatbox line history.
     * Useful when you want to reset state or avoid re-processing old messages.
     */
    public void clearHistory() {
        previousChatboxLines.clear();
        if (debugLogging) {
            ScriptLogger.debug(script, "Cleared chatbox history");
        }
    }

    /**
     * Gets the current number of registered handlers.
     *
     * @return Number of registered handlers
     */
    public int getHandlerCount() {
        return handlers.size();
    }

    /**
     * Checks if the chatbox reader is currently delayed.
     *
     * @return true if delayed (due to minimenu or tap)
     */
    public boolean isDelayed() {
        return readDelay.isActive();
    }

    private void updateChatBoxLines() {
        // Check if active filter tab is GAME
        if (script.getWidgetManager().getChatbox().getActiveFilterTab() != ChatboxFilterTab.GAME) {
            return;
        }

        // Get chatbox bounds
        Rectangle chatboxBounds = script.getWidgetManager().getChatbox().getBounds();
        if (chatboxBounds == null) {
            return;
        }

        // Check if minimenu overlaps chatbox
        CachedObject<Rectangle> minimenuBounds = script.getWidgetManager().getMiniMenu().getMenuBounds();
        if (minimenuBounds != null && minimenuBounds.getScreenUUID() != null
                && minimenuBounds.getScreenUUID().equals(script.getScreen().getUUID())) {
            if (minimenuBounds.getObject().intersects(chatboxBounds)) {
                if (debugLogging) {
                    ScriptLogger.debug(script, "Minimenu intersects chatbox - activating delay");
                }
                readDelay.activate();
                return;
            }
        }

        // Check if we recently tapped over the chatbox (causes text reading issues)
        Rectangle chatboxBounds2 = chatboxBounds.getPadding(0, 0, 12, 0);
        long lastTapMillis = script.getFinger().getLastTapMillis();
        if (chatboxBounds2.contains(script.getFinger().getLastTapX(), script.getFinger().getLastTapY())
                && System.currentTimeMillis() - lastTapMillis < tapDelayMillis) {
            if (debugLogging) {
                ScriptLogger.debug(script, "Recent tap over chatbox - activating delay");
            }
            readDelay.activate();
            return;
        }

        // Return if delay is active
        if (readDelay.isActive()) {
            return;
        }

        // Read current chatbox lines
        var currentChatboxLines = script.getWidgetManager().getChatbox().getText();
        if (currentChatboxLines.isNotVisible()) {
            if (debugLogging) {
                ScriptLogger.debug(script, "Chatbox not visible");
            }
            return;
        }

        List<String> currentLines = currentChatboxLines.asList();
        if (currentLines.isEmpty()) {
            return;
        }

        // Get new lines
        List<String> newLines = getNewLines(currentLines, previousChatboxLines);

        // Update previous lines
        previousChatboxLines.clear();
        previousChatboxLines.addAll(currentLines);

        // Process new messages
        if (!newLines.isEmpty()) {
            onNewChatBoxMessages(newLines);
        }
    }

    private void onNewChatBoxMessages(List<String> newLines) {
        for (String line : newLines) {
            ChatBoxMessage message = new ChatBoxMessage(line);

            if (debugLogging) {
                ScriptLogger.debug(script, "New chatbox message: " + line);
            }

            // Trigger matching handlers
            for (Map.Entry<String, ChatBoxMessageHandler> entry : handlers.entrySet()) {
                String pattern = entry.getKey();
                ChatBoxMessageHandler handler = entry.getValue();

                if (message.contains(pattern)) {
                    if (debugLogging) {
                        ScriptLogger.debug(script, "Triggering handler for pattern: '" + pattern + "'");
                    }

                    try {
                        handler.onMessage(message);
                    } catch (Exception e) {
                        ScriptLogger.exception(script, "Error in chatbox handler for pattern: '" + pattern + "'", e);
                    }
                }
            }
        }
    }

    private List<String> getNewLines(List<String> currentLines, List<String> previousLines) {
        lastChatBoxRead = System.currentTimeMillis();

        if (currentLines.isEmpty()) {
            return Collections.emptyList();
        }

        int firstDifference = 0;

        if (!previousLines.isEmpty()) {
            if (currentLines.equals(previousLines)) {
                return Collections.emptyList();
            }

            int currSize = currentLines.size();
            int prevSize = previousLines.size();

            for (int i = 0; i < currSize; i++) {
                int suffixLen = currSize - i;
                if (suffixLen > prevSize) {
                    continue;
                }

                boolean match = true;
                for (int j = 0; j < suffixLen; j++) {
                    if (!currentLines.get(i + j).equals(previousLines.get(j))) {
                        match = false;
                        break;
                    }
                }

                if (match) {
                    firstDifference = i;
                    break;
                }
            }
        }

        List<String> newLines = firstDifference == 0
                ? List.copyOf(currentLines)
                : currentLines.subList(0, firstDifference);

        if (!newLines.isEmpty()) {
            lastChatBoxChange = System.currentTimeMillis();
        }

        return newLines;
    }
}
