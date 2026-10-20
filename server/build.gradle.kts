plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	kotlin("plugin.jpa") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
	jacoco
}

group = "ch.sonensei"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot (versions from the Boot BOM)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0")
	implementation("net.logstash.logback:logstash-logback-encoder:8.1")

	// Domain libraries (SPEC.md §2.1)
	implementation("org.eclipse.angus:angus-mail")
	implementation("org.mnode.ical4j:ical4j:4.1.1")
	implementation("com.googlecode.ez-vcard:ez-vcard:0.12.1")
	implementation("com.vladsch.flexmark:flexmark:0.64.8")
	implementation("com.vladsch.flexmark:flexmark-ext-gfm-strikethrough:0.64.8")
	implementation("com.vladsch.flexmark:flexmark-ext-gfm-tasklist:0.64.8")
	implementation("com.vladsch.flexmark:flexmark-ext-tables:0.64.8")
	implementation("com.vladsch.flexmark:flexmark-ext-autolink:0.64.8")
	implementation("org.jsoup:jsoup:1.21.2")
	implementation("net.coobird:thumbnailator:0.4.20")
	implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")

	// Auth: Argon2 via spring-security-crypto (from the security starter) + BouncyCastle, TOTP, WebAuthn
	implementation("org.bouncycastle:bcprov-jdk18on:1.81")
	implementation("com.eatthepath:java-otp:0.4.0")
	implementation("com.webauthn4j:webauthn4j-core:0.29.5.RELEASE")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("io.kotest:kotest-assertions-core:6.0.4")
	testImplementation("io.mockk:mockk:1.14.6")
	testImplementation("com.ninja-squad:springmockk:5.0.1")
	testImplementation("org.wiremock:wiremock-standalone:3.13.1")
	testImplementation("com.icegreen:greenmail-junit5:2.1.5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

// JPA entities must be open for Hibernate proxies (plugin.jpa adds the no-arg constructors).
allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

// `test` (part of `check`): unit tests + Spring tests against the shared Postgres Testcontainer.
// `integrationTest` (not part of `check`): tests tagged `external` (GreenMail, Radicale, WireMock, ...).
// The contract file and UPDATE_OPENAPI are inputs, so OpenApiContractTest is not skipped as UP-TO-DATE
// when api/openapi.yaml changed or a regeneration is requested (see api/README.md).
tasks.test {
	useJUnitPlatform {
		excludeTags("external")
	}
	inputs
		.files("../api/openapi.yaml")
		.withPropertyName("openApiContract")
		.withPathSensitivity(PathSensitivity.NONE)
	inputs.property("updateOpenApi", providers.environmentVariable("UPDATE_OPENAPI").orElse(""))
	// Gradle's default of 512 MB is too small for the cached Spring contexts plus the 60 MB upload test.
	maxHeapSize = "1g"
}

tasks.register<Test>("integrationTest") {
	description = "Runs tests tagged 'external' (external-system integration tests)."
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	val testSourceSet = sourceSets.test.get()
	testClassesDirs = testSourceSet.output.classesDirs
	classpath = testSourceSet.runtimeClasspath
	useJUnitPlatform {
		includeTags("external")
	}
	shouldRunAfter(tasks.test)
}

// Regenerates api/openapi.yaml (same as `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`, see api/README.md).
tasks.register<Test>("updateOpenApi") {
	description = "Regenerates the API contract api/openapi.yaml from the running server."
	group = "documentation"
	val testSourceSet = sourceSets.test.get()
	testClassesDirs = testSourceSet.output.classesDirs
	classpath = testSourceSet.runtimeClasspath
	useJUnitPlatform()
	filter { includeTestsMatching("*OpenApiContractTest") }
	environment("UPDATE_OPENAPI", "1")
	outputs.upToDateWhen { false }
}

// Coverage: ./gradlew test jacocoTestReport -> build/reports/jacoco/test/ (XML for tools, HTML for people)
tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
	}
}
