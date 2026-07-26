package com.byd.clusternav.modules.clustercast.v2

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastAndroidRuntimeTest {
    private val sealDl3DisplayDump = """
        Display Devices: size=3
          DisplayDeviceInfo{"Built-in Screen": uniqueId="local:19261206365013889", 1920 x 1080}
          DisplayDeviceInfo{"launcher-split": uniqueId="virtual:com.dudu.autoui,10075,launcher-split,0", 1284 x 1080}
          DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface": uniqueId="virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0", 1920 x 720}
        Display 0:
          mDisplayId=0
          mPhysicalDisplayId=19261206365013889
          mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", app 1920 x 1080, density 240}
        Display 1:
          mDisplayId=1
          mBaseDisplayInfo=DisplayInfo{"launcher-split, displayId 1", app 1284 x 1080, density 240}
        Display 2:
          mDisplayId=2
          mBaseDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 2", app 1920 x 720, density 180}
    """.trimIndent()

    @Test
    fun `parser accepts exactly one non-main display and never guesses across displays`() {
        val known = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(2, "com.example.maps/.MainActivity"),
                "Display: mDisplayId=2",
                clusterDisplayDump(2 to "fission"),
                "10",
                "1.0\n0.5\n1.0",
                "Package com.example.maps:\n  PICTURE_IN_PICTURE: allow",
            ),
        ) as ObservationValue.Known
        assertEquals(ObservedCoarseState.ACTIVE_SINGLE, known.value.coarseState)
        assertEquals("com.example.maps", known.value.target!!.packageName)
        assertEquals(2, known.value.target!!.displayId)

        val disputed = AndroidObservedStateParser.parse(
            RawObservation(
                "",
                "Display: mDisplayId=2\nDisplay: mDisplayId=3",
                clusterDisplayDump(2 to "fission", 3 to "xdja"),
                "profile",
            ),
        )
        assertTrue(disputed is ObservationValue.Unknown)
    }

    @Test
    fun `parser extracts hidden protected residue PIP animation and profile truth`() {
        val parsed = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(
                    2,
                    "com.example.maps/.Main visible=true",
                    "com.byd.carplay.ui/.Video visible=false",
                ),
                "Display: mDisplayId=2\nbounds=[0,0][1920,720] density=180",
                clusterDisplayDump(2 to "fission"),
                "10",
                "1.0\n0.5\n1.0",
                "Package com.example.maps:\n  PICTURE_IN_PICTURE: ignore",
            ),
        ) as ObservationValue.Known
        assertEquals("com.example.maps", parsed.value.target!!.packageName)
        assertEquals(ResidueVisibility.HIDDEN, parsed.value.protectedResidue!!.visibility)
        assertEquals("ignore", parsed.value.pipMode)
        assertEquals("0.5", parsed.value.animationPerKey["transition_animation_scale"])
        assertEquals("android-user-10", parsed.value.profile)
    }

    @Test
    fun `parser rejects ambiguous protected residue incomplete animation and absent target app-ops`() {
        val twoResidues = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(
                    2,
                    "com.example.maps/.Main visible=true",
                    "com.byd.carplay.ui/.One visible=false",
                    "com.androidauto.ui/.Two visible=false",
                ),
                "Display: mDisplayId=2", clusterDisplayDump(2 to "fission"), "10", "1\n1\n1",
                "Package com.example.maps:\n  PICTURE_IN_PICTURE: allow",
            ),
        )
        assertTrue(twoResidues is ObservationValue.Unknown)

        val incompleteAnimation = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(2, "com.example.maps/.Main visible=true"),
                "Display: mDisplayId=2", clusterDisplayDump(2 to "fission"), "10", "1\n1",
                "Package com.example.maps:\n  PICTURE_IN_PICTURE: allow",
            ),
        )
        assertTrue(incompleteAnimation is ObservationValue.Unknown)

        val unrelatedPip = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(2, "com.example.maps/.Main visible=true"),
                "Display: mDisplayId=2", clusterDisplayDump(2 to "fission"), "10", "1\n1\n1",
                "Package com.other.app:\n  PICTURE_IN_PICTURE: allow",
            ),
        )
        assertTrue(unrelatedPip is ObservationValue.Unknown)

        val staleParent = AndroidObservedStateParser.parse(
            RawObservation(
                "Stack id=1 displayId=2\nSection:\n  taskId=7: com.example.maps/.Main visible=true",
                "Display: mDisplayId=2", clusterDisplayDump(2 to "fission"), "10", "1\n1\n1",
                "Package com.example.maps:\n  PICTURE_IN_PICTURE: allow",
            ),
        )
        assertTrue(staleParent is ObservationValue.Unknown)
    }

    @Test
    fun `parser reports unsupported active profile format instead of inventing identity`() {
        val parsed = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(2, "com.example.maps/.Main visible=true"),
                "Display: mDisplayId=2", clusterDisplayDump(2 to "fission"), "Current user unavailable", "1\n1\n1",
                "Package com.example.maps:\n  PICTURE_IN_PICTURE: allow",
            ),
        )
        assertTrue(parsed is ObservationValue.Unsupported)
    }

    @Test
    fun `real Seal DL3 logical block selects cluster display and supplies idle bootstrap geometry`() {
        val discovered = discoverClusterDisplay(sealDl3DisplayDump)!!
        assertEquals(2, discovered.id)
        assertEquals("fission_bg_xdjaVirtualSurface", discovered.name)
        assertEquals(1920, discovered.appWidth)
        assertEquals(720, discovered.appHeight)
        assertEquals(180, discovered.densityDpi)

        val parsed = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(0, "com.android.launcher3/.Launcher visible=true"),
                "Display: mDisplayId=0 bounds=[0,0][1920,1080] density=240\n" +
                    "Display: mDisplayId=1 bounds=[0,0][1284,1080] density=240",
                sealDl3DisplayDump,
                "10",
                "1.0\n0.5\n1.0",
                "",
            ),
        ) as ObservationValue.Known
        assertEquals(ObservedCoarseState.IDLE_CLEAN, parsed.value.coarseState)
        assertEquals("display-2", parsed.value.displayIdentity)
        assertEquals("fission_bg_xdjaVirtualSurface", parsed.value.displayName)
        assertEquals(CastRect(0, 0, 1920, 720), parsed.value.geometry!!.bounds)
        assertEquals(180, parsed.value.geometry!!.densityDpi)
        assertTrue(CastColdBootstrapVerification.accepts(parsed.value))

        val suffixDump = sealDl3DisplayDump.replace(
            "fission_bg_xdjaVirtualSurface",
            "Fission_bg_xdjaVirtualSurface_0",
        )
        assertEquals("Fission_bg_xdjaVirtualSurface_0", discoverClusterDisplay(suffixDump)?.name)
        val suffixParsed = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(0, "com.android.launcher3/.Launcher visible=true"),
                "unrelated bounds=[0,0][1,1] density=1", suffixDump, "10", "1.0\n0.5\n1.0", "",
            ),
        ) as ObservationValue.Known
        assertTrue(CastColdBootstrapVerification.accepts(suffixParsed.value))

        val ambiguous = sealDl3DisplayDump + "\n" + """
            Display 3:
              mDisplayId=3
              mBaseDisplayInfo=DisplayInfo{"xdja_recreated_0, displayId 3", app 1920 x 720, density 180}
        """.trimIndent()
        assertEquals(null, discoverClusterDisplay(ambiguous))

        val topLevelOnly = """
            DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface": uniqueId="virtual:cluster"}
            Display 0:
              mDisplayId=0
              mPhysicalDisplayId=19261206365013889
              mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", app 1920 x 1080, density 240}
            Display 1:
              mDisplayId=1
              mBaseDisplayInfo=DisplayInfo{"launcher-split, displayId 1", app 1284 x 1080, density 240}
        """.trimIndent()
        assertEquals(null, discoverClusterDisplay(topLevelOnly))
    }

    @Test
    fun `BYD physical display id is ignored and named cluster display discovery is exact`() {
        val physicalDisplayOnly = """
            Display 0:
              mDisplayId=0
              DisplayDeviceInfo{"Built-in Screen", uniqueId="local:19261206365013889"}
              mPhysicalDisplayId=19261206365013889
        """.trimIndent()

        val parsed = AndroidObservedStateParser.parse(
            RawObservation("", "Display: mDisplayId=0", physicalDisplayOnly, "10", "1\n1\n1", ""),
        )
        assertTrue(parsed is ObservationValue.Unknown)
        assertEquals(null, discoverClusterDisplayId(physicalDisplayOnly))

        val oneCluster = physicalDisplayOnly + "\n" + clusterDisplayDump(2 to "fission")
        assertEquals(2, discoverClusterDisplayId(oneCluster))
        val known = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(2, "com.example.maps/.MainActivity"),
                "Display: mDisplayId=2",
                oneCluster,
                "10",
                "1.0\n0.5\n1.0",
                "Package com.example.maps:\n  PICTURE_IN_PICTURE: allow",
            ),
        ) as ObservationValue.Known
        assertEquals(2, known.value.target!!.displayId)

        val multipleClusters = oneCluster + "\n" + clusterDisplayDump(3 to "xdja")
        assertEquals(null, discoverClusterDisplayId(multipleClusters))
        val mixedMalformed = oneCluster + "\nDisplay 3: trailing-garbage"
        assertEquals(null, discoverClusterDisplayId(mixedMalformed))

        val malformedHeader = clusterDisplayDump(2 to "ordinary") + "\n" + """
            Display not-a-number:
              DisplayDeviceInfo{"fission", uniqueId="virtual:fission"}
        """.trimIndent()
        assertEquals(null, discoverClusterDisplayId(malformedHeader))

        val overflowingHeader = """
            Display 19261206365013889:
              DisplayDeviceInfo{"fission", uniqueId="virtual:fission"}
        """.trimIndent()
        assertEquals(null, discoverClusterDisplayId(overflowingHeader))
    }

    @Test
    fun `parser fails closed when display bounds overflow Int`() {
        val parsed = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(2, "com.example.maps/.Main visible=true"),
                "Display: mDisplayId=2 mBounds=Rect(0, 0 - 19261206365013889, 720) density=180",
                clusterDisplayDump(2 to "fission"),
                "10",
                "1\n1\n1",
                "Package com.example.maps:\n  PICTURE_IN_PICTURE: allow",
            ),
        )
        assertTrue(parsed is ObservationValue.Unknown)
    }

    @Test
    fun `named display requires authoritative matching same-block base info geometry and density`() {
        val deviceInfoOnly = """
            Display 2:
              mDisplayId=2
              DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface", uniqueId="virtual:cluster"}
        """.trimIndent()
        val wrongEmbeddedId = clusterDisplayDump(2 to "fission_bg_xdjaVirtualSurface")
            .replace("displayId 2", "displayId 3")
        val missingGeometry = clusterDisplayDump(2 to "fission_bg_xdjaVirtualSurface")
            .replace(", app 1920 x 720, density 180", "")
        assertEquals(null, discoverClusterDisplay(deviceInfoOnly))
        assertEquals(null, discoverClusterDisplay(wrongEmbeddedId))
        assertEquals(null, discoverClusterDisplay(missingGeometry))
    }

    @Test
    fun `raw observation order shares one absolute deadline and emits no command after expiry`() {
        var now = 1_000L
        val kinds = mutableListOf<CommandKind>()
        val deadlines = mutableListOf<Long>()
        val gateway = object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest): ShellResult {
                kinds += request.kind
                deadlines += request.deadlineMillis
                now += 100L
                return ShellResult.Success("known", "", 100L)
            }
            override fun close() = Unit
        }
        val reader = ObservedStateReader(gateway, ObservedStateParser { error("parser must not run") }) { now }

        val result = reader.inspectRaw(1_350L)

        assertTrue(result is ObservationValue.Unknown)
        assertEquals(
            listOf(
                CommandKind.AM_STACK_LIST,
                CommandKind.WM_DISPLAYS,
                CommandKind.DISPLAY_STATE,
                CommandKind.PROFILE_STATE,
            ),
            kinds,
        )
        assertEquals(listOf(350L, 250L, 150L, 50L), deadlines)
    }

    @Test
    fun `phone session parser requires explicit connected or disconnected marker`() {
        assertEquals(true, ProjectionSessionEvidenceParser.parse("mPhoneConnected=true"))
        assertEquals(false, ProjectionSessionEvidenceParser.parse("connectionState=DISCONNECTED"))
        assertEquals(null, ProjectionSessionEvidenceParser.parse("service running package=carplay"))
    }

    @Test
    fun `Android V2 gateway owns ephemeral DADB and contains no legacy transport or move-stack`() {
        val source = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastAndroidRuntime.kt")
        assertTrue(source.contains("Dadb.create"))
        assertTrue(source.contains("task.get(timeoutMillis"))
        assertTrue(source.contains("TimeoutException"))
        assertTrue(source.contains("fenceInFlight"))
        assertTrue(source.contains("activeSessions"))
        assertTrue(source.contains("ThreadPoolExecutor("))
        assertTrue(source.contains("ArrayBlockingQueue(4)"))
        assertTrue(source.contains("RejectedExecutionException"))
        assertTrue(source.contains("callSession.getAndSet(null)"))
        assertTrue(source.contains("isDisplayClean(adb, id, c)"))
        assertTrue(source.contains("CastAmStackParser.isKnownCleanDisplay"))
        assertFalse(source.contains("line.contains(\"taskId\""))
        assertFalse(source.contains("newCachedThreadPool"))
        assertTrue(source.contains("AdbKeys.ensure(context)"))
        assertFalse(source.contains("DadbBridge"))
        assertFalse(source.contains("modules.clustercast.ClusterCast"))
        assertTrue(source.contains("unsupported, unresolved, or fenced mutation"))
        assertTrue(source.contains("CastPlacementCommands.of("))
        // The in-process DisplayManager fallback keeps placement usable when the dump wording changes.
        assertTrue(source.contains("measuredCluster = { CastInProcessDisplay.measure(context) }"))
        assertTrue(source.contains("AndroidObservedStateParser.withFallback { CastInProcessDisplay.measure(app) }"))

        val placement = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastPlacementCommands.kt")
        // The reparent rung exists exactly once and never moves a stack back to display 0.
        assertEquals(1, Regex("am display move-stack").findAll(placement).count())
        assertTrue(placement.contains("am display move-stack \$it \$display"))
        assertFalse(placement.contains("move-stack \$it 0"))
        assertTrue(placement.contains("--windowingMode \$FREEFORM_MODE"))
        assertTrue(placement.contains("force_resizable_activities 0"))
        assertTrue(placement.contains("appops set \$it PICTURE_IN_PICTURE ignore"))
        assertTrue(placement.contains("am task resize"))
        assertTrue(placement.contains("am start --display 0"))
        // clear-task exists only on the destructive rung, and never on a session-preserving launch.
        assertEquals(1, Regex("--activity-clear-task").findAll(placement).count())
        assertTrue(placement.contains("CommandKind.START_FRESH_NORMAL"))
    }

    @Test
    fun `fixed bootstrap command surface is exactly six byte-exact strings`() {
        val expected = mapOf(
            CommandKind.SEAL_DL3_BOOTSTRAP_30 to "service call AutoContainer 2 i32 1000 i32 30 s16 \"\"",
            CommandKind.SEAL_DL3_BOOTSTRAP_31 to "service call AutoContainer 2 i32 1000 i32 31 s16 \"\"",
            CommandKind.SEAL_DL3_BOOTSTRAP_16 to "service call AutoContainer 2 i32 1000 i32 16 s16 \"\"",
            CommandKind.SEAL_DL3_BOOTSTRAP_35 to "service call AutoContainer 2 i32 1000 i32 35 s16 \"\"",
            CommandKind.SEAL_DL3_COMPENSATE_18 to "service call AutoContainer 2 i32 1000 i32 18 s16 \"\"",
            CommandKind.SEAL_DL3_COMPENSATE_0 to "service call AutoContainer 2 i32 1000 i32 0 s16 \"\"",
        )
        assertEquals(expected, CommandKind.entries.mapNotNull { kind ->
            fixedSealDl3BootstrapCommand(kind)?.let { kind to it }
        }.toMap())

        val source = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastAndroidRuntime.kt")
        assertTrue(source.contains("fixedSealDl3BootstrapCommand(kind: CommandKind)"))
        assertFalse(source.contains("fixedSealDl3BootstrapCommand(kind: CommandKind,"))
        val placement = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastPlacementCommands.kt")
        val fixedRoute = placement.indexOf("fixedSealDl3BootstrapCommand(request.kind)?.let { return it }")
        val discovery = placement.indexOf("val display = discoverCastDisplay(adb, cancelled)", fixedRoute)
        assertTrue(fixedRoute >= 0 && discovery > fixedRoute)
    }

    @Test
    fun `named display truth and exact Android build facts reach the runtime boundary`() {
        val parsed = AndroidObservedStateParser.parse(
            RawObservation(
                amStack(0, "com.android.launcher3/.Launcher visible=true"),
                "Display: mDisplayId=2 bounds=[0,0][1920,720] density=180",
                clusterDisplayDump(2 to "xdja"), "10", "1\n1\n1", "",
            ),
        ) as ObservationValue.Known
        assertEquals("display-2", parsed.value.displayIdentity)
        assertEquals("xdja", parsed.value.displayName)

        val source = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastAndroidRuntime.kt")
        listOf(
            "Build.VERSION.SDK_INT", "Build.MANUFACTURER", "Build.BRAND",
            "Build.PRODUCT, Build.DEVICE, Build.PRODUCT",
        ).forEach { assertTrue(source.contains(it), it) }
    }

    @Test
    fun `per-call timeout and Stop fence cannot dispatch late mutation or cancel unrelated reads`() {
        val source = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastAndroidRuntime.kt")
        val create = source.indexOf("val adb = Dadb.create")
        val postCreateCheck = source.indexOf("if (cancelled())", create)
        val successDispatch = source.indexOf("success(adb, cancelled)", postCreateCheck)
        val mutationCheck = source.indexOf(
            "if (cancelled()) return@withDadbBounded MutationResult.UnknownEffect(\"mutation fenced before dispatch\")",
        )
        val mutationShell = source.indexOf("val result = adb.shell(command)", mutationCheck)
        val timeoutCatch = source.indexOf("catch (_: TimeoutException)")
        val timeoutCancellation = source.indexOf("callCancelled.set(true)", timeoutCatch)
        val timeoutReturn = source.indexOf("timeout()", timeoutCancellation)
        val fenceStart = source.indexOf("override fun fenceInFlight()")
        val fenceEnd = source.indexOf("override fun close()", fenceStart)
        val fence = source.substring(fenceStart, fenceEnd)
        assertTrue(source.indexOf("val callCancelled = AtomicBoolean(false)") in 0 until create)
        assertTrue(postCreateCheck > create && successDispatch > postCreateCheck)
        assertTrue(mutationCheck >= 0 && mutationShell > mutationCheck)
        assertTrue(timeoutCancellation > timeoutCatch && timeoutReturn > timeoutCancellation)
        assertFalse(source.substring(timeoutCatch, timeoutReturn).contains("mutationGeneration.incrementAndGet()"))
        assertTrue(fence.contains("activeMutationTasks") && fence.contains("activeMutationSessions"))
        assertFalse(fence.contains("activeTasks.forEach") || fence.contains("activeSessions.forEach"))
        assertTrue(source.contains("result.exitCode == 0 && result.errorOutput.isBlank()"))
        assertTrue(source.contains("result.exitCode != 0 || result.errorOutput.isNotBlank()"))
    }

    private fun source(relative: String): String {
        val current = Path.of(System.getProperty("user.dir"))
        val app = if (Files.exists(current.resolve("src"))) current else current.resolve("app")
        return app.resolve("src").resolve(relative).toFile().readText()
    }

    private fun amStack(displayId: Int, vararg taskBodies: String): String =
        taskBodies.mapIndexed { index, body ->
            "Stack id=${100 + index} bounds=[0,0][1920,720] displayId=$displayId userId=0\n" +
                "  taskId=${7 + index}: $body"
        }.joinToString("\n")

    private fun clusterDisplayDump(vararg displays: Pair<Int, String>): String =
        displays.joinToString("\n") { (id, name) ->
            """
                Display $id:
                  mDisplayId=$id
                  mBaseDisplayInfo=DisplayInfo{"$name, displayId $id", app 1920 x 720, density 180}
            """.trimIndent()
        }
}
