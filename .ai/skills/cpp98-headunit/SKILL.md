---
name: cpp98-headunit
description: Writing or changing the C++98 head-unit plugin DrivelogBridge (headunit/src, headunit/tests, Makefile, toolchain) that runs inside the car's HMI on NBT Evo (QNX 6.5) and EntryNav2 (Linux ARMv7hf) and serves CDS values over TCP.
---

# C++98 head-unit plugin (headunit/)

`libDrivelogBridge.so` is loaded **into the car's HMI process**. A crash, a deadlock or a blocked thread takes down
the screen, radio and navigation. Every rule below exists because of that.

Files: `src/drivelog_bridge.cpp` (framework glue, threads, sockets, logging), `src/bridge_core.hpp` (pure logic:
property list `kProperties`, JSON lines, config parsing; host-testable), `tests/host_test.cpp` +
`tests/fake_framework.*` (the plugin run under qemu against a fake framework), `make_db.py` (texts/images zips).

## Hard limits

- **C++98 only.** QNX GCC 4.4 and EntryNav2 GCC 4.6: no `auto`, lambdas, `nullptr`, range-for, `override`,
  `std::thread`, `<atomic>`, `<chrono>`, `std::unique_ptr`, variadic templates, `>>` in nested templates without a
  space. `-std=gnu++98 -Wall -Wextra` must stay warning-free.
- **Read only.** Never call `cdsSetPropertyAsync` or anything else that changes the car.
- **No blocking** in CDS callbacks or on the HMI thread: copy the value into the queue under a short lock and return.
- **Non-blocking sockets** (`O_NONBLOCK`) in the network thread, with `select`/`poll` timeouts; no blocking
  `connect`, `send`, `recv` or DNS.
- Keep the **crash guard** and the **`app.disable`** kill switch (`kDisableA`/`kDisableB`) working.
- Every new framework symbol must be exported by **both** units' `libAppFramework.A.so`. Only `namespace bmw` /
  `bmw::` may name the car maker (mangled names must match).
- The result needs only `GLIBC_2.4` symbols (the Makefile checks).
- No personal data (GPS, contacts, VIN, addresses) without the maintainer's explicit decision.
- Never commit the car's `*.A.so` files or `build/`.

## Good practices (follow them in new code even where older code does not)

### Memory and strings
- Fixed-size buffers with `snprintf(buf, sizeof(buf), ...)`; check the return value for truncation where the
  result matters (paths, JSON lines). Never `sprintf`, `strcpy`, `strcat`, `gets`.
- Bound every `%s` from outside data with a precision (`%.200s`).
- No unbounded growth: queues, client lists and logs have a fixed maximum and drop the oldest.
- Avoid heap allocation in callbacks and hot paths; allocate at start-up.
- Initialise every variable and every struct (`memset` or a constructor). Use `static_cast` / `reinterpret_cast`,
  never C casts in new code.
- `const` everything that does not change; `const char* const` for tables; mark single-argument constructors `explicit`.

### Resources (RAII without C++11)
- Wrap file descriptors, `DIR*`, `FILE*`, `ifaddrs` lists and mutex locks in small RAII classes (a `ScopedLock` with a
  private copy constructor and assignment) so early returns cannot leak or leave a mutex locked.
- `close()` every socket on every error path; set `FD_CLOEXEC` on new descriptors.

### Threads
- Only `pthread`. Shared state behind `pthread_mutex_t`, locks held for a few instructions, never across I/O or
  a framework call. One lock order, documented, if two are ever needed.
- Flags read by another thread: use the mutex, or GCC's `__sync_*` builtins (available in GCC 4.4). `volatile` alone
  is not synchronisation.
- Threads are joined (or told to stop and detached) when the plugin stops. No thread outlives the plugin.

### Network
- Handle `EINTR`, `EAGAIN`/`EWOULDBLOCK` and partial `send`s; drop a client whose buffer fills instead of waiting.
- Avoid `SIGPIPE`: `MSG_NOSIGNAL` where available, otherwise `SO_NOSIGPIPE` / ignore it for the socket. Never change
  the HMI's global signal handlers beyond what the crash guard needs.
- Check every return value of `socket`, `bind`, `listen`, `accept`, `fcntl`, `setsockopt`.
- Keep both directions: the phone calls the head unit on 7777, and the head unit calls the phone on 7778 (wireless
  Android Auto hides the car's Wi-Fi from other apps).

### Errors and logging
- No exception may escape into the framework: framework entry points catch `(...)` and log. Don't rely on RTTI.
- Log state changes once, not every loop (see `noteCallResult`): the log lives on a USB stick or flash.
- Log lines are short, ASCII, with a time stamp; never log personal data.

### Structure and tests
- Pure logic (parsing, formatting, config) goes into `bridge_core.hpp` without framework or socket calls, so the
  host test covers it.
- A new CDS property: add it to `kProperties` (`bridge_core.hpp`) **and** `CdsProperty` (`plugins/id6/Cds.kt`),
  a stub in `kStubs` (one per property, a compile-time check counts them), parsing in `CdsParser`, the host test's
  expectations (`props`, value counts) and the screen texts in `make_db.py` (then `make db`).
- Each fix gets a check in `tests/host_test.cpp`; under qemu keep test data in the container's `/tmp`
  (32-bit `stat()` on a Windows bind mount fails with `EOVERFLOW`).

### SOLID within C++98 (where it fits)

The plugin is one translation unit plus a header, and simplicity beats abstraction inside the car's HMI. Apply
SOLID to keep parts testable, not to build class hierarchies.

- **S - single responsibility.** Keep separate parts apart, as functions or small classes with one job each:
  framework glue (CDS callbacks, `PluginApp`), the value queue, the network loop (server and callbacks to the phone),
  config, logging, and the pure formatting/parsing in `bridge_core.hpp`. A CDS callback only stores the value; it
  does not format JSON or touch sockets.
- **O - open/closed.** Driven by tables: a new property is a new line in `kProperties` (and its stub), not new
  code paths. Keep it that way for other lists (USB roots, disable files, text ids).
- **L - Liskov substitution.** If you derive from a framework class, the derived class must behave as the HMI expects
  from the base (same calling thread, no blocking, no throwing). Prefer composition over inheritance from framework
  types; inherit only where the framework requires it.
- **I - interface segregation.** Functions take the few values they need (`const char*` and a size, an `int fd`),
  not the global state struct. Headers expose only what the other file uses.
- **D - dependency inversion.** The core logic depends on small seams, not on the framework or the OS: time,
  socket operations and the framework entry points go through a thin layer that the host test replaces
  (`tests/fake_framework.*`). In C++98 that is a struct of function pointers or an abstract class with virtual
  functions and a protected destructor. Use it only where a test needs it; virtual calls in callbacks are fine, heap
  allocation is not.

### Files
- `*.sh`, `*.py`, `Makefile` use LF; `*.bat`, `*.ps1` use CRLF (`.gitattributes`). A CR breaks scripts on the head
  unit and in Docker.

## Verify (always after a headunit/ change)

```bash
bash headunit/toolchain/check.sh
```

EntryNav2 package: `headunit/build_entryNav2.bat` (needs the car's libs in `headunit/lib/entrynav2/`).
NBT Evo needs QNX SDP 6.5 (`headunit\build_nbt.bat dist`), which is not on this PC: say so when a change was not
built for NBT, and that nothing is verified in a real car until it has been tried there.
