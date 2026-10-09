package dev.mikadore.nuntius.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.mikadore.nuntius.network.AuthSession
import dev.mikadore.nuntius.network.AuthSessionImpl
import dev.mikadore.nuntius.network.AuthenticatedApiClient
import dev.mikadore.nuntius.network.AuthenticatedApiClientImpl
import dev.mikadore.nuntius.network.AuthenticatedBearerInterceptor
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideAuthenticatedOkHttpClient(authSession: AuthSession): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthenticatedBearerInterceptor(authSession))
            // Do not let a redirect forward an authenticated request to another origin.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthenticatedApiBindings {
    @Binds
    @Singleton
    abstract fun bindAuthSession(implementation: AuthSessionImpl): AuthSession

    @Binds
    @Singleton
    abstract fun bindAuthenticatedApiClient(
        implementation: AuthenticatedApiClientImpl,
    ): AuthenticatedApiClient
}
