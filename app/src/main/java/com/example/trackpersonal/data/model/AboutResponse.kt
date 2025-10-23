package com.example.trackpersonal.data.model

data class AboutResponse(
    val success: Boolean,
    val status: Int,
    val status_message: String,
    val message: String,
    val data: AboutData?
)

data class AboutData(
    val id: Int?,
    val title: String?,
    val content: String?,
    val image_url: String?,
    val video_url: String?,
    val dev: String?,
    val version: String?,
    val created_at: String?,
    val updated_at: String?
) {
    // biar gampang dipakai di app (snake_case → camelCase)
    val imageUrl get() = image_url
    val videoUrl get() = video_url
}
