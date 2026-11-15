package com.audiobookshelf.app.media

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.support.v4.media.MediaBrowserCompat
import androidx.media.CapturingBrowserResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.server.ApiHandler
import io.paperdb.Paper
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unstarted service and real loopback HTTP, with Paper confined to isolated cache storage. */
@RunWith(AndroidJUnit4::class)
class AndroidAutoBrowseDeviceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var context: Context
  private lateinit var previousData: DeviceData
  private var previousConfig: ServerConnectionConfig? = null
  private val books = listOf("device", "log", "playbackSession", "localMediaProgress", "progressReceipts", "localLibraryItems")
  private val managers = mutableListOf<MediaManager>()
  private val services = mutableListOf<PlayerNotificationService>()
  private val servers = mutableListOf<TestServer>()

  @Before fun setUp() {
    val target = instrumentation.targetContext
    val files = File(target.cacheDir, "download-recovery-test-storage").apply { mkdirs() }
    context = object : ContextWrapper(target) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = files
      override fun getSharedPreferences(name: String, mode: Int) =
        target.getSharedPreferences("android-auto-test-$name", mode)
    }
    Paper.init(context)
    DbManager.initialize(context)
    books.forEach {
      check(File(Paper.book(it).path).parentFile == files) { "Refusing to touch app user data" }
      Paper.book(it).destroy()
    }
    previousData = DeviceManager.deviceData
    previousConfig = DeviceManager.serverConnectionConfig
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
    DeviceManager.serverConnectionConfig = null
  }

  @After fun tearDown() {
    instrumentation.runOnMainSync {
      services.forEach { service ->
        field("androidAutoBrowseDestroyed").setBoolean(service, true)
        (field("androidAutoBrowseHandler").get(service) as Handler).removeCallbacksAndMessages(null)
      }
      managers.forEach { it.closeAndroidAutoBrowse() }
    }
    servers.forEach { it.close() }
    if (!::context.isInitialized || !books.all { File(Paper.book(it).path).parentFile == context.filesDir }) return
    DeviceManager.deviceData = previousData
    DeviceManager.serverConnectionConfig = previousConfig
    books.forEach { Paper.book(it).destroy() }
  }

  @Test fun coldRootAndDownloadsReturnBeforeUnresponsiveServer() {
    val requested = CountDownLatch(1)
    val release = CountDownLatch(1)
    val server = server { path ->
      if (path == "/ping") { requested.countDown(); release.await(5, TimeUnit.SECONDS) }
      response(path)
    }
    select(config("a", server.address))
    instrumentation.runOnMainSync {
      val manager = manager()
      val service = PlayerNotificationService().also { services.add(it) }
      ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
        isAccessible = true
      }.invoke(service, context)
      field("ctx").set(service, context)
      service.mediaManager = manager
      assertNotNull(service.onGetRoot("com.google.android.projection.gearhead", 10132, null))
      val root = CapturingBrowserResult()
      service.onLoadChildren("/", root)
      assertEquals("Root must complete on the caller's stack", 1, root.calls)
      assertTrue(root.items.any { it.mediaId == "__DOWNLOADS__" && it.isBrowsable })
      assertTrue("No network stage may delay root delivery", server.requests.isEmpty())
      val downloads = CapturingBrowserResult()
      service.onLoadChildren("__DOWNLOADS__", downloads)
      assertEquals(1, downloads.calls)
      assertEquals(emptyList<MediaBrowserCompat.MediaItem>(), downloads.items)
    }
    assertTrue(requested.await(5, TimeUnit.SECONDS))
    // Avoid starting the real service or touching playback; discard this synthetic load.
    instrumentation.runOnMainSync {
      services.forEach { field("androidAutoBrowseDestroyed").setBoolean(it, true) }
      managers.forEach { it.closeAndroidAutoBrowse() }
    }
    release.countDown()
  }

  @Test fun timeoutKeepsCachedLibrariesAndRetryRejectsTheLateResponse() {
    val requested = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val server = server { path ->
      if (path.startsWith("/api/libraries")) {
        if (calls.incrementAndGet() == 1) {
          requested.countDown()
          release.await(5, TimeUnit.SECONDS)
          libraries("obsolete")
        } else libraries("fresh")
      } else response(path)
    }
    select(config("a", server.address))
    lateinit var manager: MediaManager
    val done = CountDownLatch(2)
    val outcomes = CopyOnWriteArrayList<Boolean>()
    instrumentation.runOnMainSync {
      manager = manager(1_000)
      manager.checkResetServerItems()
      manager.serverLibraries = listOf(Library("cached", "Cached", mutableListOf(), "audiobookshelf", "book", null))
      repeat(2) { manager.loadAndroidAutoItems { outcomes.add(it); done.countDown() } }
    }
    assertTrue(requested.await(5, TimeUnit.SECONDS))
    assertTrue(done.await(5, TimeUnit.SECONDS))
    assertEquals(listOf(false, false), outcomes)
    assertEquals(listOf("cached"), manager.serverLibraries.map { it.id })
    val retried = CountDownLatch(1)
    instrumentation.runOnMainSync { manager.loadAndroidAutoItems { outcomes.add(it); retried.countDown() } }
    assertTrue(retried.await(5, TimeUnit.SECONDS))
    assertEquals(listOf(false, false, true), outcomes)
    release.countDown()
    awaitLog("stage=libraries ignored=expired")
    assertEquals(listOf("fresh"), manager.serverLibraries.map { it.id })
    assertTrue(DeviceManager.dbManager.getAllLogs().any { "stage=libraries outcome=timeout" in it.message })
  }

  @Test fun switchingAccountWhileLibrariesLoadCannotCommitOldItems() {
    val requested = CountDownLatch(1)
    val release = CountDownLatch(1)
    val server = server { path ->
      if (path.startsWith("/api/libraries")) {
        requested.countDown(); release.await(5, TimeUnit.SECONDS); libraries("old-account")
      } else response(path)
    }
    select(config("a", server.address))
    lateinit var manager: MediaManager
    val done = CountDownLatch(1)
    var loaded = true
    instrumentation.runOnMainSync {
      manager = manager()
      manager.checkResetServerItems()
      manager.loadAndroidAutoItems { loaded = it; done.countDown() }
    }
    assertTrue(requested.await(5, TimeUnit.SECONDS))
    instrumentation.runOnMainSync { select(config("b", server.address)) }
    release.countDown()
    assertTrue(done.await(5, TimeUnit.SECONDS))
    assertFalse(loaded)
    assertTrue(manager.serverLibraries.isEmpty())
  }

  @Test fun cachedContinueIsImmediatelyBrowsableAndClearedWhenAccountChanges() {
    val server = server { path ->
      if (path.endsWith("/personalized")) CONTINUE_SHELF else response(path)
    }
    select(config("a", server.address))
    lateinit var manager: MediaManager
    val loaded = CountDownLatch(1)
    instrumentation.runOnMainSync {
      manager = manager()
      manager.checkResetServerItems()
      manager.loadAndroidAutoItems { assertTrue(it); loaded.countDown() }
    }
    assertTrue(loaded.await(5, TimeUnit.SECONDS))
    val shelves = CountDownLatch(1)
    instrumentation.runOnMainSync {
      manager.populatePersonalizedDataForAllLibraries { assertTrue(it); shelves.countDown() }
    }
    assertTrue(shelves.await(5, TimeUnit.SECONDS))
    instrumentation.runOnMainSync {
      val service = PlayerNotificationService().also { services.add(it) }
      ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
        isAccessible = true
      }.invoke(service, context)
      field("ctx").set(service, context)
      service.mediaManager = manager
      val root = CapturingBrowserResult()
      service.onLoadChildren("/", root)
      assertEquals(1, root.calls)
      assertTrue(root.items.any { it.mediaId == "__CONTINUE__" })
      val continued = CapturingBrowserResult()
      service.onLoadChildren("__CONTINUE__", continued)
      assertEquals(listOf("resume"), continued.items.map { it.mediaId })
      // A retained child subscription must not expose the previous account's cache.
      select(config("b", server.address))
      val switched = CapturingBrowserResult()
      service.onLoadChildren("__CONTINUE__", switched)
      assertTrue(switched.items.isEmpty())
      field("androidAutoBrowseDestroyed").setBoolean(service, true)
    }
  }

  @Test fun stalledProgressSyncDoesNotPreventBrowsing() {
    val release = CountDownLatch(1)
    val requested = CountDownLatch(1)
    val server = server { path ->
      if (path == "/api/me") { requested.countDown(); release.await(8, TimeUnit.SECONDS) }
      response(path)
    }
    select(config("a", server.address))
    val done = CountDownLatch(1)
    var loaded = false
    instrumentation.runOnMainSync { manager(6_000).loadAndroidAutoItems { loaded = it; done.countDown() } }
    assertTrue(requested.await(5, TimeUnit.SECONDS))
    assertTrue("Browse should proceed after the 3s sync wait", done.await(6, TimeUnit.SECONDS))
    assertTrue(loaded)
    release.countDown()
    awaitLog("stage=progress_sync ignored=expired")
  }

  private fun awaitLog(message: String) {
    val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
      if (DeviceManager.dbManager.getAllLogs().any { message in it.message }) return
      android.os.SystemClock.sleep(20)
    }
    fail("Missing retained diagnostic: $message")
  }

  private fun manager(timeout: Long = 15_000) =
    MediaManager(ApiHandler(context), context, timeout).also { managers.add(it) }

  private fun field(name: String) = PlayerNotificationService::class.java.getDeclaredField(name).apply { isAccessible = true }
  private fun select(config: ServerConnectionConfig) {
    DeviceManager.deviceData = DeviceData(mutableListOf(config), config.id, DeviceSettings.default(), null)
    DeviceManager.serverConnectionConfig = config
  }
  private fun config(id: String, address: String) = ServerConnectionConfig(
    id, 0, "Synthetic $id", address, "2.25.0", "user-$id", "test-$id", "token-$id", null)
  private fun libraries(id: String) = """{"libraries":[{"id":"$id","name":"Test","folders":[],"icon":"audiobookshelf","mediaType":"book"}]}"""
  private fun response(path: String) = when {
    path == "/ping" -> """{"success":true}"""
    path == "/api/me" -> """{"id":"user-a","username":"test","mediaProgress":[]}"""
    path == "/api/authorize" -> """{"user":{"mediaProgress":[]}}"""
    path.startsWith("/api/libraries") -> libraries("fresh")
    else -> "{}"
  }

  companion object {
    private const val CONTINUE_SHELF = """{"value":[{"id":"continue-listening","label":"Continue","total":1,"type":"book","entities":[{"id":"resume","ino":"1","libraryId":"fresh","folderId":"folder","path":"/books/resume","relPath":"resume","mediaType":"book","media":{"numTracks":1,"tags":[],"metadata":{"title":"Resume","genres":[],"authors":[],"narrators":[]}}}]}]}"""
  }
  private fun server(handler: (String) -> String) = TestServer(handler).also { servers.add(it) }

  private class TestServer(private val handler: (String) -> String) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    val address = "http://127.0.0.1:${listener.localPort}"
    val requests = CopyOnWriteArrayList<String>()
    private val accepting = thread(isDaemon = true, name = "AndroidAutoTestServer") {
      while (!listener.isClosed) {
        val socket = try { listener.accept() } catch (_: java.io.IOException) { break }
        workers.execute {
          try { socket.use {
            it.soTimeout = 10_000
            val reader = it.getInputStream().bufferedReader()
            val path = reader.readLine().split(' ')[1]
            var length = 0
            while (true) {
              val line = reader.readLine() ?: break
              if (line.isEmpty()) break
              if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
            }
            repeat(length) { check(reader.read() >= 0) }
            requests.add(path)
            val bytes = handler(path).toByteArray()
            val output = it.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
            output.flush()
          } } catch (_: InterruptedException) { /* Fixture teardown releases held requests. */ }
            catch (_: java.io.IOException) { /* Client may cancel an expired request. */ }
        }
      }
    }
    override fun close() {
      listener.close()
      workers.shutdownNow()
      accepting.join(1_000)
      check(workers.awaitTermination(2, TimeUnit.SECONDS))
    }
  }
}
