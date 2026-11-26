package org.lunatranslator.mobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

data class JoiGameCandidate(val name:String,val root:Uri)

/** Scan only the user-authorized tree. Never write during discovery. */
class JoiGameScanner(private val context:Context,private val tree:Uri) {
    private data class Entry(val name:String,val uri:Uri,val directory:Boolean)
    private data class Pending(val uri:Uri,val path:String,val depth:Int)
    private fun children(parent:Uri):List<Entry> {
        val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE)
        return context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent)),columns,null,null,null)?.use { c->
            buildList { while(c.moveToNext()) add(Entry(c.getString(1),DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)),c.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR)) }
        }.orEmpty()
    }
    private fun text(uri:Uri):String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input->
            val out=ByteArrayOutputStream();val buf=ByteArray(8192)
            while(true) {
                val n=input.read(buf);if(n<0)break
                if(out.size()+n>262144)return@use null
                out.write(buf,0,n)
            }
            out.toString("UTF-8")
        }
    }.getOrNull()
    private fun display(path:String,entries:List<Entry>,webEntries:List<Entry>):String {
        val ini=entries.firstOrNull{it.name.equals("Game.ini",true)}?.let{text(it.uri)}
        val fromIni=ini?.let{Regex("(?im)^\\s*Title\\s*=(.*)$").find(it)?.groupValues?.get(1)?.trim()}
        val content=if(webEntries.isNotEmpty())webEntries else entries
        val data=content.firstOrNull{it.directory && it.name.equals("data",true)}
        val system=data?.let{runCatching{children(it.uri)}.getOrDefault(emptyList())}?.firstOrNull{it.name.equals("System.json",true)}
        val fromSystem=system?.let{text(it.uri)}?.let{runCatching{JSONObject(it).optString("gameTitle")}.getOrNull()}
        val title=listOf(fromSystem,fromIni).firstOrNull{!it.isNullOrBlank() && '\uFFFD' !in it && it.length<=150 && !it.equals("Game",true)}
        return if(title==null || path.endsWith("/"+title,true) || path.equals(title,true))path else "$title\n$path"
    }
    fun scan():List<JoiGameCandidate> {
        val root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val rootName=DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':')
        val pending=ArrayDeque<Pending>();pending.add(Pending(root,rootName,0))
        val found=mutableListOf<JoiGameCandidate>()
        val skip=setOf("audio","img","graphics","movies","video","node_modules","locales","cache","save","saves","savedata","mtool","swiftshader","tool","data","js","css","fonts","lib","www","renpy","assets","resources")
        var visited=0
        while(pending.isNotEmpty() && visited<3000 && found.size<300) {
            val item=pending.removeFirst();visited++
            val entries=runCatching { children(item.uri) }.getOrDefault(emptyList())
            val names=entries.map{it.name.lowercase()}.toSet()
            val www=entries.firstOrNull{it.directory && it.name.equals("www",true)}
            val webEntries=www?.let{runCatching{children(it.uri)}.getOrDefault(emptyList())}.orEmpty()
            val web=webEntries.any{it.name.equals("index.html",true)}
            val renpy=("game" in names && "renpy" in names) || entries.any{it.name.endsWith(".rpy",true)||it.name.endsWith(".rpyc",true)||it.name.endsWith(".rpa",true)}
            if("game.ini" in names || "index.html" in names || web || renpy) {
                found.add(JoiGameCandidate(display(item.path,entries,webEntries),item.uri))
            }
            if(item.depth>=6) continue
            for(e in entries) if(e.directory && e.name.lowercase() !in skip && !e.name.startsWith('.'))
                pending.add(Pending(e.uri,item.path+"/"+e.name,item.depth+1))
        }
        return found.sortedBy{it.name.lowercase()}
    }
}
