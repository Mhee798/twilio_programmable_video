## Unreleased

- **BREAKING**: the Dart SDK constraint is now `>=3.0.0 <4.0.0` (was `>=2.12.0 <3.0.0`) and the
  Flutter constraint is `>=3.10.0` (was `>=1.17.0`, which predates Dart 3 and could never actually
  satisfy the new SDK range). The package was already null-safe; this only drops support for Dart 2
  toolchains.
- **BREAKING**: `AudioTrack`, `VideoTrack`, `LocalVideoTrack`, `RemoteAudioTrack` and
  `RemoteVideoTrack` now declare their `enabled` and `name` constructor parameters as `bool` and
  `String` instead of leaving them untyped (`dynamic`). Passing a non-`bool`/non-`String` value —
  including `null` — is now a compile-time error rather than a debug-only assertion or an opaque
  `TypeError` thrown from inside `Track`. Code that already passed correctly typed values is
  unaffected.
- **Android**: fixed a crash and several `PlatformException`s on Android 12+ (API 31) when
  `BLUETOOTH_CONNECT` has not been granted. `setAudioSettings` could take the whole app down with an
  uncaught `SecurityException` raised from the Bluetooth headset `ServiceListener` callback, and
  `setSpeakerphoneOn` failed outright. The plugin now declares `BLUETOOTH_CONNECT` in its own
  manifest (so consuming apps no longer have to) and every Bluetooth call degrades to "no headset
  connected" when the runtime grant is missing — speaker and receiver routing keep working. Reading
  the Bluetooth adapter also no longer throws on devices without Bluetooth.
- **Android**: `getStats()` called before connecting to a `Room` returned a Kotlin
  `UninitializedPropertyAccessException` wrapped in a `PlatformException`. It now resolves to `null`,
  which is what the Dart layer already expected. The same unguarded access is fixed across
  `disconnect()` and the other room-dependent method channel calls.
- Added `example/integration_test/plugin_smoke_test.dart`, an on-device smoke test for the method
  channel that needs no Twilio account, access token or Firebase project. It covers the two crashes
  above; the existing unit suites mock the platform interface and cannot. CI does not run it — it
  needs a device — see the file header for how to run it and why it must pass both with and without
  `BLUETOOTH_CONNECT` granted.
- **Android**: bumped `com.twilio:video-android` to 7.10.4 (was floating on `7.8.+`) and pinned it,
  along with Kotlin 2.1.21 and Android Gradle Plugin 8.13.2, so builds are reproducible. The Kotlin
  Gradle Plugin previously ran at 1.9.23 while the standard library resolved to 2.1.x.
- **Android**: removed the `ndkVersion` pin. The plugin has no native sources of its own, so the pin
  only asked every consuming app to install and declare that exact NDK.
- **iOS BREAKING**: the minimum supported iOS version is now **13.0** (was 11.0). Apps targeting
  a lower version fail at `pod install` with `The platform of the target 'Runner' (iOS 11.0) is not
  compatible with twilio_programmable_video`. Set `platform :ios, '13.0'` in your `ios/Podfile` and
  raise **Deployment Info → Target** to iOS 13 in Xcode.
- **iOS BREAKING**: the Objective-C header `<twilio_programmable_video/TwilioProgrammableVideoPlugin.h>`
  has been removed. A Swift Package target cannot mix Swift and Objective-C sources, so the
  hand-written shim is gone; `TwilioProgrammableVideoPlugin` remains available to Objective-C via
  `@objc` and to Swift via a `typealias`, so the generated plugin registrants keep working. Only
  hand-written Objective-C that `#import`s the header directly is affected — replace the import with
  `@import twilio_programmable_video;`.
- **iOS**: the plugin now ships a `Package.swift`, so it builds under Swift Package Manager in
  addition to CocoaPods. Swift sources moved to `ios/twilio_programmable_video/Sources/twilio_programmable_video/`;
  both build systems compile that same tree. **Building via SwiftPM requires Flutter 3.44 or
  newer**, because the manifest depends on the `FlutterFramework` package that only Flutter 3.44+
  generates. CocoaPods is unaffected and still works on older Flutter versions, so the
  `environment: flutter` constraint is deliberately left permissive.

## 1.1.1

- Fixed Android build error due to the deprecation of the `kotlin-android-extensions` plugin.

## 1.1.0

- Added `VideoRenderMode mode` as an optional parameter to the `widget` method of the `LocalVideoTrack`, and the `RemoteVideoTrack` classes. For backwards compatibility, it defaults to `VideoRenderMode.BALANCED`. 
- **Web** Fixed some small annotation errors in the Web implementation.

