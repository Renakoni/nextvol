package indi.renakoni.nextvol.tts.offline.legacy

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.VersionedPackage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@Suppress("DEPRECATION")
class VocalizerContextTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun sdkIdentityIsLocalAndOtherPackageQueriesKeepRealMetadata() {
        val realInfo = PackageInfo().apply {
            packageName = "reader.host"
            signatures = arrayOf(Signature(byteArrayOf(9)))
        }
        val applicationInfo = ApplicationInfo().apply { packageName = "reader.host" }
        val manager = mockk<PackageManager> {
            every { getPackageInfo("reader.host", PackageManager.GET_SIGNATURES) } returns realInfo
            every { getApplicationInfo("reader.host", 0) } returns applicationInfo
        }
        val base = mockk<Context> {
            every { packageManager } returns manager
            every { packageName } returns "reader.host"
            every { getApplicationInfo() } returns applicationInfo
        }
        val certificate = byteArrayOf(1, 2, 3)
        val sdk = VocalizerContext(base, arrayOf(certificate))
        val local = sdk.packageManager.getPackageInfo(sdk.packageName, PackageManager.GET_SIGNATURES)

        assertEquals("es.codefactory.vocalizertts", local.packageName)
        assertArrayEquals(certificate, requireNotNull(local.signatures)[0].toByteArray())
        assertEquals(local.packageName, sdk.packageManager.getPackageInfo(VersionedPackage(sdk.packageName, 0), 0).packageName)
        assertSame(realInfo, sdk.packageManager.getPackageInfo("reader.host", PackageManager.GET_SIGNATURES))
        assertSame(applicationInfo, sdk.applicationInfo)
        assertSame(applicationInfo, sdk.packageManager.getApplicationInfo(sdk.packageName, 0))
        assertEquals("reader.host", base.packageName)
        assertArrayEquals(byteArrayOf(9), requireNotNull(realInfo.signatures)[0].toByteArray())
    }

    @Test fun missingOrMalformedCertificateFailsBeforeNativeLoading() {
        val directory = files.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = directory
        }
        File(directory, "offline-runtime-licenses.json").writeText("""{"vocalizer":{"signatures":[]}}""")
        assertThrows(IllegalStateException::class.java) { vocalizerContext(context) }

        File(directory, "offline-runtime-licenses.json").writeText("""{"vocalizer":{"signatures":["AQID"]}}""")
        val failure = assertThrows(IllegalStateException::class.java) { vocalizerContext(context) }
        assertTrue(failure.message.orEmpty().contains("X.509"))
    }
}
