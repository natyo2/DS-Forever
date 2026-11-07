package com.jrees.wowcompanion.stream

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.jrees.wowcompanion.CompanionHost
import com.jrees.wowcompanion.HostShell
import com.jrees.wowcompanion.InputSink
import com.jrees.wowcompanion.LauncherHooks
import com.jrees.wowcompanion.ModeBridge
import com.jrees.wowcompanion.PlayMode
import com.jrees.wowcompanion.PlayModes
import com.jrees.wowcompanion.RootPolicy
import com.jrees.wowcompanion.TapBridge
import com.jrees.wowcompanion.TapSinks
import java.util.concurrent.Executors

/**
 * COMPANION-ONLY MODE ("DSF PC Companion", its own home-screen icon): WoW runs on the PC and ANOTHER streamer shows it on the top screen
 * (Apollo on the PC, Artemis on the Thor), while DS Forever's companion runs on the BOTTOM screen, fed by DS Forever Desktop's bridge.
 *
 * Unlike Streaming (StreamActivity + DsForeverScreen's Presentation, both owned by DS Forever's top-screen activity), the companion here
 * is an activity of its own on the bottom display, so it stays up while Artemis has the top screen. Its window is NOT FOCUSABLE (as the
 * Presentation's: touching it never takes the controller away from Artemis), except while a text field types.
 *
 * - Pairing: the code shown in DS Forever Desktop, on this screen, companion-only (no Moonlight: [StreamPairing.pairCompanionOnly]),
 *   remembered in its own slot ([HostStore.COMPANION_SLOT]) as its own device, so Streaming's pairing is never touched.
 * - The game's data: [BridgeLog] (the same documents Streaming reads), through [PlayModes].
 * - Input: [StreamKeys] (its key-safety rules unchanged) over [BridgeInput] = Desktop's WowInput plays each touch as one plain mouse
 *   click / key press, only while WoW is in front on the PC. Taps are fractions of WoW's own window ([TapBridge.anyShape]).
 * - Started on the top screen, it moves itself to the bottom one and opens the streamer (Artemis / Moonlight) on the top.
 */
