package ch.sonensei.cockpit.gtd.projects

import ch.sonensei.cockpit.core.network.generated.models.ProjectResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import java.util.UUID

/**
 * The project PATCH with a hand-built body: the generated request class sends every field, and a PATCH treats `null`
 * as "clear", so changing the title would wipe the due date.
 */
interface ProjectWriteApi {
    @PATCH("api/v1/projects/{id}")
    suspend fun patchProject(
        @Path("id") id: UUID,
        @Body patch: JsonObject,
        @Header("If-Match") ifMatch: String,
    ): Response<ProjectResponse>
}

@Module
@InstallIn(SingletonComponent::class)
object ProjectWriteModule {
    @Provides
    fun projectWriteApi(retrofit: Retrofit): ProjectWriteApi = retrofit.create(ProjectWriteApi::class.java)
}
