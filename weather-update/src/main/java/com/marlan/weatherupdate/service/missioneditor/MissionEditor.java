package com.marlan.weatherupdate.service.missioneditor;

import com.marlan.shared.utilities.Log;
import com.marlan.weatherupdate.model.station.AVWXStation;
import com.marlan.weatherupdate.service.missioneditor.values.Conditions;
import com.marlan.weatherupdate.utilities.AltimeterUtility;
import com.marlan.weatherupdate.utilities.CloudPresetSelector;
import org.jetbrains.annotations.NotNull;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles replacing strings inside the mission file
 */
public class MissionEditor {
    private static final Log log = Log.getInstance();
    private static final double KNOTS_TO_METERS = 0.51444444444;
    private static final double INHG_TO_MMHG = 25.4;
    private static final double TEMP_LAPSE_RATE_C = 1.98;
    private static final Random random = new Random();
    private final AVWXStation stationAVWX;
    private final MissionValues missionValues;

    public MissionEditor(AVWXStation stationAVWX, MissionValues missionValues) {
        this.stationAVWX = stationAVWX;
        this.missionValues = missionValues;
    }

    public String editMission(String mission) {
        double correctedQffInHg = AltimeterUtility.getCorrectedQff(missionValues.getStation().getQnh(), missionValues.getStation().getTempC(), stationAVWX);
        double qffMmHg = correctedQffInHg * INHG_TO_MMHG;

        double windSpeedBase = missionValues.getWind().getSpeed();
        double modifiedWindSpeed = getModifiedBaseWindSpeed(windSpeedBase);
        double windSpeedGround = getCorrectedGroundWindSpeed(modifiedWindSpeed, stationAVWX.getElevationM()); // "Ground" Wind is 10m/33ft but also sets ~500m/1660ft
        double windSpeed2000 = getModifiedWindSpeed(2000, modifiedWindSpeed); // "2000" Wind is 2000m/6600ft
        double windSpeed8000 = getModifiedWindSpeed(8000, modifiedWindSpeed); // "8000" Wind is 8000m/26000ft

        double windDirectionDcs = invertWindDirection(missionValues.getWind().getDirection()); // Wind Direction is backwards in DCS.
        double windDirectionGround = windDirectionDcs;
        double windDirection2000 = randomizeWindDirection(windDirectionDcs);
        double windDirection8000 = randomizeWindDirection(windDirection2000);

        CloudPresetSelector.Selection cloudsSelection = CloudPresetSelector.pick(
                missionValues.getClouds().getLayers(),
                stationAVWX.getElevationM(),
                missionValues.getClouds().isHasPrecip());

        mission = replaceCloudsBlock(mission, cloudsSelection);
        mission = applyConditions(mission, missionValues.getConditions());
        mission = replaceWind8000(mission, windSpeed8000, windDirection8000);
        mission = replaceWind2000(mission, windSpeed2000, windDirection2000);
        //mission = replaceWindGround(mission, windSpeedGround, windDirectionGround);
        mission = replaceHour(mission, missionValues.getTime().getHour());
        mission = replaceDay(mission, missionValues.getTime().getDay());
        mission = replaceMonth(mission, missionValues.getTime().getMonth());
        mission = replaceTemperature(mission, missionValues.getStation().getTempC());
        mission = replaceQnh(mission, qffMmHg, missionValues.getStation().getQnh());

        return mission;
    }

    /**
     * This method modifies a base wind speed value using a soft limiting function.
     * For wind speeds above the limit, a logarithmic scaling is applied such that the output increases,
     * but at a decreasing rate. This has the effect of 'softly' limiting the wind speed to the limit.
     * The rate of increase above the limit is controlled by the 'steepness' parameter.
     */
    private double getModifiedBaseWindSpeed(double windSpeedBase) {
        double limit = 15.0;
        double steepness = 2.5;

        if (windSpeedBase <= limit) {
            return windSpeedBase;
        } else {
            return limit + steepness * Math.log(windSpeedBase - limit + 1);
        }
    }

