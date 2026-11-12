import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec

/**
 * `strata.gates`: applied to the root project. Registers the repository-wide gates:
 * `checkEntitlements` (T-CI-08), `ktlintCheck` / `ktlintFormat` (ktlint CLI from a configuration),
 * and the aggregate `checkGates`.
 */
class RootGatesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
            val ktlint =
                configurations.create("ktlint") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    description = "ktlint command line (build tool only, never on an app classpath)."
                }
            dependencies.add(ktlint.name, "com.pinterest.ktlint:ktlint-cli:$ktlintVersion:all")

            val sources =
                fileTree(rootDir) {
                    include("**/*.kt", "**/*.kts")
                    exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "research/**")
                }

            fun registerKtlint(name: String, format: Boolean) =
                tasks.register(name, JavaExec::class.java) {
                    group = "verification"
                    description =
                        if (format) "Fix ktlint violations in place." else "ktlint over all Kotlin sources."
                    classpath = ktlint
                    mainClass.set("com.pinterest.ktlint.Main")
                    // ktlint reads the working directory's .editorconfig; globs are resolved relative to it.
                    workingDir = rootDir
                    val args =
                        mutableListOf(
                            "--relative",
                            "**/*.kt",
                            "**/*.kts",
                            "!**/build/**",
                            "!**/.gradle/**",
                            "!**/.kotlin/**",
                            "!research/**",
                        )
                    if (format) args += "--format"
                    setArgs(args)
                    if (!format) {
                        inputs.files(sources).withPropertyName("kotlinSources")
                        inputs.file(rootProject.file(".editorconfig")).withPropertyName("editorconfig")
                        val stamp = layout.buildDirectory.file("ktlint.ok")
                        outputs.file(stamp)
                        doLast {
                            stamp
                                .get()
                                .asFile
                                .apply { parentFile.mkdirs() }
                                .writeText("ok")
                        }
                    }
                }
            registerKtlint("ktlintCheck", format = false)
            registerKtlint("ktlintFormat", format = true)

            val entitlements =
                tasks.register("checkEntitlements", CheckEntitlementsTask::class.java) {
                    group = "verification"
                    description =
                        "T-CI-08: no isPro/premium/entitle/purchase/billing symbols in src/main or the theme schema."
                    rootPath.set(rootDir.absolutePath)
                    this.sources.from(
                        fileTree(rootDir) {
                            include("**/src/main/**")
                            include("spec/theme.schema.json", "spec/sample-themes/*.json")
                            include("**/assets/themes/**")
                            exclude("build-logic/**", "**/build/**", "research/**", ".claude/**")
                        }.matching {
                            // Text sources only; binary resources cannot carry symbols.
                            include(
                                "**/*.kt",
                                "**/*.java",
                                "**/*.xml",
                                "**/*.json",
                                "**/*.pro",
                                "**/*.txt",
                                "**/*.properties",
                            )
                        },
                    )
                }
            val licences =
                tasks.register("checkLicences", CheckLicencesTask::class.java) {
                    group = "verification"
                    description = "T-CI-05: every bundled font and allow-listed runtime artifact is in licences.json."
                    val assets = layout.projectDirectory.dir("feature/settings/src/main/assets")
                    licencesJson.set(assets.file("licences.json"))
                    assetsDir.set(assets)
                    allowList.set(layout.projectDirectory.file("config/dependency-allowlist.txt"))
                    fonts.from(
                        fileTree(rootDir) {
                            include("**/src/main/res/font/**", "**/src/main/assets/fonts/**")
                            exclude("**/build/**", "research/**", ".claude/**")
                        },
                    )
                }
            tasks.register("checkGates") {
                group = "verification"
                description = "Every repository-wide static gate."
                dependsOn(entitlements, licences, "ktlintCheck")
            }
        }
}
