import subprocess

xml = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n'
       '<map>\n    <boolean name="avatars" value="false" />\n</map>\n').encode()

r = subprocess.run(
    ["adb", "shell", "run-as com.vim.vrcfriends sh -c 'cat > shared_prefs/vrc-ui.xml'"],
    input=xml, capture_output=True)
print("write rc", r.returncode, r.stderr.decode(errors="replace")[:120])

r2 = subprocess.run(["adb", "shell", "run-as com.vim.vrcfriends cat shared_prefs/vrc-ui.xml"],
                    capture_output=True)
print(r2.stdout.decode(errors="replace"))
