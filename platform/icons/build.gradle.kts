plugins { alias(libs.plugins.strata.android.library) }

dependencies {
    api(projects.core.platformApi)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.testing.fakes)
}

// Strata glyph bundle (ARCHITECTURE §9.1, T-THEME-55): the build checks the index and every referenced drawable and
// generates `GlyphBundle` (bundle version + glyph id → drawable). Task type: build-logic `GenerateGlyphBundle`.
val generateGlyphBundle by tasks.registering(GenerateGlyphBundle::class) {
    index.set(layout.projectDirectory.file("src/main/res/raw/glyph_index.txt"))
    drawables.set(layout.projectDirectory.dir("src/main/res/drawable-nodpi"))
    packageName.set("app.strata.launcher.platform.icons")
    output.set(layout.buildDirectory.dir("generated/glyphBundle"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateGlyphBundle, GenerateGlyphBundle::output)
    }
}
