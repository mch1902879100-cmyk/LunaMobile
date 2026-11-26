package org.lunatranslator.mobile

import org.junit.Assert.*
import org.junit.Test

class GameTextQueueTest {
    private fun log(sequence: Int, text: String) = GameTextFrame("battle/$sequence", "battle_log", text)

    @Test fun capturesAttackAndDamageInOneRead() {
        val queue = GameTextQueue()
        queue.offer(log(1, "貴方の攻撃！"))
        queue.offer(log(2, "コウモリに 163 のダメージを与えた！"))
        assertEquals("貴方の攻撃！\nコウモリに 163 のダメージを与えた！", queue.take()?.text)
    }

    @Test fun arrivalsDuringTranslationKeepEarlierBattleLines() {
        val queue = GameTextQueue()
        queue.offer(log(1, "攻撃！"))
        assertEquals("攻撃！", queue.take()?.text)
        queue.offer(log(2, "ダメージ！"))
        assertTrue(queue.hasPending())
        assertEquals("攻撃！\nダメージ！", queue.take()?.text)
    }

    @Test fun dialogueAndNextBattleResetOldBattleText() {
        val queue = GameTextQueue()
        queue.offer(log(1, "古い戦闘"))
        queue.offer(GameTextFrame("battle/2", "dialogue", "こんにちは。"))
        assertEquals("こんにちは。", queue.take()?.text)
        queue.offer(GameTextFrame("battle/3", "battle_message", "コウモリが出現！"))
        queue.offer(log(4, "新しい攻撃"))
        assertEquals("新しい攻撃", queue.take()?.text)
    }

    @Test fun repeatedHitsRemainAndPageIsBounded() {
        val queue = GameTextQueue()
        repeat(10) { queue.offer(log(it, "10 ダメージ！")) }
        assertEquals(List(8) { "10 ダメージ！" }.joinToString("\n"), queue.take()?.text)
    }

    @Test fun newGameSessionClearsPreviousBattle() {
        val queue = GameTextQueue()
        queue.offer(log(1, "前のゲーム"))
        queue.offer(GameTextFrame("new-game/1", "battle_log", "新しいゲーム"))
        assertEquals("新しいゲーム", queue.take()?.text)
    }

    @Test fun failedRequestRetriesWithoutReplacingNewerPage() {
        val queue = GameTextQueue()
        val first = log(1, "攻撃！")
        queue.offer(first)
        val inFlight = queue.take()!!
        queue.retry(inFlight)
        assertEquals(first, queue.take())
        queue.offer(GameTextFrame("battle/2", "dialogue", "次のページ"))
        queue.retry(inFlight)
        assertEquals("次のページ", queue.take()?.text)
    }

    @Test fun changingPlayerClearsUnreadTextAndBattleHistory() {
        val queue = GameTextQueue()
        queue.offer(log(1, "前のプレイヤー"))
        queue.clear()
        assertFalse(queue.hasPending())
        assertNull(queue.take())
        queue.offer(log(2, "切り替え後"))
        assertEquals("切り替え後", queue.take()?.text)
    }
}
