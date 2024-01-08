/*
 * Ani
 * Copyright (C) 2022-2024 Him188
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

@file:Suppress("OPT_IN_IS_NOT_ENABLED")

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("kotlinx-atomicfu")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(projects.app.shared)
}

compose.desktop {

    application {
        mainClass = "me.him188.ani.desktop.AniDesktop"
//        jvmArgs("--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.Rpm,
                TargetFormat.Dmg,
                TargetFormat.Exe,
                TargetFormat.Msi,
            )
            packageName = "Ani"
            description = project.description
            vendor = "Him188"
            // adding copyright causes package to fail.
//            copyright = """
//                    Ani
//                    Copyright (C) 2022-2024 Him188
//
//                    This program is free software: you can redistribute it and/or modify
//                    it under the terms of the GNU General Public License as published by
//                    the Free Software Foundation, either version 3 of the License, or
//                    (at your option) any later version.
//
//                    This program is distributed in the hope that it will be useful,
//                    but WITHOUT ANY WARRANTY; without even the implied warranty of
//                    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//                    GNU General Public License for more details.
//
//                    You should have received a copy of the GNU General Public License
//                    along with this program.  If not, see <https://www.gnu.org/licenses/>.
//            """.trimIndent()
            licenseFile.set(rootProject.rootDir.resolve("LICENSE"))
            packageVersion = properties["package.version"].toString()
        }
    }
}

// workaround for resource not found
//kotlin.sourceSets.main.get().resources.srcDir(project(":common").projectDir.resolve("src/androidMain/res/raw"))

tasks.withType(KotlinJvmCompile::class) {
    kotlinOptions.jvmTarget = "11"
}