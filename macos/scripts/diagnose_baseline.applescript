-- Control test: does the SAME kind of rapid System Events polling that's
-- hanging against Microsoft Outlook also hang against a totally unrelated,
-- non-corporate app? If TextEdit is fast and clean here, that points at
-- something specific to Outlook (its own load, or active interference).
-- If TextEdit ALSO hangs/flakes the same way, the problem is this
-- technique/machine in general, not Outlook specifically.

tell application "TextEdit"
	activate
	if (count of documents) = 0 then make new document
end tell
delay 1

set successCount to 0
set failCount to 0
set startTime to (current date)

repeat 40 times
	try
		with timeout of 30 seconds
			tell application "System Events"
				tell process "TextEdit"
					set frontmost to true
					if (count of windows) > 0 then
						set winName to (name of front window) as text
						set successCount to successCount + 1
					end if
				end tell
			end tell
		end timeout
	on error
		set failCount to failCount + 1
	end try
	delay 0.25
end repeat

set endTime to (current date)
set elapsed to (endTime - startTime)

say ("Baseline test done. " & (successCount as text) & " successes. " & (failCount as text) & " failures. Took " & (elapsed as text) & " seconds.")
