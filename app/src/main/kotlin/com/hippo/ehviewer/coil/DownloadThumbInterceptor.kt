package com.hippo.ehviewer.coil

import coil3.Extras
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.EhApplication.Companion.thumbCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.downloadThumbLocation

private val downloadInfoKey = Extras.Key<DownloadInfo?>(default = null)

fun ImageRequest.Builder.downloadInfo(info: DownloadInfo) = apply {
    extras[downloadInfoKey] = info
}

val ImageRequest.downloadInfo: DownloadInfo?
    get() = getExtra(downloadInfoKey)

object DownloadThumbInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val info = chain.request.downloadInfo
        if (info != null && !info.dirname.isNullOrBlank()) {
            val thumbKey = getThumbKey(chain.request.data as String)
            if (info.thumbKey != thumbKey) {
                info.thumbKey = thumbKey
                EhDB.putGalleryInfo(info.galleryInfo)
            }
            val format = thumbKey.substringAfterLast('.', "")
            check(format.isNotBlank())

            val thumbDir = downloadThumbLocation
            val thumb = thumbDir / "${info.gid}.$format"
            val v1Thumb = thumbDir / "${info.gid}.jpg"
            if (thumb.isFile) {
                val new = chain.request.newBuilder().data(thumb.toUri()).build()
                val result = chain.withRequest(new).proceed()
                if (result is SuccessResult) {
                    if (thumb != v1Thumb) v1Thumb.delete()
                    return result
                }
            }

            // Fallback: check legacy gallery subfolder if it still exists
            val legacyDir = downloadLocation / info.dirname!!
            val legacyThumb = legacyDir / "thumb.$format"
            if (legacyThumb.isFile) {
                val new = chain.request.newBuilder().data(legacyThumb.toUri()).build()
                val result = chain.withRequest(new).proceed()
                if (result is SuccessResult) {
                    runCatching { legacyThumb sendTo thumb }
                    return result
                }
            }

            val result = chain.proceed()
            if (result is SuccessResult && thumbDir.isDirectory) {
                // Accessing the recreated file immediately after deleting it throws
                // FileNotFoundException, so we just overwrite the existing file.
                val key = requireNotNull(chain.request.memoryCacheKey)
                thumbCache.read(key) {
                    data sendTo thumb
                }
                if (thumb != v1Thumb) v1Thumb.delete()
            }
            return result
        }
        return chain.proceed()
    }
}
