package com.marlan.weatherupdate.service.destruction;

import com.marlan.shared.utilities.Log;
import com.marlan.weatherupdate.service.destruction.model.DestroyedSource;
import com.marlan.weatherupdate.service.destruction.model.DestroyedUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-mission battle-damage changelog: one Discord-pasteable markdown file per
 * mission NAME+VERSION (the _A/_B suffix is stripped, so both halves of a day
 * share one file; a version bump in the name naturally starts a new file).
 * Only the actual diff of a run is appended — a run that changed nothing
 * writes nothing.
 *
 * Location: <working dir>/changelog/<mission base>_changelog.md
 */
final class ChangelogWriter {
    private static final Log log = Log.getInstance();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // A removed-unit bullet as this writer emits it: "- `1212` ...".
    private static final Pattern LOGGED_UNIT = Pattern.compile("(?m)^- `(\\d+)`");
    private static final Pattern LOGGED_ZONES =
            Pattern.compile("\\*\\*Scenery destruction:\\*\\* (\\d+) impact");

    private ChangelogWriter() {
    }

    static void append(String workingDir, String baseMizName, List<UnitRemover.Removed> removed,
                       Map<Long, DestroyedUnit> reportById, int zonesWritten, int zonesBefore,
                       boolean zonesChanged) {
        if (removed.isEmpty() && !zonesChanged) return;

        String base = baseMizName.endsWith(".miz")
                ? baseMizName.substring(0, baseMizName.length() - 4)
                : baseMizName;
        Path dir = Path.of(workingDir, "changelog");
        Path file = dir.resolve(base + "_changelog.md");

        // The changelog is MISSION-level, but the edits are per FILE — the
        // _A/_B copies each get the same state applied once, so the catch-up
        // pass on the other half would re-report identical kills. Dedupe
        // against what this changelog already recorded: unit ids already
        // listed are skipped, and the zone bake is only mentioned when the
        // count differs from the last logged one.
        String existing = "";
        if (Files.exists(file)) {
            try {
                existing = Files.readString(file);
            } catch (IOException ioe) {
                log.error("Could not read changelog for dedupe: " + ioe.getMessage());
            }
        }
        Set<Long> alreadyLogged = new HashSet<>();
        Matcher unitLines = LOGGED_UNIT.matcher(existing);
        while (unitLines.find()) {
            alreadyLogged.add(Long.parseLong(unitLines.group(1)));
        }
        List<UnitRemover.Removed> fresh = removed.stream()
                .filter(r -> !alreadyLogged.contains(r.unitId()))
                .toList();
        int lastLoggedZones = -1;
        Matcher zoneLines = LOGGED_ZONES.matcher(existing);
        while (zoneLines.find()) {
            lastLoggedZones = Integer.parseInt(zoneLines.group(1));
        }
        boolean zoneNews = zonesChanged && zonesWritten != lastLoggedZones;
        if (fresh.isEmpty() && !zoneNews) return; // file synced, nothing NEW happened
        removed = fresh;

        // ASCII only throughout — the file gets opened by whatever tool is at
        // hand (and pasted into Discord), so no em-dashes/arrows to mojibake.
        StringBuilder md = new StringBuilder();
        if (!Files.exists(file)) {
            md.append("# ").append(base).append(" changelog\n");
        }
        md.append("\n## ").append(LocalDateTime.now().format(STAMP));
        md.append(" - ").append(removed.size()).append(" unit(s) removed, ")
                .append(zonesWritten).append(" destruction zone(s)\n");

        if (!removed.isEmpty()) {
            md.append("**Removed from mission:**\n");
            for (UnitRemover.Removed r : removed) {
                md.append("- `").append(r.unitId()).append("` ");
                DestroyedUnit info = reportById.get(r.unitId());
                if (info != null && info.getType() != null && !info.getType().isEmpty()) {
                    md.append("**").append(info.getType()).append("** ");
                }
                md.append('`').append(r.unitName()).append('`');
                md.append(" _(").append(r.groupName());
                if (r.wholeGroupGone()) md.append(", group now empty -> removed whole");
                md.append(")_");
                String evidence = evidenceOf(info);
                if (!evidence.isEmpty()) md.append(" - ").append(evidence);
                md.append('\n');
            }
        }
        if (zoneNews) {
            md.append("**Scenery destruction:** ").append(zonesWritten)
                    .append(" impact zone(s) baked into `").append(ZoneWriter.TRIGGER_COMMENT)
                    .append("`");
            if (zonesBefore != zonesWritten) {
                md.append(" _(was ").append(zonesBefore).append(")_");
            }
            md.append('\n');
        }

        try {
            Files.createDirectories(dir);
            Files.writeString(file, md.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Battle-damage changelog updated: " + file);
        } catch (IOException ioe) {
            log.error("Could not write battle-damage changelog: " + ioe.getMessage());
        }
    }

    /** Short human line from the destroyed report's first source, e.g.
     *  "killed by Dave (VY11) with GBU-31(V)2/B (2026-08-10)". */
    private static String evidenceOf(DestroyedUnit info) {
        if (info == null || info.getSources() == null || info.getSources().isEmpty()) return "";
        DestroyedSource s = info.getSources().get(0);
        StringBuilder sb = new StringBuilder();
        if (s.getKilledBy() != null) {
            sb.append("killed by ").append(s.getKilledBy());
        } else if (s.getDmpi() != null) {
            sb.append("DMPI ").append(s.getDmpi());
            if (s.getBda() != null) sb.append(" (BDA ").append(s.getBda()).append(')');
        }
        if (s.getDate() != null) {
            sb.append(sb.isEmpty() ? "" : " ").append('(').append(s.getDate()).append(')');
        }
        return sb.toString();
    }
}
