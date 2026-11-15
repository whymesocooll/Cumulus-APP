package cumulus.battery.stats.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager

private const val MAX_RETAINED_SESSION_COUNT = 10
private const val SESSION_MODE_NONE = 0
private const val SESSION_MODE_CHARGING = 1
private const val SESSION_MODE_DISCHARGING = 2

data class BatteryStatsItem(
    var timestamp: Long = 0,
    var packageName: String = "",
    var batteryStatus: Int = 0,
    var batteryPercentage: Int = 0,
    var batteryPower: Int = 0,
    var batteryTemperature: Int = 0
)

class BatteryDataSQLHelper(context: Context) :
    SQLiteOpenHelper(context, "battery_data.db", null, 1) {

    private val tableName = "BatteryData"

    override fun onCreate(dataBase: SQLiteDatabase?) {
        if (dataBase != null) {
            createTable(dataBase)
        }
    }

    override fun onUpgrade(dataBase: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        if (dataBase != null) {
            createTable(dataBase)
        }
    }

    fun createTable(dataBase: SQLiteDatabase) {
        val createTableEntries = """
            CREATE TABLE IF NOT EXISTS ${tableName} (
                timestamp INTEGER PRIMARY KEY,
                packageName TEXT NOT NULL,
                batteryStatus INTEGER NOT NULL,
                batteryPercentage INTEGER NOT NULL,
                batteryPower INTEGER NOT NULL,
                batteryTemperature INTEGER NOT NULL
            )
        """.trimIndent()

        try {
            dataBase.execSQL(createTableEntries)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun optimize() {
        try {
            val dataBase = writableDatabase
            val retentionTimestamp = getRetentionTimestamp(dataBase)
            if (retentionTimestamp != null) {
                dataBase.execSQL("DELETE FROM ${tableName} WHERE timestamp < ${retentionTimestamp}")
            }
            dataBase.execSQL("PRAGMA synchronous = NORMAL")
            dataBase.execSQL("PRAGMA wal_checkpoint(FULL)")
            dataBase.execSQL("PRAGMA optimize")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 只保留最近 MAX_RETAINED_SESSION_COUNT 次充电会话与放电会话,
    // 返回仍需保留的最早时间戳(之前的记录可删除); 无会话时返回 null
    private fun getRetentionTimestamp(dataBase: SQLiteDatabase): Long? {
        val chargingSessionStarts = ArrayDeque<Long>()
        val dischargingSessionStarts = ArrayDeque<Long>()
        var sessionMode = SESSION_MODE_NONE

        dataBase.query(
            tableName,
            arrayOf("timestamp", "batteryStatus"),
            null,
            null,
            null,
            null,
            "timestamp ASC"
        ).use { cursor ->
            val timestampColumn = cursor.getColumnIndexOrThrow("timestamp")
            val batteryStatusColumn = cursor.getColumnIndexOrThrow("batteryStatus")
            while (cursor.moveToNext()) {
                val recordMode = getSessionMode(cursor.getInt(batteryStatusColumn))
                // 未知状态不参与会话划分, 避免偶发的 UNKNOWN 记录把一次会话切碎
                if (recordMode == SESSION_MODE_NONE || recordMode == sessionMode) {
                    continue
                }
                sessionMode = recordMode
                val sessionStarts = if (recordMode == SESSION_MODE_CHARGING) {
                    chargingSessionStarts
                } else {
                    dischargingSessionStarts
                }
                sessionStarts.addLast(cursor.getLong(timestampColumn))
                if (sessionStarts.size > MAX_RETAINED_SESSION_COUNT) {
                    sessionStarts.removeFirst()
                }
            }
        }

        val chargingSessionBegin = chargingSessionStarts.firstOrNull()
        val dischargingSessionBegin = dischargingSessionStarts.firstOrNull()
        return when {
            chargingSessionBegin == null -> dischargingSessionBegin
            dischargingSessionBegin == null -> chargingSessionBegin
            else -> minOf(chargingSessionBegin, dischargingSessionBegin)
        }
    }

    private fun getSessionMode(batteryStatus: Int): Int {
        return when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> SESSION_MODE_CHARGING

            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> SESSION_MODE_DISCHARGING

            else -> SESSION_MODE_NONE
        }
    }

    fun insert(item: BatteryStatsItem) {
        val dataBase = writableDatabase
        createTable(dataBase)

        try {
            val contentValues = ContentValues()
            contentValues.apply {
                put("timestamp", item.timestamp)
                put("packageName", item.packageName)
                put("batteryStatus", item.batteryStatus)
                put("batteryPercentage", item.batteryPercentage)
                put("batteryPower", item.batteryPower)
                put("batteryTemperature", item.batteryTemperature)
            }
            dataBase.insertWithOnConflict(
                tableName,
                null,
                contentValues,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun readAll(): MutableList<BatteryStatsItem>? {
        try {
            val records: MutableList<BatteryStatsItem> = mutableListOf()
            readableDatabase.query(
                tableName,
                null,
                null,
                null,
                null,
                null,
                "timestamp ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val item = BatteryStatsItem()
                    item.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                    item.packageName = cursor.getString(cursor.getColumnIndexOrThrow("packageName"))
                    item.batteryStatus =
                        cursor.getInt(cursor.getColumnIndexOrThrow("batteryStatus"))
                    item.batteryPercentage =
                        cursor.getInt(cursor.getColumnIndexOrThrow("batteryPercentage"))
                    item.batteryPower =
                        cursor.getInt(cursor.getColumnIndexOrThrow("batteryPower"))
                    item.batteryTemperature =
                        cursor.getInt(cursor.getColumnIndexOrThrow("batteryTemperature"))
                    records.add(item)
                }
            }
            return records
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun readLatest(): BatteryStatsItem? {
        try {
            readableDatabase.query(
                tableName,
                null,
                null,
                null,
                null,
                null,
                "timestamp DESC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val item = BatteryStatsItem()
                    item.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                    item.packageName = cursor.getString(cursor.getColumnIndexOrThrow("packageName"))
                    item.batteryStatus =
                        cursor.getInt(cursor.getColumnIndexOrThrow("batteryStatus"))
                    item.batteryPercentage =
                        cursor.getInt(cursor.getColumnIndexOrThrow("batteryPercentage"))
                    item.batteryPower =
                        cursor.getInt(cursor.getColumnIndexOrThrow("batteryPower"))
                    item.batteryTemperature =
                        cursor.getInt(cursor.getColumnIndexOrThrow("batteryTemperature"))
                    return item
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun deleteAll() {
        try {
            writableDatabase.execSQL("DROP TABLE IF EXISTS ${tableName}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
