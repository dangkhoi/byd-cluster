import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Khoá ký release nạp từ keystore.properties (KHÔNG commit — maintainer giữ local cùng release.keystore).
// Người clone không có file này -> release tự fallback ký bằng debug key để build/cài thử được ngay.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply { if (hasKeystore) keystorePropsFile.inputStream().use { load(it) } }

android {
    namespace = "com.byd.clusternav"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.byd.clusternav"
        minSdk = 29
        targetSdk = 34
        versionCode = 63
        versionName = "0.63"
        manifestPlaceholders["appLabel"] = "ClusterNav"   // ★ nhãn launcher — bản debug đổi thành "ClusterNav DEBUG"
    }

    signingConfigs {
        // Ký release bằng keystore.properties (gitignored). Người clone không có -> bỏ qua, dùng debug fallback.
        if (hasKeystore) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile", "release.keystore"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false   // tắt minify: experiment dùng reflection HAL, tránh proguard phá
            // maintainer có keystore.properties -> ký release (chữ ký cố định); người clone -> fallback debug
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isMinifyEnabled = false
            // ★ v0.60-debug (RT2.6): bản DEBUG cài SONG SONG bản release (applicationId riêng) → log không lẫn,
            //   dò nav trên xe mà vẫn giữ bản ổn định. versionName "-debug" + nhãn riêng để phân biệt.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "ClusterNav DEBUG"
        }
    }

    buildFeatures {
        buildConfig = true   // ★ v0.60-debug: NavProbe gate BuildConfig.DEBUG (export external ở bản debug)
    }

    lint {
        // App hobby: không để lint chặn build release (lint-vital hay kén môi trường offline).
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Chạy property test (jqwik) + JUnit 5 off-device qua ./gradlew testDebugUnitTest.
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    // "CÁI KIA" — dadb: ADB client thuần JVM, tự nối localhost:5555 -> uid 2000 -> chạy navopen (HAL trực tiếp,
    // ETA + icon hoàn hảo như DashCast). KÉO okio + bouncycastle transitively.
    // ⚠️ Build LẦN ĐẦU ở cty PHẢI có internet (KHÔNG dùng --offline) để tải dep; sau khi cache xong build offline OK.
    implementation("dev.mobile:dadb:1.2.10")

    // — JVM unit + property tests (off-device, chạy bằng ./gradlew testDebugUnitTest) —
    // JUnit 5 (Jupiter) làm test engine; jqwik cho property-based testing (Property 5/9/14...).
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("net.jqwik:jqwik:1.8.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ─── GÔM APK VỀ 1 CHỖ DUY NHẤT: byd/apks/ ───────────────────────────────────────
// Trước đây APK nằm ở CẢ app/build/outputs/apk/{debug,release}/ (gradle sinh) LẪN byd/apks/ (copy tay) → dễ lẫn.
// Giờ sau mỗi assembleDebug/Release, TỰ copy + đổi tên rõ ràng về byd/apks/, khỏi copy tay:
//   • ClusterNav-debug.apk   = CÀI VÀO XE (khớp chữ ký debug đang cài → update sạch)
//   • ClusterNav-release.apk = để CHIA SẺ (chữ ký release cố định)
// → Chỉ cần nhìn byd/apks/, bỏ qua app/build/outputs.
tasks.register<Copy>("collectApks") {
    // ★ build APK VÀO TRONG repo: apk/ (nơi .gitignore track `apk/ClusterNav-*-release.apk` cho UpdateChecker/README),
    //   KHÔNG ra sibling byd/apks/. Tên CÓ VERSION để khớp rule track + phân biệt bản.
    val apkDir = rootProject.projectDir.resolve("apk")
    val ver = android.defaultConfig.versionName ?: "0"
    into(apkDir)
    from(layout.buildDirectory.dir("outputs/apk/debug"))   { include("*.apk"); rename { "ClusterNav-$ver-debug.apk" } }
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk"); rename { "ClusterNav-$ver-release.apk" } }
    doLast { println("★ APK → ${apkDir.absolutePath}") }
}
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach { finalizedBy("collectApks") }