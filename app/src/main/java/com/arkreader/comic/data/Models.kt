package com.arkreader.comic.data

enum class WarehouseType {
    LOCAL_SAF,
    WEBDAV
}

enum class ComicFormat {
    FOLDER,
    ZIP,
    PDF,
    EPUB
}

enum class LockType {
    NONE,
    PIN,
    BIOMETRIC
}

enum class ScrollMode(val label: String) {
    VERTICAL("垂直连贯"),
    LTR("水平左滑"),
    RTL("水平右滑")
}

enum class DoublePageMode(val label: String) {
    NORMAL("始终单页"),
    AUTO_LANDSCAPE("竖屏单页 / 横屏双页"),
    MERGE("始终双页合并"),
    SPLIT("始终双页分割")
}

enum class ClickFlipMode(val label: String) {
    LEFT_RIGHT("左右"),
    DIAGONAL_BL_TR("对角(左下-右上)"),
    DIAGONAL_TL_BR("对角(左上-右下)")
}

enum class ReaderOrientation(val label: String) {
    SYSTEM("跟随系统"),
    PORTRAIT("竖屏"),
    LANDSCAPE("横屏")
}

enum class ReaderBgColor(val label: String, val colorInt: Int) {
    BLACK("纯黑", 0xFF000000.toInt()),
    DARK_GRAY("深灰", 0xFF1E1E1E.toInt()),
    LIGHT_GRAY("浅灰", 0xFFEAEAEA.toInt()),
    WHITE("纯白", 0xFFFFFFFF.toInt())
}

enum class GridSize(val label: String) {
    LARGE("网格-大"),
    MEDIUM("网格-中"),
    SMALL("网格-小"),
    LIST("列表")
}

enum class SortType(val label: String) {
    NAME_ASC("名称(A-Z)"),
    NAME_DESC("名称(Z-A)"),
    READ_DESC("阅读时间(新到旧)"),
    UPDATE_DESC("更新时间(新到旧)"),
    FAVORITE_DESC("收藏优先")
}

data class Warehouse(
    val id: Long = 0,
    val name: String,
    val uriOrUrl: String,
    val type: WarehouseType,
    val coverName: String = "cover",
    val hideInGallery: Boolean = false,
    val defaultBookshelfId: Long = 0,
    val webdavUser: String = "",
    val webdavPass: String = "",
    val lastScannedAt: Long = 0
)

data class Comic(
    val id: Long = 0,
    val warehouseId: Long,
    val title: String,
    val sourceUri: String,
    val format: ComicFormat,
    val coverCacheKey: String = "",
    val totalChapters: Int = 0,
    val lastReadChapterId: Long = 0,
    val lastReadPage: Int = 0,
    val lastReadAt: Long = 0,
    val updatedAt: Long = 0,
    val isFavorite: Boolean = false,
    val readerConfigId: Long = 1
)

data class Chapter(
    val id: Long = 0,
    val comicId: Long,
    val title: String,
    val sourceUri: String,
    val format: ComicFormat,
    val entryPrefix: String = "",
    val pageCount: Int = 0,
    val sortOrder: Int = 0,
    val lastReadPage: Int = 0
)

data class Bookshelf(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val isHiddenFromAll: Boolean = false,
    val lockType: LockType = LockType.NONE,
    val pinHash: String = "",
    val quickRelock: Boolean = false
)

data class Bookmark(
    val id: Long = 0,
    val comicId: Long,
    val chapterId: Long,
    val chapterTitle: String,
    val pageIndex: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ReaderConfig(
    val id: Long = 1,
    val name: String = "默认配置",
    val scrollMode: ScrollMode = ScrollMode.VERTICAL,
    val doublePageMode: DoublePageMode = DoublePageMode.AUTO_LANDSCAPE,
    val firstPageSingle: Boolean = true,
    val mergeRightToLeft: Boolean = false,
    val trimMargins: Boolean = false,
    val trimStrength: Int = 18,
    val trimIgnoreSides: Boolean = true,
    val trimProtect: Boolean = true,
    val skipBlankPages: Boolean = false,
    val clickFlipEnabled: Boolean = true,
    val clickFlipMode: ClickFlipMode = ClickFlipMode.LEFT_RIGHT,
    val swapClickFlip: Boolean = false,
    val volumeKeyFlip: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = true,
    val showToolbarOnOpen: Boolean = false,
    val orientation: ReaderOrientation = ReaderOrientation.SYSTEM,
    val sideMarginDp: Int = 0,
    val bgColor: ReaderBgColor = ReaderBgColor.BLACK,
    val pdfScale: Float = 2.0f
)
