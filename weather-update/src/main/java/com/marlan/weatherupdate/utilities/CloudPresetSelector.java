package com.marlan.weatherupdate.utilities;

import com.marlan.shared.utilities.Log;
import com.marlan.weatherupdate.model.metar.fields.CloudLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks a DCS cloud preset that matches a METAR cloud report.
 * Port of the algorithm from github.com/evogelsa/DCS-real-weather (miz/mission.go).
 */
public final class CloudPresetSelector {
    private static final Log log = Log.getInstance();
    private static final Random random = new Random();

    public static final double FEET_TO_METERS = 0.3048;

    public static final int BASE_MIN_METERS = 300;
    public static final int BASE_MAX_METERS = 5000;

    public record Preset(String name, int minBase, int maxBase) {}

    public record Selection(String presetName, int baseMeters) {
        public static final Selection CLEAR = new Selection("", 0);
    }

    /**
     * kind ("FEW","SCT","BKN","OVC", or "*+RA" variant) -> valid presets, bases in meters MSL.
     */
    private static final Map<String, List<Preset>> CLOUD_PRESETS = Map.ofEntries(
            Map.entry("FEW", List.of(
                    new Preset("Preset1", 840, 4200),
                    new Preset("Preset2", 1260, 2520)
            )),
            Map.entry("SCT", List.of(
                    new Preset("Preset3", 840, 2520),
                    new Preset("Preset4", 1260, 2520),
                    new Preset("Preset5", 1260, 4620),
                    new Preset("Preset6", 1260, 4200),
                    new Preset("Preset7", 1680, 5040),
                    new Preset("Preset8", 3780, 5460),
                    new Preset("Preset9", 1680, 3780),
                    new Preset("Preset10", 1260, 4200),
                    new Preset("Preset11", 2520, 5460),
                    new Preset("Preset12", 1680, 3360)
            )),
            Map.entry("SCT+RA", List.of(
                    new Preset("RainyPreset4", 1260, 4200),
                    new Preset("NEWRAINPRESET4", 840, 5174)
            )),
            Map.entry("BKN", List.of(
                    new Preset("Preset13", 1680, 3360),
                    new Preset("Preset14", 1680, 3360),
                    new Preset("Preset15", 840, 5040),
                    new Preset("Preset16", 1260, 4200),
                    new Preset("Preset17", 0, 2520),
                    new Preset("Preset18", 0, 3780),
                    new Preset("Preset19", 0, 2940),
                    new Preset("Preset20", 0, 3780)
            )),
            Map.entry("BKN+RA", List.of(
                    new Preset("RainyPreset5", 1260, 2520)
            )),
            Map.entry("OVC", List.of(
                    new Preset("Preset21", 1260, 4200),
                    new Preset("Preset22", 420, 4200),
                    new Preset("Preset23", 840, 3360),
                    new Preset("Preset24", 420, 2520),
                    new Preset("Preset25", 420, 3360),
                    new Preset("Preset26", 420, 2940),
                    new Preset("Preset27", 420, 2520)
            )),
            Map.entry("OVC+RA", List.of(
                    new Preset("RainyPreset1", 420, 2940),
                    new Preset("RainyPreset2", 840, 2520),
                    new Preset("RainyPreset3", 840, 2520),
                    new Preset("RainyPreset6", 1260, 2940)
            ))
    );

    private static final Map<String, Integer> CODE_RANK = Map.of(
            "FEW", 1,
            "SCT", 2,
            "BKN", 3,
            "OVC", 4
    );

    private CloudPresetSelector() {}

