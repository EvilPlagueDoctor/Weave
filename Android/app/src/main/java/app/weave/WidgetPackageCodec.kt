package app.weave

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object WidgetPackageCodec {
    private const val ENVELOPE = "VEILYSOCIAL_WIDGET_PACKAGE_TEXT_V1"
    private const val VERSION = 1
    private class W {
        val out=ByteArrayOutputStream()
        fun u8(v:Int)=out.write(v and 255)
        fun u16(v:Int){u8(v);u8(v ushr 8)}
        fun u32(v:Long){repeat(4){u8((v ushr(it*8)).toInt())}}
        fun i64(v:Long){repeat(8){u8((v ushr(it*8)).toInt())}}
        fun bool(v:Boolean)=u8(if(v)1 else 0)
        fun str(s:String){val b=s.toByteArray(Charsets.UTF_8);require(b.size<=65535);u16(b.size);out.write(b)}
        fun bytes(b:ByteArray){u32(b.size.toLong());out.write(b)}
    }
    private class R(private val b:ByteArray){var p=0;fun u8():Int{require(p<b.size);return b[p++].toInt()and 255};fun u16()=u8()or(u8()shl 8);fun u32():Long{var v=0L;repeat(4){v=v or(u8().toLong()shl(it*8))};return v};fun i64():Long{var v=0L;repeat(8){v=v or(u8().toLong()shl(it*8))};return v};fun bool()=when(val v=u8()){0->false;1->true;else->error("invalid bool $v")};fun str():String{val n=u16();require(p+n<=b.size);val s=b.copyOfRange(p,p+n).toString(Charsets.UTF_8);p+=n;return s};fun bytes():ByteArray{val n=u32().toInt();require(n>=0&&p+n<=b.size);return b.copyOfRange(p,p+n).also{p+=n}};fun done()=p==b.size}

    fun encodeBinary(pkg:WidgetPackage):ByteArray{
        val w=W();"VWPK".forEach{w.u8(it.code)};w.u16(VERSION)
        val m=pkg.manifest
        w.str(m.widgetId);w.str(m.name);w.str(m.author);w.u16(m.runtimeVersion);w.u16(m.defaultWidth);w.u16(m.defaultHeight);w.bool(m.warnOnResize)
        w.u8(m.relation.ordinal);w.str(m.sourceHash);w.str(m.upstreamWidgetId);w.str(m.upstreamSourceHash);w.bytes(m.localBackupSource.toByteArray(Charsets.UTF_8))
        w.u8(m.capabilities.size);m.capabilities.sortedBy{it.ordinal}.forEach{w.u8(it.ordinal)}
        w.bytes(pkg.source.toByteArray(Charsets.UTF_8));w.bytes(pkg.bytecode);w.i64(pkg.modifiedEpochMs)
        return w.out.toByteArray()
    }
    fun decodeBinary(data:ByteArray):WidgetPackage{
        val r=R(data);require(r.u8()=='V'.code&&r.u8()=='W'.code&&r.u8()=='P'.code&&r.u8()=='K'.code){"not a widget package"};require(r.u16()==VERSION){"unsupported widget package version"}
        val m=WidgetManifest(widgetId=r.str(),name=r.str(),author=r.str(),runtimeVersion=r.u16(),defaultWidth=r.u16(),defaultHeight=r.u16(),warnOnResize=r.bool())
        m.relation=WidgetRelation.entries[r.u8()];m.sourceHash=r.str();m.upstreamWidgetId=r.str();m.upstreamSourceHash=r.str();m.localBackupSource=r.bytes().toString(Charsets.UTF_8);m.capabilities.clear();repeat(r.u8()){m.capabilities+=WidgetCapability.entries[r.u8()]}
        val pkg=WidgetPackage(manifest=m,source=r.bytes().toString(Charsets.UTF_8),bytecode=r.bytes(),modifiedEpochMs=r.i64());require(r.done()){"trailing widget package data"}
        require(widgetSourceHash(pkg.source)==m.sourceHash){"widget source hash does not match package manifest"}
        val compiled=VeilWidgetLanguage.compile(pkg.source);require(compiled.ok){"widget source no longer compiles"};require(compiled.bytecode.contentEquals(pkg.bytecode)){"widget bytecode does not match the packaged source"};VeilWidgetBytecode.decode(pkg.bytecode)
        return pkg
    }
    fun encodeText(pkg:WidgetPackage)=ENVELOPE+"\n"+Base64.getEncoder().encodeToString(encodeBinary(pkg))+"\n"
    fun decodeText(text:String):WidgetPackage{val first=text.lineSequence().firstOrNull()?:error("empty widget file");require(first==ENVELOPE){"invalid widget text envelope"};val b64=text.substringAfter('\n').filterNot{it.isWhitespace()};return decodeBinary(Base64.getDecoder().decode(b64))}
    fun save(pkg:WidgetPackage,file:File){file.parentFile?.mkdirs();file.writeText(encodeText(pkg),Charsets.UTF_8)}
    fun load(file:File)=decodeText(file.readText(Charsets.UTF_8))
}
