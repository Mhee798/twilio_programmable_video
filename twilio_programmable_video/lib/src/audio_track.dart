part of 'parts.dart';

/// Abstract base class for audio tracks.
abstract class AudioTrack extends Track {
  // Types are inherited from Track(this._enabled, this._name): bool, String.
  AudioTrack(super.enabled, super.name);
}
