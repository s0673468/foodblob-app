package org.example.foodblob.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.SkinId
import kotlin.math.*

internal data class JellyContact(val point: Offset, val seconds: Float, val strength: Float = 1f)
internal data class JellyPaint(val point: Offset, val ageMs: Long, val after: Color, val tint: Color)
internal data class JellyFrame(
    val profile: JellyProfile,
    val color: Color,
    val paints: List<JellyPaint>,
    val contacts: List<JellyContact>,
    val press: Offset,
    val pressDepth: Float,
    val skin: SkinId,
    val translucency: Float = 0f,
)

internal interface JellyVolumeMaterial {
    fun draw(scope: DrawScope, path: Path, frame: JellyFrame)
}

internal fun createJellyVolumeMaterial(): JellyVolumeMaterial =
    if (Build.VERSION.SDK_INT >= 33) GpuJellyVolumeMaterial() else MeshJellyVolumeMaterial()

/** Suspended glints and the entering offering sit behind the transmitting skin. */
private fun DrawScope.drawJellyInterior(path: Path, frame: JellyFrame) = clipPath(path) {
    if (frame.translucency <= 0f) return@clipPath
    val profile = frame.profile
    val middle = Offset(profile.center.x.toFloat(), profile.center.y.toFloat())
    val scale = profile.scale.toFloat()
    repeat(3) { index ->
        val angle = index * 2.399 + profile.radii[index * 17] * .8
        val point = middle + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (scale * (.25f + index * .13f))
        val radius = scale * (.025f + index * .006f)
        drawCircle(Color.White.copy(alpha = .19f * frame.translucency), radius, point, style = Stroke(maxOf(.7f, scale*.006f)))
        drawCircle(Color.White.copy(alpha = .22f * frame.translucency), radius*.32f, point - Offset(radius*.24f,radius*.28f))
    }
    frame.paints.forEach { offering ->
        val alpha = JellyVolumeGeometry.absorptionAlpha(offering.ageMs).toFloat()
        if (alpha > 0f) {
            val point = JellyVolumeGeometry.absorptionPoint(
                BlobPoint(offering.point.x.toDouble(), offering.point.y.toDouble()), profile.center, offering.ageMs,
            )
            val position = Offset(point.x.toFloat(), point.y.toFloat())
            val progress = JellyVolumeGeometry.paintFraction(offering.ageMs).toFloat()
            val radius = scale * (.085f + progress*.21f)
            drawCircle(Brush.radialGradient(
                listOf(offering.tint.copy(alpha = alpha), offering.tint.copy(alpha = alpha*.34f), Color.Transparent),
                position, radius,
            ), radius, position)
            // A submerged curved ribbon keeps the offered colour recognisable as
            // it folds into the established mix. The same path serves GPU and mesh.
            val incoming = offering.point-middle
            val tangent = Offset(-incoming.y,incoming.x)/incoming.getDistance().coerceAtLeast(1f)
            val tail = position + incoming*(.20f*(1f-progress))
            val end = position-incoming*(.22f*progress)
            val bend = tangent*(scale*.18f*sin(progress*PI).toFloat())
            val ribbon = Path().apply {
                moveTo(tail.x,tail.y)
                cubicTo(position.x+bend.x,position.y+bend.y,end.x+bend.x,end.y+bend.y,end.x,end.y)
            }
            drawPath(ribbon,offering.tint.copy(alpha=alpha*.70f),style=Stroke(scale*(.035f+.045f*progress),cap=StrokeCap.Round))
            drawPath(ribbon,Color.White.copy(alpha=alpha*.18f),style=Stroke(scale*.009f,cap=StrokeCap.Round))
            drawCircle(Color.White.copy(alpha = alpha*.6f), radius*.17f, position - Offset(radius*.25f,radius*.28f))
        }
    }
    frame.contacts.take(JellyVolumeGeometry.CONTACTS).forEach { contact ->
        val progress = (contact.seconds/.5f).coerceIn(0f,1f)
        val light = LiquidFeedbackMotion.landingLight(progress)*contact.strength.coerceIn(0f,1f)
        if (contact.seconds >= 0f && light > .001f) {
            val point = contact.point + (middle-contact.point)*(.62f*progress)
            val radius = scale*(.10f+.40f*progress)
            // The travelling light sits inside the volume; the transmitting
            // surface softens it into the ripple instead of painting a white ring.
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = light*.25f),
                frame.color.copy(alpha = light*.18f),Color.Transparent),point,radius),radius,point)
        }
    }
}

