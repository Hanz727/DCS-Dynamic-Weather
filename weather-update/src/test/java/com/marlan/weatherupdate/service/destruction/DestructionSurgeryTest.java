package com.marlan.weatherupdate.service.destruction;

import com.marlan.weatherupdate.service.destruction.model.WeaponImpact;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DestructionSurgeryTest {

    // A miniature mission with the real serializer's shapes: two vehicle
    // groups (one dies whole), a static group, one user zone, one stale CVIC
    // zone, one existing trigger rule + compiled trig.
    private static final String MISSION = """
            mission = \s
            {
            \t["trig"] = \s
            \t{
            \t\t["actions"] = \s
            \t\t{
            \t\t\t[1] = "a_do_script(\\"LEL\\"); mission.trig.func[1]=nil;",
            \t\t}, -- end of ["actions"]
            \t\t["events"] = \s
            \t\t{
            \t\t\t["mission start"] = \s
            \t\t\t{
            \t\t\t\t[1] = "if mission.trig.conditions[1]() then mission.trig.actions[1]() end",
            \t\t\t}, -- end of ["mission start"]
            \t\t}, -- end of ["events"]
            \t\t["flag"] = \s
            \t\t{
            \t\t\t[1] = true,
            \t\t}, -- end of ["flag"]
            \t\t["conditions"] = \s
            \t\t{
            \t\t\t[1] = "return(true)",
            \t\t}, -- end of ["conditions"]
            \t}, -- end of ["trig"]
            \t["triggers"] = \s
            \t{
            \t\t["zones"] = \s
            \t\t{
            \t\t\t[1] = \s
            \t\t\t{
            \t\t\t\t["radius"] = 3000,
            \t\t\t\t["zoneId"] = 100,
            \t\t\t\t["name"] = "USER ZONE",
            \t\t\t\t["y"] = -1,
            \t\t\t\t["x"] = -2,
            \t\t\t\t["type"] = 0,
            \t\t\t}, -- end of [1]
            \t\t\t[2] = \s
            \t\t\t{
            \t\t\t\t["radius"] = 50,
            \t\t\t\t["zoneId"] = 101,
            \t\t\t\t["name"] = "CVIC_DZONE_001",
            \t\t\t\t["y"] = -3,
            \t\t\t\t["x"] = -4,
            \t\t\t\t["type"] = 0,
            \t\t\t}, -- end of [2]
            \t\t}, -- end of ["zones"]
            \t}, -- end of ["triggers"]
            \t["trigrules"] = \s
            \t{
            \t\t[1] = \s
            \t\t{
            \t\t\t["rules"] = {},
            \t\t\t["eventlist"] = "",
            \t\t\t["predicate"] = "triggerOnce",
            \t\t\t["actions"] = \s
            \t\t\t{
            \t\t\t\t[1] = \s
            \t\t\t\t{
            \t\t\t\t\t["text"] = "LEL",
            \t\t\t\t\t["predicate"] = "a_do_script",
            \t\t\t\t}, -- end of [1]
            \t\t\t}, -- end of ["actions"]
            \t\t\t["comment"] = "SCRIPTS",
            \t\t}, -- end of [1]
            \t}, -- end of ["trigrules"]
            \t["coalition"] = \s
            \t{
            \t\t["red"] = \s
            \t\t{
            \t\t\t["country"] = \s
            \t\t\t{
            \t\t\t\t[1] = \s
            \t\t\t\t{
            \t\t\t\t\t["name"] = "Iran",
            \t\t\t\t\t["vehicle"] = \s
            \t\t\t\t\t{
            \t\t\t\t\t\t["group"] = \s
            \t\t\t\t\t\t{
            \t\t\t\t\t\t\t[1] = \s
            \t\t\t\t\t\t\t{
            \t\t\t\t\t\t\t\t["units"] = \s
            \t\t\t\t\t\t\t\t{
            \t\t\t\t\t\t\t\t\t[1] = \s
            \t\t\t\t\t\t\t\t\t{
            \t\t\t\t\t\t\t\t\t\t["type"] = "SNR_75V",
            \t\t\t\t\t\t\t\t\t\t["unitId"] = 157,
            \t\t\t\t\t\t\t\t\t\t["name"] = "SAM-1",
            \t\t\t\t\t\t\t\t\t}, -- end of [1]
            \t\t\t\t\t\t\t\t\t[2] = \s
            \t\t\t\t\t\t\t\t\t{
            \t\t\t\t\t\t\t\t\t\t["type"] = "S_75M_Volhov",
            \t\t\t\t\t\t\t\t\t\t["unitId"] = 158,
            \t\t\t\t\t\t\t\t\t\t["name"] = "SAM-2",
            \t\t\t\t\t\t\t\t\t}, -- end of [2]
            \t\t\t\t\t\t\t\t}, -- end of ["units"]
            \t\t\t\t\t\t\t\t["name"] = "BHR-SA-2-1",
            \t\t\t\t\t\t\t}, -- end of [1]
            \t\t\t\t\t\t\t[2] = \s
            \t\t\t\t\t\t\t{
            \t\t\t\t\t\t\t\t["units"] = \s
            \t\t\t\t\t\t\t\t{
            \t\t\t\t\t\t\t\t\t[1] = \s
            \t\t\t\t\t\t\t\t\t{
            \t\t\t\t\t\t\t\t\t\t["type"] = "ZIL-131 KUNG",
            \t\t\t\t\t\t\t\t\t\t["unitId"] = 300,
            \t\t\t\t\t\t\t\t\t\t["name"] = "Truck-1",
            \t\t\t\t\t\t\t\t\t}, -- end of [1]
            \t\t\t\t\t\t\t\t}, -- end of ["units"]
            \t\t\t\t\t\t\t\t["name"] = "TRUCKS",
            \t\t\t\t\t\t\t}, -- end of [2]
            \t\t\t\t\t\t}, -- end of ["group"]
            \t\t\t\t\t}, -- end of ["vehicle"]
            \t\t\t\t}, -- end of [1]
            \t\t\t}, -- end of ["country"]
            \t\t}, -- end of ["red"]
            \t}, -- end of ["coalition"]
            } -- end of mission
            """;

    private static WeaponImpact impact(double x, double y, double radius) {
        WeaponImpact i = new WeaponImpact();
        i.setX(x);
        i.setY(y);
        i.setRadiusM(radius);
        return i;
    }

    /** Lua's view of the braces: quote-aware AND comment-aware (a brace after
     *  `--` on a line does not count — the exact bug class an inline `{}`
     *  insertion can produce). */
    private static void assertBalanced(String text) {
        int depth = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                i = LuaTable.skipString(text, i);
                continue;
            }
            if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                int nl = text.indexOf('\n', i);
                i = nl == -1 ? text.length() : nl;
                continue;
            }
            if (c == '{') depth++;
            if (c == '}') depth--;
            assertTrue(depth >= 0, "brace depth went negative at " + i);
            i++;
        }
        assertEquals(0, depth, "unbalanced braces");
    }

    @Test
    void baseMissionNameStripsAandB() {
        assertEquals("Foo_v4.1.miz", DestructionService.baseMissionName("Foo_v4.1_A.miz"));
        assertEquals("Foo_v4.1.miz", DestructionService.baseMissionName("Foo_v4.1_B.miz"));
        assertEquals("Foo_v4.1.miz", DestructionService.baseMissionName("Foo_v4.1.miz"));
        assertEquals("Foo_A_v4.1.miz", DestructionService.baseMissionName("Foo_A_v4.1.miz"));
    }

    @Test
    void removesUnitAndRenumbersSurvivors() {
        UnitRemover.Result result = UnitRemover.remove(MISSION, Set.of(157L));
        assertBalanced(result.text());
        assertEquals(1, result.removed().size());
        assertEquals(157L, result.removed().get(0).unitId());
        assertEquals("SAM-1", result.removed().get(0).unitName());
        assertFalse(result.removed().get(0).wholeGroupGone());
        assertFalse(result.text().contains("[\"unitId\"] = 157"));
        assertTrue(result.text().contains("[\"unitId\"] = 158"));
        // survivor renumbered: S_75M now sits under a [1] header, no [2] left
        assertTrue(result.text().matches("(?s).*\\[1] = .{0,250}S_75M_Volhov.*"),
                "survivor should be re-indexed to [1]");
        assertFalse(result.text().matches("(?s).*\\[2] = .{0,250}S_75M_Volhov.*"),
                "survivor must no longer carry its old [2] index");
    }

    @Test
    void dropsWholeGroupWhenLastUnitDies() {
        UnitRemover.Result result = UnitRemover.remove(MISSION, Set.of(300L));
        assertBalanced(result.text());
        assertEquals(1, result.removed().size());
        assertTrue(result.removed().get(0).wholeGroupGone());
        assertFalse(result.text().contains("TRUCKS"));
        assertTrue(result.text().contains("BHR-SA-2-1"), "other group untouched");
    }

    @Test
    void writesZonesRuleAndCompiledTrig() {
        ZoneWriter.Result result = ZoneWriter.write(
                MISSION, List.of(impact(1000, 2000, 50), impact(3000, 4000, 25)));
        String text = result.text();
        assertBalanced(text);
        assertTrue(result.changed());
        assertEquals(2, result.zonesWritten());
        assertEquals(1, result.zonesBefore());
        // stale CVIC zone replaced; user zone kept; ids continue after max
        assertTrue(text.contains("\"USER ZONE\""));
        assertEquals(2, text.split("CVIC_DZONE_", -1).length - 1,
                "exactly two CVIC zone names in the file");
        assertTrue(text.contains("[\"zoneId\"] = 101,") && text.contains("[\"zoneId\"] = 102,"));
        // ME rule appended at position 2 with both zone actions
        assertTrue(text.contains("[\"comment\"] = \"CVIC_SCENERY_DESTRUCT\""));
        assertTrue(text.contains("[\"predicate\"] = \"a_scenery_destruction_zone\""));
        assertTrue(text.contains("[\"zone\"] = 101,") && text.contains("[\"zone\"] = 102,"));
        // compiled mirror: actions[2], conditions[2], flag[2], dispatch entry
        assertTrue(text.contains("[2] = \"a_scenery_destruction_zone(101, 100);"
                + "a_scenery_destruction_zone(102, 100);"
                + " mission.trig.events[\\\"mission start\\\"][2]=nil;\""));
        assertTrue(text.contains("[2] = \"return(true)\""));
        assertTrue(text.contains("[2] = true"));
        assertTrue(text.contains(
                "[2] = \"if mission.trig.conditions[2]() then mission.trig.actions[2]() end\""));
    }

    @Test
    void identicalImpactSetIsANoOp() {
        ZoneWriter.Result first = ZoneWriter.write(
                MISSION, List.of(impact(1000, 2000, 50)));
        assertTrue(first.changed());
        ZoneWriter.Result second = ZoneWriter.write(
                first.text(), List.of(impact(1000, 2000, 50)));
        assertFalse(second.changed());
        assertEquals(first.text(), second.text());
    }

    /** Full-scale sanity against the real deployment miz when it exists on
     *  this machine (developer box); silently skipped elsewhere. */
    @Test
    void realMissionSurgerySurvives() throws IOException {
        Path miz = Path.of("E:\\projects\\DCS\\Missions\\CVW17_2026_Deployment_PersianGulf_MASTER_v3.8.miz");
        assumeTrue(Files.exists(miz));
        String mission;
        try (ZipFile zip = new ZipFile(miz.toFile())) {
            mission = new String(zip.getInputStream(zip.getEntry("mission")).readAllBytes());
        }
        UnitRemover.Result removal = UnitRemover.remove(mission, Set.of(157L, 158L, 1212L, 1213L));
        assertEquals(4, removal.removed().size());
        ZoneWriter.Result zones = ZoneWriter.write(removal.text(),
                List.of(impact(-83286.1, 284776.9, 50), impact(-83211.9, 284777.3, 50)));
        assertTrue(zones.changed());
        assertBalanced(zones.text());
        assertFalse(zones.text().contains("[\"unitId\"] = 157,"));
        assertTrue(zones.text().contains("CVIC_DZONE_001"));
        assertTrue(zones.text().contains("CVIC_SCENERY_DESTRUCT"));
    }
}
