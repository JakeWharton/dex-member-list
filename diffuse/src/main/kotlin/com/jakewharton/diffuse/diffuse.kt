@file:JvmName("Diffuse")

package com.jakewharton.diffuse

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.FlagOption
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.jakewharton.diffuse.diff.BinaryDiff
import com.jakewharton.diffuse.format.Aab.Companion.toAab
import com.jakewharton.diffuse.format.Aab.Module
import com.jakewharton.diffuse.format.Aar.Companion.toAar
import com.jakewharton.diffuse.format.ApiMapping
import com.jakewharton.diffuse.format.ApiMapping.Companion.toApiMapping
import com.jakewharton.diffuse.format.Apk.Companion.toApk
import com.jakewharton.diffuse.format.CodeBinary
import com.jakewharton.diffuse.format.Dex.Companion.toDex
import com.jakewharton.diffuse.format.Field
import com.jakewharton.diffuse.format.Jar.Companion.toJar
import com.jakewharton.diffuse.format.Member
import com.jakewharton.diffuse.format.Method
import com.jakewharton.diffuse.info.AabInfo
import com.jakewharton.diffuse.info.AarInfo
import com.jakewharton.diffuse.info.ApkInfo
import com.jakewharton.diffuse.info.JarInfo
import com.jakewharton.diffuse.io.Input
import com.jakewharton.diffuse.io.Input.Companion.asInput
import com.jakewharton.diffuse.report.Report
import java.io.PrintStream
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import kotlin.LazyThreadSafetyMode.NONE

fun main(vararg args: String) {
  val javaVersion = System.getProperty("java.version")
  if (javaVersion == null || javaVersion.matches("^(1\\.|9|10).*$".toRegex())) {
    println("*****************************************")
    println("The minimum supported java version is 11.")
    println("*****************************************")
  }

  val defaultFs = FileSystems.getDefault()
  val systemOut = System.out

  val version = NoOpCliktCommand::class.java.`package`.implementationVersion
  NoOpCliktCommand(name = "diffuse")
    .versionOption(version)
    .subcommands(
      DiffCommand(defaultFs, defaultFs, systemOut),
      InfoCommand(defaultFs, defaultFs, systemOut),
      MembersCommand(defaultFs, systemOut),
    )
    .main(args.toList())
}

private enum class BinaryType {
  Apk, Aar, Aab, Jar, Dex
}

private fun ParameterHolder.binaryType(): FlagOption<BinaryType> {
  return option(help = "Input file type. Default is 'apk'.")
    .switch(
      "--apk" to BinaryType.Apk,
      "--aar" to BinaryType.Aar,
      "--aab" to BinaryType.Aab,
      "--jar" to BinaryType.Jar
    )
    .default(BinaryType.Apk)
}

private enum class ReportType {
  Text, Html, None
}

private fun ParameterHolder.mappingFile(name: String): OptionWithValues<ApiMapping, ApiMapping, ApiMapping> {
  return option(
    name,
    help = "Mapping file produced by R8 or ProGuard.",
    metavar = "FILE"
  )
    .path(mustExist = true, canBeDir = false, mustBeReadable = true)
    .convert { it.asInput().toApiMapping() }
    .default(ApiMapping.EMPTY)
}

private class OutputOptions(
  outputFs: FileSystem,
  private val output: PrintStream
) : OptionGroup(name = "Output options") {
  private val text by option(
    help = "File to write text report. Note: Specifying this option will disable printing the text report to standard out by default. Specify '--stdout text' to restore that behavior.",
    metavar = "FILE"
  )
    .path(fileSystem = outputFs)
  private val html by option(
    help = "File to write HTML report. Note: Specifying this option will disable printing the text report to standard out by default. Specify '--stdout text' to restore that behavior.",
    metavar = "FILE"
  )
    .path(fileSystem = outputFs)
  private val stdout by option(
    help = "Report to print to standard out. By default, The text report will be printed to standard out ONLY when neither --text nor --html are specified."
  )
    .choice("text" to ReportType.Text, "html" to ReportType.Html)
    .defaultLazy {
      if (text == null && html == null) {
        ReportType.Text
      } else {
        ReportType.None
      }
    }

  fun write(reportFactory: Report.Factory) {
    val textReport by lazy(NONE) { reportFactory.toTextReport().toString() }
    val htmlReport by lazy(NONE) { reportFactory.toHtmlReport().toString() }

    text?.writeText(textReport)
    html?.writeText(htmlReport)

    val printReport = when (stdout) {
      ReportType.Text -> textReport
      ReportType.Html -> htmlReport
      ReportType.None -> null
    }
    printReport?.let(output::println)
  }
}

