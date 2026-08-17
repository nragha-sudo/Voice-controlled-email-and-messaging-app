-- Diagnostic v3, read-only. exchange/imap/pop account queries all returned
-- 0 with no error, and default account is literally missing value — the
-- accounts on this machine (modern OAuth sign-ins) aren't represented via
-- AppleScript's classic account model at all. Testing whether "messages"
-- and "incoming messages", both listed as elements directly on the
-- application class itself (independent of any account/inbox concept),
-- can reach mail anyway.

tell application "Microsoft Outlook"
	set diag to {}

	try
		set allMsgs to (every message whose is read is false)
		set end of diag to "bare messages unread count: " & (count of allMsgs)
		repeat with m in allMsgs
			set end of diag to "  - " & (subject of m)
		end repeat
	on error errMsg
		set end of diag to "bare messages: ERROR - " & errMsg
	end try

	try
		set incMsgs to (every incoming message whose is read is false)
		set end of diag to "incoming messages unread count: " & (count of incMsgs)
		repeat with m in incMsgs
			set end of diag to "  - " & (subject of m)
		end repeat
	on error errMsg
		set end of diag to "incoming messages: ERROR - " & errMsg
	end try

	try
		set allFolders to every mail folder
		set end of diag to "mail folder count: " & (count of allFolders)
		repeat with f in allFolders
			set end of diag to "  - folder: " & (name of f)
		end repeat
	on error errMsg
		set end of diag to "mail folder: ERROR - " & errMsg
	end try

	set AppleScript's text item delimiters to linefeed
	set diagText to diag as text
	set AppleScript's text item delimiters to ""
	return diagText
end tell
