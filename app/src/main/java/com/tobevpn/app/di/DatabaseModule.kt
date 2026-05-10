package com.tobevpn.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tobevpn.app.data.local.AppDatabase
import com.tobevpn.app.data.local.DatabasePassphrase
import com.tobevpn.app.data.local.dao.AppFilterDao
import com.tobevpn.app.data.local.dao.ServerDao
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.dao.TrafficLogDao
import com.tobevpn.app.data.local.dao.UsageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        val passphrase = DatabasePassphrase.getPassphrase(context)
        // If a pre-encryption (or corrupted/foreign-key) DB file exists,
        // SQLCipher will throw "file is not a database" on open. Drop the
        // file pre-emptively if that happens so the user isn't stuck with
        // a crash loop after upgrading from a non-SQLCipher build.
        ensureCipherCompatible(context, "tobevpn.db", passphrase)
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "tobevpn.db",
        )
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
    }

    // v8 → v9: SessionEntity gained an `email` column (moved from DataStore so
    // the user's email is encrypted at rest along with the rest of the session).
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session ADD COLUMN email TEXT")
        }
    }

    // v9 → v10: per-app VPN filter selection. Stores only the package names
    // chosen by the user; the mode (off/whitelist/blacklist) lives in
    // PrefsDataStore so a wipe of the encrypted DB doesn't strand an
    // empty whitelist that would block all traffic on next connect.
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS app_filter (" +
                    "packageName TEXT NOT NULL PRIMARY KEY" +
                    ")",
            )
        }
    }

    // v10 → v11: SessionEntity gained a `subscriptionUrl` column. The
    // URL contains a per-user secret key, so we moved it out of plaintext
    // PrefsDataStore into the encrypted session row — that lets us drop
    // the blanket Auto-Backup exclusion on tobevpn_prefs and start
    // restoring non-secret settings (filter mode, language, etc.) across
    // reinstalls.
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session ADD COLUMN subscriptionUrl TEXT")
        }
    }

    private fun ensureCipherCompatible(
        context: Context,
        dbName: String,
        passphrase: ByteArray,
    ) {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return
        try {
            net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                passphrase,
                null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY,
                null,
                null,
            ).close()
        } catch (_: Throwable) {
            dbFile.delete()
            java.io.File("${dbFile.absolutePath}-shm").delete()
            java.io.File("${dbFile.absolutePath}-wal").delete()
        }
    }

    @Provides
    fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideUsageDao(db: AppDatabase): UsageDao = db.usageDao()

    @Provides
    fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideTrafficLogDao(db: AppDatabase): TrafficLogDao = db.trafficLogDao()

    @Provides
    fun provideAppFilterDao(db: AppDatabase): AppFilterDao = db.appFilterDao()
}
