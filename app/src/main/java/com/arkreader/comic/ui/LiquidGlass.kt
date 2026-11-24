package com.arkreader.comic.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import com.arkreader.comic.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal enum class LiquidGlassPreset {
    FLOATING_DOCK,
    FROSTED_BAR,
    READER_CAPSULE,
    READER_DRAWER,
    CLEAR_LENS,
    SUBTLE_PILL
}

/**
 * Optical Liquid Glass drawable implementing the WWDC25 multi-layer glass model:
 * - Edge-only rounded-rectangle SDF optical refraction (center stays undistorted)
 * - Subtle bevel chromatic dispersion (API 33+ AGSL GPU RuntimeShader)
 * - Downsampled isotropic frosted blur (for panels/docks) vs. crystal-clear lens (for floating orbs/buttons)
 * - Dual-angle opposing Fresnel specular rim + top caustic sheen + inner depth shadow
 * - Hardware-accelerated Canvas.drawBitmapMesh SDF refraction fallback for API 28-32
 */
internal class LiquidGlassDrawable(
    private val density: Float,
    private val preset: LiquidGlassPreset,
    private val cornerRadiusDp: Float,
    private var dark: Boolean,
    private val forceDark: Boolean = false
) : Drawable() {

    private var backdropBitmap: Bitmap? = null
    private var backdropShader: BitmapShader? = null
    private val shaderMatrix = Matrix()

    private val gpuPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val boundsF = RectF()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fallbackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lightU = 0.18f
    private var lightV = 0.12f
    private var selectedState = false

    // Precomputed SDF displacement mesh for API 28-32 hardware-accelerated Canvas.drawBitmapMesh
    private var meshWidth = -1
    private var meshHeight = -1
    private var meshVerts: FloatArray? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private var runtimeShader: RuntimeShader? = null

    private val effectiveDark: Boolean
        get() = forceDark || dark

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimeShader = runCatching { RuntimeShader(LIQUID_GLASS_AGSL) }.getOrNull()
        }
        rebuildFallbackPaints()
    }

    fun setDarkMode(isDark: Boolean) {
        if (dark == isDark) return
        dark = isDark
        rebuildFallbackPaints()
        invalidateSelf()
    }

    fun setSelectedGlow(selected: Boolean) {
        if (selectedState == selected) return
        selectedState = selected
        rebuildFallbackPaints()
        invalidateSelf()
    }

    fun setLightPosition(u: Float, v: Float) {
        val nu = u.coerceIn(0f, 1f)
        val nv = v.coerceIn(0f, 1f)
        if (abs(nu - lightU) > 0.01f || abs(nv - lightV) > 0.01f) {
            lightU = nu
            lightV = nv
            invalidateSelf()
        }
    }

    fun resetLightPosition() {
        setLightPosition(0.18f, 0.12f)
    }

    fun updateBackdrop(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) {
            backdropBitmap = null
            backdropShader = null
            return
        }
        if (backdropBitmap !== bitmap) {
            backdropBitmap = bitmap
            backdropShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        boundsF.set(bounds)
        val radius = resolvedCornerRadius(boundsF.width(), boundsF.height())
        clipPath.reset()
        clipPath.addRoundRect(boundsF, radius, radius, Path.Direction.CW)
        meshWidth = -1
        rebuildFallbackPaints()
    }

    override fun draw(canvas: Canvas) {
        val w = boundsF.width()
        val h = boundsF.height()
        if (w < 2f || h < 2f) return

        val bmp = backdropBitmap
        val bmpShader = backdropShader
        val radius = resolvedCornerRadius(w, h)
        val bevel = resolvedBevelPx(w, h, radius)

        if (bmp != null && !bmp.isRecycled && bmpShader != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
                drawAgsl(canvas, bmp, bmpShader, w, h, radius, bevel)
                return
            }
            drawMeshFallback(canvas, bmp, w, h, radius, bevel)
            return
        }

        // Clean optical fallback before first backdrop capture
        drawStaticOpticalGlass(canvas, radius)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawAgsl(
        canvas: Canvas,
        bmp: Bitmap,
        bmpShader: BitmapShader,
        w: Float,
        h: Float,
        radius: Float,
        bevel: Float
    ) {
        val shader = runtimeShader ?: return
        shaderMatrix.reset()
        shaderMatrix.setScale(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        bmpShader.setLocalMatrix(shaderMatrix)
        shader.setInputShader("backdrop", bmpShader)

        val spec = tuningFor(preset, effectiveDark, selectedState, density)
        shader.setFloatUniform("resolution", w, h)
        shader.setFloatUniform("cornerRadius", radius)
        shader.setFloatUniform("bevelWidth", bevel)
        shader.setFloatUniform("refractionStrength", spec.refractionPx)
        shader.setFloatUniform("chromaticPx", spec.chromaticPx)
        shader.setFloatUniform("opticalBoost", spec.opticalBoost)
        shader.setFloatUniform("lightPos", lightU, lightV)
        shader.setFloatUniform("rimIntensity", spec.rimIntensity)
        shader.setFloatUniform("innerShadowOpacity", spec.innerShadowOpacity)
        shader.setFloatUniform("outerDarkLipOpacity", spec.outerDarkLipOpacity)
        shader.setColorUniform("tintColor", spec.tintColor)
        shader.setColorUniform("specularColor", spec.specularColor)
        shader.setColorUniform("secondaryRimColor", spec.secondaryRimColor)

        gpuPaint.shader = shader
        val save = canvas.save()
        canvas.translate(boundsF.left, boundsF.top)
        canvas.drawRect(0f, 0f, w, h, gpuPaint)
        canvas.restoreToCount(save)
    }

    private fun drawMeshFallback(
        canvas: Canvas,
        bmp: Bitmap,
        w: Float,
        h: Float,
        radius: Float,
        bevel: Float
    ) {
        val spec = tuningFor(preset, effectiveDark, selectedState, density)
        val iw = w.toInt()
        val ih = h.toInt()
        if (meshWidth != iw || meshHeight != ih || meshVerts == null) {
            meshVerts = buildSdfRefractionMesh(w, h, radius, bevel, spec.refractionPx)
            meshWidth = iw
            meshHeight = ih
        }
        val verts = meshVerts ?: return

        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.translate(boundsF.left, boundsF.top)
        canvas.drawBitmapMesh(bmp, MESH_COLS, MESH_ROWS, verts, 0, null, 0, null)
        canvas.restoreToCount(save)

        // Material tint + inner depth + caustic sheen + dual-angle specular rim
        drawOpticalOverlays(canvas, radius, spec)
    }

    private fun drawStaticOpticalGlass(canvas: Canvas, radius: Float) {
        val spec = tuningFor(preset, effectiveDark, selectedState, density)
        canvas.drawRoundRect(boundsF, radius, radius, fallbackBgPaint)
        drawOpticalOverlays(canvas, radius, spec)
    }

    private fun drawOpticalOverlays(
        canvas: Canvas,
        radius: Float,
        spec: GlassTuning
    ) {
        tintPaint.color = spec.tintColor
        canvas.drawRoundRect(boundsF, radius, radius, tintPaint)

        // Top caustic sheen + bottom inner depth shadow
        canvas.drawRoundRect(boundsF, radius, radius, innerShadowPaint)
        canvas.drawRoundRect(boundsF, radius, radius, sheenPaint)

        // Crisp dual-angle Fresnel rim stroke
        val inset = max(0.75f * density, 1f)
        rimPaint.strokeWidth = inset * 1.25f
        val rimRect = RectF(
            boundsF.left + inset * 0.6f,
            boundsF.top + inset * 0.6f,
            boundsF.right - inset * 0.6f,
            boundsF.bottom - inset * 0.6f
        )
        canvas.drawRoundRect(rimRect, max(0f, radius - inset * 0.5f), max(0f, radius - inset * 0.5f), rimPaint)
    }

    private fun rebuildFallbackPaints() {
        val w = max(boundsF.width(), 48f * density)
        val h = max(boundsF.height(), 48f * density)
        val d = effectiveDark
        val spec = tuningFor(preset, d, selectedState, density)

        fallbackBgPaint.shader = LinearGradient(
            boundsF.left, boundsF.top, boundsF.left + w, boundsF.top + h,
            when {
                selectedState && d -> intArrayOf(0xB442A2FF.toInt(), 0x862466C6.toInt())
                selectedState -> intArrayOf(0xECE0F1FF.toInt(), 0xD8C2E0FF.toInt())
                preset == LiquidGlassPreset.CLEAR_LENS -> intArrayOf(0xB84673A8.toInt(), 0xA81D3354.toInt())
                d -> intArrayOf(0xCC1E2C45.toInt(), 0xBA111A2C.toInt())
                else -> intArrayOf(0xE0FFFFFF.toInt(), 0xC8ECF4FF.toInt())
            },
            null,
            Shader.TileMode.CLAMP
        )

        sheenPaint.shader = LinearGradient(
            boundsF.left, boundsF.top, boundsF.left, boundsF.top + h * 0.58f,
            intArrayOf(
                Color.argb((Color.alpha(spec.specularColor) * (if (d) 0.20f else 0.42f)).toInt(), 255, 255, 255),
                0x00FFFFFF
            ),
            null,
            Shader.TileMode.CLAMP
        )

        innerShadowPaint.shader = LinearGradient(
            boundsF.left, boundsF.top + h * 0.52f, boundsF.left, boundsF.top + h,
            intArrayOf(
                0x00000000,
                Color.argb((255f * spec.innerShadowOpacity).toInt().coerceIn(0, 120), 4, 10, 22)
            ),
            null,
            Shader.TileMode.CLAMP
        )

        rimPaint.shader = LinearGradient(
            boundsF.left, boundsF.top, boundsF.left + w, boundsF.top + h,
            intArrayOf(
                spec.specularColor,
                Color.argb(if (d) 36 else 110, 220, 234, 255),
                spec.secondaryRimColor
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun resolvedCornerRadius(w: Float, h: Float): Float {
        return min(cornerRadiusDp * density, min(w, h) * 0.5f)
    }

    private fun resolvedBevelPx(w: Float, h: Float, radius: Float): Float {
        val minDim = min(w, h)
        return when (preset) {
            LiquidGlassPreset.CLEAR_LENS -> (minDim * 0.44f).coerceIn(10f * density, 22f * density)
            LiquidGlassPreset.READER_DRAWER -> 20f * density
            LiquidGlassPreset.SUBTLE_PILL -> (minDim * 0.36f).coerceIn(8f * density, 14f * density)
            else -> max(14f * density, radius * 0.60f).coerceAtMost(minDim * 0.38f)
        }
    }

    override fun setAlpha(alpha: Int) {
        gpuPaint.alpha = alpha
        fallbackBgPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        gpuPaint.colorFilter = colorFilter
        fallbackBgPaint.colorFilter = colorFilter
    }

    @Deprecated("Android drawable compatibility")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private data class GlassTuning(
        val refractionPx: Float,
        val chromaticPx: Float,
        val opticalBoost: Float,
        val rimIntensity: Float,
        val innerShadowOpacity: Float,
        val outerDarkLipOpacity: Float,
        val tintColor: Int,
        val specularColor: Int,
        val secondaryRimColor: Int
    )

    companion object {
        private const val MESH_COLS = 16
        private const val MESH_ROWS = 16

        private fun tuningFor(
            preset: LiquidGlassPreset,
            dark: Boolean,
            selected: Boolean,
            density: Float
        ): GlassTuning {
            if (selected) {
                return GlassTuning(
                    refractionPx = 14f * density,
                    chromaticPx = 2.4f * density,
                    opticalBoost = 1.16f,
                    rimIntensity = 1.28f,
                    innerShadowOpacity = if (dark) 0.16f else 0.14f,
                    outerDarkLipOpacity = if (dark) 0.10f else 0.22f,
                    tintColor = if (dark) 0x783A9BFF else 0x86CBE8FF.toInt(),
                    specularColor = 0xFAFFFFFF.toInt(),
                    secondaryRimColor = 0xC87AC4FF.toInt()
                )
            }
            return when (preset) {
                LiquidGlassPreset.CLEAR_LENS -> GlassTuning(
                    refractionPx = 22.0f * density,
                    chromaticPx = 3.4f * density,
                    opticalBoost = 1.24f,
                    rimIntensity = 1.32f,
                    innerShadowOpacity = 0.24f,
                    outerDarkLipOpacity = 0.14f,
                    tintColor = 0x58182E52,
                    specularColor = 0xFAFFFFFF.toInt(),
                    secondaryRimColor = 0xD676CCFF.toInt()
                )
                LiquidGlassPreset.READER_CAPSULE -> GlassTuning(
                    refractionPx = 22.0f * density,
                    chromaticPx = 3.0f * density,
                    opticalBoost = 1.15f,
                    rimIntensity = 1.24f,
                    innerShadowOpacity = 0.24f,
                    outerDarkLipOpacity = 0.12f,
                    tintColor = 0x760E1626,
                    specularColor = 0xF2FFFFFF.toInt(),
                    secondaryRimColor = 0xB86EC2FF.toInt()
                )
                LiquidGlassPreset.READER_DRAWER -> GlassTuning(
                    refractionPx = 16.0f * density,
                    chromaticPx = 2.2f * density,
                    opticalBoost = 1.00f,
                    rimIntensity = 1.05f,
                    innerShadowOpacity = 0.18f,
                    outerDarkLipOpacity = 0.10f,
                    tintColor = 0xA60E1523.toInt(),
                    specularColor = 0xD8FFFFFF.toInt(),
                    secondaryRimColor = 0x9870B8FF.toInt()
                )
                LiquidGlassPreset.FLOATING_DOCK -> GlassTuning(
                    refractionPx = 24.0f * density,
                    chromaticPx = 3.2f * density,
                    opticalBoost = if (dark) 1.16f else 1.10f,
                    rimIntensity = if (dark) 1.26f else 1.35f,
                    innerShadowOpacity = if (dark) 0.22f else 0.18f,
                    outerDarkLipOpacity = if (dark) 0.10f else 0.26f,
                    tintColor = if (dark) 0x58121E36 else 0x3EF2F9FF,
                    specularColor = if (dark) 0xF4FFFFFF.toInt() else 0xFFFFFFFF.toInt(),
                    secondaryRimColor = if (dark) 0xBC6CC0FF.toInt() else 0xD660A6FF.toInt()
                )
                LiquidGlassPreset.SUBTLE_PILL -> GlassTuning(
                    refractionPx = 18.0f * density,
                    chromaticPx = 2.6f * density,
                    opticalBoost = 1.12f,
                    rimIntensity = if (dark) 1.18f else 1.30f,
                    innerShadowOpacity = if (dark) 0.18f else 0.16f,
                    outerDarkLipOpacity = if (dark) 0.10f else 0.24f,
                    tintColor = if (dark) 0x62121C30 else 0x42F4FAFF,
                    specularColor = 0xF8FFFFFF.toInt(),
                    secondaryRimColor = if (dark) 0xB072C2FF.toInt() else 0xCC66AAFF.toInt()
                )
                LiquidGlassPreset.FROSTED_BAR -> GlassTuning(
                    refractionPx = 22.0f * density,
                    chromaticPx = 3.0f * density,
                    opticalBoost = if (dark) 1.14f else 1.08f,
                    rimIntensity = if (dark) 1.22f else 1.32f,
                    innerShadowOpacity = if (dark) 0.20f else 0.17f,
                    outerDarkLipOpacity = if (dark) 0.10f else 0.25f,
                    tintColor = if (dark) 0x60121D32 else 0x44F4FAFF,
                    specularColor = if (dark) 0xF0FFFFFF.toInt() else 0xFFFFFFFF.toInt(),
                    secondaryRimColor = if (dark) 0xB470C0FF.toInt() else 0xD062A8FF.toInt()
                )
            }
        }

        private fun roundedRectSdf(px: Float, py: Float, halfW: Float, halfH: Float, radius: Float): Float {
            val qx = abs(px) - halfW + radius
            val qy = abs(py) - halfH + radius
            val outX = max(qx, 0f)
            val outY = max(qy, 0f)
            return min(max(qx, qy), 0f) + sqrt(outX * outX + outY * outY) - radius
        }

        private fun buildSdfRefractionMesh(
            w: Float,
            h: Float,
            radius: Float,
            bevel: Float,
            refractionPx: Float
        ): FloatArray {
            val verts = FloatArray((MESH_COLS + 1) * (MESH_ROWS + 1) * 2)
            val halfW = w * 0.5f
            val halfH = h * 0.5f
            var idx = 0
            for (r in 0..MESH_ROWS) {
                val v = r / MESH_ROWS.toFloat()
                val y = v * h
                for (c in 0..MESH_COLS) {
                    val u = c / MESH_COLS.toFloat()
                    val x = u * w
                    val px = x - halfW
                    val py = y - halfH
                    val sdf = roundedRectSdf(px, py, halfW, halfH, radius)
                    val distIn = max(-sdf, 0f)
                    val edgeT = (1f - distIn / max(bevel, 2f)).coerceIn(0f, 1f)
                    val steep = edgeT.toDouble().pow(2.2).toFloat()
                    if (steep > 0.001f) {
                        val eps = 1.5f
                        val gx = roundedRectSdf(px + eps, py, halfW, halfH, radius) -
                            roundedRectSdf(px - eps, py, halfW, halfH, radius)
                        val gy = roundedRectSdf(px, py + eps, halfW, halfH, radius) -
                            roundedRectSdf(px, py - eps, halfW, halfH, radius)
                        val len = sqrt(gx * gx + gy * gy)
                        if (len > 0.0001f) {
                            // Push outer mesh vertices outward along normal so backdrop magnifies/wraps at edge
                            val disp = refractionPx * steep * 0.72f
                            verts[idx++] = x + (gx / len) * disp
                            verts[idx++] = y + (gy / len) * disp
                        } else {
                            verts[idx++] = x
                            verts[idx++] = y
                        }
                    } else {
                        verts[idx++] = x
                        verts[idx++] = y
                    }
                }
            }
            return verts
        }

        // Exact AGSL translation of the WWDC25 / shuding-style roundedRectSDF Liquid Glass optical shader
        private const val LIQUID_GLASS_AGSL = """
            uniform shader backdrop;
            uniform float2 resolution;
            uniform float cornerRadius;
            uniform float bevelWidth;
            uniform float refractionStrength;
            uniform float chromaticPx;
            uniform float opticalBoost;
            uniform float2 lightPos;
            uniform float rimIntensity;
            uniform float innerShadowOpacity;
            uniform float outerDarkLipOpacity;
            layout(color) uniform half4 tintColor;
            layout(color) uniform half4 specularColor;
            layout(color) uniform half4 secondaryRimColor;

            float roundedRectSDF(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + radius;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            float2 roundedRectNormal(float2 p, float2 halfSize, float radius) {
                float eps = 1.0;
                float gx = roundedRectSDF(p + float2(eps, 0.0), halfSize, radius)
                         - roundedRectSDF(p - float2(eps, 0.0), halfSize, radius);
                float gy = roundedRectSDF(p + float2(0.0, eps), halfSize, radius)
                         - roundedRectSDF(p - float2(0.0, eps), halfSize, radius);
                float2 g = float2(gx, gy);
                float len = length(g);
                return len > 0.0001 ? (g / len) : float2(0.0, -1.0);
            }

            half3 applyOpticalTone(half3 rgb, float boost) {
                half luma = dot(rgb, half3(0.299, 0.587, 0.114));
                half3 sat = mix(half3(luma), rgb, half(1.0 + 0.18 * boost));
                half3 con = (sat - half(0.5)) * half(1.0 + 0.12 * boost) + half(0.5);
                return clamp(con * half(1.0 + 0.045 * boost), half3(0.0), half3(1.0));
            }

            half4 main(float2 fragCoord) {
                float2 size = max(resolution, float2(2.0, 2.0));
                float2 halfSize = size * 0.5;
                float radius = min(cornerRadius, min(halfSize.x, halfSize.y));
                float bevel = max(bevelWidth, 2.0);

                float2 p = fragCoord - halfSize;
                float sdf = roundedRectSDF(p, halfSize, radius);
                float shapeAlpha = clamp(0.5 - sdf, 0.0, 1.0);
                if (shapeAlpha <= 0.001) {
                    return half4(0.0);
                }

                float distIn = max(-sdf, 0.0);
                float edgeT = clamp(1.0 - distIn / bevel, 0.0, 1.0);
                float meniscus = edgeT * edgeT * (3.0 - 2.0 * edgeT);
                float steepCurve = pow(edgeT, 2.4);

                float2 normal = roundedRectNormal(p, halfSize, radius);
                float2 refractPos = clamp(fragCoord - normal * (refractionStrength * steepCurve), float2(0.0), size);
                float2 chromaOff = -normal * (chromaticPx * steepCurve);

                half r = backdrop.eval(clamp(refractPos + chromaOff, float2(0.0), size)).r;
                half g = backdrop.eval(refractPos).g;
                half b = backdrop.eval(clamp(refractPos - chromaOff, float2(0.0), size)).b;
                half3 baseRgb = applyOpticalTone(half3(r, g, b), opticalBoost);

                half tintFactor = clamp(tintColor.a * half(1.0 - 0.45 * meniscus), half(0.0), half(1.0));
                half3 glassRgb = mix(baseRgb, tintColor.rgb, tintFactor);

                float2 uv = fragCoord / size;
                float outerRim = smoothstep(bevel * 0.36, 0.0, distIn);
                float crispRim = smoothstep(2.2, 0.0, distIn);
                float depthShade = innerShadowOpacity * (uv.y * 0.52 + meniscus * 0.52) + outerDarkLipOpacity * crispRim;
                glassRgb *= half(clamp(1.0 - depthShade, 0.0, 1.0));

                float topSheen = pow(clamp(1.0 - uv.y * 1.9, 0.0, 1.0), 2.0) * (0.20 + 0.12 * (1.0 - edgeT));
                glassRgb += specularColor.rgb * half(topSheen * float(specularColor.a) * rimIntensity);

                float2 lightDir = normalize((lightPos - 0.5) * size - p + float2(-0.001, -0.001));
                float2 keyLight = normalize(mix(normalize(float2(-0.68, -0.73)), lightDir, 0.45));
                float keyAlign = clamp(dot(normal, keyLight) * 0.65 + 0.35, 0.0, 1.0);
                float oppAlign = clamp(dot(normal, -keyLight) * 0.70 + 0.30, 0.0, 1.0);

                float primarySpec = (crispRim * 0.85 + outerRim * 0.26) * pow(keyAlign, 1.45) * rimIntensity;
                float secondarySpec = (crispRim * 0.58 + outerRim * 0.22) * pow(oppAlign, 1.75) * rimIntensity;

                glassRgb += specularColor.rgb * half(primarySpec * float(specularColor.a));
                glassRgb += secondaryRimColor.rgb * half(secondarySpec * float(secondaryRimColor.a));
                glassRgb = clamp(glassRgb, half3(0.0), half3(1.0));

                return half4(glassRgb * half(shapeAlpha), half(shapeAlpha));
            }
        """
    }
}

/**
 * Attaches live backdrop sampling + touch-reactive optical glint & elastic feedback to any View.
 * Captures the real underlying backdrop view into a reused offscreen Bitmap (downscaled + stack-blurred
 * for frosted presets; 1:1 sharp for CLEAR_LENS), with re-entrancy guards and frame throttling.
 */
internal class LiquidGlassBinder(
    private val targetView: View,
    private val backdropProvider: () -> View?,
    val drawable: LiquidGlassDrawable,
    private val preset: LiquidGlassPreset,
    private val fallbackBaseColor: Int,
    private val interactiveElastic: Boolean = false
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

    private var captureBitmap: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private val targetLoc = IntArray(2)
    private val backdropLoc = IntArray(2)
    private var lastCaptureMs = 0L
    private var isSampling = false
    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        targetView.addOnAttachStateChangeListener(this)
        if (targetView.isAttachedToWindow) {
            targetView.viewTreeObserver.addOnPreDrawListener(this)
        }
        if (interactiveElastic) {
            attachTouchFeedback()
        }
        targetView.post { captureNow(force = true) }
    }

    fun detach() {
        if (!attached) return
        attached = false
        if (targetView.viewTreeObserver.isAlive) {
            targetView.viewTreeObserver.removeOnPreDrawListener(this)
        }
        targetView.removeOnAttachStateChangeListener(this)
        captureBitmap?.recycle()
        captureBitmap = null
        captureCanvas = null
    }

    override fun onViewAttachedToWindow(v: View) {
        if (v.viewTreeObserver.isAlive) {
            v.viewTreeObserver.removeOnPreDrawListener(this)
            v.viewTreeObserver.addOnPreDrawListener(this)
        }
        captureNow(force = true)
    }

    override fun onViewDetachedFromWindow(v: View) {
        if (v.viewTreeObserver.isAlive) {
            v.viewTreeObserver.removeOnPreDrawListener(this)
        }
    }

    override fun onPreDraw(): Boolean {
        captureNow(force = false)
        return true
    }

    fun captureNow(force: Boolean) {
        val backdropView = backdropProvider() ?: return
        if (isSampling || targetView.visibility != View.VISIBLE || backdropView.visibility != View.VISIBLE) return
        val w = targetView.width
        val h = targetView.height
        if (w < 4 || h < 4 || backdropView.width < 4 || backdropView.height < 4) return

        val now = SystemClock.uptimeMillis()
        val minInterval = if (preset == LiquidGlassPreset.CLEAR_LENS) 24L else 33L
        if (!force && now - lastCaptureMs < minInterval) return
        lastCaptureMs = now

        val scale = when (preset) {
            LiquidGlassPreset.CLEAR_LENS -> 0.85f
            LiquidGlassPreset.SUBTLE_PILL -> 0.45f
            else -> 0.25f
        }
        val bw = (w * scale).toInt().coerceIn(8, 360)
        val bh = (h * scale).toInt().coerceIn(8, 360)

        var bmp = captureBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != bw || bmp.height != bh) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            captureBitmap = bmp
            captureCanvas = Canvas(bmp)
        }
        val canvas = captureCanvas ?: return

        targetView.getLocationInWindow(targetLoc)
        backdropView.getLocationInWindow(backdropLoc)
        val dx = (targetLoc[0] - backdropLoc[0]).toFloat()
        val dy = (targetLoc[1] - backdropLoc[1]).toFloat()

        isSampling = true
        val prevVisibility = targetView.visibility
        val sameSubtree = isDescendantOf(targetView, backdropView)
        try {
            if (sameSubtree) targetView.visibility = View.INVISIBLE
            bmp.eraseColor(fallbackBaseColor)
            val save = canvas.save()
            canvas.scale(bw / w.toFloat(), bh / h.toFloat())
            canvas.translate(-dx, -dy)
            backdropView.draw(canvas)
            canvas.restoreToCount(save)

            val blurRadius = when (preset) {
                LiquidGlassPreset.CLEAR_LENS -> 0
                LiquidGlassPreset.SUBTLE_PILL -> 3
                LiquidGlassPreset.FLOATING_DOCK -> 5
                LiquidGlassPreset.FROSTED_BAR -> 5
                LiquidGlassPreset.READER_CAPSULE -> 6
                LiquidGlassPreset.READER_DRAWER -> 8
            }
            if (blurRadius > 0) {
                fastBoxBlur(bmp, blurRadius)
            }
            drawable.updateBackdrop(bmp)
        } catch (_: Throwable) {
            // Keep previous frame gracefully on transient hardware canvas states
        } finally {
            if (sameSubtree) targetView.visibility = prevVisibility
            isSampling = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchFeedback() {
        targetView.setOnTouchListener { v, event ->
            val w = max(v.width.toFloat(), 1f)
            val h = max(v.height.toFloat(), 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drawable.setLightPosition(event.x / w, event.y / h)
                    animateElastic(v, 0.95f, 90L)
                }
                MotionEvent.ACTION_MOVE -> {
                    drawable.setLightPosition(event.x / w, event.y / h)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    drawable.resetLightPosition()
                    animateElastic(v, 1.0f, 180L)
                }
            }
            false
        }
    }

    private fun animateElastic(view: View, scale: Float, durationMs: Long) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(durationMs)
            .start()
    }

    private fun isDescendantOf(child: View, ancestor: View): Boolean {
        var cur: Any? = child
        while (cur is View) {
            if (cur === ancestor) return true
            cur = cur.parent
        }
        return false
    }

    companion object {
        // Zero-allocation separable 2-pass box blur on compact downsampled backdrop bitmap
        private fun fastBoxBlur(bitmap: Bitmap, radius: Int) {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 3 || h < 3 || radius < 1) return
            val pixels = IntArray(w * h)
            val tmp = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val r = radius.coerceIn(1, min(w, h) / 2)
            val div = r * 2 + 1

            repeat(2) {
                // Horizontal pass
                for (y in 0 until h) {
                    val row = y * w
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    for (i in -r..r) {
                        val c = pixels[row + i.coerceIn(0, w - 1)]
                        rSum += (c shr 16) and 0xFF
                        gSum += (c shr 8) and 0xFF
                        bSum += c and 0xFF
                    }
                    for (x in 0 until w) {
                        tmp[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                        val oldC = pixels[row + (x - r).coerceIn(0, w - 1)]
                        val newC = pixels[row + (x + r + 1).coerceIn(0, w - 1)]
                        rSum += ((newC shr 16) and 0xFF) - ((oldC shr 16) and 0xFF)
                        gSum += ((newC shr 8) and 0xFF) - ((oldC shr 8) and 0xFF)
                        bSum += (newC and 0xFF) - (oldC and 0xFF)
                    }
                }
                // Vertical pass
                for (x in 0 until w) {
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    for (i in -r..r) {
                        val c = tmp[i.coerceIn(0, h - 1) * w + x]
                        rSum += (c shr 16) and 0xFF
                        gSum += (c shr 8) and 0xFF
                        bSum += c and 0xFF
                    }
                    for (y in 0 until h) {
                        pixels[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                        val oldC = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                        val newC = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                        rSum += ((newC shr 16) and 0xFF) - ((oldC shr 16) and 0xFF)
                        gSum += ((newC shr 8) and 0xFF) - ((oldC shr 8) and 0xFF)
                        bSum += (newC and 0xFF) - (oldC and 0xFF)
                    }
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}

internal class AmbientGlassCanvasDrawable(private val dark: Boolean) : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb1Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb2Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb3Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = max(bounds.width().toFloat(), 1f)
        val h = max(bounds.height().toFloat(), 1f)
        bgPaint.shader = LinearGradient(
            0f, 0f, w, h,
            if (dark) intArrayOf(0xFF0A0F1C.toInt(), 0xFF0F172A.toInt(), 0xFF131B30.toInt())
            else intArrayOf(0xFFF0F5FD.toInt(), 0xFFEAF1FC.toInt(), 0xFFF3EFFB.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        orb1Paint.shader = RadialGradient(
            w * 0.10f, h * 0.12f, max(w, h) * 0.48f,
            if (dark) 0x723A9EFF else 0x6C3A9EFF,
            0x003A9EFF,
            Shader.TileMode.CLAMP
        )
        orb2Paint.shader = RadialGradient(
            w * 0.12f, h * 0.68f, max(w, h) * 0.44f,
            if (dark) 0x648A6EFF else 0x5E8A6EFF,
            0x008A6EFF,
            Shader.TileMode.CLAMP
        )
        orb3Paint.shader = RadialGradient(
            w * 0.88f, h * 0.16f, max(w, h) * 0.50f,
            if (dark) 0x5E22C5F6 else 0x5822C5F6,
            0x0022C5F6,
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, bgPaint)
        canvas.drawRect(bounds, orb1Paint)
        canvas.drawRect(bounds, orb2Paint)
        canvas.drawRect(bounds, orb3Paint)
    }

    override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { bgPaint.colorFilter = colorFilter }
    @Deprecated("Android drawable compatibility")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

internal fun View.applyLiquidGlass(
    backdropProvider: () -> View?,
    preset: LiquidGlassPreset,
    cornerRadiusDp: Float,
    forceDark: Boolean = false,
    interactiveElastic: Boolean = false,
    elevationDp: Float = 6f
): LiquidGlassDrawable {
    detachLiquidGlass()
    val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val effectiveDark = forceDark || dark
    val drawable = LiquidGlassDrawable(
        density = resources.displayMetrics.density,
        preset = preset,
        cornerRadiusDp = cornerRadiusDp,
        dark = dark,
        forceDark = forceDark
    )
    background = drawable
    elevation = elevationDp * resources.displayMetrics.density
    val baseColor = if (effectiveDark) 0xFF0A0F1C.toInt() else 0xFFEAF1FC.toInt()
    val binder = LiquidGlassBinder(this, backdropProvider, drawable, preset, baseColor, interactiveElastic)
    setTag(R.id.tag_liquid_glass_binder, binder)
    binder.attach()
    return drawable
}

internal fun View.applyLiquidGlass(
    backdropView: View?,
    preset: LiquidGlassPreset,
    cornerRadiusDp: Float,
    forceDark: Boolean = false,
    interactiveElastic: Boolean = false,
    elevationDp: Float = 6f
): LiquidGlassDrawable = applyLiquidGlass(
    backdropProvider = { backdropView },
    preset = preset,
    cornerRadiusDp = cornerRadiusDp,
    forceDark = forceDark,
    interactiveElastic = interactiveElastic,
    elevationDp = elevationDp
)

internal fun View.detachLiquidGlass() {
    val existing = getTag(R.id.tag_liquid_glass_binder) as? LiquidGlassBinder
    if (existing != null) {
        existing.detach()
        setTag(R.id.tag_liquid_glass_binder, null)
    }
}

internal fun View.refreshLiquidGlassBackdrop() {
    (getTag(R.id.tag_liquid_glass_binder) as? LiquidGlassBinder)?.captureNow(force = true)
}
