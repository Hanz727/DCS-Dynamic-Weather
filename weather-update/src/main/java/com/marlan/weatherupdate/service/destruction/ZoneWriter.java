package com.marlan.weatherupdate.service.destruction;

import com.marlan.weatherupdate.service.destruction.model.WeaponImpact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bakes the impact list into the mission as scenery-destruction zones:
 *
 *   1. triggers.zones: every zone named CVIC_DZONE_* is replaced by one zone
 *      per impact (fresh sequential names, zoneIds above the surviving max),
 *      written `hidden = true` so dozens of generated circles never clutter
 *      the mission maker's ME view. Manually-made zones are never touched.
 *   2. trigrules: the ME-side rule `CVIC_SCENERY_DESTRUCT` (a `1 ONCE`,
 *      MISSION START trigger) gets one a_scenery_destruction_zone action per
 *      zone at 100%. Created at the end of the rule list if missing.
 *   3. trig: the compiled runtime mirror — actions[p] (p = our rule's
 *      position) becomes the concatenated action calls plus the standard
 *      once-trigger self-deregistration; conditions[p]/flag[p] and the
 *      "mission start" event dispatch are created when the rule is new.
 *
 * The exact shapes replicate a DCS-ME-authored reference mission
 * (test_destruction / zone BOMBA) byte-for-byte in structure.
 */
