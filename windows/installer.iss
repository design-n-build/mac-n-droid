; Mac n Droid for Windows installer (Inno Setup 6). Built by .github/workflows/desktop.yml:
;   iscc /DVersion=0.13.1 /DArch=x64 /DSource=publish\x64 windows\installer.iss
#ifndef Version
  #define Version "0.0.0"
#endif
#ifndef Arch
  #define Arch "x64"
#endif

[Setup]
AppId={{6C1F2E4A-8B7D-4E5F-9A0B-3C2D1E0F4A5B}
AppName=Mac n Droid
AppVersion={#Version}
AppPublisher=Design n Build Studio
AppPublisherURL=https://github.com/design-n-build/mac-n-droid
DefaultDirName={localappdata}\Programs\Mac n Droid
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\release
OutputBaseFilename=MacNDroid-{#Version}-Windows-{#Arch}
SetupIconFile=App\Assets\AppIcon.ico
UninstallDisplayIcon={app}\MacNDroid.exe
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
#if Arch == "arm64"
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
#else
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
CloseApplications=yes

[Files]
Source: "{#Source}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{autoprograms}\Mac n Droid"; Filename: "{app}\MacNDroid.exe"

[Tasks]
Name: "startup"; Description: "Open Mac n Droid when I sign in"; Flags: checkedonce

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Mac n Droid"; \
  ValueData: """{app}\MacNDroid.exe"" --background"; Flags: uninsdeletevalue; Tasks: startup

[Run]
; Lets the phone connect over the local network without a firewall prompt (private networks only).
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Mac n Droid"" dir=in action=allow program=""{app}\MacNDroid.exe"" profile=private"; \
  Flags: runhidden; Check: IsAdmin
Filename: "{app}\MacNDroid.exe"; Description: "Open Mac n Droid"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Mac n Droid"""; Flags: runhidden; RunOnceId: "fw"
