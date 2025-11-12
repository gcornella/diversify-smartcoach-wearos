package com.example.kurtosisstudy.complications

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

// Your storage accessor
import com.example.kurtosisstudy.DataStorageManager
import com.example.kurtosisstudy.PrefsKeys

class MyProgressComplicationProviderService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(id: Int, type: ComplicationType) {
        Log.d(TAG, "onComplicationActivated(id=$id, type=$type)")
    }

    /** Preview for the picker/editor — single type only */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.RANGED_VALUE) return null
        val previewValue = 10f // 10' preview
        val previewMax   = DEFAULT_GOAL_MINUTES.toFloat()
        return buildRanged(previewValue, previewMax, formatHm(previewValue.toInt()))
    }

    /** Live data — single path for RANGED_VALUE */
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.RANGED_VALUE) return null

        val prefsDataStorage = applicationContext.getSharedPreferences(PrefsKeys.Data.PREFS, Context.MODE_PRIVATE)
        val weekId = prefsDataStorage.getInt(PrefsKeys.Data.WEEK_ID, 1)

        // TODO - 2nd STUDY (comment next section to always show progress circle)
        // Hide the complication during the first and 6+ weeks
        if (weekId==1 || weekId >= 6) {
            return NoDataComplicationData()
        }

        val today = loadTodayProgressAndGoal()
        val g = max(today.goalMinutes, 1)          // max >= 1
        val p = today.progressMinutes.coerceAtLeast(0)
        val label = if (p <= g) formatHm(p) else "+${p - g}'"  // truthful label past goal
        Log.d(TAG, "onComplicationActivated(g=$g, p=$p, label=$label)")

        val value = p.coerceAtMost(g).toFloat()            // <-- cap for the ring
        val maxV  = g.toFloat()

        Log.d(TAG, "buildRanged(value=$value, maxV=$maxV")

        return buildRanged(value, maxV, label)
    }


    companion object {
        private const val TAG = "ProgressProviderComplication_KurtosisStudy"
        private const val DEFAULT_GOAL_MINUTES = 840 // 14h fallback

        /** minutes -> "HhMM'" (e.g., 70 -> 1h10') */
        private fun formatHm(mins: Int): String {
            val h = mins / 60
            val m = mins % 60
            return if (m < 10) "${h}h0${m}'" else "${h}h${m}'"
        }

        /** Single helper that reads today's progress + goal (IO thread). */
        private suspend fun loadTodayProgressAndGoal(): TodaySnapshot =
            withContext(Dispatchers.IO) {
                val dayKey  = DataStorageManager.getDayForDB()
                val mainDb  = DataStorageManager.getMainDatabase()
                val dailyDb = DataStorageManager.getDailyDatabase() // your accessor

                val progress: Int = try {
                    mainDb?.dailyCumulativeDao()?.getLastEntryForDay(dayKey)?.cumulative ?: 0
                } catch (_: Throwable) { 0 }

                val goal: Int = try {
                    dailyDb
                        ?.adjustedDailyGoalDao()
                        ?.getLastGoal()
                        ?.adjustedDailyGoal
                        ?.takeIf { it > 0 }           // or { it != 0 } if you only want to treat 0 as invalid
                        ?: DEFAULT_GOAL_MINUTES
                } catch (_: Throwable) {
                    DEFAULT_GOAL_MINUTES
                }
                Log.w(TAG, "Updating complication to: "+ progress + "; out of: "+ goal)
                TodaySnapshot(progress, goal)
            }

        private data class TodaySnapshot(
            val progressMinutes: Int,
            val goalMinutes: Int
        )

        /** Call this after you update the DB so the complication refreshes immediately. */
        @JvmStatic
        fun requestComplicationUpdate(ctx: android.content.Context) {
            ComplicationDataSourceUpdateRequester
                .create(ctx, ComponentName(ctx, MyProgressComplicationProviderService::class.java))
                .requestUpdateAll()
        }
    }

    private fun buildRanged(value: Float, max: Float, label: String): RangedValueComplicationData {
        return RangedValueComplicationData.Builder(
            value = value,
            min = 0f,
            max = max,
            contentDescription = PlainComplicationText.Builder("Watch progress $label today").build()
        )
            // This is the text you'll read in the watch face via [COMPLICATION.TEXT]
            .setText(PlainComplicationText.Builder(label).build())
            .setTitle(PlainComplicationText.Builder(label).build())  // fallback: read as [COMPLICATION.TITLE]
            .build()
    }

    override fun onComplicationDeactivated(id: Int) {
        Log.d(TAG, "onComplicationDeactivated(id=$id)")
    }
}