/** One native shader, no WebView, offscreen bitmap uploads, or per-frame shader compilation. */
@RequiresApi(33)
internal class GpuJellyVolumeMaterial : JellyVolumeMaterial {
    private val shader = RuntimeShader(JELLY_VOLUME_SHADER)
    private val brush = ShaderBrush(shader)
    // RuntimeShader copies uniforms; these renderer-owned buffers can be reused every frame.
    private val contacts = FloatArray(JellyVolumeGeometry.CONTACTS*4)
    private val paints = FloatArray(JellyVolumeGeometry.PAINTS*4)
    private val afterColors = FloatArray(JellyVolumeGeometry.PAINTS*4)
    private val tints = FloatArray(JellyVolumeGeometry.PAINTS*4)
    override fun draw(scope: DrawScope, path: Path, frame: JellyFrame) {
        scope.drawJellyInterior(path, frame)
        val profile = frame.profile
        shader.setFloatUniform("body", profile.center.x.toFloat(), profile.center.y.toFloat(), profile.scale.toFloat())
        shader.setFloatUniform("radii", profile.radii)
        shader.setColorUniform("baseColor", frame.color.toArgb())
        shader.setFloatUniform("night", if (frame.skin == SkinId.SHRINE) 1f else 0f)
        shader.setFloatUniform("translucency", BlobAppearance.normalize(frame.translucency))
        fun local(point: Offset) = Offset(
            ((point.x-profile.center.x)/profile.scale).toFloat(),
            ((point.y-profile.center.y)/profile.scale).toFloat(),
        )
        val press = local(frame.press)
        shader.setFloatUniform("press", press.x, press.y, frame.pressDepth)
        repeat(JellyVolumeGeometry.CONTACTS) { i ->
            val event = frame.contacts.getOrNull(i)
            val point = event?.let { local(it.point) } ?: Offset.Zero
            contacts[i*4] = point.x; contacts[i*4+1] = point.y
            contacts[i*4+2] = event?.seconds ?: -1f
            contacts[i*4+3] = event?.strength ?: 0f
        }
        shader.setFloatUniform("contacts", contacts)
        repeat(JellyVolumeGeometry.PAINTS) { i ->
            val event = frame.paints.getOrNull(i)
            val point = event?.let { local(it.point) } ?: Offset.Zero
            paints[i*4] = point.x; paints[i*4+1] = point.y
            paints[i*4+2] = event?.let { JellyVolumeGeometry.paintFraction(it.ageMs).toFloat() } ?: 0f
            paints[i*4+3] = if (event != null && event.ageMs >= ConnectedBlobMotion.FLIGHT_MS) 1f else 0f
            val after = event?.after ?: frame.color
            val tint = event?.tint ?: frame.color
            afterColors[i*4] = after.red; afterColors[i*4+1] = after.green; afterColors[i*4+2] = after.blue; afterColors[i*4+3] = 1f
            tints[i*4] = tint.red; tints[i*4+1] = tint.green; tints[i*4+2] = tint.blue; tints[i*4+3] = 1f
        }
        shader.setFloatUniform("paints", paints)
        shader.setFloatUniform("afterColors", afterColors)
        shader.setFloatUniform("tints", tints)
        with(scope) { drawPath(path, brush) }
    }
}

/** API26–32 still gets a real normal-lit dome. Gouraud triangles deform with the same profile. */
internal class MeshJellyVolumeMaterial : JellyVolumeMaterial {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rings = 14
    private val sectors = 64
    private val directionX = DoubleArray(sectors) { cos(it*2*PI/sectors) }
    private val directionY = DoubleArray(sectors) { sin(it*2*PI/sectors) }
    private val boundary = DoubleArray(sectors)
    private val vertices = FloatArray((rings+1)*sectors*2)
    private val colors = IntArray((rings+1)*sectors)
    // Shared vertices keep the older-device path below a thousand lighting samples.
    private val indices = ShortArray(rings*sectors*6).also { result ->
        var offset = 0
        repeat(rings) { ring -> repeat(sectors) { sector ->
            val next = (sector+1)%sectors
            val a = ring*sectors+sector; val b = (ring+1)*sectors+sector
            val c = (ring+1)*sectors+next; val d = ring*sectors+next
            for (vertex in intArrayOf(a,b,c,a,c,d)) result[offset++] = vertex.toShort()
        } }
    }

