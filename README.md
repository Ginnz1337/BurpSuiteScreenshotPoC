# Burp Suite Screenshot PoC

A Burp Suite extension for taking clean, report-ready screenshots of HTTP requests and responses
from Repeater, Logger and Proxy.

Screenshot PoC adds a `ScreenshotPoC` tab to Burp's message editors. The tab renders the request
or the response as a syntax-coloured, read-only text view with the browser noise headers hidden,
the secrets blurred, and the evidence highlighted. Take the screenshot with any screen capture
tool and paste it straight into a pentest or bug bounty report.

It renders no image. It draws text in Burp's own colours, so the PoC screenshot reads the way
Burp itself reads.

If you are looking for a way to screenshot a Burp Suite request, blur a session cookie or an API
key before it reaches a report, hide noisy headers such as `Sec-Fetch-*`, or cut the lines the
report does not need, this extension is that.

---

## Current Version

**1.0.0**

### Version History

| Version | Date       | Summary                                                                                                                                                                                         |
| ------- | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1.0.0   | 2026-09-13 | First release. `ScreenshotPoC` tab in Repeater, Logger and Proxy, on the request and the response. Header hiding, line removal, highlight and redaction rules, gutter selection, search, undo. |

---

## Main Features

- **`ScreenshotPoC` tab in Repeater, Logger and Proxy**, on both the request and the response.
  Read-only: `isModified()` is always `false`, so the tab can never alter the message. The
  response tab stays hidden until a response exists.
- **Syntax colouring matching Burp**: method, URL parts, header name and value, status, JSON,
  HTML, form fields, each with its own colour in both the dark and the light theme.
- **Header hiding** from a plain list, one name per line. `Prefix*` matches by prefix, `!Name`
  always shows. Fifteen noisy browser headers are hidden by default, and
  `Add names from this message` fills the list from the message on screen.
- **Line removal by original line number**, for the request and the response separately,
  for example `17-25, 30-38`. The numbers are the ones the gutter prints. Removed lines leave
  `··· [N lines omitted] ···`, and each field reports `15 lines removed` or names a bad entry.
- **Highlight and redaction rules**, literal or regex with a capture group, scoped to the
  request, the response, or both. Eleven presets ship with the extension, and seven redaction
  rules are on by default.
- **Blur that hides the value but keeps the shape.** Coordinate-keyed noise, not a black box, so
  two screenshots of one message are identical. A redaction can also hide its match outright.
- **Right-click menus** in the text for highlight, blur, hide and rule removal, and in the
  gutter to remove a dragged range of lines.
- **Wrap, gutter and search.** Long lines break at the pane edge, including tokens with no
  space in them. The gutter keeps the original line numbers. `Ctrl + F` searches with a count.
- **Undo** with `Ctrl + Z`, fifty steps, including the settings dialog as one step.
- **Burp theme tracking**, no reload needed. Nothing global is changed: `UIManager.put` and
  `updateComponentTreeUI` are never called, so no other extension or Burp window is affected.
- **No rebuild for the same message.** Burp re-pushes the message on every tab switch; an
  unchanged message is not rebuilt, so `ScreenshotPoC` → `Pretty` → `ScreenshotPoC` is free.

---

## Common questions

**How do I screenshot a request or a response in Burp Suite?**
Open the message in Repeater, Logger or Proxy, click the `ScreenshotPoC` tab, and capture that
area with any screen capture tool. The tab is there on both the request and the response.

**How do I blur a password, cookie, bearer token or API key before it reaches the report?**
Select the value, right-click, and choose `Blur`. Seven credential rules are on by default, so
most secrets are already covered before you look.

**Can I hide the browser noise headers?**
Yes. Fifteen noisy headers ship hidden, among them `Accept`, `Sec-Fetch-*`, `Sec-Ch-Ua-*`,
`Connection` and `Priority`. The list is editable and takes prefixes and exceptions.

**Can I remove lines from the screenshot?**
Yes. Drag over their numbers in the gutter and right-click, or type a range such as
`17-25, 30-38` in the settings. The numbers are the original message line numbers.

**Does it work in Proxy, Intruder and Scanner?**
Repeater, Logger and Proxy, on the request and on the response. Not in Intruder or Scanner.

**Will it change the rest of my Burp Suite?**
No. It never calls `UIManager.put`, and only its own scroll bars are restyled.

---

## Hotkeys

| Key          | Action                                 |
| ------------ | -------------------------------------- |
| `Ctrl + F` | Focus the search field                 |
| `Ctrl + Z` | Undo the last configuration change     |
| `F5`       | Rebuild the view for the current theme |

All three are bound on the tab panel and only fire while the caret is inside the tab. Elsewhere
in Burp those keys belong to Burp.

---

## Project Structure

```
Burp-Suite-Screenshot-PoC/
├── build.ps1                      Build and verify (PowerShell, the supported path)
├── build.bat                      Build and verify (cmd)
├── lib/
│   ├── gson-2.10.1.jar            Bundled into the fat JAR
│   └── montoya-api-2023.12.1.jar  Not committed. The build downloads it, compile only
├── src/main/java/burp/screenshot/
│   ├── BurpExtender.java          Entry point: the two editor providers, the theme source
│   ├── design/                    Tokens, theme, syntax palette, token types, icons
│   ├── engine/                    Syntax highlighter, text processor, rules, template file
│   ├── model/                     Config, rules, undo history, exchange data
│   └── ui/                        The tab, the text view, the menus, the settings dialog
│       └── components/            Buttons, fields, toggle, segmented control, scroll bars
├── src/test/java/burp/screenshot/
│   └── ScreenshotVerificationTest.java   The verification suite
├── licenses/
│   └── Apache-2.0.txt             Gson's license, which the fat JAR redistributes
├── LICENSE                        GNU General Public License v3.0
└── README.md
```

