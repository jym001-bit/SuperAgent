param([switch]$Real)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot 'target\superagent-java.jar'
$sourcePath = Join-Path $projectRoot 'target\demo-source'
$modeState = if ($Real) { 'target\real-state' } else { 'target\demo-state' }
$statePath = Join-Path $projectRoot $modeState
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'Build the JAR first: mvn package'
}
if (-not (Test-Path -LiteralPath $sourcePath)) {
    New-Item -ItemType Directory -Path $sourcePath | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples\tiny-java\Hello.java') -Destination $sourcePath
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples\tiny-java\.gitignore') -Destination $sourcePath
    & git -C $sourcePath init
    if ($LASTEXITCODE -ne 0) { throw 'git init failed' }
    & git -C $sourcePath add -- Hello.java .gitignore
    if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
    & git -C $sourcePath -c user.name=Demo -c user.email=demo@example.invalid commit -m 'Initial demo project'
    if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
}
$previousState = $env:SUPERAGENT_STATE_DIR
try {
    $env:SUPERAGENT_STATE_DIR = $statePath
    if ($Real) {
        & java -jar $jarPath --repo $sourcePath
    } else {
        & java -jar $jarPath --repo $sourcePath --demo
    }
    if ($LASTEXITCODE -ne 0) { throw "Application exited with code $LASTEXITCODE" }
} finally {
    $env:SUPERAGENT_STATE_DIR = $previousState
}
