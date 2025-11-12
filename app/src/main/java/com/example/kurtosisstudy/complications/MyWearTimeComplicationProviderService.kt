package com.example.kurtosisstudy.complications

import android.content.ComponentName
import android.util.Log
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

// Your storage accessor
import com.example.kurtosisstudy.DataStorageManager

class MyWearTimeComplicationProviderService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(id: Int, type: ComplicationType) {
        Log.d(TAG, "onComplicationActivated(id=$id, type=$type)")
    }

    /** Preview for the picker/editor — single type only */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.RANGED_VALUE) return null
        val previewValue = 150f // 2h30' preview
        return buildRanged(previewValue, GOAL_MINUTES, formatHm(previewValue.toInt()))
    }

    /** Live data — single path for RANGED_VALUE */
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.RANGED_VALUE) return null

        val wornMins = withContext(Dispatchers.IO) { readTodayWornMinutes() }
        val clamped = min(max(wornMins.toFloat(), 0f), GOAL_MINUTES)
        val label = formatHm(wornMins)

        return buildRanged(clamped, GOAL_MINUTES, label)
    }

    override fun onComplicationDeactivated(id: Int) {
        Log.d(TAG, "onComplicationDeactivated(id=$id)")
    }

    // ————— helpers —————

    private fun buildRanged(value: Float, max: Float, label: String): RangedValueComplicationData {
        return RangedValueComplicationData.Builder(
            value = value,
            min = 0f,
            max = max,
            contentDescription = PlainComplicationText.Builder("Watch worn $label today").build()
        )
            // This is the text you'll read in the watch face via [COMPLICATION.TEXT]
            .setText(PlainComplicationText.Builder(label).build())
            .build()
    }

    companion object {
        private const val TAG = "WearTimeComp_kurtosisstudy"
        private const val GOAL_MINUTES = 840f // 14h goal (change to what you want)

        /** minutes -> "HhMM'" (e.g., 70 -> 1h10') */
        private fun formatHm(mins: Int): String {
            val h = mins / 60
            val m = mins % 60
            return if (m < 10) "${h}h0${m}'" else "${h}h${m}'"
        }

        /** Read today’s wornMinutes using your manager + DAO. */
        private suspend fun readTodayWornMinutes(): Int {
            val db = DataStorageManager.getMainDatabase()
            if (db == null) {
                Log.w(TAG, "Main database is null; returning 0")
                return 0
            }
            val dayKey = DataStorageManager.getDayForDB() // your existing helper (e.g., "yyyy-MM-dd")
            return try {
                // Expect: DailyWearTimeDao.getLastEntryForDay(String): DailyWearTimeEntity?
                db.dailyWearTimeDao().getLastEntryForDay(dayKey)?.wornMinutes ?: 0
            } catch (t: Throwable) {
                Log.e(TAG, "DAO read failed", t)
                0
            }
        }

        /** Call this after you update the DB so the complication refreshes immediately. */
        @JvmStatic
        fun requestComplicationUpdate(ctx: android.content.Context) {
            ComplicationDataSourceUpdateRequester
                .create(ctx, ComponentName(ctx, MyWearTimeComplicationProviderService::class.java))
                .requestUpdateAll()
        }
    }
}
