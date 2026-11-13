package com.arkreader.comic

import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.DoublePageMode
import com.arkreader.comic.data.ReaderConfig
import com.arkreader.comic.engine.NaturalSort
import com.arkreader.comic.engine.PageSlot
import com.arkreader.comic.engine.SpreadSlotBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEngineTest {

    @Test
    fun naturalSortOrdersNumericTokensProperly() {
        val input = listOf("第10话", "第2话", "第1话", "第20话")
        val sorted = input.sortedWith(NaturalSort::compare)
        assertEquals(listOf("第1话", "第2话", "第10话", "第20话"), sorted)
    }

    @Test
    fun naturalSortHandlesMixedPrefixesAndPadding() {
        val input = listOf("Vol.10 Ch.2", "Vol.2 Ch.10", "Vol.2 Ch.2")
        val sorted = input.sortedWith(NaturalSort::compare)
        assertEquals(listOf("Vol.2 Ch.2", "Vol.2 Ch.10", "Vol.10 Ch.2"), sorted)
    }

    @Test
    fun searchMatchesCaseInsensitiveAndTraditionalSimplifiedFolding() {
        assertTrue(NaturalSort.matchesSearch("JOJO的奇妙冒险", "jojo"))
        assertTrue(NaturalSort.matchesSearch("乔乔福音 01", "喬喬福音"))
        assertTrue(NaturalSort.matchesSearch("鬼滅之刃 第01話", "鬼灭"))
        assertFalse(NaturalSort.matchesSearch("乔乔福音 01", "海贼王"))
    }

    @Test
    fun pinHashIsDeterministicAndDistinct() {
        val h1 = DatabaseHelper.hashPin("1234")
        val h2 = DatabaseHelper.hashPin("1234")
        val h3 = DatabaseHelper.hashPin("4321")
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
        assertEquals(64, h1.length)
    }

    @Test
    fun defaultReaderConfigUsesAutoLandscapeSingleToDouble() {
        val cfg = ReaderConfig()
        assertEquals(1L, cfg.id)
        assertEquals(DoublePageMode.AUTO_LANDSCAPE, cfg.doublePageMode)
        assertTrue(cfg.clickFlipEnabled)
        assertTrue(cfg.keepScreenOn)
        assertTrue(cfg.firstPageSingle)
    }

    @Test
    fun autoLandscapeSwitchesBetweenPortraitSingleAndLandscapeDouble() {
        val portraitMode = SpreadSlotBuilder.resolveEffectiveMode(DoublePageMode.AUTO_LANDSCAPE, isLandscape = false)
        val landscapeMode = SpreadSlotBuilder.resolveEffectiveMode(DoublePageMode.AUTO_LANDSCAPE, isLandscape = true)
        assertEquals(DoublePageMode.NORMAL, portraitMode)
        assertEquals(DoublePageMode.MERGE, landscapeMode)

        val portraitSlots = SpreadSlotBuilder.buildSlots(5, portraitMode, firstPageSingle = true, mergeRightToLeft = false)
        assertEquals(5, portraitSlots.size)
        assertTrue(portraitSlots.all { it is PageSlot.Single })

        val landscapeSlots = SpreadSlotBuilder.buildSlots(5, landscapeMode, firstPageSingle = true, mergeRightToLeft = false)
        assertEquals(
            listOf(PageSlot.Single(0), PageSlot.Merged(1, 2), PageSlot.Merged(3, 4)),
            landscapeSlots
        )
    }

    @Test
    fun toggleSpreadMatchShiftsParityAndPreservesWideSpreads() {
        val shifted = SpreadSlotBuilder.buildSlots(5, DoublePageMode.MERGE, firstPageSingle = false, mergeRightToLeft = false)
        assertEquals(
            listOf(PageSlot.Merged(0, 1), PageSlot.Merged(2, 3), PageSlot.Single(4)),
            shifted
        )

        // Page 2 is already a wide 2-page spread -> kept single, subsequent pages re-pair cleanly
        val withWide = SpreadSlotBuilder.buildSlots(
            pageCount = 6,
            effectiveMode = DoublePageMode.MERGE,
            firstPageSingle = true,
            mergeRightToLeft = false,
            isWidePage = { it == 2 }
        )
        assertEquals(
            listOf(PageSlot.Single(0), PageSlot.Single(1), PageSlot.Single(2), PageSlot.Single(3), PageSlot.Merged(4, 5)),
            withWide
        )
    }
}
