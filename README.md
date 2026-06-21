# helpera - Time-Lapse Camera App

An Android application that uses the phone's camera to capture a photo every 5 seconds.

## Built APK
The compiled APK is ready at the root of this folder:
- **File:** `helpera-camera.apk`

## How it Works
1. **Camera Permission**: The app requests Camera access at launch.
2. **Camera Preview**: Once granted, a live camera preview is displayed to let you frame the shots.
3. **Screen Control**: The app keeps the screen awake while it is active in the foreground, ensuring the timer isn't interrupted by device sleep.
4. **Time-lapse Loop**: When you press **START (5s INTERVAL)**:
   - It captures a photo immediately.
   - It starts an interval loop taking a photo every 5 seconds.
   - It updates the UI with the total photos taken and the filename of the last captured picture.
5. **Storage**: Photos are saved to the app's external pictures directory, which **requires no extra runtime permissions** on modern Android devices.

## Where to Find Captured Photos
On your phone's storage, the captured photos are stored in your device's default gallery under:
`DCIM/Camera`

## Rebuilding the App
If you modify the Kotlin code or settings:
1. Run the build script in the terminal:
   ```bash
   bash build_apk.sh
   ```
2. The newly compiled APK will replace `app/build/outputs/apk/debug/app-debug.apk` and can be copied to the root.
