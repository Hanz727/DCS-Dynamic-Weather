package com.marlan.weatherupdate.service.destruction.model;

import lombok.Data;

/**
 * One A/G surface impact from GET /api/v1/mission/impacts — becomes one
 * CVIC_DZONE scenery-destruction zone. radius_m is the backend's
 * warhead-mass-scaled destruction radius.
 */
@Data
public class WeaponImpact {
    private double x; // DCS world meters, north
    private double y; // DCS world meters, east
    private double radiusM;
    private String date;
    private String weapon;
    private String weaponType;
    private String domain;
    private Double explosiveKg;
    private Double warheadKg;
    private boolean splash;
    private String target;
    private String targetCategory;
    private String pilot;
    private String callsign;
}
