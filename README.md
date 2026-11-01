# HDHR Meter

Android app for an HDHomeRun on the local network. It finds the tuner, lists channels, plays the live MPEG-TS stream, and draws a signal meter over the picture.

The phone and the HDHomeRun need to be on the same LAN. The app uses the tuner’s normal HTTP API on port 80 and the stream on port 5004. Discovery also sends the SiliconDust UDP packet to port 65001.

## What you can do

- Scan the network, or type an IP address or hostname.
- Open the channel lineup. Search, and filter to HD, favorites, or recently viewed channels. Recents lists channels you have started watching, newest first, and keeps that list for each tuner. Copy-protected channels are listed and marked Protected. They are not played.
- Set a guide provider by IP address. The app reads `http://<address>:8080/xmlguide.xmltv` and shows the program on now in the channel lineup and on the player. Each lineup row also shows the next program and the time it starts.
- Watch a channel. The stream stops when you leave the player, so the tuner is free for something else. Pressing Home while a channel is playing moves the picture into the system picture-in-picture window and keeps the tuner. Closing that window releases the tuner. Resume tunes it again.
- Read the overlay while a channel is playing. The corner by the back button shows the channel number and call sign. The panel has Info and Signal tabs. Info is selected until you pick Signal, and that choice is remembered.
  - Info: program name, episode number, episode title, air time, and description. A smaller block at the bottom shows the next program.
  - Signal: RF channel and modulation, such as `31 · 8VSB`. The tuner’s `ch=` field is used when it is present. Otherwise a US broadcast center frequency is mapped to an RF channel. A frequency that is not on that map is shown in MHz.
  - Strength, quality, and symbol meters, with a peak mark for the current session
  - Picture size, frame rate, video codec, audio codec, and channel layout, once the player has seen them
  - Live bitrate of the program being received
  - RF rate, which is the whole channel’s bitrate reported by the tuner, not the video bitrate

Tap the picture to hide the overlay. Channel up and down skip protected channels. Each tap shows the channel you would land on in the center of the picture. Tuning waits half a second, and another tap restarts that wait, so you can move several channels without loading each one.

### Interpreting signal meter data

- Strength: Raw signal strength level. This basically indicates how close you are to the broadcast tower. This one isn't the most useful for adjusting antenna placement - it's OK if the strength level is lower than the other values.
- Quality: Signal-to-noise ratio. **This is the most useful for dialing in your antenna placement.** The higher the better. Quality levels below ~60% may be at risk of dropping out as signal fluctuates. Also, a highly variable quality level indicates an unstable signal.
- Symbol: When your signal is locked in, the symbol value will always be 100%. Signal dropping out will rapidly lower the symbol value, signal coming back in will rapidly raise the symbol value until it returns to 100%.

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
