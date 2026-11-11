plugins {
    alias(libs.plugins.buzbuz.androidLibrary)
    alias(libs.plugins.buzbuz.androidUnitTest)
    alias(libs.plugins.buzbuz.flavour)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.buzbuz.smartautoclicker.core.script.runtime"
    sourceSets.getByName("test").resources.srcDir(rootProject.file("examples/scripts"))
    sourceSets.getByName("androidTest").resources.srcDir(rootProject.file("examples/scripts"))
}

// Run the same runtime contract against host JNI and Android JNI.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.androidTest?.sources?.kotlin?.addStaticSourceDirectory("src/test/java")
    }
}

// Local tests need the host JNI binary; instrumentation uses the Android artifact.
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(libs.quickjs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
