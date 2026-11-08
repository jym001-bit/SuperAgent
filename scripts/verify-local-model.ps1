#Requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateRange(30, 900)]
    [int] $TimeoutSeconds = 240,
    [string] $Resume
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'target/superagent-java.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw '请在项目目录运行 mvn package 生成 JAR。'
}

if ($Resume) {
    $savedRun = (Resolve-Path -LiteralPath $Resume).Path
    $savedSource = Join-Path $savedRun 'source'
    $savedState = Join-Path $savedRun 'state'
    $savedPatch = Join-Path $savedRun 'changes.patch'
    if (-not (Test-Path -LiteralPath (Join-Path $savedState 'state.db') -PathType Leaf) -or
        -not (Test-Path -LiteralPath $savedSource -PathType Container) -or
        -not (Test-Path -LiteralPath $savedPatch -PathType Leaf)) {
        throw '请选择已经生成补丁的 target/model-check-* 运行目录。'
    }
    Write-Host "现有补丁位于 $savedPatch。用 /tasks 找到 READY 的父任务，确认后用 /accept <任务ID> 记录验收。"
    $previousState = $env:SUPERAGENT_STATE_DIR
    try {
        $env:SUPERAGENT_STATE_DIR = $savedState
        & java -jar $jar --repo $savedSource
        if ($LASTEXITCODE -ne 0) { throw "续验进程退出，退出码 $LASTEXITCODE。" }
    } finally {
        $env:SUPERAGENT_STATE_DIR = $previousState
    }
    return
}

$modelKey = $env:SUPERAGENT_API_KEY
if ([string]::IsNullOrWhiteSpace($modelKey)) { $modelKey = $env:DEEPSEEK_API_KEY }
$localConfig = Join-Path $root '.env'
$canUseSavedKey = (-not $env:SUPERAGENT_PROVIDER -or $env:SUPERAGENT_PROVIDER -eq 'deepseek') -and
    (-not $env:SUPERAGENT_BASE_URL -or $env:SUPERAGENT_BASE_URL -match '^https://api\.deepseek\.com(?::443)?(?:/|$)') -and
    (Test-Path -LiteralPath $localConfig -PathType Leaf) -and
    (Select-String -LiteralPath $localConfig -Pattern '^\s*DEEPSEEK_API_KEY\s*=\s*\S' -Quiet)
if ([string]::IsNullOrWhiteSpace($modelKey) -and -not $canUseSavedKey) {
    $secureKey = Read-Host '输入模型 API Key' -AsSecureString
    try {
        $modelKey = [System.Net.NetworkCredential]::new('', $secureKey).Password
    } finally {
        $secureKey.Dispose()
    }
}
if ([string]::IsNullOrWhiteSpace($modelKey) -and -not $canUseSavedKey) { throw '需要模型 API Key。' }

function Invoke-GitChecked {
    param([string[]] $GitArgs)
    & git -c core.hooksPath= @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "Git 验收步骤失败，退出码 $LASTEXITCODE。" }
}

$run = Join-Path $root ('target/model-check-' + [Guid]::NewGuid().ToString('N'))
$source = Join-Path $run 'source'
$review = Join-Path $run 'review'
New-Item -ItemType Directory -Path $source | Out-Null
Set-Content -LiteralPath (Join-Path $source 'README.md') -Value 'Super-agent acceptance fixture'
Invoke-GitChecked @('-C', $source, 'init', '-q')
Invoke-GitChecked @('-C', $source, 'add', 'README.md')
Invoke-GitChecked @('-C', $source, '-c', 'user.name=Super-agent Check',
    '-c', 'user.email=local@example.invalid', 'commit', '-qm', 'Acceptance baseline')

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
try { $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
$tokenBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($tokenBytes) } finally { $rng.Dispose() }
$token = [BitConverter]::ToString($tokenBytes).Replace('-', '')
$api = "http://127.0.0.1:$port"
$headers = @{ Authorization = "Bearer $token" }
$launch = [System.Diagnostics.ProcessStartInfo]::new()
$launch.FileName = (Get-Command java -CommandType Application).Source
$launch.WorkingDirectory = $root
$launch.UseShellExecute = $false
$launch.CreateNoWindow = $true
$launch.Arguments = '-jar "{0}" --serve-local --repo "{1}"' -f $jar, $source
if (-not [string]::IsNullOrWhiteSpace($modelKey)) {
    $launch.EnvironmentVariables['SUPERAGENT_API_KEY'] = $modelKey
}
$launch.EnvironmentVariables['SUPERAGENT_OPERATOR_TOKEN'] = $token
$launch.EnvironmentVariables['SUPERAGENT_PORT'] = [string] $port
$launch.EnvironmentVariables['SUPERAGENT_STATE_DIR'] = Join-Path $run 'state'
$launch.EnvironmentVariables['SUPERAGENT_WORKERS'] = '2'
$launch.EnvironmentVariables['SUPERAGENT_MAX_MODEL_CALLS'] = '12'

