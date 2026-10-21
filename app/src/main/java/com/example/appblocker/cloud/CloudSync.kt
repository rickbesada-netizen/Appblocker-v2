package com.example.appblocker.cloud

import android.content.Context
import android.os.Build
import com.example.appblocker.BlockListStore
import com.example.appblocker.StrictModeStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Account sign-in + multi-device sync, on the SAME Supabase account as the
 * desktop app. Everything the app does on-device works with no account; signing
 * in adds: block lists sync across devices, and receiving remote
 * "Strict on" / "Lock all" commands from your other devices.
 *
 * Block lists are the single source of truth (BlockListStore's JSON blob); we
 * push that blob up on every local change and apply it down on remote changes.
 */
object CloudSync {
    // Same project as desktop. Public client values (safe to embed).
    private const val URL = "https://witygsuyvkxhmhbjtxnu.supabase.co"
    private const val ANON =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndpdHlnc3V5dmt4aG1oYmp0eG51Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTE1ODUyMzEsImV4cCI6MjEwNzE2MTIzMX0.1dDJxkHTHAJPsw1OzbrnleeAIZ-Q-nWdSyad84mwaEA"

    lateinit var client: SupabaseClient
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var appContext: Context
    private lateinit var prefs: android.content.SharedPreferences
    private var deviceId: String? = null
    @Volatile private var applyingRemote = false

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("appblocker_cloud", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null)
        client = createSupabaseClient(URL, ANON) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
        scope.launch { if (isSignedIn) onSignedIn() }
    }

    val isSignedIn: Boolean get() = ::client.isInitialized && client.auth.currentUserOrNull() != null
    val email: String? get() = client.auth.currentUserOrNull()?.email
    private val uid: String? get() = client.auth.currentUserOrNull()?.id
    fun thisDeviceId(): String? = deviceId

    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        client.auth.signInWith(Email) { this.email = email; this.password = password }
        onSignedIn()
    }

    suspend fun signUp(email: String, password: String): Result<Unit> = runCatching {
        client.auth.signUpWith(Email) { this.email = email; this.password = password }
        if (isSignedIn) onSignedIn()
    }

    suspend fun signOut(): Result<Unit> = runCatching { client.auth.signOut() }

    private suspend fun onSignedIn() {
        registerDevice()
        pullConfig()
        pushLists()        // make sure the cloud has this device's current lists
        subscribe()
    }

    private suspend fun registerDevice() {
        val user = uid ?: return
        if (deviceId == null) {
            val inserted = client.from("devices").insert(buildJsonObject {
                put("user_id", user); put("name", Build.MODEL ?: "Android phone"); put("platform", "android")
            }) { select() }.decodeSingleOrNull<DeviceRow>()
            deviceId = inserted?.id
            prefs.edit().putString("device_id", deviceId).apply()
        } else {
            runCatching {
                client.from("devices").update(buildJsonObject { put("last_seen", nowIso()) }) {
                    filter { eq("id", deviceId!!) }
                }
            }
        }
    }

    // ---- Config (block lists) ----

    private suspend fun pullConfig() {
        val user = uid ?: return
        val row = runCatching {
            client.from("configs").select { filter { eq("user_id", user) } }.decodeSingleOrNull<ConfigRow>()
        }.getOrNull() ?: return
        applyRemote(row)
    }

    /** Push the local block lists to the cloud. Called on every local edit. */
    fun pushLists() {
        if (!isSignedIn || applyingRemote) return
        val listsRaw = BlockListStore.rawJson(appContext)
        scope.launch {
            runCatching {
                val listsElement = json.parseToJsonElement(listsRaw)
                client.from("configs").upsert(buildJsonObject {
                    put("user_id", uid!!)
                    put("data", buildJsonObject {
                        put("lists", listsElement)
                        put("source", "android")
                    })
                    put("updated_at", nowIso())
                    deviceId?.let { put("updated_by", it) }
                })
            }
        }
    }

    private fun applyRemote(row: ConfigRow) {
        if (row.updated_by != null && row.updated_by == deviceId) return  // our own write echoed back
        if (StrictModeStore.isActive(appContext)) return                 // never weaken a locked device
        val data = row.data as? JsonObject ?: return
        // Only sync between devices running THIS app (ignore other-shaped configs).
        if ((data["source"] as? JsonPrimitive)?.content != "android") return
        val lists = data["lists"] as? JsonArray ?: return
        applyingRemote = true
        runCatching { BlockListStore.applyRemoteJson(appContext, lists.toString()) }
        applyingRemote = false
    }

    private fun subscribe() {
        val user = uid ?: return
        // Remote commands (strict_on / lock_all) from other devices.
        scope.launch {
            val ch = client.channel("cmd-$user")
            val flow = ch.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "commands"; filter("user_id", FilterOperator.EQ, user)
            }
            ch.subscribe()
            flow.collect { action ->
                val c = runCatching { json.decodeFromString<CommandRow>(action.record.toString()) }.getOrNull()
                    ?: return@collect
                if (c.target_device != null && c.target_device != deviceId) return@collect
                when (c.type) {
                    "strict_on" -> StrictModeStore.setActive(appContext, true)
                    "lock_all" -> { BlockListStore.enableAll(appContext); StrictModeStore.setActive(appContext, true) }
                }
                runCatching {
                    client.from("commands").update(buildJsonObject { put("consumed", true) }) {
                        filter { eq("id", c.id) }
                    }
                }
            }
        }
        // Remote config changes (block lists edited on another device).
        scope.launch {
            val ch = client.channel("cfg-$user")
            val flow = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "configs"; filter("user_id", FilterOperator.EQ, user)
            }
            ch.subscribe()
            flow.collect { action ->
                val record = when (action) {
                    is PostgresAction.Insert -> action.record
                    is PostgresAction.Update -> action.record
                    else -> null
                } ?: return@collect
                runCatching { json.decodeFromString<ConfigRow>(record.toString()) }.getOrNull()?.let { applyRemote(it) }
            }
        }
    }

    suspend fun listDevices(): List<DeviceRow> = runCatching {
        val user = uid ?: return@runCatching emptyList<DeviceRow>()
        client.from("devices").select { filter { eq("user_id", user) } }.decodeList<DeviceRow>()
    }.getOrDefault(emptyList())

    suspend fun sendCommand(type: String, target: String?): Result<Unit> = runCatching {
        val user = uid ?: return@runCatching
        client.from("commands").insert(buildJsonObject {
            put("user_id", user); target?.let { put("target_device", it) }; put("type", type)
        })
    }

    private fun nowIso(): String = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
}
