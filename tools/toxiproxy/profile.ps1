param([ValidateSet('normal','weak','severe','unstable','blackhole','reset','disconnect','restore')][string]$Profile = 'weak')
$api = 'http://localhost:8474'
$proxy = 'mintmusic'
function Invoke-Toxi($method, $path, $body = $null) {
    $args = @{ Method = $method; Uri = "$api$path"; ContentType = 'application/json' }
    if ($null -ne $body) { $args.Body = ($body | ConvertTo-Json -Depth 6) }
    Invoke-RestMethod @args | Out-Null
}
try { Invoke-Toxi Delete "/proxies/$proxy" } catch { }
if ($Profile -in @('disconnect','restore')) {
    if ($Profile -eq 'restore') { Invoke-Toxi Post '/proxies' @{ name=$proxy; listen='0.0.0.0:8666'; upstream='origin:80'; enabled=$true } }
    Write-Host "Profile $Profile applied"; exit 0
}
Invoke-Toxi Post '/proxies' @{ name=$proxy; listen='0.0.0.0:8666'; upstream='origin:80'; enabled=$true }
$toxics = switch ($Profile) {
    normal { @() }
    weak { @(@{name='latency';type='latency';stream='downstream';attributes=@{latency=350;jitter=100}}, @{name='bandwidth';type='bandwidth';stream='downstream';attributes=@{rate=256}}) }
    severe { @(@{name='latency';type='latency';stream='downstream';attributes=@{latency=1000;jitter=300}}, @{name='bandwidth';type='bandwidth';stream='downstream';attributes=@{rate=64}}) }
    unstable { @(@{name='latency';type='latency';stream='downstream';attributes=@{latency=700;jitter=500}}, @{name='slicer';type='slicer';stream='downstream';attributes=@{average_size=1024;size_variation=512;delay=250}}) }
    blackhole { @(@{name='timeout';type='timeout';stream='downstream';attributes=@{timeout=0}}) }
    reset { @(@{name='reset';type='reset_peer';stream='downstream';attributes=@{timeout=1000}}) }
}
foreach ($toxic in $toxics) { Invoke-Toxi Post "/proxies/$proxy/toxics" $toxic }
Write-Host "Profile $Profile applied; Android emulator URL: http://10.0.2.2:8666/<file>"
