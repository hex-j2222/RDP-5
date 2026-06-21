plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gotohex.rdp"
    compileSdk = 35
    // تثبيت إصدار NDK صراحةً — يمنع Gradle من اختيار أحدث NDK مثبّت على الجهاز/CI
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.gotohex.rdp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        resourceConfigurations += listOf("en", "ar")

        // ── aFreeRDP native bridge (RDP) ─────────────────────────────────────
        // ABIs to build the native FreeRDP bridge for.
        // arm64-v8a covers virtually all modern Android devices.
        // x86_64 covers emulators.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // عندما تُبنى FreeRDP فعلياً (submodule موجود)، find_package(OpenSSL)
        // التقليدي في CMake يبحث في مسارات النظام (Linux/host) بدل sysroot
        // الخاص بـ NDK، فيجد OpenSSL غير متوافق مع target Android ويفشل
        // تكوين CMake بالكامل (هذا سبب فشل configureCMakeDebug في الـ CI).
        // الحل: نمرر جذر OpenSSL المخصص لأندرويد (مبني مسبقاً في CI عبر
        // سكربت FreeRDP الرسمي android-build-openssl.sh، انظر main.yml)
        // إلى CMake عبر متغير البيئة ANDROID_OPENSSL_ROOT.
        // نمرر ANDROID_OPENSSL_ROOT فقط — CMakeLists.txt يحتسب المسار الكامل لكل ABI.
        // FreeRDP يُبنى كـ prebuilt منفصل في CI (انظر main.yml)، ولا يُبنى داخل Gradle.
        val androidOpenSslRoot = System.getenv("ANDROID_OPENSSL_ROOT")
        if (!androidOpenSslRoot.isNullOrBlank()) {
            externalNativeBuild {
                cmake {
                    arguments += "-DANDROID_OPENSSL_ROOT=$androidOpenSslRoot"
                }
            }
        }
    }

    // ── Native build via CMake ────────────────────────────────────────────────
    // CMakeLists.txt already guards against missing FreeRDP submodule:
    // if FreeRDP/CMakeLists.txt is absent it prints a warning and skips
    // native build silently — the Kotlin fallback + VNC + SSH still work.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // bcprov-jdk18on و jsch كلاهما يشحن نفس مسار MANIFEST.MF
            // داخل multi-release jar (versions/9 و versions/11)، ما يسبب
            // فشل mergeDebugJavaResource. هذا الملف مجرد بيان OSGi
            // وغير ضروري وقت التشغيل على أندرويد، فنستثنيه بالكامل
            // بدلاً من استثناء نسخة واحدة فقط.
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // All detectors below crash with IncompatibleClassChangeError because
        // the Kotlin Analysis API interfaces they depend on changed from classes
        // to interfaces between the Kotlin version used to compile lint jars
        // and the one used by the compiler plugin. Disabling them globally
        // until the upstream libraries ship updated lint artifacts.
        disable += setOf(
            // androidx.compose.runtime lint
            "FlowOperatorInvokedInComposition",
            "FrequentlyChangingValue",
            "ComposableDestinationInComposeNavigator",
            "ComposableLambdaParameterNaming",
            "ComposableLambdaParameterPosition",
            "CompositionLocalNaming",
            "RememberReturnType",
            "UnrememberedAnimatable",
            "UnrememberedMutableInteractionSource",
            "UnrememberedMutableState",
            // androidx.lifecycle lint
            "NullSafeMutableLiveData",
            "StateFlowValueCalledInComposition",
            "LifecycleWhenChecks",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)

    // Lottie Animation
    implementation(libs.lottie.compose)

    // Gson
    implementation(libs.gson)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Splash Screen
    implementation(libs.splashscreen)

    // BouncyCastle for TLS/NLA
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bctls)

    // JSch — pure-Java SSH2 client
    implementation(libs.jsch)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
