group = "dev.geekthought.firefoxamoled"

patches {
    about {
        name = "Firefox Absolute AMOLED Patches"
        description = "Experimental Pure Black patches for Firefox for Android"
        source = "https://github.com/geekthought/morphe-patches-template"
        author = "geekthought"
        contact = "na"
        website = "https://github.com/geekthought/morphe-patches-template"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
