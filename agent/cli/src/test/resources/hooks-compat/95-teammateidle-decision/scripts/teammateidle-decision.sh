#!/bin/sh
# exit 2 让成员继续工作。
cat > /dev/null
printf '%s' 'Build artifact missing. Run the build before stopping.' >&2
exit 2
