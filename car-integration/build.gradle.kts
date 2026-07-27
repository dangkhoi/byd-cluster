// :car-integration — nói chuyện với head unit qua adb. Kotlin JVM thuần, KHÔNG có Android.
//
// Ranh giới ở đây hẹp hơn tên gọi ban đầu, và đó là một phát hiện thiết kế ngày 2026-07-27: "nói chuyện
// với thiết bị" gồm hai loại rất khác nhau. Loại thứ nhất là gửi lệnh qua adb tới head unit — thứ mà CLI
// runner cần chạy được mà không có APK, nên nó phải là JVM thuần và nằm ở module này. Loại thứ hai là
// gọi API Android cục bộ của chính app (PackageManager, DisplayManager, AtomicFile, broadcast) — thứ đó
// không phải car-execution, và nó ở lại :app.
plugins {
    id("java-library")
    // Cho phép chạy runner headless: ./gradlew :car-integration:run --args="observe --host <ip>"
    id("application")
}

application {
    mainClass.set("com.byd.clusternav.carexec.CarExecCli")
}

apply(plugin = "org.jetbrains.kotlin.jvm")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    api(project(":core"))
    implementation("dev.mobile:dadb:1.2.10")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
