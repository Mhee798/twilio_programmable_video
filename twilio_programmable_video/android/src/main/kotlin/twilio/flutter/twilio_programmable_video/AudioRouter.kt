package twilio.flutter.twilio_programmable_video

import android.content.Context
import android.os.Build
import android.os.Looper
import com.twilio.audioswitch.AbstractAudioSwitch
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch
import com.twilio.audioswitch.CommDeviceAudioSwitch
import com.twilio.audioswitch.LegacyAudioSwitch

/**
 * Turns [AudioSettings] into the device-priority order AudioSwitch understands, and
 * owns the switch itself.
 *
 * The plugin used to route by hand: write `AudioManager.isSpeakerphoneOn`, and call
 * `startBluetoothSco()` when the speaker was off. Neither half survives modern
 * Android. `startBluetoothSco` is deprecated from API 31 and no longer moves playout
 * — measured on Android 16 the framework reports SCO_AUDIO_STATE_CONNECTED for this
 * app's uid while both the headset and the speaker stay silent — and the SCO call was
 * gated on the speaker being off, so an app asking for `speakerphoneEnabled: true`
 * never reached it at all. AudioSwitch replaces both with one device list and one
 * selection, and on API 31+ routes through `AudioManager.setCommunicationDevice`,
 * which is the only call that still moves the route there.
 */
internal class AudioRouter(private val applicationContext: Context) {
    private val TAG = "AudioRouter"

    private var audioSwitch: AbstractAudioSwitch? = null

    /**
     * True only once [AbstractAudioSwitch.start] has returned without throwing.
     *
     * Distinct from `audioSwitch != null`, which is set just before that call — see
     * [startInternal] — and which also stays set for a switch whose stop() was rejected.
     */
    private var started = false

    /** The last order applied to the current switch, so a redundant re-apply is skipped. */
    private var appliedOrder: List<Class<out AudioDevice>>? = null

    /**
     * Runs [action] on the main looper, inline if already there.
     *
     * Every AudioSwitch entry point has to be called from one thread and it offers no
     * synchronisation of its own. Method channel calls already arrive on the main
     * looper; the audio player listener is handed out to other plugins
     * (`TwilioProgrammableVideoPlugin.getAudioPlayerEventListener`) and can arrive on
     * any thread.
     *
     * Callers that also touch state outside this class have to hop *once, around the
     * whole sequence* rather than relying on this per-call — mixing an inline call with
     * a posted one reorders them. `AudioNotificationListener.audioPlayerEventListener`
     * is the case in point and marshals itself.
     */
    private fun onMainThread(action: () -> Unit) = runOnMainThread(TAG, action)

    /**
     * Runs [action], swallowing a RuntimeException so a rejected routing call cannot
     * end the call or take the process down.
     *
     * OEM audio stacks are known to reject these with IllegalStateException, and API
     * 31+ adds SecurityException wherever BLUETOOTH_CONNECT is involved. Catching only
     * those two was tried in the hand-rolled code this replaces and still let unrelated
     * RuntimeExceptions through from inside the library.
     *
     * **This covers only calls this class makes into AudioSwitch.** AudioSwitch also
     * reaches AudioManager from its own callbacks — a scanner's AudioDeviceCallback
     * arriving on the main looper re-enters `selectAudioDevice` and, while activated,
     * `onActivate`, which on API 23-30 is `startBluetoothSco()`. A throw on that stack
     * has no frame of ours to catch it. It cannot be closed from here: AudioSwitch's
     * every constructor that accepts a Handler or a Scanner is `internal`, so neither a
     * subclass nor an injected collaborator is available, and installing a default
     * uncaught-exception handler is not something a plugin may do to its host. The
     * residual window is recorded in the CHANGELOG rather than hidden.
     *
     * Returns whether [action] completed, so callers can avoid recording state for a
     * call that did not happen.
     */
    private inline fun guarded(what: String, action: () -> Unit): Boolean {
        return try {
            action()
            true
        } catch (e: RuntimeException) {
            debug("$what => failed: $e")
            false
        }
    }

