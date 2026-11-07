package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE TARGET CARD (natyo2's fork, companion-only mode; the user 2026-11-07: "add the target card, ideally on the chat side, we can
 * probably still display most of the chat, but have the target at the top of the chat bit, we still want the controls to be
 * visible"): the player's current target from the "target" document (WowCompanionCharacter/Target.lua) at the top of Home's chat
 * well, while something is targeted. Display only: a touch on it does nothing in the game.
 *
 *   name and level, the frame coloured by who it is (green friendly, yellow neutral, red hostile; a player in their class colour)
 *   health (percent, "?" while the game hides it), power under it, the cast bar while it casts
 *   up to 8 buffs and 8 debuffs as small icons, the player's own debuffs ringed in gold, with the time left
 */
class TargetCard(context: Context) : View(context) {
    data class Aura(val icon: Int, val stacks: Int, val exp: Double, val dur: Double, val mine: Boolean)
    data class Cast(val name: String, val start: Double, val end: Double, val channel: Boolean)
    data class Target(val name: String, val level: Int?, val cls: String?, val reaction: String, val player: Boolean, val classFile: String?,
                      val dead: Boolean, val hp: Int?, val pw: Int?, val powerType: String?, val cast: Cast?,
                      val buffs: List<Aura>, val debuffs: List<Aura>, val gameNow: Double, val at: Long,
                      /** the picture: a class icon, the beast family's or the creature type's (Target.lua), 0 = none */
                      val icon: Int = 0)

    /** the current target, null = none (Home leaves the card out) */
    var target: Target? = null
        private set

    /** a "target" document; true = whether a target exists changed (Home lays out again) */
    fun update(msg: JSONObject): Boolean {
        val had = target != null
        target = parse(msg, SystemClock.uptimeMillis())
        invalidate()
        return had != (target != null)
    }

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val name = UiKit.bodyPaint(26f, Color.WHITE)
    private val small = UiKit.bodyPaint(20f, Color.WHITE).also { UiKit.asNumber(it) }
    private val timer = UiKit.bodyPaint(15f, Color.WHITE).also { UiKit.asNumber(it); it.textAlign = Paint.Align.CENTER }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val t = target ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val edge = colour(t)
        // the well: the kit's dark panel, its rim in the target's colour
        r.set(2f, 2f, w - 2f, h - 2f)
        panel.style = Paint.Style.FILL; panel.color = Color.argb(235, 22, 16, 12)
        c.drawRoundRect(r, 12f, 12f, panel)
        panel.style = Paint.Style.STROKE; panel.strokeWidth = 3f; panel.color = edge
        c.drawRoundRect(r, 12f, 12f, panel)

        val pad = 12f
        // the picture on the left of the name and bars (a class icon, the beast family's or the creature type's)
        var left = pad
        val pic = if (t.icon > 0) Icons.get(t.icon) else null
        if (pic != null) {
            val ps = 64f
            r.set(pad, pad, pad + ps, pad + ps)
            c.drawBitmap(pic, null, r, null)
            panel.style = Paint.Style.STROKE; panel.strokeWidth = 2f; panel.color = edge
            c.drawRect(r, panel)
            left = pad + ps + 10f
        }
        var y = pad + name.textSize
        // line 1: level, name, elite / rare
        val lv = when { t.level == null -> ""; t.level < 0 -> "?? "; else -> "${t.level} " }
        val tag = when (t.cls) { "elite" -> " (Elite)"; "rare" -> " (Rare)"; "rareelite" -> " (Rare Elite)"; "worldboss" -> " (Boss)"; else -> "" }
        name.color = edge
        val title = TextFit.fit(lv + t.name + tag, name, w - left - pad)
        c.drawText(title, left, y, name)

        // health bar: only when the game lets it be read (this client hides other units' health from add-ons, measured 2026-11-07;
        // a dead target still says so)
        y += 10f
        val barH = 26f
        if (t.hp != null || t.dead) {
            r.set(left, y, w - pad, y + barH)
            bar(c, r, t.hp, if (t.dead) Color.rgb(90, 90, 90) else Color.rgb(40, 170, 60), if (t.dead) "Dead" else "${t.hp}%")
            y += barH + 4f
        }
        // power bar (thin)
        if (t.pw != null && !t.dead) {
            r.set(left, y, w - pad, y + 10f)
            bar(c, r, t.pw, powerColour(t.powerType), null)
            y += 14f
        }
        // the cast bar while it casts
        val now = gameNow(t)
        t.cast?.takeIf { now < it.end && it.end > it.start }?.let { cast ->
            r.set(left, y, w - pad, y + 22f)
            val done = ((now - cast.start) / (cast.end - cast.start)).coerceIn(0.0, 1.0)
            val p = (if (cast.channel) 1.0 - done else done) * 100
            bar(c, r, p.toInt(), Color.rgb(230, 170, 40), cast.name)
            y += 26f
            postInvalidateDelayed(50)
        }
        // the auras: buffs, then debuffs (the player's own ringed in gold), as far as the width allows; under the picture
        y = maxOf(y + 4f, if (pic != null) pad + 64f + 8f else 0f)
        val size = (h - y - pad).coerceIn(0f, 44f)
        if (size >= 24f) {
            var x = pad
            for ((a, debuff) in t.buffs.map { it to false } + t.debuffs.map { it to true }) {
                if (x + size > w - pad) break
                aura(c, a, debuff, x, y, size, now)
                x += size + 6f
            }
        }
        if (t.cast != null || (t.buffs + t.debuffs).any { it.exp > 0 }) postInvalidateDelayed(500)
    }

    private fun bar(c: Canvas, box: RectF, percent: Int?, colour: Int, text: String?) {
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(200, 0, 0, 0)
        c.drawRoundRect(box, 6f, 6f, fill)
        if (percent != null) {
            fill.color = colour
            val save = c.save()
            c.clipRect(box.left, box.top, box.left + box.width() * percent.coerceIn(0, 100) / 100f, box.bottom)
            c.drawRoundRect(box, 6f, 6f, fill)
            c.restoreToCount(save)
        }
        if (text != null) {
            small.textAlign = Paint.Align.CENTER
            val fitted = TextFit.fit(text, small, box.width() - 8f)
            c.drawText(fitted, box.centerX(), box.centerY() + small.textSize * 0.35f, small)
            small.textAlign = Paint.Align.LEFT
        }
    }

    private fun aura(c: Canvas, a: Aura, debuff: Boolean, x: Float, y: Float, size: Float, now: Double) {
        r.set(x, y, x + size, y + size)
        Icons.getOrUnknown(a.icon)?.let { c.drawBitmap(it, null, r, null) }
        panel.style = Paint.Style.STROKE; panel.strokeWidth = if (a.mine) 3f else 2f
        panel.color = when { a.mine -> UiKit.SOFT_GOLD; debuff -> Color.rgb(200, 40, 40); else -> Color.argb(160, 0, 0, 0) }
        c.drawRect(r, panel)
        if (a.stacks > 1) { small.textAlign = Paint.Align.RIGHT; c.drawText("${a.stacks}", x + size - 2f, y + size - 3f, small); small.textAlign = Paint.Align.LEFT }
        if (a.exp > 0) {
            val left = a.exp - now
            if (left > 0) c.drawText(if (left >= 60) "${(left / 60).toInt()}m" else "${left.toInt()}", x + size / 2f, y + size + timer.textSize, timer)
        }
    }

    /** the game's clock now: the document's own GetTime plus the time since it arrived */
    private fun gameNow(t: Target) = t.gameNow + (SystemClock.uptimeMillis() - t.at) / 1000.0

    companion object {
        fun parse(o: JSONObject, at: Long): Target? {
            if (o.optString("type") != "target" || !o.optBoolean("has")) return null
            fun auras(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.mapNotNull { x ->
                val icon = x.optInt("i", 0).takeIf { it > 0 } ?: return@mapNotNull null
                Aura(icon, x.optInt("s", 1), x.optDouble("exp", 0.0), x.optDouble("dur", 0.0), x.optBoolean("m"))
            }
            val cast = o.optJSONObject("cast")?.let { Cast(it.optString("n"), it.optDouble("s"), it.optDouble("e"), it.optBoolean("ch")) }
            return Target(
                name = o.optString("n").ifEmpty { "Target" },
                level = if (o.has("lv")) o.optInt("lv") else null,
                cls = o.optString("c").ifEmpty { null },
                reaction = o.optString("rx", "n"),
                player = o.optBoolean("pl"),
                classFile = o.optString("cl").ifEmpty { null },
                dead = o.optBoolean("dead"),
                hp = if (o.has("hp")) o.optInt("hp") else null,
                pw = if (o.has("pw")) o.optInt("pw") else null,
                powerType = o.optString("pt").ifEmpty { null },
                cast = cast,
                buffs = auras(o.optJSONArray("b")),
                debuffs = auras(o.optJSONArray("d")),
                gameNow = o.optDouble("now", 0.0),
                at = at,
                icon = o.optInt("ic", 0),
            )
        }

        /** a player in their class colour, else friendly green / neutral yellow / hostile red */
        fun colour(t: Target): Int = (if (t.player) CLASS[t.classFile] else null) ?: when (t.reaction) {
            "f" -> Color.rgb(80, 210, 90); "h" -> Color.rgb(235, 70, 60); else -> Color.rgb(240, 210, 60)
        }

        private val CLASS = mapOf(
            "WARRIOR" to Color.rgb(198, 155, 109), "PALADIN" to Color.rgb(244, 140, 186), "HUNTER" to Color.rgb(170, 211, 114),
            "ROGUE" to Color.rgb(255, 244, 104), "PRIEST" to Color.rgb(255, 255, 255), "SHAMAN" to Color.rgb(0, 112, 221),
            "MAGE" to Color.rgb(63, 199, 235), "WARLOCK" to Color.rgb(135, 136, 238), "DRUID" to Color.rgb(255, 124, 10),
        )

        fun powerColour(token: String?): Int = when (token) {
            "RAGE" -> Color.rgb(220, 40, 40); "ENERGY" -> Color.rgb(240, 220, 40); "FOCUS" -> Color.rgb(230, 130, 50)
            else -> Color.rgb(50, 110, 230)
        }
    }

    /** text cut with an ellipsis to fit a width */
    private object TextFit {
        fun fit(s: String, p: Paint, max: Float): String {
            if (p.measureText(s) <= max) return s
            var e = s.length
            while (e > 1 && p.measureText(s.substring(0, e) + "…") > max) e--
            return s.substring(0, e) + "…"
        }
    }
}
