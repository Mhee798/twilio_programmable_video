import 'package:collection/collection.dart' show IterableExtension;
import 'package:flutter_test/flutter_test.dart';
import 'package:twilio_programmable_video/src/parts.dart';
import 'package:twilio_programmable_video_platform_interface/twilio_programmable_video_platform_interface.dart';

import 'mock_platform_interface.dart';
import 'model_instances.dart';

void main() {
  group('.disconnect()', () {
    test('should call interface code to disconnect from room', () async {
      final mockInterface = MockInterface();
      ProgrammableVideoPlatform.instance = mockInterface;
      final room = Room(0);
      await room.disconnect();

      expect(mockInterface.disconnectWasCalled, true);
    });
  });

  MockInterface? mockInterface;
  Room? room;
  setUp(() {
    mockInterface = MockInterface();
    ProgrammableVideoPlatform.instance = mockInterface!;
    room = Room(0);
  });

  group('Room', () {
    test('should update properties correctly from an interface event', () async {
      const updateRoom = RoomModel(
        name: 'updateRoom',
        sid: 'updateRoomSid',
        mediaRegion: Region.jp1,
        state: RoomState.RECONNECTING,
        localParticipant: ModelInstances.localParticipantModel,
        remoteParticipants: <RemoteParticipantModel>[],
      );

      mockInterface!.addRoomEvent(const Connected(updateRoom));
      expect(await room!.onConnected.first, room);
      expect(room!.name, updateRoom.name);
      expect(room!.sid, updateRoom.sid);
      expect(room!.mediaRegion, updateRoom.mediaRegion);
      expect(room!.state, updateRoom.state);
    });
  });

  group('.onConnected', () {
    test('should return current room after a `Connected` event arrives from the interface', () async {
      mockInterface!.addRoomEvent(const Connected(ModelInstances.roomModel));
      expect(await room!.onConnected.first, room);
    });
  });

  group('.onDisconnected', () {
    // A legitimate Disconnected always follows Connected: both the iOS and Android
    // listeners report a failed connect through `onConnectFailure`, never through
    // `onDisconnected`. Room relies on that to drop cross-call event bleed, so these
    // tests have to emit Connected first to reproduce a real event sequence.
    test('should return correct `RoomDisconnectedEvent` after a `Disconnected` event arrives from the interface', () async {
      const exceptionModel = ModelInstances.twilioExceptionModel;
      mockInterface!.addRoomEvent(const Connected(ModelInstances.roomModel));
      await room!.onConnected.first;

      mockInterface!.addRoomEvent(const Disconnected(ModelInstances.roomModel, exceptionModel));
      final event = await room!.onDisconnected.first;
      expect(event.room, room);
      expect(event.exception?.code, exceptionModel.code);
      expect(event.exception?.message, exceptionModel.message);
    });

    /// Emits [model] as a `Disconnected` and reports whether `onDisconnected` fired.
    /// `pumpEventQueue` settles it deterministically: `MockInterface` is a plain
    /// `StreamController` and `Room._parseRoomEvents` is synchronous, so there is
    /// nothing timer-based to wait on.
    Future<bool> disconnectedFires(RoomModel model) async {
      var fired = false;
      final subscription = room!.onDisconnected.listen((_) => fired = true);

      mockInterface!.addRoomEvent(Disconnected(model, ModelInstances.twilioExceptionModel));
      await pumpEventQueue();

      await subscription.cancel();
      return fired;
    }

    test('should ignore a `Disconnected` event for a room that never connected', () async {
      expect(await disconnectedFires(ModelInstances.roomModel), false);
    });

    test('should ignore a stale `Disconnected` event belonging to a different room', () async {
      mockInterface!.addRoomEvent(const Connected(ModelInstances.roomModel));
      await room!.onConnected.first;

      expect(await disconnectedFires(ModelInstances.otherRoomModel), false);
      expect(room!.sid, ModelInstances.roomModel.sid);
    });
  });

  group('.onConnectFailure', () {
    test('should return correct `RoomConnectFailureEvent` after a `ConnectFailure` event arrives from the interface', () async {
      const exceptionModel = ModelInstances.twilioExceptionModel;
      mockInterface!.addRoomEvent(const ConnectFailure(ModelInstances.roomModel, exceptionModel));
      final event = await room!.onConnectFailure.first;
      expect(event.room, room);
      expect(event.exception?.code, exceptionModel.code);
      expect(event.exception?.message, exceptionModel.message);
    });
  });

  group('.onDominantSpeakerChange', () {
    test('should return correct `DominantSpeakerChangedEvent` after a `DominantSpeakerChanged` event arrives from the interface', () async {
      const remoteParticipantModel = ModelInstances.remoteParticipantModel;
      mockInterface!.addRoomEvent(const DominantSpeakerChanged(ModelInstances.roomModel, remoteParticipantModel));
      final event = await room!.onDominantSpeakerChange.first;
      expect(event.room, room);
      expect(
        event.remoteParticipant,
        room!.remoteParticipants.firstWhereOrNull(
          (RemoteParticipant p) => p.sid == event.remoteParticipant?.sid,
        ),
      );
    });
  });

  group('.onParticipantConnected', () {
    test('should return correct `RoomParticipantConnectedEvent` after a `ParticipantConnected` event arrives from the interface', () async {
      const remoteParticipantModel = ModelInstances.remoteParticipantModel;
      mockInterface!.addRoomEvent(const ParticipantConnected(ModelInstances.roomModel, remoteParticipantModel));
      final event = await room!.onParticipantConnected.first;
      expect(event.room, room);
      expect(
        event.remoteParticipant,
        room!.remoteParticipants.firstWhereOrNull(
          (RemoteParticipant p) => p.sid == event.remoteParticipant.sid,
        ),
      );
    });
  });

  group('.onParticipantDisconnected', () {
    test('should return correct `RoomParticipantDisconnectedEvent` after a `ParticipantDisconnected` event arrives from the interface', () async {
      const remoteParticipantModel = ModelInstances.remoteParticipantModel;
      mockInterface!.addRoomEvent(const ParticipantDisconnected(ModelInstances.roomModel, remoteParticipantModel));
      final event = await room!.onParticipantDisconnected.first;
      expect(event.room, room);
      expect(
        null,
        room!.remoteParticipants.firstWhereOrNull(
          (RemoteParticipant p) => p.sid == event.remoteParticipant.sid,
        ),
      );
    });
  });

  group('.onReconnected', () {
    test('should return current room after a `Reconnected` event arrives from the interface', () async {
      mockInterface!.addRoomEvent(const Reconnected(ModelInstances.roomModel));
      expect(await room!.onReconnected.first, room);
    });
  });

  group('.onReconnecting', () {
    test('should return correct `RoomReconnectingEvent` after a `Reconnecting` event arrives from the interface', () async {
      const exceptionModel = ModelInstances.twilioExceptionModel;
      mockInterface!.addRoomEvent(const Reconnecting(ModelInstances.roomModel, exceptionModel));
      final event = await room!.onReconnecting.first;
      expect(event.room, room);
      expect(event.exception?.code, exceptionModel.code);
      expect(event.exception?.message, exceptionModel.message);
    });
  });

  group('.onRecordingStarted', () {
    test('should return current room after a `RecordingStarted` event arrives from the interface', () async {
      mockInterface!.addRoomEvent(const RecordingStarted(ModelInstances.roomModel));
      expect(await room!.onRecordingStarted.first, room);
    });
  });

  group('.onRecordingStopped', () {
    test('should return current room after a `RecordingStarted` event arrives from the interface', () async {
      mockInterface!.addRoomEvent(const RecordingStopped(ModelInstances.roomModel));
      expect(await room!.onRecordingStopped.first, room);
    });
  });
}
