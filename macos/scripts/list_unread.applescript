-- Diagnostic v2, read-only. Tests several ways of reaching unread mail and
-- reports the real error for each instead of silently swallowing it, since
-- v1 (all three account-type queries returning empty with no visible error)
-- didn't tell us whether that's a real empty result or a masked failure.

tell application "Microsoft Outlook"
	set diag to {}

	try
		set exAccts to every exchange account
		set end of diag to "exchange account: " & (count of exAccts) & " found"
	on error errMsg
		set end of diag to "exchange account: ERROR - " & errMsg
	end try

	try
		set imapAccts to every imap account
		set end of diag to "imap account: " & (count of imapAccts) & " found"
	on error errMsg
		set end of diag to "imap account: ERROR - " & errMsg
	end try

	try
		set popAccts to every pop account
		set end of diag to "pop account: " & (count of popAccts) & " found"
	on error errMsg
		set end of diag to "pop account: ERROR - " & errMsg
	end try

	try
		set defAcct to default account
		set end of diag to "default account: " & (name of defAcct)
	on error errMsg
		set end of diag to "default account: ERROR - " & errMsg
	end try

	try
		set directUnread to (messages of inbox whose is read is false)
		set end of diag to "direct inbox unread count: " & (count of directUnread)
		repeat with m in directUnread
			set end of diag to "  - " & (subject of m)
		end repeat
	on error errMsg
		set end of diag to "direct inbox: ERROR - " & errMsg
	end try

	set AppleScript's text item delimiters to linefeed
	set diagText to diag as text
	set AppleScript's text item delimiters to ""
	return diagText
end tell
