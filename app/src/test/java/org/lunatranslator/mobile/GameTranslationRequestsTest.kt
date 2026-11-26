package org.lunatranslator.mobile

import org.junit.Assert.*
import org.junit.Test

class GameTranslationRequestsTest {
    private fun page(n: Int, text: String) = GameTextFrame("game/$n", "dialogue", text)

    @Test fun noRefreshBeforeAnyActualGameText() {
        val requests = GameTranslationRequests()
        assertFalse(requests.requestRefresh())
        assertNull(requests.take(false, 0))
    }

    @Test fun refreshUsesCurrentActualFrameAndBypassesAutomaticMode() {
        val requests = GameTranslationRequests()
        val actual = page(1, "こんにちは")
        requests.offer(actual)
        val automatic = requests.take(false, 0)!!
        assertFalse(automatic.manual)
        assertTrue(requests.requestRefresh())
        assertFalse(requests.isCurrent(automatic))
        val manual = requests.take(false, 0)!!
        assertTrue(manual.manual)
        assertEquals(actual, manual.frame)
        assertNull(requests.take(false, 0))
    }

    @Test fun pausedAutomaticCaptureStillAllowsOneExplicitLatestTextRequest() {
        val requests = GameTranslationRequests()
        requests.offer(page(1, "前のページ"))
        assertNull(requests.take(true, 0))
        val latest = page(2, "現在のページ")
        requests.offer(latest)
        assertTrue(requests.requestRefresh())
        val manual = requests.take(true, 0)!!
        assertTrue(manual.manual)
        assertEquals(latest, manual.frame)
        assertNull(requests.take(true, 0))
        assertNull(requests.take(false, 0)) // no redundant automatic request on resume
    }

    @Test fun aNewPagePreventsOldManualResultOrFailureFromReplacingIt() {
        val requests = GameTranslationRequests()
        requests.offer(page(1, "古いページ"))
        requests.requestRefresh()
        val old = requests.take(false, 0)!!
        requests.offer(page(2, "新しいページ"))
        var displayed = "new"
        assertFalse(requests.publishIfCurrent(old) { displayed = "stale" })
        assertEquals("new", displayed)
        assertFalse(requests.retry(old, 5000))
        assertEquals("新しいページ", requests.take(false, 0)?.frame?.text)
    }

    @Test fun rapidRefreshesCoalesceAndInvalidateAnEarlierInFlightResult() {
        val requests = GameTranslationRequests()
        requests.offer(page(1, "A"))
        requests.requestRefresh()
        val first = requests.take(false, 0)!!
        repeat(5) { requests.requestRefresh() }
        assertFalse(requests.isCurrent(first))
        assertTrue(requests.take(false, 0)!!.manual)
        assertNull(requests.take(false, 0))
    }

    @Test fun automaticBackoffDoesNotDelayExplicitManualRequestWhilePaused() {
        val requests = GameTranslationRequests()
        requests.offer(page(1, "A"))
        val failed = requests.take(false, 0)!!
        assertTrue(requests.retry(failed, 5000))
        assertNull(requests.take(false, 4999))
        requests.requestRefresh()
        val manual = requests.take(true, 0)!!
        assertTrue(manual.manual)
        assertFalse(requests.retry(manual, 10000)) // manual errors never turn into cache-hit automatic retries
        assertNull(requests.take(false, 20000))
    }

    @Test fun newSourceTextCanRunImmediatelyDespitePreviousAutomaticBackoff() {
        val requests = GameTranslationRequests()
        requests.offer(page(1, "A"))
        requests.retry(requests.take(false, 0)!!, 5000)
        requests.offer(page(2, "B"))
        assertEquals("B", requests.take(false, 0)!!.frame.text)
    }

    @Test fun newGameClearsPendingManualAndRejectsPriorGameCompletions() {
        val requests = GameTranslationRequests()
        requests.offer(page(1, "A"))
        val old = requests.take(false, 0)!!
        requests.requestRefresh()
        requests.clear()
        assertFalse(requests.isCurrent(old))
        assertFalse(requests.requestRefresh())
        assertNull(requests.take(false, 0))
        requests.offer(GameTextFrame("other/1", "dialogue", "B"))
        val current = requests.take(false, 0)!!
        assertNotEquals(old.generation, current.generation)
        var displayed = ""
        assertTrue(requests.publishIfCurrent(current) { displayed = current.frame.text })
        assertEquals("B", displayed)
    }

    @Test fun manualBattlePageRetainsBoundedLogWithoutDuplicatingOldLines() {
        val requests = GameTranslationRequests()
        requests.offer(GameTextFrame("game/1", "battle_log", "攻撃"))
        requests.take(false, 0)
        requests.offer(GameTextFrame("game/2", "battle_log", "163ダメージ"))
        requests.requestRefresh()
        assertEquals("攻撃\n163ダメージ", requests.take(false, 0)?.frame?.text)
        requests.offer(GameTextFrame("game/3", "battle_log", "敵が倒れた"))
        assertEquals("攻撃\n163ダメージ\n敵が倒れた", requests.take(false, 0)?.frame?.text)
    }

    @Test fun automaticResultPausedInFlightCanBeReplayedAfterResume() {
        val requests = GameTranslationRequests()
        requests.offer(page(1, "A"))
        val pending = requests.take(false, 0)!!
        assertTrue(requests.retry(pending, 0))
        assertNull(requests.take(true, 0))
        assertEquals(pending.frame, requests.take(false, 0)?.frame)
    }
}
