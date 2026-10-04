package com.hippo.ehviewer.spider

import com.ehviewer.core.database.util.SimpleTagsConverter
import com.ehviewer.core.files.read
import com.ehviewer.core.files.write
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryTag
import com.ehviewer.core.model.PowerStatus
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.TagNamespace.Artist
import com.ehviewer.core.model.TagNamespace.Character
import com.ehviewer.core.model.TagNamespace.Cosplayer
import com.ehviewer.core.model.TagNamespace.Female
import com.ehviewer.core.model.TagNamespace.Group
import com.ehviewer.core.model.TagNamespace.Language
import com.ehviewer.core.model.TagNamespace.Location
import com.ehviewer.core.model.TagNamespace.Male
import com.ehviewer.core.model.TagNamespace.Mixed
import com.ehviewer.core.model.TagNamespace.Other
import com.ehviewer.core.model.TagNamespace.Parody
import com.ehviewer.core.model.TagNamespace.Reclass
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.parser.ParserUtils
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.devrieze.xmlutil.serialization.kxio.decodeFromSource
import net.devrieze.xmlutil.serialization.kxio.encodeToSink
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import okio.Path
import splitties.init.appCtx

const val COMIC_INFO_FILE = "ComicInfo.xml"
private const val TAG_ORIGINAL = "original"

private val xml = XML {
    recommended {
        ignoreUnknownChildren()
    }
    xmlDeclMode = XmlDeclMode.Charset
    xmlVersion = XmlVersion.XML10
    setIndent(2)
}

private val CATEGORY_RES = mapOf(
    EhUtils.DOUJINSHI to R.string.doujinshi,
    EhUtils.MANGA to R.string.manga,
    EhUtils.ARTIST_CG to R.string.artist_cg,
    EhUtils.GAME_CG to R.string.game_cg,
    EhUtils.WESTERN to R.string.western,
    EhUtils.NON_H to R.string.non_h,
    EhUtils.IMAGE_SET to R.string.image_set,
    EhUtils.COSPLAY to R.string.cosplay,
    EhUtils.ASIAN_PORN to R.string.asian_porn,
    EhUtils.MISC to R.string.misc,
)

