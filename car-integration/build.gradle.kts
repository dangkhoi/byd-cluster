// :car-integration — JVM module talking to head unit via adb (no Android).
plugins {
    id("java-library")
    id("application")
    id("org.jetbrains.kotlin.jvm")
}

application {
    mainClass.set("com.byd.clusternav.carexec.CarExecCli")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    implementation("dev.mobile:dadb:2.0.0")
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
