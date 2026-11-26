package org.lunatranslator.mobile
import org.junit.Assert.*
import org.junit.Test
class JoiWebEntryTest {
 @Test fun adapterLoadsBeforeGameScriptsAndKeepsUnicode(){
  val html="<!doctype html><HEAD lang='ja'><title>元のゲーム</title></HEAD><body><script src='js/main.js'></script></body>"
  val result=JoiWebEntry.patch(html)
  assertEquals(html,result.replace("\n"+JoiWebEntry.TAG+"\n",""))
  assertTrue(result.indexOf(JoiWebEntry.TAG)<result.indexOf("js/main.js"))
 }
 @Test fun untrackedAdapterIsPreserved(){assertThrows(IllegalStateException::class.java){JoiWebEntry.patch("<head>"+JoiWebEntry.TAG+"</head>")}}
 @Test fun ambiguousNonHtmlIsPreserved(){assertThrows(IllegalStateException::class.java){JoiWebEntry.patch("game data")}}
}
