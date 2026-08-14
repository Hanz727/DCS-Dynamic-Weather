package com.marlan.weatherupdate.service.destruction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes dead units from the mission text by unitId, across every coalition
 * and category. A group whose last unit is removed is removed whole (an empty
 * group is invalid to the ME). Surviving entries are re-indexed [1..n].
 */
final class UnitRemover {
    private static final Pattern UNIT_ID = Pattern.compile("\\[\"unitId\"\\]\\s*=\\s*(\\d+)");
    private static final Pattern NAME = Pattern.compile("\\[\"name\"\\]\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final String[] COALITIONS = {"red", "blue", "neutrals"};
    private static final String[] CATEGORIES = {"vehicle", "static", "ship", "plane", "helicopter"};

    /** A unit actually excised this run (for the changelog). */
    record Removed(long unitId, String unitName, String groupName, boolean wholeGroupGone) {
    }

    record Result(String text, List<Removed> removed) {
    }

    private UnitRemover() {
    }

    static Result remove(String mission, Set<Long> ids) {
        List<Removed> removed = new ArrayList<>();
        if (ids.isEmpty()) return new Result(mission, removed);

        // Replacements are computed against the ORIGINAL text per category
        // group-table (spans never overlap), then applied back-to-front so
        // earlier offsets stay valid.
        record Replacement(int start, int end, String content) {
        }
        List<Replacement> replacements = new ArrayList<>();

        LuaTable.KeyTable coalition = LuaTable.findKeyTable(mission, "coalition", 0, mission.length());
        if (coalition == null) return new Result(mission, removed);

        for (String side : COALITIONS) {
            LuaTable.KeyTable sideTable = LuaTable.findKeyTable(
                    mission, side, coalition.contentStart(), coalition.contentEnd());
            if (sideTable == null) continue;
            LuaTable.KeyTable countryTable = LuaTable.findKeyTable(
                    mission, "country", sideTable.contentStart(), sideTable.contentEnd());
            if (countryTable == null) continue;

            for (LuaTable.Entry country : LuaTable.entries(
                    mission, countryTable.contentStart(), countryTable.contentEnd())) {
                int countryStart = country.start();
                int countryEnd = country.end();
                for (String category : CATEGORIES) {
                    LuaTable.KeyTable catTable = LuaTable.findKeyTable(
                            mission, category, countryStart, countryEnd);
                    if (catTable == null) continue;
                    LuaTable.KeyTable groupTable = LuaTable.findKeyTable(
                            mission, "group", catTable.contentStart(), catTable.contentEnd());
                    if (groupTable == null) continue;

                    List<LuaTable.Entry> groups = LuaTable.entries(
                            mission, groupTable.contentStart(), groupTable.contentEnd());
                    List<String> keptGroups = new ArrayList<>();
                    boolean changed = false;

                    for (LuaTable.Entry group : groups) {
                        String rebuilt = rebuildGroup(mission, group, ids, removed);
                        if (rebuilt == null) {
                            changed = true; // whole group dropped
                        } else {
                            if (!rebuilt.equals(group.value())) changed = true;
                            keptGroups.add(rebuilt);
                        }
                    }
                    if (changed) {
                        replacements.add(new Replacement(
                                groupTable.contentStart(), groupTable.contentEnd(),
                                LuaTable.emitEntries(keptGroups, depthOf(mission, groupTable.contentStart()) + 1)));
                    }
                }
            }
        }

        replacements.sort((a, b) -> Integer.compare(b.start(), a.start()));
        StringBuilder sb = new StringBuilder(mission);
        for (Replacement r : replacements) {
            sb.replace(r.start(), r.end(), r.content());
        }
        return new Result(sb.toString(), removed);
    }

    /** The group's rebuilt value with dead units excised; the original value
     *  when untouched; null when every unit is gone (drop the group). */
    private static String rebuildGroup(String mission, LuaTable.Entry group,
                                       Set<Long> ids, List<Removed> removed) {
        int groupStart = group.start();
        int groupEnd = group.end();
        LuaTable.KeyTable unitsTable = LuaTable.findKeyTable(mission, "units", groupStart, groupEnd);
        if (unitsTable == null) return group.value();

        String groupName = firstName(mission, unitsTable.contentEnd(), groupEnd, groupStart);
        List<LuaTable.Entry> units = LuaTable.entries(
                mission, unitsTable.contentStart(), unitsTable.contentEnd());
        List<String> kept = new ArrayList<>();
        List<Removed> dropped = new ArrayList<>();
        for (LuaTable.Entry unit : units) {
            String idText = LuaTable.extract(unit.value(), UNIT_ID);
            Long unitId = idText == null ? null : Long.parseLong(idText);
            if (unitId != null && ids.contains(unitId)) {
                String unitName = LuaTable.extract(unit.value(), NAME);
                dropped.add(new Removed(unitId, unitName == null ? "?" : unitName,
                        groupName, false));
            } else {
                kept.add(unit.value());
            }
        }
        if (dropped.isEmpty()) return group.value();
        if (kept.isEmpty()) {
            for (Removed r : dropped) {
                removed.add(new Removed(r.unitId(), r.unitName(), r.groupName(), true));
            }
            return null;
        }
        removed.addAll(dropped);
        boolean leadRemoved = wasLeadDropped(units, kept);
        // Splice the rebuilt units content into the group's value text. The
        // value is the group's `{...}` table, so its absolute offset is the
        // first brace after the entry's `[n] = ` header.
        int valueStart = mission.indexOf('{', groupStart);
        String value = group.value();
        int vRelStart = unitsTable.contentStart() - valueStart;
        int vRelEnd = unitsTable.contentEnd() - valueStart;
        String units_ = LuaTable.emitEntries(kept, depthOf(mission, unitsTable.contentStart()) + 1);
        String rebuilt = value.substring(0, vRelStart) + units_ + value.substring(vRelEnd);
        if (leadRemoved) {
            // DCS snaps a ground group's FIRST unit onto the route's first
            // waypoint at spawn. With the old lead removed, the next unit
            // would teleport onto the dead lead's spot — re-anchor the route
            // start (and the group's own x/y) onto the NEW lead's position so
            // every survivor spawns exactly where the ME shows it.
            String leadX = LuaTable.extract(kept.get(0), X_FIELD);
            String leadY = LuaTable.extract(kept.get(0), Y_FIELD);
            if (leadX != null && leadY != null) {
                rebuilt = retargetGroupAnchor(rebuilt, leadX, leadY);
            }
        }
        return rebuilt;
    }

    /** True when the original first unit is not the rebuilt first unit. */
    private static boolean wasLeadDropped(List<LuaTable.Entry> original, List<String> kept) {
        return !original.isEmpty() && !kept.isEmpty()
                && !original.get(0).value().equals(kept.get(0));
    }

    private static final Pattern X_FIELD = Pattern.compile("\\[\"x\"\\]\\s*=\\s*([-0-9.e]+)");
    private static final Pattern Y_FIELD = Pattern.compile("\\[\"y\"\\]\\s*=\\s*([-0-9.e]+)");

    /** Rewrites the group's route point 1 x/y and the group-level x/y (the
     *  fields after the units table) to the new lead's position. */
    private static String retargetGroupAnchor(String groupValue, String x, String y) {
        String out = groupValue;
        // Route start: ["route"] → ["points"] → entry [1].
        LuaTable.KeyTable route = LuaTable.findKeyTable(out, "route", 0, out.length());
        if (route != null) {
            LuaTable.KeyTable points = LuaTable.findKeyTable(
                    out, "points", route.contentStart(), route.contentEnd());
            if (points != null) {
                List<LuaTable.Entry> pts = LuaTable.entries(
                        out, points.contentStart(), points.contentEnd());
                if (!pts.isEmpty()) {
                    LuaTable.Entry first = pts.get(0);
                    int pStart = out.indexOf('{', first.start());
                    String pointValue = first.value();
                    String updated = replaceFirstField(
                            replaceFirstField(pointValue, Y_FIELD, "[\"y\"] = " + y),
                            X_FIELD, "[\"x\"] = " + x);
                    out = out.substring(0, pStart) + updated
                            + out.substring(pStart + pointValue.length());
                }
            }
        }
        // Group-level x/y: the first ["y"]/["x"] AFTER the units table close.
        LuaTable.KeyTable unitsTable = LuaTable.findKeyTable(out, "units", 0, out.length());
        if (unitsTable != null) {
            String tail = out.substring(unitsTable.contentEnd());
            tail = replaceFirstField(tail, Y_FIELD, "[\"y\"] = " + y);
            tail = replaceFirstField(tail, X_FIELD, "[\"x\"] = " + x);
            out = out.substring(0, unitsTable.contentEnd()) + tail;
        }
        return out;
    }

    private static String replaceFirstField(String text, Pattern field, String replacement) {
        java.util.regex.Matcher m = field.matcher(text);
        if (!m.find()) return text;
        return text.substring(0, m.start()) + replacement + text.substring(m.end());
    }

    /** The group's own ["name"] — the first name that is NOT inside the units
     *  table (unit names come first in serializer order half the time, so we
     *  look outside the units span, falling back to any name). */
    private static String firstName(String mission, int unitsEnd, int groupEnd, int groupStart) {
        String after = mission.substring(Math.min(unitsEnd, groupEnd), groupEnd);
        String name = LuaTable.extract(after, NAME);
        if (name != null) return name;
        String before = mission.substring(groupStart, Math.max(groupStart, unitsEnd));
        name = LuaTable.extract(before, NAME);
        return name == null ? "?" : name;
    }

    /** Tab depth of the line the offset sits on (for matching indentation). */
    private static int depthOf(String text, int offset) {
        int lineStart = text.lastIndexOf('\n', offset) + 1;
        int tabs = 0;
        while (lineStart + tabs < text.length() && text.charAt(lineStart + tabs) == '\t') tabs++;
        return tabs;
    }
}
