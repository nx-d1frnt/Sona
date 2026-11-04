package com.sona.player.data.model

data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val smartType: String? = null,
    val seedSongId: Long? = null
) {
    val isSmart: Boolean get() = !smartType.isNullOrEmpty()
}
