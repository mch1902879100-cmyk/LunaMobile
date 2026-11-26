package org.lunatranslator.mobile
import org.junit.Assert.*
import org.junit.Test
class JoiFeedProtocolTest {
    @Test fun engineIdentitiesAreAcceptedWithoutOcr(){for(engine in listOf("RGSS1","RGSS2","MV","MZ","TYRANO","HTML","RENPY")){val p=JoiFeedProtocol.parse(meta().replace("5247535333",engine.toByteArray().joinToString(""){"%02x".format(it.toInt() and 255)}),11000)!!;assertEquals(engine,p.identity.engine)}}
    @Test fun unknownEngineDoesNotPretendToBeConnected(){assertNull(JoiFeedProtocol.parse(meta().replace("5247535333","554e4b4e4f574e"),11000))}
    private fun row(session:String,seq:Int,kind:String,text:String)="$session\t$seq\t$kind\t"+text.toByteArray().joinToString(""){"%02x".format(it.toInt() and 255)}+"\n"
    private fun meta(session:String="s",at:Long=10000,title:String="実際のゲーム")=row(session,1,"luna_game","LUNA_JOI_1\n"+"a".repeat(32)+"\nRGSS3\n$title\n$at")
    @Test fun identityComesFromPluginAndIsNotTranslated(){val p=JoiFeedProtocol.parse(meta()+row("s",2,"dialogue","敵は？"),11000)!!;assertEquals("実際のゲーム",p.identity.title);assertEquals(listOf("敵は？"),p.frames.map{it.text})}
    @Test fun staleGameDoesNotRemainConnected(){assertNull(JoiFeedProtocol.parse(meta(),16000))}
    @Test fun partialWriteDoesNotCreateFrame(){assertTrue(JoiFeedProtocol.parse(meta()+row("s",2,"dialogue","文字").trimEnd(),11000)!!.frames.isEmpty())}
    @Test fun oldGameSessionDoesNotLeakIntoNewGame(){val p=JoiFeedProtocol.parse(row("old",2,"dialogue","古い文字")+meta("new")+row("new",2,"choices","はい\nいいえ"),11000)!!;assertEquals(1,p.frames.size);assertEquals("new/2",p.frames.single().id)}
    @Test fun legacyUnidentifiedFileIsNotPretendedToBeDetected(){assertNull(JoiFeedProtocol.parse(row("old",1,"dialogue","文字"),11000))}
    @Test fun malformedMetadataDoesNotConnect(){assertNull(JoiFeedProtocol.parse(meta(title=""),11000));assertNull(JoiFeedProtocol.parse(meta(at=20000),11000))}
    @Test fun hiddenGameTextIsExplicitEvenWhenOldFramesRemain(){val raw=row("s",1,"luna_game","LUNA_JOI_1\n"+"a".repeat(32)+"\nRGSS3\n町\n10000\n0")+row("s",2,"dialogue","古い文字");assertFalse(JoiFeedProtocol.parse(raw,11000)!!.identity.textVisible)}
    @Test fun invalidVisibilityDoesNotShowStaleText(){val raw=row("s",1,"luna_game","LUNA_JOI_1\n"+"a".repeat(32)+"\nRGSS3\n町\n10000\nmaybe");assertNull(JoiFeedProtocol.parse(raw,11000))}
}
