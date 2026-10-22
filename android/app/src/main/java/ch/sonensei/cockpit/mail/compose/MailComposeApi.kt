package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.list.MailAccountListDto
import ch.sonensei.cockpit.mail.list.MailAddressDto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

/**
 * The draft calls of SPEC.md §7.4 with hand-written bodies (see `MailApi`). A draft's version travels as the `ETag`
 * header and goes back verbatim in `If-Match`.
 */
interface MailComposeApi {
    @GET("api/v1/mail/accounts")
    suspend fun accounts(): Response<MailAccountListDto>

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

/** Keeps an attachment of the current version (`partId`). */
@Serializable
data class AttachmentRefDto(
    val partId: String,
)

@Serializable
data class CreateDraftDto(
    val accountId: String,
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
