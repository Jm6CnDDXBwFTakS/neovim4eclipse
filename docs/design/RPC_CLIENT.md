# Design note: our own msgpack-rpc client instead of neovim-java

The plug-in talks to `nvim --embed` through its own msgpack-rpc client in `core/rpc`, built on
**msgpack-core**, the standard MessagePack library for Java, which is the only library embedded in
`lib/`. The client replaced [neovim-java](https://codeberg.org/neovim-java/neovim-java)
(`com.ensarsarajcic.neovim.java`, five jars), which pulled in Jackson (three jars),
`jackson-dataformat-msgpack` and SLF4J (two jars): eleven of twelve jars, about 2.4 MB of the 2.5 MB
in `lib/`, went, and with them four workarounds for neovim-java's bugs (section 6). The plug-in jar
in the ZIP is about 416 KB.

The protocol, [msgpack-rpc](https://github.com/msgpack-rpc/msgpack-rpc/blob/master/spec.md), has
four message shapes and fits in a page, so owning it costs little. It also fits the project's aim
as a learning project: the RPC layer is code a reader can study, not a black box.

## 1. The protocol

Every message is one MessagePack array:

```text
request       [0, msgid, method, params]     expects exactly one response with the same msgid
response      [1, msgid, error, result]      error is nil, or Neovim's [type, message]
notification  [2, method, params]            no answer
```

Both sides send all three. Neovim answers our requests (`nvim_input`, ...), and we answer Neovim's
(`vim.rpcrequest`). Responses may arrive in any order relative to notifications, and Neovim may send
us a request while we wait for its response to ours (`:w` sent as `nvim_command` triggers
`eclipse_write`, and the response to `nvim_command` comes only after our answer).

Buffers, windows and tab pages travel as MessagePack *extension* values whose payload is the
handle's number, itself msgpack-encoded. Their type codes come from `nvim_get_api_info()` and have
been `0`, `1`, `2` since Neovim 0.1, so the client hard-codes them. If they ever change, read them
from `nvim_get_api_info()` at open (one request).

## 2. The classes

All in `core/rpc`, with no `org.eclipse` imports, so they are compiled and tested in `tests/unit`.

| Class | Responsibility |
|---|---|
| `MsgpackValues` | Java ↔ MessagePack for the values the API uses (2.1). |
| `Handle` | `record Handle(Kind kind, long id)` with `Kind { BUFFER, WINDOW, TABPAGE }`, for extension values. `Handle.CURRENT_WINDOW` is window `0`, which Neovim's window functions take as the current window, so `NeovimSession.getCursor`/`setCursor` need no `nvim_get_current_win` round trip. |
| `RpcChannel` | The connection: a reader thread, request ids, pending responses, writing, dispatching Neovim's notifications and requests, closing (section 3). |
| `Incoming` | What Neovim sends on its own: `notification(String, List<?>)` and `request(String, List<?>)`. |
| `RpcRequests` | One method, `request(method, params)`, which `RpcChannel` implements; lets `NeovimApiTest` record calls with a lambda instead of pipes. |
| `NeovimApi` | The Neovim functions the plug-in calls (2.2) as thin typed methods returning `CompletableFuture`s. |
| `MsgpackNeovimApi` | `NeovimApi` over `RpcRequests`, each method one line over `request`. |
| `Mode` | `nvim_get_mode`'s answer, `Mode(String mode, boolean blocking)`; used by `StateSync` and `KeyInput`. |
| `WinCursor` | `nvim_win_get_cursor`'s answer, `WinCursor(int row, int col)`. `NeovimSession` converts it to `core/CursorPos`, the listeners' type, so `core/rpc` does not depend on `core` (which depends on it). |
| `NeovimErrorException` | Neovim's error answer: its error type and message, so `Rpc.errorMessage` finds `E94: ...` in it. |
| `NeovimConnectionClosedException` | A request that cannot be answered because the connection ended, and a `notify` after the end. |
| `UnexpectedAnswerException` | An answer of the wrong shape for a typed method. |

Every exception class extends the abstract, unchecked `util/NeovimExceptionBase`, as all of the
plug-in's do.

### 2.1 `MsgpackValues`

- Static `pack(MessagePacker, Object)` and `unpack(MessageUnpacker)`, streaming, as `RpcChannel`
  needs them; the class comment has the type table: `null`, `Boolean`, `Long`/`Integer`, `Double`,
  `String`, `List`, `Map`, `Handle`.
- Integers always decode as `Long`, maps as `LinkedHashMap`. There is no `BigInteger`: Neovim's
  Integer is signed 64-bit, and a larger uint64 fails as a protocol error. MessagePack `bin` decodes
  as `byte[]`. Strings that are not valid UTF-8 decode with the replacement character (msgpack-core's
  default).
- Extension types 0, 1, 2 decode as `Handle`; any other is an `IOException`. A Java type with no
  MessagePack form is an `IllegalArgumentException`, possibly after part of a list was written, so
  `RpcChannel` packs each message into a buffer before sending it.
- msgpack-core reports truncated input (also a stream that ends, when Neovim exits) with unchecked
  `MessagePackException`s, not `IOException`, so the reader loop catches both.
- The parsing code elsewhere goes through `Number` and `String.valueOf`
  (`StateSnapshot.toInt`), never `instanceof Integer`, so the integer type of
  a decoded value does not matter.

### 2.2 The API functions

`NeovimApi` is flat and uses Neovim's names without `nvim_` (`bufSetLines(Handle, ...)` is
`nvim_buf_set_lines`). It has only the functions the plug-in calls; adding one is a line.

| Method | Neovim function | Called from |
|---|---|---|
| `execLua` | `nvim_exec_lua` | `NeovimSession`, `DocumentBuffers` (most features: a script from `LuaScripts`) |
| `command` | `nvim_command` | `NeovimSession` |
| `input` | `nvim_input` | `NeovimSession` (key input) |
| `setClientInfo` | `nvim_set_client_info` | `NeovimConnection` |
| `uiAttach` | `nvim_ui_attach` | `NeovimConnection` |
| `uiTryResize` | `nvim_ui_try_resize` | `NeovimSession`, for the viewport ([VIEWPORT.md](VIEWPORT.md)) |
| `getMode` | `nvim_get_mode` | `NeovimSession` for `StateSync` and `KeyInput` |
| `setVar` | `nvim_set_var` | `NeovimSession` |
| `getChannelId` | `nvim_get_chan_info` (the one field read) | `NeovimSession` (channel for `vim.rpcrequest`) |
| `createBuf` | `nvim_create_buf` | `NeovimSession`, `DocumentBuffers` |
| `setCurrentBuf` | `nvim_set_current_buf` | `DocumentBuffers`, `NeovimSession` |
| `bufAttach` | `nvim_buf_attach` | `NeovimSession`, `DocumentBuffers` |
| `bufSetName` | `nvim_buf_set_name` | `DocumentBuffers` |
| `bufDelete` | `nvim_buf_delete` | `DocumentBuffers` |
| `bufGetLines` / `bufSetLines` | `nvim_buf_get_lines` / `nvim_buf_set_lines` | `NeovimSession` |
| `winGetCursor` / `winSetCursor` | `nvim_win_get_cursor` / `nvim_win_set_cursor` | `NeovimSession` |

A buffer's number (`DocumentBuffers.bufnr`) is the handle's id, no call. A full typed API (hundreds
of methods) is not needed: most features go through `nvim_exec_lua` with a script from
`LuaScripts`, whose results the plug-in parses itself from plain lists and maps (`StateSnapshot`,
`NeovimSessionParsingTest`).

## 3. `RpcChannel` in detail

```java
public final class RpcChannel implements RpcRequests, AutoCloseable {
    public RpcChannel(InputStream in, OutputStream out, Incoming incoming, String threadName);
    public void start();                                   // starts the reader thread
    public CompletableFuture<Object> request(String method, Object... params);
    public void notify(String method, Object... params);
    public void closing();    // from now on the end of the input is expected
    public void close();
}
```

- **Request ids:** msgids are uint32 and wrap. The future goes into a map *before* the message is
  written, so a fast answer cannot arrive first. A pending request is removed when its future
  completes in any way, a caller's `cancel` included; a late answer, or one with an unknown id, is
  logged at FINE and dropped.
- **Writing:** any thread may call `request` or `notify`. Each message is packed into a buffer and
  then written whole under a lock, so two threads never mix their bytes and a value that cannot be
  encoded leaves nothing half-written. A reply to Neovim's request that cannot be encoded becomes an
  error reply.
- **Reading:** one daemon thread per channel (`neovim4eclipse-rpc`), no pool. For each message it
  - completes the matching future (`complete(result)`, or `completeExceptionally(new
    NeovimErrorException(...))`);
  - calls `incoming.notification(...)`;
  - calls `incoming.request(...)` and writes exactly one response: the result, or `[0, message]`
    when it threw.

  Notifications and requests are handled on the reader thread, in arrival order, one at a time:
  while a handler runs, nothing else is read. `ARCHITECTURE.md` relies on that (the clipboard paste
  waits up to 2 s for the UI thread on this thread). Two rules follow, both in `RpcChannel`'s class
  comment: a handler must never wait for an answer from Neovim (it would wait for the very thread it
  blocks), and since futures are completed on the reader thread, continuations (`thenApply`) must
  not block either. Callers wait with `Rpc.await` on their own thread.
- **End of input:** when the input ends (Neovim exited) or breaks, the reader stops and every
  pending request fails at once with `NeovimConnectionClosedException`, as does any later one, so no
  caller waits for its full timeout. After `closing()` (we asked Neovim to exit) the end is expected
  and logged at FINE only; before it, it is logged once at WARNING ("Neovim exited unexpectedly").
- **Close:** sets `closing`, closes both streams, interrupts the reader if needed, fails pending
  futures. `NeovimConnection` owns the process and ends it.
- **Logging:** `java.util.logging`, like the rest of the plug-in. At FINEST every message in and
  out is traced, the way to study the traffic: `-Dneovim4eclipse.logLevel=FINEST`
  ([LOG_LEVEL.md](LOG_LEVEL.md)).

## 4. Opening the connection

`NeovimConnection.open` starts the process, builds an `RpcChannel` over its streams and a
`MsgpackNeovimApi`, then:

- sends `nvim_set_client_info` (name `neovim4eclipse`, type `remote`, a version map with
  `prerelease`, built by `clientVersion`);
- attaches a UI with `nvim_ui_attach`, 120 × 40, options `NeovimConnection.TERMINAL_UI` (all false;
  `rgb` must be sent, since it defaults to true). The UI is required: without it Neovim does not
  process `nvim_input` reliably under `--embed`.
  `NeovimSessionIntegrationTest.neovimKnowsTheClientAndATerminalUi` pins what Neovim records about
  us.

Neovim's stderr is a separate stream, not merged into stdout: stdout carries MessagePack, and stray
output would corrupt it. A small daemon thread logs each stderr line at WARNING.

The channel's `Incoming` is a small forwarder, so `NotificationBridge` can be connected with
`setIncoming` after `open`; until then notifications are dropped and requests answered with an
error. `NotificationBridge` implements `Incoming`, dispatching by name with a `switch`. Unknown
notification names (`redraw`, `nvim_buf_changedtick_event`, ...) are ignored and logged at FINEST;
a notification handler that throws is logged at WARNING; a request whose handler throws is answered
with an error.

`requestQuit` calls `channel.closing()` and then `channel.notify("nvim_command", "qa!")`.

The JDK's `sun.misc.Unsafe` warning at start-up comes from msgpack-core itself (its
`MessageBuffer`); msgpack-core 0.9.12 still prints it on JDK 25.

