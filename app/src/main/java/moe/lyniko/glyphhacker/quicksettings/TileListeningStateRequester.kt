package moe.lyniko.glyphhacker.quicksettings

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object TileListeningStateRequester {
    fun requestRecognitionTileListeningState(context: Context) {
        val appContext = context.applicationContext
        TileService.requestListeningState(
            appContext,
            ComponentName(appContext, RecognitionTileService::class.java),
        )
    }
}
