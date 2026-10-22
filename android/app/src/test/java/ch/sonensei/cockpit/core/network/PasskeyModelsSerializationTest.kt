package ch.sonensei.cockpit.core.network

import ch.sonensei.cockpit.core.network.generated.apis.PasskeysApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.core.network.generated.models.PasskeyLoginRequest
import ch.sonensei.cockpit.core.network.generated.models.PasskeyRegistrationRequest
import ch.sonensei.cockpit.core.network.generated.models.PasskeyTokenRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The free-form WebAuthn credential (additionalProperties: {}) is generated as a map of JSON elements. */
class PasskeyModelsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** The Json the app's Retrofit stack uses (CockpitHttp.retrofit default). */
    private val appJson = Serializer.kotlinxSerializationJson

    @Test
    fun `credential JSON survives a round trip`() {
        val credential =
            buildJsonObject {
                put("id", "Y3JlZC0x")
                put("type", "public-key")
                put("response", buildJsonObject { put("clientDataJSON", "e30") })
            }
        val request = PasskeyLoginRequest(ceremonyId = "c-1", credential = credential.jsonObject)

        val encoded = json.encodeToString(PasskeyLoginRequest.serializer(), request)
        val decoded = json.decodeFromString(PasskeyLoginRequest.serializer(), encoded)

        assertEquals(credential, json.parseToJsonElement(encoded).jsonObject["credential"])
        assertEquals(JsonPrimitive("public-key"), decoded.credential["type"])
        assertEquals(request, decoded)
    }

    @Test
    fun `all three passkey requests keep every JSON kind of the credential with the app Json`() {
        val credential = webAuthnCredential()
        val cases: List<Pair<String, () -> String>> =
            listOf(
                "PasskeyLoginRequest" to {
                    appJson.encodeToString(
                        PasskeyLoginRequest.serializer(),
                        PasskeyLoginRequest(ceremonyId = CEREMONY_ID, credential = credential, rememberDevice = true),
                    )
                },
                "PasskeyRegistrationRequest" to {
                    appJson.encodeToString(
                        PasskeyRegistrationRequest.serializer(),
                        PasskeyRegistrationRequest(ceremonyId = CEREMONY_ID, credential = credential, name = "Test phone"),
                    )
                },
                "PasskeyTokenRequest" to {
                    appJson.encodeToString(
                        PasskeyTokenRequest.serializer(),
                        PasskeyTokenRequest(ceremonyId = CEREMONY_ID, credential = credential, deviceName = "Test phone"),
                    )
                },
            )

        cases.forEach { (model, encode) ->
            val body = appJson.parseToJsonElement(encode()).jsonObject
            // A nested JSON object (not a string), with nested objects, arrays, numbers, booleans and nulls intact.
            assertEquals(model, credential, body["credential"])
            assertEquals(model, JsonPrimitive(CEREMONY_ID), body["ceremonyId"])
        }
    }

    @Test
    fun `a credential decoded from wire JSON encodes back to the same JSON`() {
        val wire =
            """
            {"ceremonyId":"$CEREMONY_ID","deviceName":"Test phone",
             "credential":{"id":"Y3JlZC0x","rawId":"Y3JlZC0x","type":"public-key",
               "authenticatorAttachment":null,"clientExtensionResults":{},
               "response":{"clientDataJSON":"e30","authenticatorData":"AAEC","signature":"MEUC","userHandle":"dXNlcg"}}}
            """.trimIndent()

        val decoded = appJson.decodeFromString(PasskeyTokenRequest.serializer(), wire)
        val reEncoded = appJson.parseToJsonElement(appJson.encodeToString(PasskeyTokenRequest.serializer(), decoded))

        assertEquals("Test phone", decoded.deviceName)
        assertTrue(decoded.credential.containsKey("authenticatorAttachment"))
        assertEquals(appJson.parseToJsonElement(wire), reEncoded)
    }

    @Test
    fun `the passkey token call posts the credential as a nested JSON object`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(TOKEN_BODY)
                    .build(),
            )
            val client = CockpitHttp.okHttpClient(ServerUrlInterceptor { server.url("/") }, null, null)
            val api = CockpitHttp.retrofit(client).create(PasskeysApi::class.java)
            val credential = webAuthnCredential()

            val response =
                runBlocking {
                    api.issueDeviceTokenWithPasskey(
                        PasskeyTokenRequest(ceremonyId = CEREMONY_ID, credential = credential, deviceName = "Test phone"),
                    )
                }

            assertEquals(200, response.code())
            assertEquals("fresh-access", response.body()?.accessToken)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/v1/auth/passkeys/token", recorded.url.encodedPath)
            val sent = appJson.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals(credential, sent["credential"])
            assertEquals(JsonPrimitive("Test phone"), sent["deviceName"])
        }
    }

    /** A fictitious AuthenticationResponseJSON / RegistrationResponseJSON shape with every JSON value kind. */
    private fun webAuthnCredential(): Map<String, JsonElement?> =
        buildJsonObject {
            put("id", "Y3JlZC0x")
            put("rawId", "Y3JlZC0x")
            put("type", "public-key")
            put("authenticatorAttachment", JsonNull)
            putJsonObject("clientExtensionResults") { put("credProps", buildJsonObject { put("rk", true) }) }
            putJsonObject("response") {
                put("clientDataJSON", "e30")
                put("attestationObject", "o2NmbXRkbm9uZQ")
                putJsonArray("transports") {
                    add("internal")
                    add("hybrid")
                }
                put("publicKeyAlgorithm", -7)
            }
        }

    private companion object {
        const val CEREMONY_ID = "0192f1a0-0000-7000-8000-0000000000c1"
        val TOKEN_BODY =
            """
            {"accessToken":"fresh-access","accessTokenExpiresAt":"2026-10-20T12:15:00Z",
             "deviceId":"0192f1a0-0000-7000-8000-000000000001","refreshToken":"fresh-refresh",
             "refreshTokenExpiresAt":"2027-01-18T12:00:00Z","tokenType":"Bearer"}
            """.trimIndent()
    }
}
