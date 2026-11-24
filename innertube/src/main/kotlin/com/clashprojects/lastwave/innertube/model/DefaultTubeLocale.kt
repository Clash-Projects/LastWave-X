package com.clashprojects.lastwave.innertube.model

import java.util.Locale

internal fun defaultTubeLocale(): TubeLocale = systemTubeLocale(Locale.getDefault())

internal fun systemTubeLocale(locale: Locale): TubeLocale =
    TubeLocale(
        gl = locale.country.uppercase(Locale.ROOT),
        hl = locale.toLanguageTag(),
    )
