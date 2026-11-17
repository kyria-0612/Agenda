package com.agenda.app.design

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GlassSnapshot(val image:ImageBitmap,val sourceWidth:Int,val sourceHeight:Int)

/** Three separable box passes approximate a Gaussian. Downsampling bounds the work
 * and yields an actually blurred raster, even on GPUs that retain nested layers. */
suspend fun blurGlassSnapshot(source:ImageBitmap,radiusPx:Float):GlassSnapshot = withContext(Dispatchers.Default) {
    val software=source.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888,false)
    val width=(source.width/4).coerceAtLeast(1)
    val height=(source.height/4).coerceAtLeast(1)
    val small=Bitmap.createScaledBitmap(software,width,height,true)
    val pixels=IntArray(width*height)
    small.getPixels(pixels,0,width,0,0,width,height)
    val scratch=IntArray(pixels.size)
    val radius=(radiusPx/4).toInt().coerceIn(1,32)
    repeat(3) {
        boxPass(pixels,scratch,width,height,radius,true)
        boxPass(scratch,pixels,width,height,radius,false)
    }
    val blurred=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    if(small!==software) small.recycle()
    software.recycle()
    GlassSnapshot(blurred.asImageBitmap(),source.width,source.height)
}

private fun boxPass(input:IntArray,output:IntArray,width:Int,height:Int,radius:Int,horizontal:Boolean) {
    val lines=if(horizontal) height else width
    val length=if(horizontal) width else height
    val kernel=radius*2+1
    for(line in 0 until lines) {
        fun index(pos:Int):Int = if(horizontal) line*width+pos.coerceIn(0,length-1) else pos.coerceIn(0,length-1)*width+line
        var red=0;var green=0;var blue=0
        for(i in -radius..radius) {
            val pixel=input[index(i)]
            red+=(pixel ushr 16) and 255;green+=(pixel ushr 8) and 255;blue+=pixel and 255
        }
        for(pos in 0 until length) {
            output[index(pos)]=(255 shl 24) or ((red/kernel) shl 16) or ((green/kernel) shl 8) or (blue/kernel)
            val old=input[index(pos-radius)]
            val next=input[index(pos+radius+1)]
            red+=((next ushr 16) and 255)-((old ushr 16) and 255)
            green+=((next ushr 8) and 255)-((old ushr 8) and 255)
            blue+=(next and 255)-(old and 255)
        }
    }
}