    @NotNull
    private String replaceQnh(String mission, double qffMmHg, double qnhInHg) {
        Pattern pattern = Pattern.compile("(\\[\"qnh\"].*)\n", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, QNH not set.");
            return mission;
        }
        mission = matcher.replaceAll("[\"qnh\"] = \\$qnh,\n"
                .replace("$qnh", Double.toString(qffMmHg))); // DCS actually uses QFF not QNH!
        double qnhMmHg = qnhInHg * INHG_TO_MMHG;
        log.info("QNH set to: " + qnhInHg + " inHg (" + qnhMmHg + " mmHg)");
        log.info("QFF set to: " + qffMmHg / INHG_TO_MMHG + " inHg (" + qffMmHg + " mmHg)");
        return mission;
    }

    @NotNull
    private String replaceTemperature(String mission, double stationTempC) {
        Pattern pattern = Pattern.compile("(\\[\"temperature\"].*)\n", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Temperature not set.");
            return mission;
        }
        mission = matcher.replaceAll("[\"temperature\"] = \\$stationTempC,\n"
                .replace("$stationTempC", Double.toString(stationTempC)));
        log.info("Station Temperature set to: " + stationTempC
                + " C" + " / Sea Level Temperature set to: "
                + Math.round(stationTempC + TEMP_LAPSE_RATE_C * (stationAVWX.getElevationFt() / 1000)) + " C");
        return mission;
    }

    @NotNull
    private String replaceMonth(String mission, int month) {
        Pattern pattern = Pattern.compile("(\\[\"Month\"].*)\n", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Month not set.");
            return mission;
        }
        mission = matcher.replaceAll("[\"Month\"] = \\$month,\n"
                .replace("$month", Integer.toString(month)));
        log.info("Month set to: " + month);
        return mission;
    }

    @NotNull
    private String replaceDay(String mission, int day) {
        Pattern pattern = Pattern.compile("(\\[\"Day\"].*)\n", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Day not set.");
            return mission;
        }
        mission = matcher.replaceAll("[\"Day\"] = \\$day,\n".replace("$day", Integer.toString(day)));
        log.info("Day set to: " + day);
        return mission;
    }

    @NotNull
    private String replaceHour(String mission, float hour) {
        Pattern pattern = Pattern.compile("^(?:\\s{4}|\\t)\\[\"start_time\"]\\s=\\s.*,$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(mission);

        if (!matcher.find()) {
            log.error("Regex match failed, Hour not set.");
            return mission;
        }
        mission = matcher.replaceAll("    [\"start_time\"] = $startTime,"
                .replace("$startTime", Float.toString(hour * 3600)));
        log.info("Start Time set to: " + hour * 3600 + "s (" + hour + "h)");
        return mission;
    }

    @NotNull
    private String replaceWindGround(String mission, double windSpeedGround, double windDirectionGround) {
        Pattern pattern = Pattern.compile("\\[\"atGround\"]\\s+=\\s+\\{([^}]*)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Wind at Ground not set.");
            return mission;
        }
        mission = matcher.replaceFirst(
                "[\"atGround\"] =\n            {\n                [\"speed\"] = $windGroundSpeed,\n                [\"dir\"] = $windGroundDir,\n            "
                        .replace("$windGroundSpeed", Double.toString(windSpeedGround))
                        .replace("$windGroundDir", Double.toString(windDirectionGround)));

        log.info("Wind at Ground set to: "
                + Math.round(windSpeedGround) + " m/s ("
                + Math.round(windSpeedGround / KNOTS_TO_METERS) + " kts) "
                + Math.floor(invertWindDirection(windDirectionGround)) + "°");
        return mission;
    }

