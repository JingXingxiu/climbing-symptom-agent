param(
    [switch]$AllowPaidCalls,
    [ValidateRange(1, 10)]
    [int]$MaxCalls = 10
)

$ErrorActionPreference = 'Stop'
if (-not $AllowPaidCalls) {
    throw 'This command sends at most MaxCalls requests to the configured reviewer. Re-run with -AllowPaidCalls only when you want those API calls.'
}
foreach ($taskName in @('AI_REVIEW_EVAL_PROVIDER', 'AI_REVIEW_EVAL_MODEL', 'AI_REVIEW_EVAL_BASE_URL', 'AI_REVIEW_EVAL_API_KEY')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($taskName))) {
        throw "Missing environment variable: $taskName"
    }
}
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$taskPreviousConsent = $env:AI_REVIEW_EVAL_ALLOW_PAID_CALLS
try {
    $env:AI_REVIEW_EVAL_ALLOW_PAID_CALLS = 'YES'
    Push-Location -LiteralPath (Join-Path $taskRoot 'backend')
    try {
        & mvn -pl service/ai -am test '-Dtest=ReviewEvaluationLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dagent.review.evaluation.enabled=true' "-Dagent.review.evaluation.max-calls=$MaxCalls"
        if ($LASTEXITCODE -ne 0) {
            throw 'Evaluation did not finish. Inspect the safe Maven error and configuration; no result is treated as approval.'
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:AI_REVIEW_EVAL_ALLOW_PAID_CALLS = $taskPreviousConsent
}
