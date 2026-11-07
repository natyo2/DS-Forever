package com.jrees.wowcompanion.stream

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * THE PLAYER'S BLUETOOTH KEYBOARD, STRAIGHT TO WoW ON THE PC (companion-only mode; the user 2026-11-07: "I'd like to use the Bluetooth
 * keyboard without affecting the android behaviour"). Artemis's own keyboard accessibility service takes EVERY key on the Thor, the
 * built-in controller, the AYN button and Home included. This one takes only the keys of an EXTERNAL alphabetic keyboard, and only
 * while the streamer (Artemis / Moonlight) is the app in front and the companion is connected; each key edge goes to Desktop as one
 * "kbd" input (WowInput.cs: played only while WoW is in front, never the Windows / Menu / media keys, Alt+Tab, Alt+F4, Ctrl+Esc).
 * Everything else, and every key while another app is in front, goes on to Android as before. Volume / media keys are Android's.
 *
 * THE CONTROLLER'S COPY (the user, 2026-11-07: "when I press the R or L triggers it does not change the companion view"): Artemis has
 * the controller, so the companion saw none of its presses. Every key of a game controller is COPIED to the companion's pad readers
 * (PadFeed: the layer buttons L / R / ZL / ZR, the face buttons, the D-pad, as StreamTop and the launcher hand them) and never taken:
 * Artemis and the game still get every press. Analog trigger travel is a motion event, which accessibility does not see; the
 * triggers' key presses carry the layers.
 */
class KeyboardBridgeService : AccessibilityService() {
    /** keys whose press this service took: their release goes the same way, whatever is in front by then */
    private val taken = HashSet<Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (fromPad(event)) {
            if (PcCompanionActivity.keyboard != null) try { com.jrees.wowcompanion.PadFeed.key(event) } catch (_: Throwable) {}
            return false
        }
        if (!fromKeyboard(event)) return false
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (!down && taken.remove(code)) { send(code, false); return true }
        if (!down) return false
        if (code in ANDROIDS) return false
        if (event.repeatCount == 0 && !(PcCompanionActivity.keyboard != null && streamerInFront())) return false
        if (event.repeatCount > 0 && code !in taken) return false
        taken.add(code)
        send(code, true)
        return true
    }

    /** a key of a real keyboard plugged in or paired (not the Thor's own controller, AYN or Home buttons) */
    private fun fromKeyboard(e: KeyEvent): Boolean {
        val d = e.device ?: return false
        return d.isExternal && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            (d.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
    }

    /** a game controller's key (the Thor's own pad or a paired one) */
    private fun fromPad(e: KeyEvent): Boolean {
        val s = e.device?.sources ?: e.source
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun streamerInFront(): Boolean =
        try { rootInActiveWindow?.packageName?.toString() in PcCompanionActivity.STREAMERS } catch (_: Throwable) { false }

    /** the key to the PC; a key with no Windows key (Meta, Search...) is still taken, so Android's shortcuts never fire mid-game */
    private fun send(code: Int, down: Boolean) {
        val vk = KeyboardMap.vk(code) ?: return
        val k = PcCompanionActivity.keyboard ?: return
        if (!k(vk, down)) try { Log.i(TAG, "keyboard: not connected, key $code dropped") } catch (_: Throwable) {}
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        for (code in taken.toList()) send(code, false)
        taken.clear()
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "DSFKBD"
        /** always Android's, even from the keyboard (the volume and the media keys) */
        private val ANDROIDS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_BRIGHTNESS_UP, KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP)
    }
}

/** Android keycodes of a PC keyboard -> Windows virtual keys (null = no such key on the PC: taken and dropped) */
object KeyboardMap {
    private val MAP: Map<Int, Int> = buildMap {
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, 0x41 + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, 0x30 + i)
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, 0x70 + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, 0x60 + i)
        put(KeyEvent.KEYCODE_ESCAPE, 0x1B); put(KeyEvent.KEYCODE_BACK, 0x1B)
        put(KeyEvent.KEYCODE_ENTER, 0x0D); put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x0D)
        put(KeyEvent.KEYCODE_TAB, 0x09); put(KeyEvent.KEYCODE_SPACE, 0x20); put(KeyEvent.KEYCODE_DEL, 0x08)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 0x2E); put(KeyEvent.KEYCODE_INSERT, 0x2D)
        put(KeyEvent.KEYCODE_MOVE_HOME, 0x24); put(KeyEvent.KEYCODE_MOVE_END, 0x23)
        put(KeyEvent.KEYCODE_PAGE_UP, 0x21); put(KeyEvent.KEYCODE_PAGE_DOWN, 0x22)
        put(KeyEvent.KEYCODE_DPAD_UP, 0x26); put(KeyEvent.KEYCODE_DPAD_DOWN, 0x28)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 0x25); put(KeyEvent.KEYCODE_DPAD_RIGHT, 0x27)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 0xA0); put(KeyEvent.KEYCODE_SHIFT_RIGHT, 0xA1)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 0xA2); put(KeyEvent.KEYCODE_CTRL_RIGHT, 0xA3)
        put(KeyEvent.KEYCODE_ALT_LEFT, 0xA4); put(KeyEvent.KEYCODE_ALT_RIGHT, 0xA5)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x14); put(KeyEvent.KEYCODE_NUM_LOCK, 0x90); put(KeyEvent.KEYCODE_SCROLL_LOCK, 0x91)
        put(KeyEvent.KEYCODE_SYSRQ, 0x2C); put(KeyEvent.KEYCODE_BREAK, 0x13)
        put(KeyEvent.KEYCODE_MINUS, 0xBD); put(KeyEvent.KEYCODE_EQUALS, 0xBB)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0xDB); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0xDD); put(KeyEvent.KEYCODE_BACKSLASH, 0xDC)
        put(KeyEvent.KEYCODE_SEMICOLON, 0xBA); put(KeyEvent.KEYCODE_APOSTROPHE, 0xDE); put(KeyEvent.KEYCODE_GRAVE, 0xC0)
        put(KeyEvent.KEYCODE_COMMA, 0xBC); put(KeyEvent.KEYCODE_PERIOD, 0xBE); put(KeyEvent.KEYCODE_SLASH, 0xBF)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0x6F); put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0x6A)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0x6D); put(KeyEvent.KEYCODE_NUMPAD_ADD, 0x6B); put(KeyEvent.KEYCODE_NUMPAD_DOT, 0x6E)
    }

    fun vk(androidKeycode: Int): Int? = MAP[androidKeycode]
}