private class InfoCommand(
  inputFs: FileSystem,
  outputFs: FileSystem,
  output: PrintStream,
) : CliktCommand(name = "info", help = "Display info about a binary.") {
  private val type by binaryType()
  private val outputOptions by OutputOptions(outputFs, output)
  private val file by argument("FILE", help = "Input file.")
    .path(mustExist = true, canBeDir = false, mustBeReadable = true, fileSystem = inputFs)

  override fun run() {
    val info = when (type) {
      BinaryType.Apk -> ApkInfo(file.asInput().toApk())
      BinaryType.Aar -> AarInfo(file.asInput().toAar())
      BinaryType.Aab -> AabInfo(file.asInput().toAab())
      BinaryType.Jar -> JarInfo(file.asInput().toJar())
      BinaryType.Dex -> error("Unsupported")
    }
    outputOptions.write(info)
  }
}

private class DiffCommand(
  inputFs: FileSystem,
  outputFs: FileSystem,
  output: PrintStream
) : CliktCommand(name = "diff", help = "Display changes between two binaries.") {
  private val inputOptions by object : OptionGroup("Input options") {
    private val type by binaryType()

    private val oldMapping by mappingFile("--old-mapping")
    private val newMapping by mappingFile("--new-mapping")

    fun parse(old: Input, new: Input): BinaryDiff {
      return when (type) {
        BinaryType.Apk -> BinaryDiff.ofApk(old.toApk(), oldMapping, new.toApk(), newMapping)
        BinaryType.Aab -> BinaryDiff.ofAab(old.toAab(), new.toAab())
        BinaryType.Aar -> BinaryDiff.ofAar(old.toAar(), oldMapping, new.toAar(), newMapping)
        BinaryType.Jar -> BinaryDiff.ofJar(old.toJar(), oldMapping, new.toJar(), newMapping)
        BinaryType.Dex -> error("Unsupported")
      }
    }
  }

  private val outputOptions by OutputOptions(outputFs, output)

  private val old by argument("OLD", help = "Old input file.")
    .path(mustExist = true, canBeDir = false, mustBeReadable = true, fileSystem = inputFs)

  private val new by argument("NEW", help = "New input file.")
    .path(mustExist = true, canBeDir = false, mustBeReadable = true, fileSystem = inputFs)

  override fun run() {
    val diff = inputOptions.parse(old.asInput(), new.asInput())
    outputOptions.write(diff)
  }
}

private class MembersCommand(
  inputFs: FileSystem,
  private val stdout: PrintStream
) : CliktCommand(name = "members", help = "List methods or fields of a binary.") {
  private val binary by argument("FILE", help = "Input file.")
    .path(mustExist = true, canBeDir = false, mustBeReadable = true, fileSystem = inputFs)

  private val hideSyntheticNumbers by option(
    "--hide-synthetic-numbers",
    help = "Remove synthetic numbers from type and method names. This is useful to prevent noise when diffing output."
  )
    .flag()

  private val binaryType by option(help = "File type. Default is 'apk'.")
    .switch("--apk" to BinaryType.Apk, "--aar" to BinaryType.Aar, "--aab" to BinaryType.Aab, "--jar" to BinaryType.Jar, "--dex" to BinaryType.Dex)
    .default(BinaryType.Apk)

  private val type by option(help = "Item types to display. Default is both (methods and fields).")
    .switch("--methods" to Type.Methods, "--fields" to Type.Fields)
    .default(Type.All)

  enum class Type {
    All, Methods, Fields
  }

  private val ownership by option(help = "Item ownerships to display. Default is both (declared and referenced).")
    .switch("--declared" to Ownership.Declared, "--referenced" to Ownership.Referenced)
    .default(Ownership.All)

  enum class Ownership {
    All, Declared, Referenced
  }

  override fun run() {
    val input = binary.asInput()

    val memberSelector = when (ownership) {
      Ownership.All -> CodeBinary::members
      Ownership.Declared -> CodeBinary::declaredMembers
      Ownership.Referenced -> CodeBinary::referencedMembers
    }

    val binaryMembers = when (binaryType) {
      BinaryType.Apk -> input.toApk().dexes
      BinaryType.Aab -> input.toAab().modules.flatMap(Module::dexes)
      BinaryType.Aar -> input.toAar().jars
      BinaryType.Jar -> listOf(input.toJar())
      BinaryType.Dex -> listOf(input.toDex())
    }

    val members = binaryMembers.map(memberSelector).flatten().toSet()

    val items = when (type) {
      Type.All -> members
      Type.Methods -> members.filterIsInstance<Method>()
      Type.Fields -> members.filterIsInstance<Field>()
    }

    val displayList = if (hideSyntheticNumbers) {
      items.map { it.withoutSyntheticSuffix() }
    } else {
      items
    }
    // Re-sort because rendering may subtly change ordering.
    displayList.map(Member::toString).sorted().forEach(stdout::println)
  }
}
