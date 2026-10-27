package ret.tawny.controlbans.services;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import ret.tawny.controlbans.model.PunishmentType;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the escalation.yml preset parser and category filtering.
 * Pure Bukkit-configuration parsing — no server required.
 */
class EscalationServiceTest {

    private List<String> warnings = new ArrayList<>();

    private Consumer<String> sink() {
        return warnings::add;
    }

    private static ConfigurationSection presetsSection(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml)).getConfigurationSection("presets");
    }

    private void clearWarnings() {
        warnings = new ArrayList<>();
    }

    // =========================================================================
    //  Valid preset parsing
    // =========================================================================

    @Test
    void testValidPresetParsesFully() {
        clearWarnings();
        String yaml = """
                presets:
                  racism:
                    display-name: "<dark_red>Racism"
                    description: "Slurs"
                    icon: "RED_CANDLE"
                    permission: ""
                    reason: "Hate speech"
                    targets: [text, voice]
                    actions:
                      - "MUTE"
                      - "VOICEMUTE"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());

        assertEquals(1, presets.size());
        assertTrue(warnings.isEmpty(), "no warnings expected: " + warnings);
        EscalationService.Preset preset = presets.get(0);
        assertEquals("racism", preset.id());
        assertEquals("<dark_red>Racism", preset.displayName());
        assertEquals("Slurs", preset.description());
        assertEquals(Material.RED_CANDLE, preset.icon());
        assertEquals("", preset.permission());
        assertEquals("Hate speech", preset.reason());
        assertTrue(preset.targets().contains(EscalationService.Category.TEXT));
        assertTrue(preset.targets().contains(EscalationService.Category.VOICE));
        assertEquals(2, preset.actions().size());

        EscalationService.ActionSpec first = preset.actions().get(0);
        assertEquals(PunishmentType.MUTE, first.type());
        assertTrue(first.permanent());
        assertEquals(-1L, first.durationSeconds());

        EscalationService.ActionSpec second = preset.actions().get(1);
        assertEquals(PunishmentType.VOICEMUTE, second.type());
        assertTrue(second.permanent());
    }

    @Test
    void testTemporaryActionWithDurationParses() {
        clearWarnings();
        String yaml = """
                presets:
                  spam:
                    display-name: "Spam"
                    targets: [text, voice]
                    actions:
                      - "TEMPMUTE 1h"
                      - "TEMPVOICEMUTE 90m"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());

        assertEquals(1, presets.size());
        assertTrue(warnings.isEmpty(), "no warnings expected: " + warnings);
        EscalationService.ActionSpec mute = presets.get(0).actions().get(0);
        assertEquals(PunishmentType.TEMPMUTE, mute.type());
        assertFalse(mute.permanent());
        assertEquals(3600L, mute.durationSeconds());
        EscalationService.ActionSpec voice = presets.get(0).actions().get(1);
        assertEquals(PunishmentType.TEMPVOICEMUTE, voice.type());
        assertEquals(5400L, voice.durationSeconds());
    }

    @Test
    void testDefaultsAppliedForMissingOptionalFields() {
        clearWarnings();
        String yaml = """
                presets:
                  minimal:
                    targets: [text]
                    actions:
                      - "KICK"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());

        assertEquals(1, presets.size());
        assertTrue(warnings.isEmpty(), "no warnings expected: " + warnings);
        EscalationService.Preset preset = presets.get(0);
        assertEquals("Minimal", preset.displayName()); // capitalized id
        assertEquals(Material.PAPER, preset.icon());
        assertEquals("", preset.permission());
        assertEquals("", preset.reason());
    }

    @Test
    void testTargetBothExpandsToTextAndVoice() {
        clearWarnings();
        String yaml = """
                presets:
                  both-target:
                    targets: [both]
                    actions:
                      - "MUTE"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());
        assertEquals(1, presets.size());
        assertTrue(presets.get(0).targets().contains(EscalationService.Category.TEXT));
        assertTrue(presets.get(0).targets().contains(EscalationService.Category.VOICE));
    }

    // =========================================================================
    //  Invalid actions are skipped with warnings
    // =========================================================================

    @Test
    void testUnknownTypeSkippedWithWarning() {
        clearWarnings();
        String yaml = """
                presets:
                  broken:
                    targets: [text]
                    actions:
                      - "FLYHACK 5d"
                      - "TEMPBAN 7d"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());

        assertEquals(1, presets.size());
        assertEquals(1, presets.get(0).actions().size());
        assertEquals(PunishmentType.TEMPBAN, presets.get(0).actions().get(0).type());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("broken"));
        assertTrue(warnings.get(0).contains("FLYHACK"));
        assertTrue(warnings.get(0).contains("skipped"));
    }

    @Test
    void testTempTypeWithoutDurationSkippedWithWarning() {
        clearWarnings();
        String yaml = """
                presets:
                  nodur:
                    targets: [text]
                    actions:
                      - "TEMPMUTE"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());

        assertTrue(presets.isEmpty(), "preset with zero valid actions must be skipped");
        assertEquals(2, warnings.size()); // action warning + "every action invalid" preset warning
        assertTrue(warnings.get(0).contains("nodur"));
        assertTrue(warnings.get(0).contains("without a duration"));
    }

    @Test
    void testPermanentTypeWithDurationSkippedWithWarning() {
        clearWarnings();
        String yaml = """
                presets:
                  wrongdur:
                    targets: [text]
                    actions:
                      - "BAN 7d"
                      - "MUTE"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());

        assertEquals(1, presets.size());
        assertEquals(1, presets.get(0).actions().size());
        assertEquals(PunishmentType.MUTE, presets.get(0).actions().get(0).type());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("wrongdur"));
        assertTrue(warnings.get(0).contains("BAN 7d"));
    }

    @Test
    void testGarbageDurationSkippedWithWarning() {
        clearWarnings();
        String yaml = """
                presets:
                  gabage:
                    targets: [text]
                    actions:
                      - "TEMPBAN blah"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());
        assertTrue(presets.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("could not parse duration")));
    }

    @Test
    void testPresetWithOnlyInvalidActionsIsSkipped() {
        clearWarnings();
        String yaml = """
                presets:
                  allbad:
                    targets: [text]
                    actions:
                      - "NOPE"
                      - "TEMPBAN"
                      - "KICK 5d"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());
        assertTrue(presets.isEmpty());
        assertEquals(4, warnings.size()); // 3 action warnings + "every action invalid" preset warning
    }

    @Test
    void testUnknownIconFallsBackToPaperWithWarning() {
        clearWarnings();
        String yaml = """
                presets:
                  badicon:
                    targets: [text]
                    icon: "NOT_A_MATERIAL"
                    actions:
                      - "KICK"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());
        assertEquals(1, presets.size());
        assertEquals(Material.PAPER, presets.get(0).icon());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("unknown icon material")));
    }

    @Test
    void testUnknownTargetIsSkippedWithWarning() {
        clearWarnings();
        String yaml = """
                presets:
                  badtarget:
                    targets: [bananas]
                    actions:
                      - "KICK"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());
        assertEquals(1, presets.size());
        assertTrue(presets.get(0).targets().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("unknown target")));
    }

    @Test
    void testPresetWithoutActionsIsSkippedWithWarning() {
        clearWarnings();
        String yaml = """
                presets:
                  noactions:
                    targets: [text]
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());
        assertTrue(presets.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no 'actions'")));
    }

    @Test
    void testMissingPresetsSectionYieldsEmptyListWithWarning() {
        clearWarnings();
        List<EscalationService.Preset> presets = EscalationService.parsePresets(null, sink());
        assertTrue(presets.isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("missing the 'presets' section"));
    }

    @Test
    void testJunkEntryUnderPresetsIsSkipped() {
        clearWarnings();
        String yaml = """
                presets:
                  not-a-section: "hello"
                  good:
                    targets: [text]
                    actions:
                      - "KICK"
                """;
        List<EscalationService.Preset> presets = EscalationService.parsePresets(presetsSection(yaml), sink());
        assertEquals(1, presets.size());
        assertEquals("good", presets.get(0).id());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("not-a-section")));
    }

    // =========================================================================
    //  Category filtering
    // =========================================================================

    private static final EscalationService.ActionSpec MUTE = new EscalationService.ActionSpec(PunishmentType.MUTE, -1L, true);
    private static final EscalationService.ActionSpec TEMPBAN = new EscalationService.ActionSpec(PunishmentType.TEMPBAN, 604800L, false);
    private static final EscalationService.ActionSpec WARN = new EscalationService.ActionSpec(PunishmentType.WARN, -1L, true);
    private static final EscalationService.ActionSpec KICK = new EscalationService.ActionSpec(PunishmentType.KICK, -1L, true);
    private static final EscalationService.ActionSpec VOICEMUTE = new EscalationService.ActionSpec(PunishmentType.VOICEMUTE, -1L, true);
    private static final EscalationService.ActionSpec TEMPVOICEMUTE = new EscalationService.ActionSpec(PunishmentType.TEMPVOICEMUTE, 3600L, false);

    @Test
    void testVoiceCategoryRunsOnlyVoiceActions() {
        assertTrue(EscalationService.matchesCategory(VOICEMUTE, EscalationService.Category.VOICE));
        assertTrue(EscalationService.matchesCategory(TEMPVOICEMUTE, EscalationService.Category.VOICE));
        assertFalse(EscalationService.matchesCategory(MUTE, EscalationService.Category.VOICE));
        assertFalse(EscalationService.matchesCategory(TEMPBAN, EscalationService.Category.VOICE));
        assertFalse(EscalationService.matchesCategory(WARN, EscalationService.Category.VOICE));
        assertFalse(EscalationService.matchesCategory(KICK, EscalationService.Category.VOICE));
    }

    @Test
    void testTextCategoryRunsEverythingExceptVoiceActions() {
        assertTrue(EscalationService.matchesCategory(MUTE, EscalationService.Category.TEXT));
        assertTrue(EscalationService.matchesCategory(TEMPBAN, EscalationService.Category.TEXT));
        assertTrue(EscalationService.matchesCategory(WARN, EscalationService.Category.TEXT));
        assertTrue(EscalationService.matchesCategory(KICK, EscalationService.Category.TEXT));
        assertFalse(EscalationService.matchesCategory(VOICEMUTE, EscalationService.Category.TEXT));
        assertFalse(EscalationService.matchesCategory(TEMPVOICEMUTE, EscalationService.Category.TEXT));
    }

    @Test
    void testBothCategoryRunsAllActions() {
        assertTrue(EscalationService.matchesCategory(MUTE, EscalationService.Category.BOTH));
        assertTrue(EscalationService.matchesCategory(TEMPBAN, EscalationService.Category.BOTH));
        assertTrue(EscalationService.matchesCategory(WARN, EscalationService.Category.BOTH));
        assertTrue(EscalationService.matchesCategory(KICK, EscalationService.Category.BOTH));
        assertTrue(EscalationService.matchesCategory(VOICEMUTE, EscalationService.Category.BOTH));
        assertTrue(EscalationService.matchesCategory(TEMPVOICEMUTE, EscalationService.Category.BOTH));
    }

    @Test
    void testRacismExampleFiltersByCategory() {
        clearWarnings();
        String yaml = """
                presets:
                  racism:
                    display-name: "Racism / Hate Speech"
                    reason: "Hate speech"
                    targets: [text, voice]
                    actions:
                      - "MUTE"
                      - "VOICEMUTE"
                """;
        EscalationService.Preset preset = EscalationService.parsePresets(presetsSection(yaml), sink()).get(0);

        long voiceActions = preset.actions().stream().filter(a -> EscalationService.matchesCategory(a, EscalationService.Category.VOICE)).count();
        long textActions = preset.actions().stream().filter(a -> EscalationService.matchesCategory(a, EscalationService.Category.TEXT)).count();
        long bothActions = preset.actions().stream().filter(a -> EscalationService.matchesCategory(a, EscalationService.Category.BOTH)).count();

        assertEquals(1, voiceActions);
        assertEquals(1, textActions);
        assertEquals(2, bothActions);
    }
}
