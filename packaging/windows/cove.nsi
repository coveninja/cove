Unicode True
!include "MUI2.nsh"
!include "FileFunc.nsh"

; Installer/uninstaller exe icon
!define MUI_ICON   "..\..\packaging\icons\cove.ico"
!define MUI_UNICON "..\..\packaging\icons\cove.ico"

; Passed in from CI: makensis /DVERSION=v0.14.5 /DOUTDIR=C:\...\workspace cove.nsi
; Falls back to sensible defaults for local testing.
!ifndef VERSION
  !define VERSION "dev"
!endif
; OUTDIR lets CI write the installer to the repo root instead of the script dir.
!ifndef OUTDIR
  !define OUTDIR "."
!endif

!define APP_NAME    "Cove"
!define PUBLISHER   "coveninja"
!define REG_UNINST  "Software\Microsoft\Windows\CurrentVersion\Uninstall\Cove"

Name      "${APP_NAME}"
!ifdef PORTABLE_UPDATE
  OutFile "${OUTDIR}\cove-windows-amd64-portable-update.exe"
  RequestExecutionLevel user
!else
  OutFile "${OUTDIR}\cove-windows-amd64-setup.exe"
  RequestExecutionLevel admin
!endif
InstallDir "$PROGRAMFILES64\Cove"
InstallDirRegKey HKCU "Software\Cove" "InstallDir"
SetCompressor /SOLID lzma

Var UpdateMode
Var UpdateTarget
Var UpdatePid
Var UpdateResult
Var UpdateOutput

; ── Pages ─────────────────────────────────────────────────────────────────────
!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY

Var StartMenuFolder
!define MUI_STARTMENUPAGE_REGISTRY_ROOT      "HKCU"
!define MUI_STARTMENUPAGE_REGISTRY_KEY       "Software\Cove"
!define MUI_STARTMENUPAGE_REGISTRY_VALUENAME "StartMenuFolder"
!insertmacro MUI_PAGE_STARTMENU Application $StartMenuFolder

!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

Function .onInit
  ${GetParameters} $R0
  ${GetOptions} $R0 "/UPDATE=" $UpdateMode
  ${GetOptions} $R0 "/TARGET=" $UpdateTarget
  ${GetOptions} $R0 "/PID=" $UpdatePid
!ifdef PORTABLE_UPDATE
  StrCmp $UpdateMode "1" portable_update_ok
  MessageBox MB_ICONSTOP "This executable updates an existing portable Cove directory."
  Abort
portable_update_ok:
!endif
  StrCmp $UpdateMode "1" 0 normal_start
  StrCmp $UpdateTarget "" invalid_update
  StrCmp $UpdatePid "" invalid_update
  ; $UpdatePid is inserted into a PowerShell expression below. Canonicalize it
  ; as an unsigned integer first so a manually supplied command line cannot
  ; turn the signed helper into an elevated command-injection primitive.
  IntFmt $R1 "%u" $UpdatePid
  StrCmp $R1 $UpdatePid 0 invalid_update
  StrCmp $R1 "0" invalid_update
  StrCpy $INSTDIR $UpdateTarget
!ifdef PORTABLE_UPDATE
  IfFileExists "$INSTDIR\.cove-portable" 0 invalid_update_target
!else
  IfFileExists "$INSTDIR\.cove-installed" 0 invalid_update_target
!endif
  IfFileExists "$INSTDIR\Cove.exe" update_target_ok invalid_update_target
update_target_ok:
  SetSilent silent
  Return
invalid_update:
  MessageBox MB_ICONSTOP "Cove received an incomplete update request."
  Abort
invalid_update_target:
  MessageBox MB_ICONSTOP "The selected directory is not a matching Cove installation."
  Abort
normal_start:
FunctionEnd

; ── Install ───────────────────────────────────────────────────────────────────
Section "-Core" SecCore
  SectionIn RO

  StrCmp $UpdateMode "1" 0 normal_install
  Call PerformVerifiedUpdate
  Goto core_done

