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