## 1.0.2

- **Web**: Stop video and audio tracks on disconnect. This helps turn off the camera light when not in use.

## 1.0.1

- Updated `permission_handler` dependency from 9.2.0 to 10.2.0

## 1.0.0

- Integrates updates for mobile platforms from version 0.13.0

## 1.0.0-alpha.2

- Added `web` as supported platform.

## 1.0.0-alpha.1

- Initial pre-release of the web implementation.

## 0.13.0

- **Android**: Fixed compilation issue for Flutter > 2.12
- **BREAKING**: Updated permission_handler dependency from 8.3.0 to 9.2.0
- Added a `create` function to the `LocalVideoTrack` class that will trigger initialization at the native layer.
- Maintain a map of `LocalVideoTracks` at the native layer to avoid initializing a second track with the same id should the end developer then provide this when connecting.
- Added a `publishTrack` method to `LocalParticipants` to allow for publishing `LocalVideoTracks` as needed.

## 0.12.1

- **Android**: Fixed the Speaker Not Working when Bluetooth is OFF

## 0.12.0

- **BREAKING**: Updated permission_handler dependency from 7.0.0 to 8.3.0

## 0.11.1

- Added null-safety check for `BluetoothAdapter.getDefaultAdapter()`. It returns null when called on an Android Emulator.
- Updated TwilioVideo iOS SDK from v4.4 to v4.6.
- Replaced `jcenter` with `mavenCentral` in the `build.gradle`.

## 0.11.0+1

- Throw `ActiveCallException` if we cannot activate the `AVAudioSession` (iOS) or get Audio Focus (Android) on `connect`.

## 0.11.0

- Added responsive management of audio settings using `setAudioSettings`.
- Added optional integration with `ocarina` on Android to allow for integrated audio focus management.
- Made improvements to `AVAudioEngineDevice`.
- Deprecated `setSpeakerPhoneOn`.
- Added audio device notifications to dart layer.
- **Android** Normalized camera ID when interacting with `CameraManager` to address a crash introduced by the formatting used by `Camera1Enumerator`.

## 0.10.0+1

- Bumped minor versions of dependencies.

## 0.10.0

- **BREAKING**: Migrated TwilioVideo iOS SDK from v3 to v4.
- **BREAKING**: Migrated TwilioVideo Android SDK from v5 to v6.
- **BREAKING**: Replaced `CameraSource` enum with a class variant to represent a potential source for camera capturing.
- **BREAKING**: The `CameraCapturer.hasTorch()` method has been replaced with a simple getter to it's `CameraSource` `hasTorch` property.
- Retrieving `CameraSource`s can be done using `CameraSource.getSources()` method.

## 0.9.0+2

- Fixed remote video stats (replaced an incorrect map key).

## 0.9.0+1

- Fixed typo in stats (trackSide -> trackSid).

## 0.9.0

- **BREAKING**: Made `exception` property of ConnectFailure, Reconnecting and Disconnected events nullable.

## 0.8.0+1

- Removed incorrect `!` from `local_video_track_model.dart`.

## 0.8.0

- **BREAKING CHANGE**: Added null safety support.

## 0.7.2+1

- **Android** Fixed type mismatch in `hasTorch` implementations that prevent building on gradle `4.x.x`.

## 0.7.2

- Added method for fetching stats.

## 0.7.1

- Added method for checking if device has a builtin earpiece.

## 0.7.0+2

- **iOS**: Fixed an uncommented line fragment that `swiftlint` missed in `AVAudioEngineDevice`.

## 0.7.0+1

- **iOS**: Updated AVAudioEngineDevice memory management.
- **iOS**: Refactored AVAudioEngineDevice initialization process.

## 0.7.0

- **BREAKING CHANGE**: Updated plugin_platform_interface dep.

## 0.6.4+1

- **iOS**: Re-added AudioDevice initialization logic to beginning of setSpeakerPhoneOn.

## 0.6.4

- **iOS**: Adjusted `AudioDevice` initialization logic to allow users of the plugin to provide a custom `AudioDevice`.
- **iOS**: Added `AVAudioEngineDevice`, a custom `AudioDevice`. Details in README.md.
- **Android**: Fixed build issue with gradle version 4.1.0 and higher.

## 0.6.3+1

- Added fallback logic for when `Camera2Capturer` is not supported on Android.

## 0.6.3

- Introduced `networkQualityLevel` property and `onNetworkQualityLevelChanged` event to the `ParticipantWidget`.

## 0.6.2

- Upgraded TwilioVideo iOS SDK to '3.7'.
- Upgraded TwilioVideo Android SDK to '5.12.+'.

