; Aerion's own uninstall window (installer/, built as AerionSetup.exe and copied here as
; AerionUninstall.exe by the release job; when it isn't there, the regular uninstaller stays).
; Windows starts whatever "UninstallString" names. The regular installer also runs that command,
; adding its own switches, and AerionUninstall.exe passes those straight to uninstall.exe.
!define AERION_NSIS_DIR "${__FILEDIR__}"

!macro NSIS_HOOK_POSTINSTALL
  !if /FileExists "${AERION_NSIS_DIR}/AerionUninstall.exe"
    SetOutPath "$INSTDIR"
    File "/oname=AerionUninstall.exe" "${AERION_NSIS_DIR}/AerionUninstall.exe"
    WriteRegStr SHCTX "${UNINSTKEY}" "UninstallString" "$\"$INSTDIR\AerionUninstall.exe$\" --uninstall"
  !endif
!macroend
