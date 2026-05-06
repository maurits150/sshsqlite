plugins {
    `java-library`
}

val driverDistributionJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Builds the desktop-tool-ready JDBC driver jar with runtime dependencies."
    archiveBaseName.set("sshsqlite-driver")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    exclude("org/apache/sshd/agent/unix/**")
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    manifest {
        attributes(
            "Implementation-Title" to "SSHSQLite JDBC Driver",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.assemble {
    dependsOn(driverDistributionJar)
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.apache.sshd:sshd-core:2.13.2")
    implementation("org.apache.sshd:sshd-sftp:2.13.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.slf4j:slf4j-nop:1.7.32")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
