-- Reads unread Outlook messages aloud via macOS's built-in `say`, across
-- every account inbox that has unread mail (found via the sidebar).
--
-- Built entirely on macOS's Accessibility API (via System Events UI
-- scripting) rather than Outlook's own AppleScript object model, because
-- the accounts on this machine are modern OAuth sign-ins and Outlook's
-- classic scripting dictionary (exchange/imap/pop account) can't see them
-- at all -- confirmed via direct testing, not assumed.
--
-- IMPORTANT: never store "front window" (or any window) in a variable and
-- use it later -- always reference "front window" literally, inline, in
-- the same tell block as the work that uses it.
--
-- IMPORTANT: do NOT click into the Inbox row before reading. Confirmed
-- (diagnose_outlook_ui.applescript) that the front window is already
-- titled "Inbox • <account>" by default -- Inbox is already the active
-- folder. Clicking it anyway reliably breaks every subsequent window
-- lookup with "Can't get window ..." regardless of how the window is
-- addressed or how long we wait afterward, so this version reads the
-- message list straight from whatever's already on screen, and only
-- falls back to clicking the sidebar row if that list isn't found.
--
-- v1 scope: reads the message-list row's own text (sender + subject +
-- time + a preview snippet -- same text visible in the inbox list), which
-- is real and audibly useful today. It does NOT yet open each message to
-- read the full untruncated body from the reading pane, and does not yet
-- mark messages read or support spoken reply/skip/done -- those are the
-- next layer once this core loop is confirmed working end to end.
--
-- Run directly in Script Editor: paste this over the existing script and
-- press Run. Requires Script Editor to have Accessibility permission
-- (System Settings > Privacy & Security > Accessibility) and Automation
-- permission to control Microsoft Outlook and System Events (System
-- Settings > Privacy & Security > Automation).

property maxMessagesPerAccount : 8

on run
	tell application "Microsoft Outlook" to activate
	delay 2

	if not (my waitForWindow()) then
		say "Could not find an Outlook window. Stopping."
		return
	end if

	-- Look for the sidebar and the message list in the same pass, without
	-- clicking anything first.
	set outlineEl to missing value
	set msgTable to missing value
	repeat 6 times
		if my waitForWindow() then
			try
				with timeout of 60 seconds
					tell application "System Events"
						tell process "Microsoft Outlook"
							if outlineEl is missing value then
								set outlineEl to my findFirstByRole(front window, "AXOutline")
							end if
							if msgTable is missing value then
								set msgTable to my findTableByDesc(front window, "Message List")
							end if
						end tell
					end tell
				end timeout
			end try
		end if
		if outlineEl is not missing value and msgTable is not missing value then exit repeat
		delay 1
	end repeat

	if outlineEl is missing value then
		say "Could not find the folder sidebar. Stopping."
		return
	end if

	set inboxRows to {}
	with timeout of 180 seconds
		tell application "System Events"
			tell process "Microsoft Outlook"
				set allRows to (UI elements of outlineEl whose role is "AXRow")
				repeat with r in allRows
					try
						set c to item 1 of (UI elements of r whose role is "AXCell")
						set d to (description of c) as text
						if d starts with "Inbox;" and d contains "unread" then
							set end of inboxRows to r
						end if
					end try
				end repeat
			end tell
		end tell
	end timeout

	-- Diagnostic: speak what was actually found before doing anything else,
	-- so a silent run and a wrong-account run are distinguishable out loud.
	say (((count of inboxRows) as text) & " matching inbox rows found.")

	if (count of inboxRows) > 0 then
		set firstDesc to ""
		with timeout of 60 seconds
			tell application "System Events"
				tell process "Microsoft Outlook"
					try
						set c to item 1 of (UI elements of (item 1 of inboxRows) whose role is "AXCell")
						set firstDesc to (description of c) as text
					end try
				end tell
			end tell
		end timeout
		say ("First inbox found: " & firstDesc)
	end if

	if (count of inboxRows) = 0 then
		say "No inbox with unread mail was found."
		say "Done reading unread messages."
		return
	end if

	-- If the message list wasn't already on screen (e.g. Outlook opened to
	-- a different folder), fall back to clicking the matching sidebar row.
	if msgTable is missing value then
		say "Message list wasn't already open. Clicking the inbox."
		set msgTable to my clickIntoInboxAndFindTable(item 1 of inboxRows)
	end if

	if msgTable is missing value then
		say "Could not find the message list. Stopping."
		return
	end if

	my readMessages(msgTable)

	say "Done reading unread messages."
