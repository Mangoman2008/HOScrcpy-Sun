Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class W {
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc cb, IntPtr l);
  public delegate bool EnumWindowsProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@
[void][W]::SetProcessDPIAware()
$results = New-Object System.Collections.ArrayList
$cb = {
  param($h, $l)
  if ([W]::IsWindowVisible($h)) {
    $sb = New-Object System.Text.StringBuilder 512
    [void][W]::GetWindowText($h, $sb, 512)
    $title = $sb.ToString()
    $cb2 = New-Object System.Text.StringBuilder 256
    [void][W]::GetClassName($h, $cb2, 256)
    $cls = $cb2.ToString()
    if ($title -and $title.Length -gt 0) {
      $r = New-Object W+RECT
      if ([W]::GetWindowRect($h, [ref]$r)) {
        $w = $r.R - $r.L; $h2 = $r.B - $r.T
        if ($w -gt 200 -and $h2 -gt 200) {
          [void]$results.Add([pscustomobject]@{ Title=$title; Class=$cls; X=$r.L; Y=$r.T; W=$w; H=$h2 })
        }
      }
    }
  }
  return $true
}
[void][W]::EnumWindows($cb, [IntPtr]::Zero)
$results | Where-Object {
  $_.Class -eq 'StartupDui' -or
  $_.Title -match '多屏|协同|镜像|Phone|Mirror|投屏|Mate|HOScrcpy|超级终端' -or
  $_.Class -match 'Mirror|Phone|Cast'
} | ForEach-Object { "{0}`t{1}`t{2}`t{3}`t{4}`t{5}" -f $_.Title, $_.Class, $_.X, $_.Y, $_.W, $_.H }
