package com.marlan.weatherupdate.model.metar.fields;

import lombok.Data;

import java.util.Optional;

@Data
public class Temperature {
    private Double value;

    public Optional<Double> getValue() {
        return Optional.ofNullable(this.value);
    }
}