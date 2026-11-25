package net.runelite.client.plugins.microbot.ascript;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Permanent product guardrail: aScript modules must never drop inventory items.
 *
 * <p>The test scans production source (not docs and not tests) after removing comments, so a
 * future implementation cannot reintroduce a drop behind a different call site. Deposits,
 * item consumption, XP/count "drop" tracking and ground-item looting are not affected.</p>
 */
public class AScriptNoDropGuardTest {

    private static final Path ROOT = Path.of("src/main/java/net/runelite/client/plugins/microbot/ascript");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "AScriptDrop|dropAtSlot\\s*\\(|dropRoute\\s*\\(|Rs2Inventory\\s*\\.\\s*drop"
                    + "|MiningAction\\s*\\.\\s*DROP|WoodcuttingAction\\s*\\.\\s*DROP"
                    + "|DROP_RAW|COOK_AND_DROP|mlmDropGems");

    @Test
    public void productionSourceContainsNoItemDropApi() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList())) {
                // The migration intentionally names the removed values so it can rewrite them
                // before ConfigManager reads the enum; it contains no drop call or API.
                if (file.getFileName().toString().equals("AScriptConfigMigration.java")) {
                    continue;
                }
                String source = stripComments(Files.readString(file));
                Matcher matcher = FORBIDDEN.matcher(source);
                while (matcher.find()) {
                    violations.add(file + ": " + matcher.group());
                }
            }
        }
        assertTrue("aScript must never drop inventory items; remove these call sites:\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }
}
