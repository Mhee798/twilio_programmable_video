import 'package:twilio_programmable_video_platform_interface/src/models/model_exports.dart';

/// Model that a plugin implementation can use to construct a RemoteAudioTrack.
class RemoteAudioTrackModel extends TrackModel {
  final String sid;

  const RemoteAudioTrackModel({
    required super.name,
    required super.enabled,
    required this.sid,
  });

  factory RemoteAudioTrackModel.fromEventChannelMap(Map<String, dynamic> map) {
    return RemoteAudioTrackModel(
      name: map['name'],
      enabled: map['enabled'],
      sid: map['sid'],
    );
  }

  @override
  String toString() {
    return '{ name: $name, enabled: $enabled, sid: $sid }';
  }
}