    /**
     * Starts device discovery without touching the route.
     *
     * Discovery is deliberately separate from [activate]: an activated route holds the
     * Bluetooth link the way audio focus holds playback, so engaging it while the
     * plugin is not using the audio system would stop another app's music from
     * resuming. Both scanners report the devices that are already connected as soon as
     * they start, so a headset paired before the call is found without waiting for a
     * connection broadcast — the case the old BroadcastReceiver could not cover,
     * because ACTION_CONNECTION_STATE_CHANGED is not sticky.
     */
    fun start() = onMainThread { startInternal() }

    /**
     * The body of [start], callable from the other entry points without a second
     * main-thread hop — they are already on it.
     */
    private fun startInternal() {
        if (started) return

        // Reaching here with a switch still held means a previous release could not stop
        // it. Retried once — the rejection may have been transient — and then given up
        // on, so a switch that can never be stopped does not block routing forever.
        audioSwitch?.let { releaseSwitch(it) }

        val switch = createAudioSwitch()
        debug("start => ${switch.javaClass.simpleName}")

        // Published *before* start(), because on API 31+ CommunicationDeviceScanner.start
        // reports the already-connected devices synchronously from inside that call and
        // the listener below drops anything arriving from a switch that is not the current
        // one. On API 23-30 AudioDeviceScanner only registers an AudioDeviceCallback and
        // the initial list arrives later on the looper, so there the ordering is merely
        // harmless rather than load-bearing — and the first applySettings/activate run
        // against an empty device set, with routing following when that callback lands.
        audioSwitch = switch
        appliedOrder = null

        val didStart = guarded("start") {
            switch.start { devices, selected ->
                // A switch whose stop() was rejected keeps its scanners registered and
                // goes on calling this. Two switches diffing against one shared
                // knownHeadsets would make a single headset produce alternating
                // add/remove events for the rest of the process.
                if (audioSwitch !== switch) {
                    debug("onAudioDevicesChanged => ignored, switch is no longer current")
                    return@start
                }
                debug("onAudioDevicesChanged => selected: $selected, available: $devices")
                TwilioProgrammableVideoPlugin.audioNotificationListener.onAudioDevicesChanged(devices)
            }
        }

        if (!didStart) {
            // No stop() here: AbstractAudioSwitch only reaches STARTED once its scanner's
            // start() has returned, so a switch that threw on the way is still STOPPED and
            // stop() is a documented no-op on it. Whatever the scanner had already
            // registered with AudioManager before the throw therefore stays registered —
            // nothing reachable from here can undo it, and each retry adds another. It is
            // recorded in the CHANGELOG with the rest of the audio-stack-rejection window.
            audioSwitch = null
            // The scanner may have announced devices before it threw, and discovery is now
            // dead, so those have to be withdrawn or the Dart layer keeps an entry that
            // nothing will ever remove.
            TwilioProgrammableVideoPlugin.audioNotificationListener.announceDevicesUnavailable()
            return
        }
        started = true
    }

    /**
     * Applies [settings] as a device-priority order, starting discovery if it is not
     * running yet.
     *
     * Starting here as well as in [start] keeps the pre-existing behaviour that
     * `connect` routes audio even when the app never called `setAudioSettings`.
     */
    fun applySettings(settings: AudioSettings) = onMainThread {
        startInternal()
        if (!started) {
            debug("applySettings => switch did not start, order not applied")
            return@onMainThread
        }
        val order = preferredDeviceListFor(settings)
        if (order == appliedOrder) return@onMainThread

        debug("applySettings => speakerEnabled: ${settings.speakerEnabled}, " +
                "bluetoothPreferred: ${settings.bluetoothPreferred}, " +
                "order: ${order.map { it.simpleName }}")

        // Recorded only once the call has actually landed. Remembering an order that was
        // rejected would make every later call skip it as already applied, leaving the
        // switch on AudioSwitch's default preference for the rest of the session.
        if (guarded("applySettings") { audioSwitch?.setPreferredDeviceList(order) }) {
            appliedOrder = order
        }
    }

