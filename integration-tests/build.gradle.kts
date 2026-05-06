plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    testImplementation(project(":driver"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<Test>().configureEach {
    systemProperty("sshsqlite.projectVersion", rootProject.version.toString())
    systemProperty("sshsqlite.soakProfile", rootProject.providers.gradleProperty("soakProfile").getOrElse("short"))
    rootProject.providers.gradleProperty("largeResultRows").orNull?.let { systemProperty("sshsqlite.largeResultRows", it) }
    rootProject.providers.gradleProperty("largeResultPayloadBytes").orNull?.let { systemProperty("sshsqlite.largeResultPayloadBytes", it) }
    rootProject.providers.gradleProperty("largeResultFetchSize").orNull?.let { systemProperty("sshsqlite.largeResultFetchSize", it) }
    rootProject.providers.gradleProperty("largeResultHeapGrowthRatio").orNull?.let { systemProperty("sshsqlite.largeResultHeapGrowthRatio", it) }
    systemProperty("sshsqlite.soakReport", rootProject.layout.buildDirectory.file("reports/soak/verifySoak.json").get().asFile.absolutePath)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("soak", "writeRelease", "legacyHelper")
    }
}

val legacyHelperTest by tasks.registering(Test::class) {
    description = "Runs optional legacy helper integration tests."
    group = "verification"
    dependsOn(rootProject.tasks.named("buildHelper"))
    systemProperty("sshsqlite.helperBinary", rootProject.layout.buildDirectory.file("helper/sshsqlite-helper").get().asFile.absolutePath)
    useJUnitPlatform {
        includeTags("legacyHelper")
    }
}

val soakTest by tasks.registering(Test::class) {
    description = "Runs bounded or full SSHSQLite soak tests. Use -PsoakProfile=full for production criteria."
    group = "verification"
    shouldRunAfter(tasks.named("test"))
    useJUnitPlatform {
        includeTags("soak")
    }
}

val writeReleaseTest by tasks.registering(Test::class) {
    description = "Runs disposable write-capable release backup/restore rehearsal tests."
    group = "verification"
    shouldRunAfter(tasks.named("test"))
    systemProperty("sshsqlite.writeReleaseReport", rootProject.layout.buildDirectory.file("reports/write-release/backup-restore.json").get().asFile.absolutePath)
    useJUnitPlatform {
        includeTags("writeRelease")
    }
}
