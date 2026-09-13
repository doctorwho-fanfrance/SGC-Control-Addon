# Changelog

## 0.5.3
- Fixed the OFFWORLD ACTIVATION overlay position so it is centered directly on the Stargate instead of offset to the left.
- Alert box now derives its placement from the actual gate center coordinates for stable alignment.

## 0.5.2
- Incoming/outgoing direction now comes from JSG itself: `EnumStargateState.incoming()` during the incoming sequence and `StargateConnection.isInitiating()` once a wormhole is connected.
- Fixed false `OFFWORLD ACTIVATION` alerts during normal outbound dialing.
- Outbound destination glyphs are now latched server-side until the gate returns to idle. Closing and reopening the terminal during a dial/connected wormhole restores the correct destination instead of falling back to the SGC/Earth address.

## 0.5.1
- Fixed the terminal block model/texturing issue by replacing the invalid rotated model with a stable laptop-style textured model.
- Enlarged and re-centered the red `OFFWORLD ACTIVATION` warning so it sits in the middle of the Stargate display.
- Added explicit centered text rendering for monitor alerts.

## 0.5.0
- Reworked incoming-activation detection and latched it across the full incoming sequence/connected phase.
- The main monitor now shows a centered red `OFFWORLD ACTIVATION` alert box during incoming activations.
- The seven right-hand chevron boxes now switch to red sequential status blocks for incoming activations, and stay fully red once the incoming wormhole is connected.
- Improved the terminal block into a more laptop-like computer model and kept full horizontal placement rotation support.

## 0.4.9
- Replaced the simple terminal block with a more realistic floor console / screen model.
- The terminal now uses a horizontal facing property, so it can be placed in the direction the player wants.
- Incoming wormhole detection now raises a blinking red `OFFWORLD ACTIVATION` warning on the SGC monitor.
- During incoming dialing, the seven right-hand boxes now illuminate red progressively instead of showing the selected outbound address.
- Lower chevrons are now hidden behind the control-keyboard overlay instead of bleeding through the panel.
- Database row selection is preserved by a stable target fingerprint, which prevents the cursor from jumping back to the Earth row when moving onto `LOCAL STARGATE`.
- Outbound address symbols are suppressed during incoming-activation mode so the monitor no longer reuses the currently selected Earth/local route as a fake incoming address.

## 0.4.8
- Correct 7/8/9-chevron physical lock order: 7-chevron destinations now finish on the top chevron.
- Energy-field bars fit fully inside their frame and now have continuously moving pale-yellow scan markers.
- Database cursor is no longer forced back to the first remote/Earth entry when selecting the local row.
- Top-lock transition remains steady (no blink).



## 0.4.4
- Manual Milky Way glyph keys are now clickable.
- Clicking a glyph toggles it in a manual address buffer (up to 8 glyphs).
- `DIAL ON/OFF` now dials the manual address when at least 6 glyphs are selected, adding the point of origin automatically.
- Destination dialing no longer relies only on a volatile list index: the client sends a stable target fingerprint (dimension, position, address and name), preventing a selected Abydos/Chulak/etc. route from being replaced by the local/Earth entry when JSG refreshes its network list.
- The right-hand dialing slots now use both a frozen copy of the selected destination and, once dialing starts, the authoritative `getDialedAddress()` symbols read back from JSG itself.
- JSG route length (7/8/9 symbols) now prefers the real `getMinimalSymbolsToDial(...)` result instead of only guessing from Minecraft dimensions.
- Reworked gate artwork with a high-resolution transparent vector-style frame asset for smoother rails and 39 ring cells.
- Reworked chevrons with dedicated high-resolution idle/locked assets and rotated housings around the gate.
- Keeps the keyboard toggle next to the state display and preserves TAB planetary database behavior.

## 0.4.3
- Froze selected destination during dialing.
- Moved keyboard toggle near IDLE.
- Improved gate and chevron geometry.