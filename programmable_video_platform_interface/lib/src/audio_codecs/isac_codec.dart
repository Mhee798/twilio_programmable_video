import 'audio_codec.dart';

/// iSAC is no longer implemented by the Twilio SDKs this plugin builds on.
///
/// Both native SDKs dropped it with their WebRTC 112 upgrade — Android in Twilio
/// 7.7.0, iOS in TwilioVideo 5.8.0 — and both now resolve a request for it to
/// [OpusCodec]. On web the name is still accepted by twilio-video.js, but browsers
/// that dropped iSAC will not negotiate it either. The class is kept so existing
/// code keeps compiling; it has no effect beyond selecting opus.
@Deprecated('iSAC was removed from the Twilio SDKs; requests for it select opus instead.')
class IsacCodec extends AudioCodec {
  static const String NAME = 'isac';

  IsacCodec() : super(NAME);
}