end run

on waitForWindow()
	-- Only checks that *a* window exists -- never captures or returns a
	-- reference to it. Callers always address "front window" fresh, inline,
	-- right where they use it.
	repeat 10 times
		with timeout of 30 seconds
			tell application "System Events"
				tell process "Microsoft Outlook"
					set frontmost to true
					try
						if (count of windows) > 0 then return true
					end try
				end tell
			end tell
		end timeout
		delay 0.5
	end repeat
	return false
end waitForWindow

on findFirstByRole(elem, targetRole)
	tell application "System Events"
		set r to ""
		try
			set r to (role of elem) as text
		end try
		if r is targetRole then return elem
		try
			set kids to UI elements of elem
			repeat with k in kids
				set found to my findFirstByRole(k, targetRole)
				if found is not missing value then return found
			end repeat
		end try
	end tell
	return missing value
end findFirstByRole

on findTableByDesc(elem, targetDesc)
	tell application "System Events"
		set elemDesc to ""
		try
			set elemDesc to (description of elem) as text
		end try
		set elemRole to ""
		try
			set elemRole to (role of elem) as text
		end try
		if elemDesc is targetDesc and elemRole is "AXTable" then return elem
		try
			set kids to UI elements of elem
			repeat with k in kids
				set found to my findTableByDesc(k, targetDesc)
				if found is not missing value then return found
			end repeat
		end try
	end tell
	return missing value
end findTableByDesc

on clickIntoInboxAndFindTable(inboxRow)
	with timeout of 180 seconds
		tell application "System Events"
			tell process "Microsoft Outlook"
				try
					perform action "AXPress" of inboxRow
				on error
					click inboxRow
				end try
			end tell
		end tell
	end timeout
	delay 3

	set msgTable to missing value
	repeat 6 times
		if my waitForWindow() then
			try
				with timeout of 60 seconds
					tell application "System Events"
						tell process "Microsoft Outlook"
							set msgTable to my findTableByDesc(front window, "Message List")
						end tell
					end tell
				end timeout
			on error
				set msgTable to missing value
			end try
		end if
		if msgTable is not missing value then exit repeat
		delay 1
	end repeat

	return msgTable
end clickIntoInboxAndFindTable

on readMessages(msgTable)
	set rowList to {}
	with timeout of 180 seconds
		tell application "System Events"
			tell process "Microsoft Outlook"
				set rowList to (UI elements of msgTable whose role is "AXRow")
			end tell
		end tell
	end timeout

	set readCount to 0
	repeat with msgRow in rowList
		if readCount = maxMessagesPerAccount then exit repeat
		set rowDesc to ""
		with timeout of 180 seconds
			tell application "System Events"
				tell process "Microsoft Outlook"
					try
						set c to item 1 of (UI elements of msgRow whose role is "AXCell")
						set rowDesc to (description of c) as text
					end try
				end tell
			end tell
		end timeout

		set hasTime to (rowDesc contains "AM," or rowDesc contains "PM,")
		set isGroupHeader to (rowDesc contains "Expanded,")

		if rowDesc is not "" and hasTime and not isGroupHeader then
			set readCount to readCount + 1
			say ("New message. " & rowDesc)
		end if
	end repeat
end readMessages
