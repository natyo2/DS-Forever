-- WowCompanionCharacter / TargetFrames.lua
-- BIG BARS ON THE TOP SCREEN (natyo2's fork; the user 2026-11-07: "I still cannot see health and that's quite important for friendly
-- targets! Ideally the bars are big for friendly on left and foes on the right", then: "hide the native one and have the buffs
-- alongside my big bar on the left ... always display our one top left where it is now, but only highlighted when targeting myself").
--
-- This client hides other units' health from add-on code (Target.lua's test: UnitHealth "secret" even out of combat), but a WIDGET may
-- be given a hidden value: a status bar's SetValue, a texture's SetTexture and a font string's SetText draw it while Lua never reads it.
--   ME      top left, always: name and level, a big health bar with its percent, a power bar, and under it a row of my buffs then my
--           debuffs (the game's own buff frame is hidden again by Hub.lua); a gold rim and glow while I am my own target
--   FRIEND  under mine on the left: a friendly or neutral target that is not me
--   FOE     top right: a target I can attack
-- Plain frames, not secure and not clickable (EnableMouse false): showing and hiding them is allowed in combat, and a click passes
-- through them to the game. Nothing here reads a hidden value into Lua, compares it or sends it anywhere.

local WC = _G.WowCompanion
if type(WC) ~= "table" then return end

local W, BAR_H, POW_H = 380, 34, 10
local ICON, ICONS = 30, 12
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
local GOLD = { 1.0, 0.82, 0.2 }

local function make(justify)
    local f = CreateFrame("Frame", nil, UIParent, BackdropTemplateMixin and "BackdropTemplate" or nil)
    f:SetSize(W, BAR_H + POW_H + 44)
    f:SetFrameStrata("MEDIUM")
    f:EnableMouse(false)
    if f.SetBackdrop then
        f:SetBackdrop({ bgFile = "Interface\\Buttons\\WHITE8X8", edgeFile = "Interface\\Buttons\\WHITE8X8", edgeSize = 2 })
        f:SetBackdropColor(0, 0, 0, 0.55)
    end
    f.name = f:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge")
    f.name:SetPoint("TOPLEFT", 8, -6); f.name:SetPoint("TOPRIGHT", -8, -6)
    f.name:SetJustifyH(justify)
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
    -- the glow while I am my own target (only the player's frame uses it)
    f.glow = f:CreateTexture(nil, "BACKGROUND", nil, -8)
    f.glow:SetPoint("TOPLEFT", -6, 6); f.glow:SetPoint("BOTTOMRIGHT", 6, -6)
    f.glow:SetColorTexture(GOLD[1], GOLD[2], GOLD[3], 0.35)
    f.glow:Hide()
    f:Hide()
    return f
end

local me, friend, foe = make("LEFT"), make("LEFT"), make("RIGHT")
me:SetPoint("TOPLEFT", UIParent, "TOPLEFT", 24, -110)
foe:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -24, -110)

-- MY AURAS under my bar: buffs, then debuffs (a red rim), the game's own icons and its cooldown sweep
local auraRow = CreateFrame("Frame", nil, UIParent)
auraRow:SetPoint("TOPLEFT", me, "BOTTOMLEFT", 0, -4)
auraRow:SetSize(W, ICON)
auraRow:EnableMouse(false)
local cells = {}
for i = 1, ICONS do
    local c = CreateFrame("Frame", nil, auraRow)
    c:SetSize(ICON, ICON)
    c:SetPoint("TOPLEFT", (i - 1) * (ICON + 2), 0)
    c:EnableMouse(false)
    c.tex = c:CreateTexture(nil, "ARTWORK"); c.tex:SetAllPoints(); c.tex:SetTexCoord(0.08, 0.92, 0.08, 0.92)
    c.rim = c:CreateTexture(nil, "BACKGROUND"); c.rim:SetPoint("TOPLEFT", -1, 1); c.rim:SetPoint("BOTTOMRIGHT", 1, -1)
    c.cd = CreateFrame("Cooldown", nil, c, "CooldownFrameTemplate"); c.cd:SetAllPoints(); c.cd:SetReverse(true)
    pcall(function() c.cd:SetHideCountdownNumbers(false) end)
    c.count = c:CreateFontString(nil, "OVERLAY", "NumberFontNormal"); c.count:SetPoint("BOTTOMRIGHT", -1, 1)
    c:Hide()
    cells[i] = c
end
-- the friend's frame sits under my auras
friend:SetPoint("TOPLEFT", auraRow, "BOTTOMLEFT", 0, -10)

local function auras()
    local n = 0
    if type(C_UnitAuras) ~= "table" or type(C_UnitAuras.GetAuraDataByIndex) ~= "function" then return end
    for _, filter in ipairs({ "HELPFUL", "HARMFUL" }) do
        for x = 1, 40 do
            if n >= ICONS then break end
            local ok, d = pcall(C_UnitAuras.GetAuraDataByIndex, "player", x, filter)
            if not ok or d == nil then break end
            n = n + 1
            local c = cells[n]
            pcall(function() c.tex:SetTexture(d.icon) end)
            if filter == "HARMFUL" then c.rim:SetColorTexture(0.8, 0.1, 0.1, 1) else c.rim:SetColorTexture(0, 0, 0, 0.8) end
            c.cd:Hide()
            pcall(function()
                if d.duration and d.duration > 0 then c.cd:SetCooldown(d.expirationTime - d.duration, d.duration); c.cd:Show() end
            end)
            c.count:SetText("")
            pcall(function() if d.applications and d.applications > 1 then c.count:SetText(d.applications) end end)
            c:Show()
        end
    end
    for i = n + 1, ICONS do cells[i]:Hide() end
end

-- the unit's health as the game gives it: straight into the widgets (a hidden value is drawn, never read here)
local function fill(f, unit)
    pcall(function() f.hp:SetMinMaxValues(0, UnitHealthMax(unit)); f.hp:SetValue(UnitHealth(unit)) end)
    pcall(function() f.pw:SetMinMaxValues(0, UnitPowerMax(unit)); f.pw:SetValue(UnitPower(unit)) end)
    if plain(call(UnitIsDeadOrGhost, unit)) == true then f.hpText:SetText("Dead"); return end
    local ok = false
    if type(UnitHealthPercent) == "function" then
        ok = pcall(function()
            local curve = type(CurveConstants) == "table" and CurveConstants.ScaleTo100 or nil
            f.hpText:SetFormattedText("%.0f%%", UnitHealthPercent(unit, true, curve))
        end)
    end
    if not ok then ok = pcall(function() f.hpText:SetText(UnitHealth(unit)) end) end
    if not ok then f.hpText:SetText("") end
end

local function colourOf(unit, friendly)
    if plain(call(UnitIsPlayer, unit)) == true then
        local _, file = call(UnitClass, unit)
        local c = CLASS[plain(file) or ""]
        if c then return c end
    end
    return friendly and { 0.31, 0.82, 0.35 } or { 0.92, 0.27, 0.24 }
end

local function dress(f, unit, friendly, rim)
    local c = colourOf(unit, friendly)
    local lv = plain(call(UnitLevel, unit))
    pcall(function() f.name:SetText(((type(lv) == "number" and lv > 0) and (lv .. "  ") or "?? ") .. (UnitName(unit) or "")) end)
    f.name:SetTextColor(c[1], c[2], c[3])
    f.hp:SetStatusBarColor(friendly and 0.2 or 0.85, friendly and 0.75 or 0.2, 0.2)
    rim = rim or c
    if f.SetBackdropBorderColor then f:SetBackdropBorderColor(rim[1], rim[2], rim[3], 0.9) end
    local _, tok = call(UnitPowerType, unit)
    local p = POWER[plain(tok) or ""] or POWER.MANA
    f.pw:SetStatusBarColor(p[1], p[2], p[3])
    fill(f, unit)
end

local function refresh()
    -- me: always, gold while I am my own target
    local isMe = plain(call(UnitIsUnit, "target", "player")) == true
    dress(me, "player", true, isMe and GOLD or { 0.35, 0.35, 0.35 })
    if isMe then me.glow:Show() else me.glow:Hide() end
    me:Show()
    auras()
    -- the target: a foe on the right, anyone else who is not me under mine on the left
    local exists = plain(call(UnitExists, "target")) == true
    if not exists or isMe then friend:Hide(); foe:Hide(); return end
    local hostile = plain(call(UnitCanAttack, "player", "target")) == true
    local f, other = hostile and foe or friend, hostile and friend or foe
    other:Hide()
    dress(f, "target", not hostile)
    f:Show()
end

local ev = CreateFrame("Frame")
ev:RegisterEvent("PLAYER_TARGET_CHANGED")
ev:RegisterEvent("PLAYER_ENTERING_WORLD")
ev:RegisterEvent("PLAYER_LEVEL_UP")
for _, e in ipairs({ "UNIT_HEALTH", "UNIT_MAXHEALTH", "UNIT_POWER_UPDATE", "UNIT_MAXPOWER", "UNIT_NAME_UPDATE", "UNIT_FACTION", "UNIT_LEVEL" }) do
    if not pcall(function() ev:RegisterUnitEvent(e, "target", "player") end) then pcall(ev.RegisterEvent, ev, e) end
end
if not pcall(function() ev:RegisterUnitEvent("UNIT_AURA", "player") end) then pcall(ev.RegisterEvent, ev, "UNIT_AURA") end
ev:SetScript("OnEvent", function(_, e, unit)
    if e == "UNIT_AURA" then if unit == "player" then auras() end return end
    if unit ~= nil and unit ~= "target" and unit ~= "player" then return end
    if e == "PLAYER_TARGET_CHANGED" or e == "PLAYER_ENTERING_WORLD" or e == "UNIT_FACTION" or e == "UNIT_NAME_UPDATE"
        or e == "UNIT_LEVEL" or e == "PLAYER_LEVEL_UP" then refresh(); return end
    if unit == "player" then fill(me, "player") end
    if friend:IsShown() then fill(friend, "target") end
    if foe:IsShown() then fill(foe, "target") end
end)
-- a hidden value may move without its event reaching an add-on: the shown bars are filled again a few times a second
local acc = 0
ev:SetScript("OnUpdate", function(_, dt)
    acc = acc + dt
    if acc < 0.2 then return end
    acc = 0
    if me:IsShown() then fill(me, "player") end
    if friend:IsShown() then fill(friend, "target") end
    if foe:IsShown() then fill(foe, "target") end
end)
