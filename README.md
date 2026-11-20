# Grid Cam (Pixel 7 Pro)

A camera app that shows composition grids, detects your subject, suggests the best grid,
draws a red circle where the subject should go, then helps you edit the photo.

## What it does
- 10 grid modes: Auto, Rule of thirds, Golden ratio, Golden spiral, Diagonals,
  Golden triangles, Center, 4x4 square, Harmonic armature, Video safe zones
- Auto mode picks the grid from the subject's size/shape and shows:
  yellow box + dot = your subject, red circle = where to put it,
  text = which way to pan/tilt. Circle turns green = perfect, take the photo.
- Pixel-style auto level: appears when slightly tilted, snaps to one line with a
  vibration when level, then fades. Works in portrait and landscape; two-crosshair
  level for top-down shots
- Auto-straighten: remembers the tilt at capture; Auto fix rotates and crops it level
- Tap anywhere to focus; pinch or tap 1× 2× 5× 10× 30× to zoom (camera zoom up to the
  phone's limit for apps, then digital crop up to 30×)
- After each photo: brightness/contrast/colour check, Lightroom slider tips,
  Auto fix, 6 sliders, Save edited copy, Open in Lightroom
- Photos are saved in Photos > Pictures/GridCam

## Install - Option A: Android Studio (recommended)
1. Install Android Studio on your PC: https://developer.android.com/studio (use all default options).
2. Unzip GridCam.zip. In Android Studio: File > Open > select the GridCam folder > Trust project.
   Wait until the bottom bar says "Gradle sync finished" (first time 5-15 min, it downloads tools).
   If it offers to "upgrade" the Android Gradle Plugin, you can click Upgrade or ignore it.
3. On the Pixel: Settings > About phone > tap "Build number" 7 times (Developer options on).
   Then Settings > System > Developer options > turn on "USB debugging".
4. Connect the phone with a USB cable, tap "Allow" on the phone.
5. In Android Studio choose your Pixel 7 Pro at the top and press the green Run (play) button.
   The app "Grid Cam" installs and opens. Allow camera permission. Done - it stays on your phone.

## Install - Option B: no Android Studio (GitHub builds the APK for you)
1. Make a free account at github.com, click New repository, name it GridCam, Create.
2. Click "uploading an existing file", drag in everything inside the unzipped GridCam folder
   (including the .github folder), Commit.
3. Open the Actions tab, wait for "Build APK" to turn green (about 5 min), open it,
   download "GridCam-apk" at the bottom, unzip it -> app-debug.apk.
4. Send app-debug.apk to your phone (Google Drive / USB), tap it, allow "Install unknown apps", Install.
