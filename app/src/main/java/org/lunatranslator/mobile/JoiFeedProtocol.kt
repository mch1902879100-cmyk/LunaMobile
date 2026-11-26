package org.lunatranslator.mobile

data class JoiIdentity(val session: String, val gameId: String, val engine: String, val title: String, val heartbeat: Long, val textVisible: Boolean=true)
data class JoiSnapshot(val identity: JoiIdentity, val frames: List<GameTextFrame>)

/** Metadata is control information, never a translation request. Ignore stale games and partial writes. */
object JoiFeedProtocol {
    fun parse(value: String, now: Long): JoiSnapshot? {
        val rows=value.substringBeforeLast('\n', "").lineSequence().mapNotNull { line ->
            val p=line.split('\t',limit=4)
            if(p.size!=4 || p[0].length !in 1..128 || p[1].toLongOrNull()==null || p[3].length !in 2..32768 || p[3].length%2!=0) null
            else runCatching { GameTextFrame(p[0]+"/"+p[1],p[2],ByteArray(p[3].length/2){p[3].substring(it*2,it*2+2).toInt(16).toByte()}.toString(Charsets.UTF_8)) }.getOrNull()
        }.toList()
        val latest=rows.lastOrNull {it.kind=="luna_game"} ?: return null
        val fields=latest.text.split('\n')
        if(fields.size !in 5..6 || fields[0]!="LUNA_JOI_1" || !Regex("[a-f0-9]{32}").matches(fields[1]) || fields[2] !in setOf("RGSS1","RGSS2","RGSS3","MV","MZ","TYRANO","HTML","RENPY")) return null
        if(fields.size==6 && fields[5] !in setOf("0","1"))return null
        val time=fields[4].toLongOrNull() ?: return null
        if(now-time !in -2000..5000 || fields[3].isBlank() || fields[3].length>256) return null
        val session=latest.id.substringBefore('/')
        return JoiSnapshot(JoiIdentity(session,fields[1],fields[2],fields[3],time,fields.size==5 || fields[5]=="1"),rows.filter {
            it.id.substringBefore('/')==session && it.kind in setOf("dialogue","choices","battle_message","battle_log") && it.text.isNotBlank()
        })
    }
}
