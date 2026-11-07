using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// COMPANION-ONLY MODE: the Thor's companion taps and keys into WoW on this PC when another streamer (Apollo, Steam Link, ...) shows
    /// the screen, so Moonlight's own input channel to the bundled Sunshine isn't there. One paired device's input, played with
    /// SendInput: the same ordinary keyboard and mouse events a remote-play host makes for its player.
    ///
    /// Kept deliberately plain (the user: "I don't want to get my wow account banned"):
    ///  - nothing reads, writes or attaches to the WoW process; the game's data reaches the Thor only through the add-on's log;
    ///  - each message is ONE key edge or ONE pointer event the Thor sent for one touch: no macros, sequences or timers here;
    ///  - played only while WoW is the foreground window (one focus attempt, else refused), so nothing lands in another program;
    ///  - Alt, the Windows keys, Menu, every F-key, Print Screen, Pause and the lock keys are never pressed, nor Esc while Ctrl is held
    ///    (the Thor's StreamKeys refuses the same set first);
    ///  - a flood guard drops input beyond what a person typing could make;
    ///  - every key this device pressed is released when its session ends (<see cref="ReleaseAll"/>).
    /// </summary>
    public sealed class WowInput
    {
        /// <summary>the flood guard: a bucket of this many events, refilled at <see cref="PerSecond"/> (typed chat is ~4 events a character)</summary>
        public const double Burst = 400, PerSecond = 200;

        readonly Func<IntPtr> findWindow;
        readonly Func<IntPtr, bool> bringToFront;
        readonly Func<IntPtr, bool> isForeground;
        readonly Action<INPUT[]> send;
        readonly HashSet<int> held = new HashSet<int>();
        readonly Stopwatch clock = Stopwatch.StartNew();
        readonly object gate = new object();
        double tokens = Burst;
        long refilledAt;
        IntPtr hwnd;
        long hwndAt = long.MinValue;

        /// <param name="findWindow">WoW's main window (Zero = not running)</param>
        /// <param name="bringToFront">WoW to the front; true = it is now</param>
        /// <param name="isForeground">null = the real foreground check; the tests pass their own</param>
        /// <param name="send">null = the real SendInput; the tests record instead</param>
        public WowInput(Func<IntPtr> findWindow, Func<IntPtr, bool> bringToFront, Func<IntPtr, bool> isForeground = null, Action<INPUT[]> send = null)
        {
            this.findWindow = findWindow;
            this.bringToFront = bringToFront;
            this.isForeground = isForeground ?? WowWindow.IsForegroundHandle;
            this.send = send ?? (a => SendInput((uint)a.Length, a, Marshal.SizeOf(typeof(INPUT))));
        }

        /// <summary>never pressed on this PC (the Thor's XToVk.systemKey, plus Esc under Ctrl below)</summary>
        public static bool SystemKey(int vk) => vk == 0x12 || vk == 0xA4 || vk == 0xA5 || vk == 0x5B || vk == 0x5C || vk == 0x5D || vk == 0x5F ||
            (vk >= 0x70 && vk <= 0x87) || vk == 0x2C || vk == 0x13 || vk == 0x14 || vk == 0x90 || vk == 0x91;

        /// <summary>one "input" message (docs/STREAM_BRIDGE_PROTOCOL.md section 7); null = played, else why not</summary>
        public string Handle(Dictionary<string, object> m)
        {
            lock (gate)
            {
                var op = m.Str("op");
                bool down = m.Bool("down");
                // a key this device holds may always go up (never a stuck key), wherever the focus is
                if (op == "key" && !down && held.Contains((int)m.Long("vk"))) { Key((int)m.Long("vk"), false); return null; }
                if (!Take()) return "too much input at once: dropped";
                var w = Window();
                if (w == IntPtr.Zero) return "WoW isn't running";
                if (!isForeground(w) && !(bringToFront(w) && isForeground(w))) return "WoW isn't the window in front on the PC";
                switch (op)
                {
                    case "key":
                        int vk = (int)m.Long("vk");
                        if (vk <= 0 || vk > 0xFE || SystemKey(vk)) return "a system key is never sent";
                        if (vk == 0x1B && (held.Contains(0xA2) || held.Contains(0xA3) || held.Contains(0x11))) return "Ctrl+Esc is never sent";
                        Key(vk, down);
                        return null;
                    case "pos":
                        long rw = m.Long("w"), rh = m.Long("h");
                        if (rw <= 0 || rh <= 0) return "bad pointer message";
                        MoveTo(w, Clamp(m.Long("x"), 0, rw - 1), Clamp(m.Long("y"), 0, rh - 1), rw, rh);
                        return null;
                    case "move":
                        Mouse(MOUSEEVENTF_MOVE, (int)Clamp(m.Long("dx"), -4000, 4000), (int)Clamp(m.Long("dy"), -4000, 4000), 0);
                        return null;
                    case "button":
                        bool right = m.Long("b") == 3;
                        Mouse(right ? (down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP) : (down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP), 0, 0, 0);
                        return null;
                    case "scroll":
                        Mouse(MOUSEEVENTF_WHEEL, 0, 0, (int)Clamp(m.Long("amount"), -1200, 1200));
                        return null;
                    default: return "unknown input";
                }
            }
        }

        /// <summary>the session ended: every key this device still holds goes up</summary>
        public void ReleaseAll()
        {
            lock (gate) foreach (var vk in new List<int>(held)) Key(vk, false);
        }

        static long Clamp(long v, long lo, long hi) => v < lo ? lo : v > hi ? hi : v;

        bool Take()
        {
            long now = clock.ElapsedMilliseconds;
            tokens = Math.Min(Burst, tokens + (now - refilledAt) * PerSecond / 1000.0);
            refilledAt = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }

        /// <summary>WoW's window, looked up again every 2 s or when it went away (a process lookup per event would be slow)</summary>
        IntPtr Window()
        {
            long now = clock.ElapsedMilliseconds;
            if (hwnd == IntPtr.Zero || now - hwndAt > 2000 || !IsWindow(hwnd)) { hwnd = findWindow(); hwndAt = now; }
            return hwnd;
        }

        void Key(int vk, bool down)
        {
            if (down) held.Add(vk); else held.Remove(vk);
            ushort scan = (ushort)MapVirtualKey((uint)vk, 0);
            uint flags = (scan != 0 ? KEYEVENTF_SCANCODE : 0u) | (Extended(vk) ? KEYEVENTF_EXTENDEDKEY : 0u) | (down ? 0u : KEYEVENTF_KEYUP);
            var i = new INPUT { type = INPUT_KEYBOARD };
            i.u.ki = new KEYBDINPUT { wVk = (ushort)vk, wScan = scan, dwFlags = flags };
            send(new[] { i });
        }

        static bool Extended(int vk) => (vk >= 0x21 && vk <= 0x28) || vk == 0x2D || vk == 0x2E || vk == 0xA3 || vk == 0xA5;

        /// <summary>the pointer to (x, y) of a rw x rh picture of WoW's client area</summary>
        void MoveTo(IntPtr w, long x, long y, long rw, long rh)
        {
            if (!GetClientRect(w, out RECT r)) return;
            var p = new POINT { X = 0, Y = 0 };
            if (!ClientToScreen(w, ref p)) return;
            double sx = p.X + (x + 0.5) * (r.Right - r.Left) / rw;
            double sy = p.Y + (y + 0.5) * (r.Bottom - r.Top) / rh;
            int vx = GetSystemMetrics(76), vy = GetSystemMetrics(77), vw = GetSystemMetrics(78), vh = GetSystemMetrics(79);
            if (vw <= 1 || vh <= 1) return;
            int nx = (int)Math.Round((sx - vx) * 65535.0 / (vw - 1)), ny = (int)Math.Round((sy - vy) * 65535.0 / (vh - 1));
            Mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, nx, ny, 0);
        }

        void Mouse(uint flags, int dx, int dy, int data)
        {
            var i = new INPUT { type = INPUT_MOUSE };
            i.u.mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = unchecked((uint)data), dwFlags = flags };
            send(new[] { i });
        }

        // ---------------------------------------------------------------- Win32
        const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
        const uint KEYEVENTF_EXTENDEDKEY = 0x1, KEYEVENTF_KEYUP = 0x2, KEYEVENTF_SCANCODE = 0x8;
        public const uint MOUSEEVENTF_MOVE = 0x1, MOUSEEVENTF_LEFTDOWN = 0x2, MOUSEEVENTF_LEFTUP = 0x4, MOUSEEVENTF_RIGHTDOWN = 0x8,
            MOUSEEVENTF_RIGHTUP = 0x10, MOUSEEVENTF_WHEEL = 0x800, MOUSEEVENTF_VIRTUALDESK = 0x4000, MOUSEEVENTF_ABSOLUTE = 0x8000;

        [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public InputUnion u; }
        [StructLayout(LayoutKind.Explicit)] public struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
        [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)] struct RECT { public int Left, Top, Right, Bottom; }
        [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }

        [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, INPUT[] inputs, int size);
        [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint mapType);
        [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h, out RECT r);
        [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr h, ref POINT p);
        [DllImport("user32.dll")] static extern int GetSystemMetrics(int i);
        [DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
    }
}
