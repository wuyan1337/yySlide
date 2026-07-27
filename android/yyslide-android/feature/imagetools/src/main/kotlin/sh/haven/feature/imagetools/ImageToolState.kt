package sh.haven.feature.imagetools

import android.graphics.Bitmap

sealed interface ImageToolState {
    data object Idle : ImageToolState
    data object Loading : ImageToolState
    data class Loaded(
        val bitmap: Bitmap,
        val cachePath: String,
        val fileName: String,
        val width: Int,
        val height: Int,
    ) : ImageToolState
    data class Processing(val label: String) : ImageToolState
    data class Preview(
        val original: Bitmap,
        val result: Bitmap,
        val resultCachePath: String,
        val fileName: String,
    ) : ImageToolState
    data class Error(val message: String) : ImageToolState
}
