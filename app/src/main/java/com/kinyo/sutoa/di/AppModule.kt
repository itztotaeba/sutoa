package com.kinyo.sutoa.di

import com.kinyo.sutoa.data.source.FdroidApi
import com.kinyo.sutoa.data.source.FdroidSource
import com.kinyo.sutoa.data.source.GitHubApi
import com.kinyo.sutoa.data.source.GitHubSource
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Core DI module. New source plugins are registered in the ordered `provideAppSources` set
 * returning [com.kinyo.sutoa.data.source.AppSource] — nothing else changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
        )
        .build()

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory()) // reflection fallback for assets JSON etc.
        .build()

    @Provides
    @Singleton
    @FdroidApiQualifier
    fun provideFdroidApi(client: OkHttpClient, moshi: Moshi): FdroidApi = Retrofit.Builder()
        .baseUrl(FdroidSource.BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(FdroidApi::class.java)

    @Provides
    @Singleton
    @GithubApiQualifier
    fun provideGitHubApi(client: OkHttpClient, moshi: Moshi): GitHubApi = Retrofit.Builder()
        .baseUrl(GitHubSource.BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GitHubApi::class.java)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    // ─── Source plugins (ordered set — dedup priority fdroid > github) ──────
    //
    // The Set is provided in PRIORITY ORDER (LinkedHashSet preserves iteration
    // order; Hilt caches it as a Singleton). AppRepository flattens results in
    // this order and dedupes with distinctBy{packageName}, so an fdroid entry
    // always beats a github entry for the same package.
    //
    // To add a new source (GitLab, Codeberg, custom repo): implement AppSource,
    // then append it here at the desired priority position. No UI/ViewModel/
    // Repository changes required.

    @Provides
    @Singleton
    fun provideAppSources(
        @FdroidApiQualifier fdroidApi: FdroidApi,
        @GithubApiQualifier gitHubApi: GitHubApi,
        gitHubCatalog: com.kinyo.sutoa.data.source.GitHubCatalog,
    ): Set<com.kinyo.sutoa.data.source.AppSource> = linkedSetOf<com.kinyo.sutoa.data.source.AppSource>(
        FdroidSource(fdroidApi),   // priority 1st — wins dedup
        GitHubSource(gitHubApi, gitHubCatalog), // priority 2nd
    )

}
