#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot '.env'
& git -C $projectRoot check-ignore --quiet -- .env
if ($LASTEXITCODE -ne 0) { throw '请先确认 .env 被 Git 忽略，然后再保存 Key。' }

$secureKey = Read-Host '输入 DeepSeek API Key' -AsSecureString
try {
    $key = [System.Net.NetworkCredential]::new('', $secureKey).Password.Trim()
    if ($key -notmatch '^\S+$') { throw 'Key 不能为空或包含空格。' }

    $lines = @()
    if (Test-Path -LiteralPath $configPath) {
        $lines = @([IO.File]::ReadAllLines($configPath) |
            Where-Object { $_ -notmatch '^\s*DEEPSEEK_API_KEY\s*=' })
    }
    $lines += "DEEPSEEK_API_KEY=$key"
    [IO.File]::WriteAllLines($configPath, [string[]] $lines, [Text.UTF8Encoding]::new($false))
    Write-Host 'DeepSeek Key 已保存到本机 .env。'
} finally {
    $secureKey.Dispose()
    $key = $null
}
