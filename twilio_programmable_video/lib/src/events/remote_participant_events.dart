part of '../parts.dart';

/// The base event class for [RemoteParticipant] events.
class RemoteParticipantEvent {
  /// The associated remote participant.
  final RemoteParticipant remoteParticipant;

  RemoteParticipantEvent(this.remoteParticipant);
}

//#region AUDIO TRACK EVENTS

class RemoteAudioTrackEvent extends RemoteParticipantEvent {
  /// The audio track publication.
  final RemoteAudioTrackPublication remoteAudioTrackPublication;

  RemoteAudioTrackEvent(
    super.remoteParticipant,
    this.remoteAudioTrackPublication,
  );
}

class RemoteAudioTrackSubscriptionEvent extends RemoteAudioTrackEvent {
  /// The audio track
  final RemoteAudioTrack _remoteAudioTrack;

  RemoteAudioTrackSubscriptionEvent(
    super.remoteParticipant,
    super.remoteAudioTrackPublication,
    this._remoteAudioTrack,
  );

  RemoteAudioTrack get remoteAudioTrack => _remoteAudioTrack;
}

class RemoteAudioTrackSubscriptionFailedEvent extends RemoteAudioTrackEvent {
  /// Exception that describes the failure.
  final TwilioException exception;

  RemoteAudioTrackSubscriptionFailedEvent(
    super.remoteParticipant,
    super.remoteAudioTrackPublication,
    this.exception,
  );
}
//#endregion

//#region DATA TRACK EVENTS

class RemoteDataTrackEvent extends RemoteParticipantEvent {
  /// The data track publication
  final RemoteDataTrackPublication remoteDataTrackPublication;

  RemoteDataTrackEvent(
    super.remoteParticipant,
    this.remoteDataTrackPublication,
  );
}

class RemoteDataTrackSubscriptionEvent extends RemoteDataTrackEvent {
  /// The data track this subscription is associated with
  final RemoteDataTrack remoteDataTrack;

  RemoteDataTrackSubscriptionEvent(
    super.remoteParticipant,
    super.remoteDataTrackPublication,
    this.remoteDataTrack,
  );
}

class RemoteDataTrackSubscriptionFailedEvent extends RemoteDataTrackEvent {
  /// Exception that describes the failure.
  final TwilioException exception;

  RemoteDataTrackSubscriptionFailedEvent(
    super.remoteParticipant,
    super.remoteDataTrackPublication,
    this.exception,
  );
}

//#endregion

class RemoteNetworkQualityLevelChangedEvent implements NetworkQualityLevelChangedEvent {
  /// The local participant
  final RemoteParticipant remoteParticipant;

  /// The new [NetworkQualityLevel]
  @override
  final NetworkQualityLevel networkQualityLevel;

  RemoteNetworkQualityLevelChangedEvent(
    this.remoteParticipant,
    this.networkQualityLevel,
  );
}

//#region VIDEO TRACK EVENTS

class RemoteVideoTrackEvent extends RemoteParticipantEvent {
  /// The video track publication.
  final RemoteVideoTrackPublication remoteVideoTrackPublication;

  RemoteVideoTrackEvent(
    super.remoteParticipant,
    this.remoteVideoTrackPublication,
  );
}

class RemoteVideoTrackSubscriptionEvent extends RemoteVideoTrackEvent {
  /// The video track this event is associated with.
  final RemoteVideoTrack remoteVideoTrack;

  RemoteVideoTrackSubscriptionEvent(
    super.remoteParticipant,
    super.remoteVideoTrackPublication,
    this.remoteVideoTrack,
  );
}

class RemoteVideoTrackSubscriptionFailedEvent extends RemoteVideoTrackEvent {
  /// Exception that describes the failure.
  final TwilioException exception;

  RemoteVideoTrackSubscriptionFailedEvent(
    super.remoteParticipant,
    super.remoteVideoTrackPublication,
    this.exception,
  );
}

//#endregion
