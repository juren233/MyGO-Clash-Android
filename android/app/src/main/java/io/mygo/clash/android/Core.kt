package io.mygo.clash.android

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import android.util.Log
import io.mygo.clash.libmygo.Libmygo
import io.mygo.clash.libmygo.LineListener
import io.mygo.clash.libmygo.Options
import io.mygo.clash.libmygo.Platform
import io.mygo.clash.libmygo.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress

/** What the VPN is doing. */
enum class VpnState { Off, Starting, On }

/**
 * The Go side (libmygo): the core, which runs in this process, and the
 * profiles. Every call that does I/O suspends on [Dispatchers.IO].
 */
object Core {
    private const val TAG = "MyGO-Clash"

    private val _state = MutableStateFlow(VpnState.Off)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** The last error of the VPN, for the home page to show. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The service whose sockets protect() keeps out of the VPN, while it runs. */
    @Volatile
    var vpn: ClashVpnService? = null

    private lateinit var app: Context

    /** Opens the data of the app and starts the core, once per process. */
    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val dir = app.filesDir.resolve("mygo")
        Libmygo.init(dir.absolutePath, MasterKey.get(app, dir), platform)
    }

    private val platform = object : Platform {
        override fun protect(fd: Int): Boolean = vpn?.protect(fd) ?: true

        override fun packageOf(network: String, srcIP: String, srcPort: Int, dstIP: String, dstPort: Int): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
            return try {
                val cm = app.getSystemService(ConnectivityManager::class.java)
                val protocol = if (network == "udp") OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
                val uid = cm.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(InetAddress.getByName(srcIP), srcPort),
                    InetSocketAddress(InetAddress.getByName(dstIP), dstPort),
                )
                if (uid < 0) "" else packageOfUid(uid)
            } catch (e: Exception) {
                ""
            }
        }
    }

    private val packages = HashMap<Int, String>()

    private fun packageOfUid(uid: Int): String = synchronized(packages) {
        packages.getOrPut(uid) {
            app.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
        }
    }

    internal fun setState(s: VpnState, error: String? = null) {
        _state.value = s
        if (s != VpnState.Off || error != null) _error.value = error
    }

    fun clearError() {
        _error.value = null
    }

    /** The options of a start, from the settings. */
    fun options(context: Context): Options = Libmygo.newOptions().apply {
        val s = Settings(context)
        mode = s.mode
        stack = s.stack
        iPv6 = s.ipv6
        mixedPort = s.mixedPort
        allowLAN = s.allowLan
        logLevel = s.logLevel
    }

    /** Starts the core with TUN on [fd], which it takes over. */
    fun start(fd: Int, dns: List<String>, options: Options) {
        Libmygo.start(fd, dns.joinToString(","), options)
    }

    /** Gives the core the DNS servers of the network under the VPN. */
    fun setDNS(dns: List<String>) {
        if (dns.isNotEmpty()) Libmygo.setDNS(dns.joinToString(","))
    }

    fun stop() {
        try {
            Libmygo.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop", e)
        }
    }

    val version: String get() = Libmygo.version()

    // ---- The REST API of the core ----

    suspend fun request(method: String, path: String, body: String = ""): String =
        withContext(Dispatchers.IO) { Libmygo.request(method, path, body) }

    suspend fun proxies(): JSONObject = JSONObject(request("GET", "/proxies")).getJSONObject("proxies")

    suspend fun select(group: String, name: String) = withContext(Dispatchers.IO) { Libmygo.selectProxy(group, name) }

    /** Tests the delays of a group: name → ms, absent on timeout. */
    suspend fun groupDelay(group: String): Map<String, Int> {
        val q = "url=" + enc("https://www.gstatic.com/generate_204") + "&timeout=5000"
        val res = JSONObject(request("GET", "/group/${enc(group)}/delay?$q"))
        return res.keys().asSequence().associateWith { res.optInt(it) }
    }

    suspend fun setMode(context: Context, mode: String) {
        Settings(context).mode = mode
        if (state.value == VpnState.On) request("PATCH", "/configs", JSONObject().put("mode", mode).toString())
    }

    /** Streams the lines of a GET request until the stream is closed. */
    fun subscribe(path: String, onLine: (String) -> Unit): Stream =
        Libmygo.subscribe(path, object : LineListener {
            override fun onLine(line: String) = onLine(line)
            override fun onClose(err: String) {
                if (err.isNotEmpty()) Log.w(TAG, "stream $path: $err")
            }
        })

    // ---- Checks, through the proxy ----

    /** What a kind of site sees: view is domestic, global or cloudflare. */
    suspend fun exitIP(view: String): JSONObject = withContext(Dispatchers.IO) { JSONObject(Libmygo.exitIP(view)) }

    fun unlockList(): org.json.JSONArray = org.json.JSONArray(Libmygo.unlockList())

    /** Checks the services; [onResult] gets each one as it arrives. */
    fun checkUnlock(onResult: (JSONObject) -> Unit, onDone: () -> Unit) {
        Libmygo.checkUnlock("", object : LineListener {
            override fun onLine(line: String) {
                runCatching { JSONObject(line) }.getOrNull()?.let(onResult)
            }

            override fun onClose(err: String) = onDone()
        })
    }

    suspend fun rules(): org.json.JSONArray = JSONObject(request("GET", "/rules")).optJSONArray("rules") ?: org.json.JSONArray()

    // ---- Profiles ----

    /** The UID of a profile's extension: kind is merge or script. */
    suspend fun extensionOf(uid: String, kind: String): String = withContext(Dispatchers.IO) { Libmygo.extensionOf(uid, kind) }

    suspend fun content(uid: String): String = withContext(Dispatchers.IO) { Libmygo.content(uid) }

    /** Checks and saves an item's content; the running core applies it. */
    suspend fun setContent(uid: String, content: String) = withContext(Dispatchers.IO) { Libmygo.setContent(uid, content) }

    /** Changes a profile: a JSON object of name, url, userAgent, updateInterval. */
    suspend fun patchProfile(uid: String, patch: JSONObject) = withContext(Dispatchers.IO) {
        Libmygo.patchProfile(uid, patch.toString())
    }


    suspend fun profiles(): JSONObject = withContext(Dispatchers.IO) { JSONObject(Libmygo.profiles()) }

    suspend fun addProfile(url: String, name: String) = withContext(Dispatchers.IO) { Libmygo.addProfile(url, name) }

    suspend fun updateProfile(uid: String) = withContext(Dispatchers.IO) { Libmygo.updateProfile(uid) }

    suspend fun selectProfile(uid: String) = withContext(Dispatchers.IO) { Libmygo.selectProfile(uid) }

    suspend fun deleteProfile(uid: String) = withContext(Dispatchers.IO) { Libmygo.deleteProfile(uid) }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
