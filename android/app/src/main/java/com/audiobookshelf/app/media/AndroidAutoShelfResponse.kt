package com.audiobookshelf.app.media

import com.audiobookshelf.app.data.LibraryShelfType
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

internal object AndroidAutoShelfResponse {
  /** null means unavailable/malformed; an empty array is a successful empty snapshot. */
  fun parse(response: String, mapper: ObjectMapper): List<LibraryShelfType>? = try {
    val root = mapper.readTree(response)
    val shelves = root?.get("value")
    if (root == null || root.has("error") || shelves == null || !shelves.isArray) null
    else mapper.readValue<List<LibraryShelfType>>(shelves.toString())
  } catch (_: JsonProcessingException) {
    null
  } catch (_: IllegalArgumentException) {
    null
  }
}
