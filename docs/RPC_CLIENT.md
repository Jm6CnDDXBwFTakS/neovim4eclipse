# Design note: our own msgpack-rpc client instead of neovim-java

Status: **done** (option A, steps 1–9). The plug-in runs on its own msgpack-rpc client and embeds msgpack-core only.
Written 2026-10-19 against the `develop` branch (neovim-java 0.5.0, msgpack 0.9.3, Jackson 2.14.2,
SLF4J 1.7.36).

## Summary

The plug-in talks to `nvim --embed` through [neovim-java](https://codeberg.org/neovim-java/neovim-java)
(`com.ensarsarajcic.neovim.java`, five jars). neovim-java in turn pulls in Jackson (three jars),
`jackson-dataformat-msgpack` and SLF4J (two jars). Of those twelve embedded jars the plug-in itself
needs only one: **msgpack-core**, the standard MessagePack library for Java. The plug-in imports no
Jackson and logs with `java.util.logging`.

neovim-java has had no release since May 2023, its request handlers do not work, and the plug-in
already carries four workarounds for it (listed below). The protocol it wraps,
[msgpack-rpc](https://github.com/msgpack-rpc/msgpack-rpc/blob/master/spec.md), has four message
shapes and fits in a page.

**Recommendation:** replace neovim-java with a small client of our own in `core/`, built on
msgpack-core only, in the eight small commits described under "Migration path". It removes eleven
jars (about 2.4 MB of the 2.5 MB in `lib/`), all four workarounds (the SLF4J configuration among them). The
new code is about 550 lines, comments included. Because this is a learning project, it also fits
the project's aim: the RPC layer becomes code a reader can study instead of a black box. If the
maintainer would rather not own RPC code, the fallback is to stay on neovim-java and upgrade
Jackson and msgpack (see "Alternatives").

## 1. What the plug-in uses from neovim-java today

All uses are in `core/` (found with `grep -rn com.ensarsarajcic plugins/*/src`), plus four unit
tests. They fall into four groups.

### 1.1 RPC transport

| Class (neovim-java) | Used in | For |
|---|---|---|
| `ProcessRpcConnection` | `NeovimConnection.open` | the process's stdin/stdout as an `RpcConnection` |
| `RpcConnection` | `QuittingConnection` | wrapped, see 1.4 |
| `RpcClient` (+ `Builder`, `ObjectMappers.defaultNeovimMapper`) | `NeovimConnection` | encodes and decodes messages with Jackson; matches responses to requests; reads on a background thread |
| `ReactiveRpcClient`, `ReactiveRpcStreamer`, `ReactiveRpcStreamerWrapper` | `NeovimConnection` | turns each request into a `CompletableFuture` |
| `NotificationMessage.Builder`, `RpcClient.send` | `NeovimConnection.requestQuit` | sends `nvim_command("qa!")` as a notification |

### 1.2 Typed API calls

`NeovimStreamApi` implements `NeovimApi`, `NeovimBufferApi` and `NeovimWindowApi`; each method
sends one request and returns a `CompletableFuture`. The plug-in calls 18 distinct API functions
(16 through `core/rpc/NeovimApi` since step 2, plus the two `NeovimConnection` makes while opening):

| Call | Neovim function | Where |
|---|---|---|
| `api.executeLua` (15 call sites) | `nvim_exec_lua` | `NeovimSession`, `DocumentBuffers` |
| `api.setCurrentBuffer` (4) | `nvim_set_current_buf` | `DocumentBuffers`, `NeovimSession` |
| `api.input` (3) | `nvim_input` | `NeovimSession` (key input) |
| `api.getMode` (2) | `nvim_get_mode` | `NeovimSession` for `StateSync` and `KeyInput` |
| `api.getCurrentWindow` (2) | `nvim_get_current_win` | `NeovimSession` (cursor) |
| `api.createBuffer` (2) | `nvim_create_buf` | `NeovimSession`, `DocumentBuffers` |
| `api.executeCommand` | `nvim_command` | `NeovimSession` |
| `api.setVariable` | `nvim_set_var` | `NeovimSession` |
| `api.getChannelInfo` | `nvim_get_chan_info` | `NeovimSession` (channel for `vim.rpcrequest`) |
| `api.setClientInfo` | `nvim_set_client_info` | `NeovimConnection` |
| `api.attachUi` | `nvim_ui_attach` | `NeovimConnection` |
| `buffer.attach` (2) | `nvim_buf_attach` | `NeovimSession`, `DocumentBuffers` |
| `buffer.setName` | `nvim_buf_set_name` | `DocumentBuffers` |
| `buffer.delete` | `nvim_buf_delete` | `DocumentBuffers` |
| `buffer.getLines` / `setLines` (2 each) | `nvim_buf_get_lines` / `nvim_buf_set_lines` | `NeovimSession` |
| `buffer.get().getId()` | (the handle's number, no call) | `DocumentBuffers.bufnr` |
| `window.getCursor` / `setCursor` | `nvim_win_get_cursor` / `nvim_win_set_cursor` | `NeovimSession` |

Value types from `api.types`: `VimMode` (also in `StateSync`, `KeyInput` and their unit tests),
`VimCoords`, `DeleteBufferOptions`, `UiOptions`, `ClientType`, `ClientAttributes`,
`ClientVersionInfo`, and the extension types `Buffer` / `BaseCustomIdType`.

Most of the logic already bypasses the typed API: 15 of the roughly 40 calls are `nvim_exec_lua`
with a script from `LuaScripts`, whose results the plug-in parses itself from plain lists and maps
(`StateSnapshot`, `NeovimSessionParsingTest`). The typed layer adds little.

### 1.3 Notifications and requests from Neovim

- `NeovimHandlerManager` and `NeovimHandlerProxy` route notifications by name to the
  `@NeovimNotificationHandler` methods of `NotificationBridge`, found by reflection (so they must be
  `public`). Seven names: `eclipse_mode`, `eclipse_cursor`, `eclipse_buf_enter`, `eclipse_yank`,
  `eclipse_action`, `eclipse_clipboard_copy` and Neovim's own `nvim_buf_lines_event`.
- Requests (`vim.rpcrequest`): `eclipse_clipboard_paste` and `eclipse_write`. They reach
  `NotificationBridge.onRequest` through `NeovimConnection.setRequestHandler`, which registers a
  raw `RpcClient` request callback and builds the `ResponseMessage` / `RpcError` itself.
- `NotificationBridge.bufferNumber` decodes buffer handles that arrive either as neovim-java's
  `BaseCustomIdType` or as Jackson's raw `MessagePackExtensionType` (so `core/` imports
  `org.msgpack.jackson.dataformat`, from `jackson-dataformat-msgpack`).

### 1.4 Workarounds for the library

| Workaround | Why it exists |
|---|---|
| `QuittingConnection` (100 lines + `QuittingConnectionTest`) | neovim-java logs the end of Neovim's output at ERROR with a stack trace ("Listening to messages failed!"), so every normal shutdown printed an error. The wrapper turns the expected EOF into an unchecked exception that ends the reader thread silently. |
| The per-connection `ExecutorService` in `NeovimConnection.open` | `RpcClient`'s default pool is static: `rpcClient.stop()` shuts it down for the whole JVM, and every later session fails to open. The reactive wrapper blocks on `ForkJoinPool.commonPool()`, shared with the whole IDE. |
| `setRequestHandler` instead of `@NeovimRequestHandler` (since step 3: `setIncoming`, for both kinds) | In 0.5.0 (and still on neovim-java's `main` branch) `NeovimHandlerProxy` stores request handlers in one map (`requestCallbacks`) and looks them up in another (`requestHandlers`), so every request is answered "No handler found". `addNotificationHandler` therefore attaches the proxy for notifications only. |
| The `org.slf4j.simpleLogger.defaultLogLevel` system property set in a static block of `NeovimConnection` | slf4j-simple logs every RPC message at INFO, several lines per key. It must be a JVM-wide system property set before the first neovim-java class loads, because slf4j-simple cannot find a properties file through OSGi's class loaders. Setting a JVM-wide property also affects any other bundle that happens to use slf4j-simple. |

## 2. The replacement

### 2.1 The protocol

Every message is one MessagePack array:

```text
request       [0, msgid, method, params]     expects exactly one response with the same msgid
response      [1, msgid, error, result]      error is nil, or Neovim's [type, message]
notification  [2, method, params]            no answer
```

Both sides send all three. Neovim answers our requests (`nvim_input`, ...), and we answer Neovim's
(`vim.rpcrequest`). Responses may arrive in any order relative to notifications, and Neovim may send
us a request while we wait for its response to ours (`:w` sent as `nvim_command` triggers
`eclipse_write`).

Buffers, windows and tab pages travel as MessagePack *extension* values whose payload is the
handle's number, itself msgpack-encoded. Their type codes come from `nvim_get_api_info()` and have
been `0`, `1`, `2` since Neovim 0.1.

### 2.2 The classes

A new package `core/rpc` (no `org.eclipse` imports, so it is compiled and tested in `tests/unit`).
Sizes are estimates including Javadoc in the project's explanatory style.

| Class | Lines | Responsibility |
|---|---|---|
| `MsgpackValues` | ~120 | Java ↔ MessagePack for the values the API uses: `null`, `Boolean`, `Long`/`Integer`, `Double`, `String`, `List`, `Map`, `Handle`. Decodes integers as `Long`, maps as `LinkedHashMap`, extension types 0/1/2 as `Handle`. |
| `Handle` | ~30 | `record Handle(Kind kind, long id)` with `Kind { BUFFER, WINDOW, TABPAGE }`; replaces `Buffer`, `BaseCustomIdType`, `MessagePackExtensionType`. |
| `NeovimErrorException` | ~20 | A `util/NeovimExceptionBase` (unchecked) carrying Neovim's error type and message, so `Rpc.errorMessage` still finds `E94: ...` in it. |
| `RpcChannel` | ~220 | The connection: a reader thread, request ids, pending responses, writing, dispatching Neovim's notifications and requests, closing. Details below. |
| `NeovimApi` (ours) | ~150 | The functions of 1.2 as thin typed methods, each one line over `RpcChannel.request`, using `Mode(String mode, boolean blocking)` (step 1) and `WinCursor(int row, int col)` (step 2). Only the functions we use; adding one is a line. |

About 550 lines in total, around 300 of them code. In return `QuittingConnection` (100 lines), the
executor and SLF4J setup in `NeovimConnection` (about 40 lines) and `QuittingConnectionTest` go away.

### 2.3 `RpcChannel` in detail

```java
final class RpcChannel implements AutoCloseable {
    // core/rpc/Incoming (since step 3): notification(method, params), request(method, params)
    RpcChannel(InputStream in, OutputStream out, Incoming incoming, String threadName);
    CompletableFuture<Object> request(String method, Object... params);
    void notify(String method, Object... params) throws IOException;
    void closing();          // from now on the end of the input is expected (was QuittingConnection)
    void close();
}
```

- **Request ids:** an `AtomicInteger`; the future goes into a `ConcurrentHashMap<Integer,
  CompletableFuture<Object>>` *before* the message is written, so a fast answer cannot arrive
  first.
- **Writing:** one `MessagePacker` over the process's stdin, used under a lock, flushed after each
  message. Any thread may call `request` or `notify`.
- **Reading:** one daemon thread per channel (`eclipse-neovim-rpc`), no pool. For each message it
  - completes the matching future (`complete(result)`, or `completeExceptionally(new
    NeovimErrorException(...))`); an unknown id is logged at FINE and ignored;
  - calls `incoming.notification(...)`;
  - calls `incoming.request(...)` and writes exactly one response: the result, or `[0, message]`
    when it threw.

  Notifications and requests are handled on the reader thread, in arrival order, exactly as
  neovim-java does today. `ARCHITECTURE.md` relies on that (the clipboard paste waits up to 2 s for
  the UI thread on this thread, while no other message is read). Futures are also completed on the
  reader thread; nothing in the plug-in chains `thenApply`/`whenComplete` on them (checked with
  grep), callers only `Rpc.await` on the input thread. A comment on `request` will say that
  continuations must not block.
- **End of input:** after `closing()` an EOF or read error ends the thread silently. Before it, it
  is logged once at WARNING ("Neovim exited unexpectedly"). Either way every pending future fails
  with an `IOException("Neovim connection closed")`, so no caller waits for its full timeout.
- **Close:** sets `closing`, closes both streams, interrupts the reader if needed, fails pending
  futures. `NeovimConnection` still owns the process and ends it as today.
- **Logging:** `java.util.logging`, like the rest of the plug-in. Message tracing at FINEST
  replaces `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug` for studying the traffic.

### 2.4 How it is tested

All in `tests/unit`, no Neovim needed, over `PipedInputStream`/`PipedOutputStream` pairs: the test
plays Neovim by reading what the channel writes and writing MessagePack back with msgpack-core.

- `MsgpackValuesTest`: round trips of every type; handles; integers too large for `int`; strings
  that are not valid UTF-8 (decoded with the replacement character, as msgpack-core's default).
- `RpcChannelTest`: response matched by id when two requests are answered in reverse order; error
  response fails the future with `E…` text; notification and request dispatch in order; a request
  whose handler throws gets an error answer; Neovim's request arriving while our request waits;
  EOF after `closing()` logs nothing and fails pending futures; EOF without it logs once.
- `NeovimApiTest`: each method sends the right function name and parameters.
- The existing integration tests (`-Pintegration`: `NeovimSessionIntegrationTest`,
  `NeovimBufferIntegrationTest`, `ClipboardIntegrationTest`) and the UI tests run against a real
  Neovim unchanged; they are the proof that the switch changed nothing.

## 3. Migration path

The idea is the "strangler" pattern: first put an interface of our own between the plug-in and
neovim-java, implemented by neovim-java, then write a second implementation and swap. Each commit
keeps `./mvnw verify -Pui` and `./mvnw test -f tests/unit/pom.xml -Pintegration` green.

1. **Own value types.** *Done.* Replace `VimMode` with `core/rpc/Mode` in `StateSync`, `KeyInput`
   and their tests; `NeovimSession` converts from neovim-java's type at the one place it calls
   `nvim_get_mode`. Pure refactoring. `VimCoords` needed no new type: it was already used only
   inside `NeovimSession`, which converts it to the existing `core/CursorPos`; it goes away with
   step 2.
2. **Own API interface.** *Done.* Introduce `core/rpc/NeovimApi` and `NeovimJavaApi`, an adapter
   that implements it with neovim-java. Move `NeovimSession` and `DocumentBuffers` to the interface;
   `DocumentBuffers.bufnr` becomes `handle.intId()`. As built:
   - The interface is flat and uses Neovim's names without `nvim_` (`bufSetLines(Handle, ...)` is
     `nvim_buf_set_lines`), 16 methods. `nvim_get_chan_info` is `getChannelId()`, the one field read.
   - `Handle` (planned for step 4) came here, because the interface passes buffers and windows as
     handles. `HandleTest` covers it.
   - `WinCursor(int row, int col)` is `nvim_win_get_cursor`'s answer. `NeovimSession` converts it to
     `core/CursorPos`, the listeners' type, so `core/rpc` does not depend on `core` (which depends
     on it).
   - `nvim_set_client_info` and `nvim_ui_attach` stay in `NeovimConnection` on neovim-java's API
     (its option types) until step 7.
   - The adapter converts answers with `thenApply`, which runs on the RPC thread; the conversions
     do not block.
   - Not caught by any test: `bufAttach`'s `sendBuffer` (only the home buffer passes `true`, and it
     is empty then). `NeovimApiTest` in step 6 checks the parameters sent.

   After this commit `com.ensarsarajcic` is imported only by `NeovimConnection`, the adapter,
   `NotificationBridge` (step 3) and `QuittingConnection` (step 7).
3. **Plain notification and request handlers.** *Done.* `NotificationBridge` implements
   `core/rpc/Incoming` (`notification(String, List<?>)` dispatching by name with a `switch`,
   `request(String, List<?>)`) instead of annotated methods taking
   `NotificationMessage`/`RequestMessage`. `NeovimConnection.setIncoming` registers one raw
   notification callback and the request callback that call them. Drops the annotation scanning
   and the need for `public` handler methods. `bufferNumber` still accepts both extension forms here.
   As built:
   - `Incoming` is in `core/rpc` already, not nested in `RpcChannel`, so step 5 uses it unchanged.
   - `NotificationBridge` and its `Sink` are package-private now.
   - Unknown notification names (`redraw`, `nvim_buf_changedtick_event`, ...) are ignored and
     logged at FINEST, as neovim-java's proxy ignored them. A notification handler that throws is
     logged at WARNING in `setIncoming`; a request that throws is answered with an error, as before.
   - `NeovimHandlerManager` and `NeovimHandlerProxy` are no longer used; their jars
     (`handler-annotations`, `neovim-notifications`) go in step 8 with the others.
4. **`MsgpackValues`** with its unit tests (`Handle` exists since step 2). *Done.* Not used yet.
   As built:
   - Static `pack(MessagePacker, Object)` and `unpack(MessageUnpacker)`, streaming, as `RpcChannel`
     needs them; the class comment has the type table.
   - Integers always decode as `Long`; there is no `BigInteger`, since Neovim's Integer is signed
     64-bit and a larger uint64 fails as a protocol error. MessagePack `bin` decodes as `byte[]`.
   - An extension type other than 0, 1 or 2 is an `IOException`. A Java type with no MessagePack
     form is an `IllegalArgumentException`, possibly after part of a list was written, so step 5
     packs each message into a buffer before sending it.
   - msgpack-core reports truncated input (also a stream that ends, when Neovim exits) with
     unchecked `MessagePackException`s, not `IOException`, so step 5's reader loop must catch both.
5. **`RpcChannel`** with its unit tests. *Done.* Not used yet. As built:
   - `start()` starts the reader thread, separate from the constructor.
   - A pending request is removed when its future completes in any way, a caller's `cancel`
     included; a late answer is logged at FINE and dropped.
   - Exceptions: every exception class of the plug-in extends the abstract, unchecked
     `util/NeovimExceptionBase`. Neovim's error answer is `NeovimErrorException` (its type and
     message; replaces the planned `NeovimError`); a request that cannot be answered because the
     connection ended fails with `NeovimConnectionClosedException` (not `IOException`), and so does
     `notify` after the end. `QuittingConnection.Quit` extends the base too until step 7.
   - Messages are packed into a buffer and then written whole under a lock; msgids are uint32 and
     wrap. A reply to Neovim's request that cannot be encoded becomes an error reply.
   - `RpcChannelTest` plays Neovim over two `java.nio` pipes (not `PipedInputStream`, which checks
     which threads are alive). `ExpectedLog` gained `assertNothingAtOrAbove`.
