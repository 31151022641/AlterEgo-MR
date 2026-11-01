package com.oculus.camerademo.model3d

import android.graphics.*

/**
 * Generates a high-detail 512x512 face texture map of Lionel Messi
 * designed to wrap around the canonical 3D Human Face Mesh.
 */
object MessiTextureGenerator {

    fun generateMessiTexture(): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Base Warm Skin Tone with Natural Radial Subsurface Gradient
        val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size * 0.5f, size * 0.45f, size * 0.48f,
                intArrayOf(
                    Color.rgb(235, 186, 154), // Highlighted nose/forehead skin
                    Color.rgb(218, 168, 132), // Mid-tone Latin complexion
                    Color.rgb(185, 135, 102)  // Edge shadow
                ),
                floatArrayOf(0.0f, 0.65f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), skinPaint)

        // 2. Forehead Hairline (Messi's characteristic short textured brown hair)
        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, 130f,
                intArrayOf(Color.rgb(35, 20, 12), Color.rgb(55, 34, 20), Color.TRANSPARENT),
                floatArrayOf(0.0f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        val hairPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.toFloat(), 0f)
            lineTo(size.toFloat(), 95f)
            // Curved hairline with temple peaks
            cubicTo(size * 0.85f, 85f, size * 0.70f, 125f, size * 0.50f, 115f)
            cubicTo(size * 0.30f, 125f, size * 0.15f, 85f, 0f, 95f)
            close()
        }
        canvas.drawPath(hairPath, hairPaint)

        // Individual hair strands for texture
        val strandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(65, 42, 28)
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
        }
        for (i in 0 until 40) {
            val sx = size * 0.15f + (i * 9f)
            canvas.drawLine(sx, 70f, sx + 6f, 118f, strandPaint)
        }

        // 3. Eyebrows (Strong, athletic brown brows with natural arch)
        val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 34, 22)
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
        }
        // Left Eyebrow (viewer's left)
        canvas.drawLine(size * 0.28f, 185f, size * 0.44f, 178f, browPaint)
        // Right Eyebrow (viewer's right)
        canvas.drawLine(size * 0.56f, 178f, size * 0.72f, 185f, browPaint)

        // 4. Expressive Warm Brown Eyes & Natural Eyelid Creases
        val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 240, 242) }
        val irisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(68, 42, 25) }
        val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val eyeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val eyelidCreasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 115, 68, 44)
            strokeWidth = 3f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        // Left Eye
        canvas.drawOval(size * 0.28f, 198f, size * 0.44f, 218f, scleraPaint)
        canvas.drawCircle(size * 0.36f, 208f, 9f, irisPaint)
        canvas.drawCircle(size * 0.36f, 208f, 4.5f, pupilPaint)
        canvas.drawCircle(size * 0.37f, 206f, 2.5f, eyeHighlightPaint)
        val leftCrease = Path().apply {
            moveTo(size * 0.29f, 196f)
            quadTo(size * 0.36f, 190f, size * 0.43f, 196f)
        }
        canvas.drawPath(leftCrease, eyelidCreasePaint)

        // Right Eye
        canvas.drawOval(size * 0.56f, 198f, size * 0.72f, 218f, scleraPaint)
        canvas.drawCircle(size * 0.64f, 208f, 9f, irisPaint)
        canvas.drawCircle(size * 0.64f, 208f, 4.5f, pupilPaint)
        canvas.drawCircle(size * 0.65f, 206f, 2.5f, eyeHighlightPaint)
        val rightCrease = Path().apply {
            moveTo(size * 0.57f, 196f)
            quadTo(size * 0.64f, 190f, size * 0.71f, 196f)
        }
        canvas.drawPath(rightCrease, eyelidCreasePaint)

        // 5. Messi's Distinctive Nose (Prominent straight bridge and nostrils)
        val noseShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 110, 65, 40)
            strokeWidth = 6f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        // Bridge contours
        canvas.drawLine(size * 0.47f, 195f, size * 0.46f, 285f, noseShadow)
        canvas.drawLine(size * 0.53f, 195f, size * 0.54f, 285f, noseShadow)

        // Nose tip highlight & nostrils
        val noseTipHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 255, 230, 210)
        }
        canvas.drawCircle(size * 0.50f, 280f, 14f, noseTipHighlight)

        val nostrilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(85, 45, 30) }
        canvas.drawOval(size * 0.44f, 284f, size * 0.48f, 294f, nostrilPaint)
        canvas.drawOval(size * 0.52f, 284f, size * 0.56f, 294f, nostrilPaint)

        // 6. Natural Lips & Recessed Oral Cavity (Visible on Open Mouth)
        val oralCavityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 16, 16) }
        val toothPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(238, 238, 232) }
        val upperLipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(182, 115, 102) }
        val lowerLipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 130, 115) }

        // Dark oral cavity behind lips
        canvas.drawOval(size * 0.41f, 340f, size * 0.59f, 355f, oralCavityPaint)
        // Upper dental tooth line
        canvas.drawRoundRect(size * 0.44f, 343f, size * 0.56f, 348f, 2f, 2f, toothPaint)

        // Natural lips
        canvas.drawOval(size * 0.40f, 335f, size * 0.60f, 348f, upperLipPaint)
        canvas.drawOval(size * 0.42f, 346f, size * 0.58f, 362f, lowerLipPaint)

        // 7. Messi's Iconic Trimmed Full Beard & Mustache
        // Signature reddish-brown / auburn-tinted trimmed beard
        val beardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size * 0.5f, size * 0.40f, size * 0.38f,
                intArrayOf(Color.TRANSPARENT, Color.argb(160, 115, 62, 32), Color.argb(240, 92, 48, 22)),
                floatArrayOf(0.4f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }

        val beardPath = Path().apply {
            moveTo(size * 0.12f, 260f)
            // Jawline left
            lineTo(size * 0.18f, 390f)
            lineTo(size * 0.32f, 465f)
            // Chin
            lineTo(size * 0.50f, 490f)
            // Jawline right
            lineTo(size * 0.68f, 465f)
            lineTo(size * 0.82f, 390f)
            lineTo(size * 0.88f, 260f)
            // Inner hollow around lips & cheeks
            lineTo(size * 0.74f, 320f)
            lineTo(size * 0.62f, 385f)
            lineTo(size * 0.38f, 385f)
            lineTo(size * 0.26f, 320f)
            close()
        }
        canvas.drawPath(beardPath, beardPaint)

        // Trimmed Mustache
        val stachePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 108, 58, 28)
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(size * 0.38f, 328f, size * 0.49f, 322f, stachePaint)
        canvas.drawLine(size * 0.51f, 322f, size * 0.62f, 328f, stachePaint)

        // Detailed beard texture stubble
        val stubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 88, 44, 20)
            strokeWidth = 2.0f
        }
        for (i in 0 until 120) {
            val bx = size * 0.28f + (i % 20) * 11f
            val by = 380f + (i / 20) * 16f
            canvas.drawLine(bx, by, bx + 2f, by + 4f, stubblePaint)
        }

        // 8. Subtle Golden Champion Star Accent (Argentina / World Cup Champion)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 215, 0)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("★ 10", size * 0.76f, 150f, starPaint)

        return bitmap
    }
}