class PcCompanionActivity : Activity(), HostShell {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsf-pc-companion") }
    private val store by lazy { HostStore(this, HostStore.COMPANION_SLOT) }
    private var host: CompanionHost? = null
    private var pc: PairedHost? = null
    @Volatile private var bridge: BridgeLog? = null
    private var keys: StreamKeys? = null
    private var discovery: Discovery? = null
    private var found: List<FoundPc> = emptyList()
    private var pcIndex = 0
    private var pairing: PairingView? = null
    private var typing = false
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && moveToBottom()) return
        current = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ModeBridge.pcCompanion = this
        discovery = Discovery(this, preferSubnet = { NetProbe.subnet(this) }) { pcs -> found = pcs; onFound() }.also { it.start() }
        val h = store.host()?.takeIf { it.hasBridge }
        if (h == null) showPairing("") else startCompanion(h)
        if (intent?.getBooleanExtra(EXTRA_OPEN_STREAMER, false) == true) openStreamer()
    }

    override fun onStart() { super.onStart(); started = true; host?.onStart() }
    override fun onStop() { if (typing) focusable(false); started = false; host?.onStop(); super.onStop() }

    /** brought back on the top screen (its icon tapped there while it runs): back to the bottom one */
    override fun onResume() {
        super.onResume()
        if (current === this && moveToBottom()) Log.i(TAG, "reopened on the top screen: moved back down")
    }

    override fun onDestroy() {
        stopCompanion()
        discovery?.stop(); discovery = null
        // a newer instance (moved to the other screen) may already be up: the shared state is only ever reset by the current one
        if (current === this) { current = null; ModeBridge.pcCompanion = null }
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- the two screens

    /** the bottom screen: started on the top one (the icon was tapped there), it opens again on the other screen; true = going away */
    private fun moveToBottom(): Boolean {
        val here = display?.displayId ?: Display.DEFAULT_DISPLAY
        if (here != Display.DEFAULT_DISPLAY) return false
        val other = getSystemService(DisplayManager::class.java)?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.isValid && it.displayId != here } ?: return false     // one screen only: stay here
        return try {
            val again = Intent(this, PcCompanionActivity::class.java).putExtra(EXTRA_OPEN_STREAMER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            finishAndRemoveTask()
            startActivity(again, ActivityOptions.makeBasic().setLaunchDisplayId(other.displayId).toBundle())
            Log.i(TAG, "moved to display ${other.displayId}")
            true
        } catch (t: Throwable) { Log.w(TAG, "move to the bottom screen: ${t.message}"); false }
    }

    /**
     * THE FOCUS GOES BACK TO THE TOP SCREEN (the Thor, 2026-11-07: "DSF PC Companion isn't responding" right after pairing). Android
     * keeps one focused app; once this activity is it (its pairing screen was touched, its icon opened it) and its window is NOT
     * FOCUSABLE, the controller's keys wait for a window that never comes and Android calls it an ANR. So whenever this becomes the top
     * app while not typing, the streamer is brought back to the front on the top screen (its task as it is: the stream is not restarted).
     */
    override fun onTopResumedActivityChanged(isTop: Boolean) {
        super.onTopResumedActivityChanged(isTop)
        if (isTop && host != null && !typing) handBack()
    }

    private var handedBackAt = 0L
    private fun handBack() {
        if ((display?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY) return     // one screen: nothing to hand back to
        val now = android.os.SystemClock.uptimeMillis()
        if (now - handedBackAt < 1_500) return
        handedBackAt = now
        if (openStreamer()) Log.i(TAG, "focus handed back to the top screen")
    }

    /** the streamer (Artemis, else Moonlight) on the top screen; false = none installed */
    private fun openStreamer(): Boolean {
        val launch = STREAMERS.firstNotNullOfOrNull { p -> try { packageManager.getLaunchIntentForPackage(p) } catch (_: Throwable) { null } }
            ?: return false
        return try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
            true
        } catch (t: Throwable) { Log.w(TAG, "open the streamer: ${t.message}"); false }
    }

    // ---------------------------------------------------------------- pairing

    private fun showPairing(note: String) {
        stopCompanion()
        focusable(true)
        val v = PairingView(this, onPair = { pair(it) }, onBack = { finish() }, onQuality = {}, onPcStep = { stepPc(it) }, quality = false)
        pairing = v
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(v, FrameLayout.LayoutParams(StreamLook.dp(this, 620f), FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        setContentView(root)
        showPc()
        if (note.isNotEmpty()) v.setMessage(note)
    }

    private fun selectedPc(): FoundPc? = found.getOrNull(pcIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0)))
    private fun stepPc(by: Int) { if (found.isNotEmpty()) { pcIndex = ((pcIndex + by) % found.size + found.size) % found.size; showPc() } }

    private fun showPc() {
        val p = selectedPc()
        pairing?.setPc(when {
            p == null -> "Looking for your PC on this Wi-Fi… (DS Forever Desktop must be running)"
            !p.hasBridge -> "${p.name} (${p.address}) has no DS Forever Desktop running"
            else -> "PC: ${p.name} (${p.address})${if (found.size > 1) "   ·   LB / RB: ${found.size} PCs" else ""}"
        })
    }

    private fun pair(code: String) {
        val view = pairing ?: return
        val p = selectedPc() ?: run { view.setMessage("No PC found yet: is DS Forever Desktop running, on the same Wi-Fi?"); return }
        view.busy = true
        view.setMessage("")
        worker.execute {
            val steps = RealPairSteps(store.identity(), store.clientId(), deviceName = "AYN Thor (companion)", stream = false)
            StreamPairing(steps) { s -> main.post { pairState(s) } }.pairCompanionOnly(p, code, NetProbe.subnet(this))
        }
    }

    private fun pairState(s: StreamPairing.State) {
        val view = pairing ?: return
        when (s) {
            is StreamPairing.State.Working -> view.setMessage(s.what)
            is StreamPairing.State.BadCode -> { view.busy = false; view.setMessage(s.message) }
            is StreamPairing.State.WrongCode -> { view.busy = false; view.reset()
                view.setMessage("That's not the code on the PC.${if (s.triesLeft >= 0) " ${s.triesLeft} tries left." else ""}") }
            is StreamPairing.State.Failed -> { view.busy = false; view.setMessage(s.message) }
            is StreamPairing.State.ShowPin -> {}
            is StreamPairing.State.Paired -> {
                val h = Route.after(s.host.copy(address = ""), s.host.address, NetProbe.subnet(this))
                store.save(h)
                Log.i(TAG, "paired with ${h.name} (${h.address}), companion only")
                startCompanion(h)
            }
        }
    }

    /** the paired PC seen at a new address (DHCP): follow it, by Desktop's id */
    private fun onFound() {
        if (pcIndex >= found.size) pcIndex = 0
        if (pairing != null) { showPc(); return }
        val h = pc ?: return
        val same = found.firstOrNull { it.hasBridge && h.desktopId.isNotEmpty() && it.desktopId == h.desktopId } ?: return
        if (AddressPolicy.decide(h.address, same.address, NetProbe.subnet(this)) != AddressPolicy.Move.MOVE) return
        Log.i(TAG, "${h.name} moved: ${h.address} -> ${same.address}")
        val moved = h.copy(address = same.address, bridgePort = same.bridgePort).also { store.save(it) }
        pc = moved
        bridgeFor(moved)
    }

    // ---------------------------------------------------------------- the companion

    private fun startCompanion(h: PairedHost) {
        pairing = null
        pc = h
        RootPolicy.allowed = false
        // taps are fractions of WoW's window on the PC, whatever its shape (TapBridge.anyShape); the PC plays them (WowInput)
        TapBridge.anyShape = true
        com.jrees.wowcompanion.ArtHarvest.mapsFirst = true     // before any art run starts (the icon retry may start one first)
        com.jrees.wowcompanion.HomeView.aurasOnTop = true        // the game's own buffs show on the top screen (Hub.lua, set 263)
        InputSink.displayW = TapBridge.ANY_SHAPE; InputSink.displayH = TapBridge.ANY_SHAPE
        LauncherHooks.pkg = packageName
        LauncherHooks.gameUp = { true }
        LauncherHooks.gameStartSec = System.currentTimeMillis() / 1000
        LauncherHooks.inFront = { false }               // the sign-in is the PC's business (Desktop), never typed from here
        val k = StreamKeys(BridgeInput { bridge }, TapBridge.ANY_SHAPE, TapBridge.ANY_SHAPE).also { keys = it }
        TapSinks.wire(PlayMode.STREAM, null, k)
        keyboard = { vk, down -> bridge?.send(BridgeProtocol.inputKbd(vk, down)) == true }
        bridgeFor(h)
        focusable(false)
        host = CompanionHost(this, this).also { it.onCreate(null); if (started) it.onStart() }
        handBack()                                      // the pairing screen had the focus: the controller is Artemis's again
        Log.i(TAG, "companion up for ${h.name} (${h.address}:${h.bridgePort})")
    }

    /** the companion reads the PC's log through the bridge (a new address or PC = a new key, so the companion follows it) */
    private fun bridgeFor(h: PairedHost) {
        val id = store.clientId()
        PlayModes.set(PlayMode.STREAM, { onMsg, onStatus ->
            BridgeLog(h.address, h.bridgePort, h.bridgeTls, h.bridgeFingerprint, h.bridgeToken, id, WsClient, onMsg, onStatus,
                onAuthLost = { main.post { authLost() } }, onGameKit = { kit -> worker.execute { useKit(kit) } }).also { bridge = it }
        }, "${h.desktopId}@${h.address}:${h.bridgePort}")
    }

    /**
     * THE GAME ART (icons, UI kit, maps), built from the PC's install: Desktop's game kit (GameKit.cs: .build.info, its configs, 16 empty
     * bucket indexes) becomes a CASC folder in this app's own storage, the art harvest's game folder (GameFiles' "game_path"); every
     * game file then reads as missing and the harvest fetches only what its art needs from Blizzard's public CDN, as for a direct
     * install. A new build on the PC = the harvest runs again for it. Worker thread.
     */
    private fun useKit(kit: BridgeMsg.GameKit) {
        if (kit.why.isNotEmpty()) { Log.w(TAG, "game kit: ${kit.why}"); return }
        val root = java.io.File(filesDir, KIT_DIR)
        val oldInfo = runCatching { java.io.File(root, ".build.info").readBytes() }.getOrNull()
        try {
            for ((rel, bytes) in kit.files) {
                val f = java.io.File(root, rel)
                if (f.isFile && f.readBytes().contentEquals(bytes)) continue
                f.parentFile?.mkdirs()
                val tmp = java.io.File(f.path + ".part")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
            // a former build's configs / indexes go (the folder is the kit, nothing else)
            for (d in listOf("Data/config", "Data/data", "Data/indices")) java.io.File(root, d).walkBottomUp().filter { it.isFile }
                .filter { it.relativeTo(root).invariantSeparatorsPath.let { p -> p !in kit.files && p !in kit.fetch } }.forEach { it.delete() }
            for ((rel, size) in kit.fetch) fetchBig(root, rel, size)
        } catch (t: Throwable) { Log.w(TAG, "game kit: not written (${t.message})"); return }
        getSharedPreferences(com.jrees.wowcompanion.GameFiles.PREFS, MODE_PRIVATE).edit()
            .putString(com.jrees.wowcompanion.GameFiles.KEY_GAME_PATH, root.path).apply()
        val changed = oldInfo == null || !oldInfo.contentEquals(kit.files[".build.info"])
        Log.i(TAG, "game kit: ${kit.files.size} files${if (changed) ", a new build" else ""}; art harvest starts")
        com.jrees.wowcompanion.ArtHarvest.mapsFirst = true
        if (changed && oldInfo != null) com.jrees.wowcompanion.ArtHarvest.restart(this)
        com.jrees.wowcompanion.ArtHarvest.start(applicationContext)
    }

    /** one of the kit's big files from Desktop's /v1/kit/ (the group indexes the CDN does not serve), unless already here whole */
    private fun fetchBig(root: java.io.File, rel: String, size: Long) {
        val f = java.io.File(root, rel)
        if (f.isFile && f.length() == size) return
        val h = pc ?: return
        val part = java.io.File(f.path + ".part")
        f.parentFile?.mkdirs()
        val c = java.net.URL("http://${h.address}:${h.bridgePort}/v1/kit/$rel").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("X-Dsf-Device", store.clientId()); c.setRequestProperty("X-Dsf-Token", h.bridgeToken)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            if (part.length() != size) throw java.io.IOException("${part.length()} of $size bytes")
            if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
            Log.i(TAG, "game kit: $rel (${size / 1024 / 1024} MB) from the PC")
        } finally { c.disconnect(); part.delete() }
    }

    private fun authLost() {
        Log.w(TAG, "the PC no longer knows this Thor: pair again")
        store.forget()
        showPairing("DS Forever Desktop no longer knows this Thor: enter the code shown on the PC.")
    }

    private fun stopCompanion() {
        host?.let { if (started) it.onStop(); it.onDestroy() }
        host = null
        keys?.releaseAll(); keys = null
        if (current !== this) return
        keyboard = null
        TapSinks.wire(PlayMode.LOCAL, null, null)
        PlayModes.set(PlayMode.LOCAL, null, "")
        bridge = null
        TapBridge.anyShape = false
    }

    // ---------------------------------------------------------------- text fields (as DsForeverScreen's HostPresentation)

    /**
     * The window is NOT FOCUSABLE so Artemis keeps the controller; a touch on a text field (Chat's line, Bags' search) makes it focusable
     * for the typing, a touch anywhere else gives the focus back.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (host != null && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val field = (hit(window.decorView, ev.rawX, ev.rawY) as? EditText)?.takeIf { it.isFocusable && it.isEnabled }
            if ((field != null) != typing) focusable(field != null)
            field?.post {
                field.requestFocus()
                getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun focusable(on: Boolean) {
        val wasTyping = typing
        typing = on && host != null
        if (on) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        else {
            if (wasTyping) main.post { handBack() }     // the typing is over: the controller goes back to the streamer
            window.currentFocus?.let { f ->
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(f.windowToken, 0)
                f.clearFocus()
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
    }

    private fun hit(v: View, x: Float, y: Float): View? {
        if (v.visibility != View.VISIBLE) return null
        val at = IntArray(2); v.getLocationOnScreen(at)
        if (x < at[0] || y < at[1] || x >= at[0] + v.width || y >= at[1] + v.height) return null
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) hit(v.getChildAt(i), x, y)?.let { return it }
        return v
    }

    // ---------------------------------------------------------------- HostShell
    override fun setHostContent(v: View) = setContentView(v)
    override fun hostGone(): Boolean = isFinishing || isDestroyed
    override fun recreateHost() { main.post { pc?.let { stopCompanion(); startCompanion(it) } } }
    override fun hostWindow() = window
    override fun hostIntent(): Intent? = null
    override fun startHostActivity(i: Intent) = startActivity(i)

    companion object {
        const val TAG = "DSFPCCOMP"
        /** the one companion-only screen that owns the shared state (PlayModes, TapSinks, TapBridge.anyShape, ModeBridge.pcCompanion) */
        @Volatile private var current: PcCompanionActivity? = null
        /** the Bluetooth keyboard's way to the PC (KeyboardBridgeService), null = no companion connected; false = not sent */
        @Volatile var keyboard: ((vk: Int, down: Boolean) -> Boolean)? = null
        const val EXTRA_OPEN_STREAMER = "dsforever_open_streamer"
        /** the CASC folder built from the PC's game kit (app-private: no file access needed) */
        const val KIT_DIR = "pcgame"
        /** the top screen's streamer, first installed wins: Artemis (Apollo's client), then Moonlight */
        val STREAMERS = listOf("com.limelight.noir", "com.limelight", "com.limelight.debug")
    }
}
