local BuildMetar = {}

local THIS_FILE = DCSDynamicWeather.MODULE_NAME .. ".BuildMetar"
local STATION_REFERENCE_ZONE_NAME = DCSDynamicWeather.JSON.getValue("stationReferenceZoneName", DCSDynamicWeather.CONFIG_PATH)
local TIME_OFFSET = DCSDynamicWeather.JSON.getValue("time_offset", DCSDynamicWeather.CONFIG_PATH)

local STANDARD_PRESSURE_PASCAL = 101325
local PASCALS_TO_INHG = 0.0295299830714
local METERS_TO_FEET = 3.28084
local STD_PRESSURE_MILLIBAR = 1013.25
local METERS_TO_KNOTS = 1.94384
local FEET_TO_STATUTORY_MILES = 0.000189394
local PASCAL_TO_MILLIBAR = 0.01
local ZERO_CELCIUS_IN_KELVIN = 273.15

function BuildMetar.getNearestAirbasePoint()
    local THIS_METHOD = THIS_FILE .. ".getNearestAirbasePoint"

    local stationReference = trigger.misc.getZone(STATION_REFERENCE_ZONE_NAME)

    local searchVolume = {
        id = world.VolumeType.SPHERE,
        params = {
            point = stationReference.point,
            radius = stationReference.radius
        }
    }

    local airbase
    local located = function(located)
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Nearest airbase point found.")
        airbase = located
    end
    world.searchObjects(Object.Category.BASE, searchVolume, located)
    if airbase then
        return airbase:getPoint()
    end

    DCSDynamicWeather.Logger.info(THIS_METHOD, "No nearest airbase point found, setting reference to StationReference.")
    return stationReference.point
end

function BuildMetar.getWind(referencePoint)
    local THIS_METHOD = THIS_FILE .. ".getWind"

    local localReferencePoint = {}
    localReferencePoint.x = referencePoint.x
    localReferencePoint.y = land.getHeight({ x = localReferencePoint.x, y = referencePoint.z }) + 15 -- Wind will return 0 within 10m of ground
    localReferencePoint.z = referencePoint.z
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Wind Reference point: { x = " .. localReferencePoint.x .. ", y = " .. localReferencePoint.y .. ", z = " .. localReferencePoint.z .. " }")

    local windVec = atmosphere.getWind(localReferencePoint)
    local windSpeed = math.sqrt((windVec.z) ^ 2 + (windVec.x) ^ 2)
    windSpeed = windSpeed * METERS_TO_KNOTS -- Meters to Knots


    local windDirection = math.deg(math.atan2(windVec.z, windVec.x))


    -- Clamp wind direction between 0 and 360
    if windDirection < 0 then
        windDirection = windDirection + 360
    end
    if windDirection > 180 then
        windDirection = windDirection - 180
    else
        windDirection = windDirection + 180
    end

    windSpeed = math.floor(windSpeed + 0.5)
    windDirection = math.floor(windDirection + 0.5)
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Wind Speed: " .. windSpeed)
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Wind Direction: " .. windDirection)

    -- Add leading zeroes
    local windDirectionLeadingZeroes
    if windSpeed == 0 then
        windDirectionLeadingZeroes = "000"
    elseif windDirection < 10 then
        windDirectionLeadingZeroes = "00" .. windDirection
    elseif windDirection < 100 then
        windDirectionLeadingZeroes = "0" .. windDirection
    else
        windDirectionLeadingZeroes = windDirection
    end

    local windSpeedLeadingZeroes
    if windSpeed < 10 then
        windSpeedLeadingZeroes = "0" .. windSpeed
    else
        windSpeedLeadingZeroes = windSpeed
    end

    return windDirectionLeadingZeroes .. windSpeedLeadingZeroes .. "KT"
end

function BuildMetar.getDayAndTimeZulu()
    local THIS_METHOD = THIS_FILE .. ".getDayAndTimeLocal"

    -- Reports mission LOCAL time (suffix "L"). TIME_OFFSET is already baked into
    -- the mission start_time by weather-update, so do not re-apply it here.

    local time = timer.getAbsTime()
    local day = env.mission.date.Day
    local hours = math.floor(time / 3600)
    local minutes = math.floor(((time / 60) - (hours * 60)) + 0.5)

    if hours >= 24 then
        day = day + math.floor(hours / 24)
        hours = hours % 24
    end

    DCSDynamicWeather.Logger.info(THIS_METHOD, "Local Time: Day: " .. day .. " Hour: " .. hours .. " Minute: " .. minutes)

    return string.format("%02d%02d%02dL", day, hours, minutes)
end

