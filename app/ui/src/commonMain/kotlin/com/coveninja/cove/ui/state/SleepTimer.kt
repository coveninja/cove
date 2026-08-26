package com.coveninja.cove.ui.state

/**
 * What the viewer asked to happen when they stop watching.
 *
 * Two shapes rather than one, because "stop in 30 minutes" and "stop after this episode"
 * answer different questions and cannot be expressed as each other: an episode has no fixed
 * length, and a duration does not know where an episode ends.
 */
sealed interface SleepTimerChoice {
    data object Off : SleepTimerChoice

    /** Let this episode finish, then stop instead of advancing. */
    data object AfterThisEpisode : SleepTimerChoice

    /** Stop once [minutes] have passed, wherever playback happens to be. */
    data class After(val minutes: Int) : SleepTimerChoice
}

/** The choices offered in the menu. A film gets everything but [SleepTimerChoice.AfterThisEpisode]. */
val SLEEP_TIMER_MINUTES = listOf(15, 30, 60, 90)

/**
 * An armed sleep timer, or the absence of one.
 *
 * [remainingSeconds] is carried rather than an expiry instant so this stays a plain value with
 * no clock in it: the composition owns the tick, and everything decided here can be decided
 * from numbers alone.
 */
data class SleepTimer(
    val choice: SleepTimerChoice = SleepTimerChoice.Off,
    /** Seconds left on a duration timer. Null for every other choice, which do not count down. */
    val remainingSeconds: Int? = null,
) {
    val armed: Boolean get() = choice != SleepTimerChoice.Off

    /** What the menu and the badge say, or null when nothing is armed. */
    val label: String?
        get() = when (choice) {
            SleepTimerChoice.Off -> null
            SleepTimerChoice.AfterThisEpisode -> "After this episode"
            is SleepTimerChoice.After -> {
                val left = remainingSeconds ?: (choice.minutes * 60)
                // Rounded up: a timer reading "0 min left" for the last fifty-nine seconds
                // looks stopped rather than nearly stopped.
                val minutes = (left + 59) / 60
                if (minutes <= 1) "1 min left" else "$minutes min left"
            }
        }

    companion object {
        val Off = SleepTimer()
    }
}

/** Arms [choice], seeding the countdown for a duration so the first tick is not a special case. */
fun armSleepTimer(choice: SleepTimerChoice): SleepTimer = SleepTimer(
    choice = choice,
    remainingSeconds = (choice as? SleepTimerChoice.After)?.let { it.minutes * 60 },
)

/**
 * Counts a duration timer down by [elapsedSeconds], never past zero.
 *
 * Only [SleepTimerChoice.After] moves: the other two have nothing to count. Clamping at zero
 * rather than going negative means [sleepTimerElapsed] can be a simple comparison and a timer
 * that fired stays fired until something re-arms it.
 */
fun tickSleepTimer(timer: SleepTimer, elapsedSeconds: Int): SleepTimer {
    if (timer.choice !is SleepTimerChoice.After) return timer
    val remaining = timer.remainingSeconds ?: (timer.choice.minutes * 60)
    return timer.copy(remainingSeconds = (remaining - elapsedSeconds).coerceAtLeast(0))
}

/** True once a duration timer has run out. Never true for the other choices, which never expire. */
fun sleepTimerElapsed(timer: SleepTimer): Boolean =
    timer.choice is SleepTimerChoice.After && (timer.remainingSeconds ?: 1) <= 0

/**
 * Whether the next episode may start on its own.
 *
 * The sleep timer and autoplay both have an opinion about what happens at the end of an
 * episode, and "stop after this one" is the more specific of the two — it is asked for
 * *knowing* autoplay is on, since with autoplay off there would be nothing to stop. Deciding
 * it here rather than at the end card keeps the two from racing: the card would otherwise
 * start the next episode while the timer was still working out that it should not.
 */
fun autoAdvanceAllowed(autoPlay: Boolean, timer: SleepTimer): Boolean =
    autoPlay && timer.choice != SleepTimerChoice.AfterThisEpisode