## 0.6.1

- Introduced `enablePlayback` and `isPlaybackEnabled` methods to the `RemoteAudioTrack`.

## 0.6.0+1

- Abort connect and throw `MissingCameraException` if no camera is found for specified `CameraSource`.

## 0.6.0+0

- **BREAKING CHANGE**: Switched over to `Camera2Capturer` from `CameraCapturer` on Android.
- **BREAKING CHANGE**: Increased minSdk for Android to `21`.
- Introduced `hasTorch()` and `setTorch(bool enabled)` methods on `CameraCapturer`.
- Introduced `onCameraSwitched`, `onFirstFrameAvailable`, `onCameraError` streams on `CameraCapturer`.

## 0.5.0+4

- Fixed unhandled exception when dominant speaker event contains no remote participant.

## 0.5.0+3

- Remote participants that have left the room will no longer be in the `Room.remoteParticipants` list.

## 0.5.0+2

- Upgraded Twilio SDK for Android from version `5.7.+` to `5.8.+`
- Upgraded Twilio SDK for iOS from version `3.3` to `3.4`

## 0.5.0+1

- `Room` now updates correctly again from `ParticipantConnected` and
  `DominantSpeakerChanged` events.
- `Room.onReconnecting` is now instantiated in the constructor of
  `Room`.

## 0.5.0

- **BREAKING CHANGE**: The 'send' method of the 'LocalDataTrack'
  class can now throw a 'TwilioException'.
- **BREAKING CHANGE**: The 'sendBuffer' method of the 'LocalDataTrack'
  class can now throw a 'TwilioException'.
- **BREAKING CHANGE**: The 'connect' method of the 'TwilioProgrammableVideo'
  class can now throw a 'TwilioException'.
- 'TwilioException' now has more error codes available through static properties.

## 0.4.0

- **BREAKING CHANGE**: The 'SwitchCamera' method of the 'CameraCapturer'
  class can now throw a 'FormatException' on IOS and Android.
- `LocalDataTrack` now uses the DataTrackOptions correctly again.

## 0.3.3+4

- Upgraded Twilio SDK for Android from version `5.6.+` to `5.7.+`
- Upgraded Twilio SDK for iOS from version `3.2` to `3.3`
- Upgraded `permission_handler` to latest version

## 0.3.3+3

- AudioTracks, VideoTracks and DataTracks are optional in
  `ConnectOptions`. Stopped mapping them when equals to `null`.

## 0.3.3+2

- Stopped importing implementation files from the platform interface
- Upgraded the platform interface version

## 0.3.3+1

- More like a house-keeping release after platform release

## 0.3.3

- Implemented the platform interface

## 0.3.2+1

- Fix passing `key` into the local participant widget

## 0.3.2

- Implemented DataTrack on IOS

## 0.3.1+5

- Upgraded Twilio SDK for Android from version `5.1.+` to `5.6.+`

## 0.3.1+4

- Fixes broken release `0.3.1+3`
- Added Flutter SDK constraint to meet new `pubspec.yaml` formatting

## 0.3.1+3

- **Note:** This version is BROKEN, do not use
- Added Automatic Subscription connection option

## 0.3.1+2

- Added Dominant Speaker Changed Events

## 0.3.1+1

- Add `getSpeakerphoneOn` method for reading the speakerphone mode

## 0.3.1

- Added Region enums for both `ConnectOptions.region` and `Room.mediaRegion` instead of string values

## 0.3.0+2

- Android: Fix Bluetooth crash on emulators
- Upgraded `permission_handler` to latest version

## 0.3.0+1

- Align `README.md` with Twilio OSS law
- Added workaround for build failure due to a bug in the Twilio SDK for Android
- Upgraded Twilio SDK for Android from version `5.1.0` to `5.1.+`
- Upgraded Android Studio Gradle plugin from version `3.5.0` to `3.6.0`

## 0.3.0

- Removed occurrence of the `unofficial` word

## 0.2.0

- Implemented iOS functionality, matching the android side.
- Added DataTrack API (Android only)
- Added Local Participant Events (Android only)
- Android: Route audio through Bluetooth headset

## 0.1.2

- Android: Switch speaker mode based on headset plug

## 0.1.1+1

- Fixed Android crashes when joining/disconnecting multiple times

## 0.1.1

- Better error handling on denied permissions
- Android: Improved re-requesting permission and otherwise open App Settings

## 0.1.0+2

- Added animated GIF to show of the example app
- Fixed typo in kotlin error message

## 0.1.0+1

- Applied health suggestions

## 0.1.0

- Initial Android release