Found while comparing the clients: `Mode.blocking` is true after `f` (Neovim waits inside the
command), not after `d` (an operator returns to the main loop as mode `no`).

## 5. Tests

In `tests/unit`, no Neovim needed:

- `MsgpackValuesTest`: round trips of every type; handles; integers too large for `int`; strings
  that are not valid UTF-8. `HandleTest` covers `Handle`.
- `RpcChannelTest` plays Neovim over two `java.nio` pipes (not `PipedInputStream`, which checks
  which threads are alive), reading what the channel writes and writing MessagePack back with
  msgpack-core: response matched by id when two requests are answered in reverse order; error
  response fails the future with `E…` text; notification and request dispatch in order; a request
  whose handler throws gets an error answer; Neovim's request arriving while our request waits; the
  end of input after `closing()` logs nothing at WARNING (`ExpectedLog.assertNothingAtOrAbove`) and
  fails pending futures; without it, logs once.
- `NeovimApiTest`: each method sends the right function name and parameters (it also covers
  `bufAttach`'s `sendBuffer`, which no other test catches: a reattach after a reload passes `true`
  unless Eclipse is typing, `NeovimSession.reattachAfterReloadAsync`).

With a real Neovim (`-Pintegration`):

- `NeovimApiIntegrationTest` runs the `NeovimApi` methods (all but `uiTryResize`) against `nvim --embed`, connected the way
  the plug-in connects. It began as a contract test run through both neovim-java and our client
  (section 7), and still compares numbers by value (neovim-java gave `Integer`).
