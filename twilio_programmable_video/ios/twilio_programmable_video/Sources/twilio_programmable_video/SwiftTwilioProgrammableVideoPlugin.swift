import Flutter
import UIKit
import TwilioVideo

// Flutter's generated registrants look this plugin up by the `pluginClass` declared
// in pubspec.yaml: `TwilioProgrammableVideoPlugin`. A single Swift Package target
// cannot mix Swift and ObjC sources, so the hand-written ObjC shim that used to
// provide that name is gone and two aliases stand in for it:
//
//   - the `typealias` below, for the Swift Package Manager path.
//     GeneratedPluginRegistrant.swift references the name as a *Swift* symbol, and
//     `@objc(...)` renames the class for Objective-C only — without this the
//     registrant fails with "cannot find 'TwilioProgrammableVideoPlugin' in scope".
//   - `@objc(TwilioProgrammableVideoPlugin)`, for the CocoaPods path.
//     GeneratedPluginRegistrant.m calls `[TwilioProgrammableVideoPlugin
//     registerWithRegistrar:]` against the Objective-C runtime name.
public typealias TwilioProgrammableVideoPlugin = SwiftTwilioProgrammableVideoPlugin

@objc(TwilioProgrammableVideoPlugin)
public class SwiftTwilioProgrammableVideoPlugin: NSObject, FlutterPlugin {
    static var pluginHandler: PluginHandler = PluginHandler()

    internal static var roomListener: RoomListener?

    internal static var remoteParticipantListener = RemoteParticipantListener()

    internal static var localParticipantListener = LocalParticipantListener()

    internal static var remoteDataTrackListener = RemoteDataTrackListener()

    internal static var audioNotificationListener = AudioNotificationListener()

    public static var cameraSource: CameraSource?

    public static var loggingSink: FlutterEventSink?

    public static var nativeDebug = false

    public static var audioDebug = false

    internal static var audioDevice: AudioDevice?

    internal static var audioDeviceOnConnected: (() -> Void)?

    internal static var audioDeviceOnDisconnected: (() -> Void)?

    public static var localVideoTracks: [String: LocalVideoTrack] = [:]

    public static func debug(_ msg: String) {
        if SwiftTwilioProgrammableVideoPlugin.nativeDebug {
            NSLog(msg)
            guard let loggingSink = loggingSink else {
                return
            }
            loggingSink(msg)
        }
    }