final class ZoneWriter {
    static final String ZONE_PREFIX = "CVIC_DZONE_";
    static final String TRIGGER_COMMENT = "CVIC_SCENERY_DESTRUCT";
    private static final Pattern ZONE_NAME = Pattern.compile("\\[\"name\"\\]\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ZONE_ID = Pattern.compile("\\[\"zoneId\"\\]\\s*=\\s*(\\d+)");

    record Result(String text, int zonesWritten, int zonesBefore, boolean changed) {
    }

    private ZoneWriter() {
    }

    static Result write(String mission, List<WeaponImpact> impacts) {
        // ── 1. zones ────────────────────────────────────────────────────────
        LuaTable.KeyTable triggers = LuaTable.findKeyTable(mission, "triggers", 0, mission.length());
        if (triggers == null) return new Result(mission, 0, 0, false);
        LuaTable.KeyTable zones = LuaTable.findKeyTable(
                mission, "zones", triggers.contentStart(), triggers.contentEnd());
        if (zones == null) return new Result(mission, 0, 0, false);

        List<LuaTable.Entry> zoneEntries = LuaTable.entries(
                mission, zones.contentStart(), zones.contentEnd());
        List<String> kept = new ArrayList<>();
        Set<String> oldCvicShapes = new LinkedHashSet<>();
        long maxZoneId = 99; // DCS starts zone ids at 100
        for (LuaTable.Entry zone : zoneEntries) {
            String name = LuaTable.extract(zone.value(), ZONE_NAME);
            if (name != null && name.startsWith(ZONE_PREFIX)) {
                oldCvicShapes.add(shapeOf(zone.value()));
                continue;
            }
            kept.add(zone.value());
            String id = LuaTable.extract(zone.value(), ZONE_ID);
            if (id != null) maxZoneId = Math.max(maxZoneId, Long.parseLong(id));
        }

        List<Long> newZoneIds = new ArrayList<>();
        Set<String> newShapes = new LinkedHashSet<>();
        int seq = 0;
        for (WeaponImpact impact : impacts) {
            long zoneId = maxZoneId + (++seq);
            newZoneIds.add(zoneId);
            String zone = zoneBody(zoneId, String.format("%s%03d", ZONE_PREFIX, seq), impact);
            kept.add(zone);
            newShapes.add(shapeOf(zone));
        }
        // Same impact picture as last run and nothing else to do → no rewrite,
        // so repeated weather updates don't churn the miz (or the changelog).
        if (oldCvicShapes.equals(newShapes)) {
            return new Result(mission, newShapes.size(), oldCvicShapes.size(), false);
        }

        String zonesContent = LuaTable.emitEntries(kept, 3);
        String text = mission.substring(0, zones.contentStart()) + zonesContent
                + mission.substring(zones.contentEnd());

        // ── 2 + 3. trigger rule + compiled mirror ───────────────────────────
        text = writeTrigger(text, newZoneIds);
        return new Result(text, newZoneIds.size(), oldCvicShapes.size(), true);
    }

    /** Position-independent identity of a zone (x/y/radius + hidden flag), for
     *  change detection across runs (names/ids renumber freely). The hidden
     *  flag is part of the identity so a flag-only change (e.g. hiding all
     *  generated zones) still rewrites zones from before that change. */
    private static String shapeOf(String zoneValue) {
        String x = LuaTable.extract(zoneValue, Pattern.compile("\\[\"x\"\\]\\s*=\\s*([-0-9.]+)"));
        String y = LuaTable.extract(zoneValue, Pattern.compile("\\[\"y\"\\]\\s*=\\s*([-0-9.]+)"));
        String r = LuaTable.extract(zoneValue, Pattern.compile("\\[\"radius\"\\]\\s*=\\s*([-0-9.]+)"));
        String hidden = LuaTable.extract(zoneValue, Pattern.compile("\\[\"hidden\"\\]\\s*=\\s*(true|false)"));
        return round(x) + "/" + round(y) + "/" + round(r) + "/" + hidden;
    }

    private static String round(String v) {
        try {
            return String.format(Locale.ROOT, "%.1f", Double.parseDouble(v));
        } catch (RuntimeException e) {
            return String.valueOf(v);
        }
    }

    private static String zoneBody(long zoneId, String name, WeaponImpact impact) {
        return String.format(Locale.ROOT, """
                {
                \t\t\t\t["radius"] = %.2f,
                \t\t\t\t["zoneId"] = %d,
                \t\t\t\t["color"] =\s
                \t\t\t\t{
                \t\t\t\t\t[1] = 1,
                \t\t\t\t\t[2] = 1,
                \t\t\t\t\t[3] = 1,
                \t\t\t\t\t[4] = 0.15,
                \t\t\t\t}, -- end of ["color"]
                \t\t\t\t["properties"] = {},
                \t\t\t\t["hidden"] = true,
                \t\t\t\t["y"] = %.6f,
                \t\t\t\t["x"] = %.6f,
                \t\t\t\t["name"] = "%s",
                \t\t\t\t["heading"] = 0,
                \t\t\t\t["type"] = 0,
                \t\t\t}""", impact.getRadiusM(), zoneId, impact.getY(), impact.getX(), name);
    }

    /** Rewrites (or creates) the CVIC_SCENERY_DESTRUCT rule in trigrules and
     *  its compiled mirror in trig. */
    private static String writeTrigger(String mission, List<Long> zoneIds) {
        LuaTable.KeyTable trigrules = LuaTable.findKeyTable(mission, "trigrules", 0, mission.length());
        if (trigrules == null) return mission;
        List<LuaTable.Entry> rules = LuaTable.entries(
                mission, trigrules.contentStart(), trigrules.contentEnd());

        int position = -1; // 1-based rule position = compiled trig index
        LuaTable.Entry ourRule = null;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).value().contains("[\"comment\"] = \"" + TRIGGER_COMMENT + "\"")) {
                position = i + 1;
                ourRule = rules.get(i);
                break;
            }
        }
        boolean isNew = ourRule == null;
        if (isNew) position = rules.size() + 1;

        String actionsContent = ruleActionsContent(zoneIds);
        String text;
        if (isNew) {
            String rule = String.format("""
                    {
                    \t\t\t["rules"] = {},
                    \t\t\t["eventlist"] = "mission start",
                    \t\t\t["predicate"] = "triggerOnce",
                    \t\t\t["actions"] =\s
                    \t\t\t{%s},
                    \t\t\t["comment"] = "%s",
                    \t\t}""", actionsContent, TRIGGER_COMMENT);
            List<String> all = new ArrayList<>();
            for (LuaTable.Entry r : rules) all.add(r.value());
            all.add(rule);
            text = mission.substring(0, trigrules.contentStart())
                    + LuaTable.emitEntries(all, 2)
                    + mission.substring(trigrules.contentEnd());
        } else {
            LuaTable.KeyTable ruleActions = LuaTable.findKeyTable(
                    mission, "actions", ourRule.start(), ourRule.end());
            if (ruleActions == null) return mission;
            text = mission.substring(0, ruleActions.contentStart()) + actionsContent
                    + mission.substring(ruleActions.contentEnd());
        }
        return writeCompiledTrig(text, position, zoneIds, isNew);
    }

    private static String ruleActionsContent(List<Long> zoneIds) {
        List<String> actions = new ArrayList<>();
        for (long zoneId : zoneIds) {
            actions.add(String.format("""
                    {
                    \t\t\t\t\t["predicate"] = "a_scenery_destruction_zone",
                    \t\t\t\t\t["destruction_level"] = 100,
                    \t\t\t\t\t["zone"] = %d,
                    \t\t\t\t}""", zoneId));
        }
        return LuaTable.emitEntries(actions, 4);
    }

    /** The runtime mirror: trig.actions[p] (+ conditions/flag/event dispatch
     *  when the rule is new). Replicates the ME's compiled once-trigger form:
     *  all zone calls in one string, then self-deregistration from the
     *  "mission start" event list. */
    private static String writeCompiledTrig(String mission, int position,
                                            List<Long> zoneIds, boolean isNew) {
        LuaTable.KeyTable trig = LuaTable.findKeyTable(mission, "trig", 0, mission.length());
        if (trig == null) return mission;

        // Event-list slot: reuse the existing dispatch entry pointing at our
        // action (replace-in-place keeps its index stable); else append.
        LuaTable.KeyTable events = LuaTable.findKeyTable(
                mission, "events", trig.contentStart(), trig.contentEnd());
        int eventSlot = 1;
        String dispatch = "if mission.trig.conditions[" + position
                + "]() then mission.trig.actions[" + position + "]() end";

        String text = mission;
        if (events != null) {
            LuaTable.KeyTable missionStart = LuaTable.findKeyTable(
                    text, "mission start", events.contentStart(), events.contentEnd());
            if (missionStart != null) {
                List<LuaTable.Entry> dispatches = LuaTable.entries(
                        text, missionStart.contentStart(), missionStart.contentEnd());
                List<String> values = new ArrayList<>();
                int found = -1;
                for (LuaTable.Entry d : dispatches) {
                    values.add(d.value());
                    if (d.value().contains("mission.trig.actions[" + position + "]")) {
                        found = values.size();
                    }
                }
                if (found == -1) {
                    values.add('"' + dispatch + '"');
                    eventSlot = values.size();
                } else {
                    eventSlot = found;
                    values.set(found - 1, '"' + dispatch + '"');
                }
                text = text.substring(0, missionStart.contentStart())
                        + LuaTable.emitEntries(values, 4)
                        + text.substring(missionStart.contentEnd());
            } else {
                // NB: must end on a fresh line — the events table may be the
                // serializer's inline `{}`, whose closing brace continues on
                // the insertion line and would otherwise be swallowed by the
                // `-- end of` comment.
                String missionStartTable = "\n\t\t\t[\"mission start\"] = \n\t\t\t{\n\t\t\t\t[1] = \""
                        + dispatch + "\",\n\t\t\t}, -- end of [\"mission start\"]\n\t\t";
                text = text.substring(0, events.contentStart()) + missionStartTable
                        + text.substring(events.contentStart());
            }
        }
        // (No ["events"] table at all would mean a miz with no compiled trig
        // section worth preserving — DCS always writes one; left untouched.)

        StringBuilder action = new StringBuilder();
        for (long zoneId : zoneIds) {
            action.append("a_scenery_destruction_zone(").append(zoneId).append(", 100);");
        }
        action.append(" mission.trig.events[\\\"mission start\\\"][").append(eventSlot).append("]=nil;");

        text = setIndexedString(text, "actions", position, action.toString());
        if (isNew) {
            text = setIndexedString(text, "conditions", position, null, "\"return(true)\"");
            text = setIndexedString(text, "flag", position, null, "true");
        }
        return text;
    }

    /** trig.<key>[index] = "value" — replaces the existing entry or appends. */
    private static String setIndexedString(String mission, String key, int index, String stringValue) {
        return setIndexedString(mission, key, index, stringValue, null);
    }

    private static String setIndexedString(String mission, String key, int index,
                                           String stringValue, String rawValue) {
        LuaTable.KeyTable trig = LuaTable.findKeyTable(mission, "trig", 0, mission.length());
        if (trig == null) return mission;
        LuaTable.KeyTable table = LuaTable.findKeyTable(
                mission, key, trig.contentStart(), trig.contentEnd());
        if (table == null) return mission;
        String value = rawValue != null ? rawValue : '"' + stringValue + '"';
        List<LuaTable.Entry> entries = LuaTable.entries(
                mission, table.contentStart(), table.contentEnd());
        for (LuaTable.Entry e : entries) {
            if (e.index() == index) {
                return mission.substring(0, e.start()) + "[" + index + "] = " + value + ","
                        + mission.substring(e.end());
            }
        }
        String appended = "\n\t\t\t[" + index + "] = " + value + ",";
        int insertAt = entries.isEmpty() ? table.contentStart()
                : entries.get(entries.size() - 1).end();
        return mission.substring(0, insertAt) + appended + mission.substring(insertAt);
    }
}