    override fun draw(scope: DrawScope, path: Path, frame: JellyFrame) {
        scope.drawJellyInterior(path, frame)
        val transmissionAmount = BlobAppearance.normalize(frame.translucency)
        val shape = frame.profile
        val light = JellyVolumeGeometry.lightOffset(
            (frame.press.x-shape.center.x)/shape.scale,
            (frame.press.y-shape.center.y)/shape.scale,frame.pressDepth.toDouble(),
        )
        repeat(sectors) { boundary[it] = shape.radiusAt(it*2*PI/sectors) }
        fun put(vertex: Int, radius: Double, sector: Int) {
            val x = directionX[sector]*boundary[sector]*radius
            val y = directionY[sector]*boundary[sector]*radius
            vertices[vertex*2] = (shape.center.x+x*shape.scale).toFloat()
            vertices[vertex*2+1] = (shape.center.y+y*shape.scale).toFloat()
            val surface = shape.gradient(x,y)
            var dx = surface.x
            var dy = surface.y
            for (index in 0 until minOf(frame.contacts.size,JellyVolumeGeometry.CONTACTS)) {
                val event = frame.contacts[index]
                val qx = x-(event.point.x-shape.center.x)/shape.scale
                val qy = y-(event.point.y-shape.center.y)/shape.scale
                val distance = hypot(qx,qy).coerceAtLeast(.000001)
                val slope = JellyVolumeGeometry.contactSlope(distance,event.seconds.toDouble())*event.strength/distance
                dx += qx*slope; dy += qy*slope
            }
            val px0 = x-(frame.press.x-shape.center.x)/shape.scale
            val py0 = y-(frame.press.y-shape.center.y)/shape.scale
            val pressSlope = .19*frame.pressDepth.coerceIn(0f,1.2f)*exp(-(px0*px0+py0*py0)/.32)/.32
            dx += px0*pressSlope; dy += py0*pressSlope
            val norm = sqrt(dx*dx+dy*dy+1)
            val nx = -dx/norm; val ny = -dy/norm; val nz = 1/norm
            var base = frame.color
            frame.paints.forEach { event ->
                if (event.ageMs >= ConnectedBlobMotion.FLIGHT_MS) {
                    val progress = JellyVolumeGeometry.paintFraction(event.ageMs)
                    val distance = hypot(vertices[vertex*2]-event.point.x.toDouble(), vertices[vertex*2+1]-event.point.y.toDouble())/shape.scale
                    val coverage = ((progress*4-distance+.15)/.3).coerceIn(0.0,1.0)
                    base = lerp(base, event.after, coverage.toFloat())
                    base = lerp(base, event.tint, (.34*sin(progress*PI)*coverage).toFloat())
                }
            }
            val thickness = sqrt(max(0.0,1-radius*radius))
            val diffuse = max(0.0, nx*-.44+ny*-.57+nz*.69)
            val fresnel = .025+.22*(1-nz).pow(3)
            val reflectedX = 2*nz*nx; val reflectedY = 2*nz*ny
            val window = exp(-((reflectedX+.42-light.x)/.38).pow(4)-((reflectedY+.48-light.y)/.43).pow(4))*.36
            val side = exp(-((reflectedX-.80)/.22).pow(2)-((reflectedY-.04)/.50).pow(2))*.16
            val caustic = exp(-((radius-.89)/.095).pow(2))*max(0.0,ny)*.12
            fun channel(value: Float, cool: Double): Float {
                val transmission = exp(-((1-value)*1.8+.16)*thickness)
                val night = if(frame.skin==SkinId.SHRINE) 1.0 else 0.0
                val glass = (value*(.32+.07*night+(.42+.02*night)*diffuse)+transmission*(.31-.02*night)+fresnel*cool*(1+.28*night)+window+side*cool+caustic).coerceIn(0.0,1.0)
                val wet = window * .32
                val paint = ((value * (.56 + .48 * diffuse)) * (1 - wet) + wet).coerceIn(0.0, 1.0)
                return (paint + (glass - paint) * transmissionAmount).toFloat()
            }
            val glassAlpha = (.20 + .32*thickness + minOf(.16, fresnel + window*.24 + side*.2)).coerceIn(.20,.76).toFloat()
            val alpha = 1f + (glassAlpha - 1f) * transmissionAmount
            colors[vertex] = Color(channel(base.red,.65),channel(base.green,.89),channel(base.blue,1.0),alpha).toArgb()
        }
        repeat(rings+1) { ring -> repeat(sectors) { sector ->
            put(ring*sectors+sector,ring.toDouble()/rings,sector)
        } }
        with(scope) {
            clipPath(path) {
                drawContext.canvas.nativeCanvas.drawVertices(
                    android.graphics.Canvas.VertexMode.TRIANGLES, vertices.size, vertices, 0,
                    null, 0, colors, 0, indices, 0, indices.size, paint,
                )
            }
        }
    }
}

