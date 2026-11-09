/*
 * Copyright (C) 2024 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.Properties

plugins {
    alias(libs.plugins.buzbuz.androidLibrary)
    alias(libs.plugins.buzbuz.androidUnitTest)
    alias(libs.plugins.buzbuz.flavour)
    alias(libs.plugins.buzbuz.kotlinSerialization)
    alias(libs.plugins.buzbuz.hilt)
}

val detectionRules = Properties().apply {
    rootProject.file("config/detection.properties").inputStream().use { load(it) }
}
val thresholdMin = detectionRules.getProperty("screenConditionThresholdMin").toInt()
val thresholdMax = detectionRules.getProperty("screenConditionThresholdMax").toInt()
require(thresholdMin >= 0 && thresholdMin < thresholdMax && thresholdMax <= 100) {
    "screenConditionThresholdMin/Max=$thresholdMin/$thresholdMax; expected 0 <= min < max <= 100"
}

android {
    namespace = "com.buzbuz.smartautoclicker.core.extensions"
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField("int", "SCREEN_CONDITION_THRESHOLD_MIN", thresholdMin.toString())
        buildConfigField("int", "SCREEN_CONDITION_THRESHOLD_MAX", thresholdMax.toString())
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)

    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.ktx)

    implementation(libs.google.material)

    testImplementation(libs.kotlinx.coroutines.test)

    playStoreImplementation(libs.google.firebase.crashlytics.ktx)
}
