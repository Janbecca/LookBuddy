package com.lookbuddy.app.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.DELETE
import retrofit2.http.Url
import retrofit2.Response
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface LookBuddyApi {
    @POST("v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): TokenPairDto

    @POST("v1/auth/login")
    suspend fun login(@Body request: LoginRequest): TokenPairDto

    @POST("v1/auth/refresh") suspend fun refresh(@Body request: RefreshRequest): TokenPairDto
    @POST("v1/auth/logout") suspend fun logout(@Body request: RefreshRequest): Response<Unit>
    @GET("v1/me") suspend fun me(): UserDto
    @GET("v1/preferences") suspend fun preferences(): PreferencesDto
    @PATCH("v1/preferences") suspend fun patchPreferences(@Body body: JsonObject): PreferencesDto
    @DELETE("v1/account") suspend fun deleteAccount(): Response<Unit>
    @POST("v1/interventions/{id}/displayed") suspend fun displayed(@Path("id") id: String): JsonObject
    @GET suspend fun getJson(@Url path: String): JsonElement
    @POST suspend fun postJson(@Url path: String, @Body body: JsonElement): JsonElement
    @PATCH suspend fun patchJson(@Url path: String, @Body body: JsonElement): JsonElement
    @DELETE suspend fun deleteJson(@Url path: String): Response<Unit>

    @GET("v1/reminders/pending")
    suspend fun pendingReminders(): List<ReminderDto>

    @POST("v1/reminders/{id}/respond")
    suspend fun respondReminder(@Path("id") id: String, @Body request: ReminderResponseRequest): ReminderDto

    @POST("v1/diaries/{date}/generate")
    suspend fun generateDiary(@Path("date") date: String): DiaryDto

    @GET("v1/diaries/{date}")
    suspend fun getDiary(@Path("date") date: String): DiaryDto
}

