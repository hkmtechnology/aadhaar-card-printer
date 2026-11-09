Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "C:\Users\Mr. Mondal\.gemini\antigravity\scratch\aadhaar-card-printer"
WshShell.Run "python server.py", 0, False
