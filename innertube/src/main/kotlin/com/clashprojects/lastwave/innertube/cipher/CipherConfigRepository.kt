package com.clashprojects.lastwave.innertube.cipher

interface CipherConfigRepository {
    val enabled: Boolean
    val sourceUrl: String
    val defaultSourceUrl: String

    var cachedJson: String
    var cachedAtMs: Long
    var cachedSourceUrl: String
    var cachedEtag: String

    companion object {

        fun disabled(): CipherConfigRepository =
            object : CipherConfigRepository {
                override val enabled: Boolean = false
                override val sourceUrl: String = ""
                override val defaultSourceUrl: String = ""
                override var cachedJson: String = ""
                override var cachedAtMs: Long = 0L
                override var cachedSourceUrl: String = ""
                override var cachedEtag: String = ""
            }
    }
}
