package com.arkreader.comic.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.arkreader.comic.engine.NaturalSort
import org.json.JSONArray
import java.security.MessageDigest

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createOnlineMetadataTable(db)
        createReadingTimeTable(db)
        db.execSQL("""
            CREATE TABLE warehouses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                uri_or_url TEXT NOT NULL UNIQUE,
                type TEXT NOT NULL,
                cover_name TEXT NOT NULL DEFAULT 'cover',
                hide_in_gallery INTEGER NOT NULL DEFAULT 0,
                default_bookshelf_id INTEGER NOT NULL DEFAULT 0,
                webdav_user TEXT NOT NULL DEFAULT '',
                webdav_pass TEXT NOT NULL DEFAULT '',
                last_scanned_at INTEGER NOT NULL DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE comics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                warehouse_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                source_uri TEXT NOT NULL UNIQUE,
                format TEXT NOT NULL,
                cover_cache_key TEXT NOT NULL DEFAULT '',
                total_chapters INTEGER NOT NULL DEFAULT 0,
                last_read_chapter_id INTEGER NOT NULL DEFAULT 0,
                last_read_page INTEGER NOT NULL DEFAULT 0,
                last_read_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                reader_config_id INTEGER NOT NULL DEFAULT 1
            )
        """)
        db.execSQL("CREATE INDEX idx_comics_wh ON comics(warehouse_id)")

        db.execSQL("""
            CREATE TABLE chapters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                comic_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                source_uri TEXT NOT NULL,
                format TEXT NOT NULL,
                entry_prefix TEXT NOT NULL DEFAULT '',
                page_count INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                last_read_page INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX idx_chapters_comic ON chapters(comic_id)")

        db.execSQL("""
            CREATE TABLE bookshelves (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                sort_order INTEGER NOT NULL DEFAULT 0,
                is_hidden_from_all INTEGER NOT NULL DEFAULT 0,
                lock_type TEXT NOT NULL DEFAULT 'NONE',
                pin_hash TEXT NOT NULL DEFAULT '',
                quick_relock INTEGER NOT NULL DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE bookshelf_comics (
                bookshelf_id INTEGER NOT NULL,
                comic_id INTEGER NOT NULL,
                added_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (bookshelf_id, comic_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                comic_id INTEGER NOT NULL,
                chapter_id INTEGER NOT NULL,
                chapter_title TEXT NOT NULL,
                page_index INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE reader_configs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                scroll_mode TEXT NOT NULL DEFAULT 'VERTICAL',
                double_page_mode TEXT NOT NULL DEFAULT 'AUTO_LANDSCAPE',
                first_page_single INTEGER NOT NULL DEFAULT 1,
                merge_rtl INTEGER NOT NULL DEFAULT 0,
                trim_margins INTEGER NOT NULL DEFAULT 0,
                trim_strength INTEGER NOT NULL DEFAULT 18,
                trim_ignore_sides INTEGER NOT NULL DEFAULT 1,
                trim_protect INTEGER NOT NULL DEFAULT 1,
                skip_blank_pages INTEGER NOT NULL DEFAULT 0,
                click_flip_enabled INTEGER NOT NULL DEFAULT 1,
                click_flip_mode TEXT NOT NULL DEFAULT 'LEFT_RIGHT',
                swap_click_flip INTEGER NOT NULL DEFAULT 0,
                volume_key_flip INTEGER NOT NULL DEFAULT 1,
                keep_screen_on INTEGER NOT NULL DEFAULT 1,
                show_page_number INTEGER NOT NULL DEFAULT 1,
                show_toolbar_on_open INTEGER NOT NULL DEFAULT 0,
                orientation TEXT NOT NULL DEFAULT 'SYSTEM',
                side_margin_dp INTEGER NOT NULL DEFAULT 0,
                bg_color TEXT NOT NULL DEFAULT 'BLACK',
                pdf_scale REAL NOT NULL DEFAULT 2.0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS comic_sources (
                source_key TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version TEXT NOT NULL DEFAULT '1.0.0',
                url TEXT NOT NULL DEFAULT '',
                script TEXT NOT NULL,
                settings_json TEXT NOT NULL DEFAULT '{}',
                enabled INTEGER NOT NULL DEFAULT 1,
                built_in INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // Insert default collection & default reader config
        val shelfCv = ContentValues().apply {
            put("name", "默认作品集")
            put("sort_order", 0)
        }
        db.insert("bookshelves", null, shelfCv)

        val cfgCv = ContentValues().apply {
            put("id", 1L)
            put("name", "默认配置")
        }
        db.insert("reader_configs", null, cfgCv)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("UPDATE reader_configs SET double_page_mode = 'AUTO_LANDSCAPE' WHERE id = 1 AND double_page_mode = 'NORMAL'")
        }
        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS comic_sources (
                    source_key TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    version TEXT NOT NULL DEFAULT '1.0.0',
                    url TEXT NOT NULL DEFAULT '',
                    script TEXT NOT NULL,
                    settings_json TEXT NOT NULL DEFAULT '{}',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    built_in INTEGER NOT NULL DEFAULT 0,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("UPDATE bookshelves SET name = '默认作品集' WHERE id = 1 AND name = '默认书架'")
        }
        if (oldVersion < 4) {
            createOnlineMetadataTable(db)
            db.execSQL("UPDATE comics SET is_favorite = 1 WHERE format = 'ONLINE' AND id IN (SELECT comic_id FROM bookshelf_comics)")
        }
        if (oldVersion < 5) createReadingTimeTable(db)
        if (oldVersion in 4..5) db.execSQL("ALTER TABLE online_metadata ADD COLUMN tags_json TEXT NOT NULL DEFAULT '[]'")
    }

    private fun createReadingTimeTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS reading_time (
                day TEXT NOT NULL,
                comic_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                milliseconds INTEGER NOT NULL,
                PRIMARY KEY (day, comic_id)
            )
        """.trimIndent())
    }

    fun addReadingTime(comicId: Long, title: String, start: java.time.Instant, elapsed: Long) {
        val portions = readingDays(start, elapsed, java.time.ZoneId.systemDefault())
        if (portions.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((day, millis) in portions) {
                db.execSQL("INSERT OR IGNORE INTO reading_time VALUES (?, ?, ?, 0)", arrayOf(day, comicId, title))
                db.execSQL("UPDATE reading_time SET milliseconds = milliseconds + ?, title = ? WHERE day = ? AND comic_id = ?",
                    arrayOf(millis, title, day, comicId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readingTime(): List<ReadingTime> {
        val privateIds = getHiddenOrLockedComicIds(emptySet())
        return readableDatabase.rawQuery("SELECT day, comic_id, title, milliseconds FROM reading_time ORDER BY day DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val comicId = cursor.getLong(1)
                    if (comicId in privateIds) add(ReadingTime(cursor.getString(0), 0L, "私人作品", cursor.getLong(3)))
                    else add(ReadingTime(cursor.getString(0), comicId, cursor.getString(2), cursor.getLong(3)))
                }
            }
        }
    }

    private fun createOnlineMetadataTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS online_metadata (
                comic_id INTEGER PRIMARY KEY,
                description TEXT NOT NULL DEFAULT '',
                official_url TEXT NOT NULL DEFAULT '',
                tags_json TEXT NOT NULL DEFAULT '[]'
            )
        """.trimIndent())
    }

    fun saveOnlineMetadata(comicId: Long, description: String, url: String = "", tags: List<String> = emptyList(), onlyMissing: Boolean = false) {
        if (description.isBlank() && url.isBlank() && tags.isEmpty()) return
        val database = writableDatabase
        database.beginTransaction()
        try {
            val previous = if (onlyMissing) getOnlineMetadata(comicId) else OnlineComicMetadata()
            val values = ContentValues().apply {
                if (description.isNotBlank() && previous.description.isBlank()) put("description", description)
                if (url.isNotBlank() && previous.url.isBlank()) put("official_url", url)
                if (tags.isNotEmpty() && previous.tags.isEmpty()) put("tags_json", JSONArray(tags).toString())
            }
            if (values.size() > 0 && database.update("online_metadata", values, "comic_id = ?", arrayOf(comicId.toString())) == 0) {
                values.put("comic_id", comicId)
                database.insertOrThrow("online_metadata", null, values)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun getOnlineMetadata(comicId: Long): OnlineComicMetadata =
        readableDatabase.rawQuery(
            "SELECT description, official_url, tags_json FROM online_metadata WHERE comic_id = ?",
            arrayOf(comicId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use OnlineComicMetadata()
            val tags = runCatching {
                val array = JSONArray(cursor.getString(2))
                List(array.length()) { array.getString(it) }
            }.getOrDefault(emptyList())
            OnlineComicMetadata(cursor.getString(0), cursor.getString(1), tags)
        }


    // --- Warehouses ---

    fun getWarehouses(): List<Warehouse> {
        val list = mutableListOf<Warehouse>()
        readableDatabase.rawQuery("SELECT * FROM warehouses ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) list.add(c.toWarehouse())
        }
        return list
    }

    fun getWarehouse(id: Long): Warehouse? {
        readableDatabase.rawQuery("SELECT * FROM warehouses WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) c.toWarehouse() else null
        }
    }

    fun upsertWarehouse(w: Warehouse): Long {
        val cv = ContentValues().apply {
            put("name", w.name)
            put("uri_or_url", w.uriOrUrl)
            put("type", w.type.name)
            put("cover_name", w.coverName)
            put("hide_in_gallery", if (w.hideInGallery) 1 else 0)
            put("default_bookshelf_id", w.defaultBookshelfId)
            put("webdav_user", w.webdavUser)
            put("webdav_pass", w.webdavPass)
            put("last_scanned_at", w.lastScannedAt)
        }
        return if (w.id > 0) {
            writableDatabase.update("warehouses", cv, "id = ?", arrayOf(w.id.toString()))
            w.id
        } else {
            val existingId = getWarehouseIdByUri(w.uriOrUrl)
            if (existingId > 0) {
                writableDatabase.update("warehouses", cv, "id = ?", arrayOf(existingId.toString()))
                existingId
            } else {
                writableDatabase.insert("warehouses", null, cv)
            }
        }
    }

    private fun getWarehouseIdByUri(uri: String): Long {
        readableDatabase.rawQuery("SELECT id FROM warehouses WHERE uri_or_url = ?", arrayOf(uri)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun deleteWarehouse(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL(
                "DELETE FROM chapters WHERE comic_id IN (SELECT id FROM comics WHERE warehouse_id = ?)",
                arrayOf(id.toString())
            )
            writableDatabase.execSQL(
                "DELETE FROM bookshelf_comics WHERE comic_id IN (SELECT id FROM comics WHERE warehouse_id = ?)",
                arrayOf(id.toString())
            )
            writableDatabase.execSQL(
                "DELETE FROM bookmarks WHERE comic_id IN (SELECT id FROM comics WHERE warehouse_id = ?)",
                arrayOf(id.toString())
            )
            writableDatabase.delete("comics", "warehouse_id = ?", arrayOf(id.toString()))
            writableDatabase.delete("warehouses", "id = ?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // --- Comics & Chapters ---

    fun getComicsForHome(
        tabMode: Int, // 0 = All, 1 = Favorite, 2 = History
        bookshelfId: Long, // >0 filters to specific shelf
        unlockedShelfIds: Set<Long>,
        query: String,
        sortType: SortType
    ): List<Comic> {
        val hiddenLockedIds = getHiddenOrLockedComicIds(unlockedShelfIds)
        val raw = mutableListOf<Comic>()
        val sql = when {
            bookshelfId > 0 -> """
                SELECT c.* FROM comics c
                INNER JOIN bookshelf_comics bc ON c.id = bc.comic_id
                WHERE bc.bookshelf_id = ? AND c.format <> 'ONLINE'
            """.trimIndent()
            tabMode == 4 -> "SELECT * FROM comics WHERE format <> 'ONLINE'"
            tabMode == 3 -> "SELECT * FROM comics WHERE is_favorite = 1 AND format = 'ONLINE'"
            tabMode == 1 -> "SELECT * FROM comics WHERE is_favorite = 1"
            tabMode == 2 -> "SELECT * FROM comics WHERE last_read_at > 0"
            else -> "SELECT * FROM comics WHERE format <> 'ONLINE' OR total_chapters > 0"
        }
        val args = if (bookshelfId > 0) arrayOf(bookshelfId.toString()) else null
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val comic = c.toComic()
                if (bookshelfId <= 0 && comic.id in hiddenLockedIds) continue
                if (query.isNotBlank() && !NaturalSort.matchesSearch(comic.title, query)) continue
                raw.add(comic)
            }
        }
        return when (sortType) {
            SortType.NAME_ASC -> raw.sortedWith { a, b -> NaturalSort.compare(a.title, b.title) }
            SortType.NAME_DESC -> raw.sortedWith { a, b -> NaturalSort.compare(b.title, a.title) }
            SortType.READ_DESC -> raw.sortedByDescending { it.lastReadAt }
            SortType.UPDATE_DESC -> raw.sortedByDescending { it.updatedAt }
            SortType.FAVORITE_DESC -> raw.sortedWith(
                compareByDescending<Comic> { it.isFavorite }
                    .thenComparator { a, b -> NaturalSort.compare(a.title, b.title) }
            )
        }
    }

    private fun getHiddenOrLockedComicIds(unlockedShelfIds: Set<Long>): Set<Long> {
        val hiddenShelfIds = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT id, is_hidden_from_all, lock_type FROM bookshelves",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isHidden = c.getInt(1) == 1
                val lockType = LockType.valueOf(c.getString(2))
                val isLocked = lockType != LockType.NONE && id !in unlockedShelfIds
                if (isHidden || isLocked) hiddenShelfIds.add(id)
            }
        }
        if (hiddenShelfIds.isEmpty()) return emptySet()
        val placeholders = hiddenShelfIds.joinToString(",") { "?" }
        val result = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT comic_id FROM bookshelf_comics WHERE bookshelf_id IN ($placeholders)",
            hiddenShelfIds.map { it.toString() }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) result.add(c.getLong(0))
        }
        return result
    }

    fun getComic(comicId: Long): Comic? {
        readableDatabase.rawQuery("SELECT * FROM comics WHERE id = ?", arrayOf(comicId.toString())).use { c ->
            return if (c.moveToFirst()) c.toComic() else null
        }
    }

    fun getMostRecentComic(): Comic? {
        readableDatabase.rawQuery(
            "SELECT * FROM comics WHERE last_read_at > 0 ORDER BY last_read_at DESC LIMIT 1",
            null
        ).use { c ->
            return if (c.moveToFirst()) c.toComic() else null
        }
    }

    fun setFavorite(comicId: Long, favorite: Boolean) {
        val cv = ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) }
        writableDatabase.update("comics", cv, "id = ?", arrayOf(comicId.toString()))
    }

    fun setComicReaderConfig(comicId: Long, configId: Long) {
        val cv = ContentValues().apply { put("reader_config_id", configId) }
        writableDatabase.update("comics", cv, "id = ?", arrayOf(comicId.toString()))
    }

    fun updateReadingProgress(comicId: Long, chapterId: Long, pageIndex: Int) {
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            val comicCv = ContentValues().apply {
                put("last_read_chapter_id", chapterId)
                put("last_read_page", pageIndex)
                put("last_read_at", now)
            }
            writableDatabase.update("comics", comicCv, "id = ?", arrayOf(comicId.toString()))

            val chapCv = ContentValues().apply { put("last_read_page", pageIndex) }
            writableDatabase.update("chapters", chapCv, "id = ?", arrayOf(chapterId.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun getChapters(comicId: Long): List<Chapter> {
        val list = mutableListOf<Chapter>()
        readableDatabase.rawQuery(
            "SELECT * FROM chapters WHERE comic_id = ? ORDER BY sort_order ASC, id ASC",
            arrayOf(comicId.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(c.toChapter())
        }
        return list
    }

    fun getChapter(chapterId: Long): Chapter? {
        readableDatabase.rawQuery("SELECT * FROM chapters WHERE id = ?", arrayOf(chapterId.toString())).use { c ->
            return if (c.moveToFirst()) c.toChapter() else null
        }
    }

    fun updateChapterPageCount(chapterId: Long, pageCount: Int) {
        val cv = ContentValues().apply { put("page_count", pageCount) }
        writableDatabase.update("chapters", cv, "id = ?", arrayOf(chapterId.toString()))
    }

    data class ScannedChapter(
        val title: String,
        val sourceUri: String,
        val format: ComicFormat,
        val entryPrefix: String = "",
        val pageCount: Int = 0
    )

    data class ScannedComic(
        val title: String,
        val sourceUri: String,
        val format: ComicFormat,
        val coverCacheKey: String,
        val chapters: List<ScannedChapter>
    )

    fun replaceWarehouseComics(warehouseId: Long, defaultBookshelfId: Long, scanned: List<ScannedComic>) {
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            val existingByUri = mutableMapOf<String, Comic>()
            readableDatabase.rawQuery(
                "SELECT * FROM comics WHERE warehouse_id = ?",
                arrayOf(warehouseId.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val comic = c.toComic()
                    existingByUri[comic.sourceUri] = comic
                }
            }

            val scannedUris = scanned.mapTo(HashSet()) { it.sourceUri }
            for ((uri, oldComic) in existingByUri) {
                if (uri !in scannedUris) {
                    writableDatabase.delete("chapters", "comic_id = ?", arrayOf(oldComic.id.toString()))
                    writableDatabase.delete("bookshelf_comics", "comic_id = ?", arrayOf(oldComic.id.toString()))
                    writableDatabase.delete("bookmarks", "comic_id = ?", arrayOf(oldComic.id.toString()))
                    writableDatabase.delete("comics", "id = ?", arrayOf(oldComic.id.toString()))
                }
            }

            for (item in scanned) {
                val old = existingByUri[item.sourceUri]
                val comicCv = ContentValues().apply {
                    put("warehouse_id", warehouseId)
                    put("title", item.title)
                    put("source_uri", item.sourceUri)
                    put("format", item.format.name)
                    put("cover_cache_key", item.coverCacheKey)
                    put("total_chapters", item.chapters.size)
                    put("updated_at", now)
                }
                val comicId = if (old != null) {
                    writableDatabase.update("comics", comicCv, "id = ?", arrayOf(old.id.toString()))
                    old.id
                } else {
                    val id = writableDatabase.insert("comics", null, comicCv)
                    if (defaultBookshelfId > 0 && id > 0) {
                        val bc = ContentValues().apply {
                            put("bookshelf_id", defaultBookshelfId)
                            put("comic_id", id)
                            put("added_at", now)
                        }
                        writableDatabase.insertWithOnConflict("bookshelf_comics", null, bc, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    id
                }

                // Preserve chapter last_read_page by title or sourceUri
                val oldChapters = mutableMapOf<String, Chapter>()
                readableDatabase.rawQuery(
                    "SELECT * FROM chapters WHERE comic_id = ?",
                    arrayOf(comicId.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        val ch = c.toChapter()
                        oldChapters[ch.sourceUri + "|" + ch.entryPrefix] = ch
                    }
                }
                writableDatabase.delete("chapters", "comic_id = ?", arrayOf(comicId.toString()))

                var restoredLastChapterId = 0L
                item.chapters.forEachIndexed { idx, ch ->
                    val prev = oldChapters[ch.sourceUri + "|" + ch.entryPrefix]
                    val chCv = ContentValues().apply {
                        put("comic_id", comicId)
                        put("title", ch.title)
                        put("source_uri", ch.sourceUri)
                        put("format", ch.format.name)
                        put("entry_prefix", ch.entryPrefix)
                        put("page_count", ch.pageCount)
                        put("sort_order", idx)
                        put("last_read_page", prev?.lastReadPage ?: 0)
                    }
                    val newChapId = writableDatabase.insert("chapters", null, chCv)
                    if (old != null && prev != null && old.lastReadChapterId == prev.id) {
                        restoredLastChapterId = newChapId
                    }
                }
                if (restoredLastChapterId > 0) {
                    val fix = ContentValues().apply { put("last_read_chapter_id", restoredLastChapterId) }
                    writableDatabase.update("comics", fix, "id = ?", arrayOf(comicId.toString()))
                }
            }

            val whUpdate = ContentValues().apply { put("last_scanned_at", now) }
            writableDatabase.update("warehouses", whUpdate, "id = ?", arrayOf(warehouseId.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // --- Bookshelves ---

    fun getBookshelves(): List<Bookshelf> {
        val list = mutableListOf<Bookshelf>()
        readableDatabase.rawQuery("SELECT * FROM bookshelves ORDER BY sort_order ASC, id ASC", null).use { c ->
            while (c.moveToNext()) list.add(c.toBookshelf())
        }
        return list
    }

    fun getBookshelf(id: Long): Bookshelf? {
        readableDatabase.rawQuery("SELECT * FROM bookshelves WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) c.toBookshelf() else null
        }
    }

    fun upsertBookshelf(shelf: Bookshelf): Long {
        val cv = ContentValues().apply {
            put("name", shelf.name)
            put("sort_order", shelf.sortOrder)
            put("is_hidden_from_all", if (shelf.isHiddenFromAll) 1 else 0)
            put("lock_type", shelf.lockType.name)
            put("pin_hash", shelf.pinHash)
            put("quick_relock", if (shelf.quickRelock) 1 else 0)
        }
        return if (shelf.id > 0) {
            writableDatabase.update("bookshelves", cv, "id = ?", arrayOf(shelf.id.toString()))
            shelf.id
        } else {
            writableDatabase.insert("bookshelves", null, cv)
        }
    }

    fun deleteBookshelf(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("bookshelf_comics", "bookshelf_id = ?", arrayOf(id.toString()))
            writableDatabase.delete("bookshelves", "id = ?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun getBookshelfIdsForComic(comicId: Long): Set<Long> {
        val set = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT bookshelf_id FROM bookshelf_comics WHERE comic_id = ?",
            arrayOf(comicId.toString())
        ).use { c ->
            while (c.moveToNext()) set.add(c.getLong(0))
        }
        return set
    }

    fun setComicBookshelves(comicId: Long, shelfIds: Set<Long>) {
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("bookshelf_comics", "comic_id = ?", arrayOf(comicId.toString()))
            for (sid in shelfIds) {
                val cv = ContentValues().apply {
                    put("bookshelf_id", sid)
                    put("comic_id", comicId)
                    put("added_at", now)
                }
                writableDatabase.insert("bookshelf_comics", null, cv)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // --- Bookmarks ---

    fun getBookmarks(comicId: Long): List<Bookmark> {
        val list = mutableListOf<Bookmark>()
        readableDatabase.rawQuery(
            "SELECT * FROM bookmarks WHERE comic_id = ? ORDER BY created_at DESC",
            arrayOf(comicId.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(c.toBookmark())
        }
        return list
    }

    fun addBookmark(b: Bookmark): Long {
        val cv = ContentValues().apply {
            put("comic_id", b.comicId)
            put("chapter_id", b.chapterId)
            put("chapter_title", b.chapterTitle)
            put("page_index", b.pageIndex)
            put("note", b.note)
            put("created_at", b.createdAt)
        }
        return writableDatabase.insert("bookmarks", null, cv)
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    // --- Reader Configs ---

    fun getReaderConfigs(): List<ReaderConfig> {
        val list = mutableListOf<ReaderConfig>()
        readableDatabase.rawQuery("SELECT * FROM reader_configs ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) list.add(c.toReaderConfig())
        }
        return list
    }

    fun getReaderConfig(id: Long): ReaderConfig {
        readableDatabase.rawQuery("SELECT * FROM reader_configs WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return c.toReaderConfig()
        }
        readableDatabase.rawQuery("SELECT * FROM reader_configs ORDER BY id ASC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) return c.toReaderConfig()
        }
        return ReaderConfig()
    }

    fun upsertReaderConfig(cfg: ReaderConfig): Long {
        val cv = ContentValues().apply {
            put("name", cfg.name)
            put("scroll_mode", cfg.scrollMode.name)
            put("double_page_mode", cfg.doublePageMode.name)
            put("first_page_single", if (cfg.firstPageSingle) 1 else 0)
            put("merge_rtl", if (cfg.mergeRightToLeft) 1 else 0)
            put("trim_margins", if (cfg.trimMargins) 1 else 0)
            put("trim_strength", cfg.trimStrength)
            put("trim_ignore_sides", if (cfg.trimIgnoreSides) 1 else 0)
            put("trim_protect", if (cfg.trimProtect) 1 else 0)
            put("skip_blank_pages", if (cfg.skipBlankPages) 1 else 0)
            put("click_flip_enabled", if (cfg.clickFlipEnabled) 1 else 0)
            put("click_flip_mode", cfg.clickFlipMode.name)
            put("swap_click_flip", if (cfg.swapClickFlip) 1 else 0)
            put("volume_key_flip", if (cfg.volumeKeyFlip) 1 else 0)
            put("keep_screen_on", if (cfg.keepScreenOn) 1 else 0)
            put("show_page_number", if (cfg.showPageNumber) 1 else 0)
            put("show_toolbar_on_open", if (cfg.showToolbarOnOpen) 1 else 0)
            put("orientation", cfg.orientation.name)
            put("side_margin_dp", cfg.sideMarginDp)
            put("bg_color", cfg.bgColor.name)
            put("pdf_scale", cfg.pdfScale)
        }
        return if (cfg.id > 0) {
            writableDatabase.update("reader_configs", cv, "id = ?", arrayOf(cfg.id.toString()))
            cfg.id
        } else {
            writableDatabase.insert("reader_configs", null, cv)
        }
    }

    // --- Venera Comic Sources & Online Comics ---

    fun getComicSources(onlyEnabled: Boolean = false): List<ComicSourceEntity> {
        val list = mutableListOf<ComicSourceEntity>()
        val sql = if (onlyEnabled) {
            "SELECT * FROM comic_sources WHERE enabled = 1 ORDER BY sort_order ASC, updated_at DESC"
        } else {
            "SELECT * FROM comic_sources ORDER BY sort_order ASC, updated_at DESC"
        }
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ComicSourceEntity(
                        sourceKey = c.getString(c.getColumnIndexOrThrow("source_key")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        version = c.getString(c.getColumnIndexOrThrow("version")),
                        url = c.getString(c.getColumnIndexOrThrow("url")),
                        script = c.getString(c.getColumnIndexOrThrow("script")),
                        settingsJson = c.getString(c.getColumnIndexOrThrow("settings_json")),
                        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                        builtIn = c.getInt(c.getColumnIndexOrThrow("built_in")) == 1,
                        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
        return list
    }

    fun getComicSource(sourceKey: String): ComicSourceEntity? {
        readableDatabase.rawQuery(
            "SELECT * FROM comic_sources WHERE source_key = ?",
            arrayOf(sourceKey)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ComicSourceEntity(
                sourceKey = c.getString(c.getColumnIndexOrThrow("source_key")),
                name = c.getString(c.getColumnIndexOrThrow("name")),
                version = c.getString(c.getColumnIndexOrThrow("version")),
                url = c.getString(c.getColumnIndexOrThrow("url")),
                script = c.getString(c.getColumnIndexOrThrow("script")),
                settingsJson = c.getString(c.getColumnIndexOrThrow("settings_json")),
                enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                builtIn = c.getInt(c.getColumnIndexOrThrow("built_in")) == 1,
                sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
                updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
            )
        }
    }

    fun upsertComicSource(src: ComicSourceEntity) {
        val cv = ContentValues().apply {
            put("source_key", src.sourceKey)
            put("name", src.name)
            put("version", src.version)
            put("url", src.url)
            put("script", src.script)
            put("settings_json", src.settingsJson)
            put("enabled", if (src.enabled) 1 else 0)
            put("built_in", if (src.builtIn) 1 else 0)
            put("sort_order", src.sortOrder)
            put("updated_at", src.updatedAt)
        }
        val existing = getComicSource(src.sourceKey)
        if (existing == null) {
            writableDatabase.insert("comic_sources", null, cv)
        } else {
            // Preserve user settings & enabled flag unless script update replaces them
            cv.put("settings_json", existing.settingsJson)
            writableDatabase.update("comic_sources", cv, "source_key = ?", arrayOf(src.sourceKey))
        }
    }

    fun setComicSourceEnabled(sourceKey: String, enabled: Boolean) {
        val cv = ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("comic_sources", cv, "source_key = ?", arrayOf(sourceKey))
    }

    fun setAllComicSourcesEnabled(enabled: Boolean) {
        val cv = ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("comic_sources", cv, null, null)
    }

    fun updateComicSourceSettings(sourceKey: String, settingsJson: String) {
        val cv = ContentValues().apply {
            put("settings_json", settingsJson)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("comic_sources", cv, "source_key = ?", arrayOf(sourceKey))
    }

    fun deleteComicSource(sourceKey: String) {
        writableDatabase.delete("comic_sources", "source_key = ?", arrayOf(sourceKey))
    }

    fun getOnlineComic(sourceKey: String, remoteId: String): Comic? =
        getComicBySourceUri("venera://$sourceKey/$remoteId")

    fun saveOnlinePreview(sourceKey: String, remoteId: String, title: String, coverKey: String): Comic {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val comic = getOnlineComic(sourceKey, remoteId) ?: upsertOnlineComic(
                sourceKey, remoteId, title.ifBlank { remoteId }, coverKey, emptyList()
            ).let { checkNotNull(getComic(it)) }
            database.setTransactionSuccessful()
            return comic
        } finally {
            database.endTransaction()
        }
    }

    fun getComicBySourceUri(sourceUri: String): Comic? {
        readableDatabase.rawQuery(
            "SELECT * FROM comics WHERE source_uri = ? LIMIT 1",
            arrayOf(sourceUri)
        ).use { c ->
            return if (c.moveToFirst()) c.toComic() else null
        }
    }

    /**
     * Upserts an online comic & its chapters into the unified comics/chapters tables so it
     * works identically in Collections (作品集), Favorites, Recent History, Bookmarks, and Reader.
     */
    fun upsertOnlineComic(
        sourceKey: String,
        remoteId: String,
        title: String,
        coverKey: String,
        chapters: List<RemoteChapter>
    ): Long {
        val now = System.currentTimeMillis()
        val comicUri = "venera://$sourceKey/$remoteId"
        val distinctChapters = chapters.distinctBy { it.id }
        writableDatabase.beginTransaction()
        try {
            val existing = getComicBySourceUri(comicUri)
            val comicId: Long
            if (existing != null) {
                comicId = existing.id
                val cv = ContentValues().apply {
                    put("title", title)
                    if (coverKey.isNotEmpty()) put("cover_cache_key", coverKey)
                    if (distinctChapters.isNotEmpty()) put("total_chapters", distinctChapters.size)
                    put("updated_at", now)
                }
                writableDatabase.update("comics", cv, "id = ?", arrayOf(comicId.toString()))
            } else {
                val cv = ContentValues().apply {
                    put("warehouse_id", 0L)
                    put("title", title)
                    put("source_uri", comicUri)
                    put("format", ComicFormat.ONLINE.name)
                    put("cover_cache_key", coverKey)
                    put("total_chapters", distinctChapters.size)
                    put("last_read_chapter_id", 0L)
                    put("last_read_page", 0)
                    put("last_read_at", 0L)
                    put("updated_at", now)
                    put("is_favorite", 0)
                    put("reader_config_id", 1L)
                }
                comicId = writableDatabase.insert("comics", null, cv)
            }

            if (distinctChapters.isNotEmpty()) {
                val oldChapters = getChapters(comicId)
                val oldMap = oldChapters.associateBy { it.sourceUri }
                val retained = mutableSetOf<Long>()
                distinctChapters.forEachIndexed { idx, ch ->
                    val chUri = "$comicUri/${ch.id}"
                    val exactMatch = oldMap[chUri]?.takeUnless { it.id in retained }
                    val legacyMatch = if (ch.id.startsWith("https://") || ch.id.startsWith("http://")) {
                        oldChapters.getOrNull(idx)?.takeIf {
                            it.id !in retained && it.sourceUri == "$comicUri/$idx" && it.title == ch.title
                        }
                    } else null
                    val previous = exactMatch ?: legacyMatch
                    val values = ContentValues().apply {
                        put("comic_id", comicId)
                        put("title", ch.title)
                        put("source_uri", chUri)
                        put("format", ComicFormat.ONLINE.name)
                        put("sort_order", idx)
                    }
                    if (previous == null) {
                        retained.add(writableDatabase.insertOrThrow("chapters", null, values))
                    } else {
                        writableDatabase.update("chapters", values, "id = ?", arrayOf(previous.id.toString()))
                        retained.add(previous.id)
                    }
                }
                oldChapters.filterNot { it.id in retained }.forEach { chapter ->
                    writableDatabase.delete("chapters", "id = ?", arrayOf(chapter.id.toString()))
                }
                if (existing != null && existing.lastReadChapterId > 0 && existing.lastReadChapterId !in retained) {
                    val reset = ContentValues().apply {
                        put("last_read_chapter_id", 0L)
                        put("last_read_page", 0)
                    }
                    writableDatabase.update("comics", reset, "id = ?", arrayOf(comicId.toString()))
                }
            }
            writableDatabase.setTransactionSuccessful()
            return comicId
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun deleteReaderConfig(id: Long) {
        if (id == 1L) return
        writableDatabase.beginTransaction()
        try {
            val reset = ContentValues().apply { put("reader_config_id", 1L) }
            writableDatabase.update("comics", reset, "reader_config_id = ?", arrayOf(id.toString()))
            writableDatabase.delete("reader_configs", "id = ?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // --- Helpers ---

    private fun Cursor.toWarehouse() = Warehouse(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        uriOrUrl = getString(getColumnIndexOrThrow("uri_or_url")),
        type = runCatching { WarehouseType.valueOf(getString(getColumnIndexOrThrow("type"))) }.getOrDefault(WarehouseType.LOCAL_SAF),
        coverName = getString(getColumnIndexOrThrow("cover_name")),
        hideInGallery = getInt(getColumnIndexOrThrow("hide_in_gallery")) == 1,
        defaultBookshelfId = getLong(getColumnIndexOrThrow("default_bookshelf_id")),
        webdavUser = getString(getColumnIndexOrThrow("webdav_user")),
        webdavPass = getString(getColumnIndexOrThrow("webdav_pass")),
        lastScannedAt = getLong(getColumnIndexOrThrow("last_scanned_at"))
    )

    private fun Cursor.toComic() = Comic(
        id = getLong(getColumnIndexOrThrow("id")),
        warehouseId = getLong(getColumnIndexOrThrow("warehouse_id")),
        title = getString(getColumnIndexOrThrow("title")),
        sourceUri = getString(getColumnIndexOrThrow("source_uri")),
        format = runCatching { ComicFormat.valueOf(getString(getColumnIndexOrThrow("format"))) }.getOrDefault(ComicFormat.FOLDER),
        coverCacheKey = getString(getColumnIndexOrThrow("cover_cache_key")),
        totalChapters = getInt(getColumnIndexOrThrow("total_chapters")),
        lastReadChapterId = getLong(getColumnIndexOrThrow("last_read_chapter_id")),
        lastReadPage = getInt(getColumnIndexOrThrow("last_read_page")),
        lastReadAt = getLong(getColumnIndexOrThrow("last_read_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        isFavorite = getInt(getColumnIndexOrThrow("is_favorite")) == 1,
        readerConfigId = getLong(getColumnIndexOrThrow("reader_config_id"))
    )

    private fun Cursor.toChapter() = Chapter(
        id = getLong(getColumnIndexOrThrow("id")),
        comicId = getLong(getColumnIndexOrThrow("comic_id")),
        title = getString(getColumnIndexOrThrow("title")),
        sourceUri = getString(getColumnIndexOrThrow("source_uri")),
        format = runCatching { ComicFormat.valueOf(getString(getColumnIndexOrThrow("format"))) }.getOrDefault(ComicFormat.FOLDER),
        entryPrefix = getString(getColumnIndexOrThrow("entry_prefix")),
        pageCount = getInt(getColumnIndexOrThrow("page_count")),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
        lastReadPage = getInt(getColumnIndexOrThrow("last_read_page"))
    )

    private fun Cursor.toBookshelf() = Bookshelf(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
        isHiddenFromAll = getInt(getColumnIndexOrThrow("is_hidden_from_all")) == 1,
        lockType = runCatching { LockType.valueOf(getString(getColumnIndexOrThrow("lock_type"))) }.getOrDefault(LockType.NONE),
        pinHash = getString(getColumnIndexOrThrow("pin_hash")),
        quickRelock = getInt(getColumnIndexOrThrow("quick_relock")) == 1
    )

    private fun Cursor.toBookmark() = Bookmark(
        id = getLong(getColumnIndexOrThrow("id")),
        comicId = getLong(getColumnIndexOrThrow("comic_id")),
        chapterId = getLong(getColumnIndexOrThrow("chapter_id")),
        chapterTitle = getString(getColumnIndexOrThrow("chapter_title")),
        pageIndex = getInt(getColumnIndexOrThrow("page_index")),
        note = getString(getColumnIndexOrThrow("note")),
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )

    private fun Cursor.toReaderConfig() = ReaderConfig(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        scrollMode = runCatching { ScrollMode.valueOf(getString(getColumnIndexOrThrow("scroll_mode"))) }.getOrDefault(ScrollMode.VERTICAL),
        doublePageMode = runCatching { DoublePageMode.valueOf(getString(getColumnIndexOrThrow("double_page_mode"))) }.getOrDefault(DoublePageMode.NORMAL),
        firstPageSingle = getInt(getColumnIndexOrThrow("first_page_single")) == 1,
        mergeRightToLeft = getInt(getColumnIndexOrThrow("merge_rtl")) == 1,
        trimMargins = getInt(getColumnIndexOrThrow("trim_margins")) == 1,
        trimStrength = getInt(getColumnIndexOrThrow("trim_strength")),
        trimIgnoreSides = getInt(getColumnIndexOrThrow("trim_ignore_sides")) == 1,
        trimProtect = getInt(getColumnIndexOrThrow("trim_protect")) == 1,
        skipBlankPages = getInt(getColumnIndexOrThrow("skip_blank_pages")) == 1,
        clickFlipEnabled = getInt(getColumnIndexOrThrow("click_flip_enabled")) == 1,
        clickFlipMode = runCatching { ClickFlipMode.valueOf(getString(getColumnIndexOrThrow("click_flip_mode"))) }.getOrDefault(ClickFlipMode.LEFT_RIGHT),
        swapClickFlip = getInt(getColumnIndexOrThrow("swap_click_flip")) == 1,
        volumeKeyFlip = getInt(getColumnIndexOrThrow("volume_key_flip")) == 1,
        keepScreenOn = getInt(getColumnIndexOrThrow("keep_screen_on")) == 1,
        showPageNumber = getInt(getColumnIndexOrThrow("show_page_number")) == 1,
        showToolbarOnOpen = getInt(getColumnIndexOrThrow("show_toolbar_on_open")) == 1,
        orientation = runCatching { ReaderOrientation.valueOf(getString(getColumnIndexOrThrow("orientation"))) }.getOrDefault(ReaderOrientation.SYSTEM),
        sideMarginDp = getInt(getColumnIndexOrThrow("side_margin_dp")),
        bgColor = runCatching { ReaderBgColor.valueOf(getString(getColumnIndexOrThrow("bg_color"))) }.getOrDefault(ReaderBgColor.BLACK),
        pdfScale = getFloat(getColumnIndexOrThrow("pdf_scale"))
    )

    companion object {
        private const val DB_NAME = "ark_reader.db"
        private const val DB_VERSION = 6

        fun hashPin(pin: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
