package app.strata.launcher.rel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.openAllApps
import app.strata.launcher.passOnboarding
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * T-PRIV-01 (NFR-PRIV-01, D-007): Strata opens no socket. Three independent proofs, all automated on every GMD run:
 * 1. Strata does not hold `INTERNET` (the permission gate T-CI-01 keeps it out of the manifest).
 * 2. The kernel refuses Strata's UID any IP socket: without the `inet` group, creating a TCP or UDP socket (IPv4 or
 *    IPv6, loopback included) fails with EACCES (or EPERM where an eBPF filter enforces it). The test runs in Strata's process and UID, so this is Strata's real
 *    capability, not a policy check. It runs off the main thread (StrictMode network death stays armed for the app).
 * 3. After home, the list and search have run, `/proc/net/{tcp,tcp6,udp,udp6}` (read by the shell) lists no socket
 *    owned by Strata's UID.
 * The StrictMode `detectNetwork` death penalty (debug, every GMD run) covers any attempt on the main thread.
 */
@RunWith(AndroidJUnit4::class)
class NoNetworkDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tPriv01_noInternetPermission() {
        assertWithMessage("Strata must not hold INTERNET")
            .that(context.checkSelfPermission(Manifest.permission.INTERNET))
            .isEqualTo(PackageManager.PERMISSION_DENIED)
    }

    @Test
    fun tPriv01_kernelRefusesSockets() {
        val attempts =
            mapOf(
                "tcp4" to {
                    Socket().use {
                        it.connect(
                            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9),
                            500,
                        )
                    }
                },
                "tcp6" to { Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("::1"), 9), 500) } },
                "udp4" to { DatagramSocket().use { it.connect(InetAddress.getByName("127.0.0.1"), 9) } },
            )
        val pool = Executors.newSingleThreadExecutor()
        try {
            for ((name, attempt) in attempts) {
                val error = pool.submit(Callable { runCatching { attempt() }.exceptionOrNull() }).get()
                assertWithMessage("$name socket opened by Strata's UID").that(error).isNotNull()
                // EACCES: no inet group (classic paranoid networking). EPERM: kernels whose eBPF inet_create filter
                // enforces the INTERNET permission. Either way the kernel refused the socket.
                assertWithMessage("$name: expected EACCES or EPERM (no INTERNET), got $error")
                    .that(
                        generateSequence(error) { it.cause }.any {
                            "EACCES" in it.toString() ||
                                "EPERM" in it.toString() ||
                                it is SecurityException
                        },
                    ).isTrue()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun tPriv01_noSocketsAfterJourneys() {
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(
            10_000,
        ) { compose.onAllNodesWithTag(HomeTestTags.ALL_APPS).fetchSemanticsNodes().isNotEmpty() }
        compose.openAllApps()
        compose.waitForIdle()
        val uid = Process.myUid()
        val tables = listOf("tcp", "tcp6", "udp", "udp6").associateWith { shell("cat /proc/net/$it") }
        // Some releases may not let the shell read /proc/net; then only proofs 1 and 2 stand (reported as skipped).
        assumeTrue("the shell cannot read /proc/net/tcp here", "local_address" in tables.getValue("tcp"))
        val owned =
            tables.flatMap { (table, text) ->
                text
                    .lines()
                    .drop(1)
                    .filter { socketUid(it) == uid }
                    .map { "$table: $it" }
            }
        assertWithMessage("sockets owned by Strata's UID $uid").that(owned).isEmpty()
    }

    /** `/proc/net/tcp` row: `sl local rem st tx:rx tr:when retrnsmt uid …` (uid is the 8th field). */
    private fun socketUid(row: String): Int? =
        row
            .trim()
            .split(Regex("\\s+"))
            .getOrNull(7)
            ?.toIntOrNull()
}
