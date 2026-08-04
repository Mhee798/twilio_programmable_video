part of 'parts.dart';

abstract class VideoTrack extends Track {
  // Types are inherited from Track(this._enabled, this._name): bool, String.
  VideoTrack(super.enabled, super.name);
}
