// :core — quyết định, dữ liệu, metadata, config. Kotlin JVM thuần.
//
// Module này KHÔNG có Android và KHÔNG có dadb trên classpath, một cách cố ý. Đó là toàn bộ điểm của
// nó: máy trạng thái không thể gọi thiết bị dù có muốn, nên lỗi kiểu "hoà giải gọi transport ngay
// trong đường vẽ màn hình" (đo được trên xe 2026-07-27) trở thành lỗi biên dịch thay vì lỗi rà soát.
plugins {
    id("java-library")
    // SourceRoots được dùng bởi test của cả hai module; test-fixtures giữ đúng một bản thay vì copy.
    id("java-test-fixtures")
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
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
