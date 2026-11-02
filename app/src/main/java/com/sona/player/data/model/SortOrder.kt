package com.sona.player.data.model

import androidx.annotation.StringRes
import com.sona.player.R

enum class SortOrder(@StringRes val displayNameRes: Int) {
    TITLE_ASC(R.string.sort_title_asc),
    TITLE_DESC(R.string.sort_title_desc),
    DATE_ADDED_DESC(R.string.sort_date_added_desc),
    DATE_ADDED_ASC(R.string.sort_date_added_asc),
    ARTIST_ASC(R.string.sort_artist_asc),
    DURATION_DESC(R.string.sort_duration_desc),
    DURATION_ASC(R.string.sort_duration_asc)
}
