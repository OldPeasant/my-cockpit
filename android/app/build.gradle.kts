import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.openapi.generator)
    // Applied up front so every Test task has its JaCoCo extension when the block below configures it.
    jacoco
}

android {
    namespace = "ch.sonensei.cockpit"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.sonensei.cockpit"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Instrumented tests run in HiltTestApplication (src/androidTest/.../HiltTestRunner.kt).
        testInstrumentationRunner = "ch.sonensei.cockpit.HiltTestRunner"
    }

    buildTypes {
        debug {
            // Coverage: ./gradlew createDebugUnitTestCoverageReport -> app/build/reports/coverage/test/debug/
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        // Tests that run both on a device and on Robolectric (the Phase 3 demo).
        listOf("test", "androidTest").forEach { named(it) { kotlin.srcDir("src/sharedTest/java") } }
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest.
            isIncludeAndroidResources = true
        }
    }
    lint {
        // Warnings fail the build. Version-freshness checks are off: they would break the check whenever a
        // library publishes a release; upgrades are explicit tasks instead.
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// The Phase 3 demo (package smoke) needs a running e2e stack, so unit test runs leave it out unless its address is given:
// ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=http://127.0.0.1:<port>/
val e2eBaseUrl = providers.gradleProperty("e2eBaseUrl")

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        // Robolectric loads app classes without a code source location; without this JaCoCo records 0 coverage.
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
    if (e2eBaseUrl.isPresent) {
        systemProperty("cockpit.e2e.baseUrl", e2eBaseUrl.get())
        // The stack's state is not a task input: never skip the run as up to date.
        outputs.upToDateWhen { false }
    } else {
        filter.excludeTestsMatching("ch.sonensei.cockpit.smoke.*")
    }
}

// Retrofit client from the API contract, generated at build time into build/ (never committed).
val openApiOutputDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(rootProject.file("../api/openapi.yaml").absolutePath)
    outputDir.set(openApiOutputDir.get().asFile.absolutePath)
    packageName.set("ch.sonensei.cockpit.core.network.generated")
    library.set("jvm-retrofit2")
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
            "dateLibrary" to "java8",
            "enumPropertyNaming" to "UPPERCASE",
            "sourceFolder" to "src/main/kotlin",
        ),
    )
    // Open objects (additionalProperties: true, e.g. settings registered by other modules) become JSON objects:
    // the Kotlin generator emits invalid code for them.
    schemaMappings.set(
        mapOf(
            "Settings" to "kotlinx.serialization.json.JsonObject",
            "SettingsPatch" to "kotlinx.serialization.json.JsonObject",
        ),
    )
    // core.network builds Retrofit itself, so the generated ApiClient is skipped. Only src/main/kotlin is compiled.
    ignoreFileOverride.set(file("openapi-generator-ignore").absolutePath)
    cleanupOutput.set(true)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(
            openApiOutputDir
                .get()
                .dir("src/main/kotlin")
                .asFile.absolutePath,
        )
    }
}

tasks.named { it.startsWith("compile") || it.startsWith("ksp") }.configureEach {
    dependsOn(tasks.openApiGenerate)
}

ktlint {
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

room {
    // Exported schemas are committed, so migrations can be tested against them.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.tink.android)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
