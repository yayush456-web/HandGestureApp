# Hand Gesture Control (personal use)

Android app that watches your front camera, recognizes hand shapes, and controls
brightness/volume with a pinch-and-rotate "knob" gesture. No laptop needed to build —
GitHub Actions compiles the APK for you.

## Gestures
- **Open palm** → activate / deactivate
- **Fist** (while active) → open quick menu
- **1 finger** → select Brightness
- **2 fingers** → select Volume
- **Pinch (thumb+index) and rotate your wrist** → clockwise increases, counterclockwise decreases
- **Fist** while adjusting → back to menu
- **Open palm** any time → deactivate

## How to build the APK (from your phone, no laptop)

1. Create a new **public or private** GitHub repo (e.g. `hand-gesture-control`).
2. Upload every file/folder in this project to that repo, keeping the same folder
   structure (including the hidden `.github` folder — GitHub's web uploader keeps it
   if you drag the whole extracted folder in, or use the GitHub mobile app's
   "Add file → Upload files").
3. Once pushed to the `main` branch, go to the repo's **Actions** tab. A workflow
   called "Build APK" runs automatically (takes ~3-5 minutes).
4. When it finishes (green check), open that run → scroll to **Artifacts** →
   download `HandGestureControl-debug-apk` → unzip → you get `app-debug.apk`.
5. Transfer that APK to your phone (or download directly if you're on the phone)
   and install it (you'll need to allow "install unknown apps" for your browser/files app once).

## First run on your phone
1. Open the app, tap through the 3 permission buttons (overlay, write-settings, camera).
2. Tap **Start Gesture Control**. First launch downloads the ~8MB hand-tracking
   model from Google's public bucket (needs internet once).
3. A small status dot appears top-right of your screen. Show your palm to it to activate.

## Notes / limitations of this first version
- Runs one hand at a time, front camera only.
- The floating status text is intentionally minimal (no fancy radial dial yet) —
  easy to reskin once the gesture logic feels good in practice.
- Rotation direction (clockwise = increase) is defined in `GestureStateMachine.kt`;
  flip the `+1`/`-1` in `handlePinchRotation` if it feels backwards for your camera mirroring.
- Debounce is 5 consecutive frames per gesture to avoid flicker; tune `debounceFrames`
  in `GestureStateMachine.kt` if it feels sluggish or too twitchy.
- Battery/CPU: continuous camera + ML inference in a foreground service will drain
  battery faster than normal use — this is expected for a personal utility like this.
