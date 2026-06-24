package net.qmindtech.tmap.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifies the dispatcher FocusController's countdown runs on (Default in prod; test dispatcher in tests). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FocusDispatcher

@Module
@InstallIn(SingletonComponent::class)
object FocusModule {

    @Provides
    @Singleton
    @FocusDispatcher
    fun provideFocusDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
