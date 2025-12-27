version = 251227

cloudstream {
    description = "Videos, playlists and channels from YouTube"
    authors = listOf("kreastream")
    status = 1
    tvTypes = listOf("Others")
    requiresResources = true
    iconUrl = "https://www.youtube.com/s/desktop/711fd789/img/logos/favicon_144x144.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xjvm-default=all", "-Xopt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    // Using stable NewPipe extractor version
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.24.8")
    //noinspection GradleDependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
