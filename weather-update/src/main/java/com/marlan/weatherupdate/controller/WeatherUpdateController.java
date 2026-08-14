package com.marlan.weatherupdate.controller;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.marlan.shared.utilities.FileHandler;
import com.marlan.shared.utilities.Log;
import com.marlan.weatherupdate.model.metar.AVWXMetar;
import com.marlan.weatherupdate.model.station.AVWXStation;
import com.marlan.weatherupdate.service.airplanclient.AirplanClient;
import com.marlan.weatherupdate.service.avwxclient.AVWXClient;
import com.marlan.weatherupdate.service.destruction.ChangelogDiscordPoster;
import com.marlan.weatherupdate.service.destruction.DestructionService;
import com.marlan.weatherupdate.service.missioneditor.MissionEditor;
import com.marlan.weatherupdate.service.missioneditor.MissionValues;
import com.marlan.weatherupdate.utilities.MizUtility;
import com.marlan.shared.model.Config;
import com.marlan.shared.model.DTO;

/**
 * Controller for Weather Update module
 */
public class WeatherUpdateController {
    private static final Log log = Log.getInstance();

    private WeatherUpdateController() {
    }

    /**
     * @param WORKING_DIR Working directory of the program which is the location of this file (which should also include
     *                    the other folders and files needed for the program to run e.g. data, constants, secrets, etc.)
     */
    public static void run(final String WORKING_DIR) {
        log.info("Working Directory: " + WORKING_DIR);
        final Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

        final String MISSION_FILE = "mission";
        final String DTO_PATH = "data\\dto.json";
        final String CONFIG_PATH = "config.json";

        String dataContent = FileHandler.readFile(WORKING_DIR, DTO_PATH);
        String configFileContent = FileHandler.readFile(WORKING_DIR, CONFIG_PATH);

        DTO dto = gson.fromJson(dataContent, DTO.class);
        Config config = gson.fromJson(configFileContent, Config.class);

        AVWXClient avwxClient = new AVWXClient(WORKING_DIR);
        AVWXMetar metarAVWX = gson.fromJson(avwxClient.getMetar(dto).body(), AVWXMetar.class);
        AVWXStation stationAVWX = gson.fromJson(avwxClient.getStation(metarAVWX).body(), AVWXStation.class);

        dto.setIcao(stationAVWX.getIcao());

        String weatherType = dto.getWeatherType();
        if (weatherType != null && (weatherType.contains("real") || weatherType.equals("cvops"))) {
            int windSpeedKt = (int) Math.round(metarAVWX.getWindSpeed()
                    .flatMap(com.marlan.weatherupdate.model.metar.fields.WindSpeed::getValue).orElse(0.0));
            int windDirectionDeg = (int) Math.round(metarAVWX.getWindDirection()
                    .flatMap(com.marlan.weatherupdate.model.metar.fields.WindDirection::getValue).orElse(0.0));
            dto.setWindSpeedKt(Integer.toString(windSpeedKt));
            dto.setWindDirectionDeg(Integer.toString(windDirectionDeg));
        } else {
            dto.setWindSpeedKt("");
            dto.setWindDirectionDeg("");
        }

        FileHandler.writeJSON(WORKING_DIR, DTO_PATH, dto);

        MizUtility mizUtility = new MizUtility(config);
        mizUtility.extractMission(WORKING_DIR, dto.getMission());
        String missionContent = FileHandler.readFile(WORKING_DIR, MISSION_FILE);

        if (missionContent.isEmpty()) {
            log.error("Mission extraction failed or produced empty file; aborting to preserve " + dto.getMission());
            return;
        }

        MissionValues missionValues = new MissionValues(config, dto, stationAVWX, metarAVWX, new AirplanClient());
        MissionEditor missionEditor = new MissionEditor(stationAVWX, missionValues);

        String replacedMissionContent = missionEditor.editMission(missionContent);

        // Battle-damage persistence (CVIC backend): removes known-dead units
        // and bakes scenery-destruction zones from weapon impacts. Unrelated
        // to weather and much deeper miz surgery, so it lives in its own
        // service; it no-ops for non-deployment missions and on any failure.
        DestructionService destructionService = new DestructionService(WORKING_DIR, config);
        DestructionService.Result damage =
                destructionService.apply(replacedMissionContent, dto.getMission());
        replacedMissionContent = damage.missionContent();

        FileHandler.overwriteFile(WORKING_DIR, MISSION_FILE, replacedMissionContent);

        boolean mizUpdated = mizUtility.updateMiz(WORKING_DIR, dto.getMission(), MISSION_FILE);

        FileHandler.deleteFile(WORKING_DIR, MISSION_FILE);

        // Changelog + updated miz to the mission editors' webhook — ONLY when
        // units/zones actually changed this run (weather-only updates never
        // post here; METOC covers those) and the rebuilt archive validated.
        if (mizUpdated && damage.changelogEntry() != null && config.isOutputMizToDiscord()) {
            new ChangelogDiscordPoster(WORKING_DIR)
                    .post(damage.changelogEntry(), WORKING_DIR + dto.getMission());
        }
    }

}
