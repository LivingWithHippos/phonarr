import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.github.livingwithhippos.phonarr"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.github.livingwithhippos.phonarr"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing. The keystore is keystore.jks in the project root (git-ignored) if it
    // exists, otherwise the file named by the KEYSTORE variable (what the CI workflow uses).
    // Passwords and alias come from local.properties (keystore.password, key.alias,
    // key.password), otherwise from KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD.
    // Without a keystore and all three values, the release APK is left unsigned.
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use(::load)
    }
    fun signingValue(property: String, env: String): String? =
        localProps.getProperty(property)?.takeIf { it.isNotEmpty() }
            ?: System.getenv(env)?.takeIf { it.isNotEmpty() }

    val keystore = rootProject.file("keystore.jks").takeIf { it.isFile && it.length() > 0 }
        ?: System.getenv("KEYSTORE")?.let(::file)?.takeIf { it.isFile && it.length() > 0 }
    val storePass = signingValue("keystore.password", "KEYSTORE_PASSWORD")
    val alias = signingValue("key.alias", "KEY_ALIAS")
    val keyPass = signingValue("key.password", "KEY_PASSWORD")
    val canSign = keystore != null && storePass != null && alias != null && keyPass != null
    if (keystore != null && !canSign) {
        logger.warn(
            "Release signing: found $keystore but the password or alias is missing, " +
                    "so the release APK will be unsigned.",
        )
    }
    if (canSign) {
        signingConfigs {
            create("release") {
                storeFile = keystore
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                // No packageScope: with it (androidx/kotlin/kotlinx only), R8 9.5 moved
                // kotlin.collections.CollectionsKt__IteratorsKt out of its package but not its
                // package-private superclass, and the release crashed at startup with an
                // IllegalAccessError. Optimizing the whole app avoids that.
                // packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }

            proguardFiles(
                // Default file with automatically generated optimization rules.
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // AGP's Compose Preview tasks need compiled resources, or creating them fails
            // (and with it `./gradlew tasks`).
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

base.archivesName = "phonarr-${android.defaultConfig.versionName}"

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.jsoup)
    implementation(libs.snakeyaml.engine)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("phonarr.defsDir").orNull?.let { systemProperty("phonarr.defsDir", it) }
    providers.gradleProperty("phonarr.liveSites").orNull?.let { systemProperty("phonarr.liveSites", it) }
    testLogging { showStandardStreams = true }
}