6. **`MsgpackNeovimApi`**, our implementation of the interface over `RpcChannel`, with
   `NeovimApiTest`. Make `NeovimBufferIntegrationTest` (or a new small contract test) run against
   both implementations, so a difference shows up before the switch. *Done.* As built:
   - `MsgpackNeovimApi` takes `RpcRequests`, a one-method interface (`request(method, params)`)
     that `RpcChannel` implements, so `NeovimApiTest` records calls with a lambda: no pipes.
   - An answer of the wrong shape fails with `UnexpectedAnswerException` (extends
     `NeovimExceptionBase`); Neovim's own error passes through as `NeovimErrorException`.
   - `NeovimApiContractTest` (integration) runs 15 checks against a real Neovim through each
     implementation (`@Nested` classes `NeovimJava` and `OwnClient`), comparing answers with
     numbers normalized to `Long` (neovim-java gives `Integer`). Both agree.
   - Found while writing it: our client must also send `nvim_ui_attach`, or Neovim does not process
     `nvim_input` reliably under `--embed` (the "blocking" check fails without it); step 7 adds it
     with `nvim_set_client_info` to `NeovimApi`. And `blocking` is true after `f` (Neovim waits
     inside the command), not after `d` (an operator returns to the main loop as mode `no`).
   - Surefire's `.txt` summary counts the `@Nested` classes' tests as 0; their results are in the
     XML report and the console.
