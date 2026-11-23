package com.bgdeveloper.ridelirium.di

import android.content.Context
import androidx.room.Room
import com.bgdeveloper.ridelirium.data.AppDatabase
import com.bgdeveloper.ridelirium.data.TrackPointDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ridelirium.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

    @Provides
    fun trackPointDao(db: AppDatabase): TrackPointDao = db.trackPointDao()
}