    /**
     * Routes audio to the highest-priority available device.
     *
     * Safe to call repeatedly: AudioSwitch re-applies the current selection rather
     * than stacking state, and re-selects by itself whenever the device list changes
     * while activated, so nothing else has to watch for a headset arriving mid-call.
     */
    fun activate() = onMainThread {
        startInternal()
        if (!started) {
            // activate() on a switch that never started throws IllegalStateException.
            debug("activate => switch did not start, nothing to route")
            return@onMainThread
        }
        guarded("activate") { audioSwitch?.activate() }
        debug("activate => selected: ${audioSwitch?.selectedAudioDevice}")
    }

    /**
     * Releases the route and restores the audio state AudioSwitch captured on
     * [activate].
     *
     * This is what lets another app's music resume after a call: below API 31 it stops
     * SCO, and on API 31+ it clears the communication device.
     */
    fun deactivate() = onMainThread {
        debug("deactivate")
        guarded("deactivate") { audioSwitch?.deactivate() }
    }

    /** Deactivates, if needed, stops device discovery and forgets the switch. */
    fun stop() = onMainThread {
        val switch = audioSwitch
        if (switch == null) {
            debug("stop => nothing to release")
            started = false
            appliedOrder = null
            // Still withdrawn: a start that threw after the scanner had announced devices
            // clears the switch but leaves the Dart layer holding them.
            TwilioProgrammableVideoPlugin.audioNotificationListener.announceDevicesUnavailable()
            return@onMainThread
        }
        debug("stop")
        releaseSwitch(switch)
    }

    /**
     * Deactivates and stops [switch], and clears the state that belonged to it.
     *
     * `deactivate()` is called separately rather than left to `stop()`: `stop()` on an
     * ACTIVATED switch deactivates first, and a throw there — `stopBluetoothSco` is the
     * call OEM stacks are known to reject — skips `closeListeners()` and leaves the
     * scanners registered. Deactivating first means the ordinary case reaches
     * `closeListeners()` through `stop()`'s already-STARTED branch, where nothing can
     * throw ahead of it.
     *
     * A switch whose `stop()` was rejected keeps the reference, so [startInternal] gets
     * one retry at it before building a replacement. That bounds the damage rather than
     * removing it: `deactivate()` leaves the state ACTIVATED when it throws, and an
     * ACTIVATED switch whose scanner is still registered goes on selecting and
     * re-routing — it will fight the replacement over `setCommunicationDevice`, not
     * merely leak a registration. Holding the reference indefinitely instead would stop
     * the plugin routing at all, which is worse; [startInternal]'s identity check at
     * least keeps such a switch from corrupting the events the Dart layer sees.
     */
    private fun releaseSwitch(switch: AbstractAudioSwitch) {
        guarded("release => deactivate") { switch.deactivate() }
        val didStop = guarded("release => stop") { switch.stop() }
        if (audioSwitch === switch) {
            // Kept on a failure so the next start retries it; see the KDoc.
            audioSwitch = if (didStop) null else switch
        }
        if (!didStop) {
            debug("release => stop was rejected; keeping the switch for one retry")
        }
        started = false
        appliedOrder = null
        // The device list this switch reported is no longer being maintained, so the
        // Dart layer is told those devices went away — see announceDevicesUnavailable
        // for why a silent reset is the worse of the two options.
        TwilioProgrammableVideoPlugin.audioNotificationListener.announceDevicesUnavailable()
    }

    /**
     * Whether the route resolves to the built-in speaker under the current settings and
     * device list, or null while no device has been selected.
     *
     * Read from AudioSwitch's selection rather than `AudioManager.isSpeakerphoneOn`,
     * which on API 31+ is not what decides the route. Note that a selection exists
     * whenever discovery is running, activated or not, so between calls this answers
     * "where the current settings would send audio" rather than "where audio is going
     * right now" — see `PluginHandler.getSpeakerphoneOn`, which is what exposes it.
     */
    val isSpeakerphoneSelected: Boolean?
        get() = audioSwitch?.selectedAudioDevice?.let { it is AudioDevice.Speakerphone }

