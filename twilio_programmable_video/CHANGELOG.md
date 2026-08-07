## Unreleased

- **Android**: audio was silent on both the speaker *and* the headset when a Bluetooth headset was
  connected during a call on Android 12+ (API 31). Two faults stacked. First, the code that started
  Bluetooth SCO only ran when the speaker was off (`applyAudioSettings` called
  `applyBluetoothSettings` under `if (!audioSettings.speakerEnabled)`), so an app passing
  `speakerphoneEnabled: true, bluetoothPreferred: true` — the combination the README documents as
  "Bluetooth if available, otherwise the speaker" — never reached the Bluetooth path at all;
  `bluetoothPreferred` only ever meant "do not force the speaker while a headset is connected".
  Second, even once reached, it did not work: `AudioManager.startBluetoothSco` is deprecated from
  API 31 and no longer moves playout. Measured on Android 16 the framework reported
  `SCO_AUDIO_STATE_CONNECTED` for the app's own uid while nothing came out of either device.

  Routing now goes through [AudioSwitch](https://github.com/twilio/audioswitch) as one ordered
  device preference instead of two independent flags — on API 31+ through the
  `CommDeviceAudioSwitch` variant, which routes with `AudioManager.setCommunicationDevice`, the only
  call that still moves the route there. `speakerphoneEnabled: true, bluetoothPreferred: true` now
  means what it says: the speaker, except when a headset is connected. **The Dart API is unchanged**
  — `setAudioSettings`, `getAudioSettings`, `disableAudioSettings` and `setSpeakerphoneOn` keep
  their signatures and an app needs no code change.

  Five behaviour changes come with it, all on Android:
    - A headset that was *already* connected when the call started is now used. It never was
      before: `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED` is not a sticky broadcast, so no
      event arrived for a connection that predated the plugin registering its receiver.
    - A **wired** headset now outranks the earpiece *and* a Bluetooth headset the app has not asked
      for, so plugging one in routes to it. `speakerphoneEnabled: true` still outranks it, as the
      `isSpeakerphoneOn = true` write it replaces did — that flag is the only lever the Dart API
      offers, and a speaker toggle has to work with earbuds plugged in.
    - The route is engaged only while the plugin is driving audio — from `Room.onConnected` until
      disconnect, and while an audio player registered with the plugin is playing. `setAudioSettings`
      and `setSpeakerphoneOn` outside those windows record the preference for the next one instead of
      writing `AudioManager.isSpeakerphoneOn` on the spot, which is what the old code did. This is
      deliberate: an activated route holds the Bluetooth link the way audio focus holds playback, so
      keeping it between calls would stop another app's music from resuming, and it matches what iOS
      has always done. The audio debug log says so on each such call.
    - `getSpeakerphoneOn` reports the selected device rather than `AudioManager.isSpeakerphoneOn`,
      which on API 31+ is no longer what decides the route. It answers "the speaker is where the
      current settings send audio", so it keeps its value across `disconnect` rather than tracking
      the released route. `disableAudioSettings` ends device discovery, and from there it falls
      back to the `AudioManager` flag until the next `setAudioSettings`.
    - `disableAudioSettings` now releases the audio route as well as ending route observation, so
      another app's audio can resume — previously it only unregistered the receiver and the
      Bluetooth profile proxy and left the route alone. Calling it while a `Room` is still connected
      therefore ends Bluetooth or speaker routing for that call; the plugin's own example calls it
      on the line above `Room.disconnect()`, where that makes no difference.

  This adds a dependency on `com.github.davidliu:audioswitch` from JitPack, pinned to the commit
  `flutter_webrtc` ships. The plugin adds the JitPack repository to the build itself, so a consuming
  app needs no Gradle change. The fork rather than `com.twilio:audioswitch` because only the fork
  has `CommDeviceAudioSwitch`; the last upstream release (1.2.5) still toggles `isSpeakerphoneOn`
  and `startBluetoothSco`.
- **Android API 23-30**: a Bluetooth headset that did not pick up the SCO link on the first ask is
  now asked again, every 500 ms for up to five seconds, instead of staying silent for the whole
  call. Those releases route Bluetooth with `AudioManager.startBluetoothSco()`, which does not
  always take on the first attempt — the code this replaces deferred that call by a second for the
  same reason — and the AudioSwitch variant used there asks exactly once: its `BluetoothScoJob`
  retry loop is reachable only from `LegacyAudioSwitch` (API < 23). The re-ask is
  `AudioSwitch.activate()`, which re-runs the routing call on an already-activated switch, and it
  stops as soon as `isBluetoothScoOn` reports the link, when the selection is no longer Bluetooth,
  or when the route is released. API 31+ is unaffected: it routes with `setCommunicationDevice`,
  which reports its own failures, and never starts SCO.
- **Android**: the audio route and its device discovery are now released when the Flutter engine
  detaches. Nothing did before: `disableAudioSettings` was the only caller of the teardown, and an
  app that never calls it — the README does not require it — left the route claimed and the audio
  device scanners registered for the rest of the process, so a headset stayed in call mode and
  another app's audio could not resume. Skipped for an engine whose router was never started, since
  the teardown withdraws the device list and that list is shared with any other engine in the
  process.
- **Android**: a second `FlutterEngine` in the same process no longer takes over the plugin's
  process-wide statics. `onAttachedToEngine` assigned them unconditionally, so a `FirebaseMessaging`
  background handler, a CallKit isolate or an add-to-app host attaching the plugin redirected camera
  events (`cameraError`, `firstFrameAvailable`, `cameraSwitched`) and the audio player listener to
  that engine's handler — whose event sinks nothing had listened to, so the events were dropped —
  and `onDetachedFromEngine` never handed them back, so they stayed dropped for the rest of the
  process. Each engine keeps its own handler for its own channels; the shared pointer now follows
  the engine that last made a method channel call — the one actually using the plugin — since attach
  order settles nothing: newest-wins is the bug above, and oldest-wins strands the pointer on the
  background engine when an incoming call wakes a terminated app. `onDetachedFromEngine` also
  releases the camera and audio-notification channels, which it had been leaving registered.
- **Android**, two limitations that come with routing through AudioSwitch and are recorded here rather
  than worked around:
    - With **two Bluetooth audio devices connected at once**, only the first is reported and used.
      AudioSwitch orders its available-device set with a comparator that treats two devices of the
      same class as equal, so the set holds at most one Bluetooth entry; disconnecting that one reads
      as "no Bluetooth device" and the route falls back to the speaker even though the other is still
      usable. The `BluetoothProfile.getProfileConnectionState` read this replaces answered for *any*
      connected headset and so did not have this blind spot — but it needed `BLUETOOTH_CONNECT` and
      lagged behind the broadcast that prompted it, which is worse on the common single-headset path.
    - A routing call that the audio stack **rejects from inside one of AudioSwitch's own callbacks**
      is not caught. The plugin guards every call it makes into AudioSwitch, but AudioSwitch also
      reaches `AudioManager` from its scanner callbacks: a headset connecting mid-call re-enters
      the routing path with no frame of the plugin's on the stack, whether that ends in
      `startBluetoothSco()` (API 23-30) or `setCommunicationDevice()` (API 31+). It cannot be closed
      from the plugin — every AudioSwitch constructor that accepts a Handler or a Scanner is
      `internal`, so neither a subclass nor an injected collaborator is available. Calls the plugin
      makes itself stay guarded as before.
    - In the same family: if starting device discovery is rejected part-way, the scanner
      registrations it had already made with `AudioManager` cannot be undone. `AbstractAudioSwitch`
      only reaches its STARTED state once the scanner has started, so `stop()` on such an instance
      is a no-op by its own contract, and nothing else can reach the scanner.
- **Android**: `BLUETOOTH_CONNECT` is no longer needed for Bluetooth audio routing. Devices are
  discovered through `AudioManager`, which reports a connected headset without any Bluetooth
  permission, so routing now works in the ungranted case that previously fell back to "no headset
  connected". The plugin still declares `BLUETOOTH` and `BLUETOOTH_CONNECT` — removing a permission
  an app may rely on this plugin to declare would break that app's manifest, and the API 21-22 path
  still binds the headset profile proxy — but an app that only wanted them for routing can now drop
  its own request.
- **Android**: wired headset plug/unplug events never reached Dart. The route-change receiver read
  the intent extra `"portName"` from `ACTION_HEADSET_PLUG`, which has no such extra — the real ones
  are `state`, `name` and `microphone` — and discarded the whole event when it came back null. Both
  the routing and the `newDeviceAvailable`/`oldDeviceUnavailable` events now come from AudioSwitch's
  device list, so the extra is not read at all.
- **Android**: `newDeviceAvailable`/`oldDeviceUnavailable` now always carry a `deviceName`. It used
  to be read from a `BluetoothDevice` extra, which needs `BLUETOOTH_CONNECT` on API 31+, and the
  Dart layer turns a null name into a `SkippableAudioEvent` — so without the grant the events were
  silently dropped. The name now comes from `AudioDeviceInfo.productName`, which no permission
  gates. The event names, payload keys and their meanings are unchanged.
- `NewDeviceAvailableEvent`, `OldDeviceUnavailableEvent` and `SkippableAudioEvent` are now exported
  from `package:twilio_programmable_video`. `TwilioProgrammableVideo.onAudioNotification` emits
  them, so the stream could not be used without adding
  `twilio_programmable_video_platform_interface` as a direct dependency just to name the types.
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
  uncaught `SecurityException` raised from the Bluetooth headset `ServiceListener` callback (and a
  second one from the route-change `BroadcastReceiver`), and `setSpeakerphoneOn` failed outright.
  All six Bluetooth call sites now report "no headset connected" when the state cannot be read, so
  an explicit speakerphone or audio-settings request is still honoured; only Bluetooth *routing*
  needs the grant. Reading the Bluetooth adapter also no longer throws on a device without
  Bluetooth, and `startBluetoothSco`/`stopBluetoothSco` no longer crash the process when an OEM
  audio stack rejects them.
- **Android**: the plugin now declares `BLUETOOTH` and `BLUETOOTH_CONNECT` in its own manifest, so a
  consuming app does not have to. Both are declared without `android:maxSdkVersion` on purpose —
  setting it would make the manifest merger fail the build of any app that declares the same
  permission with a different value. An app that wants Bluetooth routing still has to *request*
  `BLUETOOTH_CONNECT` at runtime; the plugin never prompts. An app that wants neither permission in
  its own manifest can strip them with `tools:node="remove"`.
- **Android**: `getStats()` called before connecting to a `Room` returned a Kotlin
  `UninitializedPropertyAccessException` wrapped in a `PlatformException`. It now resolves to `null`,
  which is what the Dart layer already expected. The same unguarded access is fixed across
  `disconnect()` and the other room-dependent method channel calls. `getStats()` *after* a `Room`
  ended could hang the same way and is fixed too — see the `getStats()` entry below, which covers
  both platforms. The iOS handlers other than `getStats` are unchanged and still operate on a stale
  `Room` after a disconnect where Android answers `NOT_FOUND`.
- **Android**: publishing a `LocalVideoTrack` without being connected to a `Room` reported success
  while publishing nothing, and dropped the track from the plugin's registry so it could never be
  released — the camera stayed held. It now answers `NOT_FOUND`, matching `unpublish`.
- **Android**: repeated `setAudioSettings` calls registered the route-change `BroadcastReceiver`
  again each time, so a single headset event was delivered once per call and re-ran the audio
  routing that many times. The same calls also bound a new Bluetooth headset profile proxy every
  time while `disableAudioSettings` released only the most recent one, leaking a service connection
  per call and re-running the audio routing once per leaked connection. Both the registration and
  the bind are now tracked, and `disableAudioSettings` no longer throws when called twice or before
  any `setAudioSettings`.
- Note for contributors: the published SDK floor is Dart 3.0, but `flutter_lints ^6` requires Dart
  3.8, so building *this repository* needs 3.8 or newer. Consumers are unaffected — pub does not
  resolve a package's dev dependencies.
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
- **Android and iOS**: `getStats()` outside a *connected* `Room` could leave its `Future` pending
  forever, and a method channel has no timeout to recover from that. On iOS the handler reached the
  SDK through an optional chain, so with no `Room` it returned without ever fulfilling the
  `FlutterResult`; a `disconnected` `Room` did the same, because `TVIRoom getStatsWithBlock:` does
  not deliver reports in that state. Android was exposed to the same thing after a disconnect it did
  not initiate: `RoomListener.onDisconnected` does not clear the `Room` (only an app-initiated
  `disconnect()` does) and `Room.getStats` drops the listener without invoking it while
  `DISCONNECTED`. Both platforms now resolve to `null` — what `programmable_video.dart` already
  expected — unless the `Room` is connected. `connecting` and `reconnecting` resolve to `null` as
  well: both can reach `disconnected` while a request is in flight, a reconnecting `Room` may have
  lost signalling entirely (`roomIsReconnecting` fires for that too, including on backgrounding),
  and Android's queued listener is popped one report at a time, so a request the core declines also
  shifts every later poll onto the previous poll's result. Stats taken mid-outage are worth little,
  so both platforms prefer the recoverable answer. `example/integration_test/plugin_smoke_test.dart`
  covers the never-connected case on both platforms.
- **iOS BREAKING**: bumped `TwilioVideo` to `>= 5.11.3, < 6.0` (resolving to 5.11.3, was
  `~> 4.6`/4.6.3) in both the podspec and `Package.swift`. The only API the Video SDK removed
  between the two versions is `IsacCodec`, dropped in 5.8.0 when the SDK moved to WebRTC 112 — so
  passing `IsacCodec()` in `preferredAudioCodecs` now selects opus instead of iSAC. Android has
  behaved that way since the 7.7.0 bump, so the two platforms agree again. Also note 5.x drops the
  `armv7` device and `i386` simulator slices — the xcframework ships `ios-arm64` and
  `ios-arm64_x86_64-simulator` — which matters for apps still building 32-bit device slices.
  Apps with an existing `ios/Podfile.lock` cannot pick this up with `pod install` — CocoaPods
  refuses to change a development pod's constraints from a lockfile and tells you to run
  **`pod update TwilioVideo`** instead. SwiftPM consumers need no action.
- `IsacCodec` — re-exported from `twilio_programmable_video_platform_interface` — is now
  `@Deprecated`, so code that still asks for iSAC gets a compile-time hint instead of silently
  getting opus. It keeps working; it has simply had no effect since both SDKs dropped the codec.
- **Android and iOS**: `preferredAudioCodecs` and `preferredVideoCodecs` no longer hand the SDK a
  preference list containing the same codec twice. Passing both `IsacCodec()` and `OpusCodec()` now
  produces one opus entry rather than two, and an unrecognised codec name — which both platforms
  fall back to opus and VP8 for — no longer duplicates an entry that was also requested by name.
  Neither SDK rejects duplicates, so this only ever produced a meaningless list, not an error.
  Note that the *order* of both lists still does not reach either native platform: the platform
  interface serialises them as maps, and the standard message codec decodes maps into an unordered
  `NSDictionary`/`HashMap`. Only the web implementation honours the order today.
- **iOS**: replaced the deprecated `AudioDeviceFormatChanged` with `AudioDeviceReinitialize` in
  `AVAudioEngineDevice`. TwilioVideo deprecated the former in 5.4.0 and will remove it in 6.0; the
  two are equivalent for this call site, so custom audio device behaviour is unchanged.
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
