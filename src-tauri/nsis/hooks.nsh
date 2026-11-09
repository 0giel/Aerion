; Aerion's own uninstall window (installer/, built as AerionSetup.exe and copied here as
; AerionUninstall.exe by the release job; when it isn't there, the regular uninstaller stays).
; Windows starts whatever "UninstallString" names. The regular installer also runs that command,
; adding its own switches, and AerionUninstall.exe passes those straight to uninstall.exe.
; Where it is comes from the environment (AERION_UNINSTALLER, set by the release job), so the
; result doesn't depend on how the build tool spells this file's own path.

!macro NSIS_HOOK_POSTINSTALL
  !if /FileExists "$%AERION_UNINSTALLER%"
    SetOutPath "$INSTDIR"
    File "/oname=AerionUninstall.exe" "$%AERION_UNINSTALLER%"
    WriteRegStr SHCTX "${UNINSTKEY}" "UninstallString" "$\"$INSTDIR\AerionUninstall.exe$\" --uninstall"
  !endif
!macroend
