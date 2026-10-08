package indi.renakoni.nextvol.benchmark

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import indi.renakoni.nextvol.NextVolApplication
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read backing injection state without constructing a repository or accessing host data. */
internal fun startupProcessProbe(app: NextVolApplication): String {
    val dependencies: List<() -> Any> = listOf(
        { app.sourceImageInterceptor }, { app.importedRuleSources }, { app.zLibrarySources },
        { app.localBooks }, { app.bangumiSync }, { app.workerFactory }, { app.loggerRepository },
        { app.userDataRepository }, { app.webBookDataSourceManager },
        { app.sourceNetworkSettings },
    )
    val injected = dependencies.count { read ->
        try { read(); true } catch (_: UninitializedPropertyAccessException) { false }
    }
    val process = File("/proc/self/cmdline").readText().substringBefore('\u0000')
    return "startup-probe process=$process uid=${Process.myUid()} appUid=${app.applicationInfo.uid} injected=$injected/${dependencies.size}"
}

/** Deliberately not an AndroidEntryPoint: only Application.onCreate may inject the host. */
open class StartupBrowserProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = Activity.RESULT_OK
        resultData = startupProcessProbe(context.applicationContext as NextVolApplication)
    }
}

class StartupNativeBrowserProbeReceiver : StartupBrowserProbeReceiver()

/** Its name does not match either browser guard, so this exercises the UID guard alone. */
class StartupIsolatedProbeService : Service() {
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION || reply == null)
                return super.onTransact(code, data, reply, flags)
            reply.writeNoException()
            reply.writeString(startupProcessProbe(application as NextVolApplication))
            return true
        }
    }
}

internal fun probeStartupIsolatedProcess(context: Context): String {
    val connected = CountDownLatch(1)
    var result: Result<String>? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            result = runCatching {
                val input = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    check(service.transact(IBinder.FIRST_CALL_TRANSACTION, input, reply, 0))
                    reply.readException()
                    requireNotNull(reply.readString())
                } finally { input.recycle(); reply.recycle() }
            }
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    check(context.bindService(Intent(context, StartupIsolatedProbeService::class.java),
        connection, Context.BIND_AUTO_CREATE))
    try {
        check(connected.await(15, TimeUnit.SECONDS)) { "Startup probe did not bind" }
        return requireNotNull(result).getOrThrow()
    } finally { context.unbindService(connection) }
}
