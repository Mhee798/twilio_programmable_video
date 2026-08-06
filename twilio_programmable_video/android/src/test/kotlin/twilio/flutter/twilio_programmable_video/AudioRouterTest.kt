package twilio.flutter.twilio_programmable_video

import com.twilio.audioswitch.AudioDevice
import org.junit.Assert.assertEquals
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
    fun `speaker with bluetooth preferred puts both headsets ahead of the speaker`() {
        val order = preferredDeviceListFor(AudioSettings(speakerEnabled = true, bluetoothPreferred = true))

        assertEquals(listOf(bluetooth, wired, speakerphone), order)
    }

    @Test
    fun `bluetooth not preferred keeps it out of the order entirely`() {
        val order = preferredDeviceListFor(AudioSettings(speakerEnabled = true, bluetoothPreferred = false))

        assertEquals(listOf(wired, speakerphone), order)
    }

    /**
     * With the speaker off the earpiece takes its place rather than the entry being
     * dropped, so "speaker off" still names a destination instead of leaving the
     * choice to AudioSwitch's default order, where the speaker would outrank nothing.
     */
    @Test
    fun `speaker off prefers the earpiece in its place`() {
        val order = preferredDeviceListFor(AudioSettings(speakerEnabled = false, bluetoothPreferred = true))

        assertEquals(listOf(bluetooth, wired, earpiece), order)
    }

    @Test
    fun `neither speaker nor bluetooth falls back to wired then earpiece`() {
        val order = preferredDeviceListFor(AudioSettings(speakerEnabled = false, bluetoothPreferred = false))

        assertEquals(listOf(wired, earpiece), order)
    }

    /**
     * A wired headset outranks the speaker under every flag combination. There is no
     * Dart switch for it, and plugging one in has always meant "route here".
     */
    @Test
    fun `a wired headset always outranks the speaker`() {
        for (bluetoothPreferred in listOf(true, false)) {
            val order = preferredDeviceListFor(
                AudioSettings(speakerEnabled = true, bluetoothPreferred = bluetoothPreferred)
            )

            assertTrue(
                "wired must precede the speaker for bluetoothPreferred=$bluetoothPreferred",
                order.indexOf(wired) < order.indexOf(speakerphone)
            )
        }
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
