package net.qmindtech.tmap.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.ui.you.SignOutAction
import javax.inject.Named

/**
 * Hilt bindings for YouViewModel (P9.6 / P9.10).
 * - Provides [SignOutAction] bound to [AuthRepository.logout] so the VM never re-implements teardown (§5.3).
 * - Provides the [pendingCount] flow from [OutboxRepository.observeUnparkedCount()] under the
 *   @Named("pendingCount") qualifier so the pure-JVM test can inject a [MutableStateFlow] directly.
 */
@Module
@InstallIn(ViewModelComponent::class)
object YouModule {

    @Provides @ViewModelScoped
    fun provideSignOutAction(authRepository: AuthRepository): SignOutAction =
        SignOutAction { authRepository.logout() }

    @Provides @ViewModelScoped
    @Named("pendingCount")
    fun providePendingCount(outboxRepository: OutboxRepository): Flow<Int> =
        outboxRepository.observeUnparkedCount()
}
