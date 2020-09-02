package com.jakewharton.diffuse

import com.android.apksig.util.DataSource
import com.android.apksig.util.DataSources
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile
import com.jakewharton.diffuse.io.Input
import com.jakewharton.diffuse.io.PathInput
import java.io.Closeable
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.contracts.InvocationKind.EXACTLY_ONCE
import kotlin.contracts.contract
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal fun InputStream.asZip(charset: Charset = Charsets.UTF_8) = ZipInputStream(this, charset)
internal fun InputStream.readByteString() = readBytes().toByteString()

// TODO https://youtrack.jetbrains.com/issue/KT-18242
internal fun Path.writeText(text: String, charset: Charset = Charsets.UTF_8) = Files.write(this, text.toByteArray(charset))
internal fun Path.inputStream(vararg options: OpenOption): InputStream = Files.newInputStream(this, *options)
internal val Path.exists get() = Files.exists(this)
internal fun Path.asZipFileSystem(loader: ClassLoader? = null) = FileSystems.newFileSystem(this, loader)!!

internal fun ByteString.asInputStream() = Buffer().write(this).inputStream()

internal inline fun Input.useDataSource(body: (DataSource) -> Unit) {
  contract {
    callsInPlace(body, EXACTLY_ONCE)
  }
  var closeable: Closeable? = null
  val source = when (this) {
    is PathInput -> {
      val channel = FileChannel.open(path)
      closeable = channel
      DataSources.asDataSource(channel)
    }
    else -> DataSources.asDataSource(toByteString().asByteBuffer())
  }
  try {
    body(source)
  } finally {
    closeable?.close()
  }
}

internal fun Input.toBinaryResourceFile() = BinaryResourceFile(toByteArray())

internal fun <T, R> Pair<T, T>.mapEach(body: (T) -> R): Pair<R, R> = body(first) to body(second)

// TODO replace with https://youtrack.jetbrains.com/issue/KT-20690
internal fun <T : Comparable<T>> comparingValues(): Comparator<Iterable<T>> {
  return object : Comparator<Iterable<T>> {
    override fun compare(o1: Iterable<T>, o2: Iterable<T>): Int {
      val i1 = o1.iterator()
      val i2 = o2.iterator()
      while (true) {
        if (!i1.hasNext()) {
          return if (!i2.hasNext()) 0 else -1
        }
        if (!i2.hasNext()) {
          return 1
        }
        val result = i1.next().compareTo(i2.next())
        if (result != 0) {
          return result
        }
      }
    }
  }
}

internal fun ZipInputStream.entries(): Sequence<ZipEntry> {
  return object : Sequence<ZipEntry> {
    override fun iterator(): Iterator<ZipEntry> {
      return object : Iterator<ZipEntry> {
        var next: ZipEntry? = null
        var done = false

        override fun hasNext(): Boolean {
          if (done) return false
          if (next == null) {
            next = nextEntry
            if (next == null) {
              done = true
            }
          }
          return next != null
        }

        override fun next(): ZipEntry {
          if (!hasNext()) {
            throw NoSuchElementException()
          }
          return next!!.also { next = null }
        }
      }
    }
  }
}
