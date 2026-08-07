package twilio.flutter.twilio_programmable_video

import com.twilio.audioswitch.AudioDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the one piece of the audio rewrite that is decidable without a device: the
 * translation from the two-flag Dart API onto AudioSwitch's device-priority order.
 *
 * Everything else in AudioRouter is a call into AudioSwitch, whose collaborators
 * (AudioDeviceManager, the scanners) are `internal` to that library and so cannot be
 * substituted from here; and the behaviour that actually broke — whether playout
 * follows the selection — is a property of the device's audio HAL, not of any code
 * this suite could reach. Those are covered by the integration smoke test and by the
 * manual matrix in the pull request.
 *
 * The plugin has no Gradle wrapper of its own, so run these through a host app that
 * consumes it:
 *
 *     cd <host app>/android && ./gradlew :twilio_programmable_video:testDebugUnitTest
 */
class AudioRouterTest {
    private val bluetooth = AudioDevice.BluetoothHeadset::class.java
    private val wired = AudioDevice.WiredHeadset::class.java
    private val earpiece = AudioDevice.Earpiece::class.java
    private val speakerphone = AudioDevice.Speakerphone::class.java

    /**
     * The combination every caller of this plugin in practice passes, and the one the
     * old code got wrong: it read `speakerphoneEnabled: true` as "speaker, full stop"
     * and skipped Bluetooth entirely.
     */
    @Test
    fun `speaker with bluetooth preferred puts bluetooth ahead of the speaker`() {
        val order = preferredDeviceListFor(AudioSettings(speakerEnabled = true, bluetoothPreferred = true))

        assertEquals(listOf(bluetooth, speakerphone, wired, earpiece), order)
    }

    @Test
    fun `bluetooth not preferred ranks it last rather than dropping it`() {
        val order = preferredDeviceListFor(AudioSettings(speakerEnabled = true, bluetoothPreferred = false))

        assertEquals(listOf(speakerphone, wired, earpiece, bluetooth), order)
    }

    /**
     * With the speaker off the earpiece takes its place at the head of the built-in
     * outputs, so "speaker off" still names a destination rather than leaving the
     * choice to AudioSwitch.
     */
    @Test
    fun `speaker off prefers the earpiece in its place`() {
        val order = preferredDeviceListFor(AudioSettings(speakerEnabled = false, bluetoothPreferred = true))

        assertEquals(listOf(bluetooth, wired, earpiece, speakerphone), order)
    }

    @Test
    fun `neither speaker nor bluetooth falls back to wired then earpiece`() {
        val order = preferredDeviceListFor(AudioSettings(speakerEnabled = false, bluetoothPreferred = false))

        assertEquals(listOf(wired, earpiece, speakerphone, bluetooth), order)
    }

    /**
     * Every class is named under every combination. An omitted entry does not fall to
     * the back of the order: AudioSwitch rebuilds the list by removing the named entries
     * from its default and reinserting them at the front, so what is left keeps its
     * default rank. Omitting Bluetooth for `bluetoothPreferred: false` left it ahead of
     * the speaker, which on a device with no earpiece routed the call to it.
     */
    @Test
    fun `every combination names all four devices`() {
        for (speakerEnabled in listOf(true, false)) {
            for (bluetoothPreferred in listOf(true, false)) {
                val order = preferredDeviceListFor(AudioSettings(speakerEnabled, bluetoothPreferred))

                assertEquals(
                    "incomplete order for speakerEnabled=$speakerEnabled bluetoothPreferred=$bluetoothPreferred",
                    setOf(bluetooth, wired, earpiece, speakerphone),
                    order.toSet()
                )
            }
        }
    }

    /**
     * `speakerphoneEnabled` is the only lever the Dart API offers here, so it has to
     * beat a plugged-in headset — the `isSpeakerphoneOn = true` write it replaces did.
     * Ranking wired above it unconditionally left an app whose user taps "speaker" with
     * earbuds in with nothing to ask again with: the call reported success and the audio
     * stayed on the earbuds.
     */
    @Test
    fun `the speaker flag outranks a wired headset`() {
        for (bluetoothPreferred in listOf(true, false)) {
            val order = preferredDeviceListFor(
                AudioSettings(speakerEnabled = true, bluetoothPreferred = bluetoothPreferred)
            )

            assertTrue(
                "the speaker must precede wired for bluetoothPreferred=$bluetoothPreferred",
                order.indexOf(speakerphone) < order.indexOf(wired)
            )
        }
    }

