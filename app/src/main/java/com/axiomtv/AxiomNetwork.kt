package com.axiomtv

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

/** The app's shared HTTP client (provider API, TV guide, TMDB, updates). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StandardOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AxiomNetworkModule {
    @StandardOkHttpClient
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}
