import 'package:flutter_test/flutter_test.dart';
import 'package:twilio_programmable_video/src/parts.dart';

void main() {
  // The former `should not construct without name/enabled` tests passed `null`
  // through untyped (`dynamic`) constructor parameters to trip an
  // `assert(x != null)`. Both parameters are now declared `bool`/`String`, so
  // passing null is a compile-time error and the runtime guard is unreachable —
  // the case is enforced by the analyzer instead of by these tests.
  group('RemoteAudioTrack()', () {
    test('should store sid, enabled and name', () {
      final track = RemoteAudioTrack('sid', true, 'name');

      expect(track.sid, 'sid');
      expect(track.isEnabled, true);
      expect(track.name, 'name');
    });

    test('should keep enabled false when constructed disabled', () {
      final track = RemoteAudioTrack('other-sid', false, 'other-name');

      expect(track.sid, 'other-sid');
      expect(track.isEnabled, false);
      expect(track.name, 'other-name');
    });
  });
}
