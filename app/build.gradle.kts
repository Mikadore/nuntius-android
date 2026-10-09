plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.openapi.generator)
}

android {
    namespace = "dev.mikadore.nuntius"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.mikadore.nuntius"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.getByName("main").java.srcDir(
        layout.buildDirectory.dir("generated/openapi/src/main/java").get().asFile,
    )
    buildFeatures {
        compose = true
    }
}

openApiGenerate {
    generatorName.set("java")
    inputSpec.set("$projectDir/openapi/openapi.json")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("dev.mikadore.nuntius.network.api")
    modelPackage.set("dev.mikadore.nuntius.network.model")
    invokerPackage.set("dev.mikadore.nuntius.network")
    configOptions.set(
        mapOf(
            "library" to "retrofit2",
            "serializationLibrary" to "gson",
            "dateLibrary" to "legacy",
            "annotationLibrary" to "none",
            "hideGenerationTimestamp" to "true",
            "useBeanValidation" to "false",
        ),
    )
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "apiTests" to "false",
            "modelTests" to "false",
            "apiDocs" to "false",
            "modelDocs" to "false",
            "supportingFiles" to "CollectionFormats.java,StringUtil.java",
        ),
    )
}

tasks.matching { it.name.startsWith("ksp") || it.name.startsWith("compile") }.configureEach {
    dependsOn(tasks.named("openApiGenerate"))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.play.services.code.scanner)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.gson.fire)
    implementation(libs.jsr305)
    implementation(libs.javax.annotation.api)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
