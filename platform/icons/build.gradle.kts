plugins { alias(libs.plugins.strata.android.library) }

dependencies {
    api(projects.core.platformApi)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.testing.fakes)
}

// Strata glyph bundle (ARCHITECTURE §9.1, T-THEME-55): the build checks the index and every referenced drawable and
// generates `GlyphBundle` (bundle version + glyph id → drawable) and the packaged `res/raw/glyph_index.txt`.
// Task type: build-logic `GenerateGlyphBundle`.
// Private overlay (CONTRIBUTING "Public mirror and CI"): when `glyphs-private/` exists, its `index.txt` is merged after
// the public index and its `res/` is an extra resource directory. The mirror strips the directory from every commit;
// without it the build is the public stub alone.
val glyphsPrivate = layout.projectDirectory.dir("glyphs-private")
val hasPrivateGlyphs = glyphsPrivate.file("index.txt").asFile.isFile

val generateGlyphBundle by tasks.registering(GenerateGlyphBundle::class) {
    index.set(layout.projectDirectory.file("src/main/res/raw/glyph_index.txt"))
    drawables.set(layout.projectDirectory.dir("src/main/res/drawable-nodpi"))
    if (hasPrivateGlyphs) {
        overlayIndex.set(glyphsPrivate.file("index.txt"))
        overlayDrawables.set(glyphsPrivate.dir("res/drawable-nodpi"))
    }
    packageName.set("app.strata.launcher.platform.icons")
    output.set(layout.buildDirectory.dir("generated/glyphBundle"))
    resOutput.set(layout.buildDirectory.dir("generated/glyphIndexRes"))
}

if (hasPrivateGlyphs) {
    android.sourceSets.named("main") { res.srcDir(glyphsPrivate.dir("res")) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateGlyphBundle, GenerateGlyphBundle::output)
        // The merged index overrides src/main/res/raw/glyph_index.txt (a generated directory is a higher layer).
        variant.sources.res?.addGeneratedSourceDirectory(generateGlyphBundle, GenerateGlyphBundle::resOutput)
    }
}
