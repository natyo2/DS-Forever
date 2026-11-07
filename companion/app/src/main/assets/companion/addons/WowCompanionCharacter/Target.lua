-- WowCompanionCharacter / Target.lua
-- THE PLAYER'S TARGET FOR THE COMPANION'S TARGET CARD (natyo2's fork, companion-only mode; the user 2026-11-07: "add the target card,
-- ideally on the chat side ... have the target at the top of the chat bit"). READ ONLY: nothing here clicks, casts, targets or
-- changes a frame; it reads the target the player chose and sends one small document when something about it changed.
--
-- WHAT IT SENDS: {"type":"target","v":TV, "has":bool, "now":GetTime(), and while has:
--   n   name;  lv  level (-1 = "??": a boss or far above);  c  classification ("elite", "rare", "rareelite", "worldboss", "")
--   rx  "f" friendly / "n" neutral / "h" hostile (UnitReaction, else UnitIsFriend / UnitCanAttack);  pl  a player;  cl  class file
--   dead;  hp  health 0..100 (nil = hidden by the game);  pw  power 0..100;  pt  power token ("MANA", "RAGE", "ENERGY", ...)
--   cast {n, i icon, s start, e end (GetTime seconds), ch channel}
--   b / d  up to 8 buffs / debuffs: {i icon, s stacks (>1), dur, exp (GetTime), m = the player's own} }
--
-- SECRECY: this client hides some unit values from add-ons while restrictions are in effect (combat and the like: PadMap.lua's
-- m79 / m86 notes). Every value goes through plain(): a secret value is never compared, stored or encoded, it is left out. Health
-- falls back to the percent reader, then to the DRAWN fill of the game's own target frame (it is drawn even while the hub keeps it
-- invisible), as PadMap.readHp does for the party.
--
-- COST: Sink.lua's flush is throttled and shared; this sends only on a change (the encoded document compared with the last one), at
-- most every TICK seconds while events come, plus a 1 s look while a target exists (a hidden health value moves without events).

local WC = _G.WowCompanion
if type(WC) ~= "table" then return end

local TV = "t1"
local TICK = 0.25
local MAXA = 8

local function plain(v)
    if type(issecretvalue) == "function" then local ok, q = pcall(issecretvalue, v); if not ok or q then return nil end end
    if type(WC.isPlain) == "function" and not WC.isPlain(v) then return nil end
    return v
end
local function call(f, ...) local ok, a, b, c, d, e, g, h, i = pcall(f, ...); if ok then return a, b, c, d, e, g, h, i end end
local function num(v) v = plain(v); return type(v) == "number" and v or nil end
local function str(v) v = plain(v); return type(v) == "string" and v or nil end
local function bool(v) v = plain(v); if type(v) == "boolean" then return v end end

local function pct(cur, max) if cur and max and max > 0 then return math.max(0, math.min(100, math.floor(cur * 100 / max + 0.5))) end end

-- the drawn fill of a status bar, as a percent (its value may be secret, its texture's width is not)
local function barPct(fr)
    if type(fr) ~= "table" or type(fr.GetStatusBarTexture) ~= "function" then return nil end
    local v = num(call(function() return fr:GetValue() end))
    local lo, hi = call(function() return fr:GetMinMaxValues() end)
    lo, hi = num(lo), num(hi)
    if v and lo and hi and hi > lo then return math.floor((v - lo) * 100 / (hi - lo) + 0.5) end
    local tw = num(call(function() local t = fr:GetStatusBarTexture(); return t and t:GetWidth() end))
    local fw = num(call(function() return fr:GetWidth() end))
    if tw and fw and fw > 0 then return math.max(0, math.min(100, math.floor(tw * 100 / fw + 0.5))) end
end

local function frameBar(paths)
    for _, path in ipairs(paths) do
        local t = _G
        for _, k in ipairs(path) do if type(t) ~= "table" then t = nil break end; t = t[k] end
        local p = barPct(t)
        if p then return p end
    end
end

local function health()
    local p = pct(num(call(UnitHealth, "target")), num(call(UnitHealthMax, "target")))
    if p then return p end
    if type(UnitHealthPercent) == "function" then
        local q = num(call(UnitHealthPercent, "target"))
        if q then return math.floor((q <= 1 and q * 100 or q) + 0.5) end
    end
    return frameBar({ { "TargetFrame", "TargetFrameContent", "TargetFrameContentMain", "HealthBarsContainer", "HealthBar" },
                      { "TargetFrameHealthBar" }, { "TargetFrame", "healthbar" } })
end

local function power()
    local p = pct(num(call(UnitPower, "target")), num(call(UnitPowerMax, "target")))
    if p then return p end
    return frameBar({ { "TargetFrame", "TargetFrameContent", "TargetFrameContentMain", "ManaBar" },
                      { "TargetFrameManaBar" }, { "TargetFrame", "manabar" } })
end

local function reaction()
    local r = num(call(UnitReaction, "player", "target"))
    if r then return r >= 5 and "f" or (r == 4 and "n" or "h") end
    if bool(call(UnitCanAttack, "player", "target")) then return "h" end
    if bool(call(UnitIsFriend, "player", "target")) then return "f" end
    return "n"
end

local function auras(filter)
    local out = {}
    if type(C_UnitAuras) ~= "table" or type(C_UnitAuras.GetAuraDataByIndex) ~= "function" then return out end
    for x = 1, 40 do
        if #out >= MAXA then break end
        local ok, d = pcall(C_UnitAuras.GetAuraDataByIndex, "target", x, filter)
        if not ok or type(d) == "nil" then break end
        d = plain(d)
        if type(d) == "table" then
            local a = { i = num(d.icon) }
            local s = num(d.applications); if s and s > 1 then a.s = s end
            local dur = num(d.duration); if dur and dur > 0 then a.dur = dur; a.exp = num(d.expirationTime) end
            local src = str(d.sourceUnit)
            if src == "player" or src == "pet" then a.m = true end
            if a.i then out[#out + 1] = a end
        end
    end
    return out
end

local function cast()
    local n, _, icon, s, e = call(UnitCastingInfo, "target")
    local ch = false
    if not str(n) then n, _, icon, s, e = call(UnitChannelInfo, "target"); ch = true end
    n, icon, s, e = str(n), num(icon), num(s), num(e)
    if not n or not s or not e then return nil end
    return { n = n, i = icon, s = s / 1000, e = e / 1000, ch = ch or nil }
end

local function build()
    local doc = { type = "target", v = TV, now = GetTime() }
    if not bool(call(UnitExists, "target")) then doc.has = false; return doc end
    doc.has = true
    doc.n = str(call(UnitName, "target"))
    local lv = num(call(UnitLevel, "target")); doc.lv = lv
    doc.c = str(call(UnitClassification, "target"))
    if doc.c == "normal" or doc.c == "trivial" or doc.c == "minus" then doc.c = nil end
    doc.rx = reaction()
    doc.pl = bool(call(UnitIsPlayer, "target")) or nil
    if doc.pl then local _, file = call(UnitClass, "target"); doc.cl = str(file) end
    doc.dead = bool(call(UnitIsDeadOrGhost, "target")) or nil
    doc.hp = health()
    doc.pw = power()
    local _, tok = call(UnitPowerType, "target"); doc.pt = str(tok)
    doc.cast = cast()
    doc.b = auras("HELPFUL")
    doc.d = auras("HARMFUL")
    return doc
end

local last, queued = nil, false

local function send()
    local ok, doc = pcall(build)
    if not ok or type(doc) ~= "table" then return end
    -- compared without its clock: the same target, the same numbers = nothing to send
    local nowWas = doc.now; doc.now = nil
    local okk, key = pcall(WC.encode, doc)
    if not okk then return end
    if key == last then return end
    last = key
    doc.now = nowWas
    local oke, text = pcall(WC.encode, doc)
    if not oke then return end
    WC.send(text)
    WC.push()
end

local function soon()
    if queued then return end
    queued = true
    C_Timer.After(TICK, function() queued = false; send() end)
end

local f = CreateFrame("Frame")
pcall(f.RegisterEvent, f, "PLAYER_TARGET_CHANGED")
pcall(f.RegisterEvent, f, "PLAYER_ENTERING_WORLD")
for _, e in ipairs({ "UNIT_HEALTH", "UNIT_MAXHEALTH", "UNIT_POWER_UPDATE", "UNIT_MAXPOWER", "UNIT_AURA", "UNIT_NAME_UPDATE", "UNIT_LEVEL",
                     "UNIT_FACTION", "UNIT_SPELLCAST_START", "UNIT_SPELLCAST_STOP", "UNIT_SPELLCAST_FAILED", "UNIT_SPELLCAST_INTERRUPTED",
                     "UNIT_SPELLCAST_CHANNEL_START", "UNIT_SPELLCAST_CHANNEL_STOP", "UNIT_SPELLCAST_DELAYED" }) do
    local ok = pcall(function() f:RegisterUnitEvent(e, "target") end)
    if not ok then pcall(f.RegisterEvent, f, e) end
end
f:SetScript("OnEvent", function(_, e, unit)
    if e == "PLAYER_TARGET_CHANGED" then send(); return end
    if unit ~= nil and unit ~= "target" then return end
    soon()
end)

-- a hidden health value moves without events: one look a second while a target exists
C_Timer.NewTicker(1, function()
    if bool(call(UnitExists, "target")) or (last ~= nil and not last:find('"has":false', 1, true)) then send() end
end)
