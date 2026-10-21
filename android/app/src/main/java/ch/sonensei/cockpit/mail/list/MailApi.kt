package ch.sonensei.cockpit.mail.list

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The mail calls of the app, with hand-written bodies: the generated mail models declare fields as non-null that the
 * server sends as `null` (`nextCursor` on the last page, a message without subject or date), and would fail to read
 * them. Ids and dates stay strings here and are parsed by the mappers, so one odd value fails only its own field.
 */
interface MailApi {
    @GET("api/v1/mail/accounts")
    suspend fun accounts(): Response<MailAccountListDto>

    @GET("api/v1/mail/accounts/{id}/folders")
    suspend fun folders(
        @Path("id") accountId: String,
    ): Response<MailFolderListDto>

    @GET("api/v1/mail/messages")
    suspend fun messages(
        @Query("accountId") accountId: String?,
        @Query("folderId") folderId: String?,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
    ): Response<MailMessageListDto>

    @POST("api/v1/mail/messages/actions")
    suspend fun act(
        @Body request: MailActionRequestDto,
    ): Response<MailActionResponseDto>

    @GET("api/v1/mail/messages/{id}")
    suspend fun message(
        @Path("id") id: String,
    ): Response<MailMessageDto>

    @POST("api/v1/mail/messages/{id}/to-task")
    suspend fun toTask(
        @Path("id") id: String,
    ): Response<MailGtdItemDto>

    @POST("api/v1/mail/messages/{id}/to-waiting")
    suspend fun toWaiting(
        @Path("id") id: String,
    ): Response<MailGtdItemDto>
}

@Module
@InstallIn(SingletonComponent::class)
object MailApiModule {
    @Provides
    fun mailApi(retrofit: Retrofit): MailApi = retrofit.create(MailApi::class.java)
}

@Serializable
data class MailAccountListDto(
    val items: List<MailAccountDto> = emptyList(),
)

@Serializable
data class MailAccountDto(
    val id: String,
    val displayName: String? = null,
    val emailAddress: String? = null,
)

@Serializable
data class MailFolderListDto(
    val items: List<MailFolderDto> = emptyList(),
)

@Serializable
data class MailFolderDto(
    val id: String,
    val accountId: String,
    val name: String? = null,
    val path: String? = null,
    val delimiter: String? = null,
    val role: String? = null,
    val selectable: Boolean = true,
)

@Serializable
data class MailAddressDto(
    val name: String? = null,
    val address: String? = null,
)

@Serializable
data class MailMessageListDto(
    val items: List<MailSummaryDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class MailSummaryDto(
    val id: String,
    val accountId: String? = null,
    val folderId: String? = null,
    val subject: String? = null,
    val snippet: String? = null,
    val from: List<MailAddressDto> = emptyList(),
    val sentAt: String? = null,
    val receivedAt: String? = null,
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val hasAttachments: Boolean = false,
)

@Serializable
data class MailActionRequestDto(
    val action: String,
    val ids: List<String>,
)

@Serializable
data class MailActionResponseDto(
    val results: List<MailActionResultDto> = emptyList(),
)

@Serializable
data class MailActionResultDto(
    val id: String,
    val ok: Boolean,
    val errorCode: String? = null,
)

@Serializable
data class MailBodyDto(
    val html: String? = null,
    val text: String? = null,
    val remoteImageCount: Int = 0,
    val remoteImagesBlocked: Boolean = false,
)

@Serializable
data class MailAttachmentDto(
    val partId: String,
    val filename: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val inline: Boolean = false,
)

@Serializable
data class MailMessageDto(
    val id: String,
    val accountId: String? = null,
    val folderId: String? = null,
    val subject: String? = null,
    val from: List<MailAddressDto> = emptyList(),
    val to: List<MailAddressDto> = emptyList(),
    val cc: List<MailAddressDto> = emptyList(),
    val sentAt: String? = null,
    val receivedAt: String? = null,
    val seen: Boolean = false,
    val body: MailBodyDto? = null,
    val attachments: List<MailAttachmentDto> = emptyList(),
)

@Serializable
data class MailGtdItemDto(
    val id: String,
    val title: String? = null,
)
