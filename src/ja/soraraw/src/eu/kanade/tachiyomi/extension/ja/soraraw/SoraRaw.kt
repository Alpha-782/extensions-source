package eu.kanade.tachiyomi.extension.ja.soraraw

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.TriState
import eu.kanade.tachiyomi.source.model.Filter.TriState.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.source.model.Filter.TriState.Companion.STATE_IGNORE
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class SoraRaw :
    KeiSource(),
    ConfigurableSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val preferences by getPreferencesLazy()

    // Intercepts canva image responses and descrambles them on-device.
    // Fragments never reach the wire; untagged requests pass through untouched.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(CanvaDescrambleInterceptor())

    private class CanvaDescrambleInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val fragment = request.url.fragment ?: return chain.proceed(request)

            // Tag formats from getPageList:
            //   "#c<chapterId>:<token>" — canva  (vL/seedrandom pipeline)
            //   "#w<chapterId>"         — canva2 (wasm nxn pipeline)
            val bytes: ByteArray
            val contentType: MediaType?
            when (fragment.firstOrNull()) {
                'c' -> {
                    val parts = fragment.drop(1).split(':', limit = 2)
                    if (parts.size != 2 || parts[0].toLongOrNull() == null || parts[1].isEmpty()) {
                        return chain.proceed(request)
                    }
                    val response = chain.proceed(stripped(request))
                    contentType = response.body?.contentType()
                    bytes = response.body?.bytes() ?: return response
                    val fixed = runCatching {
                        CanvaDescrambler.descramble(bytes, parts[0], parts[1])
                    }.getOrNull() ?: return rawResponse(response, bytes, contentType)
                    return rawResponse(response, fixed, "image/png".toMediaTypeOrNull())
                }
                'w' -> {
                    val chapterId = fragment.drop(1)
                    if (chapterId.toLongOrNull() == null) return chain.proceed(request)
                    val response = chain.proceed(stripped(request))
                    contentType = response.body?.contentType()
                    bytes = response.body?.bytes() ?: return response
                    val fixed = runCatching {
                        ImageDescrambler.descramble(bytes, chapterId)
                    }.getOrNull() ?: return rawResponse(response, bytes, contentType)
                    return rawResponse(response, fixed, "image/png".toMediaTypeOrNull())
                }
                else -> return chain.proceed(request)
            }
        }

        private fun stripped(request: Request): Request = request.newBuilder()
            .url(request.url.newBuilder().fragment(null).build())
            .build()

        private fun rawResponse(response: Response, body: ByteArray, type: MediaType?): Response = response.newBuilder()
            .body(body.toResponseBody(type))
            .build()
    }

    private val defaultContent: String?
        get() = when (preferences.getString(PREF_DEFAULT_CONTENT, "all")) {
            "general" -> "no"
            "adult" -> "yes"
            else -> null
        }

    // =========================== Catalog ============================
    private suspend fun catalog(): List<MangaEntryDto> {
        val entries = buildList {
            for (page in 1..MAX_DUMP_PAGES) {
                val response = client.get("$baseUrl/mangas_$page.json", ensureSuccess = false)
                if (!response.isSuccessful) break // 404 = documented end-of-catalog marker
                val dto = runCatching { response.parseAs<MangaListDto>() }.getOrElse { break }
                if (dto.list.isEmpty()) break
                addAll(dto.list)
            }
        }.distinctBy { it.id }

        if (entries.isEmpty()) throw Exception("作品一覧の取得に失敗しました。")
        return entries
    }

    private suspend fun queryCatalog(
        page: Int,
        query: String? = null,
        order: String? = null,
        status: String? = null,
        content: String? = null,
        mode: String? = null,
        genreIds: Set<Long> = emptySet(),
        excludedGenreIds: Set<Long> = emptySet(),
    ): MangasPage {
        var entries = catalog()

        if (!query.isNullOrBlank()) {
            val q = query.trim()
            entries = entries.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.altNames.orEmpty().contains(q, ignoreCase = true) ||
                    it.author.orEmpty().contains(q, ignoreCase = true)
            }
        }
        status?.let { s -> entries = entries.filter { it.type == s } }
        content?.let { c -> entries = entries.filter { it.isAdult == c } }

        entries = when (order) {
            "updated" -> entries.sortedByDescending { it.latestChapterDate.orEmpty() }
            "bookmark" -> entries.sortedByDescending { it.bookmarks }
            else -> entries.sortedByDescending { it.views }
        }

        when (mode) {
            "horizontal" -> entries = entries.filter { it.mode?.startsWith("horizontal") == true }
            "vertical" -> entries = entries.filter { it.mode == "vertical" }
            else -> Unit
        }

        if (genreIds.isNotEmpty()) entries = entries.filter { e -> e.genres.any { it in genreIds } }

        if (excludedGenreIds.isNotEmpty()) {
            entries = entries.filter { e -> e.genres.none { it in excludedGenreIds } }
        }

        val from = (page - 1) * PAGE_SIZE
        val mangas = entries.drop(from).take(PAGE_SIZE).map { it.toSManga() }
        return MangasPage(mangas, from + PAGE_SIZE < entries.size)
    }

    private fun MangaEntryDto.toSManga() = SManga.create().apply {
        title = name
        setUrlWithoutDomain("$baseUrl/manga/$slug")
        thumbnail_url = img?.let { "$CDN_BASE/$it" }
    }

    // ====================== Popular, Latest & Search ======================
    override suspend fun getPopularManga(page: Int) = queryCatalog(
        page = page,
        order = "views",
        content = defaultContent,
        excludedGenreIds = excludedGenreIds(),
    )

    override suspend fun getLatestUpdates(page: Int) = queryCatalog(
        page = page,
        order = "updated",
        content = defaultContent,
        excludedGenreIds = excludedGenreIds(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = queryCatalog(
        page = page,
        query = query.takeIf { it.isNotBlank() },
        order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.selected,
        status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected,
        content = when (filters.filterIsInstance<ContentFilter>().firstOrNull()?.selected) {
            "any" -> null
            "no" -> "no"
            "yes" -> "yes"
            else -> defaultContent
        },
        genreIds = filters.findGenreFilters().flatMapTo(mutableSetOf()) { it.selectedIds },
        excludedGenreIds = excludedGenreIds() -
            filters.findGenreFilters().flatMapTo(mutableSetOf()) { it.selectedIds },
        mode = filters.filterIsInstance<ModeFilter>().firstOrNull()?.selected,
    )

    // ============================== Filters ==============================
    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/genres.json").parseAs()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genreList = data?.let { parseGenres(it) }
        val excluded = excludedGenreIds()

        val filters = mutableListOf<Filter<*>>(
            ContentFilter(),
            ModeFilter(),
            OrderFilter(),
            StatusFilter(),
        )

        if (!genreList.isNullOrEmpty()) {
            val genreFilters = mutableListOf<GenreFilter>()

            genreList.sortedByDescending { it.total }
                .chunked(GENRE_CHUNK_SIZE)
                .forEachIndexed { index, chunk ->
                    val from = index * GENRE_CHUNK_SIZE + 1
                    genreFilters.add(
                        GenreFilter("ジャンル $from–${from + chunk.size - 1}", chunk, excluded),
                    )
                }

            if (genreFilters.isNotEmpty()) {
                filters.add(GenreGroupFilter(genreFilters.toList()))
            }
        }

        return FilterList(filters)
    }

    private fun FilterList.findGenreFilters(): List<GenreFilter> = flatMap { filter ->
        when (filter) {
            is GenreGroupFilter -> filter.state
            else -> emptyList()
        }
    }

    private fun parseGenres(data: JsonElement): List<GenreDto> {
        val array = when (data) {
            is JsonArray -> data
            is JsonObject -> data["list"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return runCatching { json.decodeFromJsonElement<List<GenreDto>>(array) }.getOrDefault(emptyList())
    }

    class TriStateFilter(
        name: String,
        val value: String = name,
        state: Int = STATE_IGNORE,
    ) : TriState(name, state)

    class GenreGroupFilter(
        state: List<GenreFilter>,
    ) : Filter.Group<GenreFilter>("ジャンル", state)

    class GenreFilter(
        name: String,
        val genreValues: List<GenreDto>,
        val excludedIds: Set<Long> = emptySet(),
    ) : Filter.Group<TriStateFilter>(
        name = name,
        state = genreValues.map { genre ->
            TriStateFilter(
                name = genre.name,
                state = if (genre.id in excludedIds) STATE_EXCLUDE else STATE_IGNORE,
            )
        },
    ) {
        val selectedIds: Set<Long> get() =
            genreValues.filterIndexed { i, _ -> state[i].isIncluded() }.map { it.id }.toSet()
    }

    private class ContentFilter :
        Filter.Select<String>(
            "コンテンツ",
            arrayOf("既定", "すべて", "一般", "18+"),
        ) {
        val selected: String? get() = arrayOf(null, "any", "no", "yes")[state]
    }

    private class ModeFilter :
        Filter.Select<String>(
            "表示形式",
            arrayOf("すべて", "横", "縦"),
        ) {
        val selected: String? get() = arrayOf(null, "horizontal", "vertical")[state]
    }

    private class OrderFilter :
        Filter.Select<String>(
            "並び順",
            arrayOf("閲覧数", "更新", "保存"),
        ) {
        val selected: String get() = arrayOf("views", "updated", "bookmark")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "ステータス",
            arrayOf("すべて", "連載中", "完結"),
        ) {
        val selected: String? get() = arrayOf(null, "incomplete", "complete")[state]
    }

    // ============================== Details ==============================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = client.get(getMangaUrl(manga)).asJsoup().getMangaDto()
        return SMangaUpdate(dto.toSManga(), dto.toChapterList())
    }

    private fun Document.getMangaDto(): MangaDetailsDto {
        val obj = nextData().pageProps()["data"]?.jsonObject
            ?.get("manga")?.jsonObject
            ?: throw Exception("詳細データの読み込みに失敗しました。")
        return json.decodeFromJsonElement(obj)
    }

    private fun MangaDetailsDto.toSManga() = SManga.create().apply {
        title = name
        author = this@toSManga.author?.takeIf { it.isNotBlank() && it != "更新中" }
        status = when (type) {
            "complete" -> SManga.COMPLETED
            "incomplete" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        thumbnail_url = image?.let { "$CDN_BASE/$it" }
        genre = genres.joinToString { it.name }
        description = buildDescription()
    }

    private fun MangaDetailsDto.buildDescription(): String? = buildString {
        description?.let { append(it.trim()) }

        content?.let { contentJson ->
            runCatching {
                val blocks = json.parseToJsonElement(contentJson)
                    .jsonObject["blocks"]?.jsonArray ?: return@runCatching
                if (isNotEmpty()) append("\n\n")
                blocks.forEach { block ->
                    val obj = block.jsonObject
                    val data = obj["data"]?.jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "paragraph", "header" -> data?.get("text")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?.let { append(Jsoup.parse(it).text()).append("\n\n") }
                        "list" -> data?.get("items")?.jsonArray?.forEach { item ->
                            append("• ").append(Jsoup.parse(item.jsonPrimitive.content).text()).append("\n")
                        }
                    }
                }
            }
        }

        if (altNames.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("別名義: ").append(altNames.joinToString { it.name })
        }
    }.trim().takeIf { it.isNotEmpty() }

    private fun MangaDetailsDto.toChapterList(): List<SChapter> = chapters.map { ch ->
        SChapter.create().apply {
            val number = ch.path?.substringAfterLast("-ch-", ch.name) ?: ch.name
            url = "/manga/$slug/ch-$number"
            name = listOfNotNull(
                ch.name.takeIf { it.isNotBlank() }?.let { "第${it}話" },
                ch.title?.takeIf { it.isNotBlank() },
            ).joinToString(" ").ifBlank { number }
            chapter_number = ch.order
            date_upload = ch.publishedAt?.toEpochMillis() ?: 0L
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga {
        val dataObj = client.get(url).asJsoup().nextData().pageProps()["data"]?.jsonObject
            ?: throw Exception("詳細データの読み込みに失敗しました。")
        val mangaObj = (dataObj["manga"] ?: dataObj["chapter"]?.jsonObject?.get("manga"))?.jsonObject
            ?: throw Exception("このURLは作品ページではありません。")
        return json.decodeFromJsonElement<MangaDetailsDto>(mangaObj).toSManga()
    }

    // =============================== Pages ===============================
    private val manifestBase: String by lazy {
        eDecrypt(SETTINGS_D, SETTINGS_PASSWORD)?.takeIf { it.startsWith("http") } ?: MANIFEST_FALLBACK
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val dataObj = client.get(getChapterUrl(chapter)).asJsoup().nextData().pageProps()["data"]?.jsonObject
            ?: throw Exception("章データの取得に失敗しました。")

        // Some chapters embed images directly
        (dataObj["chapter"]?.jsonObject?.get("images") as? JsonArray)
            ?.takeIf { it.isNotEmpty() }
            ?.let { arr -> return arr.mapIndexedNotNull { i, el -> el.asImageUrl()?.let { Page(i, it) } } }

        val ch = json.decodeFromJsonElement<ChapterPageDto>(
            dataObj["chapter"]?.jsonObject ?: throw Exception("章データがありません。"),
        )

        // canva chapters are scrambled at source on ALL flavors; the site
        // descrambles client-side with K = HMAC-SHA256(token, chapterId) —
        // ported in CanvaDescrambler, applied by the interceptor via fragment tag.
        // canva2 routes through the site's x8/wasm pipeline instead — not ported.
        val tag = when (ch.mode) {
            "canva" -> ch.token?.takeIf { it.isNotBlank() }?.let { "#c${ch.id}:$it" }
            "canva2" -> "#w${ch.id}"
            else -> null
        }.orEmpty()

        val uuidHex = ch.uuid ?: throw Exception("復号鍵がありません。")
        val mangaId = ch.manga?.id ?: ch.mangaId ?: throw Exception("作品IDがありません。")
        val host = ch.imageBase ?: "https://lh${ch.id % 4 + 1}.rawcontent.top"
        val driveBase = ch.driveBase ?: "https://lh3.googleusercontent.com"
        val t = ch.updatedAt?.toEpochMillis() ?: System.currentTimeMillis()

        val resp = client.get("$manifestBase/$mangaId/${ch.id}.json?t=$t")
        val payload = json.decodeFromString<ImagesResponseDto>(resp.body.string()).d
        val entries: List<ManifestEntryDto> = json.decodeFromString(payload.xorDecrypt())

        // b (rawcontent) preferred — validated descramble path; d (Drive) fallback.
        return entries.sortedBy { it.order }.mapIndexed { i, e ->
            val imageUrl = when {
                e.b != null -> gDecrypt(e.b, uuidHex).let { "$host/$it" }
                e.d != null -> gDecrypt(e.d, uuidHex).let { "$driveBase/$it" }
                else -> throw Exception("画像データがありません。")
            }
            Page(i, imageUrl = imageUrl + tag)
        }
    }

    // ============================== Crypto ===============================
    // Manifest outer layer: base64url → repeating-key XOR → UTF-8 JSON
    private fun String.xorDecrypt(): String {
        val raw = this.decodeB64Flexible()
        val key = MANIFEST_XOR_KEY.toByteArray(Charsets.UTF_8)
        val out = ByteArray(raw.size) { i ->
            (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8).replace("\u0000", "")
    }

    // g(): b64url(b) XOR pwd → [16-byte IV | ciphertext] → AES-256-CTR(key = uuid)
    private fun gDecrypt(b64: String, uuidHex: String): String {
        val key = ByteArray(uuidHex.length / 2) { i ->
            uuidHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val data = b64.decodeB64Flexible()
        val pw = IMAGE_PASSWORD.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(data.size) { i ->
            (data[i].toInt() xor pw[i % pw.size].toInt()).toByte()
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(xored, 0, 16))
        return cipher.doFinal(xored, 16, xored.size - 16).toString(Charsets.UTF_8)
    }

    // E(): fnv1a32(password + input[0..8]) seeds xorshift32 keystream over base64(input[8..])
    private fun eDecrypt(input: String, password: String): String? = runCatching {
        var x = fnv1a32(password + input.substring(0, 8))
        val data = Base64.getDecoder().decode(input.substring(8))
        val out = ByteArray(data.size)
        for (i in data.indices) {
            x = x xor ((x shl 13) and 0xFFFFFFFFL)
            x = x xor (x ushr 17)
            x = x xor ((x shl 5) and 0xFFFFFFFFL)
            out[i] = (data[i].toInt() xor (x and 0xFF).toInt()).toByte()
        }
        String(out, Charsets.UTF_8)
    }.getOrNull()

    private fun fnv1a32(s: String): Long {
        var h = 0x811c9dc5L
        for (c in s) {
            h = h xor c.code.toLong()
            h = (h + (h shl 1) + (h shl 4) + (h shl 7) + (h shl 8) + (h shl 24)) and 0xFFFFFFFFL
        }
        return h
    }

    // ============================== Helpers ===============================
    private fun Document.nextData(): JsonObject = selectFirst("script#__NEXT_DATA__")?.data()
        ?.let { json.parseToJsonElement(it) }
        ?.jsonObject
        ?: throw Exception("予期しないレスポンスです。")

    private fun String.toEpochMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)

    // Manifest arrays may be strings or {url}/{src} objects
    private fun JsonElement.asImageUrl(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> (this["url"] ?: this["src"])?.jsonPrimitive?.contentOrNull
        else -> null
    }

    // __NEXT_DATA__ root has the payload under "props" (the _next/data endpoint
    // omits it — handle both as fallback)
    private fun JsonObject.pageProps(): JsonObject = (this["props"]?.jsonObject ?: this)["pageProps"]?.jsonObject
        ?: throw Exception("予期しないレスポンスです。")

    // The site normalizes both base64 alphabets to standard before decoding
    // (replace(/-/g,'+').replace(/_/g,'/') + pad), so we mirror it
    private fun String.decodeB64Flexible(): ByteArray {
        val s = replace('-', '+').replace('_', '/')
        return Base64.getDecoder().decode(s + "=".repeat((4 - s.length % 4) % 4))
    }

    private fun defaultContentMode(): String = preferences.getString(PREF_DEFAULT_CONTENT, "all")!!

    private fun excludedGenreIds(): Set<Long> {
        val mode = defaultContentMode()
        val ids = when (mode) {
            "general" -> preferences.getStringSet(PREF_EXCLUDE_GENRE_GENERAL, emptySet()).orEmpty()
            else -> preferences.getStringSet(PREF_EXCLUDE_GENRE_ADULT, emptySet()).orEmpty()
        }
        return ids.mapNotNull(String::toLongOrNull).toSet()
    }

    // ============================ Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val contentPref = ListPreference(screen.context).apply {
            key = PREF_DEFAULT_CONTENT
            title = "既定の表示コンテンツ"
            entries = arrayOf("すべて", "一般のみ", "18+のみ")
            entryValues = arrayOf("all", "general", "adult")
            setDefaultValue("all")
            summary = "%s"
        }
        screen.addPreference(contentPref)

        val genres = getFilterList()
            .findGenreFilters()
            .flatMap { it.genreValues }
            .sortedByDescending { it.total }

        val genreEntries = genres.map { it.name }.toTypedArray()
        val genreValues = genres.map { it.id.toString() }.toTypedArray()
        val hasGenres = genreValues.isNotEmpty()
        val mode = defaultContentMode()

        val generalBlacklist = MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_GENRE_GENERAL
            title = "ジャンルブラックリスト（一般）"
            summary = "「一般のみ」閲覧時に除外するジャンル"
            entries = genreEntries
            entryValues = genreValues
            setDefaultValue(emptySet<String>())
            setEnabled(hasGenres && mode == "general")
        }
        screen.addPreference(generalBlacklist)

        val adultBlacklist = MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_GENRE_ADULT
            title = "ジャンルブラックリスト（18+）"
            summary = "「すべて」「18+のみ」閲覧時に除外するジャンル"
            entries = genreEntries
            entryValues = genreValues
            setDefaultValue(emptySet<String>())
            setEnabled(hasGenres && (mode == "all" || mode == "adult"))
        }
        screen.addPreference(adultBlacklist)

        contentPref.setOnPreferenceChangeListener { _, newValue ->
            val newMode = newValue as String
            generalBlacklist.setEnabled(hasGenres && newMode == "general")
            adultBlacklist.setEnabled(hasGenres && (newMode == "all" || newMode == "adult"))
            true
        }
    }

    companion object {

        private const val CDN_BASE = "https://i.mangaraw.lat"

        // Manifest outer layer: base64url + repeating-XOR
        private const val MANIFEST_XOR_KEY = "/fuCkYou!!!"

        // g(): b64url(entry.b) XOR pwd → [16B IV | ct] → AES-256-CTR(key = chapter.uuid)
        private const val IMAGE_PASSWORD = "202508055d0db38bae2e86cc41649f90"

        // settings.d: E()-encrypted manifest base (resolves to the fallback; both kept
        // per the site's own `E(d) || apiImage` chain)
        private const val SETTINGS_D = "yDe1Tn9IbseIzyb4Ql++OBHdsm3xBqOL74wfKSAsUzit"
        private const val SETTINGS_PASSWORD = "202508055d0db38bae2e86cc41649f90"
        private const val MANIFEST_FALLBACK = "https://api.mangarawgo.site"

        private const val MAX_DUMP_PAGES = 200
        private const val PAGE_SIZE = 24
        private const val GENRE_CHUNK_SIZE = 250

        private const val PREF_DEFAULT_CONTENT = "default_content"

        private const val PREF_EXCLUDE_GENRE_GENERAL = "exclude_genre_general"
        private const val PREF_EXCLUDE_GENRE_ADULT = "exclude_genre_adult"
    }
}
