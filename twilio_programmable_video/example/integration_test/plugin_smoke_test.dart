import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:permission_handler/permission_handler.dart';
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
/// states. Note what that does and does not prove — most of these cases pin
/// `bluetoothPreferred: false`, which makes the routing guard short-circuit before it
/// ever looks at the Bluetooth state, so only the case that deliberately leaves
/// `bluetoothPreferred` at its default exercises the permission-dependent branch.
///
/// NOTE: neither platform can currently host this suite from `example/`.
/// `example/android` still applies Flutter's Gradle plugin the old imperative way,
/// which Flutter 3.44 rejects outright; `example/ios` fails to build because
/// `cloud_functions` 1.1.2 does not compile against the Firebase 9.6.0 that
/// `firebase_core` 1.24.0 pulls in. Until both are migrated, run it from a
/// throwaway `flutter create` host app with the plugin as a path dependency:
///
///     flutter create --platforms=ios itest_host
///     # pubspec.yaml: twilio_programmable_video: {path: <repo>/twilio_programmable_video}
///     #              dev_dependencies: integration_test: {sdk: flutter}
///     #                                permission_handler: ^12.0.1
///     cp <repo>/twilio_programmable_video/example/integration_test/*.dart itest_host/integration_test/
///     cd itest_host && flutter test integration_test/plugin_smoke_test.dart -d <device>
///
/// Two caveats about what iOS coverage actually means here:
/// `CameraSource#getSources` is hardcoded to front+back on iOS rather than
/// enumerating through the Twilio SDK, so there it proves the channel contract
/// rather than that the SDK loaded; and `deviceHasReceiver` keys off
/// `userInterfaceIdiom`, so an iPad target returns false by design.
///
/// The speakerphone cases skip themselves on iOS along with the granted-permission
/// case: `bluetoothPreferred` and the headset-state guard they pin down are Android
/// behaviour (PluginHandler.setSpeakerPhoneOnInternal), and iOS routes audio through
/// AVAudioSession instead.
///
/// `requestPermissionForCameraAndMicrophone()` is deliberately excluded — it raises
/// a system dialog and would hang an unattended run.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    await TwilioProgrammableVideo.debug(dart: true, native: true);
  });

  /// True when the plugin can read the Bluetooth headset state.
  ///
  /// This decides whether the speakerphone assertions below are deterministic. With
  /// the grant in place the plugin binds the headset profile proxy, and both
  /// `AudioNotificationListener.onServiceConnected` and its BroadcastReceiver call
  /// `applyAudioSettings()` asynchronously — which rewrites `isSpeakerphoneOn` — while
  /// `applyBluetoothSettings()` posts another write 1 s later. Reading back
  /// `getSpeakerphoneOn()` right after a set is therefore a race. Without the grant the
  /// proxy is never bound, none of that fires, and the read is stable.
  ///
  /// `.status` queries without prompting, so it is safe in an unattended run.
  Future<bool> canReadHeadsetState() async {
    if (!Platform.isAndroid) return true;
    return (await Permission.bluetoothConnect.status).isGranted;
  }

  group('native channel round-trips', () {
    testWidgets('deviceHasReceiver returns a bool without throwing', (_) async {
      final result = await TwilioProgrammableVideo.deviceHasReceiver();
      expect(result, isA<bool>());
    });

    testWidgets('setSpeakerphoneOn state survives the round trip to native', (_) async {
      if (await canReadHeadsetState()) {
        markTestSkipped('asynchronous re-routing makes isSpeakerphoneOn racy once the '
            'headset profile proxy is bound; run without BLUETOOTH_CONNECT to assert it');
        return;
      }

      // bluetoothPreferred off so this case does not also depend on the headset state:
      // PluginHandler.setSpeakerPhoneOnInternal skips applying the speakerphone setting
      // while a headset is connected and Bluetooth is preferred, and getSpeakerphoneOn
      // reports the real AudioManager state rather than the requested one.
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

      await TwilioProgrammableVideo.setSpeakerphoneOn(false);
      expect(await TwilioProgrammableVideo.getSpeakerphoneOn(), isFalse);
    });

    // The test above pins bluetoothPreferred to false, which short-circuits
    // PluginHandler.setSpeakerPhoneOnInternal through `!audioSettings.bluetoothPreferred`
    // before the Bluetooth headset state is ever consulted — so on its own it cannot
    // tell a working routing guard from a broken one. This case leaves
    // bluetoothPreferred at its default of true so the guard has to actually resolve
    // the headset state.
    //
    // Without BLUETOOTH_CONNECT that state is unreadable, and the plugin treats
    // unreadable as "no headset" precisely so an explicit request still wins. An
    // earlier revision returned "unknown" here and declined to touch the route, which
    // turned every speakerphone request into a silent no-op that still reported
    // success. If that regresses, this fails.
    //
    // Only assertable without the grant, and that is exactly the regime the fix is
    // about: with the grant the headset state is readable, so declining to switch can
    // be the correct answer, and the asynchronous re-routing described above makes the
    // read racy anyway.
    testWidgets('speakerphone still applies with bluetoothPreferred left at its default', (_) async {
      if (await canReadHeadsetState()) {
        markTestSkipped('this pins the unreadable-headset-state path; '
            'run without BLUETOOTH_CONNECT to exercise it');
        return;
      }

      await TwilioProgrammableVideo.setAudioSettings(
        speakerphoneEnabled: true,
        bluetoothPreferred: true,
      );
      addTearDown(TwilioProgrammableVideo.disableAudioSettings);

      await TwilioProgrammableVideo.setSpeakerphoneOn(true);
      expect(
        await TwilioProgrammableVideo.getSpeakerphoneOn(),
        isTrue,
        reason: 'speakerphone must be honoured even when the headset state is unknown',
      );
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
