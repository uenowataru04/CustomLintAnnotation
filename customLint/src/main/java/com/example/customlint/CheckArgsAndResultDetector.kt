package com.example.customlint

import com.android.tools.lint.checks.AbstractAnnotationDetector
import com.android.tools.lint.checks.AnnotationDetector
import com.android.tools.lint.checks.PermissionDetector
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import org.jetbrains.uast.*

/**
 * Created by mick on 2019-08-14.
 */
/**
 * CheckResultDetectorを書き換えたもの。
 * 引数をチェックする以外はCheckResultDetectorと全く同じ
 * [com.android.tools.lint.checks.CheckResultDetector]
 */
class CheckArgsAndResultDetector : AbstractAnnotationDetector(), SourceCodeScanner {

    override fun applicableAnnotations(): List<String> = listOf("com.example.customlint.CheckArgsAndResult")

    override fun visitAnnotationUsage(
        context: JavaContext,
        usage: UElement,
        type: AnnotationUsageType,
        annotation: UAnnotation,
        qualifiedName: String,
        method: PsiMethod?,
        referenced: PsiElement?,
        annotations: List<UAnnotation>,
        allMemberAnnotations: List<UAnnotation>,
        allClassAnnotations: List<UAnnotation>,
        allPackageAnnotations: List<UAnnotation>
    ) {
        method ?: return

        // Don't inherit CheckResult from packages for now; see
        //  https://issuetracker.google.com/69344103
        // for a common (dagger) package declaration that doesn't have
        // a @CanIgnoreReturnValue exclusion on inject.
        if (allPackageAnnotations.contains(annotation)) return

        if (qualifiedName == AnnotationDetector.ERRORPRONE_CAN_IGNORE_RETURN_VALUE) return

        /* ここを書き加えた */
        if (checkArguments(usage)) return

        checkResult(
            context, usage, method, annotation,
            allMemberAnnotations, allClassAnnotations
        )
    }

    /**
     * 引数をチェックして CompositeDisposable がなければtrueを返す
     */
    private fun checkArguments(element: UElement): Boolean {
        // 引数の型のリスト
        val argTypeList = element.getUCallExpression()?.valueArguments?.map {
            it.getExpressionType()?.canonicalText
        }
        return argTypeList?.contains("io.reactivex.disposables.CompositeDisposable") ?: false
    }

    private fun checkResult(
        context: JavaContext,
        element: UElement,
        method: PsiMethod,
        annotation: UAnnotation,
        allMemberAnnotations: List<UAnnotation>,
        allClassAnnotations: List<UAnnotation>
    ) {
        if (isExpressionValueUnused(element)) {
            // If this CheckResult annotation is from a class, check to see
            // if it's been reversed with @CanIgnoreReturnValue
            val memberHasAnnotation = UastLintUtils.containsAnnotation(
                allMemberAnnotations,
                AnnotationDetector.ERRORPRONE_CAN_IGNORE_RETURN_VALUE
            )
            val classHasAnnotation = UastLintUtils.containsAnnotation(
                allClassAnnotations,
                AnnotationDetector.ERRORPRONE_CAN_IGNORE_RETURN_VALUE
            )
            if (memberHasAnnotation || classHasAnnotation) {
                return
            }
            if (method.returnType == PsiType.VOID) {
                return
            }

            val methodName = JavaContext.getMethodName(element)
            val suggested = UastLintUtils.getAnnotationStringValue(annotation, AnnotationDetector.ATTR_SUGGEST)

            // Failing to check permissions is a potential security issue (and had an existing
            // dedicated issue id before which people may already have configured with a
            // custom severity in their LintOptions etc) so continue to use that issue
            // (which also has category Security rather than Correctness) for these:
            var issue = CHECK_RESULT
            // methodの名前がnullじゃない かつ その名前が"check"で始まる かつ その名前に"Permission"が含まれる時
            if (methodName != null && methodName.startsWith("check") && methodName.contains("Permission")) {
                issue = PermissionDetector.CHECK_PERMISSION
            }

            var message = String.format(
                "The result of `%1\$s` is not used",
                methodName
            )
            if (suggested != null) {
                // TODO: Resolve suggest attribute (e.g. prefix annotation class if it starts
                // with "#" etc?
                message = String.format(
                    "The result of `%1\$s` is not used; did you mean to call `%2\$s`?",
                    methodName, suggested
                )
            } else if ("intersect" == methodName && context.evaluator.isMemberInClass(
                    method,
                    "android.graphics.Rect"
                )
            ) {
                message += ". If the rectangles do not intersect, no change is made and the " +
                        "original rectangle is not modified. These methods return false to " +
                        "indicate that this has happened."
            }

            val fix = suggested.takeIf { it != null }?.let { fix().data(it) }

            val location = context.getLocation(element)
            report(context, issue, element, location, message, fix)
        }
    }

    private fun isExpressionValueUnused(element: UElement): Boolean {
        var prev = element.getParentOfType<UExpression>(
            UExpression::class.java, false
        ) ?: return true

        var curr = prev.uastParent ?: return true
        while (curr is UQualifiedReferenceExpression && curr.selector === prev) {
            prev = curr
            curr = curr.uastParent ?: return true
        }

        @Suppress("RedundantIf")
        if (curr is UBlockExpression) {
            if (curr.uastParent is ULambdaExpression && isKotlin(curr.sourcePsi)) {
                // Lambda block: for now assume used (e.g. parameter
                // in call. Later consider recursing here to
                // detect if the lambda itself is unused.
                return false
            }

            // In Java, it's apparent when an expression is unused:
            // the parent is a block expression. However, in Kotlin it's
            // much trickier: values can flow through blocks and up through
            // if statements, try statements.
            //
            // In Kotlin, we consider an expression unused if its parent
            // is not a block, OR, the expression is not the last statement
            // in the block, OR, recursively the parent expression is not
            // used (e.g. you're in an if, but that if statement is itself
            // not doing anything with the value.)
            val block = curr
            val expression = prev
            val index = block.expressions.indexOf(expression)
            if (index == -1) {
                return true
            }

            if (index < block.expressions.size - 1) {
                // Not last child
                return true
            }

            if (isJava(curr.sourcePsi)) {
                // In Java there's no implicit passing to the parent
                return true
            }

            // It's the last child: see if the parent is unused
            val parent = curr.uastParent ?: return true
            if (parent is UMethod || parent is UClassInitializer) {
                return true
            }
            return isExpressionValueUnused(parent)
        } else if (curr is UMethod && curr.isConstructor) {
            return true
        } else {
            // Some other non block node type, such as assignment,
            // method declaration etc: not unused
            // TODO: Make sure that a void/unit method inline declaration
            // works correctly
            return false
        }
    }

    companion object {
        private val IMPLEMENTATION = Implementation(
            CheckArgsAndResultDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )

        /** Method result should be used  */
        @JvmField
        val CHECK_RESULT = Issue.create(
            id = "CheckResult",
            briefDescription = "Ignoring results",
            explanation = """
                Some methods have no side effects, an calling them without doing something \
                without the result is suspicious.""",
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.WARNING,
            implementation = IMPLEMENTATION
        )
    }
}