    /** With the speaker off, a wired headset is preferred over both built-in outputs. */
    @Test
    fun `speaker off leaves a wired headset ahead of the built-in outputs`() {
        for (bluetoothPreferred in listOf(true, false)) {
            val order = preferredDeviceListFor(
                AudioSettings(speakerEnabled = false, bluetoothPreferred = bluetoothPreferred)
            )

            assertTrue(
                "wired must precede the built-in outputs for bluetoothPreferred=$bluetoothPreferred",
                order.indexOf(wired) < order.indexOf(earpiece) &&
                    order.indexOf(wired) < order.indexOf(speakerphone)
            )
        }
    }

    /**
     * The tablet case that `bluetoothPreferred: false` used to lose: with no earpiece in
     * the available set, the entry that answers has to be the speaker and not the
     * Bluetooth headset the app opted out of.
     */
    @Test
    fun `bluetooth not preferred never outranks the speaker`() {
        for (speakerEnabled in listOf(true, false)) {
            val order = preferredDeviceListFor(
                AudioSettings(speakerEnabled = speakerEnabled, bluetoothPreferred = false)
            )

            assertTrue(
                "the speaker must precede bluetooth for speakerEnabled=$speakerEnabled",
                order.indexOf(speakerphone) < order.indexOf(bluetooth)
            )
        }
    }

    /**
     * API 23-30 is the only window where the plugin re-asks for the SCO link: below it
     * AudioSwitch's own `EnableBluetoothScoJob` already retries, and from 31 the route is
     * `setCommunicationDevice` and SCO says nothing about it.
     */
    @Test
    fun `sco is only retried on the releases that route bluetooth through it`() {
        val onlyElapsedVaries = { sdkInt: Int ->
            shouldRetryBluetoothSco(sdkInt, selectedIsBluetooth = true, scoOn = false, elapsedMs = 0)
        }

        assertFalse("API 22 retries inside AudioSwitch", onlyElapsedVaries(22))
        assertTrue("API 23 is the first release this covers", onlyElapsedVaries(23))
        assertTrue("API 30 is the last", onlyElapsedVaries(30))
        assertFalse("API 31 routes with setCommunicationDevice", onlyElapsedVaries(31))
        assertFalse("and so does everything above it", onlyElapsedVaries(36))
    }

    @Test
    fun `sco is not retried once the link is up, or for a route that is not bluetooth`() {
        assertFalse(
            "the link is already up",
            shouldRetryBluetoothSco(30, selectedIsBluetooth = true, scoOn = true, elapsedMs = 0)
        )
        assertFalse(
            "the route is not bluetooth",
            shouldRetryBluetoothSco(30, selectedIsBluetooth = false, scoOn = false, elapsedMs = 0)
        )
    }

    /** Bounded, so a headset that never connects cannot leave a retry looping all call. */
    @Test
    fun `sco retries give up at the timeout`() {
        val lastAttempt = SCO_RETRY_TIMEOUT_MS - SCO_RETRY_INTERVAL_MS
        assertTrue(
            "still within the window at ${lastAttempt}ms",
            shouldRetryBluetoothSco(30, selectedIsBluetooth = true, scoOn = false, elapsedMs = lastAttempt)
        )
        assertFalse(
            "done at the timeout",
            shouldRetryBluetoothSco(30, selectedIsBluetooth = true, scoOn = false, elapsedMs = SCO_RETRY_TIMEOUT_MS)
        )
    }

    /**
     * AudioSwitch throws IllegalArgumentException on a preferred list containing the
     * same class twice, from inside the constructor and from setPreferredDeviceList —
     * both on paths this plugin drives from a method channel call.
     */
    @Test
    fun `no combination produces a duplicate entry`() {
        for (speakerEnabled in listOf(true, false)) {
            for (bluetoothPreferred in listOf(true, false)) {
                val order = preferredDeviceListFor(AudioSettings(speakerEnabled, bluetoothPreferred))

                assertEquals(
                    "duplicate entry for speakerEnabled=$speakerEnabled bluetoothPreferred=$bluetoothPreferred",
                    order.size,
                    order.toSet().size
                )
            }
        }
    }
}
