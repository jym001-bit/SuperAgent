[CmdletBinding()]
param(
    [switch]$Real,
    [string[]]$Only = @(),
    [string]$Tasks = 'eval/tasks',
    [string]$Out = 'target/eval-reports',
    [int]$TimeoutSeconds = 300,
    [switch]$SkipBuild,
    [switch]$List,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if ($Help) {
        Write-Host @'
Super-agent 评测入口（默认离线，不联网、不调用真实模型）

  .\scripts\eval.ps1                         离线模式：ScriptedModel 按脚本执行
  .\scripts\eval.ps1 -Real                   真实模型模式（需要 API Key，会产生调用费用）
  .\scripts\eval.ps1 -Only hello-output      只跑指定任务（可逗号分隔多个）
  .\scripts\eval.ps1 -List                   只列出数据集任务
  .\scripts\eval.ps1 -SkipBuild              跳过 mvn package，直接使用已有 jar

其它参数: -Tasks <目录> -Out <目录> -TimeoutSeconds <秒>

报告: target/eval-reports/eval-report.json 与 eval-report.md
证据: target/eval-<uuid>/（每个任务一份 source/state/evidence）
退出码: 0 全部通过；1 存在未通过任务；2 参数、数据集或模型配置错误。
'@
        exit 0
    }

    $jar = Join-Path $root 'target/superagent-java.jar'
    if (-not $SkipBuild) {
        Write-Host '构建 target/superagent-java.jar ...'
        & mvn -o -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            Write-Host "构建失败（mvn 退出码 $LASTEXITCODE）。请先修复编译错误；已有 jar 时可加 -SkipBuild。" -ForegroundColor Red
            exit $LASTEXITCODE
        }
    }
    elseif (-not (Test-Path $jar)) {
        Write-Host "找不到 $jar，且指定了 -SkipBuild。请先运行 mvn -o -DskipTests package。" -ForegroundColor Red
        exit 2
    }

    $javaArgs = @(
        '-cp', $jar,
        'dev.superagent.eval.EvalCli',
        '-tasks', $Tasks,
        '-out', $Out,
        '-artifacts', (Join-Path $root 'target'),
        '-timeout', "$TimeoutSeconds"
    )
    if ($Real) { $javaArgs += '-real' }
    if ($List) { $javaArgs += '-list' }
    foreach ($entry in $Only) {
        foreach ($id in ($entry -split ',')) {
            if ($id.Trim()) { $javaArgs += '-only'; $javaArgs += $id.Trim() }
        }
    }

    & java @javaArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
