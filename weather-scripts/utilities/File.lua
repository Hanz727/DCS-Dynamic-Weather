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

--- Pure-Lua binary copy — no shell, so no cmd quoting/environment quirks
--- (os.execute("copy ...") from inside the DCS process proved unreliable).
--- Returns the number of bytes written, or nil + reason.
function DCSDynamicWeather.File.copy(src, dst)
    local fin = io.open(src, "rb")
    if not fin then
        return nil, "cannot open source: " .. src
    end
    local data = fin:read("*a")
    io.close(fin)
    if not data then
        return nil, "cannot read source: " .. src
    end
    local fout = io.open(dst, "wb")
    if not fout then
        return nil, "cannot open destination: " .. dst
    end
    fout:write(data)
    io.close(fout)
    return #data
end
