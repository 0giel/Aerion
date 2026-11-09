; Aerion's own uninstall window (installer/, built as AerionSetup.exe and copied here as
; AerionUninstall.exe by the release job; when it isn't there, the regular uninstaller stays).
; Windows starts whatever "UninstallString" names. The regular installer also runs that command,
; adding its own switches, and AerionUninstall.exe passes those straight to uninstall.exe.
; On Windows the path can start with \\?\, where forward slashes don't work.
!ifdef NSIS_WIN32_MAKENSIS
  !define AERION_UNINSTALLER "${__FILEDIR__}\AerionUninstall.exe"
!else
  !define AERION_UNINSTALLER "${__FILEDIR__}/AerionUninstall.exe"
!endif

!macro NSIS_HOOK_POSTINSTALL
  !if /FileExists "${AERION_UNINSTALLER}"
    SetOutPath "$INSTDIR"
    File "/oname=AerionUninstall.exe" "${AERION_UNINSTALLER}"
    WriteRegStr SHCTX "${UNINSTKEY}" "UninstallString" "$\"$INSTDIR\AerionUninstall.exe$\" --uninstall"
  !endif
!macroend
