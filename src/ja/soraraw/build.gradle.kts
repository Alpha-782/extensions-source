import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SoraRaw"
    versionCode = 0
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    // https://soraraw.top has the domain links; soraraw.net domain is blocked inside app
    source {
        lang = "ja"
        baseUrl = "https://soraraw.com"
    }
}