internal const val JELLY_VOLUME_SHADER = """
uniform float3 body;
uniform float radii[64];
layout(color) uniform half4 baseColor;
uniform float night;
uniform float translucency;
uniform float3 press;
uniform float4 contacts[4];
uniform float4 paints[8];
uniform float4 afterColors[8];
uniform float4 tints[8];

// AGSL requires constant uniform-array indices. Each path makes six choices.
float readRadius(int index) {
    return (index < 32 ? (index < 16 ? (index < 8 ? (index < 4 ? (index < 2 ? (index < 1 ? radii[0] : radii[1]) : (index < 3 ? radii[2] : radii[3])) : (index < 6 ? (index < 5 ? radii[4] : radii[5]) : (index < 7 ? radii[6] : radii[7]))) : (index < 12 ? (index < 10 ? (index < 9 ? radii[8] : radii[9]) : (index < 11 ? radii[10] : radii[11])) : (index < 14 ? (index < 13 ? radii[12] : radii[13]) : (index < 15 ? radii[14] : radii[15])))) : (index < 24 ? (index < 20 ? (index < 18 ? (index < 17 ? radii[16] : radii[17]) : (index < 19 ? radii[18] : radii[19])) : (index < 22 ? (index < 21 ? radii[20] : radii[21]) : (index < 23 ? radii[22] : radii[23]))) : (index < 28 ? (index < 26 ? (index < 25 ? radii[24] : radii[25]) : (index < 27 ? radii[26] : radii[27])) : (index < 30 ? (index < 29 ? radii[28] : radii[29]) : (index < 31 ? radii[30] : radii[31]))))) : (index < 48 ? (index < 40 ? (index < 36 ? (index < 34 ? (index < 33 ? radii[32] : radii[33]) : (index < 35 ? radii[34] : radii[35])) : (index < 38 ? (index < 37 ? radii[36] : radii[37]) : (index < 39 ? radii[38] : radii[39]))) : (index < 44 ? (index < 42 ? (index < 41 ? radii[40] : radii[41]) : (index < 43 ? radii[42] : radii[43])) : (index < 46 ? (index < 45 ? radii[44] : radii[45]) : (index < 47 ? radii[46] : radii[47])))) : (index < 56 ? (index < 52 ? (index < 50 ? (index < 49 ? radii[48] : radii[49]) : (index < 51 ? radii[50] : radii[51])) : (index < 54 ? (index < 53 ? radii[52] : radii[53]) : (index < 55 ? radii[54] : radii[55]))) : (index < 60 ? (index < 58 ? (index < 57 ? radii[56] : radii[57]) : (index < 59 ? radii[58] : radii[59])) : (index < 62 ? (index < 61 ? radii[60] : radii[61]) : (index < 63 ? radii[62] : radii[63]))))));
}
float2 radialSurface(float2 p) {
    float angle = (atan(p.y,p.x)+6.2831853)/6.2831853;
    float a = fract(angle)*64.0;
    int i = int(clamp(floor(a), 0.0, 63.0));
    float t = fract(a);
    // AGSL has no integer remainder operator. Keep every uniform lookup bounded.
    int previous = i == 0 ? 63 : i-1;
    int next = i == 63 ? 0 : i+1;
    int after = i >= 62 ? i-62 : i+2;
    float r0 = readRadius(previous); float r1 = readRadius(i);
    float r2 = readRadius(next); float r3 = readRadius(after);
    float radius = .5*(2.0*r1+(-r0+r2)*t+(2.0*r0-5.0*r1+4.0*r2-r3)*t*t+(-r0+3.0*r1-3.0*r2+r3)*t*t*t);
    float slope = .5*((-r0+r2)+2.0*(2.0*r0-5.0*r1+4.0*r2-r3)*t+3.0*(-r0+3.0*r1-3.0*r2+r3)*t*t)*10.185916;
    return float2(max(.08,radius),radius>.08 ? slope : 0.0);
}
// Exact derivatives share one polar contour sample instead of five height samples.
float2 surfaceGradient(float2 p,float2 radial) {
    float distance = length(p);
    float edge = radial.x-distance;
    float2 gradient = float2(0.0);
    if (distance>.000001 && edge>=-.000001) {
        float attenuation = exp(-max(edge,.000001)*4.5);
        float root = sqrt(max(.000000001,1.0-attenuation));
        float shoulder = .68*root;
        float slope = .68*2.25*attenuation/root;
        float t = clamp(distance/.40,0.0,1.0);
        float blend = t*t*(3.0-2.0*t);
        float blendSlope = distance<.40 ? 6.0*t*(1.0-t)/.40 : 0.0;
        gradient = ((shoulder-.66)*blendSlope-slope*blend)*p/distance;
        gradient += slope*blend*radial.y*float2(-p.y,p.x)/(distance*distance);
    }
    for (int i=0;i<4;i++) {
        float t = contacts[i].z;
        if (t>=0.0 && t<.5) {
            float2 q = p-contacts[i].xy;
            float d = max(length(q),.000001);
            float rise = clamp(t/.06,0.0,1.0);
            float envelope = rise*rise*(3.0-2.0*rise)*(1.0-t/.5)*(1.0-t/.5);
            float dent = -.065*exp(-d*d/.32)*exp(-t*4.0)*envelope;
            float front = d-t*3.8;
            float back = d+t*3.8;
            float slope = dent*(-2.0*d/.32)-.052*envelope/.1764*(front*exp(-front*front/.1764)+back*exp(-back*back/.1764));
            gradient += q*(slope*contacts[i].w/d);
        }
    }
    float2 pd = p-press.xy;
    return gradient+.19*clamp(press.z,0.0,1.2)*exp(-dot(pd,pd)/.32)*pd/.32;
}
half4 main(float2 coord) {
    float2 p = (coord-body.xy)/body.z;
    float2 radial = radialSurface(p);
    float2 gradient = surfaceGradient(p,radial);
    float3 n = normalize(float3(-gradient,1.0));
    float3 base = baseColor.rgb;
    for (int i=0;i<8;i++) {
        if (paints[i].w>0.0) {
            float t = paints[i].z;
            float2 q = p-paints[i].xy;
            float d = length(q);
            float curl = .07*sin(atan(q.y,q.x)*3.0+d*9.0-t*10.0)*sin(t*3.1415927);
            float coverage = 1.0-smoothstep(t*4.0-.15,t*4.0+.15,d+curl);
            base = mix(base,afterColors[i].rgb,coverage);
            base = mix(base,tints[i].rgb,.34*sin(t*3.1415927)*coverage);
        }
    }
    float ratio = length(p)/radial.x;
    float thickness = sqrt(max(0.0,1.0-ratio*ratio));
    float3 absorption = (1.0-base)*1.8+.16;
    float3 transmitted = exp(-absorption*thickness);
    float diffuse = max(0.0,dot(n,normalize(float3(-.44,-.57,.69))));
    float fresnel = .025+.22*pow(1.0-n.z,3.0);
    float3 reflected = reflect(float3(0,0,-1),n);
    float2 light = clamp(press.xy,-1.0,1.0)*clamp(press.z,0.0,1.0)*.16;
    float window = exp(-pow(abs((reflected.x+.42-light.x)/.38),4.0)-pow(abs((reflected.y+.48-light.y)/.43),4.0))*.36;
    float side = exp(-pow(abs((reflected.x-.80)/.22),2.0)-pow(abs((reflected.y-.04)/.50),2.0))*.16;
    float caustic = exp(-pow(abs((ratio-.89)/.095),2.0))*max(0.0,n.y)*.12;
    float3 cool = mix(float3(.65,.89,1.0),float3(.32,.89,1.0),night);
    float3 material = base*(.32+.07*night+(.42+.02*night)*diffuse)+transmitted*(.31-.02*night);
    material += fresnel*cool*(1.0+.28*night)+window+side*cool+caustic*float3(1.0,.91,.59);
    float alpha = clamp(.20+.32*thickness+min(.16,fresnel+window*.24+side*.2),.20,.76);
    float wet = window * .32;
    float3 paint = clamp(base * (.56 + .48 * diffuse) * (1.0 - wet) + wet, 0.0, 1.0);
    material = mix(paint, clamp(material, 0.0, 1.0), translucency);
    alpha = mix(1.0, alpha, translucency);
    // RuntimeShader output is premultiplied: the real scene remains visible
    // through the thin rim and tinted body.
    return half4(half3(clamp(material,0.0,1.0)*alpha),half(alpha));
}
"""
