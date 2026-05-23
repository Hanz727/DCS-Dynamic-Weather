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
    local size = f:seek("end")
    io.close(f)
    return size or 0
end