    @NotNull
    private String replaceWind2000(String mission, double windSpeed2000, double windDirection2000) {
        Pattern pattern = Pattern.compile("\\[\"at2000\"]\\s+=\\s+\\{([^}]*)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Wind at 2000 not set.");
            return mission;
        }
        mission = matcher.replaceAll(
                "[\"at2000\"] =\n            {\n                [\"speed\"] = $wind2000Speed,\n                [\"dir\"] = $wind2000Dir,\n            "
                        .replace("$wind2000Speed", Double.toString(windSpeed2000))
                        .replace("$wind2000Dir", Double.toString(windDirection2000)));
        log.info("Wind at 2000 set to: "
                + Math.round(windSpeed2000) + " m/s ("
                + Math.round(windSpeed2000 / KNOTS_TO_METERS) + " kts) "
                + Math.floor(invertWindDirection(windDirection2000)) + "°");
        return mission;
    }

    @NotNull
    private String replaceWind8000(String mission, double windSpeed8000, double windDirection8000) {
        Pattern pattern = Pattern.compile("\\[\"at8000\"]\\s+=\\s+\\{([^}]*)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Wind at 8000 not set.");
            return mission;
        }
        mission = matcher.replaceFirst(
                "[\"at8000\"] =\n            {\n                [\"speed\"] = $wind8000Speed,\n                [\"dir\"] = $wind8000Dir,\n            "
                        .replace("$wind8000Speed", Double.toString(windSpeed8000))
                        .replace("$wind8000Dir", Double.toString(windDirection8000)));
        log.info("Wind at 8000 set to: "
                + Math.round(windSpeed8000) + " m/s ("
                + Math.round(windSpeed8000 / KNOTS_TO_METERS) + " kts) "
                + Math.floor(invertWindDirection(windDirection8000)) + "°");
        return mission;
    }

    private static final Pattern CLOUDS_BLOCK = Pattern.compile(
            "\\[\"clouds\"]\\s*=\\s*\\{[^{}]*}", Pattern.MULTILINE);

    private String applyConditions(String mission, Conditions c) {
        if (!c.isApply()) return mission;
        mission = replaceVisibilityDistance(mission, c.getDistanceMeters());
        mission = replaceEnableFog(mission, c.isHasFog());
        mission = replaceFogBlock(mission, c.getFogThicknessMeters(), c.getFogVisibilityMeters());
        mission = replaceEnableDust(mission, c.isHasDust());
        mission = replaceDustDensity(mission, c.getDustDensity());
        return mission;
    }

    private static final Pattern VISIBILITY_DISTANCE = Pattern.compile(
            "(\\[\"visibility\"]\\s*=\\s*\\{[^{}]*?\\[\"distance\"]\\s*=\\s*)[^,\\s]+",
            Pattern.MULTILINE);

    private String replaceVisibilityDistance(String mission, int meters) {
        Matcher matcher = VISIBILITY_DISTANCE.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Visibility distance not set.");
            return mission;
        }
        mission = matcher.replaceFirst("$1" + meters);
        log.info("Visibility distance set to: " + meters + " m");
        return mission;
    }

    private static final Pattern ENABLE_FOG = Pattern.compile(
            "(\\[\"enable_fog\"]\\s*=\\s*)(?:true|false)", Pattern.MULTILINE);

    private String replaceEnableFog(String mission, boolean enabled) {
        Matcher matcher = ENABLE_FOG.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, enable_fog not set.");
            return mission;
        }
        mission = matcher.replaceFirst("$1" + enabled);
        log.info("enable_fog set to: " + enabled);
        return mission;
    }

    private static final Pattern FOG_BLOCK = Pattern.compile(
            "\\[\"fog\"]\\s*=\\s*\\{[^{}]*}", Pattern.MULTILINE);

    private String replaceFogBlock(String mission, int thicknessMeters, int visibilityMeters) {
        Matcher matcher = FOG_BLOCK.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Fog block not set.");
            return mission;
        }
        String replacement = "[\"fog\"] = \n"
                + "        {\n"
                + "            [\"thickness\"] = " + thicknessMeters + ",\n"
                + "            [\"visibility\"] = " + visibilityMeters + ",\n"
                + "        }";
        mission = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        log.info("Fog set to: thickness=" + thicknessMeters + "m visibility=" + visibilityMeters + "m");
        return mission;
    }

    private static final Pattern ENABLE_DUST = Pattern.compile(
            "(\\[\"enable_dust\"]\\s*=\\s*)(?:true|false)", Pattern.MULTILINE);

    private String replaceEnableDust(String mission, boolean enabled) {
        Matcher matcher = ENABLE_DUST.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, enable_dust not set.");
            return mission;
        }
        mission = matcher.replaceFirst("$1" + enabled);
        log.info("enable_dust set to: " + enabled);
        return mission;
    }

    // replaceFogBlock has already rewritten the fog block without dust_density,
    // so the only dust_density left is the top-level one.
    private static final Pattern TOP_LEVEL_DUST_DENSITY = Pattern.compile(
            "(\\[\"dust_density\"]\\s*=\\s*)[^,\\s]+", Pattern.MULTILINE);

    private String replaceDustDensity(String mission, int density) {
        Matcher matcher = TOP_LEVEL_DUST_DENSITY.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, dust_density not set.");
            return mission;
        }
        mission = matcher.replaceFirst("$1" + density);
        log.info("dust_density set to: " + density);
        return mission;
    }

    private String replaceCloudsBlock(String mission, CloudPresetSelector.Selection sel) {
        Matcher matcher = CLOUDS_BLOCK.matcher(mission);
        if (!matcher.find()) {
            log.error("Regex match failed, Clouds block not set.");
            return mission;
        }

        String presetLine = sel.presetName().isEmpty()
                ? "            [\"preset\"] = nil,\n"
                : "            [\"preset\"] = \"" + sel.presetName() + "\",\n";

        String replacement = "[\"clouds\"] = \n"
                + "        {\n"
                + "            [\"density\"] = 0,\n"
                + "            [\"thickness\"] = 200,\n"
                + presetLine
                + "            [\"base\"] = " + sel.baseMeters() + ",\n"
                + "            [\"iprecptns\"] = 0,\n"
                + "        }";

        mission = matcher.replaceFirst(Matcher.quoteReplacement(replacement));

        if (sel.presetName().isEmpty()) {
            log.info("Clouds set to clear (no preset).");
        } else {
            log.info("Clouds preset: " + sel.presetName() + " base=" + sel.baseMeters() + "m ("
                    + Math.round(sel.baseMeters() / CloudPresetSelector.FEET_TO_METERS) + " ft)");
        }
        return mission;
    }

    private double getCorrectedGroundWindSpeed(double windSpeedKnots, double stationAltitude) {
        double windSpeedMultiplier = Math.abs((0.5 / 500) * stationAltitude - 1);
        return windSpeedKnots * windSpeedMultiplier * KNOTS_TO_METERS;
    }

    private double getModifiedWindSpeed(double altitudeMeters, double windSpeedKnots) {
        // DCS interpolates linearly between atGround and at2000, and ground wind is not
        // being written (kept at mission default), so at2000 values become the dominant
        // wind felt at field elevation and through the low climb. Keep aloft values in a
        // realistic envelope so Nellis (~570m) and 3000' don't inherit gale-force winds.
        double windSpeedMultiplier;
        double windSpeedAddition;
        if (altitudeMeters == 2000) {
            windSpeedAddition = Math.min(random.nextGaussian(5, 5), 15);
            windSpeedMultiplier = Math.min(random.nextGaussian(0.2, 0.2) + 1, 1.6);
            return Math.max(0, (windSpeedKnots * windSpeedMultiplier) + windSpeedAddition) * KNOTS_TO_METERS;
        } else if (altitudeMeters == 8000) {
            windSpeedAddition = Math.min(random.nextGaussian(10, 10), 30);
            windSpeedMultiplier = Math.min(random.nextGaussian(0.5, 0.5) + 1, 2.0);
            return Math.max(0, (windSpeedKnots * windSpeedMultiplier) + windSpeedAddition) * KNOTS_TO_METERS;
        } else {
            return 0.0;
        }
    }

    private double randomizeWindDirection(double windDirection) {
        double randomizedWindDirection = windDirection + random.nextGaussian(0, 60);
        if (randomizedWindDirection < 0) {
            randomizedWindDirection += 360;
        } else if (randomizedWindDirection > 360) {
            randomizedWindDirection -= 360;
        }
        return randomizedWindDirection;
    }

    private double invertWindDirection(double windDirection) {
        if (windDirection >= 0 && windDirection <= 180) {
            return windDirection + 180;
        } else {
            return windDirection - 180;
        }
    }
}
