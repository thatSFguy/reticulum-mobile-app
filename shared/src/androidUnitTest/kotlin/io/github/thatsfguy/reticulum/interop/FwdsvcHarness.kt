package io.github.thatsfguy.reticulum.interop

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString

/**
 * End-to-end interop harness against a real upstream rnsd + the
 * sibling [reticulum-forwarding-service] Go binary, ported from
 * `../reticulum-forwarding-service/tests/interop/harness_test.go`.
 *
 * Goal: prove our Kotlin engine speaks the same wire protocol as the
 * canonical Python RNS stack and as fwdsvc. The Go harness drives
 * fwdsvc with Python case scripts; we drive it with our own
 * [io.github.thatsfguy.reticulum.engine.ReticulumEngine] over a
 * [io.github.thatsfguy.reticulum.transport.TcpInterface] attached to a
 * spawned rnsd on loopback. Topology per test:
 *
 *     [Kotlin engine] -- TCP --> [rnsd:port] <-- TCP -- [fwdsvc]
 *
 * Each test calls [startOrSkip]; if any prerequisite is missing
 * (rnsd not on PATH, fwdsvc binary not present, Python RNS+LXMF not
 * importable) the call returns null and the test should
 * `assumeTrue(harness != null)` to skip cleanly — same contract as
 * [PythonPeer.startOrSkip].
 *
 * Per-test isolation: a fresh tempdir and fresh fwdsvc subprocess
 * per harness instance; the Go harness shares rnsd across cases for
 * speed but our JVM tests pay the cost (~1–2s) per case to keep the
 * setup obviously correct and avoid the static-shared-state foot-gun.
 *
 * Lifecycle: [startOrSkip] returns a started harness; callers use
 * `harness.use { ... }`. [close] destroys both subprocesses and dumps
 * their captured stdout/stderr to System.out if [logOnClose] was set
 * (call [armLogDump] from a test that's about to assert, then unset
 * via [disarmLogDump] only after a green assertion).
 */
