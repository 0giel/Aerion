; Aerion's own uninstall window (installer/, built as AerionSetup.exe and copied here as
; AerionUninstall.exe by the release job; when it isn't there, the regular uninstaller stays).
; Windows starts whatever "UninstallString" names. The regular installer also runs that command,
; adding its own switches, and AerionUninstall.exe passes those straight to uninstall.exe.
; Where it is comes from the environment (AERION_UNINSTALLER, set by the release job), so the
; result doesn't depend on how the build tool spells this file's own path.

; Opened by hand, the setup file shows Aerion's own window instead of the wizard; that window then
; runs this file's silent mode (or the newest one). Updates and silent installs never get here
; or pass /P, so they are left alone.
!if /FileExists "$%AERION_UNINSTALLER%"
  !include "FileFunc.nsh"
  !define MUI_CUSTOMFUNCTION_GUIINIT AerionOwnWindow
  Function AerionOwnWindow
    ${GetOptions} $CMDLINE "/P" $0
    IfErrors 0 keep_wizard
    ${GetOptions} $CMDLINE "/UPDATE" $0
    IfErrors 0 keep_wizard
    SetOutPath "$TEMP"
    SetOverwrite try
    File "/oname=AerionSetup-window.exe" "$%AERION_UNINSTALLER%"
    SetOverwrite on
    Exec '"$TEMP\AerionSetup-window.exe"'
    Quit
    keep_wizard:
  FunctionEnd
!endif

!macro NSIS_HOOK_POSTINSTALL
  !if /FileExists "$%AERION_UNINSTALLER%"
    SetOutPath "$INSTDIR"
    File "/oname=AerionUninstall.exe" "$%AERION_UNINSTALLER%"
    WriteRegStr SHCTX "${UNINSTKEY}" "UninstallString" "$\"$INSTDIR\AerionUninstall.exe$\" --uninstall"
  !endif
!macroend
