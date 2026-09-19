package eu.kanade.tachiyomi.extension.ja.soraraw

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class MangaListDto(val list: List<MangaEntryDto> = emptyList())

@Serializable
class MangaEntryDto(
    val id: Long,
    val name: String,
    val slug: String,
    val views: Long = 0,
    val author: String? = null,
    @SerialName("number_bookmark") val bookmarks: Int = 0,
    val type: String? = null,
    val img: String? = null,
    val mode: String? = null,
    val genres: List<Long> = emptyList(),
    @SerialName("alt_names") val altNames: String? = null,
    @SerialName("c_published") val latestChapterDate: String? = null,
    @SerialName("is_adult") val isAdult: String? = null,
)

@Serializable
class GenreDto(
    val id: Long,
    val name: String,
    val slug: String = "",
    val total: Int = 0,
)

@Serializable
class MangaDetailsDto(
    val name: String,
    val slug: String,
    val author: String? = null,
    val description: String? = null,
    val content: String? = null,
    val type: String? = null,
    val image: String? = null,
    @SerialName("names") val altNames: List<NameDto> = emptyList(),
    val genres: List<NameDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class NameDto(val name: String = "")

@Serializable
class ChapterDto(
    val name: String = "",
    val title: String? = null,
    val order: Float = 0f,
    @SerialName("published_at") val publishedAt: String? = null,
    val path: String? = null,
)

@Serializable
class ChapterPageDto(
    val id: Long,
    val uuid: String? = null,
    val mode: String? = null,
    val token: String? = null,
    @SerialName("_b") val imageBase: String? = null,
    @SerialName("_d") val driveBase: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("manga_id") val mangaId: Long? = null,
    val manga: MangaRefDto? = null,
)

@Serializable
class MangaRefDto(val id: Long)

@Serializable
class ImagesResponseDto(val d: String)

@Serializable
class ManifestEntryDto(
    val order: Float = 0f,
    val b: String? = null,
    val d: String? = null,
)
