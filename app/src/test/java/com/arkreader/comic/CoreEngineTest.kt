package com.arkreader.comic

import com.arkreader.comic.data.DatabaseHelper
import com.arkreader.comic.data.DoublePageMode
import com.arkreader.comic.data.ReaderConfig
import com.arkreader.comic.data.readingDays
import com.arkreader.comic.engine.MangaTranslation
import com.arkreader.comic.engine.NaturalSort
import com.arkreader.comic.engine.PageSlot
import com.arkreader.comic.engine.SourceRepoClient
import com.arkreader.comic.engine.SpreadSlotBuilder
import com.arkreader.comic.engine.VeneraScriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId

class CoreEngineTest {

    @Test
    fun readingTimeCrossingMidnightCountsOnBothDates() {
        val zone = ZoneId.of("Asia/Hong_Kong")
        val start = Instant.parse("2026-11-22T15:59:58Z")
        assertEquals(listOf("2026-11-22" to 2_000L, "2026-11-23" to 4_000L), readingDays(start, 6_000L, zone))
    }

    @Test
    fun translationStreamSkipsProgressAndReadsOnlyTheTranslatedFrame() {
        val frames = byteArrayOf(1, 0, 0, 0, 2, 24, 42, 0, 0, 0, 0, 3, 5, 6, 7)
        assertArrayEquals(byteArrayOf(5, 6, 7), MangaTranslation.readTranslatedFrame(ByteArrayInputStream(frames)))
        assertThrows(IllegalStateException::class.java) {
            MangaTranslation.readTranslatedFrame(ByteArrayInputStream(byteArrayOf(2, 0, 0, 0, 1, 5)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MangaTranslation.readTranslatedFrame(ByteArrayInputStream(byteArrayOf(0, -1, -1, -1, -1)))
        }
    }

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

    @Test
    fun veneraScriptMetaAndUriParsingWork() {
        val sampleJs = """
            class Zaimanhua extends ComicSource {
                name = "再漫画"
                key = "zaimanhua"
                version = "1.0.4"
                url = "https://cdn.example.com/zaimanhua.js"
            }
        """.trimIndent()
        val meta = VeneraScriptParser.parseScriptMeta(sampleJs)
        assertNotNull(meta)
        assertEquals("Zaimanhua", meta!!.className)
        assertEquals("zaimanhua", meta.key)
        assertEquals("再漫画", meta.name)
        assertEquals("1.0.4", meta.version)

        val parsed = VeneraScriptParser.parseVeneraUri("venera://zaimanhua/41672/197204")
        assertNotNull(parsed)
        assertEquals("zaimanhua", parsed!!.first)
        assertEquals("41672", parsed.second)
        assertEquals("197204", parsed.third)
    }

    @Test
    fun veneraRepoIndexAndNestedChaptersMapParseCleanly() {
        val indexJson = """
            [{"name":"包子漫画","fileName":"baozi.js","key":"baozi","version":"1.2.3"}]
        """.trimIndent()
        val repoItems = VeneraScriptParser.parseRepoIndexJson(
            indexJson,
            "https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/index.json"
        )
        assertEquals(1, repoItems.size)
        assertEquals("baozi", repoItems[0].key)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/baozi.js",
            repoItems[0].url
        )

        val detailsJson = """
            {
              "title": "测试漫画",
              "subTitle": "作者A",
              "cover": "https://img.example.com/1.jpg",
              "chapters": {
                "__mapEntries__": [
                  ["连载", {"__mapEntries__": [["ep1", "第1话"], ["ep2", "第2话"]]}],
                  ["单行本", {"__mapEntries__": [["vol1", "第1卷"]]}]
                ]
              }
            }
        """.trimIndent()
        val details = VeneraScriptParser.parseComicDetailsResult("zaimanhua", "123", detailsJson)
        assertEquals("测试漫画", details.title)
        assertEquals(3, details.chapters.size)
        assertEquals("ep1", details.chapters[0].id)
        assertEquals("连载 - 第1话", details.chapters[0].title)
        assertEquals("vol1", details.chapters[2].id)
    }

    @Test
    fun veneraObjectMetaMultipartAndRepoUrlResolutionWork() {
        val objectConfigScript = """
            const config = {
                "name": "测试站点",
                "key": "test_site",
                "version": "1.2.3",
                "url": "https://cdn.jsdelivr.net/gh/zmf678/venera-sources@main/test_site.js"
            };
            class TestSource extends ComicSource {}
        """.trimIndent()
        val meta = VeneraScriptParser.parseScriptMeta(objectConfigScript)
        assertNotNull(meta)
        assertEquals("test_site", meta!!.key)
        assertEquals("测试站点", meta.name)
        assertEquals("1.2.3", meta.version)

        val multiPartJson = """
            {
              "headers": {
                "Referer": "https://img.test.example/"
              },
              "comics": {
                "热门推荐": [
                  {
                    "id": "c100",
                    "title": "漫画A",
                    "subtitle": "作者甲",
                    "cover": "https://img.test.example/a.jpg",
                    "tags": ["热血", "连载"]
                  }
                ],
                "最新更新": [
                  {
                    "id": "c200",
                    "title": "漫画B",
                    "author": "作者乙",
                    "cover": "https://img.test.example/b.jpg"
                  }
                ]
              },
              "maxPage": 5
            }
        """.trimIndent()
        val (comics, maxPage) = VeneraScriptParser.parseComicsListResult("test_site", multiPartJson)
        assertEquals(2, comics.size)
        assertEquals(5, maxPage)
        val byId = comics.associateBy { it.id }
        val c100 = byId["c100"]
        val c200 = byId["c200"]
        assertNotNull(c100)
        assertNotNull(c200)
        assertEquals("https://img.test.example/", c100!!.headers["Referer"])
        assertEquals("作者甲", c100.subTitle)
        assertEquals(listOf("热血", "连载"), c100.tags)
        assertEquals("作者乙", c200!!.subTitle)

        val indexCandidates = SourceRepoClient.resolveIndexCandidateUrls("https://github.com/zmf678/venera-sources")
        assertTrue(indexCandidates.any { it == "https://cdn.jsdelivr.net/gh/zmf678/venera-sources@main/index.json" })
        assertTrue(indexCandidates.any { it == "https://raw.githubusercontent.com/zmf678/venera-sources/main/index.json" })

        val scriptCandidates = SourceRepoClient.resolveScriptCandidateUrls(
            "https://github.com/zmf678/venera-sources/blob/main/copy_manga.js"
        )
        assertTrue(scriptCandidates.any { it == "https://cdn.jsdelivr.net/gh/zmf678/venera-sources@main/copy_manga.js" })
        assertTrue(scriptCandidates.any { it == "https://raw.githubusercontent.com/zmf678/venera-sources/main/copy_manga.js" })
    }
}
