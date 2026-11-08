package net.runelite.client.plugins.microbot.ascript;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sidebar editor for the aScript module chain.
 * <p>
 * The dropdown adds a module tag to the comma-separated field; the list is a parsed preview.
 * Save active persists the canonical enum tags (plus the enable/loop toggles) in the active
 * RuneLite profile, while Save as/Load/Delete manage a named library of step-only presets in
 * {@link ChainPresetStore}. Manual editing of the field is supported, so a user can reorder or
 * paste a chain without adding one dropdown entry at a time.
 */
public class AScriptChainPanel extends PluginPanel {

    private static final String CHAIN_MODULES = "chainModules";
    private static final String CHAIN_ENABLED = "chainEnabled";
    private static final String CHAIN_LOOP = "chainLoop";
    private static final Color OK_COLOR = new Color(0, 150, 0);
    private static final Color ERROR_COLOR = new Color(190, 45, 45);

    private final AScriptConfig config;
    private final ConfigManager configManager;
    private final JComboBox<ScriptType> moduleBox;
    private final JTextField chainField = new JTextField();
    private final DefaultListModel<ModuleChain.Step> chainModel = new DefaultListModel<>();
    private final JList<ModuleChain.Step> chainList = new JList<>(chainModel);
    private final JCheckBox enabledBox = new JCheckBox("Enable chain");
    private final JCheckBox loopBox = new JCheckBox("Loop at the end");
    private final JTextField presetNameField = new JTextField();
    private final JComboBox<String> presetBox = new JComboBox<>();
    private final JLabel status = new JLabel(" ");
    private final Map<String, String> presets = new LinkedHashMap<>();
    private boolean loading;

