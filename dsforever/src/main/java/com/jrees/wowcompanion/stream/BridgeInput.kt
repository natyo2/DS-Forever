package com.jrees.wowcompanion.stream

/**
 * COMPANION-ONLY MODE's keyboard + mouse channel: [StreamKeys] speaks to it exactly as to Moonlight's ([MoonInput]), and each call goes
 * to DS Forever Desktop's bridge as one "input" message (docs/STREAM_BRIDGE_PROTOCOL.md section 7), which plays it into WoW with plain
 * keyboard / mouse events (WowInput.cs: only while WoW is in front, no system keys, a flood guard). Used when another streamer (Apollo +
 * Artemis) shows the PC's screen, so there is no Moonlight session to carry input. [bridge] null = not connected: nothing is sent.
 */
class BridgeInput(private val bridge: () -> BridgeLog?) : MoonInput {
    override fun key(vk: Int, down: Boolean, mods: Int) = send(BridgeProtocol.inputKey(vk, down))
    override fun mousePosition(x: Int, y: Int, refW: Int, refH: Int) = send(BridgeProtocol.inputPos(x, y, refW, refH))
    override fun mouseMove(dx: Int, dy: Int) = send(BridgeProtocol.inputMove(dx, dy))
    override fun mouseButton(down: Boolean, button: Int) = send(BridgeProtocol.inputButton(button, down))
    override fun scroll(amount: Int) = send(BridgeProtocol.inputScroll(amount))

    private fun send(text: String) { bridge()?.send(text) }
}

/**
 * COMPANION-ONLY MODE's tap sink: [StreamKeys] for every key (its key-safety rules), but its own exact pointer. The add-on's tap pad on
 * a 1920 x 1080 game has cells 6 x 2 px (the Thor, 2026-11-07: the controller page's taps landed one row off, as "attack"; Tailoring's
 * on its cell's edge), so a tap's point goes to Desktop as a fraction in millionths of WoW's window, never through StreamKeys' whole
 * pixels of a picture; Desktop puts the cursor on that exact pixel (WowInput).
 */
class BridgeTapSink(private val keys: StreamKeys, private val bridge: () -> BridgeLog?) : com.jrees.wowcompanion.TapSink by keys {
    override fun tap(fx: Float, fy: Float) {
        moveTo(fx, fy)
        send(BridgeProtocol.inputButton(StreamKeys.BUTTON_LEFT, true))
        Thread.sleep(StreamKeys.CLICK_MS)
        send(BridgeProtocol.inputButton(StreamKeys.BUTTON_LEFT, false))
    }

    override fun moveTo(fx: Float, fy: Float) {
        val x = Math.round(fx.toDouble().coerceIn(0.0, 1.0) * EXACT).toInt(); val y = Math.round(fy.toDouble().coerceIn(0.0, 1.0) * EXACT).toInt()
        send(BridgeProtocol.inputPos(x, y, EXACT, EXACT))
    }

    private fun send(text: String) { bridge()?.send(text) }

    companion object { const val EXACT = 1_000_000 }
}
