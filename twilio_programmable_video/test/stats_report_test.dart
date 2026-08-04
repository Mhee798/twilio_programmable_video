import 'package:flutter_test/flutter_test.dart';
import 'package:twilio_programmable_video/twilio_programmable_video.dart';
import 'package:twilio_programmable_video_platform_interface/twilio_programmable_video_platform_interface.dart';

import 'mock_platform_interface.dart';

/// The stats classes forward 5-8 positional arguments up a three-level
/// hierarchy (`*TrackStats` -> `Local/RemoteTrackStats` -> `BaseTrackStats`),
/// and within each constructor several parameters share a type: `trackSid`,
/// `codec` and `ssrc` are all `String`, and every remaining numeric field is
/// `int`. A transposed argument is therefore a legal subtype match that the
/// analyzer cannot catch, so these tests give every field a value that is
/// unique across the whole file — any swap changes an expected value.
void main() {
  group('stats constructors bind each positional argument to the right field', () {
    test('LocalAudioTrackStats', () {
      final stats = LocalAudioTrackStats('las-sid', 11, 'opus', 'las-ssrc', 1.5, 12, 13, 14, 15, 16);

      expect(stats.trackSid, 'las-sid');
      expect(stats.packetsLost, 11);
      expect(stats.codec, 'opus');
      expect(stats.ssrc, 'las-ssrc');
      expect(stats.timestamp, 1.5);
      expect(stats.bytesSent, 12);
      expect(stats.packetsSent, 13);
      expect(stats.roundTripTime, 14);
      expect(stats.audioLevel, 15);
      expect(stats.jitter, 16);
    });

    test('LocalVideoTrackStats', () {
      final stats = LocalVideoTrackStats(
        'lvs-sid',
        21,
        'VP8',
        'lvs-ssrc',
        2.5,
        22,
        23,
        24,
        VideoDimensions(25, 26),
        VideoDimensions(27, 28),
        29,
        30,
      );

      expect(stats.trackSid, 'lvs-sid');
      expect(stats.packetsLost, 21);
      expect(stats.codec, 'VP8');
      expect(stats.ssrc, 'lvs-ssrc');
      expect(stats.timestamp, 2.5);
      expect(stats.bytesSent, 22);
      expect(stats.packetsSent, 23);
      expect(stats.roundTripTime, 24);
      expect(stats.captureDimensions.height, 25);
      expect(stats.captureDimensions.width, 26);
      expect(stats.dimensions.height, 27);
      expect(stats.dimensions.width, 28);
      expect(stats.capturedFrameRate, 29);
      expect(stats.frameRate, 30);
    });

    test('RemoteAudioTrackStats', () {
      final stats = RemoteAudioTrackStats('ras-sid', 31, 'PCMU', 'ras-ssrc', 3.5, 32, 33, 34, 35);

      expect(stats.trackSid, 'ras-sid');
      expect(stats.packetsLost, 31);
      expect(stats.codec, 'PCMU');
      expect(stats.ssrc, 'ras-ssrc');
      expect(stats.timestamp, 3.5);
      expect(stats.bytesReceived, 32);
      expect(stats.packetsReceived, 33);
      expect(stats.audioLevel, 34);
      expect(stats.jitter, 35);
    });

    test('RemoteVideoTrackStats', () {
      final stats = RemoteVideoTrackStats('rvs-sid', 41, 'H264', 'rvs-ssrc', 4.5, 42, 43, VideoDimensions(44, 45), 46);

      expect(stats.trackSid, 'rvs-sid');
      expect(stats.packetsLost, 41);
      expect(stats.codec, 'H264');
      expect(stats.ssrc, 'rvs-ssrc');
      expect(stats.timestamp, 4.5);
      expect(stats.bytesReceived, 42);
      expect(stats.packetsReceived, 43);
      expect(stats.dimensions.height, 44);
      expect(stats.dimensions.width, 45);
      expect(stats.frameRate, 46);
    });

    test('VideoDimensions takes height before width', () {
      final dimensions = VideoDimensions(51, 52);

      expect(dimensions.height, 51);
      expect(dimensions.width, 52);
    });
  });

  group('StatsReport', () {
    test('exposes the peer connection id and starts with empty track lists', () {
      final report = StatsReport('pc-0');

      expect(report.peerConnectionId, 'pc-0');
      expect(report.localAudioTrackStats, isEmpty);
      expect(report.localVideoTrackStats, isEmpty);
      expect(report.remoteAudioTrackStats, isEmpty);
      expect(report.remoteVideoTrackStats, isEmpty);
    });

    test('keeps each added stat in its own list', () {
      final report = StatsReport('pc-1')
        ..addLocalAudioTrackStats(LocalAudioTrackStats('a', 1, 'c', 's', 1.0, 1, 1, 1, 1, 1))
        ..addLocalVideoTrackStats(LocalVideoTrackStats('b', 1, 'c', 's', 1.0, 1, 1, 1, VideoDimensions(1, 1), VideoDimensions(1, 1), 1, 1))
        ..addAudioTrackStats(RemoteAudioTrackStats('c', 1, 'c', 's', 1.0, 1, 1, 1, 1))
        ..addVideoTrackStats(RemoteVideoTrackStats('d', 1, 'c', 's', 1.0, 1, 1, VideoDimensions(1, 1), 1));

      expect(report.localAudioTrackStats.single.trackSid, 'a');
      expect(report.localVideoTrackStats.single.trackSid, 'b');
      expect(report.remoteAudioTrackStats.single.trackSid, 'c');
      expect(report.remoteVideoTrackStats.single.trackSid, 'd');
    });
  });

  group('.getStats() maps platform map keys onto the right fields', () {
    final mockInterface = MockInterface();
    setUp(() {
      ProgrammableVideoPlatform.instance = mockInterface;
      mockInterface.statsToReturn = {
        'pc-a': {
          'localAudioTrackStats': [
            {
              'trackSid': 'las-sid',
              'packetsLost': 11,
              'codec': 'opus',
              'ssrc': 'las-ssrc',
              'timestamp': 1.5,
              'bytesSent': 12,
              'packetsSent': 13,
              'roundTripTime': 14,
              'audioLevel': 15,
              'jitter': 16,
            }
          ],
          'localVideoTrackStats': [
            {
              'trackSid': 'lvs-sid',
              'packetsLost': 21,
              'codec': 'VP8',
              'ssrc': 'lvs-ssrc',
              'timestamp': 2.5,
              'bytesSent': 22,
              'packetsSent': 23,
              'roundTripTime': 24,
              'captureDimensionsHeight': 25,
              'captureDimensionsWidth': 26,
              'dimensionsHeight': 27,
              'dimensionsWidth': 28,
              'capturedFrameRate': 29,
              'frameRate': 30,
            }
          ],
          'remoteAudioTrackStats': [
            {
              'trackSid': 'ras-sid',
              'packetsLost': 31,
              'codec': 'PCMU',
              'ssrc': 'ras-ssrc',
              'timestamp': 3.5,
              'bytesReceived': 32,
              'packetsReceived': 33,
              'audioLevel': 34,
              'jitter': 35,
            }
          ],
          'remoteVideoTrackStats': [
            {
              'trackSid': 'rvs-sid',
              'packetsLost': 41,
              'codec': 'H264',
              'ssrc': 'rvs-ssrc',
              'timestamp': 4.5,
              'bytesReceived': 42,
              'packetsReceived': 43,
              'dimensionsHeight': 44,
              'dimensionsWidth': 45,
              'frameRate': 46,
            }
          ],
        },
      };
    });

    tearDown(() => mockInterface.statsToReturn = {});

    test('builds one report per peer connection', () async {
      final reports = await TwilioProgrammableVideo.getStats();

      expect(reports, hasLength(1));
      expect(reports!.single.peerConnectionId, 'pc-a');
    });

    test('maps localAudioTrackStats', () async {
      final stats = (await TwilioProgrammableVideo.getStats())!.single.localAudioTrackStats.single;

      expect(stats.trackSid, 'las-sid');
      expect(stats.packetsLost, 11);
      expect(stats.codec, 'opus');
      expect(stats.ssrc, 'las-ssrc');
      expect(stats.timestamp, 1.5);
      expect(stats.bytesSent, 12);
      expect(stats.packetsSent, 13);
      expect(stats.roundTripTime, 14);
      expect(stats.audioLevel, 15);
      expect(stats.jitter, 16);
    });

    test('maps localVideoTrackStats', () async {
      final stats = (await TwilioProgrammableVideo.getStats())!.single.localVideoTrackStats.single;

      expect(stats.trackSid, 'lvs-sid');
      expect(stats.packetsLost, 21);
      expect(stats.codec, 'VP8');
      expect(stats.ssrc, 'lvs-ssrc');
      expect(stats.timestamp, 2.5);
      expect(stats.bytesSent, 22);
      expect(stats.packetsSent, 23);
      expect(stats.roundTripTime, 24);
      expect(stats.captureDimensions.height, 25);
      expect(stats.captureDimensions.width, 26);
      expect(stats.dimensions.height, 27);
      expect(stats.dimensions.width, 28);
      expect(stats.capturedFrameRate, 29);
      expect(stats.frameRate, 30);
    });

    test('maps remoteAudioTrackStats', () async {
      final stats = (await TwilioProgrammableVideo.getStats())!.single.remoteAudioTrackStats.single;

      expect(stats.trackSid, 'ras-sid');
      expect(stats.packetsLost, 31);
      expect(stats.codec, 'PCMU');
      expect(stats.ssrc, 'ras-ssrc');
      expect(stats.timestamp, 3.5);
      expect(stats.bytesReceived, 32);
      expect(stats.packetsReceived, 33);
      expect(stats.audioLevel, 34);
      expect(stats.jitter, 35);
    });

    test('maps remoteVideoTrackStats', () async {
      final stats = (await TwilioProgrammableVideo.getStats())!.single.remoteVideoTrackStats.single;

      expect(stats.trackSid, 'rvs-sid');
      expect(stats.packetsLost, 41);
      expect(stats.codec, 'H264');
      expect(stats.ssrc, 'rvs-ssrc');
      expect(stats.timestamp, 4.5);
      expect(stats.bytesReceived, 42);
      expect(stats.packetsReceived, 43);
      expect(stats.dimensions.height, 44);
      expect(stats.dimensions.width, 45);
      expect(stats.frameRate, 46);
    });
  });
}