normal_install:

  SetOutPath "$INSTDIR"
  ; All files are pre-assembled in staging\ by the CI package-windows job.
  ; This includes: Cove.exe (jpackage launcher), mpv-2.dll, the bundled JRE
  ; (runtime\), and the app JARs (app\) containing the Kotlin backend.
  !cd "..\..\staging"
  File /r "*"
  !cd "..\packaging\windows"

  FileOpen $R0 "$INSTDIR\.cove-installed" w
  FileWrite $R0 "${VERSION}$\r$\n"
  FileClose $R0

  WriteRegStr HKCU "Software\Cove" "InstallDir" "$INSTDIR"
  WriteUninstaller "$INSTDIR\uninstall.exe"

  WriteRegStr   HKLM "${REG_UNINST}" "DisplayName"    "${APP_NAME}"
  WriteRegStr   HKLM "${REG_UNINST}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr   HKLM "${REG_UNINST}" "InstallLocation" "$INSTDIR"
  WriteRegStr   HKLM "${REG_UNINST}" "Publisher"       "${PUBLISHER}"
  WriteRegStr   HKLM "${REG_UNINST}" "DisplayVersion"  "${VERSION}"
  WriteRegDWORD HKLM "${REG_UNINST}" "NoModify"       1
  WriteRegDWORD HKLM "${REG_UNINST}" "NoRepair"       1

  !insertmacro MUI_STARTMENU_WRITE_BEGIN Application
    CreateDirectory "$SMPROGRAMS\$StartMenuFolder"
    CreateShortcut  "$SMPROGRAMS\$StartMenuFolder\${APP_NAME}.lnk" \
                    "$INSTDIR\Cove.exe" "" \
                    "$INSTDIR\Cove.exe"
    CreateShortcut  "$SMPROGRAMS\$StartMenuFolder\Uninstall ${APP_NAME}.lnk" \
                    "$INSTDIR\uninstall.exe"
  !insertmacro MUI_STARTMENU_WRITE_END
core_done:
SectionEnd

Section "Desktop shortcut" SecDesktop
  StrCmp $UpdateMode "1" desktop_done
  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" \
                 "$INSTDIR\Cove.exe" "" \
                 "$INSTDIR\Cove.exe"
desktop_done:
SectionEnd

Function PerformVerifiedUpdate
  ; The updater was already authenticated by Cove's signed release manifest and
  ; launched from its device-local staging directory. Wait until the Java process
  ; has released app/runtime files before replacing anything.
  nsExec::ExecToStack 'powershell.exe -NoProfile -NonInteractive -Command "Wait-Process -Id $UpdatePid -Timeout 120 -ErrorAction Stop"'
  Pop $UpdateResult
  Pop $UpdateOutput
  StrCmp $UpdateResult "0" update_process_stopped
  MessageBox MB_ICONSTOP "Cove did not close in time. No files were changed."
  Abort

update_process_stopped:
  RMDir /r "$INSTDIR\.cove-update-backup"
  CreateDirectory "$INSTDIR\.cove-update-backup"
  ClearErrors
  IfFileExists "$INSTDIR\runtime\*.*" 0 +2
    Rename "$INSTDIR\runtime" "$INSTDIR\.cove-update-backup\runtime"
  IfFileExists "$INSTDIR\app\*.*" 0 +2
    Rename "$INSTDIR\app" "$INSTDIR\.cove-update-backup\app"
  IfFileExists "$INSTDIR\Cove.exe" 0 +2
    Rename "$INSTDIR\Cove.exe" "$INSTDIR\.cove-update-backup\Cove.exe"
  IfFileExists "$INSTDIR\mpv-2.dll" 0 +2
    Rename "$INSTDIR\mpv-2.dll" "$INSTDIR\.cove-update-backup\mpv-2.dll"
  IfErrors update_backup_failed

  StrCpy $UpdateResult "replace"
  SetOutPath "$INSTDIR"
  !cd "..\..\staging"
  File /r "*"
  !cd "..\packaging\windows"
  IfErrors update_rollback

