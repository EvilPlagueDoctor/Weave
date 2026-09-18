$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Target = Join-Path $Root 'app\src\main\assets\content_filter'
New-Item -ItemType Directory -Force -Path $Target | Out-Null

function Download-Model([string]$Name, [string]$Url, [string]$Sha256 = '') {
    $Out = Join-Path $Target $Name
    $Tmp = "$Out.download"
    Write-Host "Downloading $Name ..."
    try {
        Invoke-WebRequest -UseBasicParsing -MaximumRedirection 10 -Uri $Url -OutFile $Tmp
        if ($Sha256) {
            $Actual = (Get-FileHash -Algorithm SHA256 $Tmp).Hash.ToLowerInvariant()
            if ($Actual -ne $Sha256.ToLowerInvariant()) {
                throw "SHA-256 mismatch for $Name. Expected $Sha256, got $Actual"
            }
        }
        Move-Item -Force $Tmp $Out
        $MiB = [math]::Round((Get-Item $Out).Length / 1MB, 1)
        Write-Host "  saved $Name ($MiB MiB)"
    } finally {
        if (Test-Path $Tmp) { Remove-Item -Force $Tmp }
    }
}

Download-Model `
    'image_safety_xs.onnx' `
    'https://huggingface.co/OwenElliott/image-safety-classifier-xs/resolve/main/onnx/image-safety-classifier-xs.onnx?download=true'

Download-Model `
    'toxic_minilm_int8.onnx' `
    'https://huggingface.co/minuva/MiniLMv2-toxic-jigsaw-onnx/resolve/main/model_optimized_quantized.onnx?download=true' `
    'bcd9dfb48cad802ac8f7cd789e1294f1f0b22d532797bd41f5a11694e3c269a0'

Download-Model `
    'vocab.txt' `
    'https://huggingface.co/minuva/MiniLMv2-toxic-jigsaw-onnx/resolve/main/vocab.txt?download=true'

Write-Host ''
Write-Host 'Content-filter models installed. You can now build Weave normally.'
