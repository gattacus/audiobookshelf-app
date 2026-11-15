package com.audiobookshelf.app.media

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.plugins.AbsLogger
import com.getcapacitor.JSObject
import java.util.*
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MediaManager(
  private var apiHandler: ApiHandler,
  var ctx: Context,
  private val browseLoadTimeoutMs: Long = 15_000L
) {
  val tag = "MediaManager"

  private var serverLibraryItems = mutableListOf<LibraryItem>() // Store all items here

  private var cachedLibraryAuthors : MutableMap<String, MutableMap<String, LibraryAuthorItem>> = hashMapOf()
  private var cachedLibraryAuthorItems : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibraryAuthorSeriesItems : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibrarySeries : MutableMap<String, List<LibrarySeriesItem>> = hashMapOf()
  private var cachedLibrarySeriesItem : MutableMap<String, MutableMap<String, List<LibraryItem>>> = hashMapOf()
  private var cachedLibraryCollections : MutableMap<String, MutableMap<String, LibraryCollection>> = hashMapOf()
  private var cachedLibraryRecentShelves : MutableMap<String, MutableList<LibraryShelfType>> = hashMapOf()
  private var cachedLibraryDiscovery : MutableMap<String, MutableList<LibraryItem>> = hashMapOf()
  private var cachedLibraryPodcasts : MutableMap<String, MutableMap<String, LibraryItem>> = hashMapOf()
  private var isLibraryPodcastsCached : MutableMap<String, Boolean> = hashMapOf()
  var allLibraryPersonalizationsDone : Boolean = false
  private var libraryPersonalizationsDone : Int = 0

  private var selectedPodcast:Podcast? = null
  private var selectedLibraryItemId:String? = null
  private var podcastEpisodeLibraryItemMap = mutableMapOf<String, LibraryItemWithEpisode>()
  private var serverBrowseContext: String? = null
  var serverUserMediaProgress:MutableList<MediaProgress> = mutableListOf()
  private val androidAutoLibraryCaches = mutableMapOf<String, AndroidAutoBrowseCache<List<ItemInProgress>>>()
  private val browseHandler = Handler(Looper.getMainLooper())
  val serverItemsInProgress: List<ItemInProgress>
    get() = AndroidAutoContinueItems.merge(serverLibraries.map {
      androidAutoLibraryCaches[it.id]?.value.orEmpty()
    })
  var serverLibraries = listOf<Library>()

  var userSettingsPlaybackRate:Float? = null

  private fun configKey(config: ServerConnectionConfig?): String? = config?.let {
    "${it.id}|${it.address}|${it.userId}"
  }

  private fun browseConfig(): ServerConnectionConfig? =
    if (DeviceManager.deviceData.lastServerConnectionConfigId.isNullOrBlank()) null
    else DeviceManager.serverConnectionConfig ?: DeviceManager.deviceData.getLastServerConnectionConfig()

  private fun browseContextKey(): String? = browseConfig()?.let {
    "${DeviceManager.deviceData.lastServerConnectionConfigId}|${configKey(it)}"
  }

  private fun newBrowseLoad(label: String) = AndroidAutoLoadCoordinator(
    contextKey = { browseContextKey() },
    now = { SystemClock.elapsedRealtime() },
    dispatch = { action -> browseHandler.post { action() } },
    schedule = { delay, action ->
      val task = Runnable { action() }
      browseHandler.postDelayed(task, delay)
      val cancel: () -> Unit = { browseHandler.removeCallbacks(task) }
      cancel
    },
    timeoutMs = browseLoadTimeoutMs,
    log = { message ->
      try { AbsLogger.info(tag, "Android Auto $label $message") }
      catch (error: Exception) { Log.e(tag, "Could not retain Android Auto diagnostics: ${error.javaClass.simpleName}") }
    }
  )

  private val androidAutoLoad by lazy { newBrowseLoad("initialization") }
  private val androidAutoShelfLoads = mutableMapOf<String, AndroidAutoLoadCoordinator>()

  fun closeAndroidAutoBrowse() {
    androidAutoLoad.invalidate()
    androidAutoShelfLoads.values.toList().forEach { it.invalidate() }
    androidAutoShelfLoads.clear()
    browseHandler.removeCallbacksAndMessages(null)
  }

  fun getIsLibrary(id:String) : Boolean {
    return serverLibraries.find { it.id == id } != null
  }

  /**
   * Check if there is discovery shelf for [libraryId]
   * If personalized shelves are not yet populated for library then populate
   *
   */
  fun getHasDiscovery(libraryId: String) : Boolean {
    if (cachedLibraryDiscovery.containsKey(libraryId)) {
      if (cachedLibraryDiscovery[libraryId]!!.isNotEmpty()) {
        return true
      }
    } else {
      populatePersonalizedDataForLibrary(libraryId){}
    }
    return false
  }

  fun getLibrary(id:String) : Library? {
    return serverLibraries.find { it.id == id }
  }

  /**
   * Add [libraryItem] to [serverLibraryItems] if it is not already added
   */
  private fun addServerLibrary(libraryItem: LibraryItem) {
    if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
      serverLibraryItems.add(libraryItem)
    }
  }

  fun getSavedPlaybackRate():Float {
    if (userSettingsPlaybackRate != null) {
      return userSettingsPlaybackRate ?: 1f
    }

    val sharedPrefs = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    if (sharedPrefs != null) {
      val userSettingsPref = sharedPrefs.getString("userSettings", null)
      if (userSettingsPref != null) {
        try {
          val userSettings = JSObject(userSettingsPref)
          if (userSettings.has("playbackRate")) {
            userSettingsPlaybackRate = userSettings.getDouble("playbackRate").toFloat()
            return userSettingsPlaybackRate ?: 1f
          }
        } catch(je:JSONException) {
          Log.e(tag, "Failed to parse userSettings JSON ${je.localizedMessage}")
        }
      }
    }
    return 1f
  }

  fun setSavedPlaybackRate(newRate: Float) {
    val sharedPrefs = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    val sharedPrefEditor = sharedPrefs.edit()
    if (sharedPrefs != null) {
      val userSettingsPref = sharedPrefs.getString("userSettings", null)
      if (userSettingsPref != null) {
        try {
          val userSettings = JSObject(userSettingsPref)
          // toString().toDouble() to prevent float conversion issues (ex 1.2f becomes 1.2000000476837158d)
          userSettings.put("playbackRate", newRate.toString().toDouble())
          sharedPrefEditor.putString("userSettings", userSettings.toString())
          sharedPrefEditor.apply()
          userSettingsPlaybackRate = newRate
          Log.d(tag, "Saved userSettings JSON from Android Auto with playbackRate=$newRate")
        } catch(je:JSONException) {
          Log.e(tag, "Failed to save userSettings JSON ${je.localizedMessage}")
        }
      } else {
        // Not sure if this is the best place for this, but if a user has not changed any user settings in the app
        // the object will not exist yet, could be moved to a centralized place or created on first app load
        val userSettings = JSONObject()
        userSettings.put("playbackRate", newRate.toString().toDouble())
        sharedPrefEditor.putString("userSettings", userSettings.toString())
        userSettingsPlaybackRate = newRate
        Log.d(tag, "Created and saved userSettings JSON from Android Auto with playbackRate=$newRate")
      }
    }
  }

  fun checkResetServerItems():Boolean {
    // A network interruption must not erase a successful snapshot for the same account.
    val context = browseContextKey()
    if (context != serverBrowseContext) {
      serverBrowseContext = context
      androidAutoLoad.invalidate()
      clearServerBrowseItems()
      return true
    }
    return false
  }

  private fun clearServerBrowseItems() {
    androidAutoShelfLoads.values.toList().forEach { it.invalidate() }
    androidAutoShelfLoads.clear()
    podcastEpisodeLibraryItemMap = mutableMapOf()
    selectedPodcast = null
    selectedLibraryItemId = null
    serverUserMediaProgress = mutableListOf()
    serverLibraries = listOf()
    serverLibraryItems = mutableListOf()
    cachedLibraryAuthors = hashMapOf()
    cachedLibraryAuthorItems = hashMapOf()
    cachedLibraryAuthorSeriesItems = hashMapOf()
    cachedLibrarySeries = hashMapOf()
    cachedLibrarySeriesItem = hashMapOf()
    cachedLibraryCollections = hashMapOf()
    cachedLibraryRecentShelves = hashMapOf()
    cachedLibraryDiscovery = hashMapOf()
    cachedLibraryPodcasts = hashMapOf()
    isLibraryPodcastsCached = hashMapOf()
    androidAutoLibraryCaches.clear()
    allLibraryPersonalizationsDone = false
    libraryPersonalizationsDone = 0
  }

  /**
   * Load personalized shelves from server for all libraries.
   * [cb] resolves when all libraries are processed
   */
  fun populatePersonalizedDataForAllLibraries(cb: (Boolean) -> Unit) {
    val libraries = serverLibraries.toList()
    if (libraries.isEmpty()) return cb(false)
    var remaining = libraries.size
    var changed = false
    libraries.forEach { lib ->
      populatePersonalizedDataForLibrary(lib.id) { loaded ->
        changed = changed || loaded
        if (--remaining == 0) {
          allLibraryPersonalizationsDone = serverLibraries.all {
            androidAutoLibraryCaches[it.id]?.hasValue == true
          }
          cb(changed)
        }
      }
    }
  }

  fun nextAndroidAutoBrowseRefreshDelayMs(): Long? = serverLibraries.minOfOrNull {
    androidAutoLibraryCaches[it.id]?.nextLoadDelayMs(SystemClock.elapsedRealtime()) ?: 0L
  }

  /**
   * Get personalized shelves from server for selected [libraryId].
   * Populates [cachedLibraryRecentShelves] and [cachedLibraryDiscovery].
   */
  private fun populatePersonalizedDataForLibrary(libraryId: String, cb: (Boolean) -> Unit) {
    val cache = androidAutoLibraryCaches.getOrPut(libraryId) { AndroidAutoBrowseCache(emptyList()) }
    val requestId = cache.beginLoad(SystemClock.elapsedRealtime()) ?: return cb(false)
    val loader = androidAutoShelfLoads.getOrPut(libraryId) { newBrowseLoad("shelves[$libraryId]") }
    var changed = false
    loader.load({ success ->
      if (!success && androidAutoLibraryCaches[libraryId] === cache) {
        cache.complete(requestId, null, SystemClock.elapsedRealtime())
      }
      cb(changed)
    }) { load ->
      load.stage<List<LibraryShelfType>?>("personalized", { done ->
        apiHandler.getLibraryPersonalized(libraryId, done)
      }) { shelves ->
        if (androidAutoLibraryCaches[libraryId] !== cache) {
          load.complete(false, "cache_reset")
          return@stage
        }
        val continueItems = shelves?.let { AndroidAutoContinueItems.fromShelves(it, serverUserMediaProgress) }
        if (shelves == null) {
          load.complete(false, "unavailable")
          return@stage
        }
        continueItems.orEmpty().forEach { item ->
          val libraryItem = item.libraryItemWrapper as LibraryItem
          if (serverLibraryItems.none { it.id == libraryItem.id }) serverLibraryItems.add(libraryItem)
          item.episode?.let { podcastEpisode ->
            podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItem, podcastEpisode)
          }
        }
        // A successful refresh replaces the snapshot, including genuinely empty shelves.
        cachedLibraryRecentShelves.remove(libraryId)
        cachedLibraryDiscovery.remove(libraryId)
        shelves.map { shelf ->
          Log.d(tag, "$shelf")
          if (shelf.type == "book") {
            if (shelf.id == "continue-listening") return@map
            else if (shelf.id == "listen-again") return@map
            else if (shelf.id == "recently-added") {
              if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                cachedLibraryRecentShelves[libraryId] = mutableListOf()
              }
              if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                cachedLibraryRecentShelves[libraryId]!!.add(shelf)
              }
            }
            else if (shelf.id == "discover") {
              if (!cachedLibraryDiscovery.containsKey(libraryId)) {
                cachedLibraryDiscovery[libraryId] = mutableListOf()
              }
              (shelf as LibraryShelfBookEntity).entities?.map {
                cachedLibraryDiscovery[libraryId]!!.add(it)
              }
            }
            else if (shelf.id == "continue-reading") return@map
            else if (shelf.id == "continue-series") return@map
            shelf as LibraryShelfBookEntity
          } else if (shelf.type == "series") {
            if (shelf.id == "recent-series") {
              if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                cachedLibraryRecentShelves[libraryId] = mutableListOf()
              }
              if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                cachedLibraryRecentShelves[libraryId]!!.add(shelf)
              }
            }
          } else if (shelf.type == "episode") {
            if (shelf.id == "continue-listening") return@map
            else if (shelf.id == "listen-again") return@map
            else if (shelf.id == "newest-episodes") {
              if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                cachedLibraryRecentShelves[libraryId] = mutableListOf()
              }
              if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                cachedLibraryRecentShelves[libraryId]!!.add(shelf)
              }

              val podcastLibraryItemIds = mutableListOf<String>()
              (shelf as LibraryShelfEpisodeEntity).entities?.forEach { libraryItem ->
                if (!podcastLibraryItemIds.contains(libraryItem.id)) {
                  podcastLibraryItemIds.add(libraryItem.id)
                  loadPodcastItem(libraryItem.libraryId, libraryItem.id) {}
                }
              }
            }
          } else if (shelf.type == "podcast") {
            if (shelf.id == "recently-added"){
              if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                cachedLibraryRecentShelves[libraryId] = mutableListOf()
              }
              if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                cachedLibraryRecentShelves[libraryId]!!.add(shelf)
              }
            }
            else if (shelf.id == "discover"){
              return@map
            }
          } else if (shelf.type =="authors") {
            if (shelf.id == "newest-authors") {
              if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                cachedLibraryRecentShelves[libraryId] = mutableListOf()
              }
              if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                cachedLibraryRecentShelves[libraryId]!!.add(shelf)
              }
            }
          }

        }
        cache.complete(requestId, continueItems, SystemClock.elapsedRealtime())
        changed = true
        load.complete(true, "loaded")
      }
    }
  }

  /**
   * Returns podcasts for selected library.
   * If data is not found from local cache it is loaded from server
   */
  fun loadLibraryPodcasts(libraryId:String, cb: (List<LibraryItem>?) -> Unit) {
    // Without this there is possibility that only recent podcasts get loaded
    // Loading recent podcasts will also create cachedLibraryPodcasts entry for library
    if (!isLibraryPodcastsCached.containsKey(libraryId)) {
      isLibraryPodcastsCached[libraryId] = false
    }
    // Ensure that there is map for library
    if (!cachedLibraryPodcasts.containsKey(libraryId)) {
      cachedLibraryPodcasts[libraryId] = mutableMapOf()
    }
    if (isLibraryPodcastsCached.getOrElse(libraryId) {false}) {
      Log.d(tag, "loadLibraryPodcasts: Found from cache: $libraryId")
      cb(cachedLibraryPodcasts[libraryId]?.values?.sortedBy { libraryItem -> (libraryItem.media as Podcast).metadata.title })
    } else {
      apiHandler.getLibraryItems(libraryId) { libraryItems ->
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        libraryItemsWithAudio.forEach { libraryItem ->
          cachedLibraryPodcasts[libraryId]?.set(libraryItem.id, libraryItem)
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }
        isLibraryPodcastsCached[libraryId] = true
        Log.d(tag, "loadLibraryPodcasts: loaded from server: $libraryId")
        cb(libraryItemsWithAudio.sortedBy { libraryItem -> (libraryItem.media as Podcast).metadata.title })
      }
    }
  }

  /**
   *  Returns series with audio books from selected library.
   *  If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesWithAudio(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    // Check "cache" first
    if (cachedLibrarySeries.containsKey(libraryId)) {
      Log.d(tag, "Series with audio found from cache | Library $libraryId ")
      cb(cachedLibrarySeries[libraryId] as List<LibrarySeriesItem>)
    } else {
      apiHandler.getLibrarySeries(libraryId) { seriesItems ->
        Log.d(tag, "Series with audio loaded from server | Library $libraryId")
        val seriesItemsWithAudio = seriesItems.filter { si -> si.audiobookCount > 0 }

        cachedLibrarySeries[libraryId] = seriesItemsWithAudio

        cb(seriesItemsWithAudio)
      }
    }
  }

  /**
   * Returns series with audiobooks from selected library using filter for paging.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesWithAudio(libraryId:String, seriesFilter:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    // Check "cache" first
    if (!cachedLibrarySeries.containsKey(libraryId)) {
      loadLibrarySeriesWithAudio(libraryId) {}
    } else {
      Log.d(tag, "Series with audio found from cache | Library $libraryId ")
    }
    val seriesWithBooks = cachedLibrarySeries[libraryId]!!.filter { ls -> ls.title.uppercase().startsWith(seriesFilter) }.toList()
    cb(seriesWithBooks)
  }

  /**
   * Sorts books in series. Assumes that sequence is main.minor
   */
  private fun sortSeriesBooks(seriesBooks: List<LibraryItem>) : List<LibraryItem> {
    val sortingLogic = compareBy<LibraryItem> { it.seriesSequenceParts[0].length }
      .thenBy { it.seriesSequenceParts[0].ifEmpty { "" } }
      .thenBy { it.seriesSequenceParts.getOrElse(1) { "" }.length }
      .thenBy { it.seriesSequenceParts.getOrElse(1) { "" } }
    return seriesBooks.sortedWith(sortingLogic)
  }

  /**
   * Returns books for series from library.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesItemsWithAudio(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    // Check "cache" first
    if (!cachedLibrarySeriesItem.containsKey(libraryId)) {
      cachedLibrarySeriesItem[libraryId] = hashMapOf()
    }
    if (cachedLibrarySeriesItem[libraryId]!!.containsKey(seriesId)) {
      Log.d(tag, "Items for series $seriesId found from cache | Library $libraryId")
      cachedLibrarySeriesItem[libraryId]!![seriesId]?.let { cb(it) }
    } else {
      apiHandler.getLibrarySeriesItems(libraryId, seriesId) { libraryItems ->
        Log.d(tag, "Items for series $seriesId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        val sortedLibraryItemsWithAudio = sortSeriesBooks(libraryItemsWithAudio)
        cachedLibrarySeriesItem[libraryId]!![seriesId] = sortedLibraryItemsWithAudio

        sortedLibraryItemsWithAudio.forEach { libraryItem ->
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }
        cb(sortedLibraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns authors with books from library.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorsWithBooks(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    // Check "cache" first
    if (cachedLibraryAuthors.containsKey(libraryId)) {
      Log.d(tag, "Authors with books found from cache | Library $libraryId ")
      cb(cachedLibraryAuthors[libraryId]!!.values.toList())
    } else {
      // Fetch data from server and add it to local "cache"
      apiHandler.getLibraryAuthors(libraryId) { authorItems ->
        Log.d(tag, "Authors with books loaded from server | Library $libraryId ")
        // TO-DO: This check won't ensure that there is audiobooks. Current API won't offer ability to do so
        var authorItemsWithBooks = authorItems.filter { li -> li.bookCount != null && li.bookCount!! > 0 }
        authorItemsWithBooks = authorItemsWithBooks.sortedBy { it.name }
        // Ensure that there is map for library
        cachedLibraryAuthors[libraryId] = mutableMapOf()
        // Cache authors
        authorItemsWithBooks.forEach {
          if (!cachedLibraryAuthors[libraryId]!!.containsKey(it.id)) {
            cachedLibraryAuthors[libraryId]!![it.id] = it
          }
        }
        cb(authorItemsWithBooks)
      }
    }
  }

  /**
   * Returns authors with books from selected library using filter for paging.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorsWithBooks(libraryId:String, authorFilter: String, cb: (List<LibraryAuthorItem>) -> Unit) {
    // Check "cache" first
    if (cachedLibraryAuthors.containsKey(libraryId)) {
      Log.d(tag, "Authors with books found from cache | Library $libraryId ")
    } else {
      loadAuthorsWithBooks(libraryId) {}
    }
    val authorsWithBooks = cachedLibraryAuthors[libraryId]!!.values.filter { lai -> lai.name.uppercase().startsWith(authorFilter) }.toList()
    cb(authorsWithBooks)
  }

  /**
   * Returns audiobooks for author from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorBooksWithAudio(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    // Ensure that there is map for library
    if (!cachedLibraryAuthorItems.containsKey(libraryId)) {
        cachedLibraryAuthorItems[libraryId] = mutableMapOf()
    }
    // Check "cache" first
    if (cachedLibraryAuthorItems[libraryId]!!.containsKey(authorId)) {
      Log.d(tag, "Items for author $authorId found from cache | Library $libraryId")
      cachedLibraryAuthorItems[libraryId]!![authorId]?.let { cb(it) }
    } else {
      apiHandler.getLibraryItemsFromAuthor(libraryId, authorId) { libraryItems ->
        Log.d(tag, "Items for author $authorId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        cachedLibraryAuthorItems[libraryId]!![authorId]  = libraryItemsWithAudio

        libraryItemsWithAudio.forEach { libraryItem ->
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }

        cb(libraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns audiobooks for author from specified series within library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorSeriesBooksWithAudio(libraryId:String, authorId:String, seriesId: String, cb: (List<LibraryItem>) -> Unit) {
    val authorSeriesKey = "$authorId|$seriesId"
    // Ensure that there is map for library
    if (!cachedLibraryAuthorSeriesItems.containsKey(libraryId)) {
      cachedLibraryAuthorSeriesItems[libraryId] = mutableMapOf()
    }
    // Check "cache" first
    if (cachedLibraryAuthorSeriesItems[libraryId]!!.containsKey(authorSeriesKey)) {
      Log.d(tag, "Items for series $seriesId with author $authorId found from cache | Library $libraryId")
      cachedLibraryAuthorSeriesItems[libraryId]!![authorSeriesKey]?.let { cb(it) }
    } else {
      apiHandler.getLibrarySeriesItems(libraryId, seriesId) { libraryItems ->
        Log.d(tag, "Items for series $seriesId with author $authorId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }
        if (!cachedLibraryAuthors[libraryId]!!.containsKey(authorId)) {
          Log.d(tag, "Author data is missing")
        }
        val authorName = cachedLibraryAuthors[libraryId]!![authorId]?.name ?: ""
        Log.d(tag, "Using author name: $authorName")
        val libraryItemsFromAuthorWithAudio = libraryItemsWithAudio.filter { li -> li.authorName.indexOf(authorName, ignoreCase = true) >= 0 }

        val sortedLibraryItemsWithAudio = sortSeriesBooks(libraryItemsFromAuthorWithAudio)
        cachedLibraryAuthorSeriesItems[libraryId]!![authorId] = sortedLibraryItemsWithAudio

        sortedLibraryItemsWithAudio.forEach { libraryItem ->
          if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
            serverLibraryItems.add(libraryItem)
          }
        }

        cb(sortedLibraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns collections with audiobooks from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryCollectionsWithAudio(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    if (cachedLibraryCollections.containsKey(libraryId)) {
      Log.d(tag, "Collections with books found from cache | Library $libraryId ")
      cb(cachedLibraryCollections[libraryId]!!.values.toList())
    } else {
      apiHandler.getLibraryCollections(libraryId) { libraryCollections ->
        Log.d(tag, "Collections with books loaded from server | Library $libraryId ")
        val libraryCollectionsWithAudio = libraryCollections.filter { lc -> lc.audiobookCount > 0 }

        // Cache collections
        cachedLibraryCollections[libraryId] = hashMapOf()
        libraryCollectionsWithAudio.forEach {
          if (!cachedLibraryCollections[libraryId]!!.containsKey(it.id)) {
            cachedLibraryCollections[libraryId]!![it.id] = it
          }
        }
        cb(libraryCollectionsWithAudio)
      }
    }
  }

  /**
   * Returns audiobooks for collection from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryCollectionBooksWithAudio(libraryId: String, collectionId: String, cb: (List<LibraryItem>) -> Unit) {
    if (!cachedLibraryCollections.containsKey(libraryId)) {
      loadLibraryCollectionsWithAudio(libraryId) {}
    }
    Log.d(tag, "Trying to find collection $collectionId items from from cache | Library $libraryId ")
    if ( cachedLibraryCollections[libraryId]!!.containsKey(collectionId)) {
      val libraryCollectionBookswithAudio = cachedLibraryCollections[libraryId]!![collectionId]?.books
      libraryCollectionBookswithAudio?.forEach { libraryItem ->
        if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
          serverLibraryItems.add(libraryItem)
        }
      }
      cb(libraryCollectionBookswithAudio as List<LibraryItem>)
    }
  }

  /**
   * Returns audiobooks from discovery shelf for [libraryId]
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryDiscoveryBooksWithAudio(libraryId: String, cb: (List<LibraryItem>) -> Unit) {
    if (!cachedLibraryDiscovery.containsKey(libraryId)) {
      cb(listOf())
    }
    val libraryItemsWithAudio = cachedLibraryDiscovery[libraryId]?.filter { li -> li.checkHasTracks() }
    libraryItemsWithAudio?.forEach { libraryItem -> addServerLibrary(libraryItem) }
    cb(libraryItemsWithAudio as List<LibraryItem>)
  }

  /**
   * Returns recent shelves for [libraryId]
   * If data is not shelves are found returns empty list
   */
  fun getLibraryRecentShelfs(libraryId: String, cb: (List<LibraryShelfType>) -> Unit) {
    if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
      Log.d(tag, "getLibraryRecentShelfs: No shelves $libraryId")
      cb(listOf())
      return
    }
    cb(cachedLibraryRecentShelves[libraryId] as List<LibraryShelfType>)
  }

  /**
   * Returns recent shelf by [type] for [libraryId]
   * If shelf is not found returns null
   */
  fun getLibraryRecentShelfByType(libraryId: String, type:String, cb: (LibraryShelfType?) -> Unit) {
    Log.d(tag, "getLibraryRecentShelfByType: $libraryId | $type")
    if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
      cb(null)
      return
    }
    for (shelf in cachedLibraryRecentShelves[libraryId]!!) {
      if (shelf.type == type.lowercase()) {
        cb(shelf)
        return
      }
    }
    cb(null)
  }

  /**
   * Loads podcasts for newest episodes shelf
   */
  private fun loadPodcastItem(libraryId: String, libraryItemId: String, cb: (LibraryItem?) -> Unit) {
    // Ensure that there is map for library
    if (!cachedLibraryPodcasts.containsKey(libraryId)) {
      cachedLibraryPodcasts[libraryId] = mutableMapOf()
    }
    if (cachedLibraryPodcasts[libraryId]!!.containsKey(libraryItemId)) {
      Log.d(tag, "loadPodcastItem: Podcast found from cache | Library $libraryItemId ")
      cb(cachedLibraryPodcasts[libraryId]?.get(libraryItemId))
    } else {
      Log.d(tag, "loadPodcastItem: Calling getLibraryItem $libraryItemId")
      apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
        if (libraryItem !== null) {
          Log.d(tag, "loadPodcastItem: Got library item ${libraryItem.id} ${libraryItem.media.metadata.title}")
          val podcast = libraryItem.media as Podcast
          podcast.episodes?.forEach { podcastEpisode ->
            podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItem, podcastEpisode)
          }
          cachedLibraryPodcasts[libraryId]?.set(libraryItemId, libraryItem)
          cb(libraryItem)
        }
      }
    }
  }

  private fun loadLibraryItem(libraryItemId:String, cb: (LibraryItemWrapper?) -> Unit) {
    if (libraryItemId.startsWith("local")) {
      cb(DeviceManager.dbManager.getLocalLibraryItem(libraryItemId))
    } else {
      Log.d(tag, "loadLibraryItem: $libraryItemId")
      apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
        Log.d(tag, "loadLibraryItem: Got library item $libraryItem")
        cb(libraryItem)
      }
    }
  }

  fun loadPodcastEpisodeMediaBrowserItems(libraryItemId:String, ctx:Context, cb: (MutableList<MediaBrowserCompat.MediaItem>) -> Unit) {
      loadLibraryItem(libraryItemId) { libraryItemWrapper ->
        Log.d(tag, "Loaded Podcast library item $libraryItemWrapper")

        libraryItemWrapper?.let {
          if (libraryItemWrapper is LocalLibraryItem) { // Local podcast episodes
            if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
              cb(mutableListOf())
            } else {
              val podcast = libraryItemWrapper.media as Podcast
              selectedLibraryItemId = libraryItemWrapper.id
              selectedPodcast = podcast

              val children = podcast.episodes?.map { podcastEpisode ->
                Log.d(tag, "Local Podcast Episode ${podcastEpisode.title} | ${podcastEpisode.id}")

                val progress = DeviceManager.dbManager.getLocalMediaProgress("${libraryItemWrapper.id}-${podcastEpisode.id}")
                val description = podcastEpisode.getMediaDescription(libraryItemWrapper, progress, ctx)

                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
              }
              children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
            }
          } else if (libraryItemWrapper is LibraryItem) { // Server podcast episodes
            if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
              cb(mutableListOf())
            } else {
              val podcast = libraryItemWrapper.media as Podcast
              podcast.episodes?.forEach { podcastEpisode ->
                podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItemWrapper, podcastEpisode)
              }
              selectedLibraryItemId = libraryItemWrapper.id
              selectedPodcast = podcast
              val episodes = podcast.episodes?.sortedByDescending { it.publishedAt }
              val children = episodes?.map { podcastEpisode ->

                val progress = serverUserMediaProgress.find { it.libraryItemId == libraryItemWrapper.id && it.episodeId == podcastEpisode.id }

                // to show download icon
                val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItemWrapper.id)
                localLibraryItem?.let { lli ->
                  val localEpisode = (lli.media as Podcast).episodes?.find { it.serverEpisodeId == podcastEpisode.id }
                  podcastEpisode.localEpisodeId = localEpisode?.id
                }

                val description = podcastEpisode.getMediaDescription(libraryItemWrapper, progress, ctx)
                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
              }
              children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
            }
          }
        }
      }
  }

  fun loadServerUserMediaProgress(cb: () -> Unit) {
    Log.d(tag, "Loading server media progress")
    if (DeviceManager.serverConnectionConfig == null) {
      return cb()
    }

    DeviceManager.serverConnectionConfig?.let { config ->
      apiHandler.syncLocalSessionsAndProgress { _, _ ->
        apiHandler.authorize(config) {
          Log.d(tag, "loadServerUserMediaProgress: Authorized server config ${config.address} result = $it")
          if (!it.isNullOrEmpty()) {
            serverUserMediaProgress = it
          }
          cb()
        }
      }
    }
  }

  /** Network calls are asynchronous; all state and completion handling stay on the main thread. */
  fun loadAndroidAutoItems(cb: (Boolean) -> Unit) {
    androidAutoLoad.load(cb) { load ->
      val selected = browseConfig()?.copy()
      if (selected == null || !DeviceManager.checkConnectivity(ctx)) {
        load.complete(false, "offline")
        return@load
      }
      // Preserve saved connection fallback, but avoid pinging the selected address twice.
      val candidates = (listOf(selected) + DeviceManager.deviceData.serverConnectionConfigs.map { it.copy() })
        .distinctBy { configKey(it) }

      fun loadFrom(config: ServerConnectionConfig) {
        load.adoptContext {
          if (configKey(DeviceManager.serverConnectionConfig) != configKey(config)) {
            DeviceManager.serverConnectionConfig = DeviceManager.getServerConnectionConfig(config.id)
              ?.takeIf { configKey(it) == configKey(config) } ?: config
            serverBrowseContext = browseContextKey()
            clearServerBrowseItems()
          }
        }
        load.stage<Pair<Boolean, String?>>("progress_sync", { done ->
          // Sync retains its own pending records. Stop waiting after 3s so a stuck sync
          // (including a coalesced retry) cannot prevent authorization and browsing.
          val timeout = Runnable { done(false to "wait_timeout") }
          browseHandler.postDelayed(timeout, 3_000L)
          apiHandler.syncLocalSessionsAndProgress { success, error ->
            browseHandler.removeCallbacks(timeout)
            done(success to error)
          }
        }) { syncResult ->
          load.diagnostic("progress_sync_success=${syncResult.first}")
          if (syncResult.second == "wait_timeout") load.diagnostic("progress_sync_wait_timeout")
          // A failed progress upload must not prevent browsing. The sync layer retains it for retry.
          fun snapshot() = DeviceManager.getServerConnectionConfig(config.id)
            ?.takeIf { configKey(it) == configKey(config) }?.copy() ?: config.copy()
          load.stage<MutableList<MediaProgress>?>("authorize", { done -> apiHandler.authorize(snapshot(), done) }) { progress ->
            if (progress == null) {
              load.complete(false, "authorization_failed")
            } else {
              load.stage<List<Library>?>("libraries", { done -> apiHandler.getLibraries(snapshot(), done) }) { libraries ->
                if (libraries == null) {
                  load.complete(false, "libraries_failed")
                } else {
                  // Commit only after all stages, while this load still owns the current account.
                  serverUserMediaProgress = progress
                  serverLibraries = libraries
                  load.complete(true, "loaded")
                }
              }
            }
          }
        }
      }

      fun ping(index: Int) {
        if (index >= candidates.size) {
          load.complete(false, "unreachable")
          return
        }
        val config = candidates[index]
        load.stage<Boolean>("ping[$index]", { done -> apiHandler.pingServer(config, done) }) { reachable ->
          load.diagnostic("reachable=$reachable")
          if (reachable) loadFrom(config) else ping(index + 1)
        }
      }
      ping(0)
    }
  }

  /**
   * Handles search requests.
   * Searches from books, series and authors
   */
  suspend fun doSearch(libraryId: String, queryString: String) : Map<String, List<MediaBrowserCompat.MediaItem>> {
    return suspendCoroutine {
      apiHandler.getSearchResults(libraryId, queryString) { searchResult ->
        Log.d(tag, "searchLocalCache: $searchResult")
        // Nothing found from server
        if (searchResult === null) {
          it.resume(mapOf())
          return@getSearchResults
        }

        val foundItems: MutableMap<String, List<MediaBrowserCompat.MediaItem>> = mutableMapOf()

        val serverLibrary = serverLibraries.find { sl -> sl.id == libraryId }

        // Books
        if (searchResult.book !== null && searchResult.book!!.isNotEmpty()) {
          Log.d(tag, "searchLocalCache: found ${searchResult.book!!.size} books")
          val children = searchResult.book!!.filter { it.libraryItem.checkHasTracks() }.map { bookResult ->
            val libraryItem = bookResult.libraryItem

            if (serverLibraryItems.find { li -> li.id == libraryItem.id } == null) {
              serverLibraryItems.add(libraryItem)
            }
            val progress = serverUserMediaProgress.find { it.libraryItemId == libraryItem.id }
            val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
            libraryItem.localLibraryItemId = localLibraryItem?.id
            val description = libraryItem.getMediaDescription(progress, ctx, null, null, "Books (${serverLibrary?.name})")
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
          }
          foundItems["book"] = children
        }
        if (searchResult.series !== null && searchResult.series!!.isNotEmpty()) {
          Log.d(tag, "onSearch: found ${searchResult.series!!.size} series")
          val children = searchResult.series!!.map { seriesResult ->
            val seriesItem = seriesResult.series
            seriesItem.books = seriesResult.books as MutableList<LibraryItem>
            val description = seriesItem.getMediaDescription(null, ctx, "Series (${serverLibrary?.name})")
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
          }
          foundItems["series"] = children
        }
        if (searchResult.authors !== null && searchResult.authors!!.isNotEmpty()) {
          Log.d(tag, "onSearch: found ${searchResult.authors!!.size} authors")
          val children = searchResult.authors!!.map { authorItem ->
            val description = authorItem.getMediaDescription(null, ctx, "Authors (${serverLibrary?.name})")
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
          }
          foundItems["authors"] = children
        }

        it.resume(foundItems)
      }
    }
  }

  fun getFirstItem() : LibraryItemWrapper? {
    if (serverLibraryItems.isNotEmpty()) {
      return serverLibraryItems[0]
    } else {
      val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
      return if (localBooks.isNotEmpty()) return localBooks[0] else null
    }
  }

  fun getPodcastWithEpisodeByEpisodeId(id:String) : LibraryItemWithEpisode? {
    return if (id.startsWith("local")) {
      DeviceManager.dbManager.getLocalLibraryItemWithEpisode(id)
    } else {
      podcastEpisodeLibraryItemMap[id]
    }
  }

  fun getById(id:String) : LibraryItemWrapper? {
    return if (id.startsWith("local")) {
      DeviceManager.dbManager.getLocalLibraryItem(id)
    } else {
      serverLibraryItems.find { it.id == id }
    }
  }

  fun getFromSearch(query:String?) : LibraryItemWrapper? {
    if (query.isNullOrEmpty()) return getFirstItem()
    return serverLibraryItems.find {
      it.title.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))
    }
  }

  fun play(libraryItemWrapper:LibraryItemWrapper, episode:PodcastEpisode?, playItemRequestPayload:PlayItemRequestPayload, cb: (PlaybackSession?) -> Unit) {
    if (libraryItemWrapper is LocalLibraryItem) {
      cb(if (libraryItemWrapper.hasTracks(ctx, episode)) libraryItemWrapper.getPlaybackSession(episode, playItemRequestPayload.deviceInfo) else null)
    } else {
      val libraryItem = libraryItemWrapper as LibraryItem
      apiHandler.playLibraryItem(libraryItem.id,episode?.id ?: "", playItemRequestPayload) {
        if (it == null) {
          cb(null)
        } else {
          cb(it)
        }
      }
    }
  }

  private fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = Array(lhsLength) { it }
    var newCost = Array(lhsLength) { 0 }

    for (i in 1..rhsLength-1) {
      newCost[0] = i

      for (j in 1..lhsLength-1) {
        val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

        val costReplace = cost[j - 1] + match
        val costInsert = cost[j] + 1
        val costDelete = newCost[j - 1] + 1

        newCost[j] = Math.min(Math.min(costInsert, costDelete), costReplace)
      }

      val swap = cost
      cost = newCost
      newCost = swap
    }

    return cost[lhsLength - 1]
  }
}
