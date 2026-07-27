// Kotlin JVM cho :core được cấp qua classpath thay vì plugin marker: marker
// org.jetbrains.kotlin.jvm.gradle.plugin không có trong cache offline của máy build, còn artifact thật
// kotlin-gradle-plugin:1.9.24 thì có (đang dùng cho kotlin.android). Giữ được --offline cho mọi lệnh.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    }
}

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
