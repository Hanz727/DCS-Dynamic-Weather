local menuHandler = {}
local setWeather, createGroupSpecificMenus, createAllGroupsMenus
local THIS_FILE = DCSDynamicWeather.MODULE_NAME .. ".SetWeather"
local setWeatherMenu
local ADMIN_GROUP_NAME

function setWeather(weatherType)
    DCSDynamicWeather.Mission.loadNextMission(weatherType)
end

function menuHandler:onEvent(event)
    if event.id == world.event.S_EVENT_BIRTH and event.initiator:getPlayerName() ~= nil then
        if string.find(Group.getName(event.initiator:getGroup()), ADMIN_GROUP_NAME) then
            local adminGroup = event.initiator:getGroup()
            local adminGroupID = Group.getID(adminGroup)
            createGroupSpecificMenus(adminGroupID, ADMIN_GROUP_NAME)
        end
    end
end

function createGroupSpecificMenus(adminGroupID, adminGroupName)
    if setWeatherMenu ~= nil then
        return
    end
    setWeatherMenu = missionCommands.addSubMenuForGroup(adminGroupID, "Set Weather")

    if DCSDynamicWeather.JSON.getValue("cyclic_ops", DCSDynamicWeather.CONFIG_PATH) == "true" then
        local clearDayConfirm = missionCommands.addSubMenuForGroup(adminGroupID, "Clear", setWeatherMenu)
        missionCommands.addCommandForGroup(adminGroupID, "Confirm", clearDayConfirm, setWeather, "cvopsclear")
        DCSDynamicWeather.Logger.info(THIS_FILE, "Created Cyclic Ops Set Weather Menus for " .. adminGroupName)
    else

		local realWeatherMenu = missionCommands.addSubMenuForGroup(adminGroupID, "Real Weather", setWeatherMenu)
		local clearWeatherMenu = missionCommands.addSubMenuForGroup(adminGroupID, "Clear Weather", setWeatherMenu)
		
		realEventIntervalsMenu = missionCommands.addSubMenuForGroup(adminGroupID, "Events", realWeatherMenu)
		clearEventIntervalsMenu = missionCommands.addSubMenuForGroup(adminGroupID, "Events", clearWeatherMenu)

		local realIntervalMenus = {
			missionCommands.addSubMenuForGroup(adminGroupID, "0000 - 0330", realWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "0400 - 0730", realWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "0800 - 1130", realWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "1200 - 1530", realWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "1600 - 1930", realWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "2000 - 2330", realWeatherMenu)
		}
		
				
		local clearIntervalMenus = {
			missionCommands.addSubMenuForGroup(adminGroupID, "0000 - 0330", clearWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "0400 - 0730", clearWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "0800 - 1130", clearWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "1200 - 1530", clearWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "1600 - 1930", clearWeatherMenu),
			missionCommands.addSubMenuForGroup(adminGroupID, "2000 - 2330", clearWeatherMenu)
		}
		
		local times = {
			"0000", "0030", "0100", "0130", "0200", "0230", "0300", "0330",
			"0400", "0430", "0500", "0530", "0600", "0630", "0700", "0730",
			"0800", "0830", "0900", "0930", "1000", "1030", "1100", "1130",
			"1200", "1230", "1300", "1330", "1400", "1430", "1500", "1530",
			"1600", "1630", "1700", "1730", "1800", "1830", "1900", "1930",
			"2000", "2030", "2100", "2130", "2200", "2230", "2300", "2330"
		}

		local eventTimes = {
			"0730", "0915", "1100", "1245", "1430", "1615", "1800", "1945"
		}
		
		for idx, time in ipairs(times) do
			local menuLabel = time .. "L"
		    local intervalMenuId = math.ceil(idx/8.0)
			
			local subMenuReal = missionCommands.addSubMenuForGroup(adminGroupID, menuLabel, realIntervalMenus[intervalMenuId])
			local subMenuClear = missionCommands.addSubMenuForGroup(adminGroupID, menuLabel, clearIntervalMenus[intervalMenuId])
			
			local weatherSettingReal = "real" .. time
			local weatherSettingClear = "clear" .. time
			
			
			missionCommands.addCommandForGroup(adminGroupID, "Confirm", subMenuReal, setWeather, weatherSettingReal)
			missionCommands.addCommandForGroup(adminGroupID, "Confirm", subMenuClear, setWeather, weatherSettingClear)
		end

		for idx, time in ipairs(eventTimes) do
			local menuLabel = time .. "L"

			local subMenuReal = missionCommands.addSubMenuForGroup(adminGroupID, menuLabel, realEventIntervalsMenu)
			local subMenuClear = missionCommands.addSubMenuForGroup(adminGroupID, menuLabel, clearEventIntervalsMenu)
			
			local weatherSettingReal = "real" .. time
			local weatherSettingClear = "clear" .. time

			missionCommands.addCommandForGroup(adminGroupID, "Confirm", subMenuReal, setWeather, weatherSettingReal)
			missionCommands.addCommandForGroup(adminGroupID, "Confirm", subMenuClear, setWeather, weatherSettingClear)
		end
	
        --local clearDayConfirm = missionCommands.addSubMenuForGroup(adminGroupID, "Clear: 1200L", clearWeatherMenu)
        --missionCommands.addCommandForGroup(adminGroupID, "Confirm", clearDayConfirm, setWeather, "clearDay")
		
		--local clearNightConfirm = missionCommands.addSubMenuForGroup(adminGroupID, "Clear: 0000L", clearWeatherMenu)
        --missionCommands.addCommandForGroup(adminGroupID, "Confirm", clearNightConfirm, setWeather, "clearNight")
		
		--
		
		--local real1200Confirm = missionCommands.addSubMenuForGroup(adminGroupID, "Real: 1200L", realWeatherMenu)
        --missionCommands.addCommandForGroup(adminGroupID, "Confirm", real1200Confirm, setWeather, "real1200")
		
        --local real0000Confirm = missionCommands.addSubMenuForGroup(adminGroupID, "Real: 0000L", realWeatherMenu)
        --missionCommands.addCommandForGroup(adminGroupID, "Confirm", real0000Confirm, setWeather, "real0000")
		
        DCSDynamicWeather.Logger.info(THIS_FILE, "Created Non-Cyclic Ops Set Weather Menus for " .. adminGroupName)
    end
end

function createAllGroupsMenus()
    setWeatherMenu = missionCommands.addSubMenu("Set Weather")

    local clearDayConfirm = missionCommands.addSubMenu("Clear Day", setWeatherMenu)
    missionCommands.addCommand("Confirm", clearDayConfirm, setWeather, "clearDay")

    local clearNightConfirm = missionCommands.addSubMenu("Clear Night", setWeatherMenu)
    missionCommands.addCommand("Confirm", clearNightConfirm, setWeather, "clearNight")
    DCSDynamicWeather.Logger.info(THIS_FILE, "Created Set Weather Menus for all groups.")
end

local function main()
    ADMIN_GROUP_NAME = DCSDynamicWeather.JSON.getValue("adminGroupName", DCSDynamicWeather.CONFIG_PATH)
    if DCSDynamicWeather.JSON.getValue("adminMenuForEveryone", DCSDynamicWeather.CONFIG_PATH) == "false" then
        world.addEventHandler(menuHandler)
    else
        createAllGroupsMenus()
    end
end
main()
