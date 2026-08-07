package twilio.flutter.twilio_programmable_video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import com.twilio.video.LocalVideoTrack
import com.twilio.video.Video
import com.twilio.video.VideoCapturer
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformViewRegistry
import tvi.webrtc.Camera1Enumerator
import tvi.webrtc.Camera2Enumerator
import tvi.webrtc.CameraEnumerator

/** TwilioProgrammableVideoPlugin */
class TwilioProgrammableVideoPlugin : FlutterPlugin {
    private lateinit var methodChannel: MethodChannel
    private lateinit var cameraChannel: EventChannel
    private lateinit var roomChannel: EventChannel
    private lateinit var remoteParticipantChannel: EventChannel
    private lateinit var localParticipantChannel: EventChannel
    private lateinit var loggingChannel: EventChannel
    private lateinit var remoteDataTrackChannel: EventChannel
    private lateinit var audioNotificationChannel: EventChannel

    /**
     * The handler this engine's own channels talk to.
     *
     * Distinct from the shared [pluginHandler], which may belong to a different engine —
     * see its documentation.
     */
    private lateinit var enginePluginHandler: PluginHandler

    companion object {
        @JvmStatic
        val LOG_TAG = "Twilio_PVideo"

        val localVideoTracks = mutableMapOf<String, LocalVideoTrack>()

        /**
         * The handler the process-wide statics reach the plugin through: camera events
         * from [VideoCapturerHandler], and the audio player listener handed to other
         * plugins by [getAudioPlayerEventListener].
         *
         * Owned by the engine that last made a method channel call — the one actually
         * using the plugin — rather than the last one to *attach*. A second FlutterEngine
         * in the same process (a `FirebaseMessaging` background handler, a CallKit
         * isolate, an add-to-app host) has every plugin registered whether its Dart side
         * wants them or not, and assigning this on attach handed the statics to it:
         * camera events then went to a handler whose event sink nothing had listened to,
         * so they were dropped silently, and stayed dropped, because
         * `onDetachedFromEngine` never gave the pointer back.
         *
         * Ownership by attach order does not work either, in either direction. Newest-wins
         * is the bug above; oldest-wins strands the pointer on the background engine when
         * an incoming call wakes a terminated app, which is the order that matters most —
         * the background engine attaches first and the UI engine second. What the two
         * consumers want is the engine driving the call, and a method channel call is the
         * only signal of that the plugin gets.
         *
         * Each engine keeps its own handler for its own channels; only this pointer is
         * shared. [claimSharedStatics] moves it, and a detaching owner hands it to
         * whichever engine is still attached.
         */
        lateinit var pluginHandler: PluginHandler
            private set

        /** Attached plugin instances in attach order; the first one owns [pluginHandler]. */
        private val attachedPlugins = mutableListOf<TwilioProgrammableVideoPlugin>()
        lateinit var cameraEnumerator: CameraEnumerator
        lateinit var roomListener: RoomListener

        var camera2IsSupported: Boolean = false
        var cameraCapturer: VideoCapturer? = null
        var loggingSink: EventChannel.EventSink? = null
        var remoteParticipantListener = RemoteParticipantListener()
        var localParticipantListener = LocalParticipantListener()
        var handler = Handler(Looper.getMainLooper())
        var nativeDebug: Boolean = false
        var audioDebug: Boolean = false
        var remoteDataTrackListener = RemoteDataTrackListener()
        var audioNotificationListener = AudioNotificationListener()

        @JvmStatic
        val HARDWARE_AEC_BLACKLIST = hashSetOf(
                "Pixel",
                "Pixel 2",
                "Pixel XL",
                "Moto G5",
                "Moto G (5S) Plus",
                "Moto G4",
                "TA-1053",
                "Mi A1",
                "Mi A2",
                "E5823", // Sony z5 compact
                "Redmi Note 5",
                "FP2", // Fairphone FP2
                "MI 5"
        )

        @JvmStatic
        fun debug(msg: String) {
            if (nativeDebug) {
                Log.d(LOG_TAG, msg)
                handler.post {
                    loggingSink?.success(msg)
                }
            }
        }

        @JvmStatic
        fun debugAudio(msg: String) {
            if (audioDebug) {
                Log.d(LOG_TAG, msg)
                handler.post {
                    loggingSink?.success(msg)
                }
            }
        }

        @JvmStatic
        fun getAudioPlayerEventListener(): ((url: String, isPlaying: Boolean) -> Unit) {
            return audioNotificationListener::audioPlayerEventListener
        }

        @JvmStatic
        internal fun isConnected(): Boolean {
            return ::roomListener.isInitialized && roomListener.room != null
        }

        /**
         * `roomListener` is only assigned by `connect`, so any method channel call
         * that can arrive before that must read it through here. Touching the
         * `lateinit` property directly throws UninitializedPropertyAccessException,
         * which reaches Dart as an opaque PlatformException.
         */
        @JvmStatic
        internal val roomListenerOrNull: RoomListener?
            get() = if (::roomListener.isInitialized) roomListener else null

        /**
         * Hands [pluginHandler] to the engine [handler] belongs to.
         *
         * Called from every method channel call rather than from attach; see
         * [pluginHandler] for why that is the signal.
         */
        internal fun claimSharedStatics(handler: PluginHandler) {
            if (::pluginHandler.isInitialized && pluginHandler === handler) return
            pluginHandler = handler
            debug("pluginHandler => claimed by the engine now driving the plugin")
        }

        /**
         * Gives [pluginHandler] a value when it has none, and takes it off an engine that
         * is detaching, so it never points at a handler whose engine is gone while another
         * is still attached. Which of several attached engines it lands on does not matter:
         * the next method channel call settles it.
         */
        private fun repointPluginHandler() {
            attachedPlugins.firstOrNull()?.let { pluginHandler = it.enginePluginHandler }
        }

        private fun ownsSharedStatics(plugin: TwilioProgrammableVideoPlugin): Boolean {
            return ::pluginHandler.isInitialized && pluginHandler === plugin.enginePluginHandler
        }

        private fun isPluginHandlerInitialized(): Boolean = ::pluginHandler.isInitialized
    }

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(binding.applicationContext, binding.binaryMessenger, binding.platformViewRegistry)
    }

    private fun onAttachedToEngine(
        applicationContext: Context,
        messenger: BinaryMessenger,
        platformViewRegistry: PlatformViewRegistry
    ) {
        enginePluginHandler = PluginHandler(applicationContext)
        attachedPlugins.add(this)
        // Only to give the field a value at all. Attaching does not claim it — that is
        // what [claimSharedStatics] is for.
        if (!isPluginHandlerInitialized()) {
            repointPluginHandler()
        }
        // Logged because a second engine is otherwise invisible from outside, and it is
        // what decides where the shared statics point; a field report of "camera events
        // stopped" is unreadable without knowing an engine attached.
        debug("onAttachedToEngine => engines attached: ${attachedPlugins.size}")

        camera2IsSupported = Camera2Enumerator.isSupported(applicationContext)
        cameraEnumerator = if (camera2IsSupported)
            Camera2Enumerator(applicationContext)
        else
            Camera1Enumerator()

        methodChannel = MethodChannel(messenger, "twilio_programmable_video")
        methodChannel.setMethodCallHandler(enginePluginHandler)

        cameraChannel = EventChannel(messenger, "twilio_programmable_video/camera")
        cameraChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                debug("Camera eventChannel attached")
                enginePluginHandler.events = events
            }

            override fun onCancel(arguments: Any?) {
                debug("Camera eventChannel detached")
                enginePluginHandler.events = null
            }
        })

        roomChannel = EventChannel(messenger, "twilio_programmable_video/room")
        roomChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                debug("Room eventChannel attached")
                roomListener.events = events
                roomListener.room = Video.connect(applicationContext, roomListener.connectOptions, roomListener)
            }

            override fun onCancel(arguments: Any?) {
                debug("Room eventChannel detached")
                roomListener.events = null
            }
        })

        remoteParticipantChannel = EventChannel(messenger, "twilio_programmable_video/remote")
        remoteParticipantChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                debug("RemoteParticipant eventChannel attached")
                remoteParticipantListener.events = events
            }

            override fun onCancel(arguments: Any?) {
                debug("RemoteParticipant eventChannel detached")
                remoteParticipantListener.events = null
            }
        })

        localParticipantChannel = EventChannel(messenger, "twilio_programmable_video/local")
        localParticipantChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                debug("LocalParticipant eventChannel attached")
                localParticipantListener.events = events
            }

            override fun onCancel(arguments: Any?) {
                debug("LocalParticipant eventChannel detached")
                localParticipantListener.events = null
            }
        })

        loggingChannel = EventChannel(messenger, "twilio_programmable_video/logging")
        loggingChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                debug("Logging eventChannel attached")
                loggingSink = events
            }

            override fun onCancel(arguments: Any?) {
                debug("Logging eventChannel detached")
                loggingSink = null
            }
        })

        remoteDataTrackChannel = EventChannel(messenger, "twilio_programmable_video/remote_data_track")
        remoteDataTrackChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                debug("RemoteDataTrack eventChannel attached")
                remoteDataTrackListener.events = events
            }

            override fun onCancel(arguments: Any?) {
                debug("RemoteDataTrack eventChannel detached")
                remoteDataTrackListener.events = null
            }
        })

        audioNotificationChannel = EventChannel(messenger, "twilio_programmable_video/audio_notification")
        audioNotificationChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                debug("AudioNotification eventChannel attached")
                audioNotificationListener.events = events
            }

            override fun onCancel(arguments: Any?) {
                debug("AudioNotification eventChannel detached")
                audioNotificationListener.events = null
            }
        })

        val pvf = ParticipantViewFactory(StandardMessageCodec.INSTANCE, enginePluginHandler)
        platformViewRegistry.registerViewFactory("twilio_programmable_video/views", pvf)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        roomChannel.setStreamHandler(null)
        remoteParticipantChannel.setStreamHandler(null)
        loggingChannel.setStreamHandler(null)
        remoteDataTrackChannel.setStreamHandler(null)
        localParticipantChannel.setStreamHandler(null)
        cameraChannel.setStreamHandler(null)
        audioNotificationChannel.setStreamHandler(null)

        val wasSharedStaticsOwner = ownsSharedStatics(this)

        // The sink belongs to the isolate that is going away, and the release below
        // announces through it. `setStreamHandler(null)` does not reach the old handler's
        // onCancel, so this is the only thing that clears it.
        if (wasSharedStaticsOwner) {
            audioNotificationListener.events = null
        }

        // The route this engine engaged outlives it otherwise: nothing else stops the
        // router, so on API 31+ the communication device stays claimed and below that SCO
        // stays held, keeping a headset in call mode and stopping another app's audio from
        // resuming for the rest of the process. Device discovery leaks the same way — only
        // `disableAudioSettings` ever called stop(), and an app that never calls it (this
        // plugin's own README does not require it) left the scanners registered for good.
        //
        // Guarded on `isRunning` rather than calling stop() unconditionally: stop()
        // announces the known devices as unavailable, and that list lives on the shared
        // `audioNotificationListener`. A background engine detaching would otherwise
        // withdraw the headsets a call running on another engine is still using.
        if (enginePluginHandler.audioRouter.isRunning) {
            enginePluginHandler.audioRouter.stop()
        }

        attachedPlugins.remove(this)
        if (wasSharedStaticsOwner) {
            repointPluginHandler()
        }
        debug("onDetachedFromEngine => owned the shared statics: $wasSharedStaticsOwner, " +
                "engines still attached: ${attachedPlugins.size}")
    }
}