$service = [System.Diagnostics.Process]::Start($launch)
try {
    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        if ($service.HasExited) { throw "本地服务启动失败，退出码 $($service.ExitCode)。" }
        try {
            $ready = (Invoke-RestMethod "$api/readyz" -TimeoutSec 2).status -eq 'ready'
            if ($ready) { break }
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $ready) { throw '等待本地服务启动超时。' }

    $request = @{
        mode = 'plan'
        text = '严格拆成三个子任务，节点 key 为 alpha、beta、summary。alpha 创建 alpha.txt，内容 alpha。beta 创建 beta.txt，内容 beta。alpha 和 beta 相互独立。summary 依赖 alpha 和 beta，读取两个文件，按 alpha、beta 的顺序各写一行到 summary.txt。只允许修改这三个文件。'
    } | ConvertTo-Json -Compress
    $submitted = Invoke-RestMethod "$api/v1/tasks" -Method Post -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($request)) -TimeoutSec 150
    $taskUrl = "$api/v1/tasks/$($submitted.id)"
    $plan = Invoke-RestMethod $taskUrl -Headers $headers -TimeoutSec 10
    if ($plan.status -ne 'PLAN_READY') { throw "规划失败。任务 $($plan.id)，$($plan.error)" }
    $nodes = @{}
    foreach ($node in $plan.plan) { $nodes[$node.key] = $node }
    if ($nodes.Count -ne 3 -or -not $nodes.ContainsKey('alpha') -or -not $nodes.ContainsKey('beta') `
            -or -not $nodes.ContainsKey('summary') -or $nodes['alpha'].dependencies.Count -ne 0 `
            -or $nodes['beta'].dependencies.Count -ne 0 `
            -or (($nodes['summary'].dependencies | Sort-Object) -join ',') -ne 'alpha,beta') {
        throw "模型返回的计划与验收场景不符。任务 $($plan.id)。"
    }
    $plan.plan | ConvertTo-Json -Depth 5 | Write-Host
    if ((Read-Host '确认计划后输入 start 执行') -cne 'start') {
        throw "未批准计划。任务 $($plan.id)，记录位于 $run。"
    }
    Invoke-RestMethod "$taskUrl/start" -Method Post -Headers $headers -TimeoutSec 10 | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $task = Invoke-RestMethod $taskUrl -Headers $headers -TimeoutSec 10
        if ($task.status -eq 'READY') { break }
        if ($task.status -in @('BLOCKED', 'FAILED', 'CONFLICT', 'CANCELED', 'INTERRUPTED')) {
            $task | ConvertTo-Json -Depth 6 | Write-Host
            throw "任务 $($task.id) 暂停在 $($task.status)，记录位于 $run。"
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($task.status -ne 'READY') { throw "等待总补丁超时，记录位于 $run。" }

    $patch = Join-Path $run 'changes.patch'
    $download = Invoke-WebRequest "$taskUrl/patch" -Headers $headers -OutFile $patch -PassThru -TimeoutSec 10 -UseBasicParsing
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $patchStream = [System.IO.File]::OpenRead($patch)
        try {
            $hash = [BitConverter]::ToString($sha256.ComputeHash($patchStream)).Replace('-', '')
        } finally {
            $patchStream.Dispose()
        }
    } finally {
        $sha256.Dispose()
    }
    $downloadHash = @($download.Headers['X-Patch-SHA256'])[0]
    if ($hash -ne $downloadHash -or $hash -ne $task.patchSha256) {
        throw '下载的总补丁哈希与服务记录不一致。'
    }
    Invoke-GitChecked @('clone', '-q', '--no-hardlinks', '--', $source, $review)
    Invoke-GitChecked @('-C', $review, 'apply', '--check', $patch)
    Invoke-GitChecked @('-C', $review, 'apply', $patch)
    $expected = @{ 'alpha.txt' = 'alpha'; 'beta.txt' = 'beta'; 'summary.txt' = 'alpha|beta' }
    foreach ($name in $expected.Keys) {
        if (((Get-Content -LiteralPath (Join-Path $review $name)) -join '|') -cne $expected[$name]) {
            throw "文件 $name 的内容与预期不符。"
        }
    }
    $reviewChanges = @(Invoke-GitChecked @('-C', $review, 'status', '--porcelain'))
    if (($reviewChanges | Sort-Object) -join '|' -cne '?? alpha.txt|?? beta.txt|?? summary.txt') {
        throw '总补丁包含超出验收场景的改动。'
    }
    if (Invoke-GitChecked @('-C', $source, 'status', '--porcelain')) {
        throw '源仓库出现意外改动。'
    }
    Write-Host "补丁已通过内容、哈希和可应用性检查。请检查 $patch。"
    if ((Read-Host '确认结果后输入 accept 记录验收') -cne 'accept') {
        Write-Host "补丁已保存，父任务 $($task.id) 等待验收。"
        Write-Host "稍后继续：powershell -NoProfile -File .\scripts\verify-local-model.ps1 -Resume `"$run`""
        return
    }
    $accepted = Invoke-RestMethod "$taskUrl/accept" -Method Post -Headers $headers -TimeoutSec 10
    if ($accepted.status -ne 'COMPLETED') { throw '服务未确认验收状态。' }
    [ordered] @{
        taskId = $task.id
        status = $accepted.status
        modelProvider = if ($env:SUPERAGENT_PROVIDER) { $env:SUPERAGENT_PROVIDER } else { 'deepseek' }
        model = if ($env:SUPERAGENT_MODEL) { $env:SUPERAGENT_MODEL } else { 'deepseek-flash' }
        modelHost = if ($env:SUPERAGENT_BASE_URL) { ([uri] $env:SUPERAGENT_BASE_URL).Host } else { 'api.deepseek.com' }
        patchSha256 = $hash.ToLowerInvariant()
        patch = $patch
        children = $task.children
    } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $run 'acceptance.json')
    Write-Host "本地 API 验收通过。证据位于 $run。"
} finally {
    if (-not $service.HasExited) {
        $service.Kill()
        $service.WaitForExit(5000) | Out-Null
    }
    $service.Dispose()
}
