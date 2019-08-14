package com.example.customlint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.detector.api.Issue

/**
 * Created by mick on 2019-08-14.
 */
@Suppress("unused")
class CustomIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(CheckArgsAndResultDetector.CHECK_RESULT)
}
