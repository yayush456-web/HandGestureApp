# Hand Gesture Control

Personal Android app that watches the front camera, reads hand shapes, and uses them
to control the phone hands-free — brightness, volume, media playback, an on-screen
cursor, and quick system actions (Back/Home/Recents/Screenshot/Camera/Flashlight).

No laptop needed to build: push to `main` and GitHub Actions compiles the APK.

## Core idea

The same few gestures mean different things depending on what mode you're in — only
one mode is ever active, so there's no ambiguity. This keeps the gesture vocabulary
small (reliable) while still supporting a growing list of actions.

## Modes and gestures

**Always available:**
- **Open palm** → activate (from idle) / deactivate completely (from any active mode)
- **Thumbs up** → open the quick menu (from Active) / return to the quick menu (from any sub-mode)

**From the quick menu, pick a mode:**
| Gesture | Mode |
|---|---|
| 1 finger (index) | Brightness |
| 2 fingers (index + middle) | Volume |
| 3 fingers (index + middle + ring) | Music |
| Pinky only | Cursor |
| Rock shape (index + pinky) | Quick Actions |

**Brightness / Volume:**
Pinch (thumb + index) and rotate your wrist like turning a knob — clockwise increases,
counterclockwise decreases. The overlay shows the live percentage. Releasing the pinch
just pauses adjusting; it won't kick you back to the menu (only thumbs up does that).

**Music:**
A quick pinch toggles play/pause on whatever currently has media focus (Spotify,
YouTube Music, etc.) — same mechanism as a Bluetooth headset button.

**Cursor:**
Move your index finger and a small ring follows it on screen.
- Quick pinch (little movement) = tap
- Pinch that moves before releasing = drag/scroll, replayed as one gesture

Works across other apps, not just this one. Requires the Accessibility permission.

**Quick Actions:**
A centered HUD list: Camera, Flashlight, Screenshot, Back, Home, Recents, Notifications.
- 2 fingers = cycle to the next item (wraps around)
- Pinch = run whichever item is highlighted

Camera and Flashlight work without extra permissions; the rest need Accessibility.

## Permissions

| Permission | Why |
|---|---|
| Camera | hand tracking |
| Display over other apps | the floating status panel, cursor ring, and quick actions HUD |
| Modify system settings | brightness control |
| Accessibility (optional) | lets Cursor mode actually tap/drag, and lets Quick Actions trigger Back/Home/Recents/Screenshot/Notifications. Enabled manually in Settings > Accessibility — there's no programmatic way around that. Everything else works without it. |

## The floating overlay

Top-right panel shows a live camera thumbnail (so you can see what the tracker sees)
plus a status line, while this app is open. Switch to another app and it shrinks to
just a small mode label — tracking keeps running in the background either way.

## Notes

- Front camera, one hand at a time.
- Gestures need to be held steady for a short beat before they register, so
  repositioning your hand doesn't trigger things by accident.
- Continuous camera + on-device ML inference will use noticeably more battery than
  normal use — expected for an always-watching utility like this.
- Rotation direction (clockwise = increase) and gesture sensitivity are tunable in
  `GestureStateMachine.kt` and `GestureUtils.kt` if anything feels backwards or
  twitchy on your device.
