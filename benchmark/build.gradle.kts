plugins { alias(libs.plugins.strata.android.benchmark) }

dependencies {
    // PerfBudgets and BudgetMath (pure Kotlin, unit-tested in :testing:fixtures).
    implementation(projects.testing.fixtures)
}