---

## System Requirements

- **Burp Suite** with the Montoya API 2023.12.1 or newer.
- **JDK 17 or newer** to build from source. The code targets Java 17.
- **Windows** for `build.ps1` and `build.bat`, which look for a JDK under
  `C:\Program Files\Java`, then `JAVA_HOME`, then `PATH`. The Java source is portable.

---

## Dependencies

| Dependency  | Version   | Scope                                                         |
| ----------- | --------- | ------------------------------------------------------------- |
| Montoya API | 2023.12.1 | Compile only. Burp provides it at runtime.                    |
| Gson        | 2.10.1    | Bundled into the fat JAR. Reads and writes the settings file. |

`gson-2.10.1.jar` is committed. `montoya-api-2023.12.1.jar` is not: the build downloads it from
Maven Central on the first run, because the Burp Suite Professional licence grants no right to
redistribute it. There is no test framework: the verification suite is a plain `main` class with
assertions.

---

## Installation

### Build from source

```powershell
.\build.ps1
```

or `build.bat`. The first run downloads `lib\montoya-api-2023.12.1.jar` from Maven Central, so it
needs a network connection; later runs reuse the file. The script compiles with `--release 17`,
packages `build\libs\burp-screenshot-poc.jar`, then runs the verification suite with `-ea`. A
failed check exits non-zero. The suite runs after packaging, so a failing build still leaves a JAR
behind: read the exit status, not the JAR timestamp.

### Load into Burp Suite

1. Open Burp Suite.
2. Go to `Extensions` and open the `Installed` tab.
3. Click `Add`, set `Extension type` to `Java`.
4. Set `Extension file` to `build\libs\burp-screenshot-poc.jar`.
5. Click `Next`, then `Close`.

After a rebuild, use `Reload` in the `Installed` tab. No Burp restart is needed.

---

## Usage

1. Open the request in Repeater, Logger or Proxy.
2. Open the `ScreenshotPoC` tab on the response side. The `Date` header is highlighted already
   and the browser noise headers are hidden.
3. Select a token that must not reach the report, right-click, and choose `Blur` or `Hide`.
4. To drop lines, drag over their numbers in the gutter, right-click, and choose
   `Remove lines 17-24 from the PoC`.
5. Capture the text area with any screen capture tool and paste it into the report.

The extension takes no part in step 5. There is no export button and no image clipboard.

**Settings.** The palette icon at the right of the strip above the text opens
`Screenshot PoC view settings`, with two tabs. `Headers` holds the hidden header list, its
scope, and the two `Remove lines` fields. `Rules` holds every highlight and redaction rule,
each editable on its own card, with a preset picker. Changes appear behind the dialog as they
are made, debounced by 250 ms.

**Header list syntax.** `X-Internal-*` hides by prefix. `!Set-Cookie` always shows and wins over
every other entry. `Server` hides exactly that header, case-insensitively. The first line of the
message is never filtered.

**Configuration** lives in `~\.burp_poc_text_view.json`. Delete it to return to the shipped
defaults. A file from an older build is migrated on load: missing shipped rules come back, and a
rule deleted on purpose stays deleted.

**Blur is not a security boundary.** The characters stay selectable and copyable, because
whoever takes the screenshot already has the value in Repeater. The blur is there so the image
pasted into a report does not carry the token. To truly remove a value, delete the header in
Repeater.

---

## CI/CD

No CI is configured. The build is local, and its exit status is the gate.

---

## Testing

`ScreenshotVerificationTest` is a plain `main` class with 33 checks. `build.ps1` runs it with
`-ea` and fails the build on the first failed assertion. It covers the tab scope, header hiding,
line ranges and their bad inputs, the Date rule and its migration, rule matching, undo, search,
wrap at long unbroken tokens, the gutter menu, and the marks following the viewport on a 2,000
line message.

Several checks measure pixels, not just state. The suite writes captures into `build\` and
asserts on them:

- The blur region is compared against a control image with no rule: the glyph strokes must lose
  most of their contrast, and the region must not be flat. This rejects both a blur that hides
  nothing and a blur that hides the shape as well.
- The last character of a value must be as blurred as the rest of it, in both directions. This
  is the check for the off-by-one that leaves a character showing at the right edge.
- The highlight region must keep glyph pixels, so a highlight cannot swallow the text it marks.
- The wrap checks count characters drawn outside the inner edge of the pane and require zero.

---

## Contributing

- `.\build.ps1` must pass. It is the only gate.
- Add a check for a bug fix. Every check is an `assert` in the same `main` class.
- Resolve colours at paint time, never in a constructor, or a theme switch leaves the view in
  the old theme.
- Never call `UIManager.put` or `updateComponentTreeUI`. The extension shares Burp's JVM, so a
  global change reaches every other extension and Burp itself.
- `engine/` must not import `ui/`.

---

## Author

[@Ginnz1337](https://github.com/Ginnz1337)

---

## License

GNU General Public License v3.0 or later. See [LICENSE](LICENSE).

Copyright (C) 2026 Ginnz1337

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

The assembled JAR bundles Gson 2.10.1, which is licensed under Apache-2.0 and is compatible with
GPL-3.0. Its license text is in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

The Montoya API is not bundled and not redistributed here. It is covered by the Burp Suite
Professional licence, so the build downloads it from Maven Central to compile against it, and Burp
provides it at runtime.

---

This extension draws messages that are already in your Burp Suite session. It sends no traffic,
contacts no server, and writes nothing except its own settings file. Use it only against systems
you are authorised to test.
