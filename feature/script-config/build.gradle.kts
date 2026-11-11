plugins {
    alias(libs.plugins.buzbuz.androidLibrary)
    alias(libs.plugins.buzbuz.androidUnitTest)
    alias(libs.plugins.buzbuz.flavour)
    alias(libs.plugins.buzbuz.hilt)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.buzbuz.smartautoclicker.feature.script.config"
    buildFeatures.viewBinding = true
}

// Editor validation tests use host JNI; device builds keep the Android artifact.
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(project(":core:script:packages"))
    implementation(project(":core:script:android"))
    implementation(project(":core:common:base"))
    implementation(project(":core:common:display"))
    implementation(project(":core:common:accessibility"))
    implementation(project(":core:common:permissions"))
    implementation(project(":core:common:overlays"))
    implementation(project(":core:common:ui"))
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerView)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.core)
}
