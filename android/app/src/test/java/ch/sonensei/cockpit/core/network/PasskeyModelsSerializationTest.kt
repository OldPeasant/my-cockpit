package ch.sonensei.cockpit.core.network

import ch.sonensei.cockpit.core.network.generated.models.PasskeyLoginRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** The free-form WebAuthn credential (additionalProperties: {}) is generated as a map of JSON elements. */
class PasskeyModelsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

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
}
