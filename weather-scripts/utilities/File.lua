DCSDynamicWeather.File = {}

function DCSDynamicWeather.File.exists(file)
    local f = io.open(file, "rb")
    if f then
        io.close(f)
    end
    return f ~= nil
end

function DCSDynamicWeather.File.size(file)
    local f = io.open(file, "rb")
    if not f then
        return 0
    end
    -- DCS mission scripting sandbox strips io handle methods like :seek,
    -- so read the whole file and measure. .miz files are small enough.
    local data = f:read("*a")
    io.close(f)
    return data and #data or 0
end
