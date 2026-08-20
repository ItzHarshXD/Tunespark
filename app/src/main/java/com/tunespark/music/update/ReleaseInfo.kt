package com.tunespark.music.update

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long
)
