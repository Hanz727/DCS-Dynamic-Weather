package com.marlan.weatherupdate.service.missioneditor.values;

import com.marlan.weatherupdate.model.metar.fields.CloudLayer;
import lombok.Data;

import java.util.List;

@Data
public class Clouds {
    private final List<CloudLayer> layers;
    private final boolean hasPrecip;
    private final boolean forceClear;

    public Clouds(List<CloudLayer> layers, boolean hasPrecip, boolean forceClear) {
        this.layers = layers;
        this.hasPrecip = hasPrecip;
        this.forceClear = forceClear;
    }
}
