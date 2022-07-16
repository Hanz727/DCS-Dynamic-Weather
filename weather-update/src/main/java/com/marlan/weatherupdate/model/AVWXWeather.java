package com.marlan.weatherupdate.model;

import lombok.Data;

@Data
public class AVWXWeather {
    private Altimeter altimeter;
    private WindDirection windDirection;
    private WindSpeed windSpeed;
    private Temperature temperature;
    private Time time;
    private String sanitized;
    private String station;
    private Visibility visibility;
}
