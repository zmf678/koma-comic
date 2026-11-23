package com.arkreader.comic.engine

internal class LightBalloonFinder(private val width: Int, private val height: Int, private val pixels: IntArray) {
    data class Area(val x: Int, val y: Int, val width: Int, val height: Int)
    data class Balloon(val id: Int, val bounds: Area, val textArea: Area, val safe: BooleanArray, val background: Int)

    private class Component(width: Int, height: Int) {
        var count = 0
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var red = 0
        var green = 0
        var blue = 0
    }

    private val labels = IntArray(pixels.size)
    private val queue = IntArray(pixels.size)
    private val passable = BooleanArray(pixels.size)
    private val components = arrayListOf<Component?>(null)

    init {
        val light = BooleanArray(pixels.size) { brightness(pixels[it]) >= 232 }
        // Leave a dark wall even where the original outline has a tiny gap.
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val p = y * width + x
                passable[p] = light[p] && light[p - 1] && light[p + 1] && light[p - width] && light[p + width]
            }
        }
    }

    fun find(left: Int, top: Int, right: Int, bottom: Int): Balloon? {
        val scores = mutableMapOf<Int, Int>()
        for (row in 0..8) {
            for (column in 0..8) {
                val x = ((left + (right - left) * (column + 0.5) / 9) * width / 1000).toInt().coerceAtMost(width - 1)
                val y = ((top + (bottom - top) * (row + 0.5) / 9) * height / 1000).toInt().coerceAtMost(height - 1)
                val p = y * width + x
                if (!passable[p]) continue
                val id = if (labels[p] == 0) label(p) else labels[p]
                scores[id] = (scores[id] ?: 0) + if (row in 2..6 && column in 2..6) 3 else 1
            }
        }
        var chosen = 0
        var best = 13
        for ((id, votes) in scores) {
            val component = components[id]!!
            val w = component.maxX - component.minX + 1
            val h = component.maxY - component.minY + 1
            if (votes <= best || w < 12 || h < 12 || w > width * 0.9 || h > height * 0.92 ||
                w * h > pixels.size * 0.62 || component.count < w * h * 0.37) continue
            best = votes
            chosen = id
        }
        return if (chosen == 0) null else balloon(chosen)
    }

    private fun label(start: Int): Int {
        val id = components.size
        val component = Component(width, height)
        components.add(component)
        var end = 0
        fun enqueue(p: Int) {
            if (!passable[p] || labels[p] != 0) return
            labels[p] = id
            queue[end++] = p
        }
        enqueue(start)
        var head = 0
        while (head < end) {
            val p = queue[head++]
            val x = p % width
            val y = p / width
            component.minX = minOf(x, component.minX)
            component.maxX = maxOf(x, component.maxX)
            component.minY = minOf(y, component.minY)
            component.maxY = maxOf(y, component.maxY)
            val color = pixels[p]
            component.red += color ushr 16 and 255
            component.green += color ushr 8 and 255
            component.blue += color and 255
            enqueue(p - 1)
            enqueue(p + 1)
            enqueue(p - width)
            enqueue(p + width)
        }
        component.count = end
        return id
    }

    private fun balloon(id: Int): Balloon? {
        val component = components[id]!!
        val w = component.maxX - component.minX + 1
        val h = component.maxY - component.minY + 1
        val stride = w + 2
        val rows = h + 2
        val distance = IntArray(stride * rows)
        val solid = BooleanArray(distance.size)
        for (y in 0 until h) for (x in 0 until w) {
            solid[(y + 1) * stride + x + 1] = labels[(component.minY + y) * width + component.minX + x] == id
        }

        // Flood from the outside so interior ink is included in the mask while the outline stays outside.
        val scan = IntArray(distance.size)
        var end = 1
        distance[0] = -1
        fun outside(p: Int) {
            if (solid[p] || distance[p] != 0) return
            distance[p] = -1
            scan[end++] = p
        }
        var head = 0
        while (head < end) {
            val p = scan[head++]
            val x = p % stride
            val y = p / stride
            if (x > 0) outside(p - 1)
            if (x < stride - 1) outside(p + 1)
            if (y > 0) outside(p - stride)
            if (y < rows - 1) outside(p + stride)
        }
        for (i in distance.indices) distance[i] = if (distance[i] == -1) 0 else w + h + 1
        for (y in 1..h) for (x in 1..w) {
            val p = y * stride + x
            distance[p] = minOf(distance[p], 1 + minOf(distance[p - 1], distance[p - stride]))
        }
        for (y in h downTo 1) for (x in w downTo 1) {
            val p = y * stride + x
            distance[p] = minOf(distance[p], 1 + minOf(distance[p + 1], distance[p + stride]))
        }

        val safe = BooleanArray(w * h)
        val inset = maxOf(2, minOf(w, h) / 55)
        var ink = 0
        var interior = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (distance[(y + 1) * stride + x + 1] <= inset) continue
            safe[y * w + x] = true
            interior++
            if (brightness(pixels[(component.minY + y) * width + component.minX + x]) < 213) ink++
        }
        if (ink < 6 || ink > interior * 0.30) return null
        val area = largestRectangle(safe, w, h)
        if (area.width < 9 || area.height < 9) return null
        val count = component.count
        val background = 0xff000000.toInt() or ((component.red + count / 2) / count shl 16) or
            ((component.green + count / 2) / count shl 8) or ((component.blue + count / 2) / count)
        return Balloon(id, Area(component.minX, component.minY, w, h),
            area.copy(x = area.x + component.minX, y = area.y + component.minY), safe, background)
    }

    private fun largestRectangle(mask: BooleanArray, w: Int, h: Int): Area {
        var result = Area(0, 0, 0, 0)
        var largest = 0
        val heights = IntArray(w + 1)
        val stack = IntArray(w + 1)
        for (y in 0 until h) {
            var size = 0
            for (x in 0..w) {
                heights[x] = if (x == w || !mask[y * w + x]) 0 else heights[x] + 1
                while (size > 0 && heights[stack[size - 1]] >= heights[x]) {
                    val tall = heights[stack[--size]]
                    val left = if (size == 0) 0 else stack[size - 1] + 1
                    val area = tall * (x - left)
                    if (area <= largest) continue
                    largest = area
                    result = Area(left, y - tall + 1, x - left, tall)
                }
                stack[size++] = x
            }
        }
        return result
    }

    companion object {
        fun brightness(pixel: Int) = minOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255)
    }
}
