-- WowCompanionCharacter / GuideWaypoint.lua
-- RESTEDXP'S NEXT POINT AS THE GAME'S OWN WAYPOINT (natyo2's fork; the user 2026-11-07: "the compass nav icon seems to have
-- disappeared"). DS Forever's compass bar (Trail.lua) and the companion's route follow the game's super-tracked navigation point. RXP
-- super-tracks a quest only on its quest steps and points every other step ("go here", "kill these") with its OWN arrow, which the user
-- turned off so the two do not clash. So while RXP's arrow is hidden and has a target (its frame RXPG_ARROW keeps `element`: the step's
-- zone and x / y in percent), that point is set as the game's user waypoint and super-tracked; when RXP has no point any more, a
-- waypoint this file set is cleared. A waypoint the player placed themselves is never replaced while RXP has nothing to point at.
-- No game action: a map waypoint is UI state only.

local WC = _G.WowCompanion
if type(WC) ~= "table" then return end
if not (C_Map and C_Map.SetUserWaypoint and C_SuperTrack and UiMapPoint) then return end

local ours = nil          -- "zone:x:y" of the waypoint this file set, nil = none
local function key(z, x, y) return string.format("%d:%.4f:%.4f", z, x, y) end

local function target()
    local af = _G.RXPG_ARROW
    if type(af) ~= "table" then return nil end
    if af.IsShown and af:IsShown() then return nil end           -- RXP draws its own arrow: nothing to do
    local e = af.element
    if type(e) ~= "table" then return nil end
    local step = e.step
    if type(step) == "table" and not step.active then return nil end
    if e.completed or e.hidden then return nil end
    local z, x, y = tonumber(e.zone), tonumber(e.x), tonumber(e.y)
    if not (z and x and y) then return nil end
    return z, x / 100, y / 100
end

local function tick()
    -- the companion's own route holds the game's one waypoint (Map.lua WC.routeWp): it is never moved from here meanwhile
    if type(WC.routeWp) == "table" and WC.routeWp.active then ours = nil; return end
    local okT, z, x, y = pcall(target)
    if not okT then return end
    if not z then
        if ours then
            pcall(function()
                if C_Map.HasUserWaypoint() then C_Map.ClearUserWaypoint() end
            end)
            ours = nil
        end
        return
    end
    local k = key(z, x, y)
    if k == ours then
        -- still ours: keep it super-tracked unless RXP itself chose a quest for this step
        pcall(function()
            if C_Map.HasUserWaypoint() and not C_SuperTrack.IsSuperTrackingUserWaypoint()
                and (C_SuperTrack.GetSuperTrackedQuestID() or 0) == 0 then
                C_SuperTrack.SetSuperTrackedUserWaypoint(true)
            end
        end)
        return
    end
    local ok = pcall(function()
        if not C_Map.CanSetUserWaypointOnMap(z) then error("no waypoint on this map") end
        C_Map.SetUserWaypoint(UiMapPoint.CreateFromCoordinates(z, x, y))
        if (C_SuperTrack.GetSuperTrackedQuestID() or 0) == 0 then C_SuperTrack.SetSuperTrackedUserWaypoint(true) end
    end)
    if ok then ours = k end
end

C_Timer.NewTicker(0.5, tick)