7. Done in two commits (7a, 7b), as decided with the maintainer:
   - **7a** *Done.* `setClientInfo` and `uiAttach` join `NeovimApi` (18 methods), with plain maps
     for their options, still sent through neovim-java. `NeovimConnection.TERMINAL_UI` lists the
     UI options (all false; `rgb` must be sent, it defaults to true) and `clientVersion` builds the
     version map. `NeovimSessionIntegrationTest.neovimKnowsTheClientAndATerminalUi` pins what
     Neovim records about us (probed before the change: name `Eclipse-Neovim`, type `remote`,
     version with `prerelease`, a 120 x 40 UI without `rgb` or `ext_*`), so 7b must keep it.
   - **7b** *Done.* The switch below, as described. Also decided: Neovim's stderr is kept apart from the MessagePack
     stream (today it is merged, so stray output would corrupt it) and logged at WARNING by a
     small daemon thread; `NeovimApiContractTest` keeps only its own-client half, renamed
     `NeovimApiIntegrationTest`. As built: `NeovimConnection` builds the `RpcChannel` with a small
     forwarder as its `Incoming`, so `setIncoming` can come after `open` (until then notifications
     are dropped, as before, and requests answered with an error). New integration tests: a normal
     close logs no warning; a line on Neovim's stderr is logged and the session goes on. The JDK's
     `sun.misc.Unsafe` warning at start-up comes from msgpack-core itself (its `MessageBuffer`)
     and was there before; 0.9.12 still prints it on JDK 25.

   **Switch.** `NeovimConnection.open` builds an `RpcChannel` over the process streams and a
   `MsgpackNeovimApi`; `requestQuit` uses `channel.closing()` and `channel.notify("nvim_command",
   "qa!")`. Delete `QuittingConnection` and its test, `NeovimJavaApi`, the executor and the SLF4J
   static block. `NotificationBridge.bufferNumber` keeps only the `Handle` case. Update
   `NeovimConnection`'s class comment, `LEARNING.md` part 2.3 ("neovim-java" becomes "the RPC
   client", showing the four message shapes and where each is handled) and `ARCHITECTURE.md`
   (layers diagram, section 7's last paragraph, threading table, libraries list).
8. **Remove the jars.** From the root `pom.xml` (properties and `dependencyManagement`), the
   plug-in's `pom.xml` copy step, `MANIFEST.MF` `Bundle-ClassPath`, the `tests/unit` and
   `tests/text` POMs, `about.html`, `licenses/` (`jackson-NOTICE.txt`, `neovim-java-LICENSE.txt`,
   `slf4j-LICENSE.txt`; `Apache-2.0.txt` and the msgpack NOTICE stay), `VersionConsistencyTest` /
   `BundleClassPathConsistencyTest` expectations, `docs/UPGRADING.md` ("Embedded libraries" becomes
   msgpack-core only). Dependabot reads the root `pom.xml` properties, so it stops proposing the
   removed libraries by itself. Delete `plugins/.../lib/` and `target/classes` before building (see AGENTS.md, "Pitfalls"), then install
   the ZIP in a real Eclipse and try typing, `:w`, `"+p` and closing editors.
   *Done.* As built: also `ACKNOWLEDGEMENTS.md`, the Dependabot group `rpc-libraries` (removed with
   its comment; msgpack-core gets ordinary pull requests) and its pull request #6 (closed), LEARNING's
   `Bundle-ClassPath` row, README and ARCHITECTURE. The consistency tests needed no change (they
   compare the lists with each other). `lib/` now holds `msgpack-core.jar` only, and the plug-in jar
   in the ZIP is 416 KB. Still to do by hand: install the ZIP in a real Eclipse and try typing, `:w`,
   `"+p` and closing editors. Decided separately: msgpack-core 0.9.3 to 0.9.12 in its own commit (done right after step 8).
9. **Cursor calls on window 0.** `NeovimSession.getCursor` and `setCursor` first ask
   `nvim_get_current_win` and then pass that window; Neovim's window functions accept `0` for the
   current window, so `winGetCursor(Handle.window(0))` saves one round trip per call. A one-line
   behavior change, kept out of the refactoring steps on purpose. *Done.* As built: the constant
   `Handle.CURRENT_WINDOW`; `getCurrentWin` (`nvim_get_current_win`) had no other caller and left
   `NeovimApi`, which now has 17 methods.

Steps 1–3 are worth doing even if the rest is never done: they confine neovim-java to two classes.

## 4. Risks

| Risk | Mitigation |
|---|---|
| **Decoded values differ** from what Jackson produced (for example `Long` instead of `Integer`, a map class, `byte[]` for MessagePack `bin`). | The parsing code already goes through `Number` and `String.valueOf` (`NotificationBridge.toInt`, `StateSnapshot`); grep found no `instanceof Integer`. `NeovimSessionParsingTest` and the integration tests cover the real payloads. Step 6 compares both implementations. |
| **Deadlock or reordering** from a threading change. | Keep neovim-java's model exactly: one reader thread, handlers on it, in order. A request handler must never wait for a Neovim answer itself (it would wait on its own reader thread); that rule holds today too and is documented in `RpcChannel`. |
| **Shutdown noise or hangs** (the problem `QuittingConnection` solved). | `closing()` is part of the channel's API instead of a stream wrapper; `RpcChannelTest` covers EOF with and without it; pending futures fail at once instead of timing out. |
| **Extension type codes** change in a future Neovim. | They have not changed since 0.1. If it ever matters, read them from `nvim_get_api_info()` at open (one request) instead of hard-coding them. |
| **Big switch breaks something the tests miss** (for example Windows line endings in the pipe, large buffers). | Steps 1–6 keep the switch itself small; integration and UI tests on Linux and Windows (`docs/PLATFORM.md`); a manual try of the ZIP before merging. Reverting step 7 restores neovim-java while steps 1–6 stay. |
| **We own protocol code** that a library maintained for us. | The protocol is stable and tiny, and neovim-java has not been maintained since 2023 anyway (see 5). New API functions are one-line additions; we only add what we call. |
| **Losing the full typed API** (hundreds of methods we might want later). | Most features already go through `nvim_exec_lua`; a new typed method costs one line. |

## 5. neovim-java's maintenance, and the alternatives

### 5.1 State of neovim-java (checked 2026-10-19)

- The project moved from GitHub (`esensar/neovim-java`, now a mirror) to
  [Codeberg](https://codeberg.org/neovim-java/neovim-java).
- **Releases:** 0.5.0 on 2023-05-09 is the latest (Maven Central metadata last updated then). Before
  it: 0.4.0–0.4.6 in May–June 2022.
- **Commits since 0.5.0:** three in three and a half years: a project URL and an e-mail address
  (November 2025), and a Dependabot bump of a test dependency (January 2026).
- **Open issues:** 11, none with recent activity. Four from December 2024 say the API functions of
  Neovim API levels 10–12 (Neovim 0.10 and later) are not implemented yet. No open pull requests.
- **Request-handler bug:** still present on `main` (`NeovimHandlerProxy` puts request handlers in
  `requestCallbacks` and reads them from `requestHandlers`).
- **Its own dependencies** on `main`: Jackson 2.13.3, msgpack 0.9.1, SLF4J 1.7.36.
- Popularity: about 55 stars on the GitHub mirror, 4 on Codeberg.

It is not abandoned in the sense of being archived, but it is not being developed, and nothing
suggests the bug or the missing API levels will be fixed.

### 5.2 Current versions of the transitive libraries

| Library | In `lib/` | Latest (Maven Central, 2026-10-19) | Jar size today |
|---|---|---|---|
| neovim-java (5 jars) | 0.5.0 | 0.5.0 | 271 KB |
| msgpack-core | 0.9.3 | 0.9.12 (a Dependabot branch exists) | 122 KB |
| jackson-dataformat-msgpack | 0.9.3 | 0.9.12 (built against Jackson 2.20) | 30 KB |
| Jackson databind/core/annotations | 2.14.2 | 2.22.x (and Jackson 3, which neovim-java cannot use) | 2,102 KB |
| SLF4J api + simple | 1.7.36 | 2.0.20 (2.1 in alpha; Dependabot ignores major versions, so it does not propose 2.x) | 55 KB |

Jackson 2.14 is nearly four years old; dependency scanners flag jackson-core before 2.15 for
CVE-2025-52999 (a stack overflow on deeply nested input). The plug-in only parses what its own
Neovim child process writes, so the practical risk is low, but the warning will keep showing up.

### 5.3 Options compared

| Option | Effort | Jars in `lib/` | Workarounds left | Learning value |
|---|---|---|---|---|
| **A. Own client on msgpack-core** (recommended) | 8 small commits, ~550 new lines | 1 (122 KB) | none | high: the whole RPC path is readable code in `core/rpc` |
| **B. Stay, upgrade Jackson, msgpack, SLF4J** | 3–4 commits (one per library, per UPGRADING.md); mostly testing | 12 | all four | none |
| **C. Fork neovim-java** and fix the handler bug | small at first, then we maintain a multi-module library whose typed API we mostly do not use, with Jackson | 12 | some | low |
| **D. Another JVM client** | — | — | — | — |

On **B**: Jackson 2.x keeps binary compatibility within the 2.x line, so neovim-java 0.5.0 should
run on Jackson 2.22 and jackson-dataformat-msgpack 0.9.12, but nobody upstream tests that
combination; our integration tests would have to. SLF4J 2.x finds slf4j-simple through
`ServiceLoader`, which works here because both jars are on the same `Bundle-ClassPath`, but that
too needs a check. B fixes the version age and the CVE warning; it does not fix the request
handlers, the static pool, the shutdown logging or the SLF4J property, and it has to be repeated
for every Jackson release.

On **D**: we found no other maintained Java or Kotlin client for Neovim's msgpack-rpc API; neovim-java
is the only one of any size.

### 5.4 Recommendation

Choose **A**, through the migration path in section 3. Do steps 1–3 first; they are useful on their
own and make the decision reversible. If A is declined, do **B**, starting with msgpack 0.9.12
(Dependabot already proposes it) and then Jackson, one commit each.
