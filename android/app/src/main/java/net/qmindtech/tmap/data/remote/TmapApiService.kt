package net.qmindtech.tmap.data.remote

import net.qmindtech.tmap.data.remote.dto.AuthTokenResponse
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.remote.dto.CreateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.CreateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.DailyPlanResponse
import net.qmindtech.tmap.data.remote.dto.FocusSessionResponse
import net.qmindtech.tmap.data.remote.dto.LoginRequest
import net.qmindtech.tmap.data.remote.dto.LogoutRequest
import net.qmindtech.tmap.data.remote.dto.NoteGroupResponse
import net.qmindtech.tmap.data.remote.dto.NoteResponse
import net.qmindtech.tmap.data.remote.dto.ProjectResponse
import net.qmindtech.tmap.data.remote.dto.RefreshRequest
import net.qmindtech.tmap.data.remote.dto.RegisterRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.SaveSettingsRequest
import net.qmindtech.tmap.data.remote.dto.SettingsResponse
import net.qmindtech.tmap.data.remote.dto.SubtaskResponse
import net.qmindtech.tmap.data.remote.dto.SyncResponse
import net.qmindtech.tmap.data.remote.dto.TaskResponse
import net.qmindtech.tmap.data.remote.dto.UpdateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.UpdateNoteRequest
import net.qmindtech.tmap.data.remote.dto.UpdateProjectRequest
import net.qmindtech.tmap.data.remote.dto.UpdateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface TmapApiService {
    @POST("api/v1/auth/register")
    suspend fun register(@Body b: RegisterRequest): AuthTokenResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body b: LoginRequest): AuthTokenResponse

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body b: RefreshRequest): AuthTokenResponse

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body b: LogoutRequest): Response<Unit>

    @POST("api/v1/tasks")
    suspend fun createTask(@Body b: CreateTaskRequest): TaskResponse

    @PATCH("api/v1/tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body b: UpdateTaskRequest): TaskResponse

    @DELETE("api/v1/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): Response<Unit>

    @PATCH("api/v1/tasks/reorder")
    suspend fun reorderTasks(@Body b: List<ReorderItem>): Response<Unit>

    @POST("api/v1/tasks/{taskId}/subtasks")
    suspend fun createSubtask(@Path("taskId") t: String, @Body b: CreateSubtaskRequest): SubtaskResponse

    @PATCH("api/v1/subtasks/{id}")
    suspend fun updateSubtask(@Path("id") id: String, @Body b: UpdateSubtaskRequest): SubtaskResponse

    @DELETE("api/v1/subtasks/{id}")
    suspend fun deleteSubtask(@Path("id") id: String): Response<Unit>

    @GET("api/v1/projects")
    suspend fun getProjects(): List<ProjectResponse>

    @POST("api/v1/projects")
    suspend fun createProject(@Body b: CreateProjectRequest): ProjectResponse

    @PATCH("api/v1/projects/{id}")
    suspend fun updateProject(@Path("id") id: String, @Body b: UpdateProjectRequest): ProjectResponse

    @DELETE("api/v1/projects/{id}")
    suspend fun deleteProject(@Path("id") id: String): Response<Unit>

    @PATCH("api/v1/projects/reorder")
    suspend fun reorderProjects(@Body b: List<ReorderItem>): Response<Unit>

    @GET("api/v1/settings")
    suspend fun getSettings(): SettingsResponse

    @PUT("api/v1/settings")
    suspend fun saveSettings(@Body b: SaveSettingsRequest): SettingsResponse

    @GET("api/v1/sync")
    suspend fun sync(
        @Query("since") since: Long,
        @Query("cursor") cursor: Long?,
        @Query("limit") limit: Int,
    ): SyncResponse

    // ── Notes ──────────────────────────────────────────────
    @GET("api/v1/notes")
    suspend fun getNotes(
        @Query("groupId") groupId: String? = null,
        @Query("projectId") projectId: String? = null,
    ): List<NoteResponse>

    @GET("api/v1/notes/{id}")
    suspend fun getNote(@Path("id") id: String): NoteResponse

    @POST("api/v1/notes")
    suspend fun createNote(@Body b: CreateNoteRequest): NoteResponse

    @PATCH("api/v1/notes/{id}")
    suspend fun updateNote(@Path("id") id: String, @Body b: UpdateNoteRequest): NoteResponse

    @DELETE("api/v1/notes/{id}")
    suspend fun deleteNote(@Path("id") id: String): Response<Unit>

    @PATCH("api/v1/notes/reorder")
    suspend fun reorderNotes(@Body b: List<ReorderItem>): Response<Unit>

    // ── Note-groups ────────────────────────────────────────
    @GET("api/v1/note-groups")
    suspend fun getNoteGroups(@Query("projectId") projectId: String? = null): List<NoteGroupResponse>

    @POST("api/v1/note-groups")
    suspend fun createNoteGroup(@Body b: CreateNoteGroupRequest): NoteGroupResponse

    @PATCH("api/v1/note-groups/{id}")
    suspend fun updateNoteGroup(@Path("id") id: String, @Body b: UpdateNoteGroupRequest): NoteGroupResponse

    @DELETE("api/v1/note-groups/{id}")
    suspend fun deleteNoteGroup(@Path("id") id: String): Response<Unit>

    @PATCH("api/v1/note-groups/reorder")
    suspend fun reorderNoteGroups(@Body b: List<ReorderItem>): Response<Unit>

    // ── Focus-sessions (append-only) ───────────────────────
    @POST("api/v1/focus-sessions")
    suspend fun createFocusSession(@Body b: CreateFocusSessionRequest): FocusSessionResponse

    // ── Daily-plans (date-keyed upsert) ────────────────────
    @GET("api/v1/daily-plans/{date}")
    suspend fun getDailyPlan(@Path("date") date: String): DailyPlanResponse

    @PUT("api/v1/daily-plans/{date}")
    suspend fun putDailyPlan(@Path("date") date: String, @Body b: UpsertDailyPlanRequest): DailyPlanResponse
}