fun GalleryInfo.getComicInfo(): ComicInfo {
    // 1. Raw extraction using raw English namespace (Zero translation at this stage)
    val rawArtists = mutableListOf<String>()
    val rawGroups = mutableListOf<String>()
    val rawCharacters = mutableListOf<String>()
    val rawTags = mutableListOf<Pair<TagNamespace, String>>()

    with(TagNamespace) {
        when (this@getComicInfo) {
            is GalleryDetail -> tagGroups.forEach { group ->
                val list = group.tags.filterNot { (text, power, _) -> text == TAG_ORIGINAL || power == PowerStatus.Weak }.map(GalleryTag::text)
                when (val ns = group.namespace) {
                    Artist, Cosplayer -> rawArtists.addAll(list)
                    Group -> rawGroups.addAll(list)
                    Character -> rawCharacters.addAll(list)
                    Female, Male, Mixed, Location, Other, Parody, Reclass, Language -> {
                        list.forEach { tag -> rawTags.add(ns to tag) }
                    }
                    else -> Unit
                }
            }
            else -> simpleTags?.forEach { tagString ->
                val (namespace, tag) = tagString.split(':', limit = 2)
                    .takeIf { it.size == 2 } ?: return@forEach
                when (val ns = from(namespace)) {
                    Artist, Cosplayer -> rawArtists.add(tag)
                    Group -> rawGroups.add(tag)
                    Character -> rawCharacters.add(tag)
                    Female, Male, Mixed, Location, Other, Parody, Reclass, Language -> {
                        if (ns != Parody || tag != TAG_ORIGINAL) {
                            rawTags.add(ns to tag)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    // 2. Conditional Translation
    val canTranslate = with(appCtx) {
        Settings.showTagTranslations.value && EhTagDatabase.translatable && EhTagDatabase.initialized
    }
    val ehTags = EhTagDatabase.takeIf { canTranslate }

    fun String.translate(ns: TagNamespace) = ehTags?.getTranslation(prefix = ns.prefix, tag = this) ?: this
    fun TagNamespace.translate() = ehTags?.getTranslation(tag = value) ?: value

    val translatedArtists = rawArtists.map { it.translate(Artist) }.distinct()
    val translatedGroups = rawGroups.map { it.translate(Group) }.distinct()
    val translatedCharacters = rawCharacters.map { it.translate(Character) }.distinct()
    val translatedTags = rawTags.map { (ns, tag) ->
        val nsText = if (canTranslate) ns.translate() else ns.value
        val tagText = tag.translate(ns)
        "$nsText:$tagText"
    }.distinct()

    val uploaderTag = uploader?.takeIf { it.isNotBlank() && !disowned }?.let { "uploader:$it" }
    val timestampTag = posted?.let {
        runCatching {
            val seconds = ParserUtils.parseDate(it) / 1000
            "timestamp:$seconds"
        }.getOrNull()
    }
    val finalTags = (translatedTags + listOfNotNull(uploaderTag, timestampTag)).distinct()

    val categoryStr = if (canTranslate) {
        val resId = CATEGORY_RES[category]
        if (resId != null) appCtx.getString(resId) else EhUtils.getCategory(category)
    } else {
        EhUtils.getCategory(category)
    }

    return ComicInfo(
        title = title,
        series = null,
        alternateSeries = titleJpn?.ifBlank { null },
        writer = translatedGroups.ifEmpty { null },
        penciller = translatedArtists.ifEmpty { null },
        genre = categoryStr,
        tags = finalTags.ifEmpty { null },
        web = EhUrl.getGalleryDetailUrl(gid, token),
        pageCount = pages,
        languageISO = simpleLanguage?.lowercase(),
        characters = translatedCharacters.ifEmpty { null },
        communityRating = "%.1f".format(rating),
    )
}

fun ComicInfo.toSimpleTags() = listOfNotNull(
    writer,
    penciller,
    genre?.let { listOf(it) },
    characters,
    tags,
).flatten().ifEmpty { null }

fun writeComicInfo(info: ComicInfo, file: Path) = file.write { xml.encodeToSink(this, info) }

fun readComicInfo(file: Path): ComicInfo? = runCatching {
    file.read {
        xml.decodeFromSource<ComicInfo>(this)
    }
}.getOrNull()

@Suppress("ktlint:standard:annotation")
typealias SimpleTags = @Serializable(SimpleTagsSerializer::class) List<String>

object SimpleTagsSerializer : KSerializer<SimpleTags> {
    private val converter = SimpleTagsConverter()
    override val descriptor = PrimitiveSerialDescriptor("SimpleTags", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder) = converter.fromString(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: SimpleTags) = encoder.encodeString(converter.toString(value))
}

@Serializable
data class ComicInfo(
    @XmlElement
    @SerialName("Title")
    val title: String?,

    @XmlElement
    @SerialName("Series")
    val series: String? = null,

    @XmlElement
    @SerialName("AlternateSeries")
    val alternateSeries: String? = null,

    @XmlElement
    @SerialName("Writer")
    val writer: SimpleTags? = null,

    @XmlElement
    @SerialName("Penciller")
    val penciller: SimpleTags? = null,

    @XmlElement
    @SerialName("Genre")
    val genre: String? = null,

    @XmlElement
    @SerialName("Tags")
    val tags: SimpleTags? = null,

    @XmlElement
    @SerialName("Web")
    val web: String? = null,

    @XmlElement
    @SerialName("PageCount")
    val pageCount: Int = 0,

    @XmlElement
    @SerialName("LanguageISO")
    val languageISO: String? = null,

    @XmlElement
    @SerialName("Characters")
    val characters: SimpleTags? = null,

    @XmlElement
    @SerialName("CommunityRating")
    val communityRating: String? = null,
) {
    @SerialName("xmlns:xsi")
    val xmlSchemaInstance: String = "http://www.w3.org/2001/XMLSchema-instance"

    @SerialName("xsi:noNamespaceSchemaLocation")
    val xmlSchemaLocation: String = "https://raw.githubusercontent.com/anansi-project/comicinfo/main/schema/v2.0/ComicInfo.xsd"
}
