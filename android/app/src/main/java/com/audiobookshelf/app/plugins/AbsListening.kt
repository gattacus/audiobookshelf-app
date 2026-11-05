package com.audiobookshelf.app.plugins

import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.listening.*
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import kotlinx.coroutines.launch
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "AbsListening")
class AbsListening : Plugin() {
  private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
  private val mapper = jacksonObjectMapper()
  private val changed: () -> Unit = { notifyListeners("onListeningStateChanged", JSObject()) }
  override fun load() {
    ProgressStatusStore.listeners.add(changed)
    ProgressStatusStore.availabilityListeners.add(availabilityChanged)
    DownloadAutomation.schedule(context)
  }
  private val availabilityChanged: () -> Unit = { notifyListeners("onDownloadAvailabilityChanged", JSObject()) }
  override fun handleOnDestroy() { ProgressStatusStore.listeners.remove(changed); ProgressStatusStore.availabilityListeners.remove(availabilityChanged); super.handleOnDestroy() }
  private fun json(value: Any) = JSObject(mapper.writeValueAsString(value))
  private fun identity(call: PluginCall): ListeningIdentity? {
    val supplied = call.getObject("identity")
    if (supplied != null) return mapper.readValue(supplied.toString(), ListeningIdentity::class.java)
    val itemId = call.getString("libraryItemId")
    if (!itemId.isNullOrBlank()) {
      val config = DeviceManager.serverConnectionConfig ?: DownloadAutomation.selected() ?: return null
      val local = if (itemId.startsWith("local")) DeviceManager.dbManager.getLocalLibraryItem(itemId) else null
      val episodeId = call.getString("episodeId")?.takeIf { it.isNotBlank() }
      val episode = (local?.media as? com.audiobookshelf.app.data.Podcast)?.episodes?.find { it.id == episodeId }
      return ListeningIdentity(local?.serverConnectionConfigId ?: config.id, local?.serverUserId ?: config.userId, local?.libraryItemId ?: itemId, episode?.serverEpisodeId ?: episodeId)
    }
    val main = activity as MainActivity
    return if (main.isPlayerNotificationServiceInitialized()) main.foregroundService.currentPlaybackSession?.let { ListeningIdentity.from(it) } else null
  }
  private fun permitted(id: ListeningIdentity): Boolean {
    val config = DeviceManager.serverConnectionConfig
    return !id.serverLinked || (config?.id == id.configId && config.userId == id.userId)
  }
  @PluginMethod fun getProgressStatus(call: PluginCall) {
    val id = identity(call)
    if (id == null || !permitted(id)) call.resolve(JSObject().put("status", JSONObjectNull))
    else call.resolve(json(mapOf("status" to ProgressStatusStore.snapshot(id))))
  }
  @PluginMethod fun getSyncSummary(call: PluginCall) {
    val items = ProgressStatusStore.identities().filter { permitted(it) }.map { ProgressStatusStore.snapshot(it) }
      .filter { it["state"] != "synced" && it["state"] != "local_only" }
    call.resolve(json(mapOf("items" to items)))
  }
  @PluginMethod fun retrySync(call: PluginCall) {
    val id = identity(call) ?: return call.reject("No item selected")
    if (!id.serverLinked || !permitted(id)) return call.reject("Select this item's server account before retrying")
    ApiHandler(context).syncLocalSessionsAndProgress(id) { ok, error ->
      if (ok) call.resolve() else call.reject(error ?: "Sync failed; local progress retained")
    }
  }
  @PluginMethod fun getUndo(call: PluginCall) {
    val main = activity as MainActivity
    val jump = if (main.isPlayerNotificationServiceInitialized()) main.foregroundService.playbackUndo.jump else null
    call.resolve(json(mapOf("jump" to jump)))
  }
  @PluginMethod fun undoJump(call: PluginCall) {
    val generation = (call.data.opt("generation") as? Number)?.toLong() ?: return call.reject("Missing jump generation")
    val main = activity as MainActivity
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      if (main.isPlayerNotificationServiceInitialized() && main.foregroundService.undoJump(generation)) call.resolve()
      else call.reject("This recovery point is no longer available")
    }
  }
  private fun io(call: PluginCall, action: () -> Unit) {
    scope.launch { try { action() } catch (e: Exception) { call.reject(e.message ?: "Listening management failed") } }
  }
  @PluginMethod fun getManagement(call: PluginCall) = io(call) {
    call.resolve(json(mapOf("settings" to ManagedDownloadStore.settings(), "rules" to ManagedDownloadStore.rules().filter { permitted(it.identity) },
      "folders" to DeviceManager.dbManager.getAllLocalFolders().filter { it.mediaType == "podcast" })))
  }
  @PluginMethod fun getDownloadRule(call: PluginCall) = io(call) {
    val id = identity(call)?.copy(episodeId = null) ?: return@io call.reject("Select a podcast")
    if (!permitted(id)) return@io call.reject("Select this podcast's account")
    call.resolve(json(mapOf("rule" to ManagedDownloadStore.rule(id), "folders" to DeviceManager.dbManager.getAllLocalFolders().filter { it.mediaType == "podcast" })))
  }
  @PluginMethod fun saveDownloadRule(call: PluginCall) = io(call) {
    val id = identity(call)?.copy(episodeId = null) ?: return@io call.reject("Select a podcast")
    if (!permitted(id) || !id.serverLinked) return@io call.reject("Select this podcast's account")
    val count = call.getInt("count") ?: 3
    val folder = call.getString("folderId") ?: "internal-podcast"
    if (count !in 1..100 || (folder != "internal-podcast" && DeviceManager.dbManager.getLocalFolder(folder)?.mediaType != "podcast")) return@io call.reject("Invalid count or folder")
    val current = ManagedDownloadStore.rule(id)
    val enabled = call.getBoolean("enabled") == true
    ManagedDownloadStore.save(current.copy(enabled = enabled, count = count, folderId = folder, wifiOnly = call.getBoolean("wifiOnly") != false, title = call.getString("title") ?: current.title, status = if (enabled) "Waiting for next check" else "Disabled"))
    DownloadAutomation.schedule(context)
    com.audiobookshelf.app.services.DownloadServiceHost.ensure(context).pauseDisallowed()
    call.resolve()
  }
  @PluginMethod fun saveManagementSettings(call: PluginCall) = io(call) {
    val settings = mapper.readValue(call.getObject("settings").toString(), DownloadHousekeepingSettings::class.java)
    if (settings.automaticLimitBytes !in listOf(0L,GIB,2*GIB,5*GIB,10*GIB) || settings.bookDelayHours !in listOf(0,24,168) || settings.episodeDelayHours !in listOf(0,24,168)) return@io call.reject("Invalid housekeeping settings")
    ManagedDownloadStore.save(settings)
    DownloadAutomation.schedule(context)
    call.resolve()
  }
  @PluginMethod fun getCleanupPreview(call: PluginCall) = io(call) {
    call.resolve(json(mapOf("items" to FinishedDownloadCleanup(context, call.getObject("settings")?.let { mapper.readValue(it.toString(), DownloadHousekeepingSettings::class.java) }).preview(call.getBoolean("automatic") == true).filter { permitted(it.identity) })))
  }
  @PluginMethod fun cleanupFinished(call: PluginCall) = io(call) {
    val array = call.getArray("identities") ?: return@io call.reject("Select items to clean up")
    val ids = (0 until array.length()).map { mapper.readValue(array.getJSONObject(it).toString(), ListeningIdentity::class.java) }
    if (ids.any { !permitted(it) }) return@io call.reject("Select the matching account")
    call.resolve(json(FinishedDownloadCleanup(context).delete(ids)))
  }
  @PluginMethod fun getKeepDownloaded(call: PluginCall) = io(call) {
    val id = identity(call) ?: return@io call.reject("Select an item")
    call.resolve(JSObject().put("enabled", ManagedDownloadStore.protected(id)))
  }
  @PluginMethod fun setKeepDownloaded(call: PluginCall) = io(call) {
    val id = identity(call) ?: return@io call.reject("Select an item")
    if (!permitted(id)) return@io call.reject("Select the matching account")
    ManagedDownloadStore.protect(id, call.getBoolean("enabled") == true)
    call.resolve()
  }
  private companion object { val JSONObjectNull = org.json.JSONObject.NULL }
}