    /**
     * Picks the AudioSwitch implementation that can actually route on this release.
     *
     * API 31+ needs [CommDeviceAudioSwitch]: `setCommunicationDevice` is the only call
     * that still moves playout. Below that, [AudioSwitch] discovers devices through
     * `AudioManager.getDevices` and routes with the speakerphone flag and SCO, which is
     * what those releases honour.
     *
     * Under API 23 neither is available and [LegacyAudioSwitch] is the only option. It
     * treats the speaker as selectable only once `isSpeakerphoneOn` is already true, so
     * `speakerphoneEnabled: true` falls through to the earpiece there. Left as is
     * rather than worked around: Flutter's Gradle plugin fails the build of any app
     * below minSdk 23, so this branch is unreachable for a current Flutter app and only
     * exists because the plugin still declares minSdk 21.
     */
    private fun createAudioSwitch(): AbstractAudioSwitch {
        val logging = TwilioProgrammableVideoPlugin.audioDebug
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                CommDeviceAudioSwitch(applicationContext, logging)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                AudioSwitch(applicationContext, logging)
            else ->
                LegacyAudioSwitch(applicationContext, logging)
        }.apply {
            // The plugin requests and abandons audio focus itself, in
            // PluginHandler.setAudioFocus. That call site cannot be handed over: it is
            // also how `connect` detects another app already holding the audio system
            // and answers ACTIVE_CALL, and AudioSwitch does not surface the request
            // result. Two owners would race over the audio mode, so AudioSwitch is left
            // to route and to cache/restore the mode around activate/deactivate.
            manageAudioFocus = false
        }
    }

    private fun debug(msg: String) {
        TwilioProgrammableVideoPlugin.debugAudio("$TAG::$msg")
    }
}

/**
 * Runs [action] on the main looper, inline when already there, and swallows a
 * RuntimeException from the posted path.
 *
 * Shared with [AudioNotificationListener], which has the same constraint for the same
 * reason. A posted action runs as a bare Runnable with no caller to catch anything it
 * throws, so it goes straight to the uncaught handler; [what] names the caller in the
 * log that replaces it.
 */
internal fun runOnMainThread(what: String, action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        action()
        return
    }
    TwilioProgrammableVideoPlugin.handler.post {
        try {
            action()
        } catch (e: RuntimeException) {
            TwilioProgrammableVideoPlugin.debugAudio("$what::runOnMainThread => failed: $e")
        }
    }
}

/**
 * Translates the two-flag Dart API onto AudioSwitch's device-priority order.
 *
 * The flags predate this plugin having a device list at all, and their historical
 * meaning is a pair of routing decisions rather than a preference order:
 * `bluetoothPreferred` meant "do not force the speaker while a headset is connected"
 * and `speakerphoneEnabled` meant "write isSpeakerphoneOn". Expressed as an order they
 * become one rule — `speakerphoneEnabled: true, bluetoothPreferred: true` reads as
 * "speaker, except when a headset is connected", which is what an app passing both
 * already expected and what the old code failed to deliver.
 *
 * Only the entries this function cares about are listed; AudioSwitch appends whatever
 * is missing from its own default order, so the result is always a full list. The
 * unlisted entry lands behind the ones named here, which is why a `bluetoothPreferred:
 * false` caller still gets Bluetooth as a last resort rather than silence when it is
 * the only device connected.
 *
 * Kept as a top-level function, out of [AudioRouter], so the JVM unit tests can reach
 * it without loading a class whose constructor touches the Android framework.
 */
internal fun preferredDeviceListFor(settings: AudioSettings): List<Class<out AudioDevice>> {
    val order = mutableListOf<Class<out AudioDevice>>()
    if (settings.bluetoothPreferred) {
        order.add(AudioDevice.BluetoothHeadset::class.java)
    }
    // A wired headset outranks the speaker whatever the flags say. There is no Dart
    // switch for it, and plugging one in has always meant "route here".
    order.add(AudioDevice.WiredHeadset::class.java)
    order.add(
        if (settings.speakerEnabled) {
            AudioDevice.Speakerphone::class.java
        } else {
            AudioDevice.Earpiece::class.java
        }
    )
    return order
}
