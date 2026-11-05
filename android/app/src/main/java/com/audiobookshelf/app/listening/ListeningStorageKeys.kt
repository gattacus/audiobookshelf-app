package com.audiobookshelf.app.listening

import io.paperdb.Paper
import java.security.MessageDigest

/** Paper keys become filenames. Never use server identities, file paths, or encoded URIs directly. */
object ListeningStorageKeys {
  fun compact(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
  private val compactKey = Regex("[0-9a-f]{64}")
  @Synchronized fun migrate(bookName: String) {
    val book = Paper.book(bookName)
    book.allKeys.filterNot { compactKey.matches(it) }.forEach { previous ->
      val value = book.read<Any>(previous) ?: return@forEach
      val key = compact(previous)
      if (!book.contains(key)) book.write(key, value)
      book.delete(previous)
    }
  }
}
