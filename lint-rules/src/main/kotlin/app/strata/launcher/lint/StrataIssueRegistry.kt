package app.strata.launcher.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class StrataIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(StringConcatInUiDetector.ISSUE)
    override val api: Int = CURRENT_API
    override val minApi: Int = 8
    override val vendor: Vendor = Vendor(vendorName = "Strata Launcher")
}
