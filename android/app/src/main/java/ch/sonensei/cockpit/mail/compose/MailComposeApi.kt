package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.list.MailAccountListDto
import ch.sonensei.cockpit.mail.list.MailAddressDto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The draft calls of SPEC.md §7.4 with hand-written bodies (see `MailApi`). A draft's version travels as the `ETag`
 * header and goes back verbatim in `If-Match`.
 */
interface MailComposeApi {
    @GET("api/v1/mail/accounts")
    suspend fun accounts(): Response<MailAccountListDto>

    /** The sending identities of an account, in their order. */
    @GET("api/v1/mail/accounts/{accountId}/identities")
    suspend fun identities(
        @Path("accountId") accountId: String,
    ): Response<MailIdentityListDto>

    @POST("api/v1/mail/drafts")
    suspend fun create(
        @Body request: CreateDraftDto,
    ): Response<DraftDto>

    @PUT("api/v1/mail/drafts/{id}")
    suspend fun update(
        @Path("id") id: String,
        @Header("If-Match") ifMatch: String,
        @Body request: DraftContentDto,
    ): Response<DraftDto>

    @GET("api/v1/mail/drafts/{id}")
    suspend fun get(
        @Path("id") id: String,
    ): Response<DraftDto>

    @Multipart
    @POST("api/v1/mail/drafts/{id}/attachments")
    suspend fun attach(
        @Path("id") id: String,
        @Header("If-Match") ifMatch: String,
        @Part file: MultipartBody.Part,
    ): Response<DraftDto>

    @POST("api/v1/mail/drafts/{id}/send")
    suspend fun send(
        @Path("id") id: String,
        @Header("If-Match") ifMatch: String,
    ): Response<SendDraftDto>

    @DELETE("api/v1/mail/drafts/{id}")
    suspend fun delete(
        @Path("id") id: String,
        @Header("If-Match") ifMatch: String,
    ): Response<Unit>

    @POST("api/v1/mail/messages/{id}/reply")
    suspend fun reply(
        @Path("id") id: String,
    ): Response<DraftDto>

    @POST("api/v1/mail/messages/{id}/reply-all")
    suspend fun replyAll(
        @Path("id") id: String,
    ): Response<DraftDto>

    @POST("api/v1/mail/messages/{id}/forward")
    suspend fun forward(
        @Path("id") id: String,
    ): Response<DraftDto>

    /** Contacts, then recent correspondents, matching [query]. */
    @GET("api/v1/contacts/autocomplete")
    suspend fun autocomplete(
        @Query("q") query: String,
        @Query("limit") limit: Int,
    ): Response<List<ContactSuggestionDto>>
}

@Module
@InstallIn(SingletonComponent::class)
object MailComposeApiModule {
    @Provides
    fun mailComposeApi(retrofit: Retrofit): MailComposeApi = retrofit.create(MailComposeApi::class.java)
}

@Serializable
data class AddressRequestDto(
    val address: String,
    val name: String? = null,
)

/** Keeps an attachment of the current version (`partId`) or adds a file of the drive (`fileId`): exactly one is set. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttachmentRefDto(
    // Left out when unset: the server takes exactly one of them, and the API's Json writes defaults.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val partId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fileId: String? = null,
)

@Serializable
data class ContactSuggestionDto(
    val contactId: String? = null,
    val email: String? = null,
    val name: String? = null,
)

@Serializable
data class CreateDraftDto(
    val accountId: String,
    /** `null`: the account's default identity. */
    val identityId: String? = null,
    val to: List<AddressRequestDto>,
    val cc: List<AddressRequestDto>,
    val bcc: List<AddressRequestDto>,
    val subject: String,
    val html: String,
)

/** A new version: always lists the attachments it keeps, so a removed one is dropped. */
@Serializable
data class DraftContentDto(
    val to: List<AddressRequestDto>,
    val cc: List<AddressRequestDto>,
    val bcc: List<AddressRequestDto>,
    val subject: String,
    val html: String,
    val attachments: List<AttachmentRefDto>,
    val identityId: String? = null,
)

@Serializable
data class DraftAttachmentDto(
    val partId: String? = null,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val inline: Boolean = false,
)

@Serializable
data class DraftDto(
    val id: String,
    val accountId: String? = null,
    val identityId: String? = null,
    val to: List<MailAddressDto> = emptyList(),
    val cc: List<MailAddressDto> = emptyList(),
    val bcc: List<MailAddressDto> = emptyList(),
    val subject: String? = null,
    val html: String? = null,
    val text: String? = null,
    val attachments: List<DraftAttachmentDto> = emptyList(),
)

@Serializable
data class SendDraftDto(
    val sentMessageId: String? = null,
    val appendedToSent: Boolean = false,
    val draftRemoved: Boolean = false,
)

@Serializable
data class MailIdentityDto(
    val id: String,
    val accountId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class MailIdentityListDto(
    val items: List<MailIdentityDto> = emptyList(),
)
