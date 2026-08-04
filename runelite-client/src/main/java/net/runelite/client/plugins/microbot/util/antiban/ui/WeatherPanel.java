package net.runelite.client.plugins.microbot.util.antiban.ui;

import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.WeatherModulation;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;

/**
 * UI panel for Weather-Based Anti-Ban Modulation.
 */
public class WeatherPanel extends JPanel {

    private final JCheckBox weatherEnabled = new JCheckBox("Enable Weather Modulation");

    private final JButton randomLocationButton = new JButton("🎲 Random");
    private final JButton manualCoordsButton = new JButton("✏️ Manual...");
    private final JButton refreshButton = new JButton("🔄 Refresh");

    private final JLabel cityLabel = new JLabel();
    private final JLabel tempLabel = new JLabel();
    private final JLabel precipLabel = new JLabel();
    private final JLabel gustLabel = new JLabel();
    private final JLabel weatherLabel = new JLabel();
    private final JLabel combinedSpeedLabel = new JLabel();
    private final JLabel breakFactorLabel = new JLabel();
    private final JLabel microBreakOffsetLabel = new JLabel();
    private final JLabel mistakeOffsetLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");

    public WeatherPanel() {
        setLayout(new GridBagLayout());
        setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);

        weatherEnabled.setToolTipText("Modulate anti-ban timing based on real weather");
        randomLocationButton.setToolTipText("Random land location with reverse geocode");
        manualCoordsButton.setToolTipText("Enter exact lat/lon coordinates");
        refreshButton.setToolTipText("Fetch latest weather data");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;

        // ── Enable toggle ──
        add(weatherEnabled, gbc);

        // ── Location ──
        gbc.insets = new Insets(10, 5, 2, 5);
        cityLabel.setFont(cityLabel.getFont().deriveFont(Font.BOLD));
        add(cityLabel, gbc);

        // ── All buttons in one panel so they wrap together ──
        gbc.insets = new Insets(5, 5, 5, 5);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
        buttonPanel.add(randomLocationButton);
        buttonPanel.add(manualCoordsButton);
        buttonPanel.add(refreshButton);
        add(buttonPanel, gbc);

        // ── Info section ── fill horizontally so labels wrap
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.insets = new Insets(12, 5, 2, 5);
        add(new JLabel("── Current Weather ──"), gbc);

        gbc.insets = new Insets(2, 5, 2, 5);
        add(tempLabel, gbc);
        add(precipLabel, gbc);
        add(gustLabel, gbc);
        add(weatherLabel, gbc);

        gbc.insets = new Insets(12, 5, 2, 5);
        add(new JLabel("── Modulation ──"), gbc);

        // Each factor with a small gap below
        gbc.insets = new Insets(2, 5, 2, 5);
        add(combinedSpeedLabel, gbc);
        gbc.insets = new Insets(2, 5, 6, 5);
        add(breakFactorLabel, gbc);
        gbc.insets = new Insets(6, 5, 2, 5);
        add(microBreakOffsetLabel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5);
        add(mistakeOffsetLabel, gbc);

        // ── Status ──
        gbc.insets = new Insets(12, 5, 5, 5);
        statusLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        add(statusLabel, gbc);

