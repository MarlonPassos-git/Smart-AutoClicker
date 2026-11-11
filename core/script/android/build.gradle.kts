plugins {
    alias(libs.plugins.buzbuz.androidLibrary)
    alias(libs.plugins.buzbuz.androidUnitTest)
    alias(libs.plugins.buzbuz.flavour)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.buzbuz.smartautoclicker.core.script.android"
    sourceSets.getByName("test").resources.srcDir("src/androidTest/assets")
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("core/smart/detection/src/androidTest/assets"))
    sourceSets.getByName("androidTest").resources.srcDir(rootProject.file("examples/scripts"))
}

dependencies {
    api(project(":core:script:runtime"))
    api(project(":core:script:packages"))
    implementation(project(":core:common:actions"))
    implementation(project(":core:common:base"))
    implementation(project(":core:smart:detection"))
    implementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(project(":core:smart:detection-models"))
}

configurations.matching { it.name.contains("UnitTest") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}
