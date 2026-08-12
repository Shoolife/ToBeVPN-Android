package com.tobevpn.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import com.tobevpn.app.data.local.AppDatabase
import com.tobevpn.app.data.local.DatabasePassphrase
import com.tobevpn.app.data.local.dao.AppFilterDao
import com.tobevpn.app.data.local.dao.BaseStationBypassServerDao
import com.tobevpn.app.data.local.dao.PendingPromocodeActivationDao
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
            .addMigrations(
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
            )
            .fallbackToDestructiveMigration(dropAllTables = false)
            // TRUNCATE journal (no WAL) — committed writes land in the
            // main .db file synchronously, so a process kill / force-stop
            // between sessions can't lose data that's already been
            // emitted by the DAO observer. Default (WAL) batches writes
            // into a separate -wal file that's only checkpointed back
            // periodically; a SIGKILL between commit and checkpoint
            // silently loses the staged rows on next open, which is
            // exactly the symptom we saw on app_filter and usage tables.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }

    // v8 → v9: SessionEntity gained an `email` column (moved from DataStore so
    // the user's email is encrypted at rest along with the rest of the session).
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session ADD COLUMN email TEXT")
        }
    }

    // v9 → v10: legacy per-app VPN filter selection table. Newer builds keep
    // package selection in PrefsDataStore as the update-safe source of truth;
    // this table remains for migration from older installs and best-effort
    // local mirroring.
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

    // v11 -> v12: cache the human-readable current tariff name returned by
    // the backend current-plan endpoint. The enum userPlan remains the coarse
    // behaviour flag (FREE_TRIAL / PAID / ADMIN / EXPIRED).
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session ADD COLUMN planDisplayName TEXT")
        }
    }

    // v12 -> v13: cache the backend current-plan `is_admin` flag. It does
    // not change subscription tier; it unlocks extended diagnostic UI.
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session ADD COLUMN isAdminProfile INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v13 -> v14: persist the subscription-profile order of servers. Room/SQLite
    // does not guarantee row order without ORDER BY, so the list could jump
    // after refreshes or metadata enrichment rewrote the cache.
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE servers ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        }
    }

    // Telegram name + @username parsed from the panel user's description.
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session ADD COLUMN telegramName TEXT")
            db.execSQL("ALTER TABLE session ADD COLUMN telegramUsername TEXT")
        }
    }

    // Cached Telegram avatar file path.
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session ADD COLUMN photoUrl TEXT")
        }
    }

    // v16 -> v17: persist the idempotency key for promocode activations whose
    // network outcome is not yet known. This table is inside SQLCipher and is
    // deliberately separate from Auto-Backup-enabled preferences.
    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS pending_promocode_activations (" +
                    "telegramId INTEGER NOT NULL, " +
                    "code TEXT NOT NULL, " +
                    "requestId TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(telegramId, code))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_pending_promocode_activations_requestId " +
                    "ON pending_promocode_activations(requestId)",
            )
        }
    }

    // v17 -> v18: keep the public base-station-bypass profile separate from
    // the user's normal subscription servers. The database is SQLCipher-backed,
    // so VLESS credentials are never copied into plaintext DataStore prefs.
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS base_station_bypass_servers (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "address TEXT NOT NULL, " +
                    "port INTEGER NOT NULL, " +
                    "uuid TEXT NOT NULL, " +
                    "flow TEXT NOT NULL, " +
                    "security TEXT NOT NULL, " +
                    "sni TEXT NOT NULL, " +
                    "fingerprint TEXT NOT NULL, " +
                    "publicKey TEXT NOT NULL, " +
                    "shortId TEXT NOT NULL, " +
                    "network TEXT NOT NULL, " +
                    "path TEXT NOT NULL, " +
                    "mode TEXT NOT NULL, " +
                    "spx TEXT NOT NULL, " +
                    "country TEXT NOT NULL, " +
                    "isOnline INTEGER NOT NULL, " +
                    "sortOrder INTEGER NOT NULL)",
            )
        }
    }

    // v18 -> v19: traffic history now distinguishes normal subscription
    // servers from the Android-only base-station-bypass profile. The bypass
    // feature did not exist before v18, so historical rows are correctly
    // classified as STANDARD.
    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE traffic_log ADD COLUMN " +
                    "serverSource TEXT NOT NULL DEFAULT 'STANDARD'",
            )
        }
    }

    // v19 -> v20: retain transport parameters used by WS/TLS/XHTTP profiles.
    // Both caches share the Server domain model, so migrate them together.
    internal val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf("servers", "base_station_bypass_servers").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN host TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $table ADD COLUMN alpn TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $table ADD COLUMN headerType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $table ADD COLUMN serviceName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $table ADD COLUMN extra TEXT NOT NULL DEFAULT ''")
            }
        }
    }

    /**
     * Probe the DB file to make sure it's openable with the current
     * SQLCipher passphrase. Originally this wiped the file on any
     * exception ("upgrading from non-SQLCipher build" recovery), but
     * the catch-all was too aggressive — *any* transient failure
     * (UnsatisfiedLinkError on the first open before
     * System.loadLibrary("sqlcipher") finished registering JNI symbols,
     * temporary file locks, etc.) would silently delete the user's
     * data on every cold start.
     *
     * We now only wipe on **real** corruption (a SQLite-level error
     * like "file is not a database"), and rethrow anything else so the
     * caller can decide. NativeMethodNotFoundError (the "No
     * implementation found for nativeOpen" race that ate our writes)
     * no longer triggers a wipe.
     */
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
        } catch (_: android.database.SQLException) {
            // Real SQLite-level failure (decryption mismatch, corruption)
            // — the file is unrecoverable, drop it so Room recreates schema.
            dbFile.delete()
            java.io.File("${dbFile.absolutePath}-shm").delete()
            java.io.File("${dbFile.absolutePath}-wal").delete()
        } catch (_: Throwable) {
            // Anything else (JNI registration race, file lock, permission
            // hiccup) — SKIP the wipe. Room's own open path will surface
            // a real corruption error if there is one, while transient
            // failures don't cost us the user's data on every cold start.
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

    @Provides
    fun providePendingPromocodeActivationDao(
        db: AppDatabase,
    ): PendingPromocodeActivationDao = db.pendingPromocodeActivationDao()

    @Provides
    fun provideBaseStationBypassServerDao(
        db: AppDatabase,
    ): BaseStationBypassServerDao = db.baseStationBypassServerDao()
}
