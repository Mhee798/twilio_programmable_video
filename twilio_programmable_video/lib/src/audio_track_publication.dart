part of 'parts.dart';

/// A published audio track represents an audio track that has been shared with a [Room].
abstract class AudioTrackPublication extends TrackPublication {
  /// The published audio track.
  AudioTrack? get audioTrack;
}