!ifdef PORTABLE_UPDATE
  Delete "$INSTDIR\.cove-installed"
  ClearErrors
  FileOpen $R0 "$INSTDIR\.cove-portable" w
!else
  Delete "$INSTDIR\.cove-portable"
  ClearErrors
  FileOpen $R0 "$INSTDIR\.cove-installed" w
!endif
  FileWrite $R0 "${VERSION}$\r$\n"
  FileClose $R0
  IfErrors update_rollback
!ifndef PORTABLE_UPDATE
  WriteRegStr HKLM "${REG_UNINST}" "DisplayVersion" "${VERSION}"
!endif
  RMDir /r "$INSTDIR\.cove-update-backup"
!ifdef PORTABLE_UPDATE
  Exec '"$INSTDIR\Cove.exe"'
!endif
  Return

update_backup_failed:
  StrCpy $UpdateResult "backup"
  Goto update_rollback

update_rollback:
  ; A replacement failure may have written new files, so remove all app-owned
  ; paths. During a partial backup failure, only paths that reached the backup
  ; are touched; original paths that failed to move are preserved.
  StrCmp $UpdateResult "replace" 0 update_restore_runtime
    RMDir /r "$INSTDIR\runtime"
    RMDir /r "$INSTDIR\app"
    Delete "$INSTDIR\Cove.exe"
    Delete "$INSTDIR\mpv-2.dll"
update_restore_runtime:
  IfFileExists "$INSTDIR\.cove-update-backup\runtime\*.*" 0 update_restore_app
    RMDir /r "$INSTDIR\runtime"
    Rename "$INSTDIR\.cove-update-backup\runtime" "$INSTDIR\runtime"
update_restore_app:
  IfFileExists "$INSTDIR\.cove-update-backup\app\*.*" 0 update_restore_exe
    RMDir /r "$INSTDIR\app"
    Rename "$INSTDIR\.cove-update-backup\app" "$INSTDIR\app"
update_restore_exe:
  IfFileExists "$INSTDIR\.cove-update-backup\Cove.exe" 0 update_restore_mpv
    Delete "$INSTDIR\Cove.exe"
    Rename "$INSTDIR\.cove-update-backup\Cove.exe" "$INSTDIR\Cove.exe"
update_restore_mpv:
  IfFileExists "$INSTDIR\.cove-update-backup\mpv-2.dll" 0 update_rollback_message
    Delete "$INSTDIR\mpv-2.dll"
    Rename "$INSTDIR\.cove-update-backup\mpv-2.dll" "$INSTDIR\mpv-2.dll"
update_rollback_message:
  RMDir /r "$INSTDIR\.cove-update-backup"
  StrCmp $UpdateResult "backup" 0 update_replacement_message
    MessageBox MB_ICONSTOP "Cove could not back up the existing installation. No update was installed."
    Goto update_rollback_done
update_replacement_message:
  MessageBox MB_ICONSTOP "Cove rolled back the update because replacement failed. If this portable directory is not writable, use the installer instead."
update_rollback_done:
!ifdef PORTABLE_UPDATE
  Exec '"$INSTDIR\Cove.exe"'
!endif
  Abort
FunctionEnd

; ── Uninstall ─────────────────────────────────────────────────────────────────
Section "Uninstall"
  ; jpackage-bundled JRE and app JARs
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\app"
  Delete "$INSTDIR\*.exe"
  Delete "$INSTDIR\*.dll"
  Delete "$INSTDIR\.cove-installed"
  Delete "$INSTDIR\.cove-portable"

  !insertmacro MUI_STARTMENU_GETFOLDER Application $StartMenuFolder
  Delete "$SMPROGRAMS\$StartMenuFolder\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\$StartMenuFolder\Uninstall ${APP_NAME}.lnk"
  RMDir  "$SMPROGRAMS\$StartMenuFolder"

  Delete "$DESKTOP\${APP_NAME}.lnk"

  DeleteRegKey HKCU "Software\Cove"
  DeleteRegKey HKLM "${REG_UNINST}"

  ; Remove install dir only if empty (won't remove user-added files).
  RMDir "$INSTDIR"
SectionEnd
