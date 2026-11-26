package org.lunatranslator.mobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class JoiPlayGame(val id:String,val title:String,val tree:String,val feed:String) {
    fun json()=JSONObject().put("id",id).put("title",title).put("tree",tree).put("feed",feed)
    companion object {
        fun all(c:Context):List<JoiPlayGame> = runCatching {
            val a=JSONArray(c.getSharedPreferences("joiplay",Context.MODE_PRIVATE).getString("games","[]"))
            (0 until a.length()).map { val o=a.getJSONObject(it);JoiPlayGame(o.getString("id"),o.getString("title"),o.getString("tree"),o.getString("feed")) }
        }.getOrDefault(emptyList())
        fun remember(c:Context,g:JoiPlayGame) {
            val list=all(c).filterNot {it.id==g.id || it.feed==g.feed}+g
            c.getSharedPreferences("joiplay",Context.MODE_PRIVATE).edit().putString("games",JSONArray().apply {list.forEach {put(it.json())}}.toString()).apply()
        }
    }
}

/** Install a reversible per-game JoiPlay postload hook. Never opens Data/Scripts or any save for writing. */
class JoiPlayInstaller(private val context:Context, private val tree:Uri, selectedRoot:Uri?=null) {
    private val cr=context.contentResolver
    private val root=selectedRoot ?: DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
    private fun children(parent:Uri=root):Map<String,Uri> {
        val result=mutableMapOf<String,Uri>()
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { c->
            while(c.moveToNext()) result[c.getString(1)]=DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0))
        }
        return result
    }
    private fun read(uri:Uri):ByteArray=cr.openInputStream(uri)!!.use {input->
        val bytes=input.readBytes();check(bytes.size<=1_048_576){"接入脚本过大，请先手动检查。"};bytes
    }
    private fun write(uri:Uri,bytes:ByteArray){cr.openOutputStream(uri,"wt")!!.use{it.write(bytes)}}
    private fun create(name:String)=DocumentsContract.createDocument(cr,root,"application/octet-stream",name) ?: error("游戏目录不可写，请重新授权。")
    private fun sha(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun install():JoiPlayGame {
        val files=children()
        val iniUri=files.entries.firstOrNull{it.key.equals("Game.ini",true)}?.value
        if(iniUri==null) {
            val gameDir=files["game"]
            if(gameDir!=null && files["renpy"]!=null) return installRenpy(gameDir,children(gameDir))
            if(files.keys.any{it.endsWith(".rpy",true)||it.endsWith(".rpyc",true)||it.endsWith(".rpa",true)}) return installRenpy(root,files)
            return installWeb(root,files)
        }
        val ini=read(iniUri).toString(Charsets.UTF_8)
        val engine=when {
            Regex("(?im)^\\s*(?:Scripts\\s*=.*\\.rvdata2|Library\\s*=.*RGSS30[0-9]\\.dll)\\s*$").containsMatchIn(ini)->"RGSS3"
            Regex("(?im)^\\s*(?:Scripts\\s*=.*\\.rvdata|Library\\s*=.*RGSS20[0-9]\\.dll)\\s*$").containsMatchIn(ini)->"RGSS2"
            Regex("(?im)^\\s*(?:Scripts\\s*=.*\\.rxdata|Library\\s*=.*RGSS10[0-9]E?\\.dll)\\s*$").containsMatchIn(ini)->"RGSS1"
            else -> return installWeb(root,files)
        }
        val oldManifest=files["LunaJoiAdapter.json"]?.let{JSONObject(read(it).toString(Charsets.UTF_8))}
        val oldPost=files["postload.rb"]?.let(::read) ?: byteArrayOf()
        val first=oldManifest==null
        if(first) {
            check(files["LunaJoiBridge.rb"]==null && files["LunaJoiPostload.original.rb"]==null && !oldPost.toString(Charsets.UTF_8).contains("LUNA_JOI_ADAPTER")) {
                "目录中已有未登记的 Luna 接入文件，已保留，请先检查。"
            }
        } else {
            check(sha(oldPost)==oldManifest!!.getString("installed_postload_sha256")) {"postload.rb 已被修改，已保留，请先检查。"}
            check(files["LunaJoiBridge.rb"]?.let{sha(read(it))}==oldManifest.getString("payload_sha256")) {"取词脚本已被修改，已保留，请先检查。"}
        }
        val id=oldManifest?.getString("id") ?: UUID.randomUUID().toString().replace("-","")
        val iniTitle=Regex("(?im)^\\s*Title\\s*=(.*)$").find(ini)?.groupValues?.get(1)?.trim().orEmpty()
        val title=iniTitle.ifBlank{DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':')}.take(120)
        val escaped=title.replace("\\","\\\\").replace("'","\\'").replace('\r',' ').replace('\n',' ')
        val payload=context.assets.open("luna_joiplay.rb").bufferedReader().use{it.readText()}
            .replace("__LUNA_GAME_ID__",id).replace("__LUNA_GAME_TITLE__",escaped).replace("__LUNA_ENGINE__",engine).toByteArray(Charsets.UTF_8)
        val original=if(first)oldPost else files["LunaJoiPostload.original.rb"]?.let(::read) ?: error("缺少原 postload 备份。")
        if(!first) check(sha(original)==oldManifest!!.getString("original_postload_sha256")){"原脚本备份已变化，已停止更新。"}
        val post=original+"\n# LUNA_JOI_ADAPTER 1\nload 'LunaJoiBridge.rb'\n".toByteArray(Charsets.UTF_8)
        val backup=files["LunaJoiPostload.original.rb"] ?: create("LunaJoiPostload.original.rb").also{write(it,original)}
        check(sha(read(backup))==sha(original)){"原脚本备份校验失败。"}
        val payloadUri=files["LunaJoiBridge.rb"] ?: create("LunaJoiBridge.rb")
        val feed=files["LunaGameText.txt"] ?: create("LunaGameText.txt").also{write(it,byteArrayOf())}
        val postUri=files["postload.rb"] ?: create("postload.rb")
        val manifestUri=files["LunaJoiAdapter.json"] ?: create("LunaJoiAdapter.json")
        write(payloadUri,payload)
        check(sha(read(payloadUri))==sha(payload)){"取词脚本写入校验失败。"}
        val manifest=JSONObject().put("version",1).put("id",id).put("title",title).put("engine",engine)
            .put("original_postload_existed",oldManifest?.optBoolean("original_postload_existed") ?: files.containsKey("postload.rb"))
            .put("original_postload_sha256",sha(original)).put("installed_postload_sha256",sha(post)).put("payload_sha256",sha(payload))
        try {
            write(postUri,post)
            check(sha(read(postUri))==sha(post)){"加载脚本校验失败。"}
            write(manifestUri,manifest.toString(2).toByteArray(Charsets.UTF_8))
        } catch(e:Exception) {runCatching{write(postUri,oldPost)};throw e}
        return JoiPlayGame(id,title,tree.toString(),feed.toString()).also {JoiPlayGame.remember(context,it)}
    }

    private fun installWeb(selected:Uri, initial:Map<String,Uri>):JoiPlayGame {
        val parent=if(initial["index.html"]!=null) selected else initial["www"]
            ?: error("请选择游戏目录（Game.ini 或 index.html；也可包含 www）。")
        val files=if(parent==selected) initial else children(parent)
        val entry=files["index.html"] ?: error("游戏目录中没有 index.html。")
        val scripts=files["js"]?.let(::children).orEmpty()
        val main=scripts["main.js"]?.let(::read)?.toString(Charsets.UTF_8).orEmpty()
        val engine=when {
            scripts["rmmz_core.js"]!=null && main.contains("rmmz_core.js") -> "MZ"
            scripts["rpg_core.js"]!=null -> "MV"
            files["tyrano"]!=null -> "TYRANO"
            else -> "HTML"
        }
        val manifestUri=files["LunaJoiAdapter.json"]
        val old=manifestUri?.let {JSONObject(read(it).toString(Charsets.UTF_8))}
        val current=read(entry)
        val backupName="LunaJoiIndex.original.html"
        val payloadName="LunaJoiWeb.js"
        if(old==null) check(files[backupName]==null && files[payloadName]==null){"已有未登记的 Luna 接入文件，已保留。"}
        else {
            check(old.getString("engine")==engine){"游戏引擎与原接入记录不一致。"}
            check(sha(current)==old.getString("installed_index_sha256")){"index.html 已被修改，已保留。"}
            check(files[payloadName]?.let{sha(read(it))}==old.getString("payload_sha256")){"取词脚本已被修改，已保留。"}
        }
        val original=if(old==null)current else files[backupName]?.let(::read) ?: error("缺少原网页备份。")
        if(old!=null) check(sha(original)==old.getString("original_index_sha256")){"原网页备份已变化。"}
        val id=old?.getString("id") ?: UUID.randomUUID().toString().replace("-","")
        val title=Regex("(?is)<title[^>]*>(.*?)</title>").find(original.toString(Charsets.UTF_8))?.groupValues?.get(1)
            ?.replace(Regex("[\\r\\n\\t]")," ")?.trim()?.take(120)?.ifBlank{null}
            ?: DocumentsContract.getDocumentId(parent).substringAfterLast('/').take(120)
        val config=JSONObject().put("id",id).put("title",title).put("engine",engine).toString()
        val transport=context.assets.open("luna_joi_web.js").bufferedReader().use{it.readText()}.replace("__LUNA_JOI_CONFIG__",config)
        val bridge=context.assets.open("luna_mv_mz.js").bufferedReader().use{it.readText()}
        val generic=if(engine=="HTML")context.assets.open("luna_joi_html.js").bufferedReader().use{it.readText()} else ""
        val payload=(transport+"\n"+bridge+if(generic.isNotEmpty())"\n"+generic else "").toByteArray(Charsets.UTF_8)
        val index=JoiWebEntry.patch(original.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        fun make(name:String)=DocumentsContract.createDocument(cr,parent,"application/octet-stream",name) ?: error("游戏目录不可写。")
        val backup=files[backupName] ?: make(backupName).also{write(it,original)}
        check(sha(read(backup))==sha(original)){"原网页备份校验失败。"}
        val payloadUri=files[payloadName] ?: make(payloadName)
        val previousPayload=if(old==null)null else read(payloadUri)
        val feed=files["LunaGameText.txt"] ?: make("LunaGameText.txt").also{write(it,byteArrayOf())}
        val manifest=JSONObject().put("version",2).put("id",id).put("title",title).put("engine",engine)
            .put("original_index_sha256",sha(original)).put("installed_index_sha256",sha(index)).put("payload_sha256",sha(payload))
        try {
            write(payloadUri,payload);check(sha(read(payloadUri))==sha(payload)){"取词脚本校验失败。"}
            write(entry,index);check(sha(read(entry))==sha(index)){"网页接入校验失败。"}
            write(manifestUri ?: make("LunaJoiAdapter.json"),manifest.toString(2).toByteArray(Charsets.UTF_8))
        } catch(e:Exception) {
            runCatching{write(entry,current)}
            if(previousPayload!=null) runCatching{write(payloadUri,previousPayload)}
            throw e
        }
        return JoiPlayGame(id,title,tree.toString(),feed.toString()).also{JoiPlayGame.remember(context,it)}
    }

    private fun installRenpy(parent:Uri,files:Map<String,Uri>):JoiPlayGame {
        check(files.keys.any{it.endsWith(".rpy",true)||it.endsWith(".rpyc",true)||it.endsWith(".rpa",true)}){"未发现 Ren’Py 游戏脚本。"}
        val old=files["LunaJoiAdapter.json"]?.let{JSONObject(read(it).toString(Charsets.UTF_8))}
        val name="zz_luna_joiplay.rpy"
        val existing=files[name]?.let(::read)
        if(old==null) check(existing==null && files["zz_luna_joiplay.rpyc"]==null){"已有未登记的 Ren’Py 接入，已保留。"}
        else check(old.getString("engine")=="RENPY" && existing!=null && sha(existing)==old.getString("payload_sha256")){"Ren’Py 接入已变化，已保留。"}
        val id=old?.getString("id") ?: UUID.randomUUID().toString().replace("-","")
        val title=DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':').take(120)
        val escaped=title.replace("\\","\\\\").replace("'","\\'").replace('\r',' ').replace('\n',' ')
        val payload=context.assets.open("luna_joiplay.rpy").bufferedReader().use{it.readText()}
            .replace("__LUNA_GAME_ID__",id).replace("__LUNA_GAME_TITLE__",escaped).toByteArray(Charsets.UTF_8)
        fun make(n:String)=DocumentsContract.createDocument(cr,parent,"application/octet-stream",n) ?: error("游戏目录不可写。")
        val feed=files["LunaGameText.txt"] ?: make("LunaGameText.txt").also{write(it,byteArrayOf())}
        val uri=files[name] ?: make(name)
        val manifest=JSONObject().put("version",2).put("id",id).put("title",title).put("engine","RENPY").put("payload_sha256",sha(payload))
        try {
            write(uri,payload);check(sha(read(uri))==sha(payload)){"Ren’Py 脚本校验失败。"}
            write(files["LunaJoiAdapter.json"] ?: make("LunaJoiAdapter.json"),manifest.toString(2).toByteArray(Charsets.UTF_8))
        } catch(e:Exception) {if(existing!=null)runCatching{write(uri,existing)};throw e}
        return JoiPlayGame(id,title,tree.toString(),feed.toString()).also{JoiPlayGame.remember(context,it)}
    }
}