class FwdsvcHarness private constructor(
    val rnsdHost: String,
    val rnsdPort: Int,
    val fwdsvcDeliveryHashHex: String,
    private val rnsd: Spawn,
    private val fwdsvc: Spawn,
    private val tempRoot: File,
) : Closeable {

    @Volatile private var logOnClose: Boolean = true

    /** Call after a test's assertions pass to suppress the post-mortem
     *  log dump on [close]. Default behaviour is to dump — that way a
     *  test that throws mid-way still produces diagnostics. */
    fun disarmLogDump() { logOnClose = false }

    /** Block until something accepts on `rnsdHost:rnsdPort` (used by
     *  the test driver to confirm rnsd's listener is ready before
     *  attaching the engine's TCP transport). Throws on timeout. */
    fun waitForRnsdListen(timeoutMs: Long = 15_000) {
        waitForListen(rnsdHost, rnsdPort, timeoutMs)
    }

    override fun close() {
        runCatching { fwdsvc.kill() }
        runCatching { rnsd.kill() }
        if (logOnClose) {
            println("---- rnsd output ----\n${rnsd.captured()}")
            println("---- fwdsvc output ----\n${fwdsvc.captured()}")
        }
        runCatching { tempRoot.deleteRecursively() }
    }

    /** A subprocess + its log capture threads + a kill closure. */
    internal class Spawn(
        val process: Process,
        private val logBuf: StringBuilder,
        private val logLock: Any,
    ) {
        fun kill() {
            process.destroy()
            // 2s grace, then force. Windows Process.destroy on JVM 17
            // is forcible already, but the Linux branch isn't, so be
            // explicit. waitFor(timeout, unit) is API 26+.
            val gone = runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
                .getOrDefault(false)
            if (!gone) process.destroyForcibly()
        }
        fun captured(): String { synchronized(logLock) { return logBuf.toString() } }
    }

    companion object {
        private val DELIVERY_RE: Pattern =
            Pattern.compile("""delivery destination\s*:\s*([0-9a-f]{32})""")

        /**
         * Start rnsd + fwdsvc and wait until fwdsvc has logged its
         * delivery destination hash. Returns null if any prerequisite
         * is missing (test should skip in that case). Throws only on
         * genuine startup failure (e.g. subprocess crash with the
         * binaries present) — those want to fail the suite.
         *
         * @param caseName used to locate an optional preload state
         *   file at `cases/<caseName>.preload.state.json` next to the
         *   sibling repo's Python case scripts. When present, copied
         *   into fwdsvc's data dir as `state.json` before fwdsvc boots
         *   — lets tests start with a populated roster instead of
         *   driving 50 `/join` round-trips to set up.
         */
        fun startOrSkip(caseName: String): FwdsvcHarness? {
            val rnsdExe = findRnsdExe() ?: run {
                println("[fwdsvc-harness] rnsd not on PATH (pip install rns) — skipping")
                return null
            }
            val fwdsvcBin = findFwdsvcBinary() ?: run {
                println("[fwdsvc-harness] fwdsvc binary not found under ../reticulum-forwarding-service/build/ — skipping")
                return null
            }
            val pythonOk = pythonImportsAvailable()
            if (!pythonOk) {
                println("[fwdsvc-harness] python+RNS not importable (rnsd will refuse to start) — skipping")
                return null
            }

            val port = pickFreePort()
            val tempRoot = Files.createTempDirectory("fwdsvc-harness-").toFile()
            val rnsdDir = File(tempRoot, "rnsd").apply { mkdirs() }
            val fwdsvcDir = File(tempRoot, "fwdsvc").apply { mkdirs() }

            writeRnsdConfig(rnsdDir, port)
            maybePreloadState(caseName, fwdsvcDir)

            val rnsd = spawnRnsd(rnsdExe, rnsdDir)
            try {
                waitForListen("127.0.0.1", port, 15_000)
            } catch (e: Exception) {
                rnsd.kill()
                tempRoot.deleteRecursively()
                throw IllegalStateException(
                    "rnsd never started listening on :$port within 15s\n${rnsd.captured()}", e
                )
            }

            val cfgPath = writeFwdsvcConfig(fwdsvcDir, port)
            val (fwdsvc, deliveryHash) = try {
                spawnFwdsvcAndWaitForDeliveryHash(fwdsvcBin, cfgPath, fwdsvcDir, 30_000)
            } catch (e: Exception) {
                rnsd.kill()
                tempRoot.deleteRecursively()
                throw e
            }

            return FwdsvcHarness(
                rnsdHost = "127.0.0.1",
                rnsdPort = port,
                fwdsvcDeliveryHashHex = deliveryHash,
                rnsd = rnsd,
                fwdsvc = fwdsvc,
                tempRoot = tempRoot,
            )
        }

        private fun osName(): String = (System.getProperty("os.name") ?: "").lowercase()

        // ---- prerequisite probes -------------------------------------------

        /** Searches PATH + a handful of platform-specific locations so
         *  Windows installs (Python user-site Scripts/) work without
         *  the user having to manually add Scripts to PATH. */
        private fun findRnsdExe(): String? {
            val candidates = mutableListOf("rnsd", "rnsd.exe", "rnsd.py")
            // Common Windows install location for `pip install --user rns`.
            val isWindows = osName().contains("win")
            if (isWindows) {
                val userProfile = System.getenv("USERPROFILE") ?: System.getProperty("user.home")
                val py = sequenceOf("Python313", "Python312", "Python311", "Python310")
                py.forEach { pyVer ->
                    candidates += "$userProfile\\AppData\\Local\\Programs\\Python\\$pyVer\\Scripts\\rnsd.exe"
                    candidates += "$userProfile\\AppData\\Local\\Programs\\Python\\$pyVer\\Scripts\\rnsd.py"
                }
            }
            for (c in candidates) {
                if (c.contains(File.separatorChar) || c.contains('/') || c.contains('\\')) {
                    if (File(c).canExecute() || File(c).exists()) return c
                    continue
                }
                // Plain name — probe via `--version`. Going through
                // ProcessBuilder gives us a reliable PATH search on
                // both win + posix, instead of replicating shutil.which.
                val ok = runCatching {
                    val p = ProcessBuilder(c, "--version")
                        .redirectErrorStream(true)
                        .start()
                    p.waitFor() == 0
                }.getOrDefault(false)
                if (ok) return c
            }
            return null
        }

        /** Located by walking up from the JVM CWD until we hit the
         *  sibling fwdsvc repo's `build/` directory. Robust to whether
         *  gradle invoked us from `shared/` or the repo root. */
        private fun findFwdsvcBinary(): String? {
            val osName = osName()
            val isWindows = osName.contains("win")
            val arch = (System.getProperty("os.arch") ?: "").lowercase()
            val osTag = when {
                isWindows -> "windows"
                osName.contains("mac") -> "darwin"
                else -> "linux"
            }
            val archTag = when {
                "aarch64" in arch || "arm64" in arch -> "arm64"
                else -> "amd64"
            }
            val ext = if (isWindows) ".exe" else ""
            val targets = listOf(
                "fwdsvc-$osTag-$archTag$ext",
                "fwdsvc$ext",
            )
            // Walk up from CWD looking for a sibling repo.
            var dir: File? = File(".").absoluteFile.canonicalFile
            repeat(6) {
                val d = dir ?: return@repeat
                val sibling = File(d, "../reticulum-forwarding-service/build").canonicalFile
                if (sibling.isDirectory) {
                    for (t in targets) {
                        val f = File(sibling, t)
                        if (f.canExecute() || f.exists()) return f.absolutePath
                    }
                }
                dir = d.parentFile
            }
            return null
        }

        private fun pythonImportsAvailable(): Boolean {
            // rnsd ships in the RNS python package; presence of rnsd on
            // PATH implies RNS is importable, but the rnsd.exe stub on
            // Windows can be a shim that points at a Python that has
            // since been uninstalled — verify before committing to spawn.
            val candidates = listOf("python3", "python", "py")
            for (exe in candidates) {
                val ok = runCatching {
                    val p = ProcessBuilder(exe, "-c", "import RNS; import LXMF")
                        .redirectErrorStream(true)
                        .start()
                    p.waitFor() == 0
                }.getOrDefault(false)
                if (ok) return true
            }
            return false
        }

        // ---- port allocation, config, spawning -----------------------------

        private fun pickFreePort(): Int {
            // Tiny TOCTOU window between close() and rnsd binding; on a
            // quiet localhost this is fine. If it ever flakes on a busy
            // CI box, switch to `SO_REUSEPORT` + hand the bound socket
            // to rnsd via fd inheritance.
            ServerSocket(0).use { return it.localPort }
        }

        private fun writeRnsdConfig(dir: File, port: Int) {
            val cfg = """
                |[reticulum]
                |enable_transport = yes
                |share_instance = no
                |panic_on_interface_error = no
                |
                |[logging]
                |loglevel = 7
                |
                |[interfaces]
                |
                |  [[Default Interface]]
                |    type = AutoInterface
                |    enabled = no
                |
                |  [[harness]]
                |    type = TCPServerInterface
                |    enabled = yes
                |    listen_ip = 127.0.0.1
                |    listen_port = $port
                |""".trimMargin()
            File(dir, "config").writeText(cfg, StandardCharsets.UTF_8)
        }

        /** Optional preload state copy. Looks for
         *  `<reticulum-forwarding-service>/tests/interop/cases/<case>.preload.state.json`
         *  and drops it in as the fwdsvc state.json so a test can start
         *  with a populated roster. No-op when no preload file exists. */
        private fun maybePreloadState(caseName: String, fwdsvcDir: File) {
            // CWD walk just like findFwdsvcBinary — keep them in sync.
            var dir: File? = File(".").absoluteFile.canonicalFile
            repeat(6) {
                val d = dir ?: return@repeat
                val candidate = File(d, "../reticulum-forwarding-service/tests/interop/cases/$caseName.preload.state.json")
                    .canonicalFile
                if (candidate.isFile) {
                    File(fwdsvcDir, "state.json").writeBytes(candidate.readBytes())
                    println("[fwdsvc-harness] preloaded ${candidate.length()} bytes from $caseName.preload.state.json")
                    return
                }
                dir = d.parentFile
            }
        }

        private fun writeFwdsvcConfig(dir: File, rnsdPort: Int): Path {
            val identityPath = File(dir, "identity").absolutePath.replace("\\", "/")
            val statePath = File(dir, "state.json").absolutePath.replace("\\", "/")
            val historyPath = File(dir, "history.json").absolutePath.replace("\\", "/")
            val logPath = File(dir, "fwdsvc.log").absolutePath.replace("\\", "/")
            val cfg = """
                |[service]
                |display_name      = "Interop Test Forwarder"
                |identity_path     = "$identityPath"
                |state_path        = "$statePath"
                |history_path      = "$historyPath"
                |log_path          = "$logPath"
                |prune_after       = "4w"
                |prune_interval    = "1h"
                |announce_interval = "10m"
                |max_inbound_chars = 500
                |max_members       = 0
                |
                |[[interfaces]]
                |type    = "tcp_client"
                |addr    = "127.0.0.1:$rnsdPort"
                |timeout = "10s"
                |
                |[replay]
                |count   = 100
                |max_age = "7d"
                |
                |admins = []
                |mods   = []
                |""".trimMargin()
            val cfgFile = File(dir, "config.toml")
            cfgFile.writeText(cfg, StandardCharsets.UTF_8)
            return cfgFile.toPath()
        }

        private fun spawnRnsd(rnsdExe: String, configDir: File): Spawn {
            val cmd = mutableListOf(rnsdExe, "--config", configDir.absolutePath)
            // -v repeated 7× → LOG_EXTREME, matching the Go harness so
            // failing-test dumps show packet-level decisions on the rnsd
            // side. PYTHONUNBUFFERED=1 keeps stdout flush-on-write.
            repeat(7) { cmd += "-v" }
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(false)
            pb.environment()["PYTHONUNBUFFERED"] = "1"
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            return startWithCapture(pb, "rnsd")
        }

        private fun spawnFwdsvcAndWaitForDeliveryHash(
            bin: String,
            configPath: Path,
            @Suppress("UNUSED_PARAMETER") dataDir: File,
            timeoutMs: Long,
        ): Pair<Spawn, String> {
            val pb = ProcessBuilder(bin, "--config", configPath.absolutePathString())
                .redirectErrorStream(false)
            val proc = pb.start()
            val logBuf = StringBuilder()
            val logLock = Any()
            val hashRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val deadline = System.currentTimeMillis() + timeoutMs

            fun pump(stream: InputStream, label: String) {
                thread(name = "fwdsvc-$label-pump", isDaemon = true) {
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: return@use
                            synchronized(logLock) { logBuf.append('[').append(label).append("] ").append(line).append('\n') }
                            if (hashRef.get() == null) {
                                val m = DELIVERY_RE.matcher(line)
                                if (m.find()) hashRef.compareAndSet(null, m.group(1))
                            }
                        }
                    }
                }
            }
            pump(proc.inputStream,  "fwdsvc")
            pump(proc.errorStream,  "fwdsvc!")

            while (System.currentTimeMillis() < deadline) {
                hashRef.get()?.let {
                    return Spawn(proc, logBuf, logLock) to it
                }
                if (!proc.isAlive) {
                    val captured = synchronized(logLock) { logBuf.toString() }
                    throw IllegalStateException("fwdsvc exited before logging delivery destination:\n$captured")
                }
                Thread.sleep(100)
            }
            val captured = synchronized(logLock) { logBuf.toString() }
            proc.destroy()
            throw IllegalStateException("fwdsvc never logged delivery destination within ${timeoutMs}ms:\n$captured")
        }

        private fun startWithCapture(pb: ProcessBuilder, label: String): Spawn {
            val proc = pb.start()
            val logBuf = StringBuilder()
            val logLock = Any()
            fun pump(stream: InputStream, sub: String) {
                thread(name = "$label-$sub-pump", isDaemon = true) {
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: return@use
                            synchronized(logLock) { logBuf.append('[').append(sub).append("] ").append(line).append('\n') }
                        }
                    }
                }
            }
            pump(proc.inputStream,  label)
            pump(proc.errorStream,  "$label!")
            return Spawn(proc, logBuf, logLock)
        }

        private fun waitForListen(host: String, port: Int, timeoutMs: Long) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, port), 500)
                    }
                    return
                } catch (_: Exception) {
                    Thread.sleep(200)
                }
            }
            throw IllegalStateException("nothing listening on $host:$port after ${timeoutMs}ms")
        }
    }
}
