; Inno Setup script to create a Windows installer for BlenLauncher
; Requires Inno Setup (ISCC.exe) to compile this script.

[Setup]
AppName=BlenLauncher
AppVersion=1.0.0
DefaultDirName={pf}\BlenLauncher
DefaultGroupName=BlenLauncher
OutputDir=..\\build\\installer
OutputBaseFilename=BlenLauncher-Setup
Compression=lzma
SolidCompression=yes

[Files]
; Include the entire app-image produced by jpackage (exe + app + runtime)
Source: "..\\build\\installer\\BlenLauncher\\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs
Source: "install-java.ps1"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\BlenLauncher"; Filename: "{app}\BlenLauncher.exe"
Name: "{commondesktop}\BlenLauncher"; Filename: "{app}\BlenLauncher.exe"; Tasks: desktopicon

[Tasks]
Name: desktopicon; Description: "Create a Desktop Icon"; GroupDescription: "Additional icons"; Flags: unchecked

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\install-java.ps1"""; Flags: runhidden waituntilterminated
Filename: "{app}\BlenLauncher.exe"; Description: "Launch BlenLauncher"; Flags: nowait postinstall skipifsilent

; End of script
