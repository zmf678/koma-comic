package com.arkreader.comic.engine

import java.util.Locale

object NaturalSort {

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val ARCHIVE_EXTS = setOf("zip", "cbz", "epub")

    fun isImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in IMAGE_EXTS && !name.startsWith(".")
    }

    fun isArchiveName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in ARCHIVE_EXTS && !name.startsWith(".")
    }

    fun isPdfName(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) == "pdf" && !name.startsWith(".")
    }

    fun compare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = ia
                while (ia < a.length && a[ia].isDigit()) ia++
                val startB = ib
                while (ib < b.length && b[ib].isDigit()) ib++

                val numA = a.substring(startA, ia).trimStart('0')
                val numB = b.substring(startB, ib).trimStart('0')
                if (numA.length != numB.length) return numA.length.compareTo(numB.length)
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
                // Equal numeric value: shorter leading-zero string first
                val lenCmp = (ia - startA).compareTo(ib - startB)
                if (lenCmp != 0) return lenCmp
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                ia++
                ib++
            }
        }
        return a.length.compareTo(b.length)
    }

    // Minimal common Traditional -> Simplified character fold for comic title search
    private val T2S_MAP = mapOf(
        '書' to '书', '畫' to '画', '漫' to '漫', '話' to '话', '圖' to '图',
        '國' to '国', '語' to '语', '學' to '学', '園' to '园', '愛' to '爱',
        '戀' to '恋', '劍' to '剑', '龍' to '龙', '鬼' to '鬼', '神' to '神',
        '戰' to '战', '鬥' to '斗', '魔' to '魔', '傳' to '传', '說' to '说',
        '滅' to '灭', '殺' to '杀', '災' to '灾', '靈' to '灵', '聖' to '圣',
        '記' to '记', '錄' to '录', '異' to '异', '世' to '世', '界' to '界',
        '轉' to '转', '生' to '生', '東' to '东', '京' to '京', '進' to '进',
        '擊' to '击', '巨' to '巨', '人' to '人', '海' to '海', '賊' to '贼',
        '銀' to '银', '魂' to '魂', '獵' to '猎', '鋼' to '钢', '鍊' to '炼',
        '術' to '术', '師' to '师', '呪' to '咒', '迴' to '回', '響' to '响',
        '喬' to '乔', '福' to '福', '音' to '音', '冒' to '冒', '險' to '险',
        '第' to '第', '卷' to '卷', '章' to '章', '集' to '集', '篇' to '篇',
        '外' to '外', '番' to '番', '終' to '终', '結' to '结', '無' to '无',
        '雙' to '双', '葉' to '叶', '風' to '风', '雲' to '云', '電' to '电',
        '車' to '车', '門' to '门', '開' to '开', '關' to '关', '間' to '间',
        '時' to '时', '空' to '空', '星' to '星', '月' to '月', '陽' to '阳',
        '櫻' to '樱', '花' to '花', '夢' to '梦', '幻' to '幻', '影' to '影',
        '黑' to '黑', '白' to '白', '紅' to '红', '藍' to '蓝', '綠' to '绿',
        '黃' to '黄', '紫' to '紫', '金' to '金', '鐵' to '铁', '島' to '岛',
        '館' to '馆', '樓' to '楼', '城' to '城', '鎮' to '镇', '村' to '村',
        '長' to '长', '短' to '短', '高' to '高', '低' to '低', '新' to '新',
        '舊' to '旧', '真' to '真', '偽' to '伪', '與' to '与', '為' to '为',
        '從' to '从', '來' to '来', '去' to '去', '歸' to '归', '見' to '见',
        '聞' to '闻', '聽' to '听', '看' to '看', '讀' to '读', '寫' to '写',
        '體' to '体', '會' to '会', '個' to '个', '這' to '这', '還' to '还'
    )

    fun normalizeForSearch(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(T2S_MAP[ch] ?: ch.lowercaseChar())
        }
        return sb.toString()
    }

    fun matchesSearch(title: String, query: String): Boolean {
        if (query.isBlank()) return true
        return normalizeForSearch(title).contains(normalizeForSearch(query.trim()))
    }
}
