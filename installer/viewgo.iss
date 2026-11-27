; ViewGo receiver installer — Inno Setup 6
; Compile from the repo root:
;   "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer\viewgo.iss
; Optional: /DMyAppVersion=x.y.z to override the version below.

#ifndef MyAppVersion
  #define MyAppVersion "0.3.7"
#endif

#define MyAppName "ViewGo"
#define MyAppPublisher "ViewGo"
#define MyAppExeName "viewgo-receiver.exe"
#define SourceExe "..\receiver\target\release\viewgo-receiver.exe"

[Setup]
; Stable id: changing this would install a second copy instead of upgrading.
AppId={{8B4E2A17-6D3F-4C5A-9E71-2A0F5C8D3B94}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\ViewGo
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputBaseFilename=ViewGo-Setup-{#MyAppVersion}
OutputDir=..\dist
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
; Lowest privileges installs to {localappdata}\Programs, so a future in-app
; update can replace the binary without a UAC prompt.
PrivilegesRequired=lowest
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "{#SourceExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional tasks"; Flags: unchecked

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent