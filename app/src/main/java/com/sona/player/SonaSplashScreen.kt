package com.sona.player

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Build
import java.time.Duration
import java.time.Instant

/** Let the native icon finish its brief entrance before revealing the first app frame. */
internal fun Activity.configureSplashScreen() {
    setTheme(R.style.Theme_Sona)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

    splashScreen.setOnExitAnimationListener { splash ->
        if (!ValueAnimator.areAnimatorsEnabled()) {
            splash.remove()
            return@setOnExitAnimationListener
        }

        // Count only the unplayed portion; slow startups need no additional hold.
        val elapsed = splash.iconAnimationStart?.let {
            Duration.between(it, Instant.now()).toMillis()
        } ?: 600L
        val remaining = ((splash.iconAnimationDuration?.toMillis() ?: 0L) - elapsed)
            .coerceIn(0L, 600L)

        splash.animate()
            .alpha(0f)
            .setStartDelay(remaining)
            .setDuration(160L)
            .withEndAction { splash.remove() }
            .start()
    }
}
