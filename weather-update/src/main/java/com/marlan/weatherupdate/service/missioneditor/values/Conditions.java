package com.marlan.weatherupdate.service.missioneditor.values;

import lombok.Data;

/**
 * Visibility / fog / dust state to write into the mission, derived from METAR.
 * {@code apply == false} means the caller should leave the mission's existing
 * visibility/fog/dust fields untouched.
 */
@Data
public class Conditions {
    private final boolean apply;
    private final int distanceMeters;
    private final int fogThicknessMeters;
    private final int fogVisibilityMeters;
    private final int dustDensity;
    private final boolean hasFog;
    private final boolean hasDust;

    public static Conditions skip() {
        return new Conditions(false, 0, 0, 0, 0, false, false);
    }

    public Conditions(boolean apply, int distanceMeters, int fogThicknessMeters,
                      int fogVisibilityMeters, int dustDensity,
                      boolean hasFog, boolean hasDust) {
        this.apply = apply;
        this.distanceMeters = distanceMeters;
        this.fogThicknessMeters = fogThicknessMeters;
        this.fogVisibilityMeters = fogVisibilityMeters;
        this.dustDensity = dustDensity;
        this.hasFog = hasFog;
        this.hasDust = hasDust;
    }
}
