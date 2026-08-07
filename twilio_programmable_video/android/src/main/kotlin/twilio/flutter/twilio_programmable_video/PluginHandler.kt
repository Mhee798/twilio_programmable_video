package twilio.flutter.twilio_programmable_video

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.NonNull
import com.twilio.video.AudioCodec
import com.twilio.video.Camera2Capturer
import com.twilio.video.CameraCapturer
import com.twilio.video.ConnectOptions
import com.twilio.video.DataTrackOptions
import com.twilio.video.G722Codec
import com.twilio.video.H264Codec
// import com.twilio.video.IsacCodec // Removed in SDK 7.7.0+ due to WebRTC-m112 upgrade
import com.twilio.video.LocalAudioTrack
import com.twilio.video.LocalDataTrack
import com.twilio.video.LocalParticipant
import com.twilio.video.LocalVideoTrack
import com.twilio.video.NetworkQualityConfiguration
import com.twilio.video.NetworkQualityVerbosity
import com.twilio.video.OpusCodec
import com.twilio.video.PcmaCodec
import com.twilio.video.PcmuCodec
import com.twilio.video.RemoteAudioTrackPublication
import com.twilio.video.RemoteParticipant
import com.twilio.video.Room
import com.twilio.video.VideoCodec
import com.twilio.video.VideoDimensions
import com.twilio.video.VideoFormat
import com.twilio.video.Vp8Codec
import com.twilio.video.Vp9Codec
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.nio.ByteBuffer
import java.util.ArrayList
// import tvi.webrtc.voiceengine.WebRtcAudioUtils // Removed in SDK 7.0+ due to WebRTC upgrade

class PluginHandler : MethodCallHandler, ActivityAware, BaseListener {
    private val TAG = "PluginHandler"

    // Capture ceiling 720p@24 (เดิมไม่ส่ง format → SDK default VGA 640x480)
    // เป็นแค่เพดาน — WebRTC ลด encode resolution/bitrate ลงเองเมื่อ
    // bandwidth ไม่พอ และไต่กลับเมื่อเน็ตฟื้น
    private val captureVideoFormat = VideoFormat(VideoDimensions.HD_720P_VIDEO_DIMENSIONS, 24)

    private var previousAudioMode: Int? = null

    private var previousMicrophoneMute: Boolean = false

    private var audioFocusRequest: AudioFocusRequest? = null

    private var previousVolumeControlStream: Int = 0

    private var activity: Activity? = null

    var applicationContext: Context

    internal var audioManager: AudioManager

    internal var audioSettings: AudioSettings = AudioSettings()

    internal var audioRouter: AudioRouter

    @Suppress("ConvertSecondaryConstructorToPrimary")
    constructor(applicationContext: Context) {
        this.applicationContext = applicationContext
        audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioRouter = AudioRouter(applicationContext)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        this.activity = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity
    }

    private val remoteParticipants: List<RemoteParticipant>?
        get() {
            return TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.remoteParticipants?.toList()
        }

    fun getRemoteParticipant(sid: String?): RemoteParticipant? {
        return remoteParticipants?.first { it.sid == sid }
    }

    fun getLocalParticipant(): LocalParticipant? {
        return TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.localParticipant
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        // `getStats`, if called repeatedly to drive an animation, is quite noisy
        if (call.method != "getStats") {
            debug("onMethodCall => received ${call.method}")
        }

        // A call arriving here is what identifies the engine that is actually using the
        // plugin, and so where the process-wide statics should point — see
        // TwilioProgrammableVideoPlugin.pluginHandler. Attaching is not the same thing: a
        // background engine gets the plugin registered whether its Dart side wants it or
        // not, which is how it used to take those statics over.
        TwilioProgrammableVideoPlugin.claimSharedStatics(this)
        when (call.method) {
            "debug" -> debug(call, result)
            "connect" -> connect(call, result)
            "disconnect" -> disconnect(call, result)
            "setAudioSettings" -> setAudioSettings(call, result)
            "getAudioSettings" -> getAudioSettings(call, result)
            "disableAudioSettings" -> disableAudioSettings(call, result)
            "setSpeakerphoneOn" -> setSpeakerphoneOn(call, result)
            "getSpeakerphoneOn" -> getSpeakerphoneOn(result)
            "deviceHasReceiver" -> deviceHasReceiver(result)
            "getStats" -> getStats(result)
            "LocalAudioTrack#enable" -> localAudioTrackEnable(call, result)
            "LocalDataTrack#sendString" -> localDataTrackSendString(call, result)
            "LocalDataTrack#sendByteBuffer" -> localDataTrackSendByteBuffer(call, result)
            "LocalVideoTrack#create" -> localVideoTrackCreate(call, result)
            "LocalVideoTrack#enable" -> localVideoTrackEnable(call, result)
            "LocalVideoTrack#publish" -> localVideoTrackPublish(call, result)
            "LocalVideoTrack#unpublish" -> localVideoTrackUnpublish(call, result)
            "LocalVideoTrack#release" -> localVideoTrackRelease(call, result)
            "RemoteAudioTrack#enablePlayback" -> remoteAudioTrackEnable(call, result)
            "RemoteAudioTrack#isPlaybackEnabled" -> isRemoteAudioTrackPlaybackEnabled(call, result)
            "CameraCapturer#switchCamera" -> switchCamera(call, result)
            "CameraCapturer#setTorch" -> setTorch(call, result)
            "CameraSource#getSources" -> getSources(call, result)
            else -> result.notImplemented()
        }
    }

