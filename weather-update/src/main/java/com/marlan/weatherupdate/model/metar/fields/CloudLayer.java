package com.marlan.weatherupdate.model.metar.fields;

import lombok.Data;

/**
 * GSON Deserialization Class for a single AVWX cloud layer.
 * altitude is in hundreds of feet (e.g. 70 = 7000 ft AGL).
 */
@Data
public class CloudLayer {
    private String type;
    private Integer altitude;
    private String modifier;
    private String repr;
}