    AScriptChainPanel(AScriptConfig config, ConfigManager configManager) {
        this.config = config;
        this.configManager = configManager;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel("aScript Module Chain");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(title);
        add(Box.createRigidArea(new Dimension(0, 8)));

        List<ScriptType> modules = Arrays.stream(ScriptType.values())
                .filter(type -> type != ScriptType.IDLE)
                .collect(Collectors.toList());
        moduleBox = new JComboBox<>(modules.toArray(new ScriptType[0]));
        moduleBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        moduleBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, moduleBox.getPreferredSize().height));
        add(moduleBox);
        add(Box.createRigidArea(new Dimension(0, 4)));

        JButton addButton = new JButton("Add selected module");
        addButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        addButton.addActionListener(e -> addSelectedModule());
        add(addButton);
        add(Box.createRigidArea(new Dimension(0, 8)));

        JLabel fieldLabel = new JLabel("Steps (comma/semicolon separated; overrides in parentheses)");
        fieldLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(fieldLabel);
        chainField.setAlignmentX(Component.LEFT_ALIGNMENT);
        chainField.setMaximumSize(new Dimension(Integer.MAX_VALUE, chainField.getPreferredSize().height));
        chainField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updatePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updatePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updatePreview();
            }
        });
        add(chainField);
        add(Box.createRigidArea(new Dimension(0, 6)));

        chainList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chainList.setVisibleRowCount(6);
        JScrollPane scroll = new JScrollPane(chainList);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 110));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        add(scroll);
        add(Box.createRigidArea(new Dimension(0, 4)));

        JButton removeButton = new JButton("Remove selected");
        removeButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        removeButton.addActionListener(e -> removeSelectedModule());
        add(removeButton);
        add(Box.createRigidArea(new Dimension(0, 8)));

        JLabel presetsLabel = new JLabel("Saved chains");
        presetsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(presetsLabel);
        presetNameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        presetNameField.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                presetNameField.getPreferredSize().height));
        presetNameField.setToolTipText("Name for the current chain steps");
        add(presetNameField);
        add(Box.createRigidArea(new Dimension(0, 4)));
        presetBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        presetBox.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                presetBox.getPreferredSize().height));
        presetBox.setToolTipText("Select a saved chain, then Load");
        add(presetBox);
        add(Box.createRigidArea(new Dimension(0, 4)));

        JPanel presetActions = new JPanel(new GridLayout(1, 3, 4, 0));
        presetActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        presetActions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        JButton savePresetButton = new JButton("Save as");
        savePresetButton.addActionListener(e -> savePreset());
        JButton loadPresetButton = new JButton("Load");
        loadPresetButton.addActionListener(e -> loadPreset());
        JButton deletePresetButton = new JButton("Delete");
        deletePresetButton.addActionListener(e -> deletePreset());
        presetActions.add(savePresetButton);
        presetActions.add(loadPresetButton);
        presetActions.add(deletePresetButton);
        add(presetActions);
        add(Box.createRigidArea(new Dimension(0, 8)));

        enabledBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        loopBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(enabledBox);
        add(loopBox);
        add(Box.createRigidArea(new Dimension(0, 8)));

        JPanel actions = new JPanel(new GridLayout(1, 2, 6, 0));
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        JButton saveButton = new JButton("Save active");
        saveButton.addActionListener(e -> save());
        JButton deleteButton = new JButton("Delete active");
        deleteButton.addActionListener(e -> delete());
        actions.add(saveButton);
        actions.add(deleteButton);
        add(actions);
        add(Box.createRigidArea(new Dimension(0, 8)));

        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setForeground(Color.GRAY);
        add(status);
        add(Box.createVerticalGlue());

        reload();
    }

    /** Reload the controls from the active profile's config. */
    public void reload() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reload);
            return;
        }
        loading = true;
        String presetError = null;
        try {
            enabledBox.setSelected(config.chainEnabled());
            loopBox.setSelected(config.chainLoop());
            String spec = config.chainModules() == null ? "" : config.chainModules();
            chainField.setText(spec);
            try {
                presets.clear();
                presets.putAll(ChainPresetStore.parse(config.chainPresets()));
            } catch (IllegalArgumentException ex) {
                presets.clear();
                presetError = ex.getMessage();
            }
            refreshPresetBox(null);
            presetNameField.setText("");
        } finally {
            loading = false;
        }
        updatePreview(false);
        if (presetError != null) {
            setStatus(presetError, ERROR_COLOR);
        }
    }

    private void addSelectedModule() {
        ScriptType selected = (ScriptType) moduleBox.getSelectedItem();
        if (selected == null) {
            return;
        }
        String current = chainField.getText().trim();
        chainField.setText(current.isEmpty() ? selected.name() : current + ";" + selected.name());
        chainField.requestFocusInWindow();
    }

    private void removeSelectedModule() {
        int index = chainList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        List<ModuleChain.Step> remaining = new ArrayList<>();
        for (int i = 0; i < chainModel.size(); i++) {
            if (i != index) {
                remaining.add(chainModel.get(i));
            }
        }
        chainField.setText(remaining.stream()
                .map(ModuleChain.Step::toConfigText)
                .collect(Collectors.joining(";")));
    }

    private void updatePreview() {
        updatePreview(true);
    }

    private void updatePreview(boolean dirty) {
        if (loading) {
            return;
        }
        chainModel.clear();
        try {
            ModuleChain chain = ModuleChain.parse(chainField.getText());
            String settingsError = ChainSettings.validate(chain);
            if (settingsError != null) {
                setStatus(settingsError, ERROR_COLOR);
                return;
            }
            for (ModuleChain.Step step : chain.entries()) {
                chainModel.addElement(step);
            }
            if (chain.isEmpty()) {
                setStatus("No modules configured", Color.GRAY);
            } else {
                setStatus(chain.size() + " module(s)" + (dirty ? " — not saved yet" : ""),
                        Color.GRAY);
            }
        } catch (IllegalArgumentException ex) {
            setStatus(ex.getMessage(), ERROR_COLOR);
        }
    }

    /** Validate the editor and return its canonical chain text, or {@code null} on error. */
    private String canonicalChain() {
        try {
            ModuleChain chain = ModuleChain.parse(chainField.getText());
            String settingsError = ChainSettings.validate(chain);
            if (settingsError != null) {
                setStatus(settingsError, ERROR_COLOR);
                return null;
            }
            return chain.entries().stream()
                    .map(ModuleChain.Step::toConfigText)
                    .collect(Collectors.joining(";"));
        } catch (IllegalArgumentException ex) {
            setStatus(ex.getMessage(), ERROR_COLOR);
            return null;
        }
    }

    /** Save the editor under a name in this profile's saved-chain library. */
    private void savePreset() {
        String canonical = canonicalChain();
        if (canonical == null) {
            return;
        }
        if (canonical.isEmpty()) {
            setStatus("Add at least one module before saving a named chain", ERROR_COLOR);
            return;
        }
        String name = presetNameField.getText() == null ? "" : presetNameField.getText().trim();
        if (name.isEmpty()) {
            setStatus("Type a name for the saved chain", ERROR_COLOR);
            return;
        }
        boolean replaced = presets.containsKey(name);
        presets.put(name, canonical);
        if (!persistPresets()) {
            return;
        }
        refreshPresetBox(name);
        presetNameField.setText(name);
        setStatus((replaced ? "Updated" : "Saved") + " chain preset '" + name + "'", OK_COLOR);
    }

    /** Load the selected named chain into the editor; Save active still publishes it. */
    private void loadPreset() {
        String name = selectedPreset();
        if (name == null) {
            setStatus("Select a saved chain first", ERROR_COLOR);
            return;
        }
        String spec = presets.get(name);
        if (spec == null) {
            setStatus("Saved chain '" + name + "' is no longer available", ERROR_COLOR);
            refreshPresetBox(null);
            return;
        }
        chainField.setText(spec);
        updatePreview();
        presetNameField.setText(name);
        setStatus("Loaded '" + name + "' — click Save active to run it", OK_COLOR);
    }

    /** Delete only the selected preset; the active chain is left untouched. */
    private void deletePreset() {
        String name = selectedPreset();
        if (name == null) {
            setStatus("Select a saved chain first", ERROR_COLOR);
            return;
        }
        presets.remove(name);
        if (!persistPresets()) {
            return;
        }
        refreshPresetBox(null);
        setStatus("Deleted chain preset '" + name + "'", OK_COLOR);
    }

    private String selectedPreset() {
        Object selected = presetBox.getSelectedItem();
        return selected == null ? null : selected.toString();
    }

    private void refreshPresetBox(String select) {
        presetBox.removeAllItems();
        for (String name : presets.keySet()) {
            presetBox.addItem(name);
        }
        if (select != null) {
            presetBox.setSelectedItem(select);
        } else if (presetBox.getItemCount() > 0) {
            presetBox.setSelectedIndex(0);
        }
    }

    private boolean persistPresets() {
        try {
            if (presets.isEmpty()) {
                configManager.unsetConfiguration(AScriptConfig.GROUP, ChainPresetStore.CONFIG_KEY);
            } else {
                configManager.setConfiguration(AScriptConfig.GROUP, ChainPresetStore.CONFIG_KEY,
                        ChainPresetStore.serialize(presets));
            }
            return true;
        } catch (RuntimeException ex) {
            setStatus("Could not persist saved chains: " + ex.getMessage(), ERROR_COLOR);
            return false;
        }
    }

    private void save() {
        String canonical = canonicalChain();
        if (canonical == null) {
            return;
        }
        configManager.setConfiguration(AScriptConfig.GROUP, CHAIN_MODULES, canonical);
        configManager.setConfiguration(AScriptConfig.GROUP, CHAIN_ENABLED, enabledBox.isSelected());
        configManager.setConfiguration(AScriptConfig.GROUP, CHAIN_LOOP, loopBox.isSelected());
        chainField.setText(canonical);
        setStatus("Saved active chain (" + ModuleChain.parse(canonical).size()
                + " module(s)) for this profile", OK_COLOR);
    }

    private void delete() {
        configManager.unsetConfiguration(AScriptConfig.GROUP, CHAIN_MODULES);
        configManager.setConfiguration(AScriptConfig.GROUP, CHAIN_ENABLED, false);
        configManager.setConfiguration(AScriptConfig.GROUP, CHAIN_LOOP, false);
        loading = true;
        try {
            enabledBox.setSelected(false);
            loopBox.setSelected(false);
            chainField.setText("");
            chainModel.clear();
        } finally {
            loading = false;
        }
        setStatus("Chain deleted for this profile", OK_COLOR);
    }

    private void setStatus(String text, Color color) {
        status.setText(text == null || text.isEmpty() ? " " : text);
        status.setForeground(color);
    }
}
