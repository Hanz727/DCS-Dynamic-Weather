local DCS_DYNAMIC_WEATHER_HOOK_VERSION = "1.2.2"
DCSDynamicWeather = {}
local DCSDynamicWeatherCallbacks = {}
DCSDynamicWeather.Logger = {}

local THIS_FILE = "DCSDynamicWeatherHook"
local DCS_ROOT = lfs.currentdir()
local DCS_SG = lfs.writedir()

local missionLoaded = false
local simulationStartTime = DCS.getRealTime()
local initialPauseState = false
local waiting = false
local checkRestartTime = 0
local initialPauseStateSet = false
local injectedRestart = false

-- UDP Server Configuration
local UDP_PORT = 42674
local udpSocket = nil

-- ===== Logger =====

function DCSDynamicWeather.Logger.info(logSource, message)
    DCSDynamicWeather.Logger.printLog(logSource, message, "INFO    ")
end

function DCSDynamicWeather.Logger.warning(logSource, message)
    DCSDynamicWeather.Logger.printLog(logSource, message, "WARNING ")
end

function DCSDynamicWeather.Logger.error(logSource, message)
    DCSDynamicWeather.Logger.printLog(logSource, message, "ERROR   ")
end

function DCSDynamicWeather.Logger.printLog(logSource, message, level)
    local time = os.date("%Y-%m-%d %H:%M:%S ")
    local logFile = io.open(DCS_SG .. "Logs\\" .. THIS_FILE .. ".log", "a")
    io.write(logFile, time .. level .. "[" .. logSource .. "]: " .. message .. "\n")
    io.flush(logFile)
    io.close(logFile)
end

-- ===== Utilities =====

function DCSDynamicWeather.fileExists(file)
    local f = io.open(file, "rb")
    if f then
        io.close(f)
    end
    return f ~= nil
end

function DCSDynamicWeather.injectCodeStringToScriptEnv(code)
    local THIS_METHOD = "DCSDynamicWeatherHook.injectCode"

    local successful, err = pcall(net.dostring_in, "mission", code)
    if not successful then
        DCSDynamicWeather.Logger.error(THIS_METHOD, "Failed to inject: \"" .. code .. "\" with error: " .. err)
    else
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Injected: \"" .. code .. "\"")
    end
end

function DCSDynamicWeather.getRestartTimeInSeconds()
    return 3600 -- TODO: Make this configurable
end

-- ===== UDP: helpers (defined first so they are in scope below) =====

local function getPlayerCount()
    local THIS_METHOD = "getPlayerCount"
    local players = net.get_player_list()
    if not players then
        DCSDynamicWeather.Logger.warning(THIS_METHOD, "net.get_player_list() returned nil")
        return 0
    end
    local count = 0
    for _ in pairs(players) do
        count = count + 1
    end
    -- Subtract 1 for the server itself
    local result = count > 0 and count - 1 or 0
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Player count: " .. result .. " (raw list size: " .. count .. ")")
    return result
end

