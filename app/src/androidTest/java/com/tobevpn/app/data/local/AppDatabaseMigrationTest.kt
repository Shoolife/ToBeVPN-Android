package com.tobevpn.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tobevpn.app.di.DatabaseModule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Before
    fun removePreviousTestDatabase() {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration19To20PreservesBothServerCachesAndAddsTransportDefaults() {
        helper.createDatabase(TEST_DATABASE, 19).use { database ->
            insertVersion19Server(database, STANDARD_TABLE, "standard-id")
            insertVersion19Server(database, BYPASS_TABLE, "bs:legacy-id")
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            20,
            true,
            DatabaseModule.MIGRATION_19_20,
        ).use { database ->
            assertMigratedServer(database, STANDARD_TABLE, "standard-id")
            assertMigratedServer(database, BYPASS_TABLE, "bs:legacy-id")
        }
    }

    private fun insertVersion19Server(
        database: SupportSQLiteDatabase,
        table: String,
        id: String,
    ) {
        database.execSQL(
            "INSERT INTO $table (" +
                "id, name, address, port, uuid, flow, security, sni, fingerprint, " +
                "publicKey, shortId, network, path, mode, spx, country, isOnline, sortOrder" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                id,
                "Saved server",
                "node.example",
                443,
                "550e8400-e29b-41d4-a716-446655440000",
                "",
                "tls",
                "front.example",
                "chrome",
                "",
                "",
                "ws",
                "/vpn",
                "",
                "",
                "NL",
                1,
                7,
            ),
        )
    }

    private fun assertMigratedServer(
        database: SupportSQLiteDatabase,
        table: String,
        expectedId: String,
    ) {
        database.query(
            "SELECT id, name, host, alpn, headerType, serviceName, extra, sortOrder " +
                "FROM $table",
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(expectedId, cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals(
                "Saved server",
                cursor.getString(cursor.getColumnIndexOrThrow("name")),
            )
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("host")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("alpn")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("headerType")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("serviceName")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("extra")))
            assertEquals(7, cursor.getInt(cursor.getColumnIndexOrThrow("sortOrder")))
        }
    }

    private companion object {
        const val TEST_DATABASE = "app-database-migration-test"
        const val STANDARD_TABLE = "servers"
        const val BYPASS_TABLE = "base_station_bypass_servers"
    }
}
