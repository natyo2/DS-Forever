package com.jrees.wowcompanion.stream

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** the Bluetooth keyboard's keys as the PC's (KeyboardBridgeService) */
class KeyboardMapTest {
    @Test fun lettersDigitsFKeysAndModifiers() {
        assertEquals(0x41, KeyboardMap.vk(KeyEvent.KEYCODE_A)); assertEquals(0x5A, KeyboardMap.vk(KeyEvent.KEYCODE_Z))
        assertEquals(0x30, KeyboardMap.vk(KeyEvent.KEYCODE_0)); assertEquals(0x39, KeyboardMap.vk(KeyEvent.KEYCODE_9))
        assertEquals(0x70, KeyboardMap.vk(KeyEvent.KEYCODE_F1)); assertEquals(0x7B, KeyboardMap.vk(KeyEvent.KEYCODE_F12))
        assertEquals(0xA4, KeyboardMap.vk(KeyEvent.KEYCODE_ALT_LEFT)); assertEquals(0xA0, KeyboardMap.vk(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(0x1B, KeyboardMap.vk(KeyEvent.KEYCODE_ESCAPE)); assertEquals(0x08, KeyboardMap.vk(KeyEvent.KEYCODE_DEL))
        assertEquals(0x26, KeyboardMap.vk(KeyEvent.KEYCODE_DPAD_UP)); assertEquals(0x60, KeyboardMap.vk(KeyEvent.KEYCODE_NUMPAD_0))
    }

    @Test fun theWindowsKeysHaveNone() {
        assertNull(KeyboardMap.vk(KeyEvent.KEYCODE_META_LEFT)); assertNull(KeyboardMap.vk(KeyEvent.KEYCODE_META_RIGHT))
        assertNull(KeyboardMap.vk(KeyEvent.KEYCODE_SEARCH)); assertNull(KeyboardMap.vk(KeyEvent.KEYCODE_HOME))
    }
}
