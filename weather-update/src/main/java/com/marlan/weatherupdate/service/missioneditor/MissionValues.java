package com.marlan.weatherupdate.service.missioneditor;

import com.marlan.shared.model.Config;
import com.marlan.shared.model.DTO;
import com.marlan.shared.utilities.Log;
import com.marlan.weatherupdate.model.metar.AVWXMetar;
import com.marlan.weatherupdate.model.metar.fields.Temperature;
import com.marlan.weatherupdate.model.metar.fields.WindDirection;
import com.marlan.weatherupdate.model.metar.fields.WindSpeed;
import com.marlan.weatherupdate.model.station.AVWXStation;
import com.marlan.weatherupdate.service.airplanclient.AirplanClient;
import com.marlan.weatherupdate.service.missioneditor.values.Clouds;
import com.marlan.weatherupdate.service.missioneditor.values.Conditions;
import com.marlan.weatherupdate.service.missioneditor.values.Station;
import com.marlan.weatherupdate.service.missioneditor.values.Time;
import com.marlan.weatherupdate.service.missioneditor.values.Wind;
import com.marlan.weatherupdate.utilities.StationInfoUtility;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MissionValues {
    private static final Log log = Log.getInstance();
    private static final double ISA_TEMP_C = 15;
    private static final double ISA_PRESSURE_INHG = 29.92;
    private static final double INHG_TO_HPA = 33.86389;
    private final Config config;
    private final DTO dto;
    private final AVWXMetar metarAVWX;
    private final AirplanClient airplanClient;

    @Getter
    private final Wind wind;
    @Getter
    private final Station station;
    @Getter
    private final Time time;
    @Getter
    private final Clouds clouds;
    @Getter
    private final Conditions conditions;

    public MissionValues(Config config, DTO dto, AVWXStation stationAVWX, AVWXMetar metarAVWX, AirplanClient airplanClient) {
        this.config = config;
        this.dto = dto;
        this.metarAVWX = metarAVWX;
        this.airplanClient = airplanClient;
        this.wind = setWind();
        this.station = setStation();
        this.clouds = setClouds();
        this.conditions = setConditions();
        ZonedDateTime zonedDateTime = ZonedDateTime.now(ZoneId.of(StationInfoUtility.getZoneId(stationAVWX.getLatitude(), stationAVWX.getLongitude())));
        this.time = setTime(zonedDateTime);
    }

    private Clouds setClouds() {
        String dtoWeatherType = dto.getWeatherType();
        if (dtoWeatherType.contains("clear")) {
            return new Clouds(java.util.List.of(), false, true);
        }
        if (dtoWeatherType.contains("real") || dtoWeatherType.equals("cvops")) {
            return new Clouds(metarAVWX.getClouds(), hasPrecip(metarAVWX.getSanitized()), false);
        }
        return new Clouds(java.util.List.of(), false, false);
    }

    private static final Pattern PRECIP_CODE = Pattern.compile(
            "(^|\\s)[-+]?(?:RA|SN|DZ|SG|GS|GR|PL|IC|UP|TS)\\b");
    private static final Pattern FOG_CODE = Pattern.compile("(^|\\s)(?:FG|BR)\\b");
    private static final Pattern DUST_CODE = Pattern.compile("(^|\\s)(?:HZ|DU|SA|PO|DS|SS)\\b");

    private boolean hasPrecip(String sanitizedMetar) {
        if (sanitizedMetar == null) return false;
        return PRECIP_CODE.matcher(sanitizedMetar).find();
    }

    private boolean hasFog(String sanitizedMetar) {
        if (sanitizedMetar == null) return false;
        return FOG_CODE.matcher(sanitizedMetar).find();
    }

    private boolean hasDust(String sanitizedMetar) {
        if (sanitizedMetar == null) return false;
        return DUST_CODE.matcher(sanitizedMetar).find();
    }

    private static final int CAVOK_VISIBILITY_METERS = 80000;
    private static final int MIN_VISIBILITY_METERS = 100;
    private static final double STATUTE_MILES_TO_METERS = 1609.344;
    private static final double FEET_TO_METERS = 0.3048;

    private Conditions setConditions() {
        String dtoWeatherType = dto.getWeatherType();
        if (dtoWeatherType.contains("clear")) {
            return new Conditions(true, CAVOK_VISIBILITY_METERS, 0, 0, 0, false, false);
        }
        if (dtoWeatherType.contains("real") || dtoWeatherType.equals("cvops")) {
            String sanitized = metarAVWX.getSanitized();
            int visMeters = parseVisibilityMeters();
            boolean fog = hasFog(sanitized);
            boolean dust = hasDust(sanitized);

            int fogThickness = 0;
            int fogVisibility = 0;
            if (fog) {
                fogThickness = 150;
                fogVisibility = Math.max(MIN_VISIBILITY_METERS, Math.min(visMeters, 3000));
            }

            int dustDensity = dust ? 3000 : 0;

            return new Conditions(true, visMeters, fogThickness, fogVisibility,
                    dustDensity, fog, dust);
        }
        return Conditions.skip();
    }

    private int parseVisibilityMeters() {
        if (metarAVWX.getVisibility() == null || metarAVWX.getVisibility().getValue() == null) {
            return CAVOK_VISIBILITY_METERS;
        }
        double raw = metarAVWX.getVisibility().getValue();
        String unit = metarAVWX.getUnits() != null ? metarAVWX.getUnits().getVisibility() : null;
        double meters;
        if (unit == null || "m".equalsIgnoreCase(unit)) {
            meters = raw;
        } else if ("sm".equalsIgnoreCase(unit)) {
            meters = raw * STATUTE_MILES_TO_METERS;
        } else if ("ft".equalsIgnoreCase(unit)) {
            meters = raw * FEET_TO_METERS;
        } else if ("km".equalsIgnoreCase(unit)) {
            meters = raw * 1000.0;
        } else {
            meters = raw;
        }
        int rounded = (int) Math.round(meters);
        return Math.max(MIN_VISIBILITY_METERS, Math.min(rounded, CAVOK_VISIBILITY_METERS));
    }

    private Time setTime(ZonedDateTime zonedDateTime) {
        return new Time(setHour(zonedDateTime), setDay(zonedDateTime), setMonth(zonedDateTime));
    }

    private Station setStation() {
        return new Station(setMetar(), setStationTempC(), setStationQnh());
    }

    private Wind setWind() {
        return new Wind(setWindSpeed(), setWindDirection());
    }

    private double setWindSpeed() {
        String dtoWeatherType = dto.getWeatherType();
        if (dtoWeatherType.contains("real") || dtoWeatherType.equals("cvops")) {
            return metarAVWX.getWindSpeed().flatMap(WindSpeed::getValue).orElse(0.0);
        } else {
            return 0.0;
        }
    }

    private double setWindDirection() {
        String dtoWeatherType = dto.getWeatherType();
        if (dtoWeatherType.contains("real") || dtoWeatherType.equals("cvops")) {
            return metarAVWX.getWindDirection().flatMap(WindDirection::getValue).orElse(0.0);
        } else {
            return 0.0;
        }
    }

    private double setStationTempC() {
        String dtoWeatherType = dto.getWeatherType();
        if (dtoWeatherType.contains("real") || dtoWeatherType.equals("cvops")) {
            return metarAVWX.getTemperature().flatMap(Temperature::getValue).orElse(ISA_TEMP_C);
        } else {
            return ISA_TEMP_C;
        }
    }

    private double setStationQnh() {
        String dtoWeatherType = dto.getWeatherType();
        if (dtoWeatherType.contains("real") || dtoWeatherType.equals("cvops")) {
            if (metarAVWX.getUnits().getAltimeter().equals("hPa")) {
                return metarAVWX.getAltimeter().getValue() / INHG_TO_HPA;
            } else {
                return metarAVWX.getAltimeter().getValue();
            }
        } else {
            return ISA_PRESSURE_INHG;
        }
    }

    private String setMetar() {
        String dtoWeatherType = dto.getWeatherType();
        if (dtoWeatherType.contains("clear")) {
            log.info("METAR set clear");
            return "";
        } else {
            if (metarAVWX.getMeta().getWarning() != null) {
                log.warning(metarAVWX.getMeta().getWarning());
            }
            String assignedMetar = metarAVWX.getSanitized();
            log.info("METAR: " + assignedMetar);
            return assignedMetar;
        }
    }

    private int setDay(@NotNull ZonedDateTime zonedDateTime) {
        return zonedDateTime.getDayOfMonth();
    }

    private int setMonth(@NotNull ZonedDateTime zonedDateTime) {
        return zonedDateTime.getMonthValue();
    }

    private float setHour(ZonedDateTime zonedDateTime) {
        float assignedHour;
        String dtoWeatherType = dto.getWeatherType();

        if (dtoWeatherType.equals("real") || dtoWeatherType.equals("clear")) {
            String nextEventTime = airplanClient.getNextEvtTime();
            if (nextEventTime != null) {
                dtoWeatherType = nextEventTime;
            }
        }

        if (dtoWeatherType.equals("real")) {
            if (zonedDateTime.getHour() + config.getTimeOffset() < 0) {
                assignedHour = (float) 24 + zonedDateTime.getHour() + config.getTimeOffset();
            } else {
                assignedHour = (float) zonedDateTime.getHour() + config.getTimeOffset();
            }
        } else if (dtoWeatherType.contains("cvops")) {
            int currentTimeInSecs = (int) Double.parseDouble(dto.getCurrentGameTime());
            if (currentTimeInSecs < 0) {
                currentTimeInSecs = 0;
            }
            List<Integer> listOfCVEventStarts = getCVEventStarts();
            int preEventTime = config.getPreEventTime();

            final int finalCurrentTimeInSecs = currentTimeInSecs;
            int closestEvent = listOfCVEventStarts
                    .stream()
                    .min(Comparator.comparingInt(a -> Math.abs(finalCurrentTimeInSecs - a)))
                    .orElse(0);

            if (currentTimeInSecs > listOfCVEventStarts.get(listOfCVEventStarts.size() - 1) + 1800){
                assignedHour = ((float)(listOfCVEventStarts.get(0) - preEventTime) / 3600) % 24;
            } else {
                assignedHour = ((float)(closestEvent - preEventTime) / 3600) % 24;
            }
        } else {
            Pattern pattern = Pattern.compile("(real|clear)(\\d{4})");
            Matcher matcher = pattern.matcher(dtoWeatherType);

            if (matcher.matches()) {
                String timeStr = matcher.group(2);
                int hour = Integer.parseInt(timeStr.substring(0, 2));
                int minute = Integer.parseInt(timeStr.substring(2, 4));
                assignedHour =  hour + minute / 60.0f;
            } else if ("clearNight".equals(dtoWeatherType)) {
                assignedHour = 0.0f;
            } else {
                assignedHour = 12.0f;
            }
        }
        return assignedHour;
    }

    private List<Integer> getCVEventStarts() {
        List<Integer> cvEventStarts = new ArrayList<>();
        int cyclicWindows = config.getCyclicWindows();
        int firstCyclicTimeInSecs = config.getFirstCyclicTimeInSecs();
        int cyclicLengthInSecs = config.getCyclicLength() * 60;

        for (int i = 0; i < cyclicWindows; i++) {
            cvEventStarts.add(firstCyclicTimeInSecs + (i * cyclicLengthInSecs));
        }

        return cvEventStarts;
    }

}
