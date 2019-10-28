package com.jakewharton.diffuse.diff

import com.jakewharton.diffuse.Aab
import com.jakewharton.diffuse.report.DiffReport
import com.jakewharton.diffuse.report.text.AabDiffTextReport

internal class AabDiff(
  val oldAab: Aab,
  val newAab: Aab
) : BinaryDiff {
  inner class ModuleDiff(
    val oldModule: Aab.Module,
    val newModule: Aab.Module
  ) {
    val archive = ArchiveFilesDiff(oldModule.files, newModule.files)
    val dex = DexDiff(oldModule.dexes, oldAab.apiMapping, newModule.dexes, newAab.apiMapping)
    val manifest = ManifestDiff(oldModule.manifest, newModule.manifest)
  }

  val baseModule = ModuleDiff(oldAab.baseModule, newAab.baseModule)

  val addedFeatureModules = newAab.featureModules.filterKeys { it !in oldAab.featureModules }
  val removedFeatureModules = oldAab.featureModules.filterKeys { it !in newAab.featureModules }
  val changedFeatureModules = oldAab.featureModules.filterKeys { it in newAab.featureModules }
      .mapValues { (name, oldModule) ->
        ModuleDiff(oldModule, newAab.featureModules.getValue(name))
      }

  override fun toTextReport(): DiffReport = AabDiffTextReport(this)
}
