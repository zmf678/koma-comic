package com.arkreader.comic.engine

import com.arkreader.comic.data.DoublePageMode

sealed class PageSlot {
    data class Single(val rawIndex: Int) : PageSlot()
    data class SplitHalf(val rawIndex: Int, val leftHalf: Boolean) : PageSlot()
    data class Merged(val leftRaw: Int, val rightRaw: Int?) : PageSlot()
}

object SpreadSlotBuilder {

    fun resolveEffectiveMode(configured: DoublePageMode, isLandscape: Boolean): DoublePageMode {
        return when (configured) {
            DoublePageMode.AUTO_LANDSCAPE -> if (isLandscape) DoublePageMode.MERGE else DoublePageMode.NORMAL
            else -> configured
        }
    }

    fun buildSlots(
        pageCount: Int,
        effectiveMode: DoublePageMode,
        firstPageSingle: Boolean,
        mergeRightToLeft: Boolean,
        isWidePage: (Int) -> Boolean = { false },
        splitAllPages: Boolean = false
    ): List<PageSlot> {
        if (pageCount <= 0) return emptyList()
        return when (effectiveMode) {
            DoublePageMode.NORMAL, DoublePageMode.AUTO_LANDSCAPE -> {
                (0 until pageCount).map { PageSlot.Single(it) }
            }
            DoublePageMode.SPLIT -> buildList {
                for (i in 0 until pageCount) {
                    val shouldSplit = splitAllPages || isWidePage(i)
                    if (!shouldSplit || (firstPageSingle && i == 0)) {
                        add(PageSlot.Single(i))
                    } else if (mergeRightToLeft) {
                        add(PageSlot.SplitHalf(i, leftHalf = false))
                        add(PageSlot.SplitHalf(i, leftHalf = true))
                    } else {
                        add(PageSlot.SplitHalf(i, leftHalf = true))
                        add(PageSlot.SplitHalf(i, leftHalf = false))
                    }
                }
            }
            DoublePageMode.MERGE -> buildList {
                var i = 0
                var startOfRun = true
                while (i < pageCount) {
                    if (isWidePage(i)) {
                        add(PageSlot.Single(i))
                        i++
                        startOfRun = true
                        continue
                    }
                    if (startOfRun && firstPageSingle) {
                        add(PageSlot.Single(i))
                        i++
                        startOfRun = false
                        continue
                    }
                    startOfRun = false
                    if (i + 1 < pageCount && !isWidePage(i + 1)) {
                        add(PageSlot.Merged(i, i + 1))
                        i += 2
                    } else {
                        add(PageSlot.Single(i))
                        i++
                    }
                }
            }
        }
    }

    fun findSlotForRawPage(slots: List<PageSlot>, rawPage: Int): Int {
        if (slots.isEmpty()) return 0
        val exact = slots.indexOfFirst {
            when (it) {
                is PageSlot.Single -> it.rawIndex == rawPage
                is PageSlot.SplitHalf -> it.rawIndex == rawPage
                is PageSlot.Merged -> it.leftRaw == rawPage || it.rightRaw == rawPage
            }
        }
        if (exact >= 0) return exact
        return slots.indexOfFirst {
            when (it) {
                is PageSlot.Single -> it.rawIndex >= rawPage
                is PageSlot.SplitHalf -> it.rawIndex >= rawPage
                is PageSlot.Merged -> it.leftRaw >= rawPage || (it.rightRaw ?: -1) >= rawPage
            }
        }.let { if (it < 0) slots.lastIndex else it }
    }
}