    /**
     * Picks a preset for the given METAR cloud layers. Returns {@link Selection#CLEAR}
     * when there are no layers (caller should treat as clear sky).
     *
     * @param clouds             AVWX cloud layers (altitude in hundreds of feet AGL)
     * @param stationElevMeters  station elevation to add to AGL → MSL conversion
     * @param hasPrecip          true when METAR contains a precip code (RA, SN, DZ, etc.)
     */
    public static Selection pick(List<CloudLayer> clouds, double stationElevMeters, boolean hasPrecip) {
        if (clouds == null || clouds.isEmpty()) {
            return Selection.CLEAR;
        }

        CloudLayer baseLayer = pickBaseLayer(clouds, hasPrecip);
        if (baseLayer == null || baseLayer.getType() == null || baseLayer.getAltitude() == null) {
            return Selection.CLEAR;
        }

        int layerBaseAgl = (int) Math.round(baseLayer.getAltitude() * 100.0 * FEET_TO_METERS);
        int baseMeters = clamp(layerBaseAgl + (int) Math.round(stationElevMeters),
                BASE_MIN_METERS, BASE_MAX_METERS);

        return selectPreset(baseLayer.getType(), baseMeters, hasPrecip);
    }

    /**
     * Layer picker (evogelsa checkClouds):
     *  - if precip → fullest layer (highest coverage rank)
     *  - else if any BKN/OVC → first non-FEW/SCT layer
     *  - else → first layer
     */
    private static CloudLayer pickBaseLayer(List<CloudLayer> clouds, boolean hasPrecip) {
        boolean ceiling = clouds.stream().anyMatch(c ->
                "BKN".equals(c.getType()) || "OVC".equals(c.getType()));

        if (hasPrecip) {
            CloudLayer fullest = null;
            int bestRank = 0;
            for (CloudLayer c : clouds) {
                int rank = CODE_RANK.getOrDefault(c.getType(), 0);
                if (rank > bestRank) {
                    bestRank = rank;
                    fullest = c;
                }
            }
            return fullest != null ? fullest : clouds.get(0);
        }

        if (ceiling) {
            for (CloudLayer c : clouds) {
                if ("BKN".equals(c.getType()) || "OVC".equals(c.getType())) return c;
            }
        }
        return clouds.get(0);
    }

    /**
     * selectPreset (evogelsa): find presets whose [min,max] contains base; fall back to
     * range-overlap with global clamp; random pick from whatever is valid.
     */
    private static Selection selectPreset(String kind, int baseMeters, boolean hasPrecip) {
        String lookupKind = kind;
        if (hasPrecip) {
            switch (kind) {
                case "OVC" -> lookupKind = "OVC+RA";
                case "BKN" -> lookupKind = "BKN+RA";
                case "SCT" -> lookupKind = "SCT+RA";
                default -> log.warning("No rainy preset for " + kind + " with precip; ignoring precip.");
            }
        }

        List<Preset> pool = CLOUD_PRESETS.get(lookupKind);
        if (pool == null) {
            log.warning("Unknown cloud kind: " + lookupKind);
            return Selection.CLEAR;
        }

        List<Preset> exact = new ArrayList<>();
        List<Preset> overlap = new ArrayList<>();
        for (Preset p : pool) {
            if (baseMeters >= p.minBase() && baseMeters <= p.maxBase()) {
                exact.add(p);
            } else if (p.minBase() < BASE_MAX_METERS && p.maxBase() > BASE_MIN_METERS) {
                overlap.add(p);
            }
        }

        if (!exact.isEmpty()) {
            Preset chosen = exact.get(random.nextInt(exact.size()));
            return new Selection(chosen.name(), baseMeters);
        }

        log.warning("No exact preset for " + lookupKind + " base=" + baseMeters + "m; widening search.");

        if (overlap.isEmpty()) {
            log.warning("No overlapping preset for " + lookupKind + "; falling back to clear.");
            return Selection.CLEAR;
        }

        Preset chosen = overlap.get(random.nextInt(overlap.size()));
        int span = chosen.maxBase() - chosen.minBase();
        int fallbackBase = span > 0
                ? chosen.minBase() + random.nextInt(span)
                : chosen.minBase();
        return new Selection(chosen.name(), fallbackBase);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
