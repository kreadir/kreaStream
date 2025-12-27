version = 251226

cloudstream {
    authors = listOf("kreastream")
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://www.shutterstock.com/image-vector/iptv-vector-line-icon-ip-260nw-1841427610.jpg"
    description = "General IPTV player. Add a list in the settings and you'll see it in the plugins list in the home"
    requiresResources = true
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
dependencies {
    implementation("com.google.android.material:material:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}