package com.kinyo.sutoa.di

import android.content.Context
import androidx.room.Room
import com.kinyo.sutoa.data.cache.AppCacheDao
import com.kinyo.sutoa.data.cache.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .fallbackToDestructiveMigration() // MVP: schema v1, no migrations yet
            .build()

    @Provides
    fun provideAppCacheDao(db: AppDatabase): AppCacheDao = db.appCacheDao()
}
