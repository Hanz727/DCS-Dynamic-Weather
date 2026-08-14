DCSDynamicWeather.Logger = {}

-- Heal SCRIPTS_PATH here rather than in the loader — the loader is embedded
-- in every user's miz (a_do_script_file resource) and can't be redeployed,
-- while this file ships with the mission folder. An over-escaped bootstrap
-- (serialized miz text shows \ as \\) leaves literal doubled backslashes in
-- the path; Windows rejects the empty components with "The filename,
-- directory name, or volume label syntax is incorrect", which breaks the
-- _A/_B copy and 7-Zip inside the jars. dofile/io tolerate the doubles, so
-- this file still loads and can fix the path for everything after it.
if DCSDynamicWeather.SCRIPTS_PATH then
    DCSDynamicWeather.SCRIPTS_PATH = string.gsub(DCSDynamicWeather.SCRIPTS_PATH, "\\+", "\\")
end

local printLog, checkReplaceNil

-- @param fileName string
-- @param message string
function DCSDynamicWeather.Logger.info(logSource, message)
    message = checkReplaceNil(message)
    printLog(logSource, message, "INFO    ")
end

-- @param fileName string
-- @param message string
function DCSDynamicWeather.Logger.warning(logSource, message)
    message = checkReplaceNil(message)
    printLog(logSource, message, "WARNING ")
end

-- @param fileName string
-- @param message string
function DCSDynamicWeather.Logger.error(logSource, message)
    message = checkReplaceNil(message)
    printLog(logSource, message, "ERROR   ")
end

function checkReplaceNil(message)
    if message == nil then
        return "nil"
    else
        return message
    end
end

function printLog(logSource, message, level)
    local fullTimeStamp = os.date("%Y-%m-%d %H:%M:%S ")
    local ymdTimeStamp = os.date("%Y%m%d")
    local logFile = io.open(DCSDynamicWeather.SCRIPTS_PATH .. "\\logs\\" .. DCSDynamicWeather.MODULE_NAME .. "-" .. ymdTimeStamp .. ".log", "a")
    io.write(logFile, fullTimeStamp .. level .. "[" .. logSource .. "]: " .. message .. "\n")
    io.flush(logFile)
    io.close(logFile)
end