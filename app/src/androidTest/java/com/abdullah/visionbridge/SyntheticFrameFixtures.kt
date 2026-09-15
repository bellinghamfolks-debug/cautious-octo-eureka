package com.abdullah.visionbridge

import android.graphics.*

/** Procedural, non-private fixtures. No captured image is packaged in tests. */
object SyntheticFrameFixtures {
    data class Case(val id:String,val lines:List<String>,val size:Float=52f)
    val clear=listOf(
        Case("ar-phone",listOf("شبكات الهاتف","إعدادات الشاشة")),
        Case("ar-door",listOf("الباب مفتوح")),Case("ar-shop",listOf("مياه للشرب")),
        Case("ar-label",listOf("صنع في الأردن")),Case("en-phone",listOf("Battery Health","Open Settings")),
        Case("en-label",listOf("ORCHARD JUICE","Strawberry Drink")),
        Case("en-device",listOf("Model VB204","Input 12V")),Case("en-energy",listOf("ENERGY RATING","Annual use 240 kWh")),
        Case("en-perfume",listOf("CEDAR SPORT","EAU DE PARFUM")),Case("en-book",listOf("First Line","Second Line")),
        Case("mixed-one",listOf("VISION 2026","إعدادات الهاتف")),Case("mixed-two",listOf("500 ml","مياه للشرب")),
        Case("mixed-three",listOf("PRODUCT 125","صنع في الأردن")),Case("mixed-four",listOf("USB TYPE C","الباب مفتوح")),
        Case("digits-one",listOf("0123456789")),Case("digits-two",listOf("2468 1357","2026 09 11")),
        Case("small-en",listOf("Small print settings"),22f),Case("small-ar",listOf("شبكات الهاتف"),28f),
        Case("product",listOf("PURE SOAP","NET 125 g")),Case("phone-menu",listOf("Display Settings","Sound and Volume")))
    fun render(c:Case):Bitmap {
        val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=c.size;typeface=Typeface.DEFAULT }
        val width=(c.lines.maxOf { ink.measureText(it) }+120).toInt().coerceAtLeast(420)
        val height=(c.size*1.8*c.lines.size+120).toInt().coerceAtLeast(280)
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { b -> Canvas(b).apply {
            drawColor(Color.WHITE);c.lines.forEachIndexed { i,line -> drawText(line,60f,80+c.size+i*c.size*1.8f,ink) }
        } }
    }
    fun black()=Bitmap.createBitmap(800,500,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
    fun rotated(source:Bitmap,degrees:Float)=Bitmap.createBitmap(source,0,0,source.width,source.height,
        Matrix().apply { postRotate(degrees) },true)
    fun blur(source:Bitmap):Bitmap {
        val tiny=Bitmap.createScaledBitmap(source,(source.width/12).coerceAtLeast(1),(source.height/12).coerceAtLeast(1),true)
        return Bitmap.createScaledBitmap(tiny,source.width,source.height,true).also { tiny.recycle() }
    }
    fun glare(source:Bitmap)=source.copy(Bitmap.Config.ARGB_8888,true).also {
        Canvas(it).drawCircle(it.width*.6f,it.height*.5f,it.height*.35f,Paint().apply { color=Color.WHITE })
    }
}
