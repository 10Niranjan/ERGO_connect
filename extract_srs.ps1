Add-Type -AssemblyName System.IO.Compression.FileSystem

$docx = (Get-ChildItem -Path "E:\ERGO Connect" -Filter "*.docx")[0].FullName
Write-Output "Found file: $docx"

$zip = [System.IO.Compression.ZipFile]::OpenRead($docx)
$entry = $zip.GetEntry("word/document.xml")
$stream = $entry.Open()
$reader = [System.IO.StreamReader]::new($stream)
$xmlContent = $reader.ReadToEnd()
$reader.Close()
$stream.Close()
$zip.Dispose()

[xml]$xml = $xmlContent
$ns = [System.Xml.XmlNamespaceManager]::new($xml.NameTable)
$ns.AddNamespace("w", "http://schemas.openxmlformats.org/wordprocessingml/2006/main")

$nodes = $xml.SelectNodes("//w:p", $ns)
$lines = [System.Collections.Generic.List[string]]::new()

foreach ($p in $nodes) {
    $tNodes = $p.SelectNodes(".//w:t", $ns)
    $pText = ""
    foreach ($t in $tNodes) {
        $pText += $t.InnerText
    }
    if ($pText.Trim().Length -gt 0) {
        $lines.Add($pText)
    }
}

$outputPath = "E:\ERGO Connect\extracted_srs.txt"
[System.IO.File]::WriteAllLines($outputPath, $lines)
Write-Output "Successfully extracted $($lines.Count) lines to $outputPath"