function BuildMetar.getVisibility()
    local weather = env.mission.weather
    local visibilityM = weather.visibility.distance

    if weather.enable_fog == true then
        local fogVisibilityM = weather.fog.visibility
        if fogVisibilityM < visibilityM then
            visibilityM = fogVisibilityM
        end
    end

    local visibilitySM = visibilityM * METERS_TO_FEET * FEET_TO_STATUTORY_MILES
    if visibilitySM < 0.25 then
        return "1/4SM"
    elseif visibilitySM < 0.50 then
        return "1/2SM"
    elseif visibilitySM < 1 then
        return "3/4SM"
    elseif visibilitySM < 10 then
        return math.floor(visibilitySM + 0.5) .. "SM"
    else
        return "10SM"
    end
end

function BuildMetar.getWeatherMods() -- TODO: TS = Thunderstorm, DS = Dust Storm, -RA/RA/+RA
    local weatherMods = ""
    local weather = env.mission.weather

    if weather.enable_fog == true then
        local fog = weather.fog
        local fogVisibilityFt = fog.visibility * METERS_TO_FEET

        if fogVisibilityFt < 3300 then
            if fogVisibilityFt < 1300 then
                weatherMods = "+"
            end
            weatherMods = weatherMods .. "FG"
        else
            weatherMods = "BR"
        end
    end

    return weatherMods
end

-- Preset name -> list of {code, altHundredsFt} layers.
-- Mirrors weather.DecodePreset in github.com/evogelsa/DCS-real-weather.
BuildMetar.DECODE_PRESET = {
    ["Preset1"]        = { { code = "FEW", alt =  70 } },
    ["Preset2"]        = { { code = "FEW", alt =  80 }, { code = "SCT", alt = 230 } },
    ["Preset3"]        = { { code = "SCT", alt =  80 }, { code = "FEW", alt = 210 } },
    ["Preset4"]        = { { code = "SCT", alt =  80 }, { code = "SCT", alt = 240 } },
    ["Preset5"]        = { { code = "SCT", alt = 140 }, { code = "FEW", alt = 270 }, { code = "BKN", alt = 400 } },
    ["Preset6"]        = { { code = "SCT", alt =  80 }, { code = "FEW", alt = 400 } },
    ["Preset7"]        = { { code = "BKN", alt =  75 }, { code = "SCT", alt = 210 }, { code = "SCT", alt = 400 } },
    ["Preset8"]        = { { code = "SCT", alt = 180 }, { code = "FEW", alt = 360 }, { code = "FEW", alt = 400 } },
    ["Preset9"]        = { { code = "BKN", alt =  75 }, { code = "SCT", alt = 200 }, { code = "FEW", alt = 410 } },
    ["Preset10"]       = { { code = "SCT", alt = 180 }, { code = "FEW", alt = 360 }, { code = "FEW", alt = 400 } },
    ["Preset11"]       = { { code = "BKN", alt = 180 }, { code = "BKN", alt = 320 }, { code = "FEW", alt = 410 } },
    ["Preset12"]       = { { code = "BKN", alt = 120 }, { code = "SCT", alt = 220 }, { code = "FEW", alt = 410 } },
    ["Preset13"]       = { { code = "BKN", alt = 120 }, { code = "BKN", alt = 260 }, { code = "FEW", alt = 410 } },
    ["Preset14"]       = { { code = "BKN", alt =  70 }, { code = "FEW", alt = 410 } },
    ["Preset15"]       = { { code = "SCT", alt = 140 }, { code = "BKN", alt = 240 }, { code = "FEW", alt = 400 } },
    ["Preset16"]       = { { code = "BKN", alt = 140 }, { code = "BKN", alt = 280 }, { code = "FEW", alt = 400 } },
    ["Preset17"]       = { { code = "BKN", alt =  70 }, { code = "BKN", alt = 200 }, { code = "BKN", alt = 320 } },
    ["Preset18"]       = { { code = "BKN", alt = 130 }, { code = "BKN", alt = 250 }, { code = "BKN", alt = 380 } },
    ["Preset19"]       = { { code = "OVC", alt =  90 }, { code = "BKN", alt = 230 }, { code = "BKN", alt = 310 } },
    ["Preset20"]       = { { code = "BKN", alt = 130 }, { code = "BKN", alt = 280 }, { code = "FEW", alt = 380 } },
    ["Preset21"]       = { { code = "BKN", alt =  70 }, { code = "OVC", alt = 170 } },
    ["Preset22"]       = { { code = "OVC", alt =  70 }, { code = "BKN", alt = 170 } },
    ["Preset23"]       = { { code = "OVC", alt = 110 }, { code = "BKN", alt = 180 }, { code = "SCT", alt = 320 } },
    ["Preset24"]       = { { code = "OVC", alt =  30 }, { code = "OVC", alt = 170 }, { code = "BKN", alt = 340 } },
    ["Preset25"]       = { { code = "OVC", alt = 120 }, { code = "OVC", alt = 220 }, { code = "OVC", alt = 400 } },
    ["Preset26"]       = { { code = "OVC", alt =  90 }, { code = "BKN", alt = 230 }, { code = "SCT", alt = 320 } },
    ["Preset27"]       = { { code = "OVC", alt =  80 }, { code = "BKN", alt = 250 }, { code = "BKN", alt = 340 } },
    ["RainyPreset1"]   = { { code = "OVC", alt =  30 }, { code = "OVC", alt = 280 }, { code = "FEW", alt = 400 } },
    ["RainyPreset2"]   = { { code = "OVC", alt =  30 }, { code = "SCT", alt = 180 }, { code = "FEW", alt = 400 } },
    ["RainyPreset3"]   = { { code = "OVC", alt =  60 }, { code = "OVC", alt = 190 }, { code = "SCT", alt = 340 } },
    ["RainyPreset4"]   = { { code = "SCT", alt =  80 }, { code = "FEW", alt = 360 } },
    ["RainyPreset5"]   = { { code = "BKN", alt =  70 }, { code = "BKN", alt = 200 }, { code = "BKN", alt = 320 } },
    ["RainyPreset6"]   = { { code = "OVC", alt =  90 }, { code = "BKN", alt = 230 }, { code = "BKN", alt = 310 } },
    ["NEWRAINPRESET4"] = { { code = "SCT", alt =  80 }, { code = "SCT", alt = 120 } },
}

