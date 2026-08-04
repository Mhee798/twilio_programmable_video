import 'package:flutter_test/flutter_test.dart';
import 'package:twilio_programmable_video/src/parts.dart';
import 'package:twilio_programmable_video_platform_interface/twilio_programmable_video_platform_interface.dart';

import 'mock_platform_interface.dart';

void main() {
  MockInterface? mockInterface;
  setUpAll(() {
    mockInterface = MockInterface();
    ProgrammableVideoPlatform.instance = mockInterface!;
  });

  // The former `should not construct without enabled` test passed `null`
  // through an untyped (`dynamic`) constructor parameter to trip an
  // `assert(enabled != null)`. `enabled` is now declared `bool`, so passing
  // null is a compile-time error and the runtime guard is unreachable — the
  // case is enforced by the analyzer instead of by this test.
  group('LocalVideoTrack()', () {
    test('should store enabled, name and videoCapturer', () {
      final capturer = CameraCapturer(const CameraSource('BACK_CAMERA', false, false, false));
      final track = LocalVideoTrack(true, capturer, name: 'track-name');

      expect(track.isEnabled, true);
      expect(track.name, 'track-name');
      expect(track.videoCapturer, same(capturer));
    });

    test('should default name to an empty string', () {
      final track = LocalVideoTrack(
        false,
        CameraCapturer(const CameraSource('BACK_CAMERA', false, false, false)),
      );

      expect(track.isEnabled, false);
      expect(track.name, '');
    });
  });

  group('.enable()', () {
    test('should call interface code to enable the track', () async {
      final localVideoTrack = LocalVideoTrack(
        true,
        CameraCapturer(const CameraSource('BACK_CAMERA', false, false, false)),
      );
      await localVideoTrack.enable(false);

      expect(mockInterface!.enableVideoTrackWasCalled, true);
    });
  });

  group('.isEnabled()', () {
    test('should return correct value', () async {
      const constructionBool = true;
      final localVideoTrack = LocalVideoTrack(
        constructionBool,
        CameraCapturer(const CameraSource('BACK_CAMERA', false, false, false)),
      );
      expect(localVideoTrack.isEnabled, constructionBool);
      await localVideoTrack.enable(!constructionBool);
      expect(localVideoTrack.isEnabled, !constructionBool);
    });
  });
}
