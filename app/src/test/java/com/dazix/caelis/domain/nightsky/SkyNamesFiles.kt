package com.dazix.caelis.domain.nightsky

import java.io.File

/** The resource directory, whether Gradle runs the tests from the module or from the root. */
internal fun resDir(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

/** The shipped `sky_names.tsv` for [language] (`null` = the default, English). */
internal fun skyNamesFile(language: String?): File =
    File(resDir(), if (language == null) "raw/sky_names.tsv" else "raw-$language/sky_names.tsv")

internal fun skyNames(language: String?): SkyNames = SkyNames.parse(skyNamesFile(language).readText(Charsets.UTF_8))
