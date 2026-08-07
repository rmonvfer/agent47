# Patched Mosaic (0.18.0)

`repo/` is a local Maven repository vendoring two of Mosaic's published JVM artifacts, rebuilt from
a patched checkout of [jakewharton/mosaic](https://github.com/JakeWharton/mosaic) at tag `0.18.0`
(commit `e1e2a71ec7a538bc656c9de982098116da14591e`). Mosaic is Apache License 2.0; see
`repo/com/jakewharton/mosaic/*/0.18.0/*.pom` for the upstream license and attribution, unchanged
from the published artifacts.

Wired in via the root `build.gradle.kts`'s `allprojects { repositories { ... } }` block, scoped
with `exclusiveContent` to exactly these two coordinates so every other dependency (including the
untouched `mosaic-terminal-jvm` and `mosaic-tty-jvm`) still resolves from Maven Central as normal.
It must live there and not in `settings.gradle.kts`'s `dependencyResolutionManagement`: the root
build declares project-level repositories, and Gradle's default `RepositoriesMode.PREFER_PROJECT`
makes those win, silently ignoring a settings-level repository. Getting this wrong still builds —
it just resolves the unpatched jars from Maven Central. To verify the patched jars are actually in
use, delete `~/.gradle/caches/modules-2/metadata-*` and
`~/.gradle/caches/modules-2/files-2.1/com.jakewharton.mosaic/mosaic-runtime-jvm`, then confirm a
`./gradlew --info` build logs no `Downloading .../mosaic-runtime-jvm` line from Maven Central.

## Why

Mosaic 0.18.0 drops input and corrupts pasted text before it ever reaches application code:

- `TtyTerminalKt.asTerminalIn` buffers every parsed terminal event (one per keystroke) through a
  `Channel<Event>(capacity = 64, onBufferOverflow = DROP_OLDEST)`, drained only once per Compose
  frame. A fast paste produces events faster than the frame-driven drain can keep up in some
  conditions, and `DROP_OLDEST` silently discards a contiguous run from the paste's middle once
  the backlog exceeds 64.
- `EventParser`'s ground-state byte parser normalizes a raw line feed (`0x0A`) to the same
  codepoint as Enter (`0x0D`) unconditionally. That's correct for an actual keypress, but it means
  every line break inside a pasted block of text arrives as a synthetic Enter and submits the
  input mid-paste, regardless of whether bracketed paste mode is enabled.
- `EventParser` does recognize `ESC[200~`/`ESC[201~` and emits a real `BracketedPasteEvent`, but
  `MosaicComposition`'s per-frame listener only forwards `Event`s that are `KeyboardEvent`;
  `BracketedPasteEvent` (along with `ResizeEvent`, `MouseEvent`, etc.) is silently discarded before
  it ever reaches application code, so there was no way to tell a paste from fast typing.

None of this is fixable from application code: the loss happens before events reach us, and the
frame listener that discards `BracketedPasteEvent` is a synthetic Kotlin-compiler-generated inner
class wired to `MosaicComposition`'s private state via `access$` bridges, sharing code with the
Ctrl+C force-quit safety net — not a safe target for a classpath-shadow patch.

## What changed

`mosaic-tty-terminal` (module `com.jakewharton.mosaic.tty.terminal`):

- `TtyTerminal.kt`: the events channel is now `Channel<Event>(Channel.UNLIMITED)` instead of
  `Channel<Event>(64, onBufferOverflow = DROP_OLDEST)`. The consumer drains the channel fully every
  frame, so occupancy returns to near-zero immediately after any burst; unbounded capacity only
  matters during the burst itself.
- `EventParser.kt`: tracks a private `insidePaste` flag, set/cleared exactly where the parser
  already recognizes `BracketedPasteEvent(start = true/false)`. While `insidePaste` is true, a raw
  line feed (`0x0A`) is emitted as a literal `KeyboardEvent(0x0A)` instead of being normalized to
  `0x0D` ("Enter"). Outside a paste, byte handling is unchanged.

`mosaic-runtime` (module `com.jakewharton.mosaic`):

- `mosaic.kt`: `MosaicComposition`'s per-frame drain loop now also matches `BracketedPasteEvent`,
  forwarding it as a synthetic `KeyEvent` whose `key` is the literal marker text (`"ESC[200~"` /
  `"ESC[201~"`). Everything else about the loop — including the Ctrl+C force-quit safety net — is
  unchanged.

## Rebuilding

```
git clone --branch 0.18.0 --depth 1 https://github.com/JakeWharton/mosaic.git
cd mosaic
# apply the three patches described above to:
#   mosaic-tty-terminal/src/commonMain/kotlin/com/jakewharton/mosaic/tty/terminal/TtyTerminal.kt
#   mosaic-tty-terminal/src/commonMain/kotlin/com/jakewharton/mosaic/tty/terminal/EventParser.kt
#   mosaic-runtime/src/commonMain/kotlin/com/jakewharton/mosaic/mosaic.kt
# mosaic-tty-terminal/build.gradle: point its mosaic-tty dependency at the published artifact
# (`com.jakewharton.mosaic:mosaic-tty:0.18.0`) instead of `projects.mosaicTty`, so building it
# doesn't invoke mosaic-tty's jextract step, which spawns its own bundled JDK 22 and crashes with
# SIGBUS on macOS 27 betas (see the agent47 memory note macos27-jvm-sigbus.md).
./gradlew :mosaic-tty-terminal:jvmJar :mosaic-runtime:jvmJar
# copy mosaic-tty-terminal/build/libs/mosaic-tty-terminal-jvm-0.18.0.jar and
# mosaic-runtime/build/libs/mosaic-runtime-jvm-0.18.0.jar into repo/com/jakewharton/mosaic/*/0.18.0/,
# replacing the existing jars. The .pom files are the unmodified upstream POMs and do not need to
# change unless a dependency version changes upstream.
```

## Upgrading

When agent47 upgrades to a newer Mosaic release, re-check whether the release fixes the channel
and paste-forwarding issues upstream; if so, drop this directory and the `exclusiveContent` block
in the root `build.gradle.kts`, and revert to a plain `implementation("com.jakewharton.mosaic:...")`
dependency. If not, re-apply the same three patches against the new tag.
