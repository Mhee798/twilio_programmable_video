import 'package:twilio_programmable_video_platform_interface/src/models/model_exports.dart';

class LocalAudioTrackModel extends TrackModel {
  const LocalAudioTrackModel({
    required super.name,
    required super.enabled,
  });

  factory LocalAudioTrackModel.fromEventChannelMap(Map<String, dynamic> map) {
    return LocalAudioTrackModel(
      enabled: map['enabled'],
      name: map['name'],
    );
  }
}