        setupActionListeners();
    }

    private void setupActionListeners() {
        weatherEnabled.addActionListener(e -> {
            Rs2AntibanSettings.weatherEnabled = weatherEnabled.isSelected();
            if (weatherEnabled.isSelected()) {
                WeatherModulation.initFromSettings();
                WeatherModulation.ensureFresh();
                WeatherModulation.persistToSettings();
                Rs2AntibanSettings.saveToProfile();
            }
            updateValues();
        });

        randomLocationButton.addActionListener(e -> {
            randomLocationButton.setEnabled(false);
            randomLocationButton.setText("⏳");
            statusLabel.setText("🌍 Picking random location...");
            statusLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    WeatherModulation.assignRandomLocation();
                    return null;
                }
                @Override
                protected void done() {
                    WeatherModulation.persistToSettings();
                    Rs2AntibanSettings.saveToProfile();
                    WeatherModulation.refreshWeather();
                    updateValues();
                    randomLocationButton.setEnabled(true);
                    randomLocationButton.setText("🎲 Random");
                    statusLabel.setText("✅ " + WeatherModulation.getCityName());
                    statusLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
                }
            }.execute();
        });

        manualCoordsButton.addActionListener(e -> showManualCoordsDialog());
        refreshButton.addActionListener(e -> {
            WeatherModulation.refreshWeather();
            updateValues();
            statusLabel.setText("✅ Refreshed");
            statusLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        });
    }

    private void showManualCoordsDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.WEST;

        JTextField latField = new JTextField(String.format("%.4f", WeatherModulation.getLatitude()), 10);
        JTextField lonField = new JTextField(String.format("%.4f", WeatherModulation.getLongitude()), 10);

        c.gridx = 0; c.gridy = 0;
        panel.add(new JLabel("Latitude (-90 to 90):"), c);
        c.gridx = 1;
        panel.add(latField, c);

        c.gridx = 0; c.gridy = 1;
        panel.add(new JLabel("Longitude (-180 to 180):"), c);
        c.gridx = 1;
        panel.add(lonField, c);

        int result = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                panel, "Set Manual Coordinates",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            try {
                double lat = Double.parseDouble(latField.getText().trim());
                double lon = Double.parseDouble(lonField.getText().trim());
                if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                    statusLabel.setText("❌ Invalid range: lat [-90,90] lon [-180,180]");
                    statusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
                    return;
                }
                WeatherModulation.setLocation("Custom", lat, lon);
                WeatherModulation.persistToSettings();
                Rs2AntibanSettings.saveToProfile();
                WeatherModulation.refreshWeather();
                updateValues();
                statusLabel.setText("✅ Custom coords applied");
                statusLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
            } catch (NumberFormatException ex) {
                statusLabel.setText("❌ Invalid lat/lon format");
                statusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
            }
        }
    }

    public void updateValues() {
        weatherEnabled.setSelected(Rs2AntibanSettings.weatherEnabled);

        String city = WeatherModulation.getCityName();
        double lat = WeatherModulation.getLatitude();
        double lon = WeatherModulation.getLongitude();
        cityLabel.setText("<html><b>" + escapeHtml(city) + "</b>"
                + String.format("<br>%.4f, %.4f", lat, lon) + "</html>");

        if (WeatherModulation.isFetchFailed()) {
            statusLabel.setText("⚠️ Failed, using last values");
            statusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        } else if (WeatherModulation.getCacheAgeMs() < 35 * 60 * 1000L) {
            statusLabel.setText("✅ Fresh");
            statusLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        } else {
            statusLabel.setText("⏳ No data — enable or Refresh");
            statusLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        }

        tempLabel.setText("Temperature: " + String.format("%.1f", WeatherModulation.getCurrentTempCelsius()) + "°C");
        precipLabel.setText("Precipitation: " + String.format("%.1f", WeatherModulation.getCurrentPrecipitationMm()) + " mm");
        gustLabel.setText("Wind gusts: " + String.format("%.0f", WeatherModulation.getCurrentWindGustKmh()) + " km/h");
        weatherLabel.setText("Conditions: " + WeatherModulation.getWeatherDescription());

        double csf = WeatherModulation.combinedSpeedFactor();
        double sf = WeatherModulation.speedFactor();
        double gf = WeatherModulation.windGustFactor();
        double mf = WeatherModulation.weatherMoodFactor();
        combinedSpeedLabel.setText(String.format(
                "<html>Speed: ×%.3f<br>(temp ×%.3f × gust ×%.3f × mood ×%.3f)</html>",
                csf, sf, gf, mf));
        breakFactorLabel.setText("Break: ×" + String.format("%.3f", WeatherModulation.breakLengthFactor()));
        microBreakOffsetLabel.setText("Micro-break: +" + String.format("%.1f",
                WeatherModulation.microBreakChanceOffset() * 100.0) + "%");
        mistakeOffsetLabel.setText("Mistake: +" + String.format("%.1f",
                WeatherModulation.mistakeProbabilityOffset() * 100.0) + "%");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
