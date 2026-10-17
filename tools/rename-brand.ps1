# Rename upstream branding (RikkaHub) to Air-Ko in user-visible string resources.
#
# Scope is deliberately narrow. Only these string names are touched:
#   app_name                                   -> shown under the launcher icon
#   setting_page_web_server_desc               -> mentions the brand inside a sentence
#   setting_page_share_text                    -> share sheet blurb (upstream website line dropped)
#   setting_asr_configure_step_timestamp_desc  -> mentions the brand inside a sentence
#
# setting_page_share_text keeps only the "brand - tagline" head: everything from the
# first literal "\n\n" onwards is dropped, which removes the upstream website line
# ("Website: https://rikka-ai.com/") in every locale without having to match each
# locale's own label text. The resource stores the two-character escape `\n`, not a
# real newline, so the pattern below matches a backslash followed by `n`.
#
# Deliberately NOT touched:
#   rikkahub_provider_description  - describes the real upstream free AI service that
#                                    ships as a built-in provider; renaming it would
#                                    turn an accurate description into a false one.
#   RikkaHubAPI / RikkaHubSearchService / the `me.rerere.rikkahub` package and
#   Gradle `namespace` - internal identifiers, not user-visible.
#
# Safe to re-run: every substitution is idempotent ('RikkaHub' is gone after the
# first run, and the share-text head contains no brand name again).
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File tools/rename-brand.ps1

$ErrorActionPreference = 'Stop'

$targets = @(
    'app_name',
    'setting_page_web_server_desc',
    'setting_page_share_text',
    'setting_asr_configure_step_timestamp_desc'
)

$newName = 'Air-Ko'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resRoot = (Resolve-Path (Join-Path $repoRoot 'app\src\main\res')).Path
$files = Get-ChildItem -Recurse -Path $resRoot -Filter 'strings.xml'

# Every file in this repo is UTF-8 without BOM (verified); keep it that way so the
# diff stays limited to the substituted text.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$totalChanged = 0
foreach ($file in $files) {
    $original = [System.IO.File]::ReadAllText($file.FullName, $utf8NoBom)
    $updated = $original

    foreach ($name in $targets) {
        $pattern = '(<string name="' + [regex]::Escape($name) + '"[^>]*>)(.*?)(</string>)'
        $updated = [regex]::Replace(
            $updated,
            $pattern,
            {
                param($match)
                $body = $match.Groups[2].Value
                $body = $body.Replace('RikkaHub', $newName).Replace('rikkahub', $newName)
                return $match.Groups[1].Value + $body + $match.Groups[3].Value
            }
        )
    }

    # Drop everything from the first literal `\n\n` in the share blurb to the tag,
    # i.e. the "Website: https://rikka-ai.com/" tail. Done as a plain regex replace
    # (no script block) so it cannot pick up a stale loop variable.
    $sharePattern = '(<string name="setting_page_share_text"[^>]*>)(.*?)(\\n\\n[^<]*)(</string>)'
    $updated = [regex]::Replace($updated, $sharePattern, '$1$2$4')

    if ($updated -ne $original) {
        [System.IO.File]::WriteAllText($file.FullName, $updated, $utf8NoBom)
        $rel = $file.FullName.Replace($repoRoot + '\', '')
        Write-Output "updated: $rel"
        $totalChanged++
    }
    else {
        $rel = $file.FullName.Replace($repoRoot + '\', '')
        Write-Output "unchanged: $rel"
    }
}

Write-Output "files updated: $totalChanged"