    public static func debugAudio(_ msg: String) {
        if SwiftTwilioProgrammableVideoPlugin.audioDebug {
            guard let loggingSink = loggingSink else {
                return
            }
            loggingSink(msg)
        }
    }

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = SwiftTwilioProgrammableVideoPlugin()
        instance.onRegister(registrar)
    }

    public static func setCustomAudioDevice(_ device: AudioDevice, onConnected: @escaping () -> Void, onDisconnected: @escaping () -> Void) {
        audioDevice = device
        audioDeviceOnConnected = onConnected
        audioDeviceOnDisconnected = onDisconnected
        if let audioDevice = audioDevice as? AVAudioEngineDevice {
            audioDevice.setApplyAudioSettings(pluginHandler.applyAudioSettings)
        }
    }

    public static func clearCustomAudioDevice() {
        audioDevice = nil
        audioDeviceOnConnected = nil
        audioDeviceOnDisconnected = nil
    }

    private var methodChannel: FlutterMethodChannel?

    private var cameraChannel: FlutterEventChannel?

    private var roomChannel: FlutterEventChannel?

    private var remoteParticipantChannel: FlutterEventChannel?

    private var localParticipantChannel: FlutterEventChannel?

    private var loggingChannel: FlutterEventChannel?

    private var remoteDataTrackChannel: FlutterEventChannel?

    private var audioNotificationChannel: FlutterEventChannel?

    public func onRegister(_ registrar: FlutterPluginRegistrar) {
        methodChannel = FlutterMethodChannel(name: "twilio_programmable_video", binaryMessenger: registrar.messenger())
        methodChannel?.setMethodCallHandler(SwiftTwilioProgrammableVideoPlugin.pluginHandler.handle)

        cameraChannel = FlutterEventChannel(name: "twilio_programmable_video/camera", binaryMessenger: registrar.messenger())
        cameraChannel?.setStreamHandler(CameraStreamHandler())

        roomChannel = FlutterEventChannel(name: "twilio_programmable_video/room", binaryMessenger: registrar.messenger())
        roomChannel?.setStreamHandler(RoomStreamHandler())

        remoteParticipantChannel = FlutterEventChannel(name: "twilio_programmable_video/remote", binaryMessenger: registrar.messenger())
        remoteParticipantChannel?.setStreamHandler(RemoteParticipantStreamHandler())

        localParticipantChannel = FlutterEventChannel(name: "twilio_programmable_video/local", binaryMessenger: registrar.messenger())
        localParticipantChannel?.setStreamHandler(LocalParticipantStreamHandler())

        loggingChannel = FlutterEventChannel(name: "twilio_programmable_video/logging", binaryMessenger: registrar.messenger())
        loggingChannel?.setStreamHandler(LoggingStreamHandler())

        remoteDataTrackChannel = FlutterEventChannel(name: "twilio_programmable_video/remote_data_track", binaryMessenger: registrar.messenger())
        remoteDataTrackChannel?.setStreamHandler(RemoteDataTrackStreamHandler())

        audioNotificationChannel = FlutterEventChannel(name: "twilio_programmable_video/audio_notification", binaryMessenger: registrar.messenger())
        audioNotificationChannel?.setStreamHandler(AudioNotificationStreamHandler())

        let pvf = ParticipantViewFactory(SwiftTwilioProgrammableVideoPlugin.pluginHandler)
        registrar.register(pvf, withId: "twilio_programmable_video/views")
    }

    class CameraStreamHandler: NSObject, FlutterStreamHandler {
        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("CameraStreamHandler::onListen => Camera eventChannel attached")
            pluginHandler.events = events
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("CameraStreamHandler::onCancel => Camera eventChannel detached")
            pluginHandler.events = nil
            return nil
        }
    }

    class RoomStreamHandler: NSObject, FlutterStreamHandler {
        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            guard let roomListener = SwiftTwilioProgrammableVideoPlugin.roomListener else { return nil }
            SwiftTwilioProgrammableVideoPlugin.debug("RoomStreamHandler::onListen => Room eventChannel attached")
            roomListener.events = events
            roomListener.room = TwilioVideoSDK.connect(options: roomListener.connectOptions, delegate: roomListener)
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("RoomStreamHandler::onCancel => Room eventChannel detached")
            guard let roomListener = SwiftTwilioProgrammableVideoPlugin.roomListener else { return nil }
            roomListener.events = nil
            return nil
        }
    }

    class RemoteParticipantStreamHandler: NSObject, FlutterStreamHandler {
        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("RemoteParticipantStreamHandler::onListen => RemoteParticipant eventChannel attached")
            SwiftTwilioProgrammableVideoPlugin.remoteParticipantListener.events = events
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("RemoteParticipantStreamHandler::onCancel => RemoteParticipant eventChannel detached")
            SwiftTwilioProgrammableVideoPlugin.remoteParticipantListener.events = nil
            return nil
        }
    }

    class LocalParticipantStreamHandler: NSObject, FlutterStreamHandler {
        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("LocalParticipantStreamHandler::onListen => LocalParticipant eventChannel attached")
            SwiftTwilioProgrammableVideoPlugin.localParticipantListener.events = events
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("LocalParticipantStreamHandler::onCancel => LocalParticipant eventChannel detached")
            SwiftTwilioProgrammableVideoPlugin.localParticipantListener.events = nil
            return nil
        }
    }

    class LoggingStreamHandler: NSObject, FlutterStreamHandler {
        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("LoggingStreamHandler::onListen => Logging eventChannel attached")
            SwiftTwilioProgrammableVideoPlugin.loggingSink = events
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("LoggingStreamHandler::onCancel => Logging eventChannel detached")
            SwiftTwilioProgrammableVideoPlugin.loggingSink = nil
            return nil
        }
    }

    class RemoteDataTrackStreamHandler: NSObject, FlutterStreamHandler {
        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("RemoteDataTrackStreamHandler::onListen => RemoteDataTrack eventChannel attached")
            SwiftTwilioProgrammableVideoPlugin.remoteDataTrackListener.events = events
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("RemoteDataTrackStreamHandler::onCancel => RemoteDataTrack eventChannel detached")
            SwiftTwilioProgrammableVideoPlugin.remoteDataTrackListener.events = nil
            return nil
        }
    }

    class AudioNotificationStreamHandler: NSObject, FlutterStreamHandler {
        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("AudioNotificationStreamHandler::onListen => AudioNotification eventChannel attached")
            SwiftTwilioProgrammableVideoPlugin.audioNotificationListener.events = events
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            SwiftTwilioProgrammableVideoPlugin.debug("AudioNotificationStreamHandler::onCancel => AudioNotification eventChannel detached")
            SwiftTwilioProgrammableVideoPlugin.audioNotificationListener.events = nil
            return nil
        }
    }
}
