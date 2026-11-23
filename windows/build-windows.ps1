param(
    [string]$Version = "1.5.3",
    [switch]$SkipArchive
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$OutAppDir = Join-Path $RepoRoot "build\windows\KomaComic-windows-x64"
$ReleaseDir = Join-Path $RepoRoot "build\release"
$NugetDir = Join-Path $ScriptDir ".nuget\webview2"
$LogoPng = Join-Path $RepoRoot "app\src\main\res\drawable-nodpi\ic_koma_logo.png"
$IconPath = Join-Path $ScriptDir "KomaComic.ico"
$ManifestPath = Join-Path $ScriptDir "app.manifest"
$CscExe = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$WpfDir = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\WPF"
$FxDir = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319"

if (-not (Test-Path $CscExe)) {
    throw "未找到 .NET Framework C# 编译器: $CscExe"
}

Write-Host "==> [1/5] 生成 Windows 多分辨率图标 ($IconPath)..."
if (-not (Test-Path $LogoPng)) {
    throw "未找到应用图标源文件: $LogoPng"
}
python -c "from PIL import Image; img = Image.open(r'$LogoPng').convert('RGBA'); img.save(r'$IconPath', format='ICO', sizes=[(256,256),(64,64),(48,48),(32,32),(16,16)])"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $IconPath)) {
    throw "生成 KomaComic.ico 失败"
}

Write-Host "==> [2/5] 准备后台 WebView2 JS 引擎依赖..."
$Wv2Version = "1.0.2903.40"
$Wv2Nupkg = Join-Path $ScriptDir ".nuget\Microsoft.Web.WebView2.$Wv2Version.nupkg"
$NugetParent = Split-Path -Parent $Wv2Nupkg
if (-not (Test-Path $NugetParent)) { New-Item -ItemType Directory -Path $NugetParent -Force | Out-Null }

if (-not (Test-Path (Join-Path $NugetDir "lib"))) {
    if (-not (Test-Path $Wv2Nupkg)) {
        $NupkgUrl = "https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/$Wv2Version"
        Write-Host "    下载 $NupkgUrl ..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $NupkgUrl -OutFile $Wv2Nupkg -UseBasicParsing
    }
    if (Test-Path $NugetDir) { Remove-Item -Path $NugetDir -Recurse -Force }
    New-Item -ItemType Directory -Path $NugetDir -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Wv2Nupkg, $NugetDir)
}

$CoreDll = Get-ChildItem -Path (Join-Path $NugetDir "lib") -Recurse -Filter "Microsoft.Web.WebView2.Core.dll" | Select-Object -First 1
$WinFormsDll = Get-ChildItem -Path (Join-Path $NugetDir "lib") -Recurse -Filter "Microsoft.Web.WebView2.WinForms.dll" | Select-Object -First 1
$LoaderDll = Get-ChildItem -Path (Join-Path $NugetDir "runtimes\win-x64\native") -Recurse -Filter "WebView2Loader.dll" | Select-Object -First 1

if (-not $CoreDll -or -not $WinFormsDll -or -not $LoaderDll) {
    throw "WebView2 NuGet 包结构异常，缺少必要的 DLL"
}

Write-Host "==> [3/5] 准备便携版输出目录: $OutAppDir"
if (Test-Path $OutAppDir) { Remove-Item -Path $OutAppDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutAppDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $OutAppDir "assets") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $OutAppDir "data") -Force | Out-Null

Write-Host "==> [4/5] 编译原生 WPF 高清版 KomaComic.exe (Per-Monitor V2 DPI)..."
$ExePath = Join-Path $OutAppDir "KomaComic.exe"
$ProgramCs = Join-Path $ScriptDir "Program.cs"

$cscArgs = @(
    "/nologo",
    "/target:winexe",
    "/platform:x64",
    "/optimize+",
    "/utf8output",
    "/win32icon:$IconPath",
    "/win32manifest:$ManifestPath",
    "/out:$ExePath",
    "/r:System.dll",
    "/r:System.Core.dll",
    "/r:System.Security.dll",
    "/r:System.Drawing.dll",
    "/r:System.Windows.Forms.dll",
    "/r:System.IO.Compression.dll",
    "/r:System.IO.Compression.FileSystem.dll",
    "/r:System.Web.Extensions.dll",
    "/r:$FxDir\System.Xaml.dll",
    "/r:$WpfDir\WindowsBase.dll",
    "/r:$WpfDir\PresentationCore.dll",
    "/r:$WpfDir\PresentationFramework.dll",
    "/r:$WpfDir\WindowsFormsIntegration.dll",
    "/r:$($CoreDll.FullName)",
    "/r:$($WinFormsDll.FullName)",
    $ProgramCs,
    (Join-Path $ScriptDir "DirectMangaTranslation.cs"),
    (Join-Path $ScriptDir "LightBalloonFinder.cs"),
    (Join-Path $ScriptDir "TranslationRequestQueue.cs"),
    (Join-Path $ScriptDir "TranslationUsage.cs"),
    (Join-Path $ScriptDir "ReadingProfile.cs"),
    (Join-Path $ScriptDir "ReaderTranslationControls.cs"),
    (Join-Path $ScriptDir "GithubFeedback.cs")
)

& $CscExe @cscArgs
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $ExePath)) {
    throw "编译 KomaComic.exe 失败"
}

Write-Host "==> [5/5] 复制依赖与内置漫画源脚本..."
Copy-Item -Path $CoreDll.FullName -Destination $OutAppDir -Force
Copy-Item -Path $WinFormsDll.FullName -Destination $OutAppDir -Force
Copy-Item -Path $LoaderDll.FullName -Destination $OutAppDir -Force
Copy-Item -Path (Join-Path $RepoRoot "app\src\main\assets\*") -Destination (Join-Path $OutAppDir "assets") -Recurse -Force
Copy-Item -Path $LogoPng -Destination (Join-Path $OutAppDir "assets\ic_koma_logo.png") -Force

if (-not $SkipArchive) {
    if (-not (Test-Path $ReleaseDir)) { New-Item -ItemType Directory -Path $ReleaseDir -Force | Out-Null }
    $ZipPath = Join-Path $ReleaseDir "KomaComic-v$Version-windows-x64.zip"
    if (Test-Path $ZipPath) { Remove-Item -Path $ZipPath -Force }
    Compress-Archive -Path (Join-Path $OutAppDir "*") -DestinationPath $ZipPath -CompressionLevel Optimal
    Write-Host "==> 完成 Windows WPF 高清发布包: $ZipPath"
} else {
    Write-Host "==> 完成 Windows WPF 高清目录构建: $OutAppDir"
}
