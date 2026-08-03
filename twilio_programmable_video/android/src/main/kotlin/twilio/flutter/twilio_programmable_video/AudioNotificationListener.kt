package twilio.flutter.twilio_programmable_video

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

class AudioNotificationListener() : BaseListener() {
    private val TAG = "AudioNotificationListener"
    private val intentFilter: IntentFilter = IntentFilter()
    private val activeAudioPlayers: MutableSet<String> = mutableSetOf()

    private var bluetoothProfileProxy: BluetoothProfile.ServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceDisconnected(profile: Int) {
            debug("onServiceDisconnected => profile: $profile")
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothProfile = null
                // The connection is gone and the proxy we were handed is no longer valid,
                // so no closeProfileProxy is owed — and the next setAudioSettings should be
                // free to bind a fresh one rather than seeing a stale "already bound".
                profileProxyBound = false
                TwilioProgrammableVideoPlugin.pluginHandler.applyAudioSettings()
            }
        }

        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            debug("onServiceConnected => profile: $profile, proxy: $proxy")
            if (profile != BluetoothProfile.HEADSET) return
            bluetoothProfile = proxy

            // BluetoothProfile.getConnectedDevices needs BLUETOOTH_CONNECT from API
            // 31 on. The system invokes this callback on the main looper long after
            // getProfileProxy() returned, so a SecurityException raised here is not
            // caught by the caller and used to take the whole app down. Treat a
            // missing grant as "no headset connected".
            val headsetConnected = try {
                (proxy?.connectedDevices?.size ?: 0) > 0
            } catch (e: SecurityException) {
                debug("onServiceConnected => BLUETOOTH_CONNECT not granted: ${e.message}")
                false
            }

            if (headsetConnected && TwilioProgrammableVideoPlugin.pluginHandler.audioSettings.bluetoothPreferred) {
                TwilioProgrammableVideoPlugin.pluginHandler.applyAudioSettings()
            }
        }
    }

    var bluetoothProfile: BluetoothProfile? = null

    private val receiver: BroadcastReceiver = getBroadcastReceiver()

    /**
     * registerReceiver is additive — registering the same instance twice makes every
     * broadcast arrive twice, while unregisterReceiver drops all registrations at once,
     * so the two can never rebalance. setAudioSettings calls listenForRouteChanges
     * unconditionally, so the pair has to be tracked.
     */
    private var receiverRegistered = false

    /**
     * getProfileProxy is additive in exactly the same way, and setAudioSettings reaches it
     * on the same unconditional path. Every call opens another connection to the headset
     * profile service while closeProfileProxy releases only the single proxy it is handed,
     * so the bind/unbind pair cannot rebalance either.
     *
     * Leaking the connection is the smaller half of it: each new bind overwrites
     * [bluetoothProfile] in onServiceConnected, which drops the reference to every earlier
     * proxy and makes them impossible to close at all. Each bind also fires
     * onServiceConnected, so applyAudioSettings — and the audio re-routing it performs —
     * runs once per accumulated connection.
     */
    private var profileProxyBound = false

    init {
        // https://developer.android.com/reference/android/media/AudioManager#ACTION_HEADSET_PLUG
        intentFilter.addAction(AudioManager.ACTION_HEADSET_PLUG)
        // https://developer.android.com/reference/android/bluetooth/BluetoothHeadset#ACTION_CONNECTION_STATE_CHANGED
        intentFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)

        // Other actions we could listen for:
        // 1. AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED
        //      https://developer.android.com/reference/android/media/AudioManager#ACTION_SCO_AUDIO_STATE_UPDATED
        // to handle changes in the BluetoothSco state

        // 2. BluetoothAdapter.ACTION_STATE_CHANGED
        //      https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#ACTION_STATE_CHANGED
        // to handle Bluetooth being toggled at the OS level, but the BluetoothProfile.ServiceListener above
        // also fills that role.
    }

    fun listenForRouteChanges(context: Context) {
        debug("listenForRouteChanges")
        if (!receiverRegistered) {
            context.registerReceiver(receiver, intentFilter)
            receiverRegistered = true
        }

        if (profileProxyBound) {
            debug("listenForRouteChanges => headset profile proxy already bound")
            return
        }

        // Binding the headset profile proxy needs BLUETOOTH_CONNECT from API 31 on.
        // Skipping the bind when the permission is missing also means
        // onServiceConnected below never runs, so its getConnectedDevices call — the
        // one that used to take the whole app down — cannot be reached at all. The
        // try/catch there stays as a second line of defence. Only Bluetooth routing
        // is lost; the headset-plug receiver registered above keeps working.
        //
        // Nothing re-binds by itself when a grant arrives later, but setAudioSettings runs
        // this whole path again on every call, so an app that requests BLUETOOTH_CONNECT
        // mid-call picks the proxy up on its next call instead of staying unrouted for the
        // rest of the session.
        if (!TwilioProgrammableVideoPlugin.pluginHandler.hasBluetoothConnectPermission()) {
            debug("listenForRouteChanges => BLUETOOTH_CONNECT not granted, skipping headset profile proxy")
            return
        }
        try {
            // false means the profile is unsupported or the bind could not be started —
            // onServiceConnected will not run and no closeProfileProxy is owed. Only a
            // true here makes this instance responsible for releasing a connection.
            profileProxyBound = BluetoothAdapter.getDefaultAdapter()
                    ?.getProfileProxy(context, getProfileProxy(), BluetoothProfile.HEADSET) ?: false
        } catch (e: SecurityException) {
            debug("listenForRouteChanges => SecurityException: ${e.message}")
        }
    }

    fun stopListeningForRouteChanges(context: Context) {
        debug("stopListeningForRouteChanges")
        // Throws if the receiver was never registered — reachable by calling
        // disableAudioSettings twice, or before any setAudioSettings. The flag covers
        // the common case; the catch stays for a context mismatch desyncing it.
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                debug("stopListeningForRouteChanges => receiver was not registered: ${e.message}")
            }
            receiverRegistered = false
        }
        // Tracking the bind rather than re-checking the permission: a proxy is only ever
        // bound while BLUETOOTH_CONNECT is held, so this also covers the ungranted case,
        // and it does not leave a connection dangling if the grant were ever to disappear
        // between the two calls.
        if (!profileProxyBound) {
            debug("stopListeningForRouteChanges => headset profile proxy not bound, nothing to unbind")
            return
        }
        try {
            BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothProfile)
        } catch (e: SecurityException) {
            debug("stopListeningForRouteChanges => SecurityException: ${e.message}")
        }
        profileProxyBound = false
        bluetoothProfile = null
    }

    private fun getBroadcastReceiver(): BroadcastReceiver {
        debug("getBroadcastReceiver")
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val wiredEvent = intent?.action.equals(AudioManager.ACTION_HEADSET_PLUG)
                val bluetoothEvent = intent?.action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)

                val connected: Boolean = when {
                    wiredEvent -> {
                        intent?.getIntExtra("state", 0) == 1
                    }
                    bluetoothEvent -> {
                        val state = intent?.getIntExtra(BluetoothProfile.EXTRA_STATE, 0)
                        if (state == BluetoothProfile.STATE_CONNECTING || state == BluetoothProfile.STATE_DISCONNECTING) {
                            return
                        }
                        state == BluetoothProfile.STATE_CONNECTED
                    }
                    else -> null
                } ?: return

                val event = if (connected) "newDeviceAvailable" else "oldDeviceUnavailable"

                val deviceName = if (bluetoothEvent) bluetoothDeviceName(intent)
                    else intent?.getStringExtra("portName") ?: return

                debug("onReceive => connected: $connected\n\tevent: $event\n\tbluetoothEvent: $bluetoothEvent\n\twiredEvent: $wiredEvent\n\tdeviceName: $deviceName")

                if (bluetoothEvent) {
                    TwilioProgrammableVideoPlugin.pluginHandler.applyAudioSettings()
                }

                debug("onReceive => event: $event, connected: $connected, bluetooth: $bluetoothEvent, wired: $wiredEvent")
                sendEvent(event, mapOf(
                        "connected" to connected,
                        "bluetooth" to bluetoothEvent,
                        "wired" to wiredEvent,
                        "deviceName" to deviceName
                ))
            }
        }
    }

    /**
     * BluetoothDevice.getName needs BLUETOOTH_CONNECT from API 31 on, and this is read
     * inside a BroadcastReceiver where an uncaught SecurityException would tear the app
     * down. The only change from the original is the catch — the null return is
     * deliberate.
     *
     * A synthetic placeholder name was tried and is worse than dropping the event. The
     * name is the only identity these events carry, so every unidentifiable device
     * would share it: an app keeping a device list keyed by name removes the wrong
     * entry when a second unnamed headset disconnects. The Dart layer turns a null
     * deviceName into a SkippableAudioEvent (method_channel_programmable_video.dart),
     * and this plugin's own re-routing already happened in the caller before the event
     * is sent, so dropping it only costs the app a UI notification rather than
     * corrupting its state.
     */
    private fun bluetoothDeviceName(intent: Intent?): String? {
        return try {
            intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.name
        } catch (e: SecurityException) {
            debug("bluetoothDeviceName => BLUETOOTH_CONNECT not granted: ${e.message}")
            null
        }
    }

    fun getProfileProxy(): BluetoothProfile.ServiceListener {
        debug("getProfileProxy")
        return bluetoothProfileProxy
    }

    internal fun audioPlayerEventListener(url: String, isPlaying: Boolean) {
        debug("audioPlayerEventListener => url: $url, isPlaying: $isPlaying")

        val anyAudioPlayersAlreadyActive = anyAudioPlayersActive()
        updateActiveAudioPlayerList(url, isPlaying)
        val anyAudioPlayersNowActive = anyAudioPlayersActive()

        val isConnected = TwilioProgrammableVideoPlugin.isConnected()

        debug("audioPlayerEventListener =>\n\tisConnected: $isConnected\n\talreadyActive: $anyAudioPlayersAlreadyActive\n\tnowActive: $anyAudioPlayersNowActive")
        if (anyAudioPlayersNowActive && !anyAudioPlayersAlreadyActive) {
            TwilioProgrammableVideoPlugin.pluginHandler.applyAudioSettings()
        } else if (!isConnected && !anyAudioPlayersNowActive && anyAudioPlayersAlreadyActive) {
            // BluetoothSco being enabled functions similarly to holding Audio Focus when it comes
            // to external apps audio, if that external app would normally be using the connected
            // bluetooth device. That is, it prevents the external app from resuming playback.
            TwilioProgrammableVideoPlugin.pluginHandler.setBluetoothSco(false)
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
