package com.calmpad.di

import android.content.Context
import androidx.room.Room
import com.calmpad.data.CalmPadDatabase
import com.calmpad.data.NoteDao
import com.calmpad.data.PreferencesRepository
import com.calmpad.data.SectionDao
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
    fun provideDatabase(@ApplicationContext context: Context): CalmPadDatabase =
        Room.databaseBuilder(context, CalmPadDatabase::class.java, "calmpad.db")
            .build()

    @Provides
    fun provideNoteDao(db: CalmPadDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideSectionDao(db: CalmPadDatabase): SectionDao = db.sectionDao()

    @Provides
    @Singleton
    fun providePreferencesRepository(@ApplicationContext context: Context): PreferencesRepository =
        PreferencesRepository(context)
}
