package org.lunatranslator.mobile

data class GameTranslationWork(val frame: GameTextFrame, val manual: Boolean, val generation: Long, val revision: Long)

/** Keep explicit refreshes responsive, including while automatic capture is paused. */
class GameTranslationRequests {
    private val automatic = GameTextQueue()
    private var generation = 0L
    private var revision = 0L
    private var latest: GameTextFrame? = null
    private var manual: GameTranslationWork? = null
    private var retryAt = 0L

    @Synchronized fun clear() {
        generation++
        revision++
        automatic.clear()
        latest = null
        manual = null
        retryAt = 0
    }

    @Synchronized fun offer(frame: GameTextFrame) {
        automatic.offer(frame)
        latest = automatic.peek()
        revision++
        manual = null
        retryAt = 0
    }

    @Synchronized fun requestRefresh(): Boolean {
        val current = latest ?: return false
        automatic.take()
        revision++
        manual = GameTranslationWork(current, true, generation, revision)
        retryAt = 0
        return true
    }

    @Synchronized fun take(paused: Boolean, now: Long): GameTranslationWork? {
        manual?.let { manual = null; return it }
        if (paused || now < retryAt) return null
        return automatic.take()?.let { GameTranslationWork(it, false, generation, revision) }
    }

    @Synchronized fun isCurrent(work: GameTranslationWork) = work.generation == generation && work.revision == revision

    @Synchronized fun publishIfCurrent(work: GameTranslationWork, action: () -> Unit): Boolean {
        if (!isCurrent(work)) return false
        action()
        return true
    }

    @Synchronized fun retry(work: GameTranslationWork, notBefore: Long): Boolean {
        if (work.manual || !isCurrent(work) || manual != null) return false
        automatic.retry(work.frame)
        retryAt = notBefore
        return true
    }
}
