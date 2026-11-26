package org.lunatranslator.mobile

data class GameTextFrame(val id: String, val kind: String, val text: String)

/** Battle lines form a bounded reading page while dialogue follows the latest page. */
class GameTextQueue {
    private val battleLines = ArrayDeque<String>()
    private var session: String? = null
    private var pending: GameTextFrame? = null

    @Synchronized
    fun clear() { battleLines.clear(); pending = null; session = null }

    @Synchronized
    fun offer(frame: GameTextFrame) {
        val nextSession = frame.id.substringBefore('/')
        if (session != nextSession) {
            battleLines.clear()
            session = nextSession
        }
        if (frame.kind == "battle_log") {
            battleLines.addLast(frame.text)
            while (battleLines.size > 8) battleLines.removeFirst()
            pending = frame.copy(text = battleLines.joinToString("\n"))
        } else {
            battleLines.clear()
            pending = frame
        }
    }

    @Synchronized
    fun take(): GameTextFrame? = pending.also { pending = null }

    @Synchronized
    fun peek(): GameTextFrame? = pending

    @Synchronized
    fun hasPending(): Boolean = pending != null

    @Synchronized
    fun retry(frame: GameTextFrame) {
        if (pending == null) pending = frame
    }
}
