import Flutter
import Foundation
import TwilioVideo

class ParticipantView: NSObject, FlutterPlatformView {
    private var videoView: VideoView

    private var videoTrack: VideoTrack?

    init(_ videoView: VideoView, videoTrack: VideoTrack?) {
        self.videoView = videoView
        self.videoTrack = videoTrack
        // track อาจยังไม่มีตอนสร้าง view (เช่น video ถูก unpublish ตอน background
        // pause แล้ว platform view rebuild ก่อน republish) — ไม่ attach renderer
        // ดีกว่า crash; track จะถูก attach ใหม่ตอน view สร้างซ้ำหลัง republish
        videoTrack?.addRenderer(videoView)
    }

    public func view() -> UIView {
        return videoView
    }
}
