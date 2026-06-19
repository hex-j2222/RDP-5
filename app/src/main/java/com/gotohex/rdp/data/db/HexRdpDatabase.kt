package com.gotohex.rdp.data.db

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gotohex.rdp.data.model.RdpProfile
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration  // ← هذا السطر كان مفقوداً
@Dao
interface RdpProfileDao {
    @Query("SELECT * FROM rdp_profiles ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllProfiles(): Flow<List<RdpProfile>>

    @Query("SELECT * FROM rdp_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): RdpProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: RdpProfile)

    @Update
    suspend fun updateProfile(profile: RdpProfile)

    @Delete
    suspend fun deleteProfile(profile: RdpProfile)

    @Query("UPDATE rdp_profiles SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("UPDATE rdp_profiles SET lastScreenshotPath = :path WHERE id = :id")
    suspend fun updateScreenshot(id: String, path: String)

    @Query("UPDATE rdp_profiles SET isConnected = :connected WHERE id = :id")
    suspend fun updateConnectionState(id: String, connected: Boolean)
}

/**
 * v1 -> v2: introduced multi-protocol support (RDP / VNC / SSH).
 * Adds protocolType plus RD Gateway, VNC, and SSH columns. All new columns
 * are given safe defaults so existing RDP profiles keep working unmodified
 * (they implicitly become protocolType = 'RDP').
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN protocolType TEXT NOT NULL DEFAULT 'RDP'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayHost TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayPort INTEGER NOT NULL DEFAULT 443")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayUsername TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayPassword TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayDomain TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vncViewOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshAuthType TEXT NOT NULL DEFAULT 'PASSWORD'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshPrivateKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshPrivateKeyPassphrase TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [RdpProfile::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class HexRdpDatabase : RoomDatabase() {
    abstract fun rdpProfileDao(): RdpProfileDao

    companion object {
        const val DATABASE_NAME = "hex_rdp_database"
    }
}
