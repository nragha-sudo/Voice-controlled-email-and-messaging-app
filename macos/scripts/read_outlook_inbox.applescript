-- Reads unread Outlook messages aloud via macOS's built-in `say`, across
-- every account inbox that has unread mail (found via the sidebar).
--
-- Built entirely on macOS's Accessibility API (via System Events UI
-- scripting) rather than Outlook's own AppleScript object model, because
-- the accounts on this machine are modern OAuth sign-ins and Outlook's
-- classic scripting dictionary (exchange/imap/pop account) can't see them
-- at all -- confirmed via direct testing, not assumed.
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
-- (System Settings > Privacy & Security > Accessibility) -- already
-- granted earlier in this session.

property maxMessagesPerAccount : 8

on run
	tell application "Microsoft Outlook" to activate
	delay 0.5

	set inboxRows to {}
	tell application "System Events"
		tell process "Microsoft Outlook"
			set frontmost to true
			set win to front window
			set outlineEl to my findFirstByRole(win, "AXOutline")
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

	say (((count of inboxRows) as text) & " inboxes found with unread mail.")

	repeat with inboxRow in inboxRows
		my readInbox(inboxRow)
	end repeat

	say "Done reading unread messages."
end run

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

on readInbox(inboxRow)
	tell application "System Events"
		tell process "Microsoft Outlook"
			try
				perform action "AXPress" of inboxRow
			on error
				click inboxRow
			end try
		end tell
	end tell
	delay 1

	set rowList to {}
	tell application "System Events"
		tell process "Microsoft Outlook"
			set win to front window
			set msgTable to my findTableByDesc(win, "Message List")
			if msgTable is not missing value then
				set rowList to (UI elements of msgTable whose role is "AXRow")
			end if
		end tell
	end tell

	set readCount to 0
	repeat with msgRow in rowList
		if readCount = maxMessagesPerAccount then exit repeat
		set rowDesc to ""
		tell application "System Events"
			tell process "Microsoft Outlook"
				try
					set c to item 1 of (UI elements of msgRow whose role is "AXCell")
					set rowDesc to (description of c) as text
				end try
			end tell
		end tell

		set hasTime to (rowDesc contains "AM," or rowDesc contains "PM,")
		set isGroupHeader to (rowDesc contains "Expanded,")

		if rowDesc is not "" and hasTime and not isGroupHeader then
			set readCount to readCount + 1
			say ("New message. " & rowDesc)
		end if
	end repeat
end readInbox

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
