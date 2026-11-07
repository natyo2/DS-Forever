using System;
using System.Collections.Generic;
using System.Linq;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>companion-only mode's input player: what it sends, and everything it refuses</summary>
    public static class WowInputTests
    {
        static readonly IntPtr Wow = new IntPtr(0x1234);

        sealed class Rig
        {
            public bool Front = true, FocusWorks = true;
            public IntPtr Window = Wow;
            public readonly List<WowInput.INPUT> Sent = new List<WowInput.INPUT>();
            public readonly WowInput In;
            public Rig()
            {
                In = new WowInput(() => Window, h => { if (FocusWorks) Front = true; return Front; }, h => Front, a => Sent.AddRange(a), (x, y) => { });
            }
            public string Key(int vk, bool down) => In.Handle(new Dictionary<string, object> { ["op"] = "key", ["vk"] = vk, ["down"] = down });
            public List<int> Keys(bool down) => Sent.Where(i => i.type == 1 && ((i.u.ki.dwFlags & 2) == 0) == down).Select(i => (int)i.u.ki.wVk).ToList();
        }

        [Test]
        public static void PlaysAKeyPressAndRelease()
        {
            var r = new Rig();
            A.Null(r.Key(0x41, true)); A.Null(r.Key(0x41, false));
            A.Eq(2, r.Sent.Count);
            A.Eq(0x41, r.Keys(true).Single()); A.Eq(0x41, r.Keys(false).Single());
        }

        [Test]
        public static void NeverSendsSystemKeys()
        {
            var r = new Rig();
            foreach (var vk in new[] { 0x12, 0xA4, 0xA5, 0x5B, 0x5C, 0x5D, 0x70, 0x73, 0x7B, 0x87, 0x2C, 0x13, 0x14, 0x90, 0x91 })
                A.NotNull(r.Key(vk, true), "vk " + vk.ToString("X"));
            A.Eq(0, r.Sent.Count);
        }

        [Test]
        public static void NeverSendsCtrlEsc()
        {
            var r = new Rig();
            A.Null(r.Key(0xA2, true));
            A.NotNull(r.Key(0x1B, true));
            A.Null(r.Key(0xA2, false));
            A.Null(r.Key(0x1B, true));
            A.Eq(3, r.Sent.Count);
        }

        [Test]
        public static void RefusedWhenWowIsNotInFrontOrNotRunning()
        {
            var r = new Rig { Front = false, FocusWorks = false };
            A.NotNull(r.Key(0x41, true));
            r.Window = IntPtr.Zero;
            var r2 = new Rig { Window = IntPtr.Zero };
            A.Eq("WoW isn't running", r2.Key(0x41, true));
            A.Eq(0, r.Sent.Count + r2.Sent.Count);
        }

        [Test]
        public static void FocusesWowOnceThenPlays()
        {
            var r = new Rig { Front = false, FocusWorks = true };
            A.Null(r.Key(0x41, true));
            A.Eq(1, r.Sent.Count);
        }

        [Test]
        public static void AHeldKeyAlwaysGoesUpAndReleaseAllLetsGo()
        {
            var r = new Rig();
            r.Key(0xA0, true); r.Key(0x42, true);
            r.Front = false; r.FocusWorks = false;
            A.Null(r.Key(0x42, false), "a held key goes up even out of focus");
            r.In.ReleaseAll();
            A.True(r.Keys(false).SequenceEqual(new[] { 0x42, 0xA0 }), "both released");
            r.In.ReleaseAll();
            A.Eq(2, r.Keys(false).Count, "nothing left to release");
        }

        [Test]
        public static void FloodGuardDropsBursts()
        {
            var r = new Rig();
            int played = 0;
            for (int i = 0; i < 1000; i++) if (r.In.Handle(new Dictionary<string, object> { ["op"] = "move", ["dx"] = 1, ["dy"] = 0 }) == null) played++;
            A.True(played >= (int)WowInput.Burst && played < 600, "played " + played);
        }

        [Test]
        public static void ThePlayersKeyboardMayPressAltEscAndFKeysButNeverWindowsKeys()
        {
            var r = new Rig();
            Func<int, bool, string> kbd = (vk, down) => r.In.Handle(new Dictionary<string, object> { ["op"] = "kbd", ["vk"] = vk, ["down"] = down });
            A.Null(kbd(0x70, true)); A.Null(kbd(0x70, false));                 // F1 (party targeting)
            A.Null(kbd(0x1B, true)); A.Null(kbd(0x1B, false));                 // Esc (the game menu)
            A.Null(kbd(0xA4, true)); A.Null(kbd(0x5A, true)); A.Null(kbd(0x5A, false));   // Alt+Z (hide the UI)
            A.NotNull(kbd(0x09, true), "Alt+Tab"); A.NotNull(kbd(0x73, true), "Alt+F4"); A.NotNull(kbd(0x1B, true), "Alt+Esc");
            A.Null(kbd(0xA4, false));
            A.NotNull(kbd(0x5B, true), "Win"); A.NotNull(kbd(0x5D, true), "Menu"); A.NotNull(kbd(0xAD, true), "a media key");
            A.Null(kbd(0xA2, true)); A.NotNull(kbd(0x1B, true), "Ctrl+Esc"); A.Null(kbd(0xA2, false));
            A.Eq(10, r.Sent.Count);
            // the companion's own taps keep the strict rules
            A.NotNull(r.Key(0x70, true), "F1 from a tap");
        }

        [Test]
        public static void MouseButtonsAndWheel()
        {
            var r = new Rig();
            A.Null(r.In.Handle(new Dictionary<string, object> { ["op"] = "button", ["b"] = 1, ["down"] = true }));
            A.Null(r.In.Handle(new Dictionary<string, object> { ["op"] = "button", ["b"] = 3, ["down"] = false }));
            A.Null(r.In.Handle(new Dictionary<string, object> { ["op"] = "scroll", ["amount"] = -120 }));
            A.Eq(WowInput.MOUSEEVENTF_LEFTDOWN, r.Sent[0].u.mi.dwFlags);
            A.Eq(WowInput.MOUSEEVENTF_RIGHTUP, r.Sent[1].u.mi.dwFlags);
            A.Eq(WowInput.MOUSEEVENTF_WHEEL, r.Sent[2].u.mi.dwFlags);
            A.Eq(unchecked((uint)-120), r.Sent[2].u.mi.mouseData);
            A.NotNull(r.In.Handle(new Dictionary<string, object> { ["op"] = "pos", ["x"] = 1, ["y"] = 1, ["w"] = 0, ["h"] = 0 }));
        }
    }
}
