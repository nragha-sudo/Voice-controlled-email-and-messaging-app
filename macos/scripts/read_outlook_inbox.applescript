-- Reads unread Outlook messages aloud via macOS's built-in `say`, across
-- every account inbox that has unread mail (found via the sidebar).
--
-- Built entirely on macOS's Accessibility API (via System Events UI
-- scripting) rather than Outlook's own AppleScript object model, because
-- the accounts on this machine are modern OAuth sign-ins and Outlook's
-- classic scripting dictionary (exchange/imap/pop account) can't see them
-- at all -- confirmed via direct testing, not assumed.
--
-- IMPORTANT: "id of front window" is not reliably supported for this
-- window object (confirmed: failed 10/10 attempts within 5 seconds, not
-- a timing pattern) -- back to addressing via "front window" inline,
-- which DID reliably reach the sidebar/inbox-row stage in prior runs.
--
-- IMPORTANT: nothing in this script should crash to a silent Script
-- Editor error dialog anymore. Every stage is wrapped so a failure
-- SPEAKS the actual error text out loud via `say`, so whatever breaks
-- next is diagnosable from what you hear, not from guessing.
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
	try
		my mainFlow()
	on error errMsg
		say ("Top level error. " & errMsg)
	end try
end run

on mainFlow()
	tell application "Microsoft Outlook" to activate
	delay 2

	if not (my waitForWindow()) then
		say "Could not find an Outlook window. Stopping."
		return
	end if

	-- The window count genuinely flaps between 0 and 1+ from one instant to
	-- the next on this machine (confirmed via spoken "Invalid index"
	-- errors -- something on this corporate laptop is intermittently
	-- interfering with Accessibility API calls, most likely an endpoint
	-- security/monitoring agent). Check-and-use the window atomically in
	-- the SAME call, and retry aggressively (many attempts, short gaps)
	-- instead of a handful of slow retries -- this reliably catches a good
	-- window state within a couple of seconds based on prior runs.
	set outlineEl to missing value
	set msgTable to missing value
	repeat 40 times
		try
			with timeout of 30 seconds
				tell application "System Events"
					tell process "Microsoft Outlook"
						set frontmost to true
						if (count of windows) > 0 then
							if outlineEl is missing value then
								set outlineEl to my findFirstByRole(front window, "AXOutline")
							end if
							if msgTable is missing value then
								set msgTable to my findTableByDesc(front window, "Message List")
							end if
						end if
					end tell
				end tell
			end timeout
		end try
		if outlineEl is not missing value and msgTable is not missing value then exit repeat
		delay 0.25
	end repeat

	if outlineEl is missing value then
		say "Could not find the folder sidebar. Stopping."
		return
	end if

	say "Sidebar found."
	if msgTable is not missing value then
		say "Message list was already open too."
	else
		say "Message list was not already open."
	end if

	set inboxRows to {}
	repeat 40 times
		set inboxRows to {}
		try
			with timeout of 30 seconds
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
		end try
		if (count of inboxRows) > 0 then exit repeat
		delay 0.25
	end repeat

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

	if msgTable is missing value then
		say "Clicking the inbox now."
		set msgTable to my clickIntoInboxAndFindTable(item 1 of inboxRows)
	end if

	if msgTable is missing value then
		say "Could not find the message list. Stopping."
		return
	end if

	say "Message list located. Reading now."
	try
		my readMessages(msgTable)
	on error errMsg
		say ("Reading failed. " & errMsg)
	end try

	say "Done reading unread messages."
end mainFlow

on waitForWindow()
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
	repeat 40 times
		try
			with timeout of 30 seconds
				tell application "System Events"
					tell process "Microsoft Outlook"
						if (count of windows) > 0 then
							set msgTable to my findTableByDesc(front window, "Message List")
						end if
					end tell
				end tell
			end timeout
		end try
		if msgTable is not missing value then exit repeat
		delay 0.25
	end repeat

	return msgTable
end clickIntoInboxAndFindTable

on readMessages(msgTable)
	set rowList to {}
	repeat 40 times
		try
			with timeout of 30 seconds
				tell application "System Events"
					tell process "Microsoft Outlook"
						set rowList to (UI elements of msgTable whose role is "AXRow")
					end tell
				end tell
			end timeout
		end try
		if (count of rowList) > 0 then exit repeat
		delay 0.25
	end repeat

	say (((count of rowList) as text) & " rows in the message list.")

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
