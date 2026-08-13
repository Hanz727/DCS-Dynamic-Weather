package com.marlan.weatherupdate.service.destruction.model;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * GET /api/v1/mission/destroyed — everything known-dead for the selected miz.
 * Empty (with the flags telling why) when the miz is not the current
 * theatre's deployment mission or "Invincible units" is on.
 */
@Data
public class DestroyedReport {
    private String mission;
    private double radiusM;
    private boolean deploymentMission;
    private boolean invincibleUnits;
    private List<String> redGroundFiles;
    private List<DestroyedUnit> units;

    public List<DestroyedUnit> getUnits() {
        return units == null ? Collections.emptyList() : units;
    }
}
