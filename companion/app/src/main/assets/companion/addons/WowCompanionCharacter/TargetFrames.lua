-- WowCompanionCharacter / TargetFrames.lua
-- BIG TARGET BARS ON THE TOP SCREEN (natyo2's fork; the user 2026-11-07: "I still cannot see health and that's quite important for
-- friendly targets! Ideally the bars are big for friendly on left and foes on the right"). This client hides other units' health from
-- add-on code (Target.lua's test: UnitHealth "secret" even out of combat), but a WIDGET may be given a hidden value: a status bar's
-- SetValue and a font string's SetText draw it while Lua never reads it. So two frames of our own draw the target:
--   a friend (or a neutral) on the LEFT, a foe on the RIGHT: name and level, a big health bar with its percent, a power bar.
-- Plain frames, not secure and not clickable (EnableMouse false): showing and hiding them is allowed in combat, and a click passes
-- through them to the game. Nothing here reads a hidden value into Lua, compares it or sends it anywhere.

local WC = _G.WowCompanion
if type(WC) ~= "table" then return end

local W, BAR_H, POW_H = 380, 34, 10
local function call(f, ...) local ok, a, b, c = pcall(f, ...); if ok then return a, b, c end end
local function plain(v)
    if type(issecretvalue) == "function" then local ok, q = pcall(issecretvalue, v); if not ok or q then return nil end end
    return v
end

local CLASS = {
    WARRIOR = { 0.78, 0.61, 0.43 }, PALADIN = { 0.96, 0.55, 0.73 }, HUNTER = { 0.67, 0.83, 0.45 }, ROGUE = { 1.0, 0.96, 0.41 },
    PRIEST = { 1.0, 1.0, 1.0 }, SHAMAN = { 0.0, 0.44, 0.87 }, MAGE = { 0.25, 0.78, 0.92 }, WARLOCK = { 0.53, 0.53, 0.93 },
    DRUID = { 1.0, 0.49, 0.04 },
}
local POWER = { MANA = { 0.2, 0.43, 0.9 }, RAGE = { 0.86, 0.16, 0.16 }, ENERGY = { 0.94, 0.86, 0.16 }, FOCUS = { 0.9, 0.5, 0.2 } }

local function make(side)
    local f = CreateFrame("Frame", nil, UIParent, BackdropTemplateMixin and "BackdropTemplate" or nil)
    f:SetSize(W, BAR_H + POW_H + 44)
    if side == "left" then f:SetPoint("TOPLEFT", UIParent, "TOPLEFT", 24, -140) else f:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -24, -140) end
    f:SetFrameStrata("MEDIUM")
    f:EnableMouse(false)
    if f.SetBackdrop then
        f:SetBackdrop({ bgFile = "Interface\\Buttons\\WHITE8X8", edgeFile = "Interface\\Buttons\\WHITE8X8", edgeSize = 2 })
        f:SetBackdropColor(0, 0, 0, 0.55)
    end
    f.name = f:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge")
    f.name:SetPoint("TOPLEFT", 8, -6); f.name:SetPoint("TOPRIGHT", -8, -6)
    f.name:SetJustifyH(side == "left" and "LEFT" or "RIGHT")
    f.hp = CreateFrame("StatusBar", nil, f)
    f.hp:SetStatusBarTexture("Interface\\TargetingFrame\\UI-StatusBar")
    f.hp:SetPoint("TOPLEFT", 8, -32); f.hp:SetPoint("TOPRIGHT", -8, -32); f.hp:SetHeight(BAR_H)
    f.hp.bg = f.hp:CreateTexture(nil, "BACKGROUND"); f.hp.bg:SetAllPoints(); f.hp.bg:SetColorTexture(0.15, 0.15, 0.15, 0.85)
    f.hpText = f.hp:CreateFontString(nil, "OVERLAY", "GameFontHighlightLarge")
    f.hpText:SetPoint("CENTER")
    f.pw = CreateFrame("StatusBar", nil, f)
    f.pw:SetStatusBarTexture("Interface\\TargetingFrame\\UI-StatusBar")
    f.pw:SetPoint("TOPLEFT", f.hp, "BOTTOMLEFT", 0, -3); f.pw:SetPoint("TOPRIGHT", f.hp, "BOTTOMRIGHT", 0, -3); f.pw:SetHeight(POW_H)
    f.pw.bg = f.pw:CreateTexture(nil, "BACKGROUND"); f.pw.bg:SetAllPoints(); f.pw.bg:SetColorTexture(0.1, 0.1, 0.1, 0.85)
    f:Hide()
    return f
end

local left, right = make("left"), make("right")

-- the health as the game gives it: straight into the widgets (a hidden value is drawn, never read here)
local function fill(f)
    pcall(function() f.hp:SetMinMaxValues(0, UnitHealthMax("target")); f.hp:SetValue(UnitHealth("target")) end)
    pcall(function() f.pw:SetMinMaxValues(0, UnitPowerMax("target")); f.pw:SetValue(UnitPower("target")) end)
    local dead = plain(call(UnitIsDeadOrGhost, "target"))
    if dead == true then f.hpText:SetText("Dead"); return end
    -- the percent, as a widget may print it; the plain number when the game gives one; else nothing
    local ok = false
    if type(UnitHealthPercent) == "function" then
        ok = pcall(function()
            local curve = type(CurveConstants) == "table" and CurveConstants.ScaleTo100 or nil
            f.hpText:SetFormattedText("%.0f%%", UnitHealthPercent("target", true, curve))
        end)
    end
    if not ok then ok = pcall(function() f.hpText:SetText(UnitHealth("target")) end) end
    if not ok then f.hpText:SetText("") end
end

local function colourOf(isFriend)
    local isPlayer = plain(call(UnitIsPlayer, "target"))
    if isPlayer == true then
        local _, file = call(UnitClass, "target")
        local c = CLASS[plain(file) or ""]
        if c then return c end
    end
    return isFriend and { 0.31, 0.82, 0.35 } or { 0.92, 0.27, 0.24 }
end

local function refresh()
    local exists = plain(call(UnitExists, "target")) == true
    if not exists then left:Hide(); right:Hide(); return end
    local foe = plain(call(UnitCanAttack, "player", "target")) == true
    local f, other = foe and right or left, foe and left or right
    other:Hide()
    local c = colourOf(not foe)
    local lv = plain(call(UnitLevel, "target"))
    pcall(function() f.name:SetText(((type(lv) == "number" and lv > 0) and (lv .. "  ") or "?? ") .. (UnitName("target") or "")) end)
    f.name:SetTextColor(c[1], c[2], c[3])
    f.hp:SetStatusBarColor(foe and 0.85 or 0.2, foe and 0.2 or 0.75, 0.2)
    if f.SetBackdropBorderColor then f:SetBackdropBorderColor(c[1], c[2], c[3], 0.9) end
    local _, tok = call(UnitPowerType, "target")
    local p = POWER[plain(tok) or ""] or POWER.MANA
    f.pw:SetStatusBarColor(p[1], p[2], p[3])
    fill(f)
    f:Show()
end

local ev = CreateFrame("Frame")
ev:RegisterEvent("PLAYER_TARGET_CHANGED")
ev:RegisterEvent("PLAYER_ENTERING_WORLD")
for _, e in ipairs({ "UNIT_HEALTH", "UNIT_MAXHEALTH", "UNIT_POWER_UPDATE", "UNIT_MAXPOWER", "UNIT_NAME_UPDATE", "UNIT_FACTION", "UNIT_LEVEL" }) do
    if not pcall(function() ev:RegisterUnitEvent(e, "target") end) then pcall(ev.RegisterEvent, ev, e) end
end
ev:SetScript("OnEvent", function(_, e, unit)
    if unit ~= nil and unit ~= "target" then return end
    if e == "PLAYER_TARGET_CHANGED" or e == "PLAYER_ENTERING_WORLD" or e == "UNIT_FACTION" or e == "UNIT_NAME_UPDATE" then refresh()
    else
        local f = left:IsShown() and left or (right:IsShown() and right or nil)
        if f then fill(f) end
    end
end)
-- a hidden value may move without its event reaching an add-on: the shown frame is filled again a few times a second
local acc = 0
ev:SetScript("OnUpdate", function(_, dt)
    acc = acc + dt
    if acc < 0.2 then return end
    acc = 0
    local f = left:IsShown() and left or (right:IsShown() and right or nil)
    if f then fill(f) end
end)
