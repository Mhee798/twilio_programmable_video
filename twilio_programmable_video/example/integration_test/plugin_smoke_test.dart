import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:twilio_programmable_video/twilio_programmable_video.dart';

/// Runtime smoke test for twilio_programmable_video on a real device or emulator.
///
/// The plugin's unit suite mocks `ProgrammableVideoPlatform`, so it never executes
/// a single line of native code. This suite does the opposite: it only calls
/// methods that cross the MethodChannel into the platform implementation, and only
/// ones that need neither a `Room` nor Twilio credentials — so it runs unattended
/// with no account, no access token and no Firebase project. It does not use the
/// example app's `main()`, so the Firebase setup that app needs is irrelevant here.
///
/// Two of these are regression tests for crashes that shipped in 1.1.1 and that
/// nothing else in the repo can catch: `getStats` before connecting, and the
/// Android 12+ Bluetooth permission handling (see CHANGELOG "Unreleased").
///
/// Running it:
///
///     flutter test integration_test/plugin_smoke_test.dart -d <device>
///
/// On Android 12+ (API 31) the host app needs BLUETOOTH_CONNECT granted to
/// exercise the *granted* path. `flutter test` reinstalls the app on every run and
/// runtime grants do not survive a fresh install, so grant it against an install
/// that already exists:
///
///     flutter build apk --debug
///     adb install -g -r build/app/outputs/flutter-apk/app-debug.apk
///     flutter test integration_test/plugin_smoke_test.dart -d <device>
///
/// Running without the grant is worth doing too: every test here must pass in both
/// states. On API 23+ that is now a weaker statement than it used to be — audio
/// routing goes through AudioSwitch, which discovers devices through AudioManager and
/// never reads the Bluetooth profile state — but the grant still has to not break
/// anything, and the API 21-22 path does bind the headset profile proxy.
///
/// Run it with no headset attached. The routing cases below assert that a
/// speakerphone request reaches the speaker, and a connected Bluetooth or wired
/// headset legitimately outranks the speaker, which would fail them for the right
/// reason. There is no API for the suite to detect that itself and skip.
///
/// NOTE: neither platform can currently host this suite from `example/`.
/// `example/android` still applies Flutter's Gradle plugin the old imperative way,
/// which Flutter 3.44 rejects outright; `example/ios` fails to build because
/// `cloud_functions` 1.1.2 does not compile against the Firebase 9.6.0 that
/// `firebase_core` 1.24.0 pulls in. `example/ios/Podfile.lock` is stale on top of
/// that — it still names plugin 0.11.1 and `TwilioVideo (~> 4.6)`/4.6.3 — and is
/// left that way deliberately, because regenerating it drags in the Firebase bump
/// that causes the build failure above. A `pod install` there will complain that a
/// development pod's constraints changed and ask for `pod update TwilioVideo`; that
/// is expected, not a symptom of a broken podspec. Until both are migrated, run
/// this from a throwaway `flutter create` host app with the plugin as a path
/// dependency:
///
///     flutter create --platforms=ios itest_host
///     # pubspec.yaml: twilio_programmable_video: {path: <repo>/twilio_programmable_video}
///     #              dev_dependencies: integration_test: {sdk: flutter}
///     cp <repo>/twilio_programmable_video/example/integration_test/*.dart itest_host/integration_test/
///     cd itest_host && flutter test integration_test/plugin_smoke_test.dart -d <device>
///
/// Two caveats about what iOS coverage actually means here:
/// `CameraSource#getSources` is hardcoded to front+back on iOS rather than
/// enumerating through the Twilio SDK, so there it proves the channel contract
/// rather than that the SDK loaded; and `deviceHasReceiver` keys off
/// `userInterfaceIdiom`, so an iPad target returns false by design.
///
/// The speakerphone cases skip themselves on anything but Android: they pin down
/// `bluetoothPreferred` and the device-priority order it feeds (AudioRouter.kt), and
/// iOS routes audio through AVAudioSession with no counterpart. So on iOS this suite
/// reduces to the channel round-trips: those skips are expected there, not a
/// regression.
///
/// `requestPermissionForCameraAndMicrophone()` is deliberately excluded — it raises
/// a system dialog and would hang an unattended run.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    // `audio: true` as well as `native: true`: the routing decisions this suite
    // asserts on are logged through the audio channel (AudioRouter, AudioSwitch), and
    // without it a failure here says only which method channel call was made, not
    // which device the router picked or why.
    await TwilioProgrammableVideo.debug(dart: true, native: true, audio: true);
  });

  /// Skips the calling test on anything but Android, with a reason that says why.
  ///
  /// The speakerphone cases below pin down the device-priority order AudioRouter
  /// builds from `speakerphoneEnabled`/`bluetoothPreferred`; iOS has no counterpart
  /// because it routes through `AVAudioSession`.
  bool skipUnlessAndroid() {
    if (Platform.isAndroid) return false;
    markTestSkipped('speakerphone routing is Android behaviour (AudioRouter.'
        'preferredDeviceListFor); iOS routes through AVAudioSession');
    return true;
  }

  group('native channel round-trips', () {
    testWidgets('deviceHasReceiver returns a bool without throwing', (_) async {
      final result = await TwilioProgrammableVideo.deviceHasReceiver();
      expect(result, isA<bool>());
    });

    testWidgets('setSpeakerphoneOn state survives the round trip to native', (_) async {
      if (skipUnlessAndroid()) return;

      await TwilioProgrammableVideo.setAudioSettings(
        speakerphoneEnabled: true,
        bluetoothPreferred: false,
      );
      addTearDown(TwilioProgrammableVideo.disableAudioSettings);

      // setSpeakerphoneOn/getSpeakerphoneOn are deprecated in favour of
      // set/getAudioSettings, but they are still part of the public API and still
      // route through the native layer, so they stay covered until they are removed.
      // ignore_for_file: deprecated_member_use
      await TwilioProgrammableVideo.setSpeakerphoneOn(true);
      expect(await TwilioProgrammableVideo.getSpeakerphoneOn(), isTrue);

      // Speaker off means the earpiece, so this half only says anything on a device
      // that has one. A tablet without a receiver stays on the speaker, correctly.
      if (await TwilioProgrammableVideo.deviceHasReceiver()) {
        await TwilioProgrammableVideo.setSpeakerphoneOn(false);
        expect(await TwilioProgrammableVideo.getSpeakerphoneOn(), isFalse);
      }
    });

    // The case above pins bluetoothPreferred to false, so it cannot tell whether
    // Bluetooth is being weighed at all. This one leaves it at its default of true,
    // which is the combination every real caller passes and the one the old
    // implementation got wrong: it read `speakerphoneEnabled: true` as "speaker, full
    // stop" and never reached the Bluetooth branch, so a paired headset stayed silent.
    //
    // With no headset attached — a documented precondition of this suite — the two
    // combinations must agree, because the only difference between them is a
    // preference for a device that is not there. A regression that reintroduces
    // "speaker wins outright" passes this; a regression that inverts it and forces
    // Bluetooth ahead of an explicit speaker request fails it, and so does one that
    // makes the whole speakerphone request a silent no-op.
    testWidgets('speakerphone still applies with bluetoothPreferred left at its default', (_) async {
      if (skipUnlessAndroid()) return;

      await TwilioProgrammableVideo.setAudioSettings(
        speakerphoneEnabled: true,
        bluetoothPreferred: true,
      );
      addTearDown(TwilioProgrammableVideo.disableAudioSettings);

      expect(
        await TwilioProgrammableVideo.getSpeakerphoneOn(),
        isTrue,
        reason: 'preferring Bluetooth must not override an explicit speakerphone '
            'request when no headset is connected',
      );

      await TwilioProgrammableVideo.setSpeakerphoneOn(true);
      expect(await TwilioProgrammableVideo.getSpeakerphoneOn(), isTrue);
    });

    // The route is engaged lazily — only while a Room is connected or an audio player
    // is running — so every one of these calls lands on a router that has been started
    // but never activated, which is the state the plugin spends most of its life in.
    //
    // Cycling it also covers the teardown path that used to leak: `setAudioSettings`
    // registered a BroadcastReceiver and bound a Bluetooth profile proxy on every call
    // while `disableAudioSettings` released one of each, so a headset event fired once
    // per accumulated registration. `disableAudioSettings` before any
    // `setAudioSettings`, and twice in a row, used to throw.
    testWidgets('audio settings can be cycled and torn down repeatedly', (_) async {
      await TwilioProgrammableVideo.disableAudioSettings();

      for (var i = 0; i < 3; i++) {
        await TwilioProgrammableVideo.setAudioSettings(
          speakerphoneEnabled: true,
          bluetoothPreferred: true,
        );
        await TwilioProgrammableVideo.setAudioSettings(
          speakerphoneEnabled: false,
          bluetoothPreferred: false,
        );
        await TwilioProgrammableVideo.disableAudioSettings();
      }

      await TwilioProgrammableVideo.disableAudioSettings();

      // Reset() on the native side, not the last values written above.
      final settings = await TwilioProgrammableVideo.getAudioSettings();
      expect(settings.speakerphoneEnabled, isTrue, reason: 'disableAudioSettings resets speakerphoneEnabled');
      expect(settings.bluetoothPreferred, isTrue, reason: 'disableAudioSettings resets bluetoothPreferred');
    });

    testWidgets('getAudioSettings decodes the native map onto the right fields', (_) async {
      // Distinct values so a swapped map key in the native layer fails here. This
      // is the same failure mode as the getStats key mapping, which cannot be
      // covered without a Room.
      await TwilioProgrammableVideo.setAudioSettings(
        speakerphoneEnabled: true,
        bluetoothPreferred: false,
      );
      var settings = await TwilioProgrammableVideo.getAudioSettings();
      expect(settings.speakerphoneEnabled, isTrue, reason: 'speakerphoneEnabled');
      expect(settings.bluetoothPreferred, isFalse, reason: 'bluetoothPreferred');

      await TwilioProgrammableVideo.setAudioSettings(
        speakerphoneEnabled: false,
        bluetoothPreferred: true,
      );
      settings = await TwilioProgrammableVideo.getAudioSettings();
      expect(settings.speakerphoneEnabled, isFalse, reason: 'speakerphoneEnabled inverted');
      expect(settings.bluetoothPreferred, isTrue, reason: 'bluetoothPreferred inverted');

      await TwilioProgrammableVideo.disableAudioSettings();
    });

    testWidgets('CameraSource.getSources reaches the Twilio SDK and returns cameras', (_) async {
      final sources = await CameraSource.getSources();

      // On Android this goes through the Twilio SDK's camera enumeration, so an
      // empty list would mean the AAR did not load or the channel contract changed.
      // iOS hardcodes front+back instead of asking the SDK, so there it only proves
      // the channel contract. Every emulator and simulator exposes at least one.
      expect(sources, isNotEmpty);
      for (final source in sources) {
        expect(source.cameraId, isNotEmpty);
      }
    });

    // Regression test for both platforms, which used to fail differently.
    //
    // Android read the `lateinit roomListener` unconditionally, so calling this
    // before connecting died with a Kotlin UninitializedPropertyAccessException
    // surfaced as an opaque PlatformException. iOS reached the SDK through an
    // optional chain, so the trailing closure was never entered and the
    // FlutterResult was never fulfilled — this await simply never returned. Method
    // channels have no timeout, so that one was unrecoverable rather than merely
    // wrong.
    //
    // Both now resolve to null, which is the branch programmable_video.dart already
    // had waiting. Without the iOS half of the fix this test runs past 600 s instead
    // of failing, so a hang here means that regressed.
    testWidgets('getStats outside a Room resolves to null instead of throwing', (_) async {
      final reports = await TwilioProgrammableVideo.getStats();
      expect(reports, isNull);
    });
  });
}