- `NeovimSessionIntegrationTest`: the client info and UI above; a normal close logs no warning; a
  line on Neovim's stderr is logged and the session goes on.
- `NeovimBufferIntegrationTest`, `ClipboardIntegrationTest` and the UI tests run unchanged against
  a real Neovim.

## 6. Why not neovim-java

neovim-java needed four workarounds in the plug-in:

| Workaround | Why it existed |
|---|---|
| `QuittingConnection` (a stream wrapper with its own test) | neovim-java logs the end of Neovim's output at ERROR with a stack trace ("Listening to messages failed!"), so every normal shutdown printed an error. The wrapper turned the expected EOF into an unchecked exception that ended the reader thread silently. `RpcChannel.closing()` replaces it. |
| A per-connection `ExecutorService` | `RpcClient`'s default pool is static: `rpcClient.stop()` shuts it down for the whole JVM, and every later session fails to open. The reactive wrapper blocks on `ForkJoinPool.commonPool()`, shared with the whole IDE. |
| A raw request callback instead of `@NeovimRequestHandler` | In 0.5.0 (and still on neovim-java's `main` branch) `NeovimHandlerProxy` stores request handlers in one map (`requestCallbacks`) and looks them up in another (`requestHandlers`), so every request is answered "No handler found". |
| The `org.slf4j.simpleLogger.defaultLogLevel` system property, set in a static block | slf4j-simple logs every RPC message at INFO, several lines per key. It had to be a JVM-wide system property set before the first neovim-java class loads, because slf4j-simple cannot find a properties file through OSGi's class loaders, so it also affected any other bundle using slf4j-simple. |

neovim-java also routed notifications to annotated handler methods found by reflection (so they
had to be `public`), and delivered buffer handles either as its own `BaseCustomIdType` or as
Jackson's raw `MessagePackExtensionType`, so `core/` had to import `jackson-dataformat-msgpack`.

Its state, checked in October 2026: the project moved from GitHub (`esensar/neovim-java`, now a
mirror) to Codeberg; 0.5.0 (May 2023) is the latest release; three commits since, none to the code;
open issues saying the API functions of Neovim API levels 10–12 (Neovim 0.10 and later) are not
implemented; the request-handler bug still on `main`; its own dependencies Jackson 2.13.3, msgpack
0.9.1, SLF4J 1.7.36. Not archived, but not developed. The embedded Jackson 2.14 also drew dependency
scanners' warnings (CVE-2025-52999 for jackson-core before 2.15, a stack overflow on deeply nested
input; low practical risk, since the plug-in only parses its own Neovim child's output).

## 7. Alternatives, and how the switch was made

| Option | Jars in `lib/` | Workarounds left | Why not |
|---|---|---|---|
| **A. Own client on msgpack-core** (chosen) | 1 (msgpack-core) | none | — |
| **B. Stay, upgrade Jackson, msgpack, SLF4J** | 12 | all four | Fixes the version age and the CVE warning, not the request handlers, the static pool, the shutdown logging or the SLF4J property; must be repeated for every Jackson release; neovim-java on Jackson 2.22 is a combination nobody upstream tests. |
| **C. Fork neovim-java** and fix the handler bug | 12 | some | We would maintain a multi-module library, with Jackson, whose typed API we mostly do not use. |
| **D. Another JVM client** | — | — | None found: no other maintained Java or Kotlin client exists; neovim-java is the only one of any size. |

The switch used the "strangler" pattern: first an interface of our own between the plug-in and
neovim-java, implemented by neovim-java, then a second implementation, compared with the first,
then the swap. Each step kept `./mvnw verify -Pui` and `-Pintegration` green, and each was
reversible on its own.

1. **Own value types:** `core/rpc/Mode` in place of neovim-java's `VimMode` in `StateSync`,
   `KeyInput` and their tests.
2. **Own API interface:** `NeovimApi`, `Handle` and `WinCursor`, first implemented by an adapter
   over neovim-java.
3. **Plain handlers:** `NotificationBridge` implements `Incoming` instead of annotated methods.
4. **`MsgpackValues`**, with its tests.
5. **`RpcChannel`**, with its tests.
6. **`MsgpackNeovimApi`**, with `NeovimApiTest`, and a contract test running the same checks
   against a real Neovim through both implementations (both agreed). It found that our client must
   send `nvim_ui_attach` (section 4).
7. **The switch:** `setClientInfo` and `uiAttach` joined `NeovimApi`, with
   `neovimKnowsTheClientAndATerminalUi` pinning what Neovim recorded about the neovim-java client
   first (7a); then `NeovimConnection` moved to `RpcChannel`, Neovim's stderr was split from
   stdout, and `QuittingConnection`, the adapter, the executor and the SLF4J block were deleted
   (7b). The contract test kept its own-client half as `NeovimApiIntegrationTest`.
8. **The jars removed:** from the root `pom.xml`, the plug-in's copy step, `MANIFEST.MF`'s
   `Bundle-ClassPath`, the test POMs, `about.html`, `licenses/`, `ACKNOWLEDGEMENTS.md` and
   Dependabot's `rpc-libraries` group (msgpack-core gets ordinary pull requests).
9. **Cursor calls on window 0** (`Handle.CURRENT_WINDOW`), a behavior change kept out of the
   refactoring steps on purpose.
