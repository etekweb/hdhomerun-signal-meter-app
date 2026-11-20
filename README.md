# HDHR Meter

Android app for an HDHomeRun on the local network. It finds the tuner, lists channels, plays the live MPEG-TS stream, and draws a signal meter over the picture.

The phone and the HDHomeRun need to be on the same LAN. The app uses the tuner’s normal HTTP API on port 80 and the stream on port 5004. Discovery also sends the SiliconDust UDP packet to port 65001.

## What you can do

- Lineup, DVR, and Settings are tabs along the bottom. Lineup is the channel list for the selected tuner. DVR lists recordings from an HDHomeRun record engine on the network. Open a recording to see its title, episode, channel, times, and description, and play it. Record the current program from the player Info tab, or open a channel in the lineup and choose a program. A confirmation asks how early and how late to record.
- Open the channel lineup. Search, and filter to HD, favorites, or recently viewed channels. Recents lists channels you have started watching, newest first, and keeps that list for each tuner. Copy-protected channels are listed and marked Protected. They are not played.
- Tap the free/total storage value in DVR for a used/scheduled/free meter and estimated recording hours and minutes at 480i, 720p, and 1080i. Planning assumptions for original ATSC 1.0 streams are 3, 12, and 16 Mbps respectively (1.35, 5.4, and 7.2 GB/hour); actual broadcast bitrates vary. The forecast includes padding and the remaining portion of active recordings. Because the lineup only distinguishes SD and HD, forecasts use 480i for SD and 1080i for HD or unknown channels. Only currently listed scheduled airings are included, and unknown durations are flagged. The recording confirmation warns if the proposed recording plus existing scheduled/active recordings is estimated to exceed free space; updating an existing airing replaces its estimate. This warning allows you to continue recording.
- In Settings, choose None, HDHomeRun from the connected tuner, or one or more guide URLs such as `http://10.0.0.241:8080/xmlguide.xmltv`. The lineup and the player show the program on now, the next program, and the time it starts.
- Tuners found on the network are listed with tuners you have added. Choose one. If the last tuner does not answer, Lineup and DVR ask you to pick a tuner in Settings.
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

Debug builds (including Android Studio's Run button) and release builds use the same production signing key. The default keystore is `~/.gradle/AndroidAppKeystore`, with alias `key0`. Add its passwords to the ignored `local.properties` file, keeping the existing `sdk.dir` entry:

```properties
signing.storePassword=YOUR_KEYSTORE_PASSWORD
signing.keyPassword=YOUR_KEY_PASSWORD
```

To use a different keystore location or alias, also set `signing.storeFile=C:/path/to/keystore` and `signing.keyAlias=your-alias`. Use forward slashes for Windows paths. Java properties escaping applies to passwords (for example, write a literal backslash as `\\`). Keep the keystore and passwords out of source control. Signing requires both passwords; Android Studio's saved Generate Signed APK dialog passwords are not used by Gradle.

From a shell, with `JAVA_HOME` pointing at Android Studio’s `jbr`:

```
gradlew.bat assembleDebug
gradlew.bat test
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Publish a GitHub release

In the repository's **Settings → Secrets and variables → Actions → New repository secret**, add these four secrets from the signing setup above. Use the same keystore and alias as the APKs already installed on your devices so Android can update them in place.

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the entire existing keystore file |
| `ANDROID_KEY_ALIAS` | Existing key alias, normally `key0` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_PASSWORD` | Password for the signing key |

For the default keystore path, copy its Base64 to the clipboard in PowerShell, then paste it into the first secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE/.gradle/AndroidAppKeystore")) | Set-Clipboard
```

Use the actual path if `signing.storeFile` points elsewhere. Enter the original passwords as the secret values, without the escaping used in `local.properties`.

Once the workflow is on GitHub, tag the commit you want to release and push the tag:

```powershell
git tag 1.1.0
git push origin 1.1.0
```

Use increasing numeric versions such as `1.1` or `1.1.0`, with an optional lowercase `v`. Those two forms represent the same Android version, so use only one per release. Major versions range from 1 to 2099, and minor and patch numbers range from 0 to 999. Wait for each release to publish before pushing the next version. The action runs the unit tests, builds a signed APK with the tag's version, and publishes it as the latest GitHub Release. To build locally with the same version as a tag, run `gradlew.bat assembleDebug -PreleaseVersion=1.1.0`.

Install a build with the updater once from the [GitHub Releases page](https://github.com/etekweb/hdhomerun-signal-meter-app/releases). For later releases, open **Settings → App updates → Check for updates** and select **Download and install**. Android may first ask you to allow this app to install packages, then asks you to confirm the update.

## Limits

- The stream is the original broadcast. Many ATSC channels are MPEG-2 video and AC-3 audio. Playback uses LibVLC, which decodes MPEG-2 in software. Phones such as the Pixel 9 have no MediaCodec MPEG-2 decoder, and an HDHomeRun FLEX cannot transcode.
- ATSC 3.0 channels may be encrypted, or use HEVC and AC-4. The app does not decrypt them.
- DRM channels return HDHomeRun error 811 and are not started.
- The first HTTP error 804 (the chosen tuner is busy) is retried once on `/auto/`, which asks the device to pick a free tuner.
- Closing the picture-in-picture window, or leaving the player, releases the tuner. The screen stays on the player so you can resume.
- Cleartext HTTP is allowed because HDHomeRun devices speak HTTP on the LAN.
