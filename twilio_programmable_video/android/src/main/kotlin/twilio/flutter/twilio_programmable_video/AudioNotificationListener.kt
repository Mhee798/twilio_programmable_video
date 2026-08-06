package twilio.flutter.twilio_programmable_video

import com.twilio.audioswitch.AudioDevice

class AudioNotificationListener() : BaseListener() {
    private val TAG = "AudioNotificationListener"
    private val activeAudioPlayers: MutableSet<String> = mutableSetOf()

    /**
     * The headsets the Dart layer has been told about, so the next device list can be
     * turned back into the add/remove events the public API is defined in terms of.
     *
     * AudioSwitch reports the whole list on every change rather than a delta, and it
     * reports it on start too, so the first callback after `setAudioSettings` announces
     * a headset that was already connected. That is new — the old
     * BroadcastReceiver could not see one, because
     * BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED is not a sticky broadcast and no
     * event is delivered for a connection that predates the registration.
     *
     * Only ever touched from the main looper: every writer is either a method channel
     * call or an AudioSwitch callback marshalled through [runOnMainThread].
     */
    private var knownHeadsets: List<AudioDevice> = emptyList()

    /**
     * Emits the plugin's `newDeviceAvailable`/`oldDeviceUnavailable` events for the
     * difference against the previous list.
     *
     * Only headsets are reported. The earpiece and the speaker are always present in
     * AudioSwitch's list, and the Dart events have only ever described a device being
     * plugged in or unplugged, so announcing built-in outputs would be a new meaning
     * for an existing event rather than more information.
     *
     * Known limitation, inherited from AudioSwitch and not worked around here: its
     * available-device set is ordered by AudioDevicePriorityComparator, which reports
     * two devices of the same class as equal, so the set holds at most one Bluetooth
     * entry. With two Bluetooth devices connected only the first is ever announced, and
     * disconnecting it reads as "no Bluetooth device" even though the other is still
     * usable — the route then falls back to the speaker. The deleted
     * `bluetoothHeadsetConnectionState()` did not have this blind spot, because
     * BluetoothProfile.getProfileConnectionState answers for *any* connected headset;
     * it had a worse one, needing BLUETOOTH_CONNECT and lagging behind the broadcast
     * that prompted the read. Restoring it as a fallback would reintroduce that lag on
     * the common single-headset path to fix a two-headset case.
     */
    internal fun onAudioDevicesChanged(devices: List<AudioDevice>) {
        val headsets = devices.filter { it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }
        val added = headsets - knownHeadsets.toSet()
        val removed = knownHeadsets - headsets.toSet()
        knownHeadsets = headsets

        removed.forEach { sendDeviceEvent("oldDeviceUnavailable", it, connected = false) }
        added.forEach { sendDeviceEvent("newDeviceAvailable", it, connected = true) }
    }

    /**
     * Reports every known headset as unavailable and clears the list, for when route
     * discovery stops.
     *
     * The devices are still physically connected, so this is not literally true. It is
     * still the better of the two options: the events are documented as a pair, and an
     * app maintaining a device list adds on `newDeviceAvailable` and removes on
     * `oldDeviceUnavailable`. Clearing silently was tried first and leaves that list
     * holding an entry the app can never remove — the next `setAudioSettings` restarts
     * discovery, the scanner re-reports the same still-connected headset, and it is
     * announced as new a second time, so the list grows by one per call cycle.
     */
    internal fun announceDevicesUnavailable() {
        val headsets = knownHeadsets
        knownHeadsets = emptyList()
        headsets.forEach { sendDeviceEvent("oldDeviceUnavailable", it, connected = false) }
    }

    private fun sendDeviceEvent(event: String, device: AudioDevice, connected: Boolean) {
        val bluetooth = device is AudioDevice.BluetoothHeadset
        debug("sendDeviceEvent => event: $event, deviceName: ${device.name}, bluetooth: $bluetooth")
        sendEvent(event, mapOf(
                "connected" to connected,
                "bluetooth" to bluetooth,
                "wired" to !bluetooth,
                // Never null now. Reading it needed BLUETOOTH_CONNECT while the name
                // came off a BluetoothDevice extra, and the Dart layer turns a null
                // into a SkippableAudioEvent; AudioSwitch takes the name from
                // AudioDeviceInfo.productName instead, which no permission gates.
                "deviceName" to device.name
        ))
    }

    /**
     * Marshalled onto the main looper as a whole, not per call.
     *
     * This listener is handed to other plugins through
     * `TwilioProgrammableVideoPlugin.getAudioPlayerEventListener` and can arrive on any
     * thread. Letting each callee hop on its own reorders them: `AudioRouter` runs
     * inline when already on the main thread and posts otherwise, so an off-thread
     * caller would have `deactivate()` posted while `setAudioFocus(false)` below ran
     * immediately — and both write the audio mode, with AudioSwitch's restore landing
     * last and leaving the device in MODE_IN_COMMUNICATION with no focus held and
     * nothing left to put it back. `activeAudioPlayers` and the fields
     * `setAudioFocus` saves into are unsynchronised for the same reason.
     */
    internal fun audioPlayerEventListener(url: String, isPlaying: Boolean) = runOnMainThread(TAG) {
        debug("audioPlayerEventListener => url: $url, isPlaying: $isPlaying")

        val anyAudioPlayersAlreadyActive = anyAudioPlayersActive()
        updateActiveAudioPlayerList(url, isPlaying)
        val anyAudioPlayersNowActive = anyAudioPlayersActive()

        val isConnected = TwilioProgrammableVideoPlugin.isConnected()

        debug("audioPlayerEventListener =>\n\tisConnected: $isConnected\n\talreadyActive: $anyAudioPlayersAlreadyActive\n\tnowActive: $anyAudioPlayersNowActive")
        if (anyAudioPlayersNowActive && !anyAudioPlayersAlreadyActive) {
            TwilioProgrammableVideoPlugin.pluginHandler.applyAudioSettings()
        } else if (!isConnected && !anyAudioPlayersNowActive && anyAudioPlayersAlreadyActive) {
            // An activated route functions similarly to holding Audio Focus when it comes
            // to external apps audio, if that external app would normally be using the connected
            // bluetooth device. That is, it prevents the external app from resuming playback.
            //
            // Ordered against setAudioFocus below exactly as in PluginHandler.disconnect:
            // the router restores the mode it captured on activate, then setAudioFocus
            // restores the one from before the call.
            TwilioProgrammableVideoPlugin.pluginHandler.audioRouter.deactivate()
        }

        // Do not setAudioFocus here if we are Connected, because if we are we presumably already have
        // audio focus, and want to keep it.
        if (!isConnected && anyAudioPlayersAlreadyActive != anyAudioPlayersNowActive) {
            debug("audioPlayerEventListener => setAudioFocus: $anyAudioPlayersNowActive")
            TwilioProgrammableVideoPlugin.pluginHandler.setAudioFocus(anyAudioPlayersNowActive)
        }
    }

    private fun updateActiveAudioPlayerList(url: String, isPlaying: Boolean) {
        if (isPlaying) {
            activeAudioPlayers.add(url)
        } else {
            activeAudioPlayers.remove(url)
        }
    }

    internal fun anyAudioPlayersActive(): Boolean {
        return activeAudioPlayers.isNotEmpty()
    }

    internal fun debug(msg: String) {
        TwilioProgrammableVideoPlugin.debugAudio("$TAG::$msg")
    }
}
