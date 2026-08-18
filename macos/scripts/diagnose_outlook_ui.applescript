-- Diagnostic only: dumps the accessibility (UI element) tree of Outlook's
-- front window to a text file, so we can see the REAL roles/descriptions
-- Outlook exposes on this machine instead of guessing (the "AXOutline"
-- guess in read_outlook_inbox.applescript was wrong -- it returned missing
-- value, which is what caused "Can't get every UI element of missing
-- value.").
--
-- Run this in Script Editor. It writes to:
--   ~/Desktop/outlook_ui_dump.txt
-- Open that file (TextEdit, or `cat` in Terminal) and send back its
-- contents, or at least the lines that look like they belong to the left
-- folder sidebar and the message list.

property maxDepth : 6
property outFile : (POSIX path of (path to desktop folder)) & "outlook_ui_dump.txt"

on run
	tell application "Microsoft Outlook" to activate
	delay 1

	set dumpLines to {}
	with timeout of 180 seconds
		tell application "System Events"
			tell process "Microsoft Outlook"
				set frontmost to true
				set win to front window
				set end of dumpLines to "WINDOW: " & (name of win)
				my walk(win, 0, dumpLines)
			end tell
		end tell
	end timeout

	set AppleScript's text item delimiters to linefeed
	set dumpText to dumpLines as text
	set AppleScript's text item delimiters to ""

	set fh to open for access outFile with write permission
	set eof of fh to 0
	write dumpText to fh
	close access fh

	say "UI dump written. " & ((count of dumpLines) as text) & " lines. Check the desktop file."
end run

on walk(elem, depth, dumpLines)
	if depth > maxDepth then return
	tell application "System Events"
		set r to ""
		set sub to ""
		set d to ""
		set t to ""
		try
			set r to (role of elem) as text
		end try
		try
			set sub to (subrole of elem) as text
		end try
		try
			set d to (description of elem) as text
		end try
		try
			set t to (title of elem) as text
		end try

		set indent to ""
		repeat depth times
			set indent to indent & "  "
		end repeat

		set line to indent & "[" & r & "] sub=" & sub & " title=" & t & " desc=" & d
		set end of dumpLines to line

		try
			set kids to UI elements of elem
			repeat with k in kids
				my walk(k, depth + 1, dumpLines)
			end repeat
		end try
	end tell
end walk
