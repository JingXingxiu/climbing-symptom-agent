param(
    [string]$NacosServer = $(if ($env:NACOS_SERVER_ADDR) { $env:NACOS_SERVER_ADDR } else { "127.0.0.1:8848" }),
    [string]$Group = "CLIMBING_DEV"
)

$configDirectory = $PSScriptRoot
$configurationFiles = Get-ChildItem -LiteralPath $configDirectory -Filter "*-dev.yaml" -File | Sort-Object Name

if ($configurationFiles.Count -ne 6) {
    throw "Expected six Nacos seed configs in $configDirectory, found $($configurationFiles.Count)."
}

foreach ($configurationFile in $configurationFiles) {
    $content = Get-Content -LiteralPath $configurationFile.FullName -Raw
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "http://$NacosServer/nacos/v1/cs/configs" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            dataId = $configurationFile.Name
            group = $Group
            type = "yaml"
            content = $content
        }

    if ($response -ne "true") {
        throw "Nacos rejected $($configurationFile.Name): $response"
    }

    Write-Host "Published $Group/$($configurationFile.Name)"
}
