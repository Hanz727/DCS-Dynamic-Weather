package com.marlan.weatherupdate.service.destruction.model;

import lombok.Data;

/**
 * One piece of evidence behind a destroyed unit, from the CVIC backend's
 * /api/v1/mission/destroyed report (gson maps the snake_case payload).
 */
@Data
public class DestroyedSource {
    private String kind; // red_ground | bda | override
    private String date;
    private String time; // "HH:MM" of the kill — red_ground only
    private String session;
    private String cause;
    private String killedBy;
    private String dmpi;
    private String bda;
    private Integer debriefId;
}
