# HDHR Meter

Android app for an HDHomeRun on the local network. It finds the tuner, lists channels, plays the live MPEG-TS stream, and draws a signal meter over the picture.

The phone and the HDHomeRun need to be on the same LAN. The app uses the tuner’s normal HTTP API on port 80 and the stream on port 5004. Discovery also sends the SiliconDust UDP packet to port 65001.

## What you can do

- Scan the network, or type an IP address or hostname.
- Open the channel lineup. Search, and filter to HD or favorites. Copy-protected channels are listed and marked Protected. They are not played.
- Set a guide provider by IP address. The app reads `http://<address>:8080/xmlguide.xmltv` and shows the program on now in the channel lineup and on the player.
- Watch a channel. The stream stops when you leave the player, so the tuner is free for something else. Pressing Home while a channel is playing moves the picture into the system picture-in-picture window and keeps the tuner. Closing that window releases the tuner. Resume tunes it again.
- Read the overlay while a channel is playing:
  - Channel number and name
  - RF channel and modulation, such as `RF 31 · 8VSB`. The tuner’s `ch=` field is used when it is present. Otherwise a US broadcast center frequency is mapped to an RF channel. A frequency that is not on that map is shown in MHz.
  - Strength, quality, and symbol meters, with a peak mark for the current session
  - Picture size, frame rate, video codec, audio codec, and channel layout, once the player has seen them
  - Live bitrate of the program being received
  - RF rate, which is the whole channel’s bitrate reported by the tuner, not the video bitrate

Tap the picture to hide the overlay. Channel up and down skip protected channels.

Quality is the number to aim. Symbol should stay at 100%. Strength matters less.

## Build

Open this folder in Android Studio. It uses JDK 17 bytecode, `compileSdk` 37, and `minSdk` 26 (Android 8.0). Android Studio writes `local.properties` with your SDK path.

From a shell, with `JAVA_HOME` pointing at Android Studio’s `jbr`:

```
gradlew.bat assembleDebug
gradlew.bat test
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Limits

- The stream is the original broadcast. Many ATSC channels are MPEG-2 video and AC-3 audio. Playback uses LibVLC, which decodes MPEG-2 in software. Phones such as the Pixel 9 have no MediaCodec MPEG-2 decoder, and an HDHomeRun FLEX cannot transcode.
- ATSC 3.0 channels may be encrypted, or use HEVC and AC-4. The app does not decrypt them.
- DRM channels return HDHomeRun error 811 and are not started.
- The first HTTP error 804 (the chosen tuner is busy) is retried once on `/auto/`, which asks the device to pick a free tuner.
- Closing the picture-in-picture window, or leaving the player, releases the tuner. The screen stays on the player so you can resume.
- Cleartext HTTP is allowed because HDHomeRun devices speak HTTP on the LAN.
