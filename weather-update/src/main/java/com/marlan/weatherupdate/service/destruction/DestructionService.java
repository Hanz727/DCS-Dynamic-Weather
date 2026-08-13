package com.marlan.weatherupdate.service.destruction;

import com.marlan.shared.utilities.Log;
import com.marlan.weatherupdate.service.destruction.model.DestroyedReport;
import com.marlan.weatherupdate.service.destruction.model.DestroyedUnit;
import com.marlan.weatherupdate.service.destruction.model.WeaponImpact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Battle-damage persistence pass (CVIC backend integration) — deliberately
 * separate from the weather logic: it goes far deeper into the miz (unit
 * tables, trigger zones, compiled triggers) and has nothing to do with
 * weather. See MissionScripts/DESTRUCTION_PIPELINE.md in the DebriefTracker
 * repo for the full design.
 *
 * Per mission update:
 *   1. GET /api/v1/mission/destroyed  → remove every known-dead unit/static
 *      that still exists in the miz.
 *   2. GET /api/v1/mission/impacts    → rebuild the CVIC_DZONE_* zones and
 *      the CVIC_SCENERY_DESTRUCT trigger (scenery destruction at each A/G
 *      impact point).
 *   3. Append the run's actual diff to changelog/<base>_changelog.md.
 *
 * Both endpoints answer empty unless the queried miz is the current
 * theatre's deployment mission, so non-deployment missions pass through
 * untouched. Any failure (backend down, unexpected content) leaves the
 * weather-edited mission exactly as it was — this pass is strictly additive.
 */
public class DestructionService {
    private static final Log log = Log.getInstance();

    private final String workingDir;
    private final DestructionClient client;

    public DestructionService(String workingDir) {
        this(workingDir, new DestructionClient());
    }

    DestructionService(String workingDir, DestructionClient client) {
        this.workingDir = workingDir;
        this.client = client;
    }

    /** The weather script updates _A/_B copies; damage state belongs to the
     *  base mission (and so does the changelog file). */
    static String baseMissionName(String mizName) {
        return mizName.replaceFirst("(?i)(_A|_B)(?=\\.miz$)", "");
    }

    /** Applies the battle-damage state to the extracted mission text.
     *  Returns the text unchanged on any failure. */
    public String apply(String missionContent, String mizName) {
        String baseMiz = baseMissionName(mizName);
        try {
            DestroyedReport report = client.getDestroyed(baseMiz);
            if (report == null || !report.isDeploymentMission()) {
                log.info("Battle damage: " + baseMiz
                        + " is not the current deployment mission; skipping");
                return missionContent;
            }
            List<WeaponImpact> impacts = client.getImpacts(baseMiz).stream()
                    // The weapon's own impact point only — splash secondaries
                    // are extra victims of the same explosion, not new craters.
                    .filter(i -> !i.isSplash())
                    .toList();

            Set<Long> ids = new HashSet<>();
            Map<Long, DestroyedUnit> byId = new HashMap<>();
            for (DestroyedUnit unit : report.getUnits()) {
                if (unit.isInMission() && unit.getUnitId() != null) {
                    ids.add(unit.getUnitId());
                    byId.put(unit.getUnitId(), unit);
                }
            }

            UnitRemover.Result removal = UnitRemover.remove(missionContent, ids);
            ZoneWriter.Result zones = ZoneWriter.write(removal.text(), impacts);

            log.info("Battle damage: removed " + removal.removed().size() + "/" + ids.size()
                    + " dead unit(s), " + zones.zonesWritten() + " destruction zone(s)"
                    + (zones.changed() ? "" : " (unchanged)"));
            ChangelogWriter.append(workingDir, baseMiz, removal.removed(), byId,
                    zones.zonesWritten(), zones.zonesBefore(), zones.changed());
            return zones.text();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Battle damage pass interrupted; mission left untouched");
            return missionContent;
        } catch (Exception e) {
            log.error("Battle damage pass failed (" + e.getMessage()
                    + "); mission left untouched");
            return missionContent;
        }
    }
}
