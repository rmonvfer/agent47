#!/usr/bin/env python3

import errno
import fcntl
import os
import pty
import select
import signal
import struct
import sys
import tempfile
import termios
import time


BOOT_TIMEOUT_SECONDS = 3.0
TERMINAL_COLUMNS = 80
TERMINAL_ROWS = 24
FAILURE_MARKERS = (
    b"Exception in thread",
    b"UnsatisfiedLinkError",
    b"WARNING: A restricted method",
)


def wait_for_process(pid: int) -> int | None:
    try:
        waited_pid, status = os.waitpid(pid, os.WNOHANG)
    except ChildProcessError:
        return 0
    if waited_pid == 0:
        return None
    return os.waitstatus_to_exitcode(status)


def stop_process(pid: int) -> None:
    if wait_for_process(pid) is not None:
        return

    os.kill(pid, signal.SIGTERM)
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        if wait_for_process(pid) is not None:
            return
        time.sleep(0.05)

    os.kill(pid, signal.SIGKILL)
    try:
        os.waitpid(pid, 0)
    except ChildProcessError:
        pass


def read_available(master_fd: int, output: bytearray) -> None:
    while select.select([master_fd], [], [], 0)[0]:
        try:
            chunk = os.read(master_fd, 65536)
        except OSError as error:
            if error.errno == errno.EIO:
                return
            raise
        if not chunk:
            return
        output.extend(chunk)


def set_terminal_size(fd: int) -> None:
    window_size = struct.pack("HHHH", TERMINAL_ROWS, TERMINAL_COLUMNS, 0, 0)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, window_size)


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <agent47-binary>", file=sys.stderr)
        return 2

    binary = os.path.abspath(sys.argv[1])
    if not os.path.isfile(binary) or not os.access(binary, os.X_OK):
        print(f"error: binary is not executable: {binary}", file=sys.stderr)
        return 2

    output = bytearray()
    with tempfile.TemporaryDirectory(prefix="agent47-interactive-smoke-") as home:
        environment = {
            "AGENT47_DIR": os.path.join(home, ".agent47"),
            "ANTHROPIC_API_KEY": "smoke-test",
            "HOME": home,
            "LANG": "C.UTF-8",
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "TERM": "xterm-256color",
        }
        pid, master_fd = pty.fork()
        if pid == 0:
            set_terminal_size(sys.stdin.fileno())
            os.execve(binary, [binary], environment)

        try:
            return_code = None
            deadline = time.monotonic() + BOOT_TIMEOUT_SECONDS
            while time.monotonic() < deadline and return_code is None:
                select.select([master_fd], [], [], 0.1)
                read_available(master_fd, output)
                return_code = wait_for_process(pid)
            read_available(master_fd, output)

            failure = next((marker for marker in FAILURE_MARKERS if marker in output), None)
            if failure is not None or return_code is not None:
                sys.stderr.buffer.write(output)
                if failure is not None:
                    print(
                        f"error: interactive boot emitted {failure.decode()}",
                        file=sys.stderr,
                    )
                else:
                    print(
                        f"error: interactive process exited with {return_code}",
                        file=sys.stderr,
                    )
                return 1

            print("Interactive terminal boot remained healthy for 3 seconds.")
            return 0
        finally:
            os.close(master_fd)
            stop_process(pid)


if __name__ == "__main__":
    raise SystemExit(main())
