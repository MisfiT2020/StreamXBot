package com.metrolist.innertube.models.body

import com.metrolist.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class LikeBody(
    val context: Context,
    val target: Target,
) {
    
    @Serializable
    data class Target(
        val videoId: String? = null,
        val playlistId: String? = null,
    ) {
        companion object {
            fun video(id: String) = Target(videoId = id)
            fun playlist(id: String) = Target(playlistId = id)
        }
    }
}
