package dev.mikadore.nuntius

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.mikadore.nuntius.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStorage @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val db: AppDatabase,
)
