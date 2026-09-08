plugins {
    id("java")
    id("antlr")
    id("io.github.sgtsilvio.gradle.maven-central-publishing") version "0.4.1"
    id("io.github.sgtsilvio.gradle.metadata") version "0.6.0"
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    compileOnly("org.jetbrains:annotations:26.0.2")
    testCompileOnly("org.jetbrains:annotations:26.0.2")

    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    api("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

sourceSets {
    main {
        java {
            srcDirs("src/main/java")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21

    withSourcesJar()
    withJavadocJar()
}

metadata {
    moduleName = "langjal"
    readableName = "JAL: Java Assembly Language"
    description = "The official implementation of Java Assembly Language."
    license {
        shortName = "MIT"
        fullName = "MIT License"
        url = "https://github.com/JVMLand/LangJAL/blob/main/LICENSE"
    }
    developers {
        register("peyang") {
            fullName = "Peyang"
            email = "peyang@peya.tokyo"
        }
    }

    github {
        org = "JVMLand"
        repo = "LangJAL"
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications)
}

publishing {
    publications {
        register<MavenPublication>("main") {
            from(components["java"])
        }
    }
}

tasks.named("sourcesJar", Zip::class) {
    dependsOn("generateGrammarSource")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
