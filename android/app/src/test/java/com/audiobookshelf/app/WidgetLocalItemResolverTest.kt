package com.audiobookshelf.app

import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.MediaTypeMetadata
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WidgetLocalItemResolverTest {
  private val target = WidgetTarget("Book", "book", "", "item-1", "server-1", null)

  private fun local(id: String = "download", itemId: String? = "item-1",
                    serverId: String? = "server-1", mediaType: String = "book") =
    LocalLibraryItem(id, "folder", "", "", "", false, mediaType,
      MediaType(MediaTypeMetadata("Book", false), null), mutableListOf(),
      null, null, false, serverId, null, null, itemId)

  @Test fun serverOnlyWidgetFindsDownloadAddedAfterPinning() {
    assertNull(WidgetLocalItemResolver.resolve(target, emptyList()))
    val download = local()
    assertSame(download, WidgetLocalItemResolver.resolve(target, listOf(download)))
  }

  @Test fun podcastServerWidgetAlsoFindsItsDownloadedShow() {
    val download = local(mediaType = "podcast")
    assertSame(download, WidgetLocalItemResolver.resolve(target.copy(mediaType = "podcast"), listOf(download)))
  }

  @Test fun deletedAndRedownloadedItemReplacesStaleLocalId() {
    val download = local(id = "replacement")
    assertSame(download, WidgetLocalItemResolver.resolve(target.copy(localItemId = "old"), listOf(download)))
  }

  @Test fun savedLocalIdIsPreferredAmongMatchingCopies() {
    val first = local(id = "first")
    val saved = local(id = "saved")
    assertSame(saved, WidgetLocalItemResolver.resolve(target.copy(localItemId = "saved"), listOf(first, saved)))
  }

  @Test fun anotherAccountItemOrMediaTypeIsNeverUsedEvenWithSameTitleOrLocalId() {
    val savedTarget = target.copy(localItemId = "download")
    listOf(local(serverId = "other-account"), local(itemId = "other-item"),
      local(mediaType = "podcast"), local(serverId = null)).forEach {
      assertNull(WidgetLocalItemResolver.resolve(savedTarget, listOf(it)))
    }
    assertNull(WidgetLocalItemResolver.resolve(target.copy(serverConfigId = null), listOf(local())))
  }

  @Test fun purelyLocalWidgetRequiresItsOwnId() {
    val download = local(itemId = null, serverId = null)
    val localTarget = target.copy(serverItemId = null, serverConfigId = null, localItemId = download.id)
    assertSame(download, WidgetLocalItemResolver.resolve(localTarget, listOf(download)))
    assertNull(WidgetLocalItemResolver.resolve(localTarget.copy(localItemId = "gone"), listOf(download)))
  }

  @Test fun invalidDownloadIsNotSelected() {
    val download = local().apply { isInvalid = true }
    assertNull(WidgetLocalItemResolver.resolve(target, listOf(download)))
  }
}
