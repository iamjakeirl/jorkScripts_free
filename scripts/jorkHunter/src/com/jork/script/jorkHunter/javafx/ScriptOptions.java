package com.jork.script.jorkHunter.javafx;

import com.jork.script.jorkHunter.JorkHunter;
import com.jork.script.jorkHunter.config.HuntingConfig;
import com.jork.script.jorkHunter.trap.TrapType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal JavaFX options window for the JorkHunter script.
 * Provides configuration options:
 *   – Target (Birds or Chinchompas)
 *   – Placement Strategy (trap placement patterns)
 * Uses TilePicker for hunting location selection in-game.
 */
public class ScriptOptions extends VBox {

    private final ComboBox<String> targetDropdown;
    private final ComboBox<String> strategyDropdown;
    private final Button            confirmBtn;
    private final CheckBox manualLevelCheck;
    private final TextField levelInput;
    private final CheckBox expediteCollectionCheck;
    private final TextField expediteChanceInput;
    
    // Dynamic strategy options
    private VBox strategyOptionsContainer;
    private final Map<String, Object> strategyOptions = new HashMap<>();
    
    // Line pattern specific controls
    private ComboBox<String> lineOrientationDropdown;

    private final HuntingConfig config;
    
    public ScriptOptions(JorkHunter script, HuntingConfig config) {
        this.config = config;
        setSpacing(15);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(20));
        setMinWidth(320);
        setPrefWidth(350);

        // ── Target selection (specific creatures for XP tracking) ──────
        Label targetLbl      = new Label("Select creature:");
        targetLbl.setMinWidth(120);
        targetDropdown       = new ComboBox<>();
        
        // Add specific creatures based on enabled trap types in config
        if (config.isTypeEnabled(TrapType.BIRD_SNARE)) {
            targetDropdown.getItems().addAll(
                "Crimson Swift",
                "Copper Longtail", 
                "Tropical Wagtail",
                "Cerulean Twitch"
            );
        }
        if (config.isTypeEnabled(TrapType.CHINCHOMPA)) {
            targetDropdown.getItems().addAll(
                "Grey Chinchompa",
                "Red Chinchompa",
                "Black Chinchompa"
            );
        }
        
        // If it's the "all features" variant, show all creatures
        if (config.isAllFeaturesEnabled()) {
            targetDropdown.getItems().clear();
            targetDropdown.getItems().addAll(
                "Crimson Swift",
                "Copper Longtail",
                "Tropical Wagtail", 
                "Cerulean Twitch",
                "Grey Chinchompa",
                "Red Chinchompa",
                "Black Chinchompa"
            );
        }
        
        targetDropdown.getSelectionModel().selectFirst();
        targetDropdown.setPrefWidth(150);
        HBox targetRow = new HBox(targetLbl, targetDropdown);
        targetRow.setSpacing(10);
        targetRow.setAlignment(Pos.CENTER_LEFT);
        
        // ── Info label about location selection ─────────
        Label infoLbl = new Label("Location will be selected in-game using tile picker");
        infoLbl.setStyle("-fx-font-style: italic; -fx-text-fill: #666;");
        infoLbl.setWrapText(true);
        infoLbl.setMaxWidth(300);

        // ── Placement Strategy selection ─────────────────────
        Label strategyLbl = new Label("Placement strategy:");
        strategyLbl.setMinWidth(120);
        strategyDropdown  = new ComboBox<>();
        strategyDropdown.getItems().addAll("Auto", "X-Pattern", "L-Pattern", "Line", "Cross");
        strategyDropdown.getSelectionModel().selectFirst();  // Auto is default
        strategyDropdown.setPrefWidth(150);
        HBox strategyRow  = new HBox(strategyLbl, strategyDropdown);
        strategyRow.setSpacing(10);
        strategyRow.setAlignment(Pos.CENTER_LEFT);
        
        // ── Dynamic Strategy Options Container ────────────
        strategyOptionsContainer = new VBox(8);
        strategyOptionsContainer.setAlignment(Pos.CENTER_LEFT);
        strategyOptionsContainer.setPadding(new Insets(10, 0, 10, 0));
        strategyOptionsContainer.setMinHeight(80);
        
