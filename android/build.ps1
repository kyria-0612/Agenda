param(
    [string[]]$Tasks = @(':domain:test', ':data:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'),
    [string]$JdkPath = 'D:\allAboutCoding\android studio\jbr'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin\java.exe'))) {
    throw 'JDK not found. Supply -JdkPath with a compatible JDK location.'
}
$previousJavaHome = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
$agendaDrive = $null
$agendaBuildRoot = $PSScriptRoot
if ($PSScriptRoot.Contains("'")) {
    # Java @argument files treat apostrophes as quotes. A session-local drive alias
    # keeps Gradle's generated worker classpaths free of that character.
    foreach ($agendaLetter in @('Z','Y','X','W')) {
        if (-not (Test-Path -LiteralPath "${agendaLetter}:\")) {
            & subst "${agendaLetter}:" $PSScriptRoot
            if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary build drive.' }
            $agendaDrive = "${agendaLetter}:"
            $agendaBuildRoot = "${agendaLetter}:\"
            break
        }
    }
    if (-not $agendaDrive) { throw 'No free temporary build drive (Z/Y/X/W).' }
}
Push-Location $agendaBuildRoot
try {
    $env:JAVA_HOME = $JdkPath
    $env:GRADLE_USER_HOME = Join-Path $agendaBuildRoot '.gradle-user-home'
    & (Join-Path $agendaBuildRoot 'gradlew.bat') @Tasks --console plain --no-daemon
    $agendaExit = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleHome
    Pop-Location
    if ($agendaDrive) { & subst $agendaDrive /D }
}
exit $agendaExit
