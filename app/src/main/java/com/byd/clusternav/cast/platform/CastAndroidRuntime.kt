package com.byd.clusternav.cast.platform

import com.byd.clusternav.cast.transport.CastAdbGateway
import com.byd.clusternav.modules.clustercast.v2.*

import android.content.Context
import android.os.Build
import com.byd.clusternav.AdbKeys
import dadb.Dadb
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference



/** Android integration factory. Every shell call owns and closes its Cast-only DADB session. */
object CastAndroidRuntime {
    data class Runtime(
        val coordinator: CastCoordinator,
        val store: CastSessionStore,
        val gateway: CastAdbGateway,
        val adjustment: CastAdjustmentWorkspace,
        val automation: CastAutomationSettings,
        val vehicleFacts: CastVehicleFacts,
    )

    @Volatile private var processRuntime: Runtime? = null

    /** One process-wide control plane: all Activities/services share the same store lock and mutation lease. */
    fun create(context: Context): Runtime = processRuntime ?: synchronized(this) {
        processRuntime ?: newRuntime(context.applicationContext).also { processRuntime = it }
    }

    private fun newRuntime(app: Context): Runtime {
        val store = CastSessionStore(AndroidAtomicBytes(File(app.filesDir, "cast-v2/session.env")))
        // App cấp hai phụ thuộc Android cho transport; CLI runner sẽ cấp bản khác.
        val gateway = CastAdbGateway({ AdbKeys.ensure(app) }, { CastInProcessDisplay.measure(app) })
        val reader = ObservedStateReader(
            gateway,
            DumpObservedStateParser.withFallback { CastInProcessDisplay.measure(app) },
        )
        val executor = CastExecutor(store, gateway)
        val recovery = CastRecovery(store, executor)
        return Runtime(
            store = store,
            gateway = gateway,
            coordinator = CastCoordinator(store, reader, executor, recovery),
            adjustment = CastAdjustmentWorkspace(store),
            automation = CastAutomationSettings(store),
            vehicleFacts = CastVehicleFacts(
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.BRAND,
                Build.PRODUCT, Build.DEVICE, Build.PRODUCT,
            ),
        )
    }
}