        // Listen for strategy changes to update options
        strategyDropdown.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateStrategyOptions(newVal);
        });
        
        // Initialize with default strategy options
        updateStrategyOptions(strategyDropdown.getValue());

        // ── Manual Hunter Level ───────────────────────────
        manualLevelCheck = new CheckBox("Enter hunter level manually");
        levelInput = new TextField();
        levelInput.setPromptText("Level 1-99");
        levelInput.setDisable(true);
        manualLevelCheck.selectedProperty().addListener((obs, o, n) -> levelInput.setDisable(!n));
        
        // ── Expedite Collection Settings ─────────────────
        expediteCollectionCheck = new CheckBox("Expedite trap collection before breaks");
        expediteCollectionCheck.setSelected(false);
        
        Label chanceLabel = new Label("Chance:");
        expediteChanceInput = new TextField("50");
        expediteChanceInput.setPromptText("0-100");
        expediteChanceInput.setPrefWidth(60);
        expediteChanceInput.setDisable(true);
        Label percentLabel = new Label("%");
        
        // Enable/disable chance input based on checkbox
        expediteCollectionCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            expediteChanceInput.setDisable(!newVal);
            if (!newVal) {
                expediteChanceInput.setText("50");
            }
        });
        
        HBox expediteRow = new HBox(10);
        expediteRow.setAlignment(Pos.CENTER_LEFT);
        expediteRow.getChildren().addAll(expediteCollectionCheck, chanceLabel, expediteChanceInput, percentLabel);

        // ── Confirm button ─────────────────────────────────
        confirmBtn = new Button("Start");
        confirmBtn.setPrefWidth(100);
        confirmBtn.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        confirmBtn.setOnAction(e -> {
            saveStrategyOptions();
            ((Stage) getScene().getWindow()).close(); 
            
            // Always set requiresCustomAnchor to true since we're always using TilePicker
            strategyOptions.put("requiresCustomAnchor", true);
            
            // Add expedite settings to strategy options
            strategyOptions.put("expediteCollection", isExpediteCollectionEnabled());
            strategyOptions.put("expediteChance", getExpediteCollectionChance());
            
            // Notify the script that settings have been confirmed (FX thread)
            script.onSettingsSelected(
                getSelectedTarget(), 
                null, // No area selection anymore
                getSelectedStrategy(),
                isManualLevel(), 
                getManualLevel(),
                strategyOptions
            );
        });

        getChildren().addAll(targetRow, infoLbl, strategyRow, strategyOptionsContainer, 
                            manualLevelCheck, levelInput, expediteRow, confirmBtn);
    }
    
    /**
     * Updates the strategy-specific options based on selected strategy.
     */
    private void updateStrategyOptions(String strategyName) {
        strategyOptionsContainer.getChildren().clear();
        strategyOptions.clear();
        
        if ("Line".equals(strategyName)) {
            // Add Line-specific options
            Label optionsLabel = new Label("Line Pattern Options:");
            optionsLabel.setStyle("-fx-font-weight: bold");
            
            Label orientationLabel = new Label("Orientation:");
            orientationLabel.setMinWidth(80);
            lineOrientationDropdown = new ComboBox<>();
            lineOrientationDropdown.getItems().addAll("Horizontal", "Vertical");
            lineOrientationDropdown.getSelectionModel().selectFirst();
            lineOrientationDropdown.setPrefWidth(120);
            
            HBox orientationRow = new HBox(10, orientationLabel, lineOrientationDropdown);
            orientationRow.setAlignment(Pos.CENTER_LEFT);
            
            strategyOptionsContainer.getChildren().addAll(
                optionsLabel, orientationRow
            );
        } else if ("Auto".equals(strategyName)) {
            // Show info about Auto mode
            Label infoLabel = new Label("Auto Mode");
            infoLabel.setStyle("-fx-font-weight: bold");
            
            Label descLabel = new Label("Automatically selects optimal pattern:\n" +
                                      "• 1-2 traps: Line\n" +
                                      "• 3 traps: L-Pattern\n" + 
                                      "• 4 traps: Cross\n" +
                                      "• 5 traps: X-Pattern");
            descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            descLabel.setWrapText(true);
            
            strategyOptionsContainer.getChildren().addAll(infoLabel, descLabel);
        } else {
            // For other patterns, just show a simple description
            Label infoLabel = new Label(strategyName + " Pattern");
            infoLabel.setStyle("-fx-font-weight: bold");
            
            Label noteLabel = new Label("Will use custom center selected in-game");
            noteLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #777;");
            
            strategyOptionsContainer.getChildren().addAll(infoLabel, noteLabel);
        }
    }
    
    /**
     * Saves the current strategy options to the map.
     */
    private void saveStrategyOptions() {
        String strategy = getSelectedStrategy();
        
        // Always use custom anchor now
        strategyOptions.put("requiresCustomAnchor", true);
        
        if ("Line".equals(strategy)) {
            if (lineOrientationDropdown != null) {
                strategyOptions.put("lineOrientation", lineOrientationDropdown.getValue());
            }
        }
    }

    public String getSelectedTarget() {
        return targetDropdown.getValue();
    }

    // Area selection removed - always using TilePicker

    public String getSelectedStrategy() {
        return strategyDropdown.getValue();
    }
    
    public Map<String, Object> getStrategyOptions() {
        return new HashMap<>(strategyOptions);
    }

    public boolean isManualLevel() {
        return manualLevelCheck.isSelected();
    }

    public int getManualLevel() {
        try {
            return Integer.parseInt(levelInput.getText().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    // Area dropdown removed - using TilePicker for location selection
    
    /**
     * Gets whether expedite collection is enabled.
     * @return true if expedite collection is enabled
     */
    public boolean isExpediteCollectionEnabled() {
        return expediteCollectionCheck.isSelected();
    }
    
    /**
     * Gets the expedite collection chance.
     * @return The chance (0-100), defaults to 50 if invalid
     */
    public int getExpediteCollectionChance() {
        if (!expediteCollectionCheck.isSelected() || expediteChanceInput.getText().isEmpty()) {
            return 50;
        }
        
        try {
            int chance = Integer.parseInt(expediteChanceInput.getText());
            return Math.max(0, Math.min(100, chance)); // Clamp to 0-100
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Convenience helper to show the window and block until closed       */
    /* ------------------------------------------------------------------ */
    public static ScriptOptions showAndWait(JorkHunter script, HuntingConfig config) {
        ScriptOptions pane = new ScriptOptions(script, config);
        Scene scene        = new Scene(pane);
        Stage stage        = new Stage();
        stage.setTitle("JorkHunter – Options");
        stage.setScene(scene);
        stage.setMinWidth(380);
        stage.setMinHeight(400);
        stage.setWidth(400);
        stage.setHeight(420);
        stage.setResizable(false);  // Prevent resizing for consistent appearance
        stage.showAndWait();
        return pane;
    }
} 