local function getPlayerNames()
    local THIS_METHOD = "getPlayerNames"
    local players = net.get_player_list()
    if not players then
        DCSDynamicWeather.Logger.warning(THIS_METHOD, "net.get_player_list() returned nil")
        return {}
    end
    local names = {}
    for _, id in pairs(players) do
        -- Skip the server slot (id 1 is the host/server)
        if id ~= 1 then
            local name = net.get_player_info(id, "name")
            if name and name ~= "" then
                names[#names + 1] = name
            end
        end
    end
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Player names: " .. table.concat(names, ", "))
    return names
end

-- Returns the mission time-of-day as HH:MM:SS (mission start time + elapsed model time)
local function getMissionTimeOfDay()
    -- DCS.getMissionStartTime() does not exist in the GameGUI hooks env; read the
    -- start-of-day from the loaded mission table instead.
    local startOfDay = 0 -- seconds since midnight at mission start
    local ok, mission = pcall(DCS.getCurrentMission)
    if ok and mission and mission.mission and mission.mission.start_time then
        startOfDay = mission.mission.start_time
    end
    local elapsed = DCS.getModelTime() or 0           -- seconds of mission elapsed
    local secondsOfDay = math.floor(startOfDay + elapsed) % 86400
    local h = math.floor(secondsOfDay / 3600)
    local m = math.floor((secondsOfDay % 3600) / 60)
    local s = secondsOfDay % 60
    return string.format("%02d:%02d:%02d", h, m, s)
end

local function isValidWeatherType(weatherType)
    local THIS_METHOD = "isValidWeatherType"
    -- Valid formats: real, clear, realHHMM, clearHHMM, cvops, cvopsclear
    if weatherType == "real" or weatherType == "clear" or
       weatherType == "cvops" or weatherType == "cvopsclear" then
        DCSDynamicWeather.Logger.info(THIS_METHOD, "'" .. weatherType .. "' is valid (exact match)")
        return true
    end
    if string.match(weatherType, "^real%d%d%d%d$") or
       string.match(weatherType, "^clear%d%d%d%d$") then
        DCSDynamicWeather.Logger.info(THIS_METHOD, "'" .. weatherType .. "' is valid (pattern match)")
        return true
    end
    DCSDynamicWeather.Logger.warning(THIS_METHOD, "'" .. weatherType .. "' is NOT a valid weather type")
    return false
end

function DCSDynamicWeather.restartWithWeather(weatherType)
    local THIS_METHOD = "restartWithWeather"
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Triggering restart with weather: " .. weatherType)
    local code = [[a_do_script("DCSDynamicWeather.Mission.loadNextMission(']] .. weatherType .. [[')")]]
    DCSDynamicWeather.injectCodeStringToScriptEnv(code)
end

local function handleUDPMessage(data, ip, port)
    local THIS_METHOD = "handleUDPMessage"
    data = string.gsub(data, "[\r\n]", "") -- Trim newlines
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Received: '" .. data .. "' from " .. ip .. ":" .. port)

    local cmd, arg = string.match(data, "^(%w+):?(.*)$")
    cmd = cmd and string.lower(cmd) or ""
    arg = arg or ""
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Parsed cmd='" .. cmd .. "' arg='" .. arg .. "'")

    local response = ""

    if cmd == "players" then
        response = tostring(getPlayerCount())

    elseif cmd == "restart" then
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Restart requested. missionLoaded=" .. tostring(missionLoaded) .. " weatherType='" .. arg .. "'")
        if not missionLoaded then
            response = "error:no mission loaded"
        elseif arg == "" then
            response = "error:missing weather type"
        elseif not isValidWeatherType(arg) then
            response = "error:invalid weather type"
        else
            DCSDynamicWeather.restartWithWeather(arg)
            response = "ok"
        end

    elseif cmd == "status" then
        local uptime = math.floor(DCS.getRealTime() - simulationStartTime)
        local mission = DCS.getMissionName() or "none"
        local players = getPlayerCount()
        local loaded = missionLoaded and "true" or "false"
        response = string.format("%s|%d|%d|%s", mission, uptime, players, loaded)
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Status response: " .. response)

    elseif cmd == "info" then
        local mission = DCS.getMissionName() or "none"
        local uptime = math.floor(DCS.getRealTime() - simulationStartTime)
        local missionTime = getMissionTimeOfDay()
        local names = getPlayerNames()
        local playerList = table.concat(names, ",")
        response = string.format("%s|%d|%s|%s", mission, uptime, missionTime, playerList)
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Info response: " .. response)

    else
        DCSDynamicWeather.Logger.warning(THIS_METHOD, "Unknown command: '" .. cmd .. "'")
        response = "error:unknown command"
    end

    DCSDynamicWeather.Logger.info(THIS_METHOD, "Sending response: '" .. response .. "' to " .. ip .. ":" .. port)
    local ok, err = udpSocket:sendto(response .. "\n", ip, port)
    if not ok then
        DCSDynamicWeather.Logger.error(THIS_METHOD, "sendto failed: " .. tostring(err))
    end
end

-- forward-declare so onSimulationFrame can reference it before the definition below
local pollUDP

-- ===== Callbacks =====

function DCSDynamicWeatherCallbacks.onMissionLoadEnd()
    local THIS_METHOD = "DCSDynamicWeatherCallbacks.onMissionLoadEnd"

    if not initialPauseStateSet then
        initialPauseState = DCS.getPause()
    end
    waiting = false
    injectedRestart = false

    DCSDynamicWeather.Logger.info(THIS_METHOD, "Initial pause state: " .. tostring(initialPauseState))
    if initialPauseState then
        DCS.setPause(false)
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Pause State set to: " .. tostring(false))
    end

    local missionDesc = DCS.getMissionDescription()
    if not string.find(missionDesc, "DCSDW") then
        DCSDynamicWeather.Logger.info(THIS_METHOD, "\"DCSDW\" not found in mission description (situation), skipping.")
        return
    end

    missionLoaded = true
    simulationStartTime = DCS.getRealTime()
    DCSDynamicWeather.injectMissionNameToScriptEnv()
end

function DCSDynamicWeatherCallbacks.onTriggerMessage(message, _, _)
    local THIS_METHOD = "DCSDynamicWeatherCallbacks.onTriggerMessage"

    if not (DCS.isServer() and DCS.isMultiplayer()) then
        DCSDynamicWeather.Logger.warning(THIS_METHOD, "Only a multiplayer server can load a mission.")
        return
    end

    if (string.match(message, "%[DCSDynamicWeather%.Mission%]:%sLoad%sMission:%s")) then
        local mission = string.match(message, "%[DCSDynamicWeather%.Mission%]:%sLoad Mission:%s(.*)")
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Loading Mission: " .. DCS_SG .. "Missions\\" .. mission)
        net.load_mission(DCS_SG .. "Missions\\" .. mission)
    end
end

function DCSDynamicWeatherCallbacks.onSimulationFrame()
    local THIS_METHOD = "DCSDynamicWeatherCallbacks.onSimulationFrame"

    -- Poll UDP for incoming commands
    pollUDP()

    if DCS.getRealTime() > simulationStartTime + 5 and not initialPauseStateSet then
        DCS.setPause(initialPauseState)
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Pause State set to: " .. tostring(initialPauseState))
        initialPauseStateSet = true
    end

    if math.floor(DCS.getRealTime()) % 60 == 0 then
        DCSDynamicWeather.checkCondForRestart()
    end
end

-- ===== Mission control =====

function DCSDynamicWeather.checkCondForRestart()
    local THIS_METHOD = "DCSDynamicWeather.waitForRestart"
    if not missionLoaded then
        return
    end

    if injectedRestart then
        DCS.setPause(false) -- Makes sure that the mission env scripts aren't blocked by the pause state
        return
    end

    if not waiting then
        waiting = true
        checkRestartTime = DCS.getRealTime() + 15
    else
        if (DCS.getRealTime() > checkRestartTime) then
            if (DCS.getRealTime() > simulationStartTime + DCSDynamicWeather.getRestartTimeInSeconds()) then
                if not injectedRestart then
                    DCS.setPause(false)
                end
                DCSDynamicWeather.Logger.info(THIS_METHOD, "Restarting mission.")
                DCSDynamicWeather.Logger.info(THIS_METHOD, "Pause State set to: " .. tostring(false))
                DCSDynamicWeather.restart()
                injectedRestart = true
            else
                local timeUntilRestart = DCSDynamicWeather.getRestartTimeInSeconds() + simulationStartTime - DCS.getRealTime()
                DCSDynamicWeather.Logger.info(THIS_METHOD, "Waiting for restart. (" .. timeUntilRestart .. " seconds)")
            end
            waiting = false
        end
    end
end

function DCSDynamicWeather.injectMissionNameToScriptEnv()
    local missionName = DCS.getMissionName()

    local initCode = [[a_do_script("if not DCSDynamicWeather then DCSDynamicWeather = {} end")]]
    DCSDynamicWeather.injectCodeStringToScriptEnv(initCode)

    local code = [[a_do_script("DCSDynamicWeather.MISSION_NAME = \"]] .. missionName .. [[\"")]]
    DCSDynamicWeather.injectCodeStringToScriptEnv(code)
end

function DCSDynamicWeather.restart()
    local code = [[a_do_script("DCSDynamicWeather.Restart.now()")]]
    DCSDynamicWeather.injectCodeStringToScriptEnv(code)
end

function DCSDynamicWeather.desanitizeMissionScripting()
    local THIS_METHOD = "DCSDynamicWeatherHook.desanitizeMissionScripting"
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Desanitizing Mission Scripting...")
    local missionScriptingFileName = "MissionScripting.lua"
    local missionScriptingFilePath = DCS_ROOT .. "Scripts\\" .. missionScriptingFileName
    local uncommentedLineFound = false
    local newMissionScriptingContent = ""

    if not (DCSDynamicWeather.fileExists(missionScriptingFilePath)) then
        DCSDynamicWeather.Logger.error(THIS_METHOD, "File: " .. missionScriptingFilePath .. " does not exist.")
        return
    end

    local readMissionScriptingFile = io.open(missionScriptingFilePath, "rb")
    for line in io.lines(readMissionScriptingFile) do
        if (string.match(line, "^(%-%-)")) or (string.match(line, "dofile")) or (string.match(line, "^[%s%c]*$")) then
            newMissionScriptingContent = newMissionScriptingContent .. line .. "\n"
        else
            uncommentedLineFound = true
            newMissionScriptingContent = newMissionScriptingContent .. "--" .. line .. " -- Commented by " .. THIS_FILE .. "\n"
        end
    end
    io.close(readMissionScriptingFile)

    if (uncommentedLineFound) then
        local writeMissionScriptingFile = io.open(missionScriptingFilePath, "wb")
        io.write(writeMissionScriptingFile, newMissionScriptingContent)
        io.flush(writeMissionScriptingFile)
        io.close(writeMissionScriptingFile)
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Desanitized Mission Scripting.")
    else
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Mission Scripting is already desanitized.")
    end
end

-- ===== UDP Server =====

local function initUDPServer()
    local THIS_METHOD = "initUDPServer"
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Initializing UDP server on port " .. UDP_PORT .. "...")

    local success, socket = pcall(require, "socket")
    if not success then
        DCSDynamicWeather.Logger.error(THIS_METHOD, "Failed to load socket library: " .. tostring(socket))
        return false
    end
    DCSDynamicWeather.Logger.info(THIS_METHOD, "Socket library loaded successfully")

    udpSocket = socket.udp()
    if not udpSocket then
        DCSDynamicWeather.Logger.error(THIS_METHOD, "Failed to create UDP socket")
        return false
    end
    DCSDynamicWeather.Logger.info(THIS_METHOD, "UDP socket created")

    local result, err = udpSocket:setsockname("127.0.0.1", UDP_PORT)
    if not result then
        DCSDynamicWeather.Logger.error(THIS_METHOD, "Failed to bind to port " .. UDP_PORT .. ": " .. tostring(err))
        return false
    end

    udpSocket:settimeout(0) -- Non-blocking
    DCSDynamicWeather.Logger.info(THIS_METHOD, "UDP server listening on 127.0.0.1:" .. UDP_PORT)
    return true
end

-- Now define pollUDP (forward-declared above)
pollUDP = function()
    local THIS_METHOD = "pollUDP"
    if not udpSocket then
        DCSDynamicWeather.Logger.warning(THIS_METHOD, "pollUDP called but udpSocket is nil — UDP not initialized")
        return
    end

    local messagesHandled = 0
    for _ = 1, 10 do
        local data, ip, port = udpSocket:receivefrom()
        if not data then
            break
        end
        messagesHandled = messagesHandled + 1
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Processing message #" .. messagesHandled)
        local success, err = pcall(handleUDPMessage, data, ip, port)
        if not success then
            DCSDynamicWeather.Logger.error(THIS_METHOD, "Error handling message: " .. tostring(err))
            -- Always reply so the client doesn't sit through a 2s timeout
            pcall(function() udpSocket:sendto("error:internal\n", ip, port) end)
        end
    end
end

-- ===== Entry point =====

local function main()
    DCSDynamicWeather.Logger.info(THIS_FILE, "Loading DCS Dynamic Weather Version: " .. DCS_DYNAMIC_WEATHER_HOOK_VERSION .. "...")
    DCSDynamicWeather.Logger.info(THIS_FILE, "DCS_ROOT: " .. DCS_ROOT)
    DCSDynamicWeather.Logger.info(THIS_FILE, "DCS_SG: " .. DCS_SG)

    DCSDynamicWeather.desanitizeMissionScripting()

    local udpOk = initUDPServer()
    if udpOk then
        DCSDynamicWeather.Logger.info(THIS_FILE, "UDP server initialized successfully")
    else
        DCSDynamicWeather.Logger.error(THIS_FILE, "UDP server failed to initialize — UDP commands will not work")
    end

    DCS.setUserCallbacks(DCSDynamicWeatherCallbacks)

    DCSDynamicWeather.Logger.info(THIS_FILE, "Loaded.")
end
main()