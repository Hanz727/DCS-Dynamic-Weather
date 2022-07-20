package com.marlan.weatherupdate.service;

import com.marlan.weatherupdate.model.AVWXWeather;
import com.marlan.weatherupdate.utilities.ZoneIdFromIcao;
import lombok.NoArgsConstructor;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Random;

import static java.lang.System.out;

@NoArgsConstructor
public class MissionHandler {
    public String editMission(String mission, AVWXWeather weatherAVWX) throws NoSuchAlgorithmException {
        java.time.ZonedDateTime zonedDateTime = java.time.ZonedDateTime.now(java.time.ZoneId.of(ZoneIdFromIcao.getZoneId(weatherAVWX.getStation())));
        double qnh_mmHg = weatherAVWX.getAltimeter().getValue() * 25.4;
        double tempC = weatherAVWX.getTemperature().getValue();
        double windSpeed = Math.min(weatherAVWX.getWindSpeed().getValue() / 1.944, 15);
        double windDir = invertWindDirection(weatherAVWX.getWindDirection().getValue()); // Inverted because DCS is backwards.
        String cloudsPreset = buildCloudsPreset(selectCloudsPresetSuffix(weatherAVWX.getSanitized()));

        if (!mission.contains("[\"preset\"]")) {
            mission = mission.replaceAll(
                    "(\\[\"iprecptns\"].*)\n",
                    "[\"iprecptns\"] = 0,\n            [\"preset\"] = \"\\$cloudsPreset\",\n")
                    .replace("$cloudsPreset", cloudsPreset);
        } else {
            mission = mission.replaceAll(
                    "(\\[\"preset\"].*)\n",
                    "[\"preset\"] = \"\\$cloudsPreset\",\n"
                    .replace("$cloudsPreset", cloudsPreset));
        }
        out.println("Clouds Preset set to: " + cloudsPreset);

        mission = mission.replaceAll("\\[\"at8000\"]\\s+=\\s+\\{([^}]*)",
                "[\"at8000\"] =\n            {\n                [\"speed\"] = $wind8000Speed,\n                [\"dir\"] = $wind8000Dir,\n            "
                .replace("$wind8000Speed", Double.toString(windSpeed))
                .replace("$wind8000Dir", Double.toString(windDir)));
        out.println("Wind at 8000 set to: " + windSpeed + " m/s (" + windSpeed * 1.944 + " kts)");
        out.println("Wind at 8000 set to: " + windDir + " degrees");

        mission = mission.replaceAll("\\[\"at2000\"]\\s+=\\s+\\{([^}]*)",
                "[\"at2000\"] =\n            {\n                [\"speed\"] = $wind2000Speed,\n                [\"dir\"] = $wind2000Dir,\n            "
                        .replace("$wind2000Speed", Double.toString(windSpeed))
                        .replace("$wind2000Dir", Double.toString(windDir)));
        out.println("Wind at 2000 set to: " + windSpeed + " m/s (" + windSpeed * 1.944 + " kts)");
        out.println("Wind at 2000 set to: " + windDir + " degrees");

        mission = mission.replaceAll("\\[\"atGround\"]\\s+=\\s+\\{([^}]*)",
                "[\"atGround\"] =\n            {\n                [\"speed\"] = $windGroundSpeed,\n                [\"dir\"] = $windGroundDir,\n            "
                        .replace("$windGroundSpeed", Double.toString(windSpeed))
                        .replace("$windGroundDir", Double.toString(windDir)));
        out.println("Wind at Ground set to: " + windSpeed + " m/s (" + windSpeed * 1.944 + " kts)");
        out.println("Wind at Ground set to: " + windDir + " degrees");

        mission = mission.replaceAll("(?<=\\[\"currentKey\"]\\s{1,5}=\\s{1,5}.{1,100}\n)(.*)", "    [\"start_time\"] = $startTime,".replace("$startTime", Integer.toString(zonedDateTime.getHour()*3600)));
        out.println("Start Time set to: " + zonedDateTime.getHour()*3600 + "s (" + zonedDateTime.getHour() + "h)");

        mission = mission.replaceAll("(\\[\"Day\"].*)\n", "[\"Day\"] = \\$day,\n".replace("$day", Integer.toString(Integer.parseInt(weatherAVWX.getTime().getRepr().substring(0, 2)))));
        out.println("Day set to: " + zonedDateTime.getDayOfMonth());

        mission = mission.replaceAll("(\\[\"Month\"].*)\n", "[\"Month\"] = \\$month,\n".replace("$month", Integer.toString(Integer.parseInt(weatherAVWX.getTime().getDt().substring(5, 7)))));
        out.println("Month set to: " + zonedDateTime.getMonthValue());

        mission = mission.replaceAll("(\\[\"temperature\"].*)\n", "[\"temperature\"] = \\$tempC,\n".replace("$tempC", Double.toString(tempC)));
        out.println("Temperature set to: " + tempC + "C");

        mission = mission.replaceAll("(\\[\"qnh\"].*)\n", "[\"qnh\"] = \\$qnh,\n".replace("$qnh", Double.toString(qnh_mmHg)));
        out.println("QNH set to: " + qnh_mmHg + "mmHg (" + qnh_mmHg/25.4 + "inHg)");

        return mission;
    }

    private String buildCloudsPreset(int cloudsPresetSuffix){
        if (cloudsPresetSuffix == 0) {
            return "nil";
        } else {
            if (cloudsPresetSuffix > 27) {
                return "RainyPreset" + cloudsPresetSuffix % 27; // Converts Presets28-30 to RainyPreset1-3
            } else {
                return "Preset" + cloudsPresetSuffix;
            }
        }
    }

    private int selectCloudsPresetSuffix(String metar) throws NoSuchAlgorithmException {
        Random random = SecureRandom.getInstanceStrong();
        if (metar.contains("SKC") || metar.contains("NCD")) return 0;
        if (metar.contains("CLR") || metar.contains("NSC") || metar.contains("CAVOK")) return random.nextInt(3);
        if (metar.contains("OVC")) return random.nextInt(10) + 21;
        if (metar.contains("BKN")) return random.nextInt(8) + 13;
        if (metar.contains("SCT")) return random.nextInt(10) + 3;
        if (metar.contains("FEW")) return random.nextInt(5) + 1;
        return 0;
    }

    private double invertWindDirection(double windDirection) {
        if (windDirection >= 0 && windDirection <= 180) {
            return windDirection + 180;
        } else {
            return windDirection - 180;
        }
    }
}
