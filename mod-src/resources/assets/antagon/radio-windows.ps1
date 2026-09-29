param([int]$ParentId, [string]$CommandFile, [string]$ArtDir)
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } |
    Select-Object -First 1
function Await($operation, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($operation))
    $null = $task.Wait(-1)
    $task.Result
}
function Emit([string]$line) {
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
}
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$lastTrack = ''
$art = ''
while (Get-Process -Id $ParentId) {
    $session = $manager.GetSessions() | Where-Object { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1
    if (Test-Path $CommandFile) {
        $command = (Get-Content $CommandFile -Raw).Trim()
        Remove-Item $CommandFile
        if ($session) {
            switch ($command) {
                'playpause' { $null = $session.TryTogglePlayPauseAsync() }
                'next' { $null = $session.TrySkipNextAsync() }
                'previous' { $null = $session.TrySkipPreviousAsync() }
                'pause' { $null = $session.TryPauseAsync() }
            }
        }
    }
    if (-not (Get-Process Spotify)) {
        Emit 'off'
    } elseif (-not $session) {
        Emit 'stopped'
    } else {
        $info = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
        $state = if ($session.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { 'playing' } else { 'paused' }
        $track = "$($info.Artist)|$($info.Title)"
        if ($track -ne $lastTrack) {
            $lastTrack = $track
            $art = ''
            if ($info.Thumbnail) {
                try {
                    $stream = Await ($info.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                    $reader = [Windows.Storage.Streams.DataReader]::new($stream)
                    $null = Await ($reader.LoadAsync([uint32]$stream.Size)) ([uint32])
                    $bytes = New-Object byte[] ([int]$stream.Size)
                    $reader.ReadBytes($bytes)
                    $art = Join-Path $ArtDir ('art-' + [Math]::Abs($track.GetHashCode()) + '.img')
                    [IO.File]::WriteAllBytes($art, $bytes)
                } catch {
                    $art = ''
                }
            }
        }
        $timeline = $session.GetTimelineProperties()
        Emit ("$state|~|$($info.Title)|~|$($info.Artist)|~|$art|~|" +
            [string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0}|~|{1}', $timeline.Position.TotalSeconds, $timeline.EndTime.TotalMilliseconds) +
            '|~|-1')
    }
    Start-Sleep -Milliseconds 700
}
