package com.byd.clusternav.offcar

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExpansionTransportFenceTest {
    @TempDir
    lateinit var temp: Path

    private val root: Path get() = Path.of(System.getProperty("clusternav.root")).toAbsolutePath().normalize()

    @Test
    fun `all 29 expansion current paths remain exact authorized and materialized`() {
        val activeSource = classPaths(authorityRevisions().last(), "SOURCE_SEAL_INPUT").toSet()
        assertEquals(29, CURRENT_PATHS.size)
        assertEquals(CURRENT_PATHS.size, CURRENT_PATHS.distinct().size)
        CURRENT_PATHS.forEach { relative ->
            assertFalse(relative.startsWith('/') || relative.contains("..") || relative.contains('*'), relative)
            val path = root.resolve(relative).normalize()
            assertTrue(path.startsWith(root), relative)
            assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), relative)
            assertTrue(relative in activeSource, "expansion CURRENT is not authorized: $relative")
        }

        val spec = Files.readString(root.resolve(SPEC_PATH))
        val currentBlock = requireNotNull(
            Regex("<h4>CURRENT X0–X5</h4><pre><code>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL).find(spec),
        ).groupValues[1].lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        assertEquals(CURRENT_PATHS, currentBlock)
        assertEquals(SOURCE_ARTIFACT_PATHS, CURRENT_PATHS.filter { it in SOURCE_ARTIFACT_PATHS })

        val output = root.resolve(ExpansionPackRenderer.OUTPUT_DIRECTORY)
        val outputPaths = Files.list(output).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { root.relativize(it).toString() }.toList().toSet()
        }
        assertEquals(CURRENT_PATHS.filter { it.startsWith("$OUTPUT_DIRECTORY/") }.toSet(), outputPaths)
        assertEquals(ExpansionPackRenderer.OUTPUT_NAMES, outputPaths.map { Path.of(it).fileName.toString() }.toSet())
    }

    @Test
    fun `boundary authority is canonical append only exact and independently hashed`() {
        val path = root.resolve(BOUNDARY_PATH)
        val bytes = Files.readAllBytes(path)
        val authority = X4Json.asObject(X4Json.parse(bytes))
        assertArrayEquals(bytes, X4Json.canonical(authority))
        assertEquals(setOf("activeRevision", "revisions", "schemaId", "selfSha256"), authority.keys)
        assertEquals(2L, authority["activeRevision"])
        assertEquals("clusternav.offcar-boundary-revisions/v1", authority["schemaId"])
        val rootProjection = authority.toMutableMap().also { it.remove("selfSha256") }
        assertEquals(authority["selfSha256"], X4Json.sha256(X4Json.canonical(rootProjection)))

        val revisions = authorityRevisions()
        assertEquals(listOf(1L, 2L), revisions.map { it["revision"] })
        var predecessor: String? = null
        revisions.forEachIndexed { index, revision ->
            assertEquals(
                setOf("legacyParentBaselineSha256", "pathClasses", "predecessorRevisionSha256", "revision", "revisionSha256"),
                revision.keys,
            )
            assertEquals(predecessor, revision["predecessorRevisionSha256"])
            val projection = revision.toMutableMap().also { it.remove("revisionSha256") }
            val declared = X4Json.string(revision.getValue("revisionSha256"))
            assertEquals(declared, X4Json.sha256(X4Json.canonical(projection)))
            predecessor = declared
            val classes = X4Json.asObject(revision.getValue("pathClasses"))
            classes.forEach { (name, raw) ->
                val value = X4Json.asObject(raw)
                assertEquals(setOf("paths", "policyTokens"), value.keys, name)
                listOf("paths", "policyTokens").forEach { field ->
                    val items = X4Json.strings(value.getValue(field))
                    assertEquals(items.distinct().sorted(), items, "$name.$field")
                }
                classPaths(revision, name).forEach { relative ->
                    assertFalse(relative.startsWith('/') || relative.contains('\\') || relative.split('/').any { it.isEmpty() || it == "." || it == ".." } || relative.any { it in "*?[]" }, relative)
                }
            }
            val expected = if (index == 0) setOf("CURRENT", "FUTURE_T10", "FUTURE_T11")
                else setOf("FUTURE_FORBIDDEN", "LOCAL_IGNORED", "POST_BUILD_ATTESTATION", "SOURCE_SEAL_INPUT")
            assertEquals(expected, classes.keys)
        }
        assertEquals(REVISION_ONE_SHA256, revisions.first()["revisionSha256"])
        assertEquals(HISTORICAL_PARENT_BASELINE, revisions.first()["legacyParentBaselineSha256"])
        assertEquals(LegacyBaselineIdentity.PARENT_BASELINE_SHA256, revisions.last()["legacyParentBaselineSha256"])

        val source = classPaths(revisions.last(), "SOURCE_SEAL_INPUT").toSet()
        val post = classPaths(revisions.last(), "POST_BUILD_ATTESTATION").toSet()
        val local = classPaths(revisions.last(), "LOCAL_IGNORED").toSet()
        val forbidden = classPaths(revisions.last(), "FUTURE_FORBIDDEN").toSet()
        assertEquals(155, source.size)
        assertEquals(POST_BUILD_PATHS, post)
        assertEquals(setOf(".authorized-build", ".t10-local", "keystore.properties", "release.keystore"), local)
        assertEquals(setOf("POLICY-T10-APK-ARTIFACT-NAME"), classTokens(revisions.last(), "LOCAL_IGNORED").toSet())
        assertEquals(T11_PATHS, forbidden)
        assertTrue(source.intersect(post + forbidden + local).isEmpty())
        assertTrue(post.intersect(forbidden + local).isEmpty())
        assertTrue(forbidden.intersect(local).isEmpty())
        assertEquals(T10_SOURCE_PATHS, source.filter(::isT10InventoryPath).toSet())
        assertTrue(classPaths(revisions.first(), "CURRENT").all(source::contains))
        assertTrue(CURRENT_PATHS.all(source::contains))
    }

    @Test
    fun `parent baseline and T11 retain exact hashes while authorized T10 may be absent`() {
        val baseline = LegacyBaselineIdentity.capture(root)
        assertEquals(LegacyBaselineIdentity.PARENT_BASELINE_SHA256, baseline.parentCombinedSha256)
        assertEquals(PARENT_ARTIFACT_HASHES, baseline.artifacts.associate { it.path to it.fullSha256 })

        val checkedBaseline = X4Json.asObject(
            X4Json.parse(Files.readAllBytes(root.resolve("$OUTPUT_DIRECTORY/legacy-baseline.json"))),
        )
        val checkedArtifacts = X4Json.array(checkedBaseline.getValue("artifacts")).associate { value ->
            val artifact = X4Json.asObject(value)
            X4Json.string(artifact.getValue("path")) to X4Json.string(artifact.getValue("fullSha256"))
        }
        assertEquals(PARENT_ARTIFACT_HASHES, checkedArtifacts)
        assertEquals(LegacyBaselineIdentity.PARENT_BASELINE_SHA256, checkedBaseline["parentCombinedSha256"])

        assertEquals(13, T11_PATHS.size)
        assertEquals(2, T11_HASHES.size)
        T11_PATHS.forEach { relative ->
            val path = root.resolve(relative).normalize()
            assertTrue(path.startsWith(root), relative)
            val expected = T11_HASHES[relative]
            if (expected == null) assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "T11 path must remain absent: $relative")
            else {
                assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), relative)
                assertEquals(expected, sha256(Files.readAllBytes(path)), relative)
            }
        }
        (T10_SOURCE_PATHS + POST_BUILD_PATHS).forEach { relative ->
            val candidate = root.resolve(relative).normalize()
            assertTrue(candidate.startsWith(root), relative)
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                assertTrue(Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS), "authorized T10 path is not a regular file: $relative")
            }
        }
    }

    @Test
    fun `generation has no side effects outside the 12 expansion outputs`() {
        val before = repositorySnapshotOutsideOutputs()
        val parentBefore = LegacyBaselineIdentity.parentCombinedSha256(root)
        val t11Before = T11_HASHES.mapValues { (relative, _) -> sha256(Files.readAllBytes(root.resolve(relative))) }
        val result = ExpansionPackRenderer(root).writePack(temp.resolve("generated"))
        val after = repositorySnapshotOutsideOutputs()

        assertEquals(before, after)
        assertEquals(parentBefore, LegacyBaselineIdentity.parentCombinedSha256(root))
        assertEquals(t11Before, T11_HASHES.mapValues { (relative, _) -> sha256(Files.readAllBytes(root.resolve(relative))) })
        assertEquals(ExpansionPackRenderer.OUTPUT_NAMES, result.files.keys)
        CURRENT_PATHS.filter { it.startsWith("$OUTPUT_DIRECTORY/") }.forEach { relative ->
            val name = Path.of(relative).fileName.toString()
            assertArrayEquals(Files.readAllBytes(root.resolve(relative)), result.files.getValue(name), relative)
        }
    }

    @Test
    fun `expansion planner source and compiled symbols contain no transport surface`() {
        val sourceBan = Regex(
            "(?i)(?<![A-Za-z0-9_])(?:Process|Runtime|network|socket|DADB|CarExec|Android|device|callback|execute)[A-Za-z0-9_]*(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])ADB(?![A-Za-z0-9_])",
        )
        IMPLEMENTATION_SOURCES.forEach { relative ->
            val source = Files.readString(root.resolve(relative)).replace("device-width", "")
            val match = sourceBan.find(source)
            assertTrue(match == null, "$relative contains forbidden source symbol ${match?.value}")
        }

        val classesRoot = root.resolve("offcar-planner/build/classes/kotlin/main/com/byd/clusternav/offcar")
        assertTrue(Files.isDirectory(classesRoot), "compiled planner classes are required")
        val sourceNames = IMPLEMENTATION_SOURCES.map { Path.of(it).fileName.toString() }.toSet()
        val coveredSources = mutableSetOf<String>()
        var compiledClasses = 0
        Files.walk(classesRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.forEach { path ->
                val bytes = Files.readAllBytes(path)
                val sourceName = sourceNames.singleOrNull { bytes.containsUtf8(it) } ?: return@forEach
                coveredSources += sourceName
                compiledClasses++
                val symbols = String(bytes, StandardCharsets.ISO_8859_1).replace("device-width", "")
                COMPILED_BANS.forEach { ban ->
                    assertFalse(ban.containsMatchIn(symbols), "$path contains forbidden compiled symbol ${ban.pattern}")
                }
            }
        }
        assertEquals(sourceNames, coveredSources)
        assertTrue(compiledClasses >= sourceNames.size, "every implementation source must produce a scanned class")
    }

    @Test
    fun `verifier is argument-free hardened offline and has exact fixed gate mappings`() {
        val verifier = root.resolve(VERIFIER_PATH)
        assertTrue(Files.isExecutable(verifier), "verifier must be executable")
        val text = Files.readString(verifier)
        val lines = text.lineSequence().toList()
        assertEquals("#!/bin/bash -p", lines.first())
        assertEquals("set -euo pipefail", lines[1])
        listOf(
            "if (( $# != 0 )); then", "CLUSTERNAV_EXPANSION_GATE+set", "unset CLUSTERNAV_EXPANSION_GATE",
            "Kernel-dispatched privileged startup ignores BASH_ENV/ENV, imported shell functions",
            "SHELLOPTS/BASHOPTS/CDPATH/GLOBIGNORE before line 1",
            "ignored BASH_FUNC_* and option entries cannot propagate to any verifier child",
            "BOOTSTRAP_ENV=(", "/usr/bin/env -i", "/bin/bash -p -s", "CLUSTERNAV_VERIFIER_BODY",
            "GATE-X-O11 is the plain no-selector full verifier", "BASH_SOURCE[0]", "symbolic link component is forbidden",
            "PATH=\"/usr/bin:/bin\"", "unset TMPDIR TEMP TMP", "TEMP_BASE_LOGICAL=\"/tmp\"", "pwd -P",
            "CLUSTERNAV_OFFCAR_ONLY=1", "CLUSTERNAV_ALLOW_VEHICLE=0", "CLUSTERNAV_ALLOW_NETWORK=0",
            "GRADLE_OFFLINE=true", "--no-daemon", "--rerun-tasks", "--no-build-cache", "--console=plain",
            "Gradle test task was not freshly executed", "java\\.specification\\.version", "sys.version_info >= (3, 9)", "O_NOFOLLOW",
            "a5a5c199ba02189ae8c46a334223371a20599d9c298ef65e7540ede4a3f72d59",
            "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
            "556f4aa5f360e35fca77b010b306307765d4f4915a82a612035cd5cfb7a587cb",
            "semantic privacy fence failed", "output allowlist/no-follow mismatch", "/usr/bin/diff -ru",
        ).forEach { token -> assertTrue(text.contains(token), token) }
        assertTrue(text.indexOf("unset CLUSTERNAV_EXPANSION_GATE") < text.indexOf("run_selected_gate"))
        assertFalse(Regex("\\$(?:\\{)?(?:PATH|TMPDIR)").containsMatchIn(text), "inherited PATH/TMPDIR expansion")

        val mappings = linkedMapOf(
            "GATE-X-O1" to "LegacyBaselineIdentityTest", "GATE-X-O2" to "ExpansionDeterminismTest",
            "GATE-X-O4" to "ExpansionPromotionTest", "GATE-X-O5" to "DerivationClosureTest",
            "GATE-X-O6" to "ExpansionPromotionTest", "GATE-X-O7" to "AdaptivePruningTest",
            "GATE-X-O9" to "ExpansionDeterminismTest",
            "GATE-X-O10" to "ExpansionTransportFenceTest", "GATE-X-O12" to "ExpansionTraceabilityTest",
        )
        mappings.forEach { (gate, test) ->
            val dispatch = "$gate) run_gradle_test \"com.byd.clusternav.offcar.$test\" ;;"
            assertTrue(text.contains(dispatch), dispatch)
        }
        val o8 = "GATE-X-O8) run_gradle_test \"com.byd.clusternav.offcar.SameSessionQuarantineTest\" " +
            "\"com.byd.clusternav.offcar.LedgerSemanticValidationTest\"; verify_o8_test_count ;;"
        assertTrue(text.contains(o8), o8)
        assertTrue(text.contains("GATE-X-O8 must select exactly 9 tests"))
        assertTrue(text.contains("SameSessionQuarantineTest\": 3") && text.contains("LedgerSemanticValidationTest\": 6"))
        assertTrue(text.contains("GATE-X-O3) run_python_coverage_test ;;"))
        assertTrue(text.contains("if [[ \"\$SELECTED_GATE\" == \"GATE-X-O12\" ]]; then begin_output_checks; fi"))
        assertFalse(text.contains("GATE-X-O12) scripts/"), "O12 must not recurse")
        Regex("run_gradle_test \\\"([^\\\"]+)\\\"").findAll(text).forEach { match ->
            val command = ":offcar-planner:test --tests ${match.groupValues[1]}"
            assertFalse(Regex("(?i):(?:app|core|car-integration):|assemble|build|install|connected|device").containsMatchIn(command), command)
        }
        listOf("eval ", "bash -c", "sh -c", "curl ", "wget ", "ssh ", "nc ", "adb ", "dadb ", "command -v", "readlink -f")
            .forEach { assertFalse(text.contains(it, ignoreCase = true), it) }
    }

    @Test
    fun `verifier privileged startup rejects BASH_ENV functions options and invalid selectors`() {
        val attackerBin = temp.resolve("attacker-bin")
        Files.createDirectories(attackerBin)
        val fakeBash = attackerBin.resolve("bash")
        Files.writeString(fakeBash, "#!/bin/sh\nprintf 'HIJACKED_INTERPRETER\\n'\n")
        assertTrue(fakeBash.toFile().setExecutable(true))
        val startupMarker = temp.resolve("prestart-bypass.marker")
        val bashEnv = temp.resolve("malicious-bash-env.sh")
        Files.writeString(bashEnv, "printf 'BASH_ENV_PRESTART_BYPASS\\n' >>\"\$STARTUP_MARKER\"\nexit 0\n")
        val poisoned = mapOf(
            "PATH" to attackerBin.toString(), "TMPDIR" to temp.resolve("attacker-tmp").toString(),
            "CLASSPATH" to "attacker.jar", "JAVA_TOOL_OPTIONS" to "-javaagent:attacker.jar",
            "HTTP_PROXY" to "http://proxy.internal.example", "BASH_ENV" to bashEnv.toString(),
            "BASH_FUNC_printf%%" to "() { /bin/echo IMPORTED_FUNCTION_BYPASS >> \"\$STARTUP_MARKER\"; }",
            "SHELLOPTS" to "xtrace", "BASHOPTS" to "extdebug",
            "PS4" to "\$(/bin/echo SHELLOPTS_PRESTART_BYPASS >> \"\$STARTUP_MARKER\")",
            "STARTUP_MARKER" to startupMarker.toString(), "CDPATH" to temp.toString(), "GLOBIGNORE" to "*",
        )
        val expected = linkedMapOf(
            "" to "must not be empty", "GATE-X-O99" to "unknown CLUSTERNAV_EXPANSION_GATE",
            " GATE-X-O1" to "unknown CLUSTERNAV_EXPANSION_GATE", "GATE-X-O11" to "plain no-selector full verifier",
        )
        expected.forEach { (selector, message) ->
            Files.deleteIfExists(startupMarker)
            val result = runProcess(
                listOf(root.resolve(VERIFIER_PATH).toString()),
                poisoned + ("CLUSTERNAV_EXPANSION_GATE" to selector),
            )
            assertTrue(result.exitCode != 0, selector)
            assertTrue(result.output.contains(message), result.output)
            assertFalse(result.output.contains("HIJACKED_INTERPRETER"), result.output)
            assertFalse(result.output.contains("PRESTART_BYPASS") || result.output.contains("IMPORTED_FUNCTION_BYPASS"), result.output)
            assertFalse(Files.exists(startupMarker, LinkOption.NOFOLLOW_LINKS), "startup payload ran for $selector")
        }
    }

    @Test
    fun `verifier rejects invocation through a symbolic link ancestor`() {
        val linkedRoot = temp.resolve("linked-repository")
        Files.createSymbolicLink(linkedRoot, root)
        val result = runProcess(
            listOf("/bin/bash", linkedRoot.resolve(VERIFIER_PATH).toString()),
            mapOf("CLUSTERNAV_EXPANSION_GATE" to "GATE-X-O3", "PATH" to temp.resolve("poison").toString()),
        )
        assertTrue(result.exitCode != 0)
        assertTrue(result.output.contains("symbolic link component is forbidden"), result.output)
    }

    @Test
    fun `renderer rejects symbolic link ancestors and never follows an output leaf`() {
        val physical = temp.resolve("physical")
        Files.createDirectories(physical)
        val linked = temp.resolve("linked")
        Files.createSymbolicLink(linked, physical)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ExpansionPackRenderer(root).writePack(linked.resolve("pack"))
        }

        val physicalParent = temp.toRealPath().resolve("physical-parent")
        val physicalProject = physicalParent.resolve("project")
        Files.createDirectories(physicalProject)
        val linkedParent = temp.toRealPath().resolve("linked-parent")
        Files.createSymbolicLink(linkedParent, physicalParent)
        val linkedProject = linkedParent.resolve("project")
        assertFalse(Files.isSymbolicLink(linkedProject))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ExpansionPackRenderer(linkedProject).writePack(linkedProject.resolve(OUTPUT_DIRECTORY))
        }
        assertFalse(Files.exists(physicalProject.resolve("docs"), LinkOption.NOFOLLOW_LINKS))

        val output = temp.resolve("nofollow")
        Files.createDirectories(output)
        val protected = temp.resolve("protected.txt")
        Files.writeString(protected, "must remain unchanged")
        Files.createSymbolicLink(output.resolve(ExpansionPackRenderer.BASELINE), protected)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ExpansionPackRenderer(root).writePack(output)
        }
        assertEquals("must remain unchanged", Files.readString(protected))
    }

    @Test
    fun `semantic privacy scanner rejects negative fixtures permits fixed metadata and refuses links`() {
        val verifier = Files.readString(root.resolve(VERIFIER_PATH))
        val program = verifier.substringAfter("# PRIVACY_SCANNER_PROGRAM_BEGIN\n")
            .substringBefore("\n# PRIVACY_SCANNER_PROGRAM_END")
        assertTrue(program.startsWith("import ipaddress"))
        val scanner = temp.resolve("privacy-scanner.py")
        Files.writeString(scanner, program)
        val canonicalResult = runPrivacyScanner(scanner, root.resolve(OUTPUT_DIRECTORY))
        assertEquals(0, canonicalResult.exitCode, canonicalResult.output)
        val fixture = temp.resolve("privacy-fixture")
        Files.createDirectories(fixture)
        val file = fixture.resolve("rendered.txt")
        val negative = listOf(
            "api_key=sk-proj-abcdef", "VIN 1HGCM82633A004352",
            "serialNumber=SERIAL-42", "rawDump=payload", "sourceLine=42", "decompiledBody=text",
            "GPS 21.0285,105.8542", "public 8.8.8.8", "ipv6 2001:db8::1", "/Users/example/private.txt",
            "https://example.com/private", "person@example.com", "+84912345678", "name=Jane Citizen",
            "governmentId=123456789", "card 4111 1111 1111 1111", "build.internal.example",
            "FACT-8.8.8.8", "VERSION-8.8.8.8", "FACT-1HGCM82633A004352",
            "FACT-RAW-DUMP-PAYLOAD", "FACT-SOURCE-LINE-42", "FACT-SERIAL-NUMBER-42", "FACT-GPS-21.0285",
        )
        negative.forEach { value ->
            Files.writeString(file, value)
            val result = runPrivacyScanner(scanner, fixture)
            assertTrue(result.exitCode != 0, "fixture escaped scanner: $value")
        }
        val nested = fixture.resolve("nested.json")
        val nestedLeaks = listOf(
            "{\"outer\":{\"token\":\"opaque-private-credential\"}}",
            "{\"outer\":{\"passwordSha256\":\"password=CorrectHorseBatteryStaple\"}}",
            "{\"outer\":{\"passwordSha256\":null}}",
            "{\"outer\":{\"value\":\"PASSWORD-CORRECTHORSEBATTERYSTAPLE\"}}",
            "{\"outer\":{\"password\":\"PASSWORD-CORRECTHORSEBATTERYSTAPLE\"}}",
            "{\"outer\":{\"factId\":\"FACT-PASSWORD-CORRECTHORSEBATTERYSTAPLE\"}}",
        )
        nestedLeaks.forEach { value ->
            Files.writeString(nested, value)
            assertTrue(runPrivacyScanner(scanner, fixture).exitCode != 0, "nested leak escaped scanner: $value")
        }
        Files.delete(nested)

        Files.writeString(
            file,
            "https://clusternav.invalid/schema/result-ledger.schema.json " +
                "https://json-schema.org/draft/2020-12/schema ${"a".repeat(64)} FACT-SAFE-EVIDENCE TOKEN-C01-QUERY Đăng Khôi · dangkhoi",
        )
        assertEquals(0, runPrivacyScanner(scanner, fixture).exitCode)
        Files.delete(file)
        val outside = temp.resolve("outside.txt")
        Files.writeString(outside, "clean")
        Files.createSymbolicLink(fixture.resolve("linked.txt"), outside)
        val linkedResult = runPrivacyScanner(scanner, fixture)
        assertTrue(linkedResult.exitCode != 0)
        assertTrue(linkedResult.output.contains("symbolic link output"), linkedResult.output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runPrivacyScanner(scanner: Path, fixture: Path): ProcessResult {
        val python = listOf(
            Path.of("/usr/bin/python3"), Path.of("/opt/homebrew/opt/python@3.14/bin/python3.14"),
            Path.of("/usr/local/bin/python3"),
        ).firstOrNull(Files::isExecutable)?.toRealPath() ?: error("explicit Python 3 candidate is unavailable")
        return runProcess(listOf(python.toString(), "-I", scanner.toString(), fixture.toString()))
    }

    private fun runProcess(command: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private fun authorityRevisions(): List<Map<String, Any?>> = X4Json.array(X4Json.asObject(
        X4Json.parse(Files.readAllBytes(root.resolve(BOUNDARY_PATH)))).getValue("revisions")).map(X4Json::asObject)
    private fun classPaths(revision: Map<String, Any?>, name: String) = X4Json.strings(
        X4Json.asObject(X4Json.asObject(revision.getValue("pathClasses")).getValue(name)).getValue("paths"))
    private fun classTokens(revision: Map<String, Any?>, name: String) = X4Json.strings(
        X4Json.asObject(X4Json.asObject(revision.getValue("pathClasses")).getValue(name)).getValue("policyTokens"))

    private fun repositorySnapshotOutsideOutputs(): Map<String, String> = buildMap {
        Files.walk(root).use { paths ->
            paths.filter { path ->
                val relative = root.relativize(path)
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    !relative.toString().startsWith("$OUTPUT_DIRECTORY/") &&
                    relative.none { it.toString() in IGNORED_DIRECTORY_NAMES }
            }.forEach { path -> put(root.relativize(path).toString(), sha256(Files.readAllBytes(path))) }
        }
    }.toSortedMap()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun ByteArray.containsUtf8(value: String): Boolean { val target = value.toByteArray(StandardCharsets.UTF_8)
        return indices.any { start -> start + target.size <= size && target.indices.all { this[start + it] == target[it] } } }

    companion object {
        private const val SPEC_PATH = "docs/specs/seal-hud-sign-candidate-expansion.html"
        private const val OUTPUT_DIRECTORY = "docs/diagnostics/hud-sign-re/expansion"
        private const val VERIFIER_PATH = "scripts/verify-hud-sign-candidate-expansion.sh"

        private val IMPLEMENTATION_SOURCES = listOf(
            "offcar-planner/src/main/kotlin/com/byd/clusternav/offcar/ExpansionRegistry.kt",
            "offcar-planner/src/main/kotlin/com/byd/clusternav/offcar/DiscoveryProbe.kt",
            "offcar-planner/src/main/kotlin/com/byd/clusternav/offcar/ExpansionPackRenderer.kt",
            "offcar-planner/src/main/kotlin/com/byd/clusternav/offcar/ExpansionMain.kt",
        )
        private val SOURCE_ARTIFACT_PATHS = IMPLEMENTATION_SOURCES + listOf(
            "offcar-planner/src/main/resources/expansion-contracts.schema.json",
            "scripts/re/expand-candidate-coverage.py",
            VERIFIER_PATH,
        )
        private val CURRENT_PATHS = IMPLEMENTATION_SOURCES + listOf(
            "offcar-planner/src/main/resources/expansion-contracts.schema.json",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/LegacyBaselineIdentityTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/ExpansionPromotionTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/SameSessionQuarantineTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/AdaptivePruningTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/ExpansionDeterminismTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/ExpansionTransportFenceTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/DerivationClosureTest.kt",
            "offcar-planner/src/test/kotlin/com/byd/clusternav/offcar/ExpansionTraceabilityTest.kt",
            "scripts/re/expand-candidate-coverage.py", "scripts/re/tests/test_expand_candidate_coverage.py", VERIFIER_PATH,
            "$OUTPUT_DIRECTORY/legacy-baseline.json", "$OUTPUT_DIRECTORY/candidate-registry.json",
            "$OUTPUT_DIRECTORY/evidence-map.json", "$OUTPUT_DIRECTORY/candidate-diff.json",
            "$OUTPUT_DIRECTORY/candidate-expansion-report.html", "$OUTPUT_DIRECTORY/vehicle-session-plan.json",
            "$OUTPUT_DIRECTORY/vehicle-session-plan.txt", "$OUTPUT_DIRECTORY/vehicle-session-checklist.html",
            "$OUTPUT_DIRECTORY/result-ledger.schema.json", "$OUTPUT_DIRECTORY/pack-manifest.json",
            "$OUTPUT_DIRECTORY/traceability.json", "$OUTPUT_DIRECTORY/corpus-coverage.json", SPEC_PATH,
        )
        private const val BOUNDARY_PATH = "docs/diagnostics/hud-sign-re/offcar-boundary-revisions.json"
        private const val REVISION_ONE_SHA256 = "a05be7e4d6a521a81a285321754e8370dec4402e4406f2969727cd3863c46301"
        private const val HISTORICAL_PARENT_BASELINE = "8f636f508aaf89592ca676d85a8d13dbb8c7e9225112957d281dc1368901e1d4"
        private val PARENT_ARTIFACT_HASHES = linkedMapOf(
            "docs/diagnostics/hud-sign-re/README.md" to "f854683acbda36c69f899a5d3a3bdc326c0df966dc49b24059eb2d94ccb1ee46",
            "docs/diagnostics/hud-sign-re/candidate-report.html" to "af3c3db29e29b5c4cd4ebd0a0d4ef863de5a7a2d7f60c527bbe989596ce3eb36",
            "docs/diagnostics/hud-sign-re/corpus-completeness.json" to "87610a0e7a54e7d634dbcad8a423906a494e2add2e6e50b61828e1ee7217db79",
            "docs/diagnostics/hud-sign-re/evidence-index.json" to "ac3fd27701e6b05c5037594b35d314b49ddadaeb0315d8a64c3a3da0bef980b9",
            "docs/diagnostics/hud-sign-re/first-launch-emulator-result.json" to "e60e63dced72dbc0d742476883930088385c5812b29d176f145f42430540ae39",
            "docs/diagnostics/hud-sign-re/m1-nav-hud-plan.json" to "c8eff7210e093fa1b2e32328abeddf37fa51d919d524df851e4ecd634d289bf9",
            "docs/diagnostics/hud-sign-re/m2-hud-road-plan.json" to "dffb04d3e26beadd9fd7f813870fca6712d5f213f4145787280ddb4718437faf",
            "docs/diagnostics/hud-sign-re/m3-cluster-sign-plan.json" to "ac2de2631de73ab2ddd2ad4efa2f33e805fae2d8007091e2ae3497c64625fb7e",
            "docs/diagnostics/hud-sign-re/m4-hud-sign-plan.json" to "51fc71db1050baa816e9df2e1250a196c1d6e608a48df228e5318d6077c910d1",
            "docs/diagnostics/hud-sign-re/native/libbydcluster-diff.json" to "d2d7f63ee1916905e8ef21ea58242f22f4e2c02a5a1e0b3854766341f77e9464",
            "docs/diagnostics/hud-sign-re/traceability.json" to "332ae311ed642441c7ec8640fc4c389e1e7865813eafef8b1442645ff3791e60",
            "docs/diagnostics/hud-sign-re/zero-hit-report.txt" to "55acd8bf51a0baf9f397b8765162f063fc1686d9544b3fdbc83eaa96955f273f",
            "docs/specs/seal-nav-hud-speed-sign-offcar.html" to "781ff2b47f38d51deec66a47464ab78f37d781970499fdc64b86386423a28f87",
        )
        private fun pathSet(raw: String) = raw.split('|').toSet()
        private val T10_SOURCE_PATHS = pathSet(".gitignore|app/build.gradle.kts|app/src/test/java/com/byd/clusternav/BuildArtifactNamingTest.kt|app/src/test/java/com/byd/clusternav/MainProbeSurfaceAbsenceTest.kt|app/src/testVehicleTest/java/com/byd/clusternav/VehicleTestSurfaceContractTest.kt|app/src/vehicleTest/AndroidManifest.xml|app/src/vehicleTest/java/com/byd/clusternav/HudSignProbeActivity.kt|app/src/vehicleTest/java/com/byd/clusternav/HudSignProbeReceiver.kt|car-integration/build.gradle.kts|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/DadbVehicleTransport.kt|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/HudSignSessionRunner.kt|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/T10LocalAuthorization.kt|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/T10ResultStore.kt|car-integration/src/main/kotlin/com/byd/clusternav/vehicleprobe/T10RunnerMain.kt|car-integration/src/test/kotlin/com/byd/clusternav/vehicleprobe/DadbVehicleTransportTest.kt|car-integration/src/test/kotlin/com/byd/clusternav/vehicleprobe/HudSignSessionRunnerTest.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecClusterDiagnosticsCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecClusterLifecycleCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecClusterProjectionCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecHudCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecModels.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecNavigationCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/CarExecSpeedSignCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/T10FixedOperationCatalog.kt|core/src/main/kotlin/com/byd/clusternav/carexec/T10RollbackExecutor.kt|core/src/main/kotlin/com/byd/clusternav/carexec/T10SessionEngine.kt|core/src/test/kotlin/com/byd/clusternav/carexec/CarExecCatalogTest.kt|core/src/test/kotlin/com/byd/clusternav/carexec/T10SessionSafetyTest.kt|docs/_handoff/session-2026-08-10-hud-sign-t10-offcar-complete.md|docs/_handoff/session-2026-08-10-hud-sign-t10-preparation.md|gradle/authorized-apk-tasks.gradle.kts|gradle/exact-source-tasks.gradle.kts|scripts/evidence/gen-exact-source.py|scripts/evidence/tests/test_hud_sign_t10_evidence.py|scripts/evidence/verify-hud-sign-t10.py|scripts/vehicle/run-seal-hud-sign-matrix.sh|scripts/verify-seal-hud-sign-vehicle-test-t10.sh|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Canonical.kt|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Identity.kt|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Ledger.kt|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Session.kt|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/T10Transport.kt|vehicle-contracts/src/main/resources/t10-contracts.schema.json|vehicle-contracts/src/test/kotlin/com/byd/clusternav/vehicle/t10/T10ContractsTest.kt")
        private val POST_BUILD_PATHS = pathSet("docs/_handoff/hud-sign-t10-exact-source.json|docs/_handoff/hud-sign-vehicle-test-candidate.json|docs/diagnostics/hud-sign-re/vehicle/d-h0-hud-physical-temp-result.json|docs/diagnostics/hud-sign-re/vehicle/d-m1-nav-hud-result.json|docs/diagnostics/hud-sign-re/vehicle/d-m2-hud-road-result.json|docs/diagnostics/hud-sign-re/vehicle/d-m3-cluster-sign-result.json|docs/diagnostics/hud-sign-re/vehicle/d-m4-hud-sign-result.json")
        private val T11_PATHS = pathSet("app/src/main/java/com/byd/clusternav/vehicle/BydPropertyGateway.kt|app/src/main/java/com/byd/clusternav/vehicle/ClusterSpeedSignPort.kt|app/src/main/java/com/byd/clusternav/vehicle/HudSignSettingsController.kt|app/src/main/java/com/byd/clusternav/vehicle/HudSpeedSignPort.kt|app/src/main/java/com/byd/clusternav/vehicle/HudVehicleProfile.kt|app/src/main/java/com/byd/clusternav/vehicle/SpeedSignVehicleProfile.kt|app/src/main/res/layout/activity_main.xml|app/src/main/res/values/strings.xml|docs/_handoff/hud-sign-release-candidate.json|docs/diagnostics/hud-sign-re/vehicle/p-m1-nav-hud-result.json|docs/diagnostics/hud-sign-re/vehicle/p-m2-hud-road-result.json|docs/diagnostics/hud-sign-re/vehicle/p-m3-cluster-sign-result.json|docs/diagnostics/hud-sign-re/vehicle/p-m4-hud-sign-result.json")
        private val T11_HASHES = mapOf(
            "app/src/main/res/layout/activity_main.xml" to "f9e9c3fb78c9d4932cf0ae9732af45f1141386b80078c37e389cf4ad58ce0d6a",
            "app/src/main/res/values/strings.xml" to "7e5113f77066841ed08416c2b305f8f4f62d02f2ed78e99edaa8320df3e0ec9f",
        )
        private val T10_PREFIXES = "app/src/vehicleTest/|app/src/testVehicleTest/|car-integration/|core/src/main/kotlin/com/byd/clusternav/carexec/|core/src/test/kotlin/com/byd/clusternav/carexec/|gradle/|scripts/evidence/|scripts/vehicle/|vehicle-contracts/src/main/kotlin/com/byd/clusternav/vehicle/t10/|vehicle-contracts/src/test/kotlin/com/byd/clusternav/vehicle/t10/".split('|')
        private val T10_FIXED = pathSet(".gitignore|app/build.gradle.kts|app/src/test/java/com/byd/clusternav/BuildArtifactNamingTest.kt|app/src/test/java/com/byd/clusternav/MainProbeSurfaceAbsenceTest.kt|docs/_handoff/session-2026-08-10-hud-sign-t10-offcar-complete.md|docs/_handoff/session-2026-08-10-hud-sign-t10-preparation.md|scripts/verify-seal-hud-sign-vehicle-test-t10.sh|vehicle-contracts/src/main/resources/t10-contracts.schema.json")
        private fun isT10InventoryPath(path: String) = path in T10_FIXED || T10_PREFIXES.any(path::startsWith)
        private val IGNORED_DIRECTORY_NAMES = setOf(".git", ".gradle", ".idea", ".authorized-build", ".t10-local", "build", "node_modules")
        private val COMPILED_BANS = listOf(
            Regex("java/lang/Process(?:Builder)?(?:[^A-Za-z0-9_]|$)"), Regex("java/lang/Runtime(?:[^A-Za-z0-9_]|$)"),
            Regex("java/net/|java/nio/channels/(?:Server)?Socket", RegexOption.IGNORE_CASE),
            Regex("(?i)(?:^|[/.$;_<>()-])(?:network|socket|adb|dadb|device|callback|execute)(?:$|[/.$;_<>()-])"),
            Regex("(?i)carexec|android/|dalvik/|dadbvehicle|adbtransport"),
        )
    }
}
