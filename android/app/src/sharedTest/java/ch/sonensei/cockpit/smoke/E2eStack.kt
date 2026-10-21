package ch.sonensei.cockpit.smoke

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The owner account of the e2e stack (fictitious, same as the web e2e specs). */
data class E2eAccount(
    val username: String,
    val password: String,
    val totpSecret: String,
    val recoveryCodes: List<String>,
)

/** A task as the web sees it (`GET /api/v1/tasks`). */
data class ApiTask(
    val id: String,
    val title: String,
    val list: String,
    val dueDate: LocalDate?,
    val scheduledDate: LocalDate?,
)

/** A project as the web sees it (`GET /api/v1/projects`). */
data class ApiProject(
    val id: String,
    val title: String,
    val status: String,
)

/**
 * The e2e stack's API (`deploy/docker-compose.e2e.yml`, profile `e2e`), used the way the web app uses it: seeding data
 * and reading what the web would show. It has its own device token, independent of the app's session.
 */
class E2eStack(
    /** The server as seen from where the test runs, ending with `/`. */
    val baseUrl: String,
) {
    private val http =
        OkHttpClient
            .Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    private var token: String? = null

    /** Empties the database and resets the clock and the login lockout (profile e2e only). */
    fun reset() {
        post("api/test/reset", JsonObject(emptyMap()), auth = false).use { assertEquals("test reset", 204, it.code) }
        token = null
    }

    /** First-run setup (SPEC.md §4.1) with a confirmed TOTP enrollment; the stack must be fresh. */
    fun setUpOwner(): E2eAccount {
        val start =
            post(
                "api/v1/setup",
                buildJsonObject {
                    put("username", OWNER_USERNAME)
                    put("password", OWNER_PASSWORD)
                },
                auth = false,
            ).use {
                assertEquals("setup must start on a fresh stack", 201, it.code)
                Json.parseToJsonElement(it.body.string()).jsonObject
            }
        val secret = start.string("totpSecret")!!
        val (code, step) = Totp.unusedCode(secret)
        post("api/v1/setup/confirm-totp", buildJsonObject { put("code", code) }, auth = false).use {
            assertEquals("TOTP confirmation", 204, it.code)
        }
        Totp.used(step)
        return E2eAccount(
            OWNER_USERNAME,
            OWNER_PASSWORD,
            secret,
            start["recoveryCodes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /** Logs this client in as the web would, with a recovery code: the TOTP codes stay free for the app's login. */
    fun logIn(
        account: E2eAccount,
        recoveryCode: String = account.recoveryCodes.first(),
    ) {
        val body =
            buildJsonObject {
                put("username", account.username)
                put("password", account.password)
                put("recoveryCode", recoveryCode)
                put("deviceName", "e2e web stand-in")
            }
        token =
            post("api/v1/auth/token", body, auth = false).use {
                assertEquals("API login", 200, it.code)
                Json.parseToJsonElement(it.body.string()).jsonObject.string("accessToken")
            }
    }

    fun createTask(fields: JsonObject): ApiTask =
        post("api/v1/tasks", fields).use {
            assertEquals("create task", 201, it.code)
            Json.parseToJsonElement(it.body.string()).jsonObject.toTask()
        }

    fun createReminder(
        taskId: String,
        at: Instant,
    ) {
        val body =
            buildJsonObject {
                put("itemType", "TASK")
                put("itemId", taskId)
                put("remindAt", at.toString())
            }
        post("api/v1/reminders", body).use { assertEquals("create reminder", 201, it.code) }
    }

    /** Open tasks of one list, or due ones (`overdue`, `today`), in the order the web shows them. */
    fun tasks(
        list: String? = null,
        due: String? = null,
    ): List<ApiTask> {
        val query = listOfNotNull(list?.let { "list=$it" }, due?.let { "due=$it" }).joinToString("&")
        return pages("api/v1/tasks?$query").map { it.toTask() }
    }

    fun projects(): List<ApiProject> =
        pages("api/v1/projects").map {
            ApiProject(it.string("id")!!, it.string("title")!!, it.string("status")!!)
        }

    /** The files linked to [taskId] (`GET /api/v1/links`), as ids. */
    fun linkedFiles(taskId: String): List<String> =
        get("api/v1/links?type=TASK&id=$taskId")["items"]!!
            .jsonArray
            .map { it.jsonObject }
            .flatMap { listOf(it["source"]!!.jsonObject, it["target"]!!.jsonObject) }
            .filter { it.string("type") == "FILE" }
            .mapNotNull { it.string("id") }
            .distinct()

    fun fileContent(fileId: String): ByteArray =
        http.newCall(request("api/v1/files/$fileId/content").get().build()).execute().use {
            assertEquals("file content", 200, it.code)
            it.body.bytes()
        }

    private fun pages(path: String): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val separator = if ('?' in path) "&" else "?"
            val page = get(path + (cursor?.let { "${separator}cursor=$it" } ?: ""))
            items += page["items"]!!.jsonArray.map { it.jsonObject }
            cursor = page.string("nextCursor")
        } while (cursor != null)
        return items
    }

    private fun get(path: String): JsonObject =
        http.newCall(request(path).get().build()).execute().use {
            assertEquals("GET $path", 200, it.code)
            Json.parseToJsonElement(it.body.string()).jsonObject
        }

    private fun post(
        path: String,
        body: JsonObject,
        auth: Boolean = true,
    ) = http.newCall(request(path, auth).post(body.toString().toRequestBody(JSON)).build()).execute()

    private fun request(
        path: String,
        auth: Boolean = true,
    ): Request.Builder =
        Request.Builder().url(baseUrl + path).apply {
            if (auth) header("Authorization", "Bearer ${checkNotNull(token) { "log in first" }}")
        }

    private fun JsonObject.toTask() =
        ApiTask(
            id = string("id")!!,
            title = string("title")!!,
            list = string("list")!!,
            dueDate = string("dueDate")?.let(LocalDate::parse),
            scheduledDate = string("scheduledDate")?.let(LocalDate::parse),
        )

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    companion object {
        const val OWNER_USERNAME = "jane.doe"
        const val OWNER_PASSWORD = "correct horse battery staple"
        private val JSON = "application/json".toMediaType()
    }
}

/**
 * TOTP codes like an authenticator app (RFC 6238: HMAC-SHA1, 6 digits, 30 s). The server rejects a code whose time step
 * was used before, so [unusedCode] waits for a fresh step after one was [used].
 */
object Totp {
    private const val STEP_SECONDS = 30L
    private var lastUsedStep = -1L

    fun code(
        secret: String,
        step: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(base32(secret), "HmacSHA1")) }
        val hash = mac.doFinal(ByteArray(8) { i -> (step ushr (8 * (7 - i))).toByte() })
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    /** The code of the current time step, after waiting for the next step if the current one was used. */
    fun unusedCode(secret: String): Pair<String, Long> {
        var step = currentStep()
        while (step <= lastUsedStep) {
            Thread.sleep(500)
            step = currentStep()
        }
        return code(secret, step) to step
    }

    fun used(step: Long) {
        lastUsedStep = maxOf(lastUsedStep, step)
    }

    // java.time, not System.currentTimeMillis: Robolectric may fake the latter.
    private fun currentStep(): Long = Instant.now().epochSecond / STEP_SECONDS

    private fun base32(secret: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        secret.uppercase().filter { it != '=' && !it.isWhitespace() }.forEach { char ->
            buffer = (buffer shl 5) or alphabet.indexOf(char).also { require(it >= 0) { "Not base32" } }
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
