package com.marlan.weatherupdate.service.destruction.model;

import lombok.Data;

import java.util.List;

/**
 * One known-dead unit/static from the CVIC backend's destroyed report.
 * inMission == true means it still exists in the miz — our removal target.
 */
@Data
public class DestroyedUnit {
    private Long unitId;
    private String name;
    private String type;
    private String displayType;
    private String group;
    private String category;
    private boolean inMission;
    private Double x;
    private Double y;
    private String date;
    private List<DestroyedSource> sources;
}
