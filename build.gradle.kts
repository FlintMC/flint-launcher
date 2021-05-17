/*
 * FlintMC
 * Copyright (C) 2020-2021 LabyMedia GmbH and contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

plugins {
    id("org.cadixdev.licenser") version "0.5.1"
    id("java-library")
}

group = "net.flintmc.launcher"

fun RepositoryHandler.flintRepository(){
    maven {
        setUrl("https://dist.labymod.net/api/v1/maven/release")
        name = "Flint"

        var bearerToken = System.getenv("FLINT_DISTRIBUTOR_BEARER_TOKEN")

        if (bearerToken == null && project.hasProperty("net.flintmc.distributor.bearer-token")) {
            bearerToken = project.property("net.flintmc.distributor.bearer-token").toString()
        }

        if (bearerToken != null) {
            credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                value = "Bearer $bearerToken"
            }

            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://plugins.gradle.org/m2/")
    }
}

plugins.withId("java") {
    apply<MavenPublishPlugin>()
    plugins.apply("org.cadixdev.licenser")

    version = System.getenv().getOrDefault("VERSION", "1.0.0")

    tasks.withType<JavaCompile> {
        options.isFork = true
    }

    license {
        header = file("LICENSE-HEADER")
        include("**/*.java")
        include("**/*.kts")

        tasks {
            create("gradle") {
                files = project.files("build.gradle.kts", "settings.gradle.kts")
            }
        }
    }

    java {
        withJavadocJar()
        withSourcesJar()
    }
}

tasks.javadoc {
    setDestinationDir(file("docs/generated"))
}

dependencies {
    api("org.apache.logging.log4j", "log4j-api", "2.8.1")
    api("com.beust", "jcommander", "1.78")
    api("commons-io", "commons-io", "2.6")

    runtimeOnly("org.apache.logging.log4j", "log4j-core", "2.8.1")
}

configure<PublishingExtension> {
    publications {
        create<MavenPublication>(project.name) {
            from(components["java"])
        }
    }

    repositories {
        flintRepository()
    }
}
