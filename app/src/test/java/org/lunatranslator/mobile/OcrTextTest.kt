package org.lunatranslator.mobile
import org.junit.Assert.*
import org.junit.Test
class OcrTextTest {
    @Test fun quickJapaneseIgnoresChineseLauncher(){assertEquals("",OcrText.forScreen("文件管理\n浏览器\n设置\nLuna Mobile",true,"auto","zh"))}
    @Test fun quickJapaneseRetainsSpeakerAndDialogue(){assertEquals("尾田兵士\n明地の旗です！！",OcrText.forScreen("127.0.0.1:60024\n尾田兵士\n明地の旗です！！",true,"ja","zh"))}
    @Test fun explicitEnglishStillWorks(){assertEquals("The door is locked.",OcrText.forScreen("The door is locked.",true,"en","zh"))}
    @Test fun advancedScreenModePreservesKanjiMenus(){assertEquals("攻撃\n防御",OcrText.forScreen("攻撃\n防御",false,"ja","zh"))}
    @Test fun quickJapaneseKanjiOnlyLimitationIsExplicit(){assertEquals("",OcrText.forScreen("設定\n攻撃",true,"ja","zh"))}
    @Test fun skipsChineseHeaderAndFooterAroundRealJapanese(){assertEquals("今日はいい天気ですね。",OcrText.forScreen("跨座用技木測式·非遊戒\n収監証昇幕沢別与触模,不代表遊就央測\n今日はいい天気ですね。\n点古任意画面推逆一",true,"ja","zh"))}
    @Test fun isolatedKanaOcrNoiseDoesNotMakeChineseScreenJapanese(){assertEquals("",OcrText.forScreen("跨座用技木測式·非遊戒\n今日\nね。\n点古任意画面推逆一",true,"ja","zh"))}
    @Test fun shortNaturalJapaneseQuestionIsRetained(){assertEquals("尾田信長\n敵は？",OcrText.forScreen("尾田信長\n敵は？",true,"ja","zh"))}
    @Test fun returningFromLauncherCanRestoreSameDialogue(){
        val gate=TranslationGate()
        assertFalse(gate.shouldTranslate("敵は？",0));assertTrue(gate.shouldTranslate("敵は？",1000));gate.succeeded("敵は？")
        gate.clearScreen()
        assertFalse(gate.shouldTranslate("敵は？",2000));assertTrue(gate.shouldTranslate("敵は？",3000))
    }
    @Test fun preservesIndependentMenuRows(){assertEquals("アイテム\nステータス\nセーブ\nロード",OcrText.normalize(" アイテム\r\nステータス\n セーブ \nロード "))}
    @Test fun preservesDialogueLineBreaks(){assertEquals("今日はいい天気ですね。\n一緒に行きましょう。",OcrText.normalize("今日はいい天気ですね。\n一緒に行きましょう。"))}
    @Test fun stripsControlsWithoutJoiningWords(){assertEquals("hello world\nnext",OcrText.normalize("hello\t world\n\nnext\u0000"))}
}
