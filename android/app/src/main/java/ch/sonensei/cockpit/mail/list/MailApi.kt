package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
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

    /**
     * Renames a folder. Hand-written because the generated `UpdateMailFolderRequest` always sends `"role": null`,
     * which the server reads as "take the folder's role away".
     */
    @PATCH("api/v1/mail/accounts/{id}/folders/{folderId}")
    suspend fun renameFolder(
        @Path("id") accountId: String,
        @Path("folderId") folderId: String,
        @Header("If-Match") ifMatch: String,
        @Body request: RenameMailFolderDto,
    ): Response<MailFolderDto>

    @GET("api/v1/mail/messages")
    suspend fun messages(
        @Query("accountId") accountId: String?,
        @Query("folderId") folderId: String?,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int?,
    ): Response<MailMessageListDto>

    /** Searches the cached mail of a folder, an account or all accounts; [server] also searches on the mail servers. */
    @GET("api/v1/mail/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("accountId") accountId: String?,
        @Query("folderId") folderId: String?,
        @Query("server") server: Boolean?,
        @Query("limit") limit: Int?,
    ): Response<MailSearchDto>

    /** Caches up to [limit] messages of the folder older than the sync window (SPEC.md §7.2). */
    @POST("api/v1/mail/folders/{id}/load-older")
    suspend fun loadOlder(
        @Path("id") folderId: String,
        @Query("limit") limit: Int?,
    ): Response<MailLoadOlderDto>

    @POST("api/v1/mail/messages/actions")
    suspend fun act(
        @Body request: MailActionRequestDto,
    ): Response<MailActionResponseDto>

    @GET("api/v1/mail/messages/{id}")
    suspend fun message(
        @Path("id") id: String,
        @Query("remoteImages") remoteImages: String? = null,
    ): Response<MailMessageDto>

    @POST("api/v1/mail/messages/{id}/to-task")
    suspend fun toTask(
        @Path("id") id: String,
    ): Response<MailGtdItemDto>

    /** "Next action & archive": a Next action per message, then the messages are archived (SPEC.md §7.3). */
    @POST("api/v1/mail/messages/to-tasks")
    suspend fun toTasks(
        @Body request: MailToTasksRequestDto,
    ): Response<MailToTasksResponseDto>

    @POST("api/v1/mail/messages/{id}/to-waiting")
    suspend fun toWaiting(
        @Path("id") id: String,
    ): Response<MailGtdItemDto>

    @POST("api/v1/mail/messages/{id}/to-note")
    suspend fun toNote(
        @Path("id") id: String,
    ): Response<MailGtdItemDto>
}

@Module
@InstallIn(SingletonComponent::class)
object MailApiModule {
    @Provides
    fun mailApi(retrofit: Retrofit): MailApi = retrofit.create(MailApi::class.java)

    /** The generated account calls; the list uses them to create and delete folders. */
    @Provides
    fun mailAccountsApi(retrofit: Retrofit): MailAccountsApi = retrofit.create(MailAccountsApi::class.java)
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
    /** The ETag value for If-Match. */
    val version: Long? = null,
)

@Serializable
data class RenameMailFolderDto(
    val name: String,
)

@Serializable
data class MailAddressDto(
    val name: String? = null,
    val address: String? = null,
)

@Serializable
data class MailLoadOlderDto(
    val loaded: Int = 0,
    val hasMore: Boolean = false,
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
data class MailSearchDto(
    val items: List<MailSummaryDto> = emptyList(),
    val hasMore: Boolean = false,
    /** `null` without `server=true`. */
    val server: MailServerSearchDto? = null,
)

@Serializable
data class MailServerSearchDto(
    val failures: List<MailServerSearchFailureDto> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class MailServerSearchFailureDto(
    val accountId: String? = null,
    val errorCode: String? = null,
)

@Serializable
data class MailToTasksRequestDto(
    val ids: List<String>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val list: String = "NEXT",
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val archive: Boolean = true,
)

@Serializable
data class MailToTasksResponseDto(
    val results: List<MailToTaskResultDto> = emptyList(),
)

@Serializable
data class MailToTaskResultDto(
    val id: String,
    val task: MailToTaskTaskDto? = null,
    val archived: Boolean = false,
    val archiveErrorCode: String? = null,
    val archiveDetail: String? = null,
)

@Serializable
data class MailToTaskTaskDto(
    val id: String,
    val title: String? = null,
    val list: String? = null,
)

@Serializable
data class MailActionRequestDto(
    val action: String,
    val ids: List<String>,
    /** Only for MOVE: a folder of the messages' account; left out of the other requests. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val targetFolderId: String? = null,
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
    val flagged: Boolean = false,
    val body: MailBodyDto? = null,
    val attachments: List<MailAttachmentDto> = emptyList(),
)

@Serializable
data class MailGtdItemDto(
    val id: String,
    val title: String? = null,
)
