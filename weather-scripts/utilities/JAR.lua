DCSDynamicWeather.JAR = {}

local THIS_FILE = DCSDynamicWeather.MODULE_NAME .. ".JAR"
local findJar

function DCSDynamicWeather.JAR.execute(jarName)
    local THIS_METHOD = THIS_FILE .. ".executeJar"
    local jar = findJar(jarName)
    local jarPath = DCSDynamicWeather.SCRIPTS_PATH .. "\\" .. jar

    if not jar then
        DCSDynamicWeather.Logger.warning(jarName .. ".jar wasn't found.")
        return
    end

    DCSDynamicWeather.Logger.info(THIS_METHOD, "Executing JAR: " .. jar)
    local logsDir = DCSDynamicWeather.SCRIPTS_PATH .. "\\logs"
    local stderrLog = logsDir .. "\\jar-stderr.log"
    local batPath = logsDir .. "\\run-jar.bat"

    -- Write a wrapper .bat so the redirect lives inside a single-file command,
    -- avoiding cmd.exe's quoting issues when os.execute mixes many quoted args with >>.
    local bat = io.open(batPath, "w")
    if not bat then
        DCSDynamicWeather.Logger.error(THIS_METHOD, "Could not write wrapper bat: " .. batPath)
        return
    end
    bat:write("@echo off\r\n")
    bat:write('echo ===== %DATE% %TIME% jar=' .. jar .. ' ===== >> "' .. stderrLog .. '"\r\n')
    bat:write('java -jar "' .. jarPath .. '" "' .. DCSDynamicWeather.SCRIPTS_PATH .. '" >> "' .. stderrLog .. '" 2>&1\r\n')
    bat:write('exit /b %ERRORLEVEL%\r\n')
    bat:close()

    if os.execute('"' .. batPath .. '"') == 0 then
        DCSDynamicWeather.Logger.info(THIS_METHOD, "Execution successful.")
    else
        DCSDynamicWeather.Logger.error(THIS_METHOD, "Execution failed. See " .. stderrLog .. " for JVM output.")
    end
end

function findJar(jarName)
    local dir = DCSDynamicWeather.SCRIPTS_PATH
    for file in lfs.dir(dir) do
        if lfs.attributes(dir .. "\\" .. file,"mode") == "file" then
            if string.find(file, jarName, _, true) then
                DCSDynamicWeather.Logger.info(THIS_FILE, "Found JAR: " .. file)
                return file
            end
        end
    end
end