local function isRainyPreset(preset)
    return preset:find("^RainyPreset") ~= nil or preset == "NEWRAINPRESET4"
end

function BuildMetar.getCloudCover(referencePoint)
    local THIS_METHOD = THIS_FILE .. ".getCloudCover"
    local clouds = env.mission.weather.clouds
    local preset = clouds.preset

    if preset == nil or preset == "" then
        return "CAVOK"
    end

    local decode = BuildMetar.DECODE_PRESET[preset]
    if decode == nil then
        DCSDynamicWeather.Logger.warning(THIS_METHOD, "Unknown cloud preset: " .. tostring(preset) .. " -- falling back to CAVOK")
        return "CAVOK"
    end

    -- Mission clouds.base is meters MSL; METAR layers are AGL in hundreds of feet.
    local stationElevM = land.getHeight({ x = referencePoint.x, y = referencePoint.z })
    local baseAglFt = (clouds.base - stationElevM) * METERS_TO_FEET
    local baseHundredsFt = math.floor(baseAglFt / 100 + 0.5)

    -- Shift every layer by the delta between the mission base and the preset's
    -- natural first-layer altitude, so the lowest layer matches what DCS renders.
    local firstLayerHundredsFt = decode[1].alt
    local delta = baseHundredsFt - firstLayerHundredsFt

    local parts = {}
    if isRainyPreset(preset) then
        parts[#parts + 1] = "RA"
    end

    for _, layer in ipairs(decode) do
        local alt = layer.alt + delta
        if alt < 1 then alt = 1 end
        if alt > 600 then alt = 600 end
        parts[#parts + 1] = string.format("%s%03d", layer.code, alt)
    end

    return table.concat(parts, " ")
end

function BuildMetar.getPressureAltitude(referencePoint)
    local THIS_METHOD = THIS_FILE .. ".getPressureAltitude"

    local _, qfeHPA = atmosphere.getTemperatureAndPressure(referencePoint)
    local qfeMB = qfeHPA * PASCAL_TO_MILLIBAR
    local pressureAltitude = 145366.45 * (1 - math.pow((qfeMB / STD_PRESSURE_MILLIBAR), 0.190284))

    DCSDynamicWeather.Logger.info(THIS_METHOD, "Pressure Altitude: " .. pressureAltitude)
    return pressureAltitude
end

function BuildMetar.getQnh(referencePoint)
    local pressureAltitude = BuildMetar.getPressureAltitude(referencePoint)
    local altitudeDifference = (referencePoint.y * METERS_TO_FEET) - pressureAltitude
    local tempCorrectedQNHPasc = ((altitudeDifference / 27) * 100) + STANDARD_PRESSURE_PASCAL
    local qnhInHg = tempCorrectedQNHPasc * PASCALS_TO_INHG

    return "A" .. math.floor(qnhInHg + 0.5)
end

function BuildMetar.getTempDew(referencePoint)
    -- TODO Improve Dew Calculation, not matching real world, maybe get from Data file instead?
    local THIS_METHOD = THIS_FILE .. ".getTempDew"

    local localReferencePoint = {}
    localReferencePoint.x = referencePoint.x
    localReferencePoint.y = 0
    localReferencePoint.z = referencePoint.z
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Temp/Dew Reference point: { x = " .. localReferencePoint.x .. ", y = " .. localReferencePoint.y .. ", z = " .. localReferencePoint.z .. " }")

    local clouds = env.mission.weather.clouds
    local temperature, _ = atmosphere.getTemperatureAndPressure(localReferencePoint)
    temperature = temperature - ZERO_CELCIUS_IN_KELVIN

    -- Calculate Dew Point
    local cloudBase = clouds.base * METERS_TO_FEET
    local dew = temperature - ((cloudBase / 1000) * 2.5)
    dew = math.floor(dew + 0.5)

    -- Absolute Values but prefix M per METAR formatting.
    temperature = math.floor(temperature + 0.5)
    if temperature < 0 then
        temperature = math.abs(temperature)
        temperature = "M" .. temperature
    end
    if dew < 0 then
        dew = math.abs(dew)
        dew = "M" .. dew
    end

    return temperature .. "/" .. dew
end

function BuildMetar.getICAO()
    local THIS_METHOD = THIS_FILE .. ".getStationId"
    local icao = DCSDynamicWeather.JSON.getValue("icao", DCSDynamicWeather.DTO_PATH)
    if (icao == "") then
        DCSDynamicWeather.Logger.warning(THIS_METHOD, "ICAO not found.")
        return "UNKN"
    else
        return icao
    end
end

function BuildMetar.writeAirbaseCoordinatesToDataFile(referencePoint)
    local THIS_METHOD = THIS_FILE .. ".writeAirbaseCoordinatesToDataFile"

    local stationLatitude, stationLongitude, _ = coord.LOtoLL(referencePoint)
    DCSDynamicWeather.JSON.setValue("station_latitude", stationLatitude, DCSDynamicWeather.DTO_PATH)
    DCSDynamicWeather.JSON.setValue("station_longitude", stationLongitude, DCSDynamicWeather.DTO_PATH)
end

function BuildMetar.outputMetar(metar)
    local THIS_METHOD = THIS_FILE .. ".outputMetar"
    DCSDynamicWeather.JSON.setValue("metar", metar, DCSDynamicWeather.DTO_PATH)
    DCSDynamicWeather.JAR.execute("weather-output")
end

local function resetMission()
    DCSDynamicWeather.Mission.loadNextMission()
end

function BuildMetar.main()
    local referencePoint = BuildMetar.getNearestAirbasePoint()
    BuildMetar.writeAirbaseCoordinatesToDataFile(referencePoint)

    local icao = BuildMetar.getICAO()
    DCSDynamicWeather.Logger.info(THIS_FILE, "ICAO: " .. icao)

    local dayAndTimeZulu = BuildMetar.getDayAndTimeZulu()
    DCSDynamicWeather.Logger.info(THIS_FILE, "Day and Time Zulu: " .. dayAndTimeZulu)

    local wind = BuildMetar.getWind(referencePoint)
    DCSDynamicWeather.Logger.info(THIS_FILE, "Wind: " .. wind)

    local visibility = BuildMetar.getVisibility()
    DCSDynamicWeather.Logger.info(THIS_FILE, "Visibility: " .. visibility)

    local weatherMods = BuildMetar.getWeatherMods()
    DCSDynamicWeather.Logger.info(THIS_FILE, "Weather Mods: " .. weatherMods)

    local cloudCover = BuildMetar.getCloudCover(referencePoint)
    DCSDynamicWeather.Logger.info(THIS_FILE, "Cloud Cover: " .. cloudCover)

    local tempDew = BuildMetar.getTempDew(referencePoint)
    DCSDynamicWeather.Logger.info(THIS_FILE, "Temp/Dew: " .. tempDew)

    local qnh = BuildMetar.getQnh(referencePoint)
    DCSDynamicWeather.Logger.info(THIS_FILE, "QNH: " .. qnh)

    local metar = icao .. " " ..
            dayAndTimeZulu .. " " ..
            wind .. " " ..
            visibility .. " " ..
            weatherMods .. " " ..
            cloudCover .. " " ..
            tempDew .. " " ..
            qnh

    DCSDynamicWeather.Logger.info(THIS_FILE, "METAR: " .. metar)

    if icao ~= "UNKN" then
        BuildMetar.outputMetar(metar) -- TODO: check time of last update instead?
    else
        timer.scheduleFunction(resetMission, {}, timer.getTime() + 2)
    end
end
BuildMetar.main()