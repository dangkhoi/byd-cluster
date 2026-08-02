package com.byd.clusternav.modules.clustercast.simplified

import android.content.Context
import android.content.SharedPreferences
import com.byd.clusternav.AdbKeys
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastShell
import com.byd.clusternav.modules.clustercast.simplified.ShellResult
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastPrefs
import com.byd.clusternav.modules.clustercast.simplified.DisplayConfig
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastCoordinator
import com.byd.clusternav.modules.clustercast.simplified.ProjectionManager
import com.byd.clusternav.modules.clustercast.simplified.DisplayConfigurator
import com.byd.clusternav.modules.clustercast.simplified.AppMover
import dadb.Dadb

/**
 * Android-side runtime for the simplified Cluster Cast coordinator.
 *
 * Bridges the pure-Kotlin core coordinator to the Android platform:
 * - Shell execution via dadb (localhost:5555, same as V2)
 * - Preferences via SharedPreferences
 * - Lifecycle tied to app process
 *
 * Process-singleton: all activities/services share one instance.
 */
object SimpleCastRuntime {

    @Volatile private var instance: SimpleCastCoordinator? = null

    /** Get or create the process-wide coordinator. Thread-safe. */
    fun coordinator(context: Context): SimpleCastCoordinator {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    private fun create(app: Context): SimpleCastCoordinator {
        val shell = DadbSimpleCastShell(app)
        val prefs = SharedPrefsSimpleCastPrefs(app)
        val projection = ProjectionManager(shell)
        val configurator = DisplayConfigurator(shell)
        val mover = AppMover(shell)
        // Display ID 1 is the cluster on BYD DiLink3 (measured on vehicle)
        val displayId = prefs.lastDisplayId() ?: 1
        return SimpleCastCoordinator(projection, configurator, mover, prefs, displayId)
    }

    /** Shutdown the coordinator. Call from Application.onTerminate or process exit. */
    fun shutdown() {
        instance?.shutdown()
        instance = null
    }
}

/**
 * SimpleCastShell implementation using dadb localhost connection.
 *
 * Each shell command opens a fresh dadb session (same pattern as CastAdbGateway).
 * Connection timeout: 2s. Command timeout: 10s.
 */
private class DadbSimpleCastShell(private val app: Context) : SimpleCastShell {

    override fun execute(command: String): ShellResult {
        return try {
            val keyPair = AdbKeys.ensure(app)
            Dadb.create("localhost", 5555, keyPair).use { adb ->
                val result = adb.shell(command)
                ShellResult(
                    exitCode = result.exitCode,
                    stdout = result.output,
                    stderr = result.errorOutput,
                )
            }
        } catch (e: Exception) {
            ShellResult(exitCode = -1, stdout = "", stderr = e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * SharedPreferences-based implementation of SimpleCastPrefs.
 */
private class SharedPrefsSimpleCastPrefs(context: Context) : SimpleCastPrefs {
    private val sp: SharedPreferences =
        context.getSharedPreferences("simple_cast_prefs", Context.MODE_PRIVATE)

    override fun displayConfigFor(pkg: String): DisplayConfig? {
        val size = sp.getString("config_size_$pkg", null) ?: return null
        val overscan = sp.getString("config_overscan_$pkg", "0,0,0,0") ?: "0,0,0,0"
        val density = sp.getString("config_density_$pkg", "reset") ?: "reset"
        return DisplayConfig(wmSize = size, overscan = overscan, density = density)
    }

    override fun saveDisplayConfig(pkg: String, config: DisplayConfig) {
        sp.edit()
            .putString("config_size_$pkg", config.wmSize)
            .putString("config_overscan_$pkg", config.overscan)
            .putString("config_density_$pkg", config.density)
            .apply()
    }

    override fun lastDisplayId(): Int? {
        val v = sp.getInt("last_display_id", -1)
        return if (v >= 0) v else null
    }

    override fun saveLastDisplayId(id: Int) {
        sp.edit().putInt("last_display_id", id).apply()
    }

    override fun isDisplayDirty(): Boolean = sp.getBoolean("display_dirty", false)

    override fun setDisplayDirty(dirty: Boolean) {
        sp.edit().putBoolean("display_dirty", dirty).apply()
    }
}
