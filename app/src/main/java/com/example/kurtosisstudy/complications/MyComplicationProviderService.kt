package com.example.kurtosisstudy.complications

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.example.kurtosisstudy.ExerciseActivity
import com.example.kurtosisstudy.PrefsKeys

class MyComplicationProviderService : SuspendingComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        Log.d(TAG, "onComplicationActivated(): $complicationInstanceId")
    }


    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("💪🏽").build(),
                contentDescription = PlainComplicationText.Builder("emoji").build()
            ).build()
            else -> null
        }
    }


    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(TAG, "req type=${request.complicationType}")
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null

        val prefsDataStorage = applicationContext.getSharedPreferences(PrefsKeys.Data.PREFS, Context.MODE_PRIVATE)
        val weekId = prefsDataStorage.getInt(PrefsKeys.Data.WEEK_ID, 1)

        // Hide the complication when weekID is those weeks
        if (weekId==1 || weekId >=6) {
            return NoDataComplicationData()
        }

        val intent = Intent(applicationContext, ExerciseActivity::class.java).apply {
            action = "open_from_complication_${request.complicationInstanceId}" // ensure uniqueness
            putExtra(ExerciseActivity.EXTRA_LAUNCH_SOURCE, ExerciseActivity.SOURCE_COMPLICATION)
        }


        val tap = PendingIntent.getActivity(
            applicationContext,
            request.complicationInstanceId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("💪🏽").build(),
            contentDescription = PlainComplicationText.Builder("Open exercise").build()
        ).setTapAction(tap).build()
    }


    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(TAG, "onComplicationDeactivated(): $complicationInstanceId")
    }

    companion object {
        private const val TAG = "Complication_KurtosisStudy"

        // Call this whenever state changes to refresh the complication immediately.
        @JvmStatic
        fun requestComplicationUpdate(ctx: android.content.Context) {
            ComplicationDataSourceUpdateRequester
                .create(ctx, ComponentName(ctx, MyComplicationProviderService::class.java))
                .requestUpdateAll()
        }
    }
}