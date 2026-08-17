-- Diagnostic, read-only: lists unread Outlook messages across every account
-- without marking anything read or speaking anything. Run this first, before
-- anything that actually acts on your mail, to confirm the AppleScript
-- dictionary assumptions (verified against Outlook's own Script Editor
-- dictionary: message.plain text content, message.sender, message.is read,
-- account.inbox, exchange account) hold on this machine.
--
-- Run directly in Script Editor (paste + press the Run button), or from
-- Terminal with: osascript macos/scripts/list_unread.applescript

tell application "Microsoft Outlook"
	set acctList to {}
	try
		set acctList to acctList & (every exchange account)
	end try
	try
		set acctList to acctList & (every imap account)
	end try
	try
		set acctList to acctList & (every pop account)
	end try
	try
		set acctList to acctList & (every eas account)
	end try

	set outputLines to {}
	repeat with acct in acctList
		set acctName to name of acct
		try
			set theInbox to inbox of acct
			set unreadMsgs to (messages of theInbox whose is read is false)
			set unreadCount to count of unreadMsgs
			set end of outputLines to acctName & ": " & unreadCount & " unread"
			repeat with m in unreadMsgs
				set msgSubject to subject of m
				set senderName to "(unknown sender)"
				try
					set senderName to name of (sender of m)
				end try
				set end of outputLines to "  - " & senderName & " | " & msgSubject
			end repeat
		on error errMsg
			set end of outputLines to acctName & ": ERROR - " & errMsg
		end try
	end repeat

	if (count of outputLines) is 0 then
		return "No accounts found via exchange/imap/pop/eas account. Dictionary assumption about account types needs revisiting."
	end if

	set AppleScript's text item delimiters to linefeed
	set outputText to outputLines as text
	set AppleScript's text item delimiters to ""
	return outputText
end tell
