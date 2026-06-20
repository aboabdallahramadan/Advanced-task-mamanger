package net.qmindtech.tmap.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.TokenStore
import net.qmindtech.tmap.data.remote.AuthInterceptor
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.remote.TokenAuthenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Prod base URL (Global Constraints). The local-dev `http://10.0.2.2:5188` override is a build-config concern. */
    const val BASE_URL: String = "https://api-tasks.qmindtech.net/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenStore: TokenStore): AuthInterceptor = AuthInterceptor(tokenStore)

    @Provides
    @Singleton
    fun provideTokenAuthenticator(
        authRepository: AuthRepository,
        tokenStore: TokenStore,
    ): TokenAuthenticator = TokenAuthenticator(authRepository, tokenStore)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): TmapApiService =
        retrofit.create(TmapApiService::class.java)
}
