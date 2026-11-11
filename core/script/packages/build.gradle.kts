plugins {
    alias(libs.plugins.buzbuz.androidLibrary)
    alias(libs.plugins.buzbuz.androidUnitTest)
    alias(libs.plugins.buzbuz.flavour)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.buzbuz.smartautoclicker.core.script.packages"
}

dependencies {
    api(project(":core:script:runtime"))
    api(libs.kotlinx.serialization.json)
}