    private fun getSources(call: MethodCall, result: MethodChannel.Result) {
        debug("getSources => called")
        return result.success(TwilioProgrammableVideoPlugin.cameraEnumerator.deviceNames.map {
            VideoCapturerHandler.cameraIdToMap(it)
        })
    }

    private fun switchCamera(call: MethodCall, result: MethodChannel.Result) {
        debug("switchCamera => called")
        val newCameraId = call.argument<String>("cameraId")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("cameraId"), null)

        val capturer = TwilioProgrammableVideoPlugin.cameraCapturer
        if (capturer is Camera2Capturer)
            capturer.switchCamera(newCameraId)
        else if (capturer is CameraCapturer)
            capturer.switchCamera(newCameraId)

        return result.success(VideoCapturerHandler.videoCapturerToMap(TwilioProgrammableVideoPlugin.cameraCapturer!!, newCameraId))
    }

    private fun setTorch(call: MethodCall, result: MethodChannel.Result) {
        VideoCapturerHandler.setTorch(call, result)
    }

    private fun localVideoTrackCreate(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
            ?: return result.error("MISSING_PARAMS", missingParameterMessage("name"), null)
        val enabled = call.argument<Boolean>("enable")
            ?: return result.error("MISSING_PARAMS", missingParameterMessage("enabled"), null)
        val videoCapturer = call.argument<Map<String, Any>>("videoCapturer")
            ?: return result.error("MISSING_PARAMS", missingParameterMessage("videoCapturer"), null)

        debug("localVideoTrackCreate => called for $name, enable=$enabled, videoCapturer=$videoCapturer")

        if (TwilioProgrammableVideoPlugin.cameraCapturer == null) {
            VideoCapturerHandler.initializeCapturer(videoCapturer, result)
        }

        if (TwilioProgrammableVideoPlugin.localVideoTracks[name] == null) {
            val localVideoTrack = LocalVideoTrack.create(
                this.applicationContext,
                enabled,
                TwilioProgrammableVideoPlugin.cameraCapturer!!,
                captureVideoFormat,
                name
            ) ?: return result.error(
                "INIT_ERROR",
                "Unable to create local video track with name $name",
                null
            )

            TwilioProgrammableVideoPlugin.localVideoTracks[name]?.release()
            TwilioProgrammableVideoPlugin.localVideoTracks[name] = localVideoTrack
        }
        result.success(null)
    }

    private fun localVideoTrackEnable(call: MethodCall, result: MethodChannel.Result) {
        val localVideoTrackName = call.argument<String>("name")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("name"), null)
        val localVideoTrackEnable = call.argument<Boolean>("enable")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("enable"), null)

        debug("localVideoTrackEnable => called for $localVideoTrackName, enable=$localVideoTrackEnable")

        val localVideoTrack = getLocalParticipant()?.localVideoTracks?.firstOrNull { it.trackName == localVideoTrackName }
        if (localVideoTrack != null) {
            localVideoTrack.localVideoTrack.enable(localVideoTrackEnable)
            return result.success(null)
        }
        return result.error("NOT_FOUND", "No LocalVideoTrack found with the name '$localVideoTrackName'", null)
    }

    private fun localVideoTrackPublish(call: MethodCall, result: MethodChannel.Result) {
        val localVideoTrackName = call.argument<String>("name")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("name"), null)

        debug("localVideoTrackPublish => called for $localVideoTrackName")

        val localVideoTrack = TwilioProgrammableVideoPlugin.localVideoTracks[localVideoTrackName]
                ?: return result.error("NOT_FOUND", "No LocalVideoTrack found with the name '$localVideoTrackName'", null)

        // Without a LocalParticipant there is nothing to publish to. Safe-calling
        // through and still dropping the track from the map would report success while
        // publishing nothing, and would leave the track unreachable: the later
        // release() looks it up by name, fails with NOT_FOUND and the camera stays
        // held. localVideoTrackUnpublish already answers NOT_FOUND in this situation.
        val localParticipant = getLocalParticipant()
                ?: return result.error("NOT_FOUND", "No LocalParticipant found. Connect to a Room before publishing '$localVideoTrackName'", null)

        localParticipant.publishTrack(localVideoTrack)

        TwilioProgrammableVideoPlugin.localVideoTracks -= localVideoTrackName

        return result.success(null)
    }

    private fun localVideoTrackUnpublish(call: MethodCall, result: MethodChannel.Result) {
        val localVideoTrackName = call.argument<String>("name")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("name"), null)

        debug("localVideoTrackUnpublish => called for $localVideoTrackName")

        val localVideoTrack = getLocalParticipant()
                ?.localVideoTracks
                ?.firstOrNull { it.trackName == localVideoTrackName }
                ?.localVideoTrack
                ?: return result.error("NOT_FOUND", "No LocalVideoTrack found with the name '$localVideoTrackName'", null)

        getLocalParticipant()?.unpublishTrack(localVideoTrack)

        TwilioProgrammableVideoPlugin.localVideoTracks[localVideoTrackName] = localVideoTrack

        return result.success(null)
    }

    private fun localVideoTrackRelease(call: MethodCall, result: MethodChannel.Result) {
        val localVideoTrackName = call.argument<String>("name")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("name"), null)

        debug("localVideoTrackRelease => called for $localVideoTrackName")

        val preview = TwilioProgrammableVideoPlugin.localVideoTracks[localVideoTrackName]
        if (preview != null) {
            preview.release()
            TwilioProgrammableVideoPlugin.localVideoTracks -= localVideoTrackName
            return result.success(null)
        }

        return result.error("NOT_FOUND", "No LocalVideoTrack found with the name '$localVideoTrackName'", null)
    }

    private fun localAudioTrackEnable(call: MethodCall, result: MethodChannel.Result) {
        val localAudioTrackName = call.argument<String>("name")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("name"), null)
        val localAudioTrackEnable = call.argument<Boolean>("enable")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("enable"), null)

        debug("localAudioTrackEnable => called for $localAudioTrackName, enable=$localAudioTrackEnable")

        val localAudioTrack = TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.localParticipant?.localAudioTracks?.firstOrNull { it.trackName == localAudioTrackName }
        if (localAudioTrack != null) {
            localAudioTrack.localAudioTrack.enable(localAudioTrackEnable)
            return result.success(null)
        }
        return result.error("NOT_FOUND", "No LocalAudioTrack found with the name '$localAudioTrackName'", null)
    }

    private fun localDataTrackSendString(call: MethodCall, result: MethodChannel.Result) {
        val localDataTrackName = call.argument<String>("name")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("name"), null)
        val localDataTrackMessage = call.argument<String>("message")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("message"), null)

        debug("localDataTrackSendString => called for $localDataTrackName")

        val localDataTrack = TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.localParticipant?.localDataTracks?.firstOrNull { it.trackName == localDataTrackName }
                ?: return result.error("NOT_FOUND", "No LocalDataTrack found with the name '$localDataTrackName'", null)

        localDataTrack.localDataTrack.send(localDataTrackMessage)
        return result.success(null)
    }

    private fun localDataTrackSendByteBuffer(call: MethodCall, result: MethodChannel.Result) {
        val localDataTrackName = call.argument<String>("name")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("name"), null)
        val localDataTrackMessage = call.argument<ByteArray>("message")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("message"), null)

        debug("localDataTrackSendByteBuffer => called for $localDataTrackName")

        val localDataTrack = TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.localParticipant?.localDataTracks?.firstOrNull { it.trackName == localDataTrackName }
                ?: return result.error("NOT_FOUND", "No LocalDataTrack found with the name '$localDataTrackName'", null)

        localDataTrack.localDataTrack.send(ByteBuffer.wrap(localDataTrackMessage))
        return result.success(null)
    }

    private fun remoteAudioTrackEnable(call: MethodCall, result: MethodChannel.Result) {
        val remoteAudioTrackSid = call.argument<String>("sid")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("sid"), null)
        val enable = call.argument<Boolean>("enable")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("enable"), null)
        debug("remoteAudioTrackEnable => sid: $remoteAudioTrackSid enable: $enable")
        val remoteAudioTrack = getRemoteAudioTrack(remoteAudioTrackSid)
                ?: return result.error("NOT_FOUND", "No RemoteAudioTrack found with sid $remoteAudioTrackSid", null)

        remoteAudioTrack.remoteAudioTrack?.enablePlayback(enable)
        return result.success(null)
    }

    private fun isRemoteAudioTrackPlaybackEnabled(call: MethodCall, result: MethodChannel.Result) {
        val remoteAudioTrackSid = call.argument<String>("sid")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("sid"), null)
        debug("isRemoteAudioTrackPlaybackEnabled => sid: $remoteAudioTrackSid")
        val remoteAudioTrack = getRemoteAudioTrack(remoteAudioTrackSid)
                ?: return result.error("NOT_FOUND", "No RemoteAudioTrack found with sid $remoteAudioTrackSid", null)

        return result.success(remoteAudioTrack.remoteAudioTrack?.isPlaybackEnabled)
    }

    private fun getRemoteAudioTrack(sid: String): RemoteAudioTrackPublication? {
        val remoteParticipants = TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.remoteParticipants
                ?: return null

        var remoteAudioTrack: RemoteAudioTrackPublication?
        for (remoteParticipant in remoteParticipants) {
            remoteAudioTrack = remoteParticipant.remoteAudioTracks.firstOrNull { it.trackSid == sid }
            if (remoteAudioTrack != null) return remoteAudioTrack
        }
        return null
    }

    private fun setAudioSettings(call: MethodCall, result: MethodChannel.Result) {
        val speakerphoneEnabled = call.argument<Boolean>("speakerphoneEnabled")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("speakerphoneEnabled"), null)
        val bluetoothPreferred = call.argument<Boolean>("bluetoothPreferred")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("bluetoothPreferred"), null)

        audioSettings.speakerEnabled = speakerphoneEnabled
        audioSettings.bluetoothPreferred = bluetoothPreferred

        applyAudioSettings()

        result.success(null)
    }

    private fun getAudioSettings(call: MethodCall, result: MethodChannel.Result) {
        val audioSettingsMap =
                mapOf(
                        "speakerphoneEnabled" to audioSettings.speakerEnabled,
                        "bluetoothPreferred" to audioSettings.bluetoothPreferred
                )
        result.success(audioSettingsMap)
    }

    private fun disableAudioSettings(call: MethodCall, result: MethodChannel.Result) {
        // Stopping the router releases the route as well as the device scanners, which is
        // what lets another app's music resume: below API 31 it stops SCO, above it
        // clears the communication device.
        //
        // Unconditional, including mid-call. Releasing the route is the point of this
        // call, not a side effect: without it a headset stays in call mode and another
        // app's audio cannot resume.
        //
        // Deferring the release until the Room ended was tried and is worse. It needs a
        // pending-teardown flag, and every path that ends a call without an app-initiated
        // `disconnect` — a Room the server ends, which leaves `isConnected()` true
        // because `RoomListener.onDisconnected` does not clear the Room — then strands
        // that flag, so the teardown the app asked for never happens and fires later in an
        // unrelated call instead. The case it protected is narrow either way: an app that
        // calls this mid-call and keeps talking. This plugin's own example calls it on the
        // line above `Room.disconnect()`, where the route is about to go anyway.
        audioRouter.stop()
        audioSettings.reset()
        result.success(null)
    }

    /**
     * Whether the plugin is currently driving the audio system, and so whether the route
     * needs to stay engaged.
     */
    private fun isUsingAudioSystem(): Boolean {
        return TwilioProgrammableVideoPlugin.isConnected() ||
                TwilioProgrammableVideoPlugin.audioNotificationListener.anyAudioPlayersActive()
    }

    /**
     * Hands the current settings to the router, and engages the route if the plugin is
     * using the audio system.
     *
     * Only ever escalates. An activated route holds the Bluetooth link the way audio
     * focus holds playback, so it is engaged lazily — but releasing it is a teardown
     * decision that belongs to `disconnect`, `disableAudioSettings` and the audio
     * player listener. Deciding it here as well would drop the route whenever the app
     * calls `setAudioSettings` during the window between `connect` and the Room
     * actually being assigned, where `isConnected()` is still false.
     */
    internal fun applyAudioSettings() {
        debug("applyAudioSettings")
        audioRouter.applySettings(audioSettings)

        if (isUsingAudioSystem()) {
            audioRouter.activate()
        } else {
            // Recorded, not routed — and said out loud, because the two method channel calls
            // that reach here, `setAudioSettings` and `setSpeakerphoneOn`, answer success
            // either way. Without this line "the toggle did nothing" is indistinguishable
            // from a routing call the audio stack rejected.
            //
            // Not a gap to close by activating anyway: an activated route holds the
            // Bluetooth link the way audio focus holds playback, so engaging it here would
            // stop another app's music from resuming for as long as the app leaves settings
            // applied. The settings are re-applied from `connect` and from the audio player
            // listener, so nothing is lost by waiting. iOS reaches the same place from the
            // other direction — `setSpeakerphoneOn` there sets the AVAudioSession category
            // and the route follows only once the Room's audio device is running.
            debug("applyAudioSettings => stored but not routed: no Room is connected and no " +
                    "audio player registered with the plugin is active. The settings apply " +
                    "when one of those starts.")
        }
    }

    private fun setSpeakerphoneOn(call: MethodCall, result: MethodChannel.Result) {
        val on = call.argument<Boolean>("on")
                ?: return result.error("MISSING_PARAMS", missingParameterMessage("on"), null)

        audioSettings.speakerEnabled = on
        applyAudioSettings()

        return result.success(audioSettings.speakerEnabled)
    }

    /**
     * Reports whether the speaker is the device the current settings resolve to.
     *
     * Read from the router's selection rather than `AudioManager.isSpeakerphoneOn`,
     * because on API 31+ the route is set with `setCommunicationDevice` and that flag no
     * longer decides it. The AudioManager read stays as the fallback for before the
     * router has picked a device, which is what an app that never called
     * `setAudioSettings` used to get.
     *
     * Deliberately the *selection* and not the live route. A selection exists for as long
     * as device discovery is running, so this survives `disconnect` and still answers
     * "the speaker is where audio would go" — which is what an app rendering a speaker
     * toggle wants, since asking again after a call should not flip the button off. It
     * does mean this is not a reading of the hardware state: between calls the route is
     * released and playback follows the system default, whatever this reports.
     *
     * `disableAudioSettings` ends discovery, and from there this falls back to the
     * AudioManager flag until the next `setAudioSettings` — the app has said it no longer
     * wants the plugin choosing an output, so there is no selection left to report.
     */
    private fun getSpeakerphoneOn(result: MethodChannel.Result) {
        return result.success(audioRouter.isSpeakerphoneSelected ?: audioManager.isSpeakerphoneOn)
    }

    /*
     * Automatically returns true on SDKs lower than 23 as there is officially no method of querying
     * available audio devices on earlier SDKs. See: https://github.com/google/oboe/issues/67
     */
    private fun deviceHasReceiver(result: MethodChannel.Result) {
        val hasReceiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            true
        }
        debug("deviceHasReceiver => called $hasReceiver")
        return result.success(hasReceiver)
    }

    private fun getStats(result: MethodChannel.Result) {
        // Only a connected Room is asked for stats; everything else resolves to null,
        // which Dart maps onto a null StatsReport list. The result has to be fulfilled
        // explicitly on those paths — leaving it to a safe-call on the Room, or to a
        // callback the SDK never invokes, leaves the Dart future pending forever, and a
        // method channel has no timeout to recover from that.
        //
        //  - Outside a Room there is nothing to report.
        //  - `Room.getStats` drops the listener without invoking it while the Room is
        //    DISCONNECTED. That state is reachable because `RoomListener.onDisconnected`
        //    does not clear the reference — only an app-initiated `disconnect()` does —
        //    so a poll after the server ends the call used to hang here.
        //  - CONNECTING and RECONNECTING do reach the SDK, which queues the listener and
        //    pops one entry per report the core delivers. A request the core declines in
        //    those states is never popped, so it both hangs and shifts every later poll
        //    onto the previous poll's result until the Room is released. Reports taken
        //    mid-outage are worth little anyway, so they resolve to null too — matching
        //    the iOS handler, which applies the same rule for the same reason.
        //
        // A Room that disconnects after a request has been accepted is a residual and
        // much narrower window; the SDK flushes those listeners with an empty list when
        // it releases the Room, so they resolve rather than hang.
        val room = TwilioProgrammableVideoPlugin.roomListenerOrNull?.room
        if (room == null || room.state != Room.State.CONNECTED) {
            return result.success(null)
        }

        room.getStats {
            result.success(StatsMapper.statsReportsToMap(it))
        }
    }

    private fun disconnect(call: MethodCall, result: MethodChannel.Result) {
        debug("disconnect => called")
        TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.localParticipant?.localVideoTracks?.forEach { it.localVideoTrack.release() }
        TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.localParticipant?.localAudioTracks?.forEach { it.localAudioTrack.release() }
        TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.localParticipant?.localDataTracks?.forEach { it.localDataTrack.release() }
        TwilioProgrammableVideoPlugin.roomListenerOrNull?.room?.disconnect()
        TwilioProgrammableVideoPlugin.roomListenerOrNull?.room = null
        debug("disconnect => audioPlayers active: ${TwilioProgrammableVideoPlugin.audioNotificationListener.anyAudioPlayersActive()}")
        if (!TwilioProgrammableVideoPlugin.audioNotificationListener.anyAudioPlayersActive()) {
            // Order matters and both restore an audio mode: the router puts back what it
            // captured on activate, then setAudioFocus puts back what the plugin captured
            // before the call. Deactivating second would leave the router's value on top.
            audioRouter.deactivate()
            setAudioFocus(false)
        }
        result.success(true)
    }

    private fun connect(call: MethodCall, result: MethodChannel.Result) {
        debug("connect => called, Build.MODEL: '${Build.MODEL}'")
        // WebRtcAudioUtils removed in SDK 7.0+
        // Hardware AEC configuration now handled internally by Twilio SDK
        // if (TwilioProgrammableVideoPlugin.HARDWARE_AEC_BLACKLIST.contains(Build.MODEL) && !WebRtcAudioUtils.useWebRtcBasedAcousticEchoCanceler()) {
        //     debug("connect => setWebRtcBasedAcousticEchoCanceler: true")
        //     WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true)
        // }
        val optionsObj = call.argument<Map<String, Any>>("connectOptions")
                ?: return result.error("MISSING_PARAMS", "Missing 'connectOptions' parameter", null)

        val obtainedFocus = setAudioFocus(true)
        if (!obtainedFocus) {
            debug("connect => Failed to obtain audio focus, aborting connect.")
            return result.error("ACTIVE_CALL", "Detected an active call that is using the audio system.", null)
        }

        try {
            val optionsBuilder = ConnectOptions.Builder(optionsObj["accessToken"] as String)

            // Set the room name if it has been passed.
            if (optionsObj["roomName"] != null) {
                debug("connect => setting roomName to '${optionsObj["roomName"]}'")
                optionsBuilder.roomName(optionsObj["roomName"] as String)
            }

            // Set the region if it has been passed.
            if (optionsObj["region"] != null) {
                debug("connect => setting region to '${optionsObj["region"]}'")
                optionsBuilder.region(optionsObj["region"] as String)
            }

            // Set the preferred audio codecs if it has been passed.
            if (optionsObj["preferredAudioCodecs"] != null) {
                val preferredAudioCodecs = optionsObj["preferredAudioCodecs"] as Map<*, *>

                val audioCodecs = ArrayList<AudioCodec>()
                for ((audioCodec) in preferredAudioCodecs) {
                    val codec: AudioCodec = when (audioCodec) {
                        // IsacCodec removed in SDK 7.7.0+ - ISAC codec no longer supported in WebRTC
                        // "isac" -> IsacCodec()
                        OpusCodec.NAME -> OpusCodec()
                        PcmaCodec.NAME -> PcmaCodec()
                        PcmuCodec.NAME -> PcmuCodec()
                        G722Codec.NAME -> G722Codec()
                        else -> OpusCodec() // "isac" lands here too, along with anything unrecognised
                    }

                    // "isac" and "opus" both map onto opus now, and a preference list holding
                    // the same entry twice is meaningless, so collapse it — the iOS handler
                    // does the same. Dart sends this as a map keyed by codec name, so exact
                    // duplicates never arrive and that pair is the only one that can reach
                    // here twice; both entries are equal, so keeping either is correct.
                    if (audioCodecs.none { it.name == codec.name }) {
                        audioCodecs.add(codec)
                    }
                }
                debug("connect => setting audioCodecs to '${audioCodecs.joinToString(", ")}'")
                optionsBuilder.preferAudioCodecs(audioCodecs)
            }

            // Set the preferred video codecs if it has been passed.
            if (optionsObj["preferredVideoCodecs"] != null) {
                val preferredVideoCodecs = optionsObj["preferredVideoCodecs"] as Map<*, *>

                val videoCodecs = ArrayList<VideoCodec>()
                for ((videoCodec) in preferredVideoCodecs) {
                    val codec: VideoCodec = when (videoCodec) {
                        Vp8Codec.NAME -> Vp8Codec() // TODO(WLFN): It has an optional parameter, need to figure out for what: https://github.com/twilio/video-quickstart-android/blob/master/quickstartKotlin/src/main/java/com/twilio/video/quickstart/kotlin/VideoActivity.kt#L106
                        Vp9Codec.NAME -> Vp9Codec()
                        H264Codec.NAME -> H264Codec()
                        else -> Vp8Codec()
                    }

                    // Same collapse as the audio list above: an unrecognised name falls
                    // through to VP8, so asking for it alongside "VP8" would list VP8 twice.
                    if (videoCodecs.none { it.name == codec.name }) {
                        videoCodecs.add(codec)
                    }
                }
                debug("connect => setting videoCodecs to '${videoCodecs.joinToString(", ")}'")
                optionsBuilder.preferVideoCodecs(videoCodecs)
            }

            // Set the local audio tracks if it has been passed.
            if (optionsObj["audioTracks"] != null) {
                val audioTrackOptions = optionsObj["audioTracks"] as Map<*, *>

                val audioTracks = ArrayList<LocalAudioTrack?>()
                for ((audioTrack) in audioTrackOptions) {
                    audioTrack as Map<*, *> // Ensure right type.
                    audioTracks.add(LocalAudioTrack.create(this.applicationContext, audioTrack["enable"] as Boolean, audioTrack["name"] as String))
                }
                debug("connect => setting audioTracks to '${audioTracks.joinToString(", ")}'")
                optionsBuilder.audioTracks(audioTracks)
            }

            // Set the local data tracks if it has been passed.
            if (optionsObj["dataTracks"] != null) {
                val dataTrackMap = optionsObj["dataTracks"] as Map<*, *>

                val dataTracks = ArrayList<LocalDataTrack?>()
                for ((dataTrack) in dataTrackMap) {
                    dataTrack as Map<*, *> // Ensure right type.
                    if (dataTrack["dataTrackOptions"] != null) {
                        val dataTrackOptionsMap = dataTrack["dataTrackOptions"] as Map<*, *>

                        val dataTrackOptionsBuilder = DataTrackOptions.Builder()
                        if (dataTrackOptionsMap["ordered"] != null) {
                            dataTrackOptionsBuilder.ordered(dataTrackOptionsMap["ordered"] as Boolean)
                        }
                        if (dataTrackOptionsMap["maxPacketLifeTime"] != null) {
                            dataTrackOptionsBuilder.maxPacketLifeTime(dataTrackOptionsMap["maxPacketLifeTime"] as Int)
                        }
                        if (dataTrackOptionsMap["maxRetransmits"] != null) {
                            dataTrackOptionsBuilder.maxRetransmits(dataTrackOptionsMap["maxRetransmits"] as Int)
                        }
                        if (dataTrackOptionsMap["name"] != null) {
                            dataTrackOptionsBuilder.name(dataTrackOptionsMap["name"] as String)
                        }
                        dataTracks.add(LocalDataTrack.create(this.applicationContext, dataTrackOptionsBuilder.build()))
                    } else {
                        dataTracks.add(LocalDataTrack.create(this.applicationContext))
                    }
                }
                debug("connect => setting dataTracks to '${dataTracks.joinToString(", ")}'")
                optionsBuilder.dataTracks(dataTracks)
            }

            debug("connect => setting enableNetworkQuality to '${optionsObj["enableNetworkQuality"]}'")
            optionsBuilder.enableNetworkQuality(optionsObj["enableNetworkQuality"] as Boolean)

            if (optionsObj["networkQualityConfiguration"] != null) {
                val networkQualityConfigurationMap = optionsObj["networkQualityConfiguration"] as Map<*, *>
                val local: NetworkQualityVerbosity = getNetworkQualityVerbosity(networkQualityConfigurationMap["local"] as String)
                val remote: NetworkQualityVerbosity = getNetworkQualityVerbosity(networkQualityConfigurationMap["remote"] as String)
                optionsBuilder.networkQualityConfiguration(NetworkQualityConfiguration(local, remote))
            }

            // Set the local video tracks if it has been passed.
            if (optionsObj["videoTracks"] != null) {
                val videoTrackOptions = optionsObj["videoTracks"] as Map<*, *>

                val videoTracks = ArrayList<LocalVideoTrack?>()
                for ((videoTrack) in videoTrackOptions) {
                    videoTrack as Map<*, *> // Ensure right type.
                    val videoCapturerMap = videoTrack["videoCapturer"] as Map<*, *>
                    val name = videoTrack["name"] as? String ?: ""

                    if ((videoCapturerMap["type"] as String) == "CameraCapturer") {
                        VideoCapturerHandler.initializeCapturer(videoCapturerMap, result)
                    } else {
                        return result.error("INIT_ERROR", "VideoCapturer type ${videoCapturerMap["type"]} not yet supported.", null)
                    }

                    if (TwilioProgrammableVideoPlugin.cameraCapturer != null) {
                        if (name in TwilioProgrammableVideoPlugin.localVideoTracks) {
                            videoTracks.add(TwilioProgrammableVideoPlugin.localVideoTracks[name])
                            TwilioProgrammableVideoPlugin.localVideoTracks -= name
                        } else {
                            videoTracks.add(LocalVideoTrack.create(this.applicationContext, videoTrack["enable"] as Boolean, TwilioProgrammableVideoPlugin.cameraCapturer!!, captureVideoFormat, videoTrack["name"] as String))
                        }
                    }
                }
                debug("connect => setting videoTracks to '${videoTracks.joinToString(", ")}'")

                optionsBuilder.videoTracks(videoTracks)
            }

            optionsBuilder.enableDominantSpeaker(if (optionsObj["enableDominantSpeaker"] != null) optionsObj["enableDominantSpeaker"] as Boolean else false)
            optionsBuilder.enableAutomaticSubscription(if (optionsObj["enableAutomaticSubscription"] != null) optionsObj["enableAutomaticSubscription"] as Boolean else true)

            applyAudioSettings()
            // `isConnected()` is still false here — the RoomListener is assigned on the
            // next line and its Room only later, when the room event channel is listened
            // to — so applyAudioSettings cannot tell that this call is about to use the
            // audio system. Audio focus was already taken above, which settles it.
            audioRouter.activate()

            val roomId = 1 // Future preparation, for when we might want to support multiple rooms.
            TwilioProgrammableVideoPlugin.roomListener = RoomListener(roomId, optionsBuilder.build())
            result.success(roomId)
        } catch (e: Exception) {
            // Both the route and audio focus were taken above, on the way to a Room that
            // now does not exist, and nothing else releases them: `isConnected()` stays
            // false so neither `applyAudioSettings` nor the audio player listener will,
            // and a Dart connect-error handler has no reason to call
            // `disableAudioSettings`. Left engaged, the route keeps a headset in call mode
            // and stops another app's music from resuming for the rest of the session.
            //
            // Skipped whenever anything else is still using the audio system — a ringtone
            // that is still playing, or an earlier Room this failed call was not replacing.
            // `connect` on top of a live Room is reachable through app reconnect logic, and
            // tearing the route down there would send the call that is still running to the
            // wrong output.
            if (!isUsingAudioSystem()) {
                debug("connect => failed, releasing the route and audio focus")
                audioRouter.deactivate()
                setAudioFocus(false)
            }
            result.error("INIT_ERROR", e.toString(), e)
        }
    }

    private fun getNetworkQualityVerbosity(verbosity: String): NetworkQualityVerbosity {
        return when (verbosity) {
            "NETWORK_QUALITY_VERBOSITY_NONE" -> NetworkQualityVerbosity.NETWORK_QUALITY_VERBOSITY_NONE
            "NETWORK_QUALITY_VERBOSITY_MINIMAL" -> NetworkQualityVerbosity.NETWORK_QUALITY_VERBOSITY_MINIMAL
            else -> NetworkQualityVerbosity.NETWORK_QUALITY_VERBOSITY_NONE
        }
    }

    private fun debug(call: MethodCall, result: MethodChannel.Result) {
        val enableNative = call.argument<Boolean>("native")
                ?: return result.error("MISSING_PARAMS", "Missing 'native' parameter", null)

        val enableAudio = call.argument<Boolean>("audio")
                ?: return result.error("MISSING_PARAMS", "Missing 'audio' parameter", null)

        TwilioProgrammableVideoPlugin.nativeDebug = enableNative
        TwilioProgrammableVideoPlugin.audioDebug = enableAudio
        result.success(enableNative)
    }

    internal fun setAudioFocus(focus: Boolean): Boolean {
        if (focus) {
            // Snapshotted on the first take only, all three together. A second take before
            // the matching release is reachable — `connect` on top of a ringtone the audio
            // player is still playing, which took focus itself — and by then these read
            // back the values the first take installed: an unmuted microphone and
            // STREAM_VOICE_CALL. Re-reading them would discard what the app had set and
            // leave the microphone unmuted for good once the call ended. The mode was
            // always guarded this way; the other two were not.
            if (previousAudioMode == null) {
                previousAudioMode = audioManager.mode
                previousMicrophoneMute = audioManager.isMicrophoneMute
                val volumeControlStream = this.activity?.volumeControlStream
                if (volumeControlStream != null) {
                    previousVolumeControlStream = volumeControlStream
                }
            }
            var requestResult: Int

            // Request audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener {
                        // Occasionally observe during tests that just after requesting AudioFocus we receive a AudioFocus LOSS event
                        // When this occurred during tests, Spotify audio continues rather than pausing while our audio begins.
                        // We could look at introducing retry logic when this occurs, but this can also be solved by the user simply
                        // pausing playback from external apps if they encounter the issue.
                        //
                        // Per https://developer.android.com/guide/topics/media-apps/audio-focus AudioFocus is meant to be cooperative
                        // and is not enforced by the OS.
                        debug("onAudioFocusChange => focusChange: $it")
                    }
                    .build()
                debug("setAudioFocus =>" +
                    "\n\tfocus: $focus," +
                    "\n\taudioFocusRequest: $audioFocusRequest" +
                    "\n\tpreviousAudioMode: $previousAudioMode")
                requestResult = audioManager.requestAudioFocus(audioFocusRequest!!)
            } else {
                requestResult = audioManager.requestAudioFocus(
                    null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
            /*
             * Use MODE_IN_COMMUNICATION as the default audio mode. It is required
             * to be in this mode when playout and/or recording starts for the best
             * possible VoIP performance. Some devices have difficulties with
             * speaker mode if this is not set.
             */
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            /*
             * Always disable microphone mute during a WebRTC call.
             */

            audioManager.isMicrophoneMute = false
            this.activity?.volumeControlStream = AudioManager.STREAM_VOICE_CALL
            val requestGranted = requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            debug("requestAudioFocus => requestGranted: $requestGranted")
            return requestGranted
        } else {
            debug("setAudioFocus =>" +
                    "\tfocus: $focus," +
                    "\taudioFocusRequest: $audioFocusRequest" +
                    "\tpreviousAudioMode: $previousAudioMode")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocus(null)
            } else if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
            }
            // No `isSpeakerphoneOn = false` here any more. It existed to undo this
            // class's own speakerphone write, and the router now owns that flag: every
            // caller deactivates it immediately before this, which restores the value
            // from before the call, and forcing it off here would overwrite that.
            //
            // The cost is that this is no longer a backstop. If the deactivate was
            // rejected by the audio stack — AudioRouter logs it and carries on rather
            // than failing the disconnect — the speakerphone flag keeps whatever the
            // call left it at.
            if (previousAudioMode != null) {
                audioManager.mode = previousAudioMode!!
                previousAudioMode = null
            }
            audioManager.isMicrophoneMute = previousMicrophoneMute
            this.activity?.volumeControlStream = previousVolumeControlStream
            return true
        }
    }

    fun sendCameraEvent(name: String, data: Any, e: java.lang.Exception? = null) {
        sendEvent(name, data, e)
    }

    private fun missingParameterMessage(parameterName: String): String {
        return "The parameter '$parameterName' was not given"
    }

    internal fun debug(msg: String) {
        TwilioProgrammableVideoPlugin.debug("$TAG::$msg")
    }
}
