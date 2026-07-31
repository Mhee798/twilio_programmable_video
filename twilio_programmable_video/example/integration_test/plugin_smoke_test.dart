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
/// Running without the grant is also worth doing — every test here must pass in
/// both states, which is exactly what the Bluetooth fix is about.
///
/// NOTE: `example/android` still applies Flutter's Gradle plugin the old
/// imperative way, which Flutter 3.44 rejects outright, so this cannot be driven
/// against an Android target from this directory until that migration lands. Until
/// then, run it from a throwaway `flutter create` host app with the plugin added as
/// a path dependency. iOS is unaffected.
///
/// `requestPermissionForCameraAndMicrophone()` is deliberately excluded — it raises
/// a system dialog and would hang an unattended run.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    await TwilioProgrammableVideo.debug(dart: true, native: true);
  });

  group('native channel round-trips', () {
    testWidgets('deviceHasReceiver returns a bool without throwing', (_) async {
      final result = await TwilioProgrammableVideo.deviceHasReceiver();
      expect(result, isA<bool>());
    });

    testWidgets('setSpeakerphoneOn state survives the round trip to native', (_) async {
      // bluetoothPreferred must be off first, otherwise this test depends on the
      // device's Bluetooth state: PluginHandler.setSpeakerPhoneOnInternal
      // deliberately skips applying the speakerphone setting while a headset is
      // connected and Bluetooth is preferred, and getSpeakerphoneOn reports the
      // real AudioManager state rather than the requested one. An emulator that
      // reports a connected headset profile would otherwise fail here.
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

      // This call goes through the Twilio SDK's camera enumeration, so an empty
      // list would mean the AAR did not load or the channel contract changed.
      // Emulators expose at least a back camera.
      expect(sources, isNotEmpty);
      for (final source in sources) {
        expect(source.cameraId, isNotEmpty);
      }
    });

    // Regression test: PluginHandler.getStats used to read the `lateinit
    // roomListener` unconditionally, so calling it before connecting to a Room
    // died with a Kotlin UninitializedPropertyAccessException that surfaced in
    // Dart as an opaque PlatformException. It now resolves the Room through
    // roomListenerOrNull and fulfils the result with null, which is the branch
    // programmable_video.dart already had waiting.
    testWidgets('getStats outside a Room resolves to null instead of throwing', (_) async {
      final reports = await TwilioProgrammableVideo.getStats();
      expect(reports, isNull);
    });
  });
}
