using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using Path = System.IO.Path;

namespace KomaComic.Windows
{
    internal static class NativeMethods
    {
        private static readonly IntPtr DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = new IntPtr(-4);
        private const int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
        private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetProcessDpiAwarenessContext(IntPtr value);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetProcessDPIAware();

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

        public static void EnablePerMonitorV2Dpi()
        {
            try
            {
                if (SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2))
                {
                    return;
                }
            }
            catch
            {
                // Older Windows 10 builds without SetProcessDpiAwarenessContext
            }
            try
            {
                SetProcessDPIAware();
            }
            catch
            {
                // Ignore
            }
        }

        public static void ApplyDarkTitleBar(IntPtr hwnd, bool dark)
        {
            if (hwnd == IntPtr.Zero) return;
            try
            {
                int val = dark ? 1 : 0;
                int hr = DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref val, sizeof(int));
                if (hr != 0)
                {
                    DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, ref val, sizeof(int));
                }
            }
            catch
            {
                // Ignore on Windows builds without DWM dark titlebar
            }
        }
    }

    internal static class Program
    {
        internal const string AppVersion = "1.5.4";
        internal const string GithubRepoSlug = "zmf678/koma-comic";

        [STAThread]
        private static int Main(string[] args)
        {
            NativeMethods.EnablePerMonitorV2Dpi();
            ConfigureNetworking();

            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            string assetsDir = Path.Combine(baseDir, "assets");
            string dataDir = Path.Combine(baseDir, "data");
            Directory.CreateDirectory(dataDir);

            bool isSmokeTest = false;
            if (args != null)
            {
                foreach (string arg in args)
                {
                    if (string.Equals(arg, "--smoke-test", StringComparison.OrdinalIgnoreCase))
                    {
                        isSmokeTest = true;
                        break;
                    }
                }
            }

            if (isSmokeTest)
            {
                return RunSmokeTest(baseDir, assetsDir, dataDir);
            }

            Application app = new Application();
            app.ShutdownMode = ShutdownMode.OnMainWindowClose;

            AppServices services = new AppServices(baseDir, assetsDir, dataDir);
            UiTheme.ApplyTheme(app, services.ThemeMode, services.GlassMaterial);
            services.StartBackend();

            KomaMainWindow mainWin = new KomaMainWindow(services);
            app.MainWindow = mainWin;
            mainWin.Show();

            int exitCode = app.Run();
            services.Dispose();
            return exitCode;
        }

        private static void ConfigureNetworking()
        {
            try
            {
                ServicePointManager.DefaultConnectionLimit = 64;
                ServicePointManager.Expect100Continue = false;
                SecurityProtocolType proto = SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11 | SecurityProtocolType.Tls;
                try { proto |= (SecurityProtocolType)12288; } catch { }
                ServicePointManager.SecurityProtocol = proto;
            }
            catch { }
        }

        private static int RunSmokeTest(string baseDir, string assetsDir, string dataDir)
        {
            AppServices services = null;
            try
            {
                services = new AppServices(baseDir, assetsDir, dataDir);
                int port = services.StartBackend();

                string manifestPath = Path.Combine(assetsDir, "sources", "builtin_sources.json");
                string shimPath = Path.Combine(assetsDir, "venera_shim.js");
                if (!File.Exists(manifestPath) || !File.Exists(shimPath))
                {
                    Console.Error.WriteLine("SMOKE_TEST_FAIL: missing assets files");
                    return 2;
                }

                List<SourceItem> sources = services.LoadBuiltinSources();
                if (sources.Count == 0 || sources.Exists(source => string.IsNullOrWhiteSpace(services.ReadSourceScript(source))))
                {
                    Console.Error.WriteLine("SMOKE_TEST_FAIL: invalid built-in source manifest, count=" + sources.Count);
                    return 3;
                }

                string healthUrl = string.Format("http://127.0.0.1:{0}/api/health", port);
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(healthUrl);
                req.Timeout = 5000;
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                {
                    if ((int)resp.StatusCode != 200)
                    {
                        Console.Error.WriteLine("SMOKE_TEST_FAIL: health returned " + resp.StatusCode);
                        return 4;
                    }
                }

                // Verify WPF font & visual resource initialization works cleanly
                BitmapImage iconCheck = services.LoadAssetImage("ic_koma_logo.png", 64);
                if (iconCheck == null)
                {
                    Console.Error.WriteLine("SMOKE_TEST_FAIL: ic_koma_logo.png decode failed");
                    return 5;
                }

                Console.WriteLine(string.Format("SMOKE_TEST_OK (ui=WPF-DirectX, dpi=PerMonitorV2, port={0}, sources={1}, version={2})", port, sources.Count, AppVersion));
                return 0;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SMOKE_TEST_FAIL: " + ex.ToString());
                return 1;
            }
            finally
            {
                if (services != null) services.Dispose();
            }
        }
    }

    internal static class JsonUtil
    {
        public static JavaScriptSerializer CreateSerializer()
        {
            JavaScriptSerializer ser = new JavaScriptSerializer();
            ser.MaxJsonLength = int.MaxValue;
            ser.RecursionLimit = 256;
            return ser;
        }

        public static string GetStr(Dictionary<string, object> d, string key, string fallback)
        {
            if (d == null) return fallback;
            object v = null;
            if (d.TryGetValue(key, out v) && v != null)
            {
                return Convert.ToString(v);
            }
            return fallback;
        }

        public static int GetInt(Dictionary<string, object> d, string key, int fallback)
        {
            if (d == null) return fallback;
            object v = null;
            if (d.TryGetValue(key, out v) && v != null)
            {
                int n;
                if (int.TryParse(Convert.ToString(v), out n)) return n;
            }
            return fallback;
        }

        public static bool GetBool(Dictionary<string, object> d, string key, bool fallback)
        {
            if (d == null) return fallback;
            object v = null;
            if (d.TryGetValue(key, out v) && v != null)
            {
                try { return Convert.ToBoolean(v); } catch { }
            }
            return fallback;
        }
    }

    internal static class UiTheme
    {
        private static readonly Dictionary<string, Color> DarkColors = new Dictionary<string, Color>
        {
            { "Bg", Color.FromRgb(15, 19, 26) },
            { "BgElevated", Color.FromRgb(23, 29, 39) },
            { "BgRail", Color.FromRgb(19, 24, 33) },
            { "Surface", Color.FromRgb(30, 37, 51) },
            { "SurfaceHover", Color.FromRgb(41, 51, 70) },
            { "Border", Color.FromArgb(26, 255, 255, 255) },
            { "Text", Color.FromRgb(238, 242, 248) },
            { "TextMuted", Color.FromRgb(152, 164, 184) },
            { "Accent", Color.FromRgb(56, 163, 255) },
            { "AccentSoft", Color.FromArgb(44, 56, 163, 255) },
            { "AccentBorder", Color.FromRgb(64, 169, 255) },
            { "Danger", Color.FromRgb(240, 90, 101) },
            { "DangerSoft", Color.FromArgb(40, 240, 90, 101) },
            { "InputBg", Color.FromRgb(26, 33, 45) }
        };

        private static readonly Dictionary<string, Color> LightColors = new Dictionary<string, Color>
        {
            { "Bg", Color.FromRgb(244, 246, 250) },
            { "BgElevated", Color.FromRgb(255, 255, 255) },
            { "BgRail", Color.FromRgb(235, 239, 247) },
            { "Surface", Color.FromRgb(255, 255, 255) },
            { "SurfaceHover", Color.FromRgb(231, 238, 250) },
            { "Border", Color.FromArgb(24, 15, 23, 42) },
            { "Text", Color.FromRgb(21, 27, 38) },
            { "TextMuted", Color.FromRgb(91, 103, 124) },
            { "Accent", Color.FromRgb(29, 127, 232) },
            { "AccentSoft", Color.FromArgb(30, 29, 127, 232) },
            { "AccentBorder", Color.FromRgb(29, 127, 232) },
            { "Danger", Color.FromRgb(217, 59, 71) },
            { "DangerSoft", Color.FromArgb(26, 217, 59, 71) },
            { "InputBg", Color.FromRgb(255, 255, 255) }
        };

        public static bool IsDarkActive { get; private set; }
        public static bool IsGlassActive { get; private set; }

        public static void ApplyTheme(Application app, string mode, bool glass = false)
        {
            bool dark = true;
            if (string.Equals(mode, "light", StringComparison.OrdinalIgnoreCase))
            {
                dark = false;
            }
            else if (string.Equals(mode, "system", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    object val = Microsoft.Win32.Registry.GetValue(
                        @"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
                        "AppsUseLightTheme",
                        0);
                    dark = Convert.ToInt32(val) == 0;
                }
                catch { dark = true; }
            }

            IsDarkActive = dark;
            IsGlassActive = glass;
            Dictionary<string, Color> palette = dark ? DarkColors : LightColors;
            foreach (KeyValuePair<string, Color> kv in palette)
            {
                Brush brush = new SolidColorBrush(kv.Value);
                brush.Freeze();
                app.Resources["Brush." + kv.Key] = brush;
            }

            if (glass)
            {
                // Luminous ambient canvas so translucent glass layers refract light naturally
                LinearGradientBrush bgBrush = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
                if (dark)
                {
                    bgBrush.GradientStops.Add(new GradientStop(Color.FromRgb(10, 15, 28), 0.0));
                    bgBrush.GradientStops.Add(new GradientStop(Color.FromRgb(15, 23, 42), 0.48));
                    bgBrush.GradientStops.Add(new GradientStop(Color.FromRgb(19, 27, 48), 1.0));
                }
                else
                {
                    bgBrush.GradientStops.Add(new GradientStop(Color.FromRgb(240, 245, 253), 0.0));
                    bgBrush.GradientStops.Add(new GradientStop(Color.FromRgb(234, 241, 252), 0.52));
                    bgBrush.GradientStops.Add(new GradientStop(Color.FromRgb(243, 239, 251), 1.0));
                }
                bgBrush.Freeze();
                app.Resources["Brush.Bg"] = bgBrush;

                // Clean frosted glass surfaces (crystal white in light mode, deep smoke in dark mode)
                LinearGradientBrush elevated = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
                if (dark)
                {
                    elevated.GradientStops.Add(new GradientStop(Color.FromArgb(196, 30, 41, 62), 0.0));
                    elevated.GradientStops.Add(new GradientStop(Color.FromArgb(172, 19, 27, 43), 1.0));
                }
                else
                {
                    elevated.GradientStops.Add(new GradientStop(Color.FromArgb(228, 255, 255, 255), 0.0));
                    elevated.GradientStops.Add(new GradientStop(Color.FromArgb(198, 246, 250, 255), 1.0));
                }
                elevated.Freeze();
                app.Resources["Brush.BgElevated"] = elevated;
                app.Resources["Brush.InputBg"] = elevated;

                LinearGradientBrush surface = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0.8, 1) };
                if (dark)
                {
                    surface.GradientStops.Add(new GradientStop(Color.FromArgb(176, 36, 49, 74), 0.0));
                    surface.GradientStops.Add(new GradientStop(Color.FromArgb(152, 23, 33, 52), 1.0));
                }
                else
                {
                    surface.GradientStops.Add(new GradientStop(Color.FromArgb(224, 255, 255, 255), 0.0));
                    surface.GradientStops.Add(new GradientStop(Color.FromArgb(196, 242, 247, 255), 1.0));
                }
                surface.Freeze();
                app.Resources["Brush.Surface"] = surface;

                SolidColorBrush surfHover = new SolidColorBrush(dark ? Color.FromArgb(215, 48, 66, 98) : Color.FromArgb(245, 255, 255, 255));
                surfHover.Freeze();
                app.Resources["Brush.SurfaceHover"] = surfHover;

                // Diagonal specular rim border for authentic Liquid Glass refraction
                LinearGradientBrush glassBorder = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
                if (dark)
                {
                    glassBorder.GradientStops.Add(new GradientStop(Color.FromArgb(98, 255, 255, 255), 0.0));
                    glassBorder.GradientStops.Add(new GradientStop(Color.FromArgb(28, 255, 255, 255), 0.45));
                    glassBorder.GradientStops.Add(new GradientStop(Color.FromArgb(62, 130, 186, 255), 1.0));
                }
                else
                {
                    glassBorder.GradientStops.Add(new GradientStop(Color.FromArgb(242, 255, 255, 255), 0.0));
                    glassBorder.GradientStops.Add(new GradientStop(Color.FromArgb(120, 210, 224, 244), 0.55));
                    glassBorder.GradientStops.Add(new GradientStop(Color.FromArgb(175, 184, 210, 245), 1.0));
                }
                glassBorder.Freeze();
                app.Resources["Brush.Border"] = glassBorder;

                LinearGradientBrush accentSoft = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
                if (dark)
                {
                    accentSoft.GradientStops.Add(new GradientStop(Color.FromArgb(118, 56, 163, 255), 0.0));
                    accentSoft.GradientStops.Add(new GradientStop(Color.FromArgb(72, 32, 102, 196), 1.0));
                }
                else
                {
                    accentSoft.GradientStops.Add(new GradientStop(Color.FromArgb(215, 226, 242, 255), 0.0));
                    accentSoft.GradientStops.Add(new GradientStop(Color.FromArgb(182, 204, 229, 255), 1.0));
                }
                accentSoft.Freeze();
                app.Resources["Brush.AccentSoft"] = accentSoft;

                LinearGradientBrush accentGrad = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0.8, 1) };
                accentGrad.GradientStops.Add(new GradientStop(dark ? Color.FromRgb(72, 174, 255) : Color.FromRgb(46, 146, 255), 0.0));
                accentGrad.GradientStops.Add(new GradientStop(dark ? Color.FromRgb(36, 130, 238) : Color.FromRgb(22, 114, 228), 1.0));
                accentGrad.Freeze();
                app.Resources["Brush.Accent"] = accentGrad;
            }

            // Floating Dock & Glass Capsule resources
            LinearGradientBrush navGlass = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
            if (dark)
            {
                navGlass.GradientStops.Add(new GradientStop(Color.FromArgb(glass ? (byte)204 : (byte)228, 34, 46, 70), 0.0));
                navGlass.GradientStops.Add(new GradientStop(Color.FromArgb(glass ? (byte)176 : (byte)216, 18, 26, 42), 1.0));
            }
            else
            {
                navGlass.GradientStops.Add(new GradientStop(Color.FromArgb(glass ? (byte)224 : (byte)244, 255, 255, 255), 0.0));
                navGlass.GradientStops.Add(new GradientStop(Color.FromArgb(glass ? (byte)188 : (byte)236, 240, 246, 255), 1.0));
            }
            navGlass.Freeze();
            app.Resources["Brush.NavGlass"] = navGlass;

            LinearGradientBrush navRim = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
            if (dark)
            {
                navRim.GradientStops.Add(new GradientStop(Color.FromArgb(128, 255, 255, 255), 0.0));
                navRim.GradientStops.Add(new GradientStop(Color.FromArgb(34, 255, 255, 255), 0.45));
                navRim.GradientStops.Add(new GradientStop(Color.FromArgb(78, 128, 190, 255), 1.0));
            }
            else
            {
                navRim.GradientStops.Add(new GradientStop(Color.FromArgb(250, 255, 255, 255), 0.0));
                navRim.GradientStops.Add(new GradientStop(Color.FromArgb(140, 214, 228, 248), 0.55));
                navRim.GradientStops.Add(new GradientStop(Color.FromArgb(190, 178, 208, 246), 1.0));
            }
            navRim.Freeze();
            app.Resources["Brush.NavGlassRim"] = navRim;

            LinearGradientBrush navSel = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
            if (dark)
            {
                navSel.GradientStops.Add(new GradientStop(Color.FromArgb(148, 66, 158, 255), 0.0));
                navSel.GradientStops.Add(new GradientStop(Color.FromArgb(98, 36, 102, 198), 1.0));
            }
            else
            {
                navSel.GradientStops.Add(new GradientStop(Color.FromArgb(236, 224, 241, 255), 0.0));
                navSel.GradientStops.Add(new GradientStop(Color.FromArgb(210, 194, 224, 255), 1.0));
            }
            navSel.Freeze();
            app.Resources["Brush.NavGlassSelected"] = navSel;

            LinearGradientBrush sheen = new LinearGradientBrush { StartPoint = new Point(0.5, 0), EndPoint = new Point(0.5, 1) };
            sheen.GradientStops.Add(new GradientStop(dark ? Color.FromArgb(44, 255, 255, 255) : Color.FromArgb(150, 255, 255, 255), 0.0));
            sheen.GradientStops.Add(new GradientStop(Color.FromArgb(0, 255, 255, 255), 0.52));
            sheen.Freeze();
            app.Resources["Brush.GlassSheen"] = sheen;

            SolidColorBrush cardSurface = new SolidColorBrush(glass
                ? (dark ? Color.FromArgb(184, 22, 30, 46) : Color.FromArgb(218, 255, 255, 255))
                : (dark ? Color.FromRgb(23, 29, 39) : Color.FromRgb(255, 255, 255)));
            cardSurface.Freeze();
            app.Resources["Brush.CardSurface"] = cardSurface;

            SolidColorBrush favActiveBg = new SolidColorBrush(dark ? Color.FromArgb(72, 255, 190, 61) : Color.FromArgb(46, 245, 158, 11));
            favActiveBg.Freeze();
            app.Resources["Brush.FavActiveBg"] = favActiveBg;
            SolidColorBrush favActiveBorder = new SolidColorBrush(dark ? Color.FromRgb(255, 196, 77) : Color.FromRgb(234, 145, 12));
            favActiveBorder.Freeze();
            app.Resources["Brush.FavActiveBorder"] = favActiveBorder;
            SolidColorBrush favActiveFg = new SolidColorBrush(dark ? Color.FromRgb(255, 210, 102) : Color.FromRgb(194, 106, 0));
            favActiveFg.Freeze();
            app.Resources["Brush.FavActiveFg"] = favActiveFg;
        }

        public static DropShadowEffect CreateGlassShadow(bool dark, bool subtle = false)
        {
            return new DropShadowEffect
            {
                Color = dark ? Color.FromRgb(4, 8, 16) : Color.FromRgb(64, 92, 138),
                BlurRadius = subtle ? 16 : 26,
                ShadowDepth = subtle ? 3 : 6,
                Direction = 270,
                Opacity = dark ? (subtle ? 0.32 : 0.44) : (subtle ? 0.09 : 0.14)
            };
        }

        public static ControlTemplate CreateGlassSliderTemplate()
        {
            const string xaml =
                "<ControlTemplate xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' " +
                "xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml' TargetType='{x:Type Slider}'>" +
                "<Grid VerticalAlignment='Center' MinHeight='22'>" +
                "<Border Height='5' CornerRadius='2.5' Background='#3EFFFFFF' VerticalAlignment='Center'/>" +
                "<Track x:Name='PART_Track'>" +
                "<Track.DecreaseRepeatButton>" +
                "<RepeatButton Command='Slider.DecreaseLarge' FocusVisualStyle='{x:Null}'>" +
                "<RepeatButton.Template><ControlTemplate TargetType='RepeatButton'>" +
                "<Border Height='5' CornerRadius='2.5'>" +
                "<Border.Background><LinearGradientBrush StartPoint='0,0.5' EndPoint='1,0.5'>" +
                "<GradientStop Color='#FF38A3FF' Offset='0'/><GradientStop Color='#FF7DD3FC' Offset='1'/>" +
                "</LinearGradientBrush></Border.Background></Border>" +
                "</ControlTemplate></RepeatButton.Template></RepeatButton>" +
                "</Track.DecreaseRepeatButton>" +
                "<Track.IncreaseRepeatButton>" +
                "<RepeatButton Command='Slider.IncreaseLarge' FocusVisualStyle='{x:Null}'>" +
                "<RepeatButton.Template><ControlTemplate TargetType='RepeatButton'>" +
                "<Border Height='16' Background='Transparent'/>" +
                "</ControlTemplate></RepeatButton.Template></RepeatButton>" +
                "</Track.IncreaseRepeatButton>" +
                "<Track.Thumb>" +
                "<Thumb Cursor='Hand' FocusVisualStyle='{x:Null}'>" +
                "<Thumb.Template><ControlTemplate TargetType='Thumb'>" +
                "<Border x:Name='knob' Width='16' Height='16' CornerRadius='8' Background='White' BorderBrush='#B493C5FD' BorderThickness='1.5'>" +
                "<Border.Effect><DropShadowEffect Color='Black' BlurRadius='8' ShadowDepth='1' Opacity='0.45'/></Border.Effect>" +
                "</Border>" +
                "<ControlTemplate.Triggers>" +
                "<Trigger Property='IsMouseOver' Value='True'>" +
                "<Setter TargetName='knob' Property='Width' Value='19'/>" +
                "<Setter TargetName='knob' Property='Height' Value='19'/>" +
                "<Setter TargetName='knob' Property='CornerRadius' Value='9.5'/>" +
                "</Trigger></ControlTemplate.Triggers>" +
                "</ControlTemplate></Thumb.Template></Thumb>" +
                "</Track.Thumb>" +
                "</Track></Grid></ControlTemplate>";
            return (ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
        }

        public static ControlTemplate CreateRoundedButtonTemplate(double radius, string hoverBrushKey)
        {
            ControlTemplate tpl = new ControlTemplate(typeof(Button));
            FrameworkElementFactory border = new FrameworkElementFactory(typeof(Border));
            border.Name = "bd";
            border.SetValue(Border.CornerRadiusProperty, new CornerRadius(radius));
            border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
            border.SetValue(Border.BorderBrushProperty, new TemplateBindingExtension(Control.BorderBrushProperty));
            border.SetValue(Border.BorderThicknessProperty, new TemplateBindingExtension(Control.BorderThicknessProperty));
            border.SetValue(Border.PaddingProperty, new TemplateBindingExtension(Control.PaddingProperty));
            border.SetValue(UIElement.SnapsToDevicePixelsProperty, true);

            FrameworkElementFactory cp = new FrameworkElementFactory(typeof(ContentPresenter));
            cp.SetValue(ContentPresenter.HorizontalAlignmentProperty, new TemplateBindingExtension(Control.HorizontalContentAlignmentProperty));
            cp.SetValue(ContentPresenter.VerticalAlignmentProperty, new TemplateBindingExtension(Control.VerticalContentAlignmentProperty));
            border.AppendChild(cp);
            tpl.VisualTree = border;

            Trigger hover = new Trigger();
            hover.Property = UIElement.IsMouseOverProperty;
            hover.Value = true;
            if (!string.IsNullOrEmpty(hoverBrushKey))
            {
                hover.Setters.Add(new Setter(Border.BackgroundProperty, new DynamicResourceExtension(hoverBrushKey), "bd"));
            }
            else
            {
                hover.Setters.Add(new Setter(UIElement.OpacityProperty, 0.90, "bd"));
            }
            tpl.Triggers.Add(hover);

            Trigger pressed = new Trigger();
            pressed.Property = ButtonBase.IsPressedProperty;
            pressed.Value = true;
            pressed.Setters.Add(new Setter(UIElement.OpacityProperty, 0.80, "bd"));
            tpl.Triggers.Add(pressed);

            Trigger disabled = new Trigger();
            disabled.Property = UIElement.IsEnabledProperty;
            disabled.Value = false;
            disabled.Setters.Add(new Setter(UIElement.OpacityProperty, 0.45, "bd"));
            tpl.Triggers.Add(disabled);

            return tpl;
        }

        public static ControlTemplate CreateToggleSwitchTemplate()
        {
            ControlTemplate tpl = new ControlTemplate(typeof(CheckBox));
            FrameworkElementFactory track = new FrameworkElementFactory(typeof(Border));
            track.Name = "track";
            track.SetValue(FrameworkElement.WidthProperty, 42.0);
            track.SetValue(FrameworkElement.HeightProperty, 22.0);
            track.SetValue(Border.CornerRadiusProperty, new CornerRadius(11));
            track.SetValue(Border.BackgroundProperty, new SolidColorBrush(Color.FromRgb(71, 82, 102)));

            FrameworkElementFactory knob = new FrameworkElementFactory(typeof(Ellipse));
            knob.Name = "knob";
            knob.SetValue(FrameworkElement.WidthProperty, 16.0);
            knob.SetValue(FrameworkElement.HeightProperty, 16.0);
            knob.SetValue(Shape.FillProperty, Brushes.White);
            knob.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Left);
            knob.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);
            knob.SetValue(FrameworkElement.MarginProperty, new Thickness(3, 0, 3, 0));
            track.AppendChild(knob);

            tpl.VisualTree = track;

            Trigger checkedTrig = new Trigger();
            checkedTrig.Property = ToggleButton.IsCheckedProperty;
            checkedTrig.Value = true;
            checkedTrig.Setters.Add(new Setter(Border.BackgroundProperty, new DynamicResourceExtension("Brush.Accent"), "track"));
            checkedTrig.Setters.Add(new Setter(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Right, "knob"));
            tpl.Triggers.Add(checkedTrig);

            return tpl;
        }

        public static Style BuildPillButtonStyle(bool primary, bool danger)
        {
            Style st = new Style(typeof(Button));
            st.Setters.Add(new Setter(FrameworkElement.FocusVisualStyleProperty, null));
            st.Setters.Add(new Setter(Control.FontSizeProperty, 13.0));
            st.Setters.Add(new Setter(Control.FontWeightProperty, FontWeights.SemiBold));
            st.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(14, 7, 14, 7)));
            st.Setters.Add(new Setter(Control.BorderThicknessProperty, new Thickness(1)));
            st.Setters.Add(new Setter(FrameworkElement.CursorProperty, Cursors.Hand));
            st.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty, HorizontalAlignment.Center));
            st.Setters.Add(new Setter(Control.VerticalContentAlignmentProperty, VerticalAlignment.Center));

            if (primary)
            {
                st.Setters.Add(new Setter(Control.BackgroundProperty, new DynamicResourceExtension("Brush.Accent")));
                st.Setters.Add(new Setter(Control.BorderBrushProperty, new SolidColorBrush(Color.FromArgb(95, 255, 255, 255))));
                st.Setters.Add(new Setter(Control.ForegroundProperty, Brushes.White));
                st.Setters.Add(new Setter(Control.TemplateProperty, CreateRoundedButtonTemplate(16, null)));
            }
            else if (danger)
            {
                st.Setters.Add(new Setter(Control.BackgroundProperty, new DynamicResourceExtension("Brush.DangerSoft")));
                st.Setters.Add(new Setter(Control.BorderBrushProperty, new DynamicResourceExtension("Brush.Danger")));
                st.Setters.Add(new Setter(Control.ForegroundProperty, new DynamicResourceExtension("Brush.Danger")));
                st.Setters.Add(new Setter(Control.TemplateProperty, CreateRoundedButtonTemplate(16, null)));
            }
            else
            {
                st.Setters.Add(new Setter(Control.BackgroundProperty, new DynamicResourceExtension("Brush.Surface")));
                st.Setters.Add(new Setter(Control.BorderBrushProperty, new DynamicResourceExtension("Brush.Border")));
                st.Setters.Add(new Setter(Control.ForegroundProperty, new DynamicResourceExtension("Brush.Text")));
                st.Setters.Add(new Setter(Control.TemplateProperty, CreateRoundedButtonTemplate(16, "Brush.SurfaceHover")));
            }
            return st;
        }

        public static Button MakeButton(string text, bool primary, bool danger)
        {
            Button b = new Button();
            b.Content = text;
            b.FocusVisualStyle = null;
            b.Style = BuildPillButtonStyle(primary, danger);
            return b;
        }

        public static CheckBox MakeToggle(bool initialChecked)
        {
            CheckBox cb = new CheckBox();
            cb.IsChecked = initialChecked;
            cb.Cursor = Cursors.Hand;
            cb.FocusVisualStyle = null;
            cb.Template = CreateToggleSwitchTemplate();
            return cb;
        }
    }

    internal sealed class SourceItem
    {
        public string Key { get; set; }
        public string Name { get; set; }
        public string Version { get; set; }
        public string FileName { get; set; }
        public string Url { get; set; }
        public bool Enabled { get; set; }
        public string CustomScript { get; set; }
    }

    internal sealed class ComicCardModel
    {
        public string Uid { get; set; }
        public string Id { get; set; }
        public string SourceKey { get; set; }
        public bool IsLocal { get; set; }
        public string Title { get; set; }
        public string SubTitle { get; set; }
        public string Description { get; set; }
        public List<string> Tags { get; set; }
        public string OfficialUrl { get; set; }
        public string CoverUrl { get; set; }
        public string Badge { get; set; }
        public Dictionary<string, string> Headers { get; set; }
    }

    internal static class ComicSourceMetadata
    {
        public static List<string> ReadTags(Dictionary<string, object> comic)
        {
            object value;
            var list = comic != null && comic.TryGetValue("tags", out value) ? value as IList : null;
            return list == null ? new List<string>() : list.OfType<string>()
                .Select(tag => tag.Trim())
                .Where(tag => tag.Length > 0 && tag.Length <= 80 && tag != "null" && tag != "undefined")
                .Distinct(StringComparer.Ordinal).Take(64).ToList();
        }
    }

    internal sealed class ChapterItem
    {
        public string Id { get; set; }
        public string Title { get; set; }
    }

    internal sealed class ComicDetailModel
    {
        public string Title { get; set; }
        public string SubTitle { get; set; }
        public string CoverUrl { get; set; }
        public string Description { get; set; }
        public string OfficialUrl { get; set; }
        public List<string> Tags { get; set; }
        public List<ChapterItem> Chapters { get; set; }
        public Dictionary<string, string> Headers { get; set; }
    }

    internal sealed class PageImageModel
    {
        public string Url { get; set; }
        public bool IsLocal { get; set; }
        public string LocalComicId { get; set; }
        public int LocalPageIndex { get; set; }
        public bool UseSourceLoader { get; set; }
        public Dictionary<string, string> Headers { get; set; }
    }

    internal sealed class CollectionModel
    {
        public string Id { get; set; }
        public string Name { get; set; }
        public string Pin { get; set; }
        public bool HideFromAll { get; set; }
        public List<ComicCardModel> Items { get; set; }
    }

    internal sealed class ReaderPrefModel
    {
        public string Mode { get; set; }
        public string Spread { get; set; }
        public int Brightness { get; set; }
        public string Quality { get; set; }
        public bool TrimMargins { get; set; }
        public bool DoubleTapZoom { get; set; }
        public bool ShowCommentPage { get; set; }
        public bool ShowPageNumber { get; set; }
        public bool ApplyGlobal { get; set; }
        public bool WheelPageTurn { get; set; }
        public string PageTurnAnim { get; set; }
        public int PrefetchPages { get; set; }
        public bool SplitAllPages { get; set; }
        public bool SpreadGap { get; set; }
        public int LastChapterIdx { get; set; }
        public int LastPage { get; set; }

        public ReaderPrefModel()
        {
            WheelPageTurn = true;
            PageTurnAnim = "slide";
            PrefetchPages = 4;
            SplitAllPages = false;
            SpreadGap = true;
        }

        public ReaderPrefModel Clone()
        {
            ReaderPrefModel m = new ReaderPrefModel();
            m.Mode = Mode;
            m.Spread = Spread;
            m.Brightness = Brightness;
            m.Quality = Quality;
            m.TrimMargins = TrimMargins;
            m.DoubleTapZoom = DoubleTapZoom;
            m.ShowCommentPage = ShowCommentPage;
            m.ShowPageNumber = ShowPageNumber;
            m.ApplyGlobal = ApplyGlobal;
            m.WheelPageTurn = WheelPageTurn;
            m.PageTurnAnim = PageTurnAnim ?? "slide";
            m.PrefetchPages = PrefetchPages;
            m.SplitAllPages = SplitAllPages;
            m.SpreadGap = SpreadGap;
            m.LastChapterIdx = LastChapterIdx;
            m.LastPage = LastPage;
            return m;
        }
    }

    internal sealed class LocalComicRecord
    {
        public string Id { get; set; }
        public string Title { get; set; }
        public string Format { get; set; }
        public string Path { get; set; }
        public int PageCount { get; set; }
    }

    internal sealed class CookieEntry
    {
        public string Name { get; set; }
        public string Value { get; set; }
        public string Domain { get; set; }
        public string Path { get; set; }
    }

    internal sealed class AccountCapModel
    {
        public bool HasPasswordLogin { get; set; }
        public List<string> CookieFields { get; set; }
        public string RegisterWebsite { get; set; }
        public bool IsLoggedIn { get; set; }
    }

    internal sealed class SourceSettingItem
    {
        public string Key { get; set; }
        public string Title { get; set; }
        public string Type { get; set; }
        public string ButtonText { get; set; }
        public object Value { get; set; }
        public List<KeyValuePair<string, string>> Options { get; set; }
    }

    internal sealed class NaturalComparer : IComparer<string>
    {
        public static readonly NaturalComparer Instance = new NaturalComparer();
        private static readonly Regex ChunkRegex = new Regex("(\\d+|\\D+)", RegexOptions.Compiled);

        public int Compare(string x, string y)
        {
            if (x == y) return 0;
            if (x == null) return -1;
            if (y == null) return 1;

            MatchCollection mx = ChunkRegex.Matches(x);
            MatchCollection my = ChunkRegex.Matches(y);
            int count = Math.Min(mx.Count, my.Count);
            for (int i = 0; i < count; i++)
            {
                string sx = mx[i].Value;
                string sy = my[i].Value;
                long nx, ny;
                if (long.TryParse(sx, out nx) && long.TryParse(sy, out ny))
                {
                    int cmp = nx.CompareTo(ny);
                    if (cmp != 0) return cmp;
                    cmp = sx.Length.CompareTo(sy.Length);
                    if (cmp != 0) return cmp;
                }
                else
                {
                    int cmp = string.Compare(sx, sy, StringComparison.OrdinalIgnoreCase);
                    if (cmp != 0) return cmp;
                }
            }
            return mx.Count.CompareTo(my.Count);
        }
    }

    internal sealed class AppServices : IDisposable
    {
        private static readonly string[] ImageExtensions = new string[] { ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif" };
        private static readonly string[] ArchiveExtensions = new string[] { ".zip", ".cbz", ".epub" };

        public string BaseDir { get; private set; }
        public string AssetsDir { get; private set; }
        public string DataDir { get; private set; }
        public int ServerPort { get; private set; }
        public string HostUrl { get { return string.Format("http://127.0.0.1:{0}/venera_host.html", ServerPort); } }

        private readonly string _imageCacheDir;
        private readonly string _stateFile;
        private readonly string _veneraStorageFile;
        private readonly string _cookiesFile;
        private readonly string _warehouseFile;
        private readonly string _localLibFile;

        private readonly object _stateLock = new object();
        private readonly object _veneraLock = new object();
        private readonly object _cookieLock = new object();
        private readonly object _warehouseLock = new object();

        private Dictionary<string, object> _veneraStorage = new Dictionary<string, object>();
        private List<CookieEntry> _cookies = new List<CookieEntry>();
        public List<string> WarehousePaths { get; private set; }
        private readonly Dictionary<string, LocalComicRecord> _localComicsById = new Dictionary<string, LocalComicRecord>(StringComparer.Ordinal);
        private readonly Dictionary<string, BitmapImage> _memoryThumbCache = new Dictionary<string, BitmapImage>(StringComparer.Ordinal);
        private readonly SemaphoreSlim _imgFetchLimit = new SemaphoreSlim(8, 8);

        public string ThemeMode { get; set; }
        public bool GridCompact { get; set; }
        public string ActiveHomeMode { get; set; }
        public string LastSource { get; set; }
        public string WallpaperPath { get { return Path.Combine(DataDir, "library-wallpaper.jpg"); } }
        public string ProfileAvatarPath { get { return Path.Combine(DataDir, "profile-avatar.jpg"); } }
        public ReadingActivity ReadingActivity { get; private set; }
        public bool GlassMaterial { get; set; }
        public bool WallpaperEnabled { get; set; }
        public string TranslationServer { get; set; }
        public string TranslationConfig { get; set; }
        public TranslationModel TranslationModel { get; set; }
        public string TranslationSession { get; set; }
        public int TranslationPrefetchPages { get; set; }
        public string FeedbackProtectedToken { get; set; }
        public List<SourceItem> Sources { get; private set; }
        public List<ComicCardModel> Favorites { get; private set; }
        public List<ComicCardModel> History { get; private set; }
        public List<CollectionModel> Collections { get; private set; }
        public string ActiveCollectionId { get; set; }
        public Dictionary<string, bool> UnlockedCollections { get; private set; }
        public string WebDavUrl { get; set; }
        public string WebDavUser { get; set; }
        public string WebDavPass { get; set; }
        public ReaderPrefModel ReaderDefaults { get; private set; }
        public Dictionary<string, ReaderPrefModel> ComicPrefs { get; private set; }

        private HttpListener _listener;
        private Thread _acceptThread;
        private volatile bool _running;
        public event Action<string> OnToast;

        public AppServices(string baseDir, string assetsDir, string dataDir)
        {
            BaseDir = baseDir;
            AssetsDir = assetsDir;
            DataDir = dataDir;
            _imageCacheDir = Path.Combine(dataDir, "cache", "images");
            Directory.CreateDirectory(_imageCacheDir);
            ReadingActivity = new ReadingActivity(dataDir);

            _stateFile = Path.Combine(dataDir, "app_state.json");
            _veneraStorageFile = Path.Combine(dataDir, "venera_storage.json");
            _cookiesFile = Path.Combine(dataDir, "cookies.json");
            _warehouseFile = Path.Combine(dataDir, "warehouse_paths.json");
            _localLibFile = Path.Combine(dataDir, "local_library.json");

            WarehousePaths = new List<string>();
            Sources = new List<SourceItem>();
            Favorites = new List<ComicCardModel>();
            History = new List<ComicCardModel>();
            Collections = new List<CollectionModel>();
            UnlockedCollections = new Dictionary<string, bool>();
            ComicPrefs = new Dictionary<string, ReaderPrefModel>();
            ThemeMode = "dark";
            ActiveHomeMode = "zaimanhua";
            LastSource = "zaimanhua";
            TranslationServer = "";
            TranslationConfig = "{\"translator\":{\"target_lang\":\"CHS\"}}";
            TranslationModel = new TranslationModel { Mode = TranslationMode.Direct };
            ReaderDefaults = new ReaderPrefModel
            {
                Mode = "rtl",
                Spread = "double",
                Brightness = 100,
                Quality = "ultra",
                TrimMargins = false,
                DoubleTapZoom = true,
                ShowCommentPage = false,
                ShowPageNumber = true,
                ApplyGlobal = false
            };

            LoadPersistentStores();
            LoadStateAndSources();
        }

        public int StartBackend()
        {
            if (_running) return ServerPort;
            int port = ResolvePort();
            _listener = new HttpListener();
            _listener.Prefixes.Add(string.Format("http://127.0.0.1:{0}/", port));
            _listener.Start();
            ServerPort = port;
            _running = true;
            _acceptThread = new Thread(AcceptLoop);
            _acceptThread.IsBackground = true;
            _acceptThread.Start();
            return port;
        }

        public void Dispose()
        {
            _running = false;
            try
            {
                if (_listener != null && _listener.IsListening)
                {
                    _listener.Stop();
                    _listener.Close();
                }
            }
            catch { }
        }

        private static int ResolvePort()
        {
            TcpListener ephem = new TcpListener(IPAddress.Loopback, 0);
            ephem.Start();
            int assigned = ((IPEndPoint)ephem.LocalEndpoint).Port;
            ephem.Stop();
            return assigned;
        }

        private void AcceptLoop()
        {
            while (_running)
            {
                try
                {
                    HttpListenerContext ctx = _listener.GetContext();
                    ThreadPool.QueueUserWorkItem(delegate (object s) { HandleHttpContext((HttpListenerContext)s); }, ctx);
                }
                catch
                {
                    if (!_running) break;
                }
            }
        }

        private void HandleHttpContext(HttpListenerContext ctx)
        {
            HttpListenerRequest req = ctx.Request;
            HttpListenerResponse resp = ctx.Response;
            try
            {
                string path = req.Url.AbsolutePath ?? "/";
                if (path.Equals("/api/health", StringComparison.OrdinalIgnoreCase))
                {
                    WriteJson(resp, new Dictionary<string, object> { { "ok", true }, { "sources", Sources.Count } });
                    return;
                }
                if (path.Equals("/api/venera/dispatch", StringComparison.OrdinalIgnoreCase) ||
                    path.Equals("/api/venera/async", StringComparison.OrdinalIgnoreCase))
                {
                    string reqBody;
                    using (StreamReader sr = new StreamReader(req.InputStream, req.ContentEncoding ?? Encoding.UTF8)) reqBody = sr.ReadToEnd();
                    string outJson = HandleVeneraMessageJson(reqBody);
                    WriteRawJson(resp, outJson);
                    return;
                }
                if (path.Equals("/venera_host.html", StringComparison.OrdinalIgnoreCase))
                {
                    string html =
                        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><script>\n" +
                        "const bridgeFetch = window.fetch.bind(window);\n" +
                        "window._veneraNative = {\n" +
                        "  dispatch: function(jsonStr) {\n" +
                        "    var xhr = new XMLHttpRequest();\n" +
                        "    xhr.open('POST', '/api/venera/dispatch', false);\n" +
                        "    xhr.setRequestHeader('Content-Type', 'application/json; charset=utf-8');\n" +
                        "    xhr.send(jsonStr);\n" +
                        "    return xhr.responseText || 'null';\n" +
                        "  },\n" +
                        "  dispatchAsync: function(cbId, jsonStr) {\n" +
                        "    bridgeFetch('/api/venera/async', { method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: jsonStr })\n" +
                        "      .then(function(r){ return r.text(); })\n" +
                        "      .then(function(t){ if (window.__veneraResolveAsync) window.__veneraResolveAsync(cbId, t || 'null'); })\n" +
                        "      .catch(function(e){ if (window.__veneraRejectAsync) window.__veneraRejectAsync(cbId, e ? String(e.message || e) : 'Async failed'); });\n" +
                        "  },\n" +
                        "  resolve: function(callId, json) {\n" +
                        "    window.chrome.webview.postMessage(JSON.stringify({ type: 'resolve', callId: callId, result: json }));\n" +
                        "  },\n" +
                        "  reject: function(callId, err) {\n" +
                        "    window.chrome.webview.postMessage(JSON.stringify({ type: 'reject', callId: callId, error: String(err) }));\n" +
                        "  }\n" +
                        "};\n" +
                        "</script><script src=\"/assets/venera_shim.js\"></script></body></html>";
                    byte[] bytes = Encoding.UTF8.GetBytes(html);
                    resp.StatusCode = 200;
                    resp.ContentType = "text/html; charset=utf-8";
                    resp.ContentLength64 = bytes.Length;
                    resp.OutputStream.Write(bytes, 0, bytes.Length);
                    return;
                }
                if (path.StartsWith("/assets/", StringComparison.OrdinalIgnoreCase))
                {
                    string rel = path.Substring("/assets/".Length).Replace('/', Path.DirectorySeparatorChar);
                    string fullRoot = Path.GetFullPath(AssetsDir);
                    string target = Path.GetFullPath(Path.Combine(fullRoot, rel));
                    if (target.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase) && File.Exists(target))
                    {
                        byte[] bytes = File.ReadAllBytes(target);
                        resp.StatusCode = 200;
                        resp.ContentType = target.EndsWith(".js", StringComparison.OrdinalIgnoreCase) ? "application/javascript; charset=utf-8" : "application/octet-stream";
                        resp.ContentLength64 = bytes.Length;
                        resp.OutputStream.Write(bytes, 0, bytes.Length);
                        return;
                    }
                }
                resp.StatusCode = 404;
            }
            catch
            {
                try { resp.StatusCode = 500; } catch { }
            }
            finally
            {
                try { resp.OutputStream.Close(); } catch { }
            }
        }

        private static void WriteJson(HttpListenerResponse resp, object data)
        {
            WriteRawJson(resp, JsonUtil.CreateSerializer().Serialize(data));
        }

        private static void WriteRawJson(HttpListenerResponse resp, string json)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(json ?? "null");
            resp.StatusCode = 200;
            resp.ContentType = "application/json; charset=utf-8";
            resp.ContentLength64 = bytes.Length;
            resp.OutputStream.Write(bytes, 0, bytes.Length);
        }

        private string HandleVeneraMessageJson(string rawJson)
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            Dictionary<string, object> msg = ser.Deserialize<Dictionary<string, object>>(rawJson);
            if (msg == null) return "null";
            string method = JsonUtil.GetStr(msg, "method", "");
            try
            {
                switch (method)
                {
                    case "http":
                        return ExecuteVeneraHttp(msg, false, ser);
                    case "http_bytes":
                        return ExecuteVeneraHttp(msg, true, ser);
                    case "convert":
                        return ExecuteVeneraConvert(msg, ser);
                    case "cookie":
                        return ExecuteVeneraCookie(msg, ser);
                    case "storage":
                        return ExecuteVeneraStorage(msg, ser);
                    case "ui":
                        string fn = JsonUtil.GetStr(msg, "function", "");
                        if (fn == "toast")
                        {
                            string tmsg = JsonUtil.GetStr(msg, "message", "");
                            if (!string.IsNullOrEmpty(tmsg) && OnToast != null) OnToast(tmsg);
                        }
                        else if (fn == "launchUrl")
                        {
                            string u = JsonUtil.GetStr(msg, "url", "");
                            if (u.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || u.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                            {
                                try { Process.Start(new ProcessStartInfo(u) { UseShellExecute = true }); } catch { }
                            }
                        }
                        return "null";
                    default:
                        return "null";
                }
            }
            catch (Exception ex)
            {
                return ser.Serialize(new Dictionary<string, object> { { "error", ex.Message } });
            }
        }

        public string ExecuteVeneraHttp(Dictionary<string, object> msg, bool asBytes, JavaScriptSerializer ser)
        {
            string httpMethod = JsonUtil.GetStr(msg, "http_method", "GET").ToUpperInvariant();
            string urlStr = JsonUtil.GetStr(msg, "url", "");
            if (string.IsNullOrEmpty(urlStr)) throw new ArgumentException("Missing URL");

            object hRaw = null;
            msg.TryGetValue("headers", out hRaw);
            Dictionary<string, object> headersDict = hRaw as Dictionary<string, object>;

            object dataObj = null;
            msg.TryGetValue("data", out dataObj);
            byte[] bodyBytes = ExtractHttpBodyBytes(dataObj, ser);

            HttpWebRequest webReq = (HttpWebRequest)WebRequest.Create(urlStr);
            webReq.Method = httpMethod;
            webReq.Timeout = 20000;
            webReq.ReadWriteTimeout = 25000;
            webReq.AllowAutoRedirect = true;
            webReq.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            webReq.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

            string jarCookie = GetCookieHeaderForUrl(urlStr);
            if (!string.IsNullOrEmpty(jarCookie)) webReq.Headers["Cookie"] = jarCookie;
            if (headersDict != null)
            {
                foreach (KeyValuePair<string, object> kv in headersDict)
                {
                    ApplyHeaderSafe(webReq, kv.Key, kv.Value == null ? "" : Convert.ToString(kv.Value), jarCookie);
                }
            }

            if (bodyBytes != null && (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH" || httpMethod == "DELETE"))
            {
                if (string.IsNullOrEmpty(webReq.ContentType))
                {
                    webReq.ContentType = (dataObj is Dictionary<string, object> || dataObj is ArrayList)
                        ? "application/json; charset=utf-8"
                        : "application/x-www-form-urlencoded";
                }
                webReq.ContentLength = bodyBytes.Length;
                using (Stream reqStream = webReq.GetRequestStream()) reqStream.Write(bodyBytes, 0, bodyBytes.Length);
            }

            int status = 0;
            string responseUrl = urlStr;
            Dictionary<string, object> respHeaders = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            byte[] rawBytes = new byte[0];

            HttpWebResponse webResp = null;
            try { webResp = (HttpWebResponse)webReq.GetResponse(); }
            catch (WebException wex)
            {
                if (wex.Response is HttpWebResponse) webResp = (HttpWebResponse)wex.Response;
                else throw new InvalidOperationException(wex.Message, wex);
            }

            using (webResp)
            {
                status = (int)webResp.StatusCode;
                responseUrl = webResp.ResponseUri.AbsoluteUri;
                List<string> setCookies = new List<string>();
                for (int i = 0; i < webResp.Headers.Count; i++)
                {
                    string hk = webResp.Headers.GetKey(i);
                    string[] hvals = webResp.Headers.GetValues(i);
                    if (string.IsNullOrEmpty(hk) || hvals == null || hvals.Length == 0) continue;
                    if (string.Equals(hk, "Set-Cookie", StringComparison.OrdinalIgnoreCase))
                    {
                        foreach (string sc in hvals) { setCookies.Add(sc); StoreSetCookieHeader(urlStr, sc); }
                    }
                    else
                    {
                        respHeaders[hk.ToLowerInvariant()] = string.Join("; ", hvals);
                    }
                }
                if (setCookies.Count > 0)
                {
                    respHeaders["set-cookie"] = setCookies.ToArray();
                    SaveCookies();
                }

                Stream rs = webResp.GetResponseStream();
                if (rs != null)
                {
                    using (rs)
                    using (MemoryStream ms = new MemoryStream()) { rs.CopyTo(ms); rawBytes = ms.ToArray(); }
                }
            }

            Dictionary<string, object> outObj = new Dictionary<string, object>();
            outObj["status"] = status;
            outObj["url"] = responseUrl;
            outObj["headers"] = respHeaders;
            if (asBytes) outObj["bodyBase64"] = Convert.ToBase64String(rawBytes);
            else outObj["body"] = Encoding.UTF8.GetString(rawBytes);
            return ser.Serialize(outObj);
        }

        private static byte[] ExtractHttpBodyBytes(object dataObj, JavaScriptSerializer ser)
        {
            if (dataObj == null) return null;
            Dictionary<string, object> dict = dataObj as Dictionary<string, object>;
            if (dict != null)
            {
                object abVal = null;
                if (dict.TryGetValue("__ab__", out abVal) && abVal != null) return Convert.FromBase64String(Convert.ToString(abVal));
                return Encoding.UTF8.GetBytes(ser.Serialize(dict));
            }
            ArrayList arr = dataObj as ArrayList;
            if (arr != null) return Encoding.UTF8.GetBytes(ser.Serialize(arr));
            return Encoding.UTF8.GetBytes(Convert.ToString(dataObj));
        }

        private static void ApplyHeaderSafe(HttpWebRequest webReq, string key, string value, string jarCookie)
        {
            if (string.IsNullOrEmpty(key) || key.Equals("Accept-Encoding", StringComparison.OrdinalIgnoreCase)) return;
            string lk = key.ToLowerInvariant();
            if (lk == "content-length" || lk == "connection" || lk == "transfer-encoding" || lk == "expect" || lk == "accept-encoding") return;
            if (lk == "user-agent") { if (!string.IsNullOrEmpty(value)) webReq.UserAgent = value; return; }
            if (lk == "referer") { webReq.Referer = value; return; }
            if (lk == "accept") { webReq.Accept = value; return; }
            if (lk == "content-type") { webReq.ContentType = value; return; }
            if (lk == "host") { if (!string.IsNullOrEmpty(value)) webReq.Host = value; return; }
            if (lk == "cookie")
            {
                if (!string.IsNullOrEmpty(value)) webReq.Headers["Cookie"] = string.IsNullOrEmpty(jarCookie) ? value : (jarCookie + "; " + value);
                return;
            }
            try { webReq.Headers[key] = value; } catch { }
        }

        private string ExecuteVeneraConvert(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            string type = JsonUtil.GetStr(msg, "type", "");
            bool isEncode = JsonUtil.GetBool(msg, "isEncode", true);
            object valObj = null;
            msg.TryGetValue("value", out valObj);
            byte[] inputBytes = ExtractBinaryField(valObj);

            if (type == "gbk")
            {
                Encoding gbk = Encoding.GetEncoding(936);
                if (isEncode) return WrapAb(gbk.GetBytes(valObj == null ? "" : Convert.ToString(valObj)), ser);
                return ser.Serialize(gbk.GetString(inputBytes));
            }
            if (type == "md5" || type == "sha1" || type == "sha256" || type == "sha512")
            {
                HashAlgorithm alg = type == "md5" ? (HashAlgorithm)MD5.Create()
                    : (type == "sha1" ? (HashAlgorithm)SHA1.Create()
                    : (type == "sha512" ? (HashAlgorithm)SHA512.Create() : (HashAlgorithm)SHA256.Create()));
                using (alg) return WrapAb(alg.ComputeHash(inputBytes), ser);
            }
            if (type == "hmac")
            {
                string hashName = JsonUtil.GetStr(msg, "hash", "sha256").ToLowerInvariant();
                object keyObj = null;
                msg.TryGetValue("key", out keyObj);
                byte[] keyBytes = ExtractBinaryField(keyObj);
                HMAC hmac = hashName == "md5" ? (HMAC)new HMACMD5(keyBytes)
                    : (hashName == "sha1" ? (HMAC)new HMACSHA1(keyBytes)
                    : (hashName == "sha512" ? (HMAC)new HMACSHA512(keyBytes) : (HMAC)new HMACSHA256(keyBytes)));
                byte[] macBytes;
                using (hmac) macBytes = hmac.ComputeHash(inputBytes);
                if (JsonUtil.GetBool(msg, "isString", false))
                {
                    StringBuilder sb = new StringBuilder(macBytes.Length * 2);
                    foreach (byte b in macBytes) sb.Append(b.ToString("x2"));
                    return ser.Serialize(sb.ToString());
                }
                return WrapAb(macBytes, ser);
            }
            if (type == "aes-ecb" || type == "aes-cbc")
            {
                object keyObj = null;
                msg.TryGetValue("key", out keyObj);
                byte[] keyBytes = ExtractBinaryField(keyObj);
                byte[] ivBytes = null;
                if (type == "aes-cbc")
                {
                    object ivObj = null;
                    msg.TryGetValue("iv", out ivObj);
                    ivBytes = ExtractBinaryField(ivObj);
                }
                using (AesCryptoServiceProvider aes = new AesCryptoServiceProvider())
                {
                    aes.Mode = type == "aes-ecb" ? CipherMode.ECB : CipherMode.CBC;
                    aes.Padding = PaddingMode.PKCS7;
                    aes.Key = keyBytes;
                    if (ivBytes != null && ivBytes.Length > 0) aes.IV = ivBytes;
                    using (ICryptoTransform dec = aes.CreateDecryptor())
                    {
                        return WrapAb(dec.TransformFinalBlock(inputBytes, 0, inputBytes.Length), ser);
                    }
                }
            }
            return "null";
        }

        private static byte[] ExtractBinaryField(object field)
        {
            if (field == null) return new byte[0];
            Dictionary<string, object> dict = field as Dictionary<string, object>;
            if (dict != null)
            {
                object ab = null;
                if (dict.TryGetValue("__ab__", out ab) && ab != null) return Convert.FromBase64String(Convert.ToString(ab));
                return new byte[0];
            }
            string s = field as string;
            return s != null ? Encoding.UTF8.GetBytes(s) : new byte[0];
        }

        private static string WrapAb(byte[] bytes, JavaScriptSerializer ser)
        {
            return ser.Serialize(new Dictionary<string, object> { { "__ab__", Convert.ToBase64String(bytes) } });
        }

        private string ExecuteVeneraCookie(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            string fn = JsonUtil.GetStr(msg, "function", "");
            string url = JsonUtil.GetStr(msg, "url", "");
            Uri uri = null;
            try { uri = new Uri(url); } catch { }
            string host = uri != null ? uri.Host : "";

            lock (_cookieLock)
            {
                if (fn == "set")
                {
                    object arrObj = null;
                    msg.TryGetValue("cookies", out arrObj);
                    ArrayList arr = arrObj as ArrayList;
                    if (arr != null)
                    {
                        foreach (object item in arr)
                        {
                            Dictionary<string, object> c = item as Dictionary<string, object>;
                            if (c == null) continue;
                            string name = JsonUtil.GetStr(c, "name", "");
                            if (string.IsNullOrEmpty(name)) continue;
                            string val = JsonUtil.GetStr(c, "value", "");
                            string domain = JsonUtil.GetStr(c, "domain", host);
                            string path = JsonUtil.GetStr(c, "path", "/");
                            UpsertCookie(name, val, string.IsNullOrEmpty(domain) ? host : domain, string.IsNullOrEmpty(path) ? "/" : path);
                        }
                        SaveCookies();
                    }
                    return "null";
                }
                if (fn == "get")
                {
                    List<Dictionary<string, object>> result = new List<Dictionary<string, object>>();
                    foreach (CookieEntry ce in _cookies)
                    {
                        if (CookieMatchesHost(ce.Domain, host))
                        {
                            result.Add(new Dictionary<string, object>
                            {
                                { "name", ce.Name }, { "value", ce.Value }, { "domain", ce.Domain }, { "path", ce.Path }
                            });
                        }
                    }
                    return ser.Serialize(result);
                }
                if (fn == "delete")
                {
                    if (string.IsNullOrEmpty(host)) _cookies.Clear();
                    else _cookies.RemoveAll(delegate (CookieEntry ce) { return CookieMatchesHost(ce.Domain, host); });
                    SaveCookies();
                    return "null";
                }
            }
            return "null";
        }

        private string ExecuteVeneraStorage(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            string fn = JsonUtil.GetStr(msg, "function", "");
            string sourceKey = JsonUtil.GetStr(msg, "sourceKey", "");
            string key = JsonUtil.GetStr(msg, "key", "");
            object valObj = null;
            msg.TryGetValue("value", out valObj);

            lock (_veneraLock)
            {
                Dictionary<string, object> bucket = GetOrCreateStorageBucket(fn.EndsWith("Setting", StringComparison.Ordinal) ? "settings" : "data", sourceKey);
                if (fn == "loadSetting" || fn == "loadData")
                {
                    object found = null;
                    if (!bucket.TryGetValue(key, out found) || found == null) return "null";
                    return ser.Serialize(found);
                }
                if (fn == "saveSetting" || fn == "saveData")
                {
                    if (valObj == null) bucket.Remove(key);
                    else bucket[key] = valObj;
                    SaveVeneraStorage();
                    return "null";
                }
                if (fn == "deleteData")
                {
                    bucket.Remove(key);
                    SaveVeneraStorage();
                    return "null";
                }
            }
            return "null";
        }

        private Dictionary<string, object> GetOrCreateStorageBucket(string section, string sourceKey)
        {
            object secObj = null;
            if (!_veneraStorage.TryGetValue(section, out secObj) || !(secObj is Dictionary<string, object>))
            {
                secObj = new Dictionary<string, object>(StringComparer.Ordinal);
                _veneraStorage[section] = secObj;
            }
            Dictionary<string, object> secDict = (Dictionary<string, object>)secObj;
            object srcObj = null;
            if (!secDict.TryGetValue(sourceKey, out srcObj) || !(srcObj is Dictionary<string, object>))
            {
                srcObj = new Dictionary<string, object>(StringComparer.Ordinal);
                secDict[sourceKey] = srcObj;
            }
            return (Dictionary<string, object>)srcObj;
        }

        public List<SourceItem> LoadBuiltinSources()
        {
            List<SourceItem> list = new List<SourceItem>();
            string manifestPath = Path.Combine(AssetsDir, "sources", "builtin_sources.json");
            if (!File.Exists(manifestPath)) return list;

            string raw = File.ReadAllText(manifestPath, Encoding.UTF8);
            ArrayList arr = JsonUtil.CreateSerializer().Deserialize<ArrayList>(raw);
            if (arr == null) return list;

            foreach (object obj in arr)
            {
                Dictionary<string, object> d = obj as Dictionary<string, object>;
                if (d == null) continue;
                string key = JsonUtil.GetStr(d, "key", "");
                if (string.IsNullOrEmpty(key)) continue;
                list.Add(new SourceItem
                {
                    Key = key,
                    Name = JsonUtil.GetStr(d, "name", key),
                    Version = JsonUtil.GetStr(d, "version", "1.0.0"),
                    FileName = JsonUtil.GetStr(d, "fileName", "sources/" + key + ".js"),
                    Url = JsonUtil.GetStr(d, "url", ""),
                    Enabled = true,
                    CustomScript = ""
                });
            }
            return list;
        }

        public string ReadSourceScript(SourceItem item)
        {
            if (item == null) return "";
            if (!string.IsNullOrEmpty(item.CustomScript)) return item.CustomScript;
            if (string.IsNullOrEmpty(item.FileName)) return "";
            string rel = item.FileName.TrimStart('/').Replace('/', Path.DirectorySeparatorChar);
            string path = Path.Combine(AssetsDir, rel);
            return File.Exists(path) ? File.ReadAllText(path, Encoding.UTF8) : "";
        }

        public BitmapImage LoadAssetImage(string relativePath, int decodeWidth)
        {
            string full = Path.Combine(AssetsDir, relativePath.Replace('/', Path.DirectorySeparatorChar));
            if (!File.Exists(full)) return null;
            return DecodeFrozenImage(File.ReadAllBytes(full), decodeWidth);
        }

        public async Task<BitmapImage> LoadComicImageAsync(string url, Dictionary<string, string> headers, int decodeWidth)
        {
            if (string.IsNullOrEmpty(url)) return null;
            string memKey = url + "|w=" + decodeWidth;
            lock (_memoryThumbCache)
            {
                BitmapImage cachedMem = null;
                if (decodeWidth > 0 && _memoryThumbCache.TryGetValue(memKey, out cachedMem)) return cachedMem;
            }

            string diskKey = ComputeSha256Hex(url + "|" + SerializeHeadersKey(headers));
            string diskPath = Path.Combine(_imageCacheDir, diskKey);
            byte[] rawBytes = null;
            if (File.Exists(diskPath))
            {
                try { rawBytes = File.ReadAllBytes(diskPath); } catch { rawBytes = null; }
            }

            if (rawBytes == null || rawBytes.Length < 32)
            {
                await _imgFetchLimit.WaitAsync().ConfigureAwait(false);
                try
                {
                    rawBytes = await Task.Run<byte[]>(delegate ()
                    {
                        HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
                        req.Method = "GET";
                        req.Timeout = 16000;
                        req.AllowAutoRedirect = true;
                        req.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
                        req.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

                        string jarCookie = GetCookieHeaderForUrl(url);
                        if (!string.IsNullOrEmpty(jarCookie)) req.Headers["Cookie"] = jarCookie;
                        if (headers != null)
                        {
                            foreach (KeyValuePair<string, string> kv in headers)
                            {
                                ApplyHeaderSafe(req, kv.Key, kv.Value, jarCookie);
                            }
                        }

                        using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                        using (Stream rs = resp.GetResponseStream())
                        using (MemoryStream ms = new MemoryStream())
                        {
                            if (rs != null) rs.CopyTo(ms);
                            return ms.ToArray();
                        }
                    }).ConfigureAwait(false);

                    if (rawBytes != null && rawBytes.Length > 32)
                    {
                        try { File.WriteAllBytes(diskPath, rawBytes); } catch { }
                    }
                }
                catch
                {
                    rawBytes = null;
                }
                finally
                {
                    _imgFetchLimit.Release();
                }
            }

            if (rawBytes == null || rawBytes.Length < 16) return null;
            BitmapImage bmp = await Task.Run<BitmapImage>(delegate () { return DecodeFrozenImage(rawBytes, decodeWidth); }).ConfigureAwait(false);
            if (bmp != null && decodeWidth > 0)
            {
                lock (_memoryThumbCache)
                {
                    if (_memoryThumbCache.Count > 360) _memoryThumbCache.Clear();
                    _memoryThumbCache[memKey] = bmp;
                }
            }
            return bmp;
        }

        public async Task<BitmapImage> LoadSourcePageBitmapAsync(string key, Func<Task<byte[]>> loadBytes)
        {
            string path = Path.Combine(_imageCacheDir, ComputeSha256Hex(key));
            if (File.Exists(path))
            {
                BitmapImage cached = await Task.Run(() => DecodeFrozenImage(File.ReadAllBytes(path), 0));
                if (cached != null) return cached;
            }

            await _imgFetchLimit.WaitAsync();
            try
            {
                byte[] bytes = await loadBytes();
                BitmapImage bitmap = await Task.Run(() => DecodeFrozenImage(bytes, 0));
                if (bitmap != null && !File.Exists(path))
                {
                    try
                    {
                        using (FileStream file = new FileStream(path, FileMode.CreateNew))
                            file.Write(bytes, 0, bytes.Length);
                    }
                    catch (IOException) { }
                }
                return bitmap;
            }
            finally { _imgFetchLimit.Release(); }
        }

        public async Task<BitmapImage> LoadLocalPageBitmapAsync(string localComicId, int pageIndex, int decodeWidth)
        {
            return await Task.Run<BitmapImage>(delegate ()
            {
                LocalComicRecord rec = null;
                lock (_warehouseLock) { _localComicsById.TryGetValue(localComicId, out rec); }
                if (rec == null) return null;
                List<string> entries = GetLocalPageEntries(rec);
                if (pageIndex < 0 || pageIndex >= entries.Count) return null;

                byte[] bytes = null;
                string entry = entries[pageIndex];
                if (rec.Format == "FOLDER")
                {
                    if (File.Exists(entry)) bytes = File.ReadAllBytes(entry);
                }
                else if (File.Exists(rec.Path))
                {
                    using (ZipArchive zip = ZipFile.OpenRead(rec.Path))
                    {
                        ZipArchiveEntry ze = zip.GetEntry(entry);
                        if (ze != null)
                        {
                            using (Stream s = ze.Open())
                            using (MemoryStream ms = new MemoryStream())
                            {
                                s.CopyTo(ms);
                                bytes = ms.ToArray();
                            }
                        }
                    }
                }
                return bytes != null ? DecodeFrozenImage(bytes, decodeWidth) : null;
            }).ConfigureAwait(false);
        }

        public int GetLocalPageCount(string localComicId)
        {
            lock (_warehouseLock)
            {
                LocalComicRecord rec = null;
                if (_localComicsById.TryGetValue(localComicId, out rec))
                {
                    return GetLocalPageEntries(rec).Count;
                }
            }
            return 0;
        }

        private static BitmapImage DecodeFrozenImage(byte[] bytes, int decodeWidth)
        {
            try
            {
                using (MemoryStream ms = new MemoryStream(bytes))
                {
                    BitmapImage bmp = new BitmapImage();
                    bmp.BeginInit();
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
                    if (decodeWidth > 0) bmp.DecodePixelWidth = decodeWidth;
                    bmp.StreamSource = ms;
                    bmp.EndInit();
                    bmp.Freeze();
                    return bmp;
                }
            }
            catch
            {
                return null;
            }
        }

        private static string SerializeHeadersKey(Dictionary<string, string> h)
        {
            if (h == null || h.Count == 0) return "";
            StringBuilder sb = new StringBuilder();
            foreach (KeyValuePair<string, string> kv in h)
            {
                sb.Append(kv.Key).Append('=').Append(kv.Value).Append(';');
            }
            return sb.ToString();
        }

        public int ClearImageCache()
        {
            int count = 0;
            lock (_memoryThumbCache) { _memoryThumbCache.Clear(); }
            if (Directory.Exists(_imageCacheDir))
            {
                foreach (string f in Directory.GetFiles(_imageCacheDir))
                {
                    try { File.Delete(f); count++; } catch { }
                }
            }
            return count;
        }

        public List<ComicCardModel> GetLocalComicCards()
        {
            lock (_warehouseLock)
            {
                List<ComicCardModel> outList = new List<ComicCardModel>();
                foreach (LocalComicRecord rec in _localComicsById.Values)
                {
                    outList.Add(new ComicCardModel
                    {
                        Uid = rec.Id,
                        Id = rec.Id,
                        IsLocal = true,
                        Title = rec.Title,
                        SubTitle = rec.Format + " · " + rec.PageCount + " 页",
                        Badge = rec.Format == "ARCHIVE" ? "压缩包" : "本地",
                        CoverUrl = "",
                        Headers = new Dictionary<string, string>()
                    });
                }
                return outList;
            }
        }

        public void AddWarehousePath(string path)
        {
            lock (_warehouseLock)
            {
                foreach (string existing in WarehousePaths)
                {
                    if (string.Equals(existing, path, StringComparison.OrdinalIgnoreCase)) return;
                }
                WarehousePaths.Add(path);
                SaveWarehousePaths();
                RescanLocalLibraryLocked();
            }
        }

        public void RemoveWarehousePath(string path)
        {
            lock (_warehouseLock)
            {
                WarehousePaths.RemoveAll(delegate (string p) { return string.Equals(p, path, StringComparison.OrdinalIgnoreCase); });
                SaveWarehousePaths();
                RescanLocalLibraryLocked();
            }
        }

        public void RescanLocalLibrary()
        {
            lock (_warehouseLock) { RescanLocalLibraryLocked(); }
        }

        private void RescanLocalLibraryLocked()
        {
            _localComicsById.Clear();
            foreach (string root in WarehousePaths)
            {
                if (File.Exists(root) && IsArchiveFile(root)) TryIndexArchive(root);
                else if (Directory.Exists(root)) ScanDirectoryForComics(root, 0);
            }
            SaveLocalLibrary();
        }

        private void ScanDirectoryForComics(string dir, int depth)
        {
            if (depth > 3) return;
            try
            {
                string[] files = Directory.GetFiles(dir);
                int imgCount = 0;
                foreach (string f in files)
                {
                    if (IsImageFile(f)) imgCount++;
                    else if (IsArchiveFile(f)) TryIndexArchive(f);
                }
                if (imgCount > 0)
                {
                    string full = Path.GetFullPath(dir);
                    string id = "local_" + ComputeSha256Hex("FOLDER|" + full).Substring(0, 16);
                    string title = Path.GetFileName(dir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
                    _localComicsById[id] = new LocalComicRecord
                    {
                        Id = id,
                        Title = string.IsNullOrEmpty(title) ? dir : title,
                        Format = "FOLDER",
                        Path = full,
                        PageCount = imgCount
                    };
                }
                foreach (string sub in Directory.GetDirectories(dir))
                {
                    if (Path.GetFileName(sub).StartsWith(".")) continue;
                    ScanDirectoryForComics(sub, depth + 1);
                }
            }
            catch { }
        }

        private void TryIndexArchive(string archivePath)
        {
            try
            {
                int pageCount = 0;
                using (ZipArchive zip = ZipFile.OpenRead(archivePath))
                {
                    foreach (ZipArchiveEntry entry in zip.Entries)
                    {
                        if (!string.IsNullOrEmpty(entry.Name) && IsImageFile(entry.FullName)) pageCount++;
                    }
                }
                if (pageCount > 0)
                {
                    string full = Path.GetFullPath(archivePath);
                    string id = "local_" + ComputeSha256Hex("ARCHIVE|" + full).Substring(0, 16);
                    _localComicsById[id] = new LocalComicRecord
                    {
                        Id = id,
                        Title = Path.GetFileNameWithoutExtension(full),
                        Format = "ARCHIVE",
                        Path = full,
                        PageCount = pageCount
                    };
                }
            }
            catch { }
        }

        private List<string> GetLocalPageEntries(LocalComicRecord rec)
        {
            List<string> list = new List<string>();
            if (rec.Format == "FOLDER" && Directory.Exists(rec.Path))
            {
                foreach (string f in Directory.GetFiles(rec.Path)) if (IsImageFile(f)) list.Add(f);
                list.Sort(NaturalComparer.Instance);
            }
            else if (File.Exists(rec.Path))
            {
                using (ZipArchive zip = ZipFile.OpenRead(rec.Path))
                {
                    foreach (ZipArchiveEntry entry in zip.Entries)
                    {
                        if (!string.IsNullOrEmpty(entry.Name) && IsImageFile(entry.FullName)) list.Add(entry.FullName);
                    }
                }
                list.Sort(NaturalComparer.Instance);
            }
            return list;
        }

        public string TestWebDav(string url, string user, string pass, out bool ok)
        {
            ok = false;
            if (string.IsNullOrWhiteSpace(url)) return "请输入 WebDAV 服务器地址";
            try
            {
                HttpWebRequest webReq = (HttpWebRequest)WebRequest.Create(url);
                webReq.Method = "PROPFIND";
                webReq.Headers["Depth"] = "0";
                webReq.Timeout = 10000;
                if (!string.IsNullOrEmpty(user) || !string.IsNullOrEmpty(pass))
                {
                    webReq.Headers["Authorization"] = "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + pass));
                }
                using (HttpWebResponse webResp = (HttpWebResponse)webReq.GetResponse())
                {
                    int code = (int)webResp.StatusCode;
                    ok = code >= 200 && code < 400;
                    return "连接成功 (HTTP " + code + ")";
                }
            }
            catch (WebException wex)
            {
                return "连接失败: " + wex.Message;
            }
        }

        private void LoadPersistentStores()
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            if (File.Exists(_veneraStorageFile))
            {
                try
                {
                    Dictionary<string, object> s = ser.Deserialize<Dictionary<string, object>>(File.ReadAllText(_veneraStorageFile, Encoding.UTF8));
                    if (s != null) _veneraStorage = s;
                }
                catch { }
            }
            if (File.Exists(_cookiesFile))
            {
                try
                {
                    List<CookieEntry> c = ser.Deserialize<List<CookieEntry>>(File.ReadAllText(_cookiesFile, Encoding.UTF8));
                    if (c != null) _cookies = c;
                }
                catch { }
            }
            if (File.Exists(_warehouseFile))
            {
                try
                {
                    List<string> w = ser.Deserialize<List<string>>(File.ReadAllText(_warehouseFile, Encoding.UTF8));
                    if (w != null) WarehousePaths = w;
                }
                catch { }
            }
            if (File.Exists(_localLibFile))
            {
                try
                {
                    List<LocalComicRecord> lib = ser.Deserialize<List<LocalComicRecord>>(File.ReadAllText(_localLibFile, Encoding.UTF8));
                    if (lib != null) foreach (LocalComicRecord r in lib) if (!string.IsNullOrEmpty(r.Id)) _localComicsById[r.Id] = r;
                }
                catch { }
            }
        }

        private void LoadStateAndSources()
        {
            List<SourceItem> builtin = LoadBuiltinSources();
            Dictionary<string, SourceItem> savedSources = new Dictionary<string, SourceItem>(StringComparer.Ordinal);
            List<SourceItem> savedList = new List<SourceItem>();

            if (File.Exists(_stateFile))
            {
                try
                {
                    JavaScriptSerializer ser = JsonUtil.CreateSerializer();
                    Dictionary<string, object> st = ser.Deserialize<Dictionary<string, object>>(File.ReadAllText(_stateFile, Encoding.UTF8));
                    if (st != null)
                    {
                        ThemeMode = JsonUtil.GetStr(st, "theme", "dark");
                        GridCompact = JsonUtil.GetBool(st, "gridCompact", false);
                        ActiveHomeMode = JsonUtil.GetStr(st, "activeHomeMode", "zaimanhua");
                        LastSource = JsonUtil.GetStr(st, "lastSource", ActiveHomeMode);
                        GlassMaterial = JsonUtil.GetBool(st, "glassMaterial", false);
                        WallpaperEnabled = JsonUtil.GetBool(st, "wallpaperEnabled", false);
                        TranslationServer = JsonUtil.GetStr(st, "translationServer", "");
                        TranslationConfig = JsonUtil.GetStr(st, "translationConfig", TranslationConfig);
                        TranslationSession = JsonUtil.GetStr(st, "translationSession", "");
                        TranslationPrefetchPages = Math.Max(0, Math.Min(5, JsonUtil.GetInt(st, "translationPrefetchPages", 0)));
                        FeedbackProtectedToken = JsonUtil.GetStr(st, "feedbackGithubToken", "");
                        int modelMode = JsonUtil.GetInt(st, "translationModelMode", string.IsNullOrWhiteSpace(TranslationServer) ? (int)TranslationMode.Direct : 0);
                        TranslationModel = new TranslationModel
                        {
                            Mode = Enum.IsDefined(typeof(TranslationMode), modelMode) ? (TranslationMode)modelMode : TranslationMode.Direct,
                            ApiBase = JsonUtil.GetStr(st, "translationApiBase", ""),
                            ModelId = JsonUtil.GetStr(st, "translationModelId", ""),
                            ProtectedKey = JsonUtil.GetStr(st, "translationProtectedKey", ""),
                            ReasoningEffort = JsonUtil.GetStr(st, "translationReasoningEffort", ""),
                            SendImage = JsonUtil.GetBool(st, "translationSendImage", true)
                        };
                        ActiveCollectionId = JsonUtil.GetStr(st, "activeCollectionId", "col_default");

                        object wdObj = null;
                        if (st.TryGetValue("webdav", out wdObj) && wdObj is Dictionary<string, object>)
                        {
                            Dictionary<string, object> wd = (Dictionary<string, object>)wdObj;
                            WebDavUrl = JsonUtil.GetStr(wd, "url", "");
                            WebDavUser = JsonUtil.GetStr(wd, "username", "");
                            WebDavPass = JsonUtil.GetStr(wd, "password", "");
                        }
                        object rdObj = null;
                        if (st.TryGetValue("readerDefaults", out rdObj) && rdObj is Dictionary<string, object>)
                        {
                            ReaderDefaults = DeserializePref((Dictionary<string, object>)rdObj);
                        }
                        object cpObj = null;
                        if (st.TryGetValue("comicPrefs", out cpObj) && cpObj is Dictionary<string, object>)
                        {
                            foreach (KeyValuePair<string, object> kv in (Dictionary<string, object>)cpObj)
                            {
                                if (kv.Value is Dictionary<string, object>) ComicPrefs[kv.Key] = DeserializePref((Dictionary<string, object>)kv.Value);
                            }
                        }

                        Favorites = DeserializeCardsList(st, "favorites");
                        History = DeserializeCardsList(st, "history");

                        object colObj = null;
                        if (st.TryGetValue("collections", out colObj) && colObj is ArrayList)
                        {
                            foreach (object item in (ArrayList)colObj)
                            {
                                Dictionary<string, object> cd = item as Dictionary<string, object>;
                                if (cd == null) continue;
                                Collections.Add(new CollectionModel
                                {
                                    Id = JsonUtil.GetStr(cd, "id", "col_" + Collections.Count),
                                    Name = JsonUtil.GetStr(cd, "name", "作品集"),
                                    Pin = JsonUtil.GetStr(cd, "pin", ""),
                                    HideFromAll = JsonUtil.GetBool(cd, "hideFromAll", false),
                                    Items = DeserializeCardsList(cd, "items")
                                });
                            }
                        }

                        if (!JsonUtil.GetBool(st, "splitShelves", false))
                        {
                            foreach (CollectionModel shelf in Collections)
                                foreach (ComicCardModel comic in shelf.Items)
                                    if (!comic.IsLocal && !Favorites.Exists(favorite => favorite.Uid == comic.Uid))
                                        Favorites.Add(comic);
                        }

                        object scObj = null;
                        if (st.TryGetValue("sourceConfigs", out scObj) && scObj is ArrayList)
                        {
                            foreach (object item in (ArrayList)scObj)
                            {
                                Dictionary<string, object> d = item as Dictionary<string, object>;
                                if (d == null) continue;
                                string k = JsonUtil.GetStr(d, "key", "");
                                if (string.IsNullOrEmpty(k)) continue;
                                SourceItem si = new SourceItem
                                {
                                    Key = k,
                                    Name = JsonUtil.GetStr(d, "name", k),
                                    Version = JsonUtil.GetStr(d, "version", "1.0.0"),
                                    FileName = JsonUtil.GetStr(d, "fileName", ""),
                                    Url = JsonUtil.GetStr(d, "url", ""),
                                    Enabled = JsonUtil.GetBool(d, "enabled", true),
                                    CustomScript = JsonUtil.GetStr(d, "customScript", "")
                                };
                                savedSources[k] = si;
                                savedList.Add(si);
                            }
                        }
                    }
                }
                catch { }
            }

            if (Collections.Count == 0)
            {
                Collections.Add(new CollectionModel { Id = "col_default", Name = "默认作品集", Pin = "", HideFromAll = false, Items = new List<ComicCardModel>() });
                ActiveCollectionId = "col_all";
            }

            HashSet<string> seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (SourceItem b in builtin)
            {
                if (!seen.Add(b.Key)) continue;
                SourceItem prev = null;
                if (savedSources.TryGetValue(b.Key, out prev))
                {
                    b.Enabled = prev.Enabled;
                    if (!string.IsNullOrEmpty(prev.CustomScript))
                    {
                        b.CustomScript = prev.CustomScript;
                        b.Name = prev.Name;
                        b.Version = prev.Version;
                    }
                }
                Sources.Add(b);
            }
            foreach (SourceItem s in savedList)
            {
                if (!string.IsNullOrWhiteSpace(s.CustomScript) && seen.Add(s.Key)) Sources.Add(s);
            }
            if (!Sources.Exists(source => source.Key == ActiveHomeMode) &&
                ActiveHomeMode != "local" && ActiveHomeMode != "fav" && ActiveHomeMode != "history")
            {
                SourceItem first = Sources.Find(source => source.Enabled);
                ActiveHomeMode = first == null ? "local" : first.Key;
            }
        }

        public void SaveState()
        {
            lock (_stateLock)
            {
                try
                {
                    Dictionary<string, object> root = new Dictionary<string, object>();
                    root["theme"] = ThemeMode;
                    root["gridCompact"] = GridCompact;
                    root["activeHomeMode"] = ActiveHomeMode;
                    root["lastSource"] = LastSource;
                    root["splitShelves"] = true;
                    root["glassMaterial"] = GlassMaterial;
                    root["wallpaperEnabled"] = WallpaperEnabled;
                    root["translationServer"] = TranslationServer;
                    root["translationConfig"] = TranslationConfig;
                    root["translationSession"] = TranslationSession ?? "";
                    root["translationModelMode"] = (int)TranslationModel.Mode;
                    root["translationApiBase"] = TranslationModel.ApiBase ?? "";
                    root["translationModelId"] = TranslationModel.ModelId ?? "";
                    root["translationProtectedKey"] = TranslationModel.ProtectedKey ?? "";
                    root["translationReasoningEffort"] = TranslationModel.ReasoningEffort ?? "";
                    root["translationSendImage"] = TranslationModel.SendImage;
                    root["translationPrefetchPages"] = TranslationPrefetchPages;
                    root["feedbackGithubToken"] = FeedbackProtectedToken ?? "";
                    root["activeCollectionId"] = ActiveCollectionId;
                    root["webdav"] = new Dictionary<string, object> { { "url", WebDavUrl ?? "" }, { "username", WebDavUser ?? "" }, { "password", WebDavPass ?? "" } };
                    root["readerDefaults"] = SerializePref(ReaderDefaults);

                    Dictionary<string, object> cp = new Dictionary<string, object>();
                    foreach (KeyValuePair<string, ReaderPrefModel> kv in ComicPrefs) cp[kv.Key] = SerializePref(kv.Value);
                    root["comicPrefs"] = cp;

                    root["favorites"] = SerializeCardsList(Favorites);
                    root["history"] = SerializeCardsList(History);

                    List<Dictionary<string, object>> cols = new List<Dictionary<string, object>>();
                    foreach (CollectionModel c in Collections)
                    {
                        cols.Add(new Dictionary<string, object>
                        {
                            { "id", c.Id }, { "name", c.Name }, { "pin", c.Pin ?? "" }, { "hideFromAll", c.HideFromAll },
                            { "items", SerializeCardsList(c.Items) }
                        });
                    }
                    root["collections"] = cols;

                    List<Dictionary<string, object>> scs = new List<Dictionary<string, object>>();
                    foreach (SourceItem s in Sources)
                    {
                        scs.Add(new Dictionary<string, object>
                        {
                            { "key", s.Key }, { "name", s.Name }, { "version", s.Version }, { "fileName", s.FileName ?? "" },
                            { "url", s.Url ?? "" }, { "enabled", s.Enabled }, { "customScript", s.CustomScript ?? "" }
                        });
                    }
                    root["sourceConfigs"] = scs;
                    string pending = _stateFile + ".tmp";
                    File.WriteAllText(pending, JsonUtil.CreateSerializer().Serialize(root), Encoding.UTF8);
                    if (File.Exists(_stateFile)) File.Replace(pending, _stateFile, null);
                    else File.Move(pending, _stateFile);
                }
                catch { }
            }
        }

        private static List<ComicCardModel> DeserializeCardsList(Dictionary<string, object> parent, string key)
        {
            List<ComicCardModel> list = new List<ComicCardModel>();
            object raw = null;
            if (!parent.TryGetValue(key, out raw) || !(raw is ArrayList)) return list;
            foreach (object obj in (ArrayList)raw)
            {
                Dictionary<string, object> d = obj as Dictionary<string, object>;
                if (d == null) continue;
                ComicCardModel m = new ComicCardModel
                {
                    Uid = JsonUtil.GetStr(d, "uid", ""),
                    Id = JsonUtil.GetStr(d, "id", ""),
                    SourceKey = JsonUtil.GetStr(d, "sourceKey", ""),
                    IsLocal = JsonUtil.GetBool(d, "isLocal", false),
                    Title = JsonUtil.GetStr(d, "title", ""),
                    SubTitle = JsonUtil.GetStr(d, "subTitle", ""),
                    CoverUrl = JsonUtil.GetStr(d, "cover", ""),
                    Badge = JsonUtil.GetStr(d, "badge", ""),
                    Description = JsonUtil.GetStr(d, "description", ""),
                    Tags = ComicSourceMetadata.ReadTags(d),
                    OfficialUrl = JsonUtil.GetStr(d, "officialUrl", ""),
                    Headers = new Dictionary<string, string>()
                };
                object hObj = null;
                if (d.TryGetValue("headers", out hObj) && hObj is Dictionary<string, object>)
                {
                    foreach (KeyValuePair<string, object> kv in (Dictionary<string, object>)hObj)
                    {
                        if (kv.Value != null) m.Headers[kv.Key] = Convert.ToString(kv.Value);
                    }
                }
                list.Add(m);
            }
            return list;
        }

        private static List<Dictionary<string, object>> SerializeCardsList(List<ComicCardModel> list)
        {
            List<Dictionary<string, object>> res = new List<Dictionary<string, object>>();
            if (list == null) return res;
            foreach (ComicCardModel m in list)
            {
                res.Add(new Dictionary<string, object>
                {
                    { "uid", m.Uid }, { "id", m.Id }, { "sourceKey", m.SourceKey ?? "" }, { "isLocal", m.IsLocal },
                    { "title", m.Title }, { "subTitle", m.SubTitle ?? "" }, { "cover", m.CoverUrl ?? "" },
                    { "badge", m.Badge ?? "" }, { "description", m.Description ?? "" }, { "tags", m.Tags ?? new List<string>() },
                    { "officialUrl", m.OfficialUrl ?? "" }, { "headers", m.Headers ?? new Dictionary<string, string>() }
                });
            }
            return res;
        }

        private static ReaderPrefModel DeserializePref(Dictionary<string, object> d)
        {
            return new ReaderPrefModel
            {
                Mode = JsonUtil.GetStr(d, "mode", "rtl"),
                Spread = JsonUtil.GetStr(d, "spread", "double"),
                Brightness = JsonUtil.GetInt(d, "brightness", 100),
                Quality = JsonUtil.GetStr(d, "quality", "ultra"),
                TrimMargins = JsonUtil.GetBool(d, "trimMargins", false),
                DoubleTapZoom = JsonUtil.GetBool(d, "doubleTapZoom", true),
                ShowCommentPage = JsonUtil.GetBool(d, "showCommentPage", false),
                ShowPageNumber = JsonUtil.GetBool(d, "showPageNumber", true),
                ApplyGlobal = JsonUtil.GetBool(d, "applyGlobal", false),
                WheelPageTurn = JsonUtil.GetBool(d, "wheelPageTurn", true),
                PageTurnAnim = JsonUtil.GetStr(d, "pageTurnAnim", "slide"),
                PrefetchPages = Math.Max(0, Math.Min(8, JsonUtil.GetInt(d, "prefetchPages", 4))),
                SplitAllPages = JsonUtil.GetBool(d, "splitAllPages", false),
                SpreadGap = JsonUtil.GetBool(d, "spreadGap", true),
                LastChapterIdx = JsonUtil.GetInt(d, "lastChapterIdx", 0),
                LastPage = JsonUtil.GetInt(d, "lastPage", 0)
            };
        }

        private static Dictionary<string, object> SerializePref(ReaderPrefModel p)
        {
            return new Dictionary<string, object>
            {
                { "mode", p.Mode }, { "spread", p.Spread }, { "brightness", p.Brightness },
                { "quality", p.Quality }, { "trimMargins", p.TrimMargins }, { "doubleTapZoom", p.DoubleTapZoom },
                { "showCommentPage", p.ShowCommentPage }, { "showPageNumber", p.ShowPageNumber },
                { "applyGlobal", p.ApplyGlobal }, { "wheelPageTurn", p.WheelPageTurn },
                { "pageTurnAnim", p.PageTurnAnim ?? "slide" }, { "prefetchPages", p.PrefetchPages },
                { "splitAllPages", p.SplitAllPages }, { "spreadGap", p.SpreadGap },
                { "lastChapterIdx", p.LastChapterIdx }, { "lastPage", p.LastPage }
            };
        }

        private void SaveVeneraStorage() { try { File.WriteAllText(_veneraStorageFile, JsonUtil.CreateSerializer().Serialize(_veneraStorage), Encoding.UTF8); } catch { } }
        private void SaveCookies() { try { File.WriteAllText(_cookiesFile, JsonUtil.CreateSerializer().Serialize(_cookies), Encoding.UTF8); } catch { } }
        private void SaveWarehousePaths() { try { File.WriteAllText(_warehouseFile, JsonUtil.CreateSerializer().Serialize(WarehousePaths), Encoding.UTF8); } catch { } }
        private void SaveLocalLibrary() { try { File.WriteAllText(_localLibFile, JsonUtil.CreateSerializer().Serialize(new List<LocalComicRecord>(_localComicsById.Values)), Encoding.UTF8); } catch { } }

        private string GetCookieHeaderForUrl(string url)
        {
            Uri uri = null;
            try { uri = new Uri(url); } catch { return ""; }
            string host = uri.Host;
            lock (_cookieLock)
            {
                List<string> pairs = new List<string>();
                foreach (CookieEntry ce in _cookies) if (CookieMatchesHost(ce.Domain, host)) pairs.Add(ce.Name + "=" + ce.Value);
                return string.Join("; ", pairs.ToArray());
            }
        }

        private void StoreSetCookieHeader(string url, string sc)
        {
            if (string.IsNullOrEmpty(sc)) return;
            Uri uri = null;
            try { uri = new Uri(url); } catch { }
            string defaultHost = uri != null ? uri.Host : "";
            string[] parts = sc.Split(';');
            if (parts.Length == 0) return;
            int eq = parts[0].IndexOf('=');
            if (eq <= 0) return;
            string name = parts[0].Substring(0, eq).Trim();
            string value = parts[0].Substring(eq + 1).Trim();
            string domain = defaultHost;
            string path = "/";
            for (int i = 1; i < parts.Length; i++)
            {
                string attr = parts[i].Trim();
                if (attr.StartsWith("domain=", StringComparison.OrdinalIgnoreCase)) domain = attr.Substring(7).Trim().TrimStart('.');
                else if (attr.StartsWith("path=", StringComparison.OrdinalIgnoreCase)) path = attr.Substring(5).Trim();
            }
            lock (_cookieLock) { UpsertCookie(name, value, domain, path); }
        }

        private void UpsertCookie(string name, string value, string domain, string path)
        {
            for (int i = 0; i < _cookies.Count; i++)
            {
                if (string.Equals(_cookies[i].Name, name, StringComparison.Ordinal) &&
                    string.Equals(_cookies[i].Domain, domain, StringComparison.OrdinalIgnoreCase))
                {
                    _cookies[i].Value = value;
                    _cookies[i].Path = path;
                    return;
                }
            }
            _cookies.Add(new CookieEntry { Name = name, Value = value, Domain = domain, Path = path });
        }

        private static bool CookieMatchesHost(string cookieDomain, string requestHost)
        {
            if (string.IsNullOrEmpty(cookieDomain) || string.IsNullOrEmpty(requestHost)) return false;
            string cd = cookieDomain.TrimStart('.').ToLowerInvariant();
            string rh = requestHost.ToLowerInvariant();
            return rh == cd || rh.EndsWith("." + cd);
        }

        private static bool IsImageFile(string p)
        {
            string ext = Path.GetExtension(p).ToLowerInvariant();
            foreach (string c in ImageExtensions) if (ext == c) return true;
            return false;
        }

        private static bool IsArchiveFile(string p)
        {
            string ext = Path.GetExtension(p).ToLowerInvariant();
            foreach (string c in ArchiveExtensions) if (ext == c) return true;
            return false;
        }

        private static string ComputeSha256Hex(string text)
        {
            using (SHA256 sha = SHA256.Create())
            {
                byte[] bytes = sha.ComputeHash(Encoding.UTF8.GetBytes(text));
                StringBuilder sb = new StringBuilder(bytes.Length * 2);
                foreach (byte b in bytes) sb.Append(b.ToString("x2"));
                return sb.ToString();
            }
        }
    }

    internal sealed class SourceBrowseTarget
    {
        public string Kind { get; set; }
        public string Title { get; set; }
        public int Index { get; set; }
        public string Category { get; set; }
        public string Param { get; set; }
        public string Keyword { get; set; }
        public List<string> Options { get; set; }

        public Dictionary<string, object> AsScriptArgument()
        {
            return new Dictionary<string, object>
            {
                { "kind", Kind }, { "index", Index }, { "category", Category },
                { "param", Param }, { "keyword", Keyword }, { "options", Options }
            };
        }
    }

    internal sealed class SourceCategoryGroup
    {
        public int Index { get; set; }
        public string Title { get; set; }
    }

    internal sealed class SourceBrowseMenu
    {
        public bool CanSearch { get; set; }
        public string Website { get; set; }
        public string AppWebsite { get; set; }
        public List<SourceBrowseTarget> Channels { get; set; }
        public List<SourceCategoryGroup> Groups { get; set; }
        public SourceBrowseTarget Ranking { get; set; }
    }

    internal sealed class SourceBrowseFilters
    {
        public List<string> Values { get; set; }
        public List<SourceFilterField> Fields { get; set; }
    }

    internal sealed class SourceFilterField
    {
        public int Index { get; set; }
        public string Title { get; set; }
        public bool Multiple { get; set; }
        public List<SourceFilterChoice> Choices { get; set; }
    }

    internal sealed class SourceFilterChoice
    {
        public string Title { get; set; }
        public string Value { get; set; }
    }

    internal sealed class SourceFeedSection
    {
        public string Title { get; set; }
        public SourceBrowseTarget Target { get; set; }
        public List<ComicCardModel> Cards { get; set; }
        public List<Dictionary<string, object>> Comics { get; set; }
    }

    internal sealed class SourceBrowsePage
    {
        public List<SourceFeedSection> Sections { get; set; }
        public int? MaxPage { get; set; }
        public string NextCursor { get; set; }
    }

    internal enum TranslationMode { Server, Text, Vision, Direct = 4 }

    internal sealed class TranslationModel
    {
        public TranslationMode Mode { get; set; }
        public string ApiBase { get; set; }
        public string ModelId { get; set; }
        public string ProtectedKey { get; set; }
        public string ReasoningEffort { get; set; }
        public bool SendImage { get; set; }
        public TranslationModel() { SendImage = true; }
        public static readonly string[] ReasoningLevels = { "", "none", "low", "medium", "high" };
        public bool AcceptsPageImage { get { return UsesTranslationServer || SendImage; } }
        public bool UsesCustomModel { get { return Mode != TranslationMode.Server; } }
        public bool UsesTranslationServer { get { return Mode != TranslationMode.Direct; } }

        public static string EncryptKey(string key)
        {
            if (string.IsNullOrWhiteSpace(key)) return "";
            return Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(key.Trim()), null, DataProtectionScope.CurrentUser));
        }

        public static string DecryptSecret(string value)
        {
            if (string.IsNullOrEmpty(value)) return "";
            return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(value), null, DataProtectionScope.CurrentUser));
        }

        public string DecryptKey()
        {
            try { return DecryptSecret(ProtectedKey); }
            catch (Exception) { throw new ArgumentException("请在漫画翻译设置中重新填写模型密钥"); }
        }
    }

    internal sealed class ReadingEntry
    {
        public string Day { get; set; }
        public string ComicId { get; set; }
        public string Title { get; set; }
        public long Milliseconds { get; set; }
    }

    internal sealed class ReadingActivity
    {
        private readonly string _path;
        private readonly Dictionary<string, ReadingEntry> _entries = new Dictionary<string, ReadingEntry>();

        public ReadingActivity(string dataDirectory)
        {
            _path = Path.Combine(dataDirectory, "reading_activity.json");
            if (!File.Exists(_path)) return;
            try
            {
                var saved = JsonUtil.CreateSerializer().Deserialize<List<ReadingEntry>>(File.ReadAllText(_path, Encoding.UTF8));
                foreach (ReadingEntry row in saved)
                    if (row != null && row.Milliseconds > 0) _entries[row.Day + "\n" + row.ComicId] = row;
            }
            catch (Exception ex) { Trace.TraceWarning("Could not read the reading activity: " + ex.GetType().Name); }
        }

        public IEnumerable<ReadingEntry> Entries { get { return _entries.Values; } }

        public void Add(ComicCardModel comic, DateTimeOffset start, long millis)
        {
            if (string.IsNullOrEmpty(comic.Uid) || millis <= 0) return;
            DateTimeOffset end = start.AddMilliseconds(millis);
            for (DateTimeOffset cursor = start; cursor < end; )
            {
                DateTime day = cursor.LocalDateTime.Date;
                string date = day.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
                DateTimeOffset midnight = new DateTimeOffset(DateTime.SpecifyKind(day.AddDays(1), DateTimeKind.Local));
                DateTimeOffset boundary = midnight < end ? midnight : end;
                string key = date + "\n" + comic.Uid;
                ReadingEntry entry;
                if (!_entries.TryGetValue(key, out entry))
                {
                    entry = new ReadingEntry { Day = date, ComicId = comic.Uid };
                    _entries.Add(key, entry);
                }
                entry.Title = comic.Title;
                entry.Milliseconds += (long)(boundary - cursor).TotalMilliseconds;
                cursor = boundary;
            }
            try
            {
                string pending = _path + ".tmp";
                File.WriteAllText(pending, JsonUtil.CreateSerializer().Serialize(_entries.Values.ToList()), Encoding.UTF8);
                if (File.Exists(_path)) File.Replace(pending, _path, null);
                else File.Move(pending, _path);
            }
            catch (IOException ex) { Trace.TraceWarning("Could not save reading activity: " + ex.GetType().Name); }
            catch (UnauthorizedAccessException ex) { Trace.TraceWarning("Could not save reading activity: " + ex.GetType().Name); }
        }
    }

    internal static partial class MangaTranslation
    {
        private const string ApiPath = "/translate/with-form/image";
        private const string StreamPath = ApiPath + "/stream";
        private const int Limit = 32 * 1024 * 1024;
        private static readonly TranslationRequestQueue Queue = new TranslationRequestQueue();

        private static string ServiceRoot(string baseUrl)
        {
            Uri uri;
            if (!Uri.TryCreate(baseUrl.Trim(), UriKind.Absolute, out uri) || (uri.Scheme != "https" && uri.Scheme != "http") ||
                string.IsNullOrEmpty(uri.Host) || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment))
                throw new ArgumentException("请输入有效的 HTTP(S) 服务地址");
            string address = uri.AbsoluteUri.TrimEnd('/');
            if (address.EndsWith(StreamPath + "/web", StringComparison.Ordinal)) return address.Substring(0, address.Length - StreamPath.Length - 4);
            if (address.EndsWith(StreamPath, StringComparison.Ordinal)) return address.Substring(0, address.Length - StreamPath.Length);
            if (address.EndsWith(ApiPath, StringComparison.Ordinal)) return address.Substring(0, address.Length - ApiPath.Length);
            return address;
        }

        public static Uri Endpoint(string baseUrl, TranslationMode mode = TranslationMode.Server)
        {
            string address = baseUrl.Trim().TrimEnd('/');
            bool streaming = mode != TranslationMode.Server || address.EndsWith(StreamPath, StringComparison.Ordinal) ||
                address.EndsWith(StreamPath + "/web", StringComparison.Ordinal);
            return new Uri(ServiceRoot(baseUrl) + (streaming ? StreamPath : ApiPath));
        }

        public static void ValidateConfig(string config)
        {
            try
            {
                if (!(JsonUtil.CreateSerializer().DeserializeObject(config) is Dictionary<string, object>))
                    throw new ArgumentException();
            }
            catch { throw new ArgumentException("翻译配置必须是 JSON 对象"); }
        }

        internal sealed class LoginRequired : InvalidOperationException
        {
            public LoginRequired() : base("请在漫画翻译设置中登录翻译服务") { }
        }

        private sealed class TranslationFailure : InvalidOperationException
        {
            public TranslationFailure(string message) : base(message) { }
        }

        public static Task<string> LoginAsync(string server, string username, string password)
        {
            return Task.Run(delegate
            {
                Uri uri = new Uri(ServiceRoot(server) + "/auth/login");
                byte[] body = Encoding.UTF8.GetBytes(JsonUtil.CreateSerializer().Serialize(new Dictionary<string, string>
                    { { "username", username }, { "password", password } }));
                var request = (HttpWebRequest)WebRequest.Create(uri);
                request.Method = "POST";
                request.AllowAutoRedirect = false;
                request.ContentType = "application/json";
                request.Timeout = request.ReadWriteTimeout = 15000;
                request.ContentLength = body.Length;
                using (var output = request.GetRequestStream()) output.Write(body, 0, body.Length);
                using (var response = (HttpWebResponse)request.GetResponse())
                using (var input = response.GetResponseStream())
                {
                    if (response.StatusCode != HttpStatusCode.OK) throw new InvalidOperationException("登录失败");
                    var reply = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(
                        Encoding.UTF8.GetString(ReadBounded(input, 64 * 1024)));
                    if (!JsonUtil.GetBool(reply, "success", false)) throw new InvalidOperationException("登录失败");
                    if (JsonUtil.GetBool(reply, "must_change_password", false)) throw new InvalidOperationException("请先在翻译服务网页修改初始密码");
                    string token = JsonUtil.GetStr(reply, "token", "");
                    if (token.Length == 0 || token.Any(char.IsControl)) throw new InvalidOperationException("登录失败");
                    return token;
                }
            });
        }

        public static void ValidateModel(TranslationModel model)
        {
            if (!model.UsesCustomModel) return;
            ValidateModelAddress(model.ApiBase);
            if (string.IsNullOrWhiteSpace(model.ModelId)) throw new ArgumentException("请输入模型 ID");
            if (!TranslationModel.ReasoningLevels.Contains(model.ReasoningEffort ?? ""))
                throw new ArgumentException("请选择有效的思考强度");
        }

        private static Uri ValidateModelAddress(string address)
        {
            Uri api;
            if (!Uri.TryCreate(address, UriKind.Absolute, out api) || (api.Scheme != "http" && api.Scheme != "https") ||
                string.IsNullOrEmpty(api.Host) || !string.IsNullOrEmpty(api.Query) || !string.IsNullOrEmpty(api.Fragment) ||
                !string.IsNullOrEmpty(api.UserInfo)) throw new ArgumentException("请输入有效的模型 API 地址");
            return api;
        }

        public static async Task<BitmapSource> TranslateAsync(BitmapSource original, string server, string config,
            TranslationModel model, string session, string directory, CancellationToken token, string usageDirectory = null,
            TranslationRequestPriority priority = null)
        {
            ValidateConfig(config);
            ValidateModel(model);
            if (!model.AcceptsPageImage) throw new TranslationFailure("图片输入已关闭，请输入原文");
            bool direct = !model.UsesTranslationServer;
            Uri uri = direct ? DirectEndpoint(new Uri(model.ApiBase)) : Endpoint(server, model.Mode);
            config = TranslationConfig(config, model.Mode);
            string environment = "";
            if (!direct && model.UsesCustomModel)
            {
                string key = model.DecryptKey();
                environment = JsonUtil.CreateSerializer().Serialize(new Dictionary<string, string> {
                    { "OPENAI_API_BASE", model.ApiBase }, { "OPENAI_MODEL", model.ModelId },
                    { "OPENAI_API_KEY", string.IsNullOrEmpty(key) ? "ollama" : key }
                });
            }
            return await Task.Run(() => direct
                ? TranslateDirectAsync(original, uri, config, model, directory, token, usageDirectory, priority)
                : TranslateLegacyAsync(original, uri, config, environment, session, directory, token, priority), token);
        }

        private static async Task<T> RunTranslationRequest<T>(TranslationRequestPriority priority, CancellationToken token, Func<T> request)
        {
            await Queue.WaitAsync(priority, token).ConfigureAwait(false);
            try
            {
                token.ThrowIfCancellationRequested();
                return request();
            }
            finally { Queue.Release(); }
        }

        private static string TranslationConfig(string config, TranslationMode mode)
        {
            if (mode == TranslationMode.Server || mode == TranslationMode.Direct) return config;
            JavaScriptSerializer serializer = JsonUtil.CreateSerializer();
            var settings = serializer.Deserialize<Dictionary<string, object>>(config);
            object field;
            var translator = settings.TryGetValue("translator", out field) ? field as Dictionary<string, object> : null;
            if (translator == null) translator = new Dictionary<string, object>();
            translator["translator"] = mode == TranslationMode.Vision ? "openai_hq" : "openai";
            if (string.IsNullOrWhiteSpace(JsonUtil.GetStr(translator, "target_lang", ""))) translator["target_lang"] = "CHS";
            translator.Remove("translator_chain");
            translator.Remove("selective_translation");
            settings["translator"] = translator;
            return serializer.Serialize(settings);
        }

        public static async Task<BitmapSource> TestAsync(string server, string config, TranslationModel model, string session, CancellationToken token,
            string usageDirectory = null)
        {
            if (!model.AcceptsPageImage)
            {
                await TranslateTextAsync(config, model, "Good morning! 今日はいい天気です。", usageDirectory, token);
                return null;
            }
            var visual = new DrawingVisual();
            var typeface = new Typeface("Segoe UI");
            using (DrawingContext drawing = visual.RenderOpen())
            {
                drawing.DrawRectangle(new SolidColorBrush(Color.FromRgb(212, 220, 230)), null, new Rect(0, 0, 600, 220));
                var border = new Pen(Brushes.Black, 2);
                drawing.DrawRoundedRectangle(Brushes.White, border, new Rect(35, 26, 530, 79), 33, 33);
                drawing.DrawRoundedRectangle(Brushes.White, border, new Rect(35, 115, 530, 79), 33, 33);
                drawing.DrawText(new FormattedText("Good morning!", CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
                    typeface, 46, Brushes.Black, 1.0), new Point(55, 37));
                drawing.DrawText(new FormattedText("今日はいい天気です。", CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
                    typeface, 46, Brushes.Black, 1.0), new Point(55, 126));
            }
            var sample = new RenderTargetBitmap(600, 220, 96, 96, PixelFormats.Pbgra32);
            sample.Render(visual);
            sample.Freeze();
            BitmapSource translated = await TranslateAsync(sample, server, config, model, session, null, token, usageDirectory);
            if (ReferenceEquals(sample, translated)) throw new TranslationFailure("API 未在测试图中返回可排版的译文");
            return translated;
        }

        private static byte[] EncodeBitmap(BitmapSource source, BitmapEncoder encoder)
        {
            encoder.Frames.Add(BitmapFrame.Create(source));
            using (MemoryStream output = new MemoryStream())
            {
                encoder.Save(output);
                return output.ToArray();
            }
        }

        private static string CachePath(string directory, Uri uri, string config, string profile, byte[] image, string extension = "image")
        {
            if (directory == null) return null;
            using (SHA256 hash = SHA256.Create())
            {
                byte[] prefix = Encoding.UTF8.GetBytes(uri.AbsoluteUri + "\n" + config + "\n" + profile + "\n");
                hash.TransformBlock(prefix, 0, prefix.Length, prefix, 0);
                hash.TransformFinalBlock(image, 0, image.Length);
                return Path.Combine(directory, BitConverter.ToString(hash.Hash).Replace("-", "") + "." + extension);
            }
        }

        private static BitmapImage LoadCache(string cache)
        {
            if (!File.Exists(cache)) return null;
            try { return Decode(File.ReadAllBytes(cache)); }
            catch { return null; }
        }

        private static async Task<BitmapSource> TranslateLegacyAsync(BitmapSource original, Uri uri, string config, string environment,
            string session, string directory, CancellationToken token, TranslationRequestPriority priority)
        {
            byte[] image = EncodeBitmap(original, new JpegBitmapEncoder { QualityLevel = 92 });
            if (image.Length > Limit) throw new TranslationFailure("此页图片过大");
            string cache = CachePath(directory, uri, config, environment, image);
            BitmapImage cached = LoadCache(cache);
            if (cached != null) return cached;
            return await RunTranslationRequest(priority, token, () => LoadCache(cache) ??
                RequestLegacyImage(uri, config, environment, session, image, cache, token)).ConfigureAwait(false);
        }

        private static BitmapImage RequestLegacyImage(Uri uri, string config, string environment, string session, byte[] image,
            string cache, CancellationToken token)
        {
            if (environment.Length > 0 && string.IsNullOrEmpty(session)) throw new LoginRequired();
            string boundary = "Koma" + Guid.NewGuid().ToString("N");
            string modelPart = environment.Length == 0 ? "" : "\r\n--" + boundary +
                "\r\nContent-Disposition: form-data; name=\"user_env_vars\"\r\n\r\n" + environment;
            byte[] head = Encoding.UTF8.GetBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"config\"\r\n\r\n" + config + modelPart +
                "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"page.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n");
            byte[] end = Encoding.ASCII.GetBytes("\r\n--" + boundary + "--\r\n");
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(uri);
            request.Method = "POST";
            request.AllowAutoRedirect = false;
            request.Timeout = request.ReadWriteTimeout = 300000;
            request.ContentType = "multipart/form-data; boundary=" + boundary;
            request.ContentLength = head.Length + image.Length + end.Length;
            if (!string.IsNullOrEmpty(session)) request.Headers["X-Session-Token"] = session;
            token.ThrowIfCancellationRequested();
            using (token.Register(request.Abort))
            {
                try
                {
                    using (Stream stream = request.GetRequestStream())
                    {
                        stream.Write(head, 0, head.Length);
                        stream.Write(image, 0, image.Length);
                        stream.Write(end, 0, end.Length);
                    }
                    return ReadTranslatedImage(request, cache, uri.AbsolutePath.EndsWith(StreamPath, StringComparison.Ordinal));
                }
                catch (WebException error)
                {
                    token.ThrowIfCancellationRequested();
                    using (HttpWebResponse response = error.Response as HttpWebResponse)
                        if (response != null) throw ResponseError((int)response.StatusCode);
                    throw;
                }
            }
        }

        private static BitmapImage ReadTranslatedImage(HttpWebRequest request, string cache, bool streaming)
        {
            using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
            using (Stream input = response.GetResponseStream())
            {
                if ((int)response.StatusCode >= 300) throw ResponseError((int)response.StatusCode);
                byte[] data = streaming ? ReadTranslatedFrame(input) : ReadBounded(input, Limit);
                BitmapImage translated;
                try { translated = Decode(data); }
                catch (IOException) { throw new TranslationFailure("服务未返回可读的译图"); }
                catch (NotSupportedException) { throw new TranslationFailure("服务未返回可读的译图"); }
                catch (ArgumentException) { throw new TranslationFailure("服务未返回可读的译图"); }
                if (cache != null) SaveCache(cache, data);
                return translated;
            }
        }

        private static void SaveCache(string cache, byte[] data)
        {
            string temporary = cache + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(cache));
                try
                {
                    File.WriteAllBytes(temporary, data);
                    if (File.Exists(cache)) File.Replace(temporary, cache, null);
                    else File.Move(temporary, cache);
                }
                finally { if (File.Exists(temporary)) File.Delete(temporary); }
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        private static Exception ResponseError(int status)
        {
            switch (status)
            {
                case 401: return new LoginRequired();
                case 403: return new TranslationFailure("服务拒绝翻译（HTTP 403），请检查账户权限和模型密钥");
                case 404:
                case 405: return new TranslationFailure("找不到漫画翻译接口（HTTP " + status + "），请检查服务类型和地址");
                case 413: return new TranslationFailure("服务拒绝了过大的漫画页（HTTP 413）");
                case 422: return new TranslationFailure("服务无法接受此翻译配置或图片（HTTP 422）");
                case 429: return new TranslationFailure("翻译服务请求过多或额度已用完（HTTP 429）");
            }
            return new TranslationFailure(status >= 300 && status < 400
                ? "翻译服务要求重定向，请填写最终服务地址（HTTP " + status + "）"
                : "翻译服务返回 HTTP " + status + "，请检查服务日志");
        }

        public static string Describe(Exception error)
        {
            if (error is LoginRequired || error is TranslationFailure || error is ArgumentException) return error.Message;
            var network = error as WebException ?? error.InnerException as WebException;
            if (network != null && network.Status == WebExceptionStatus.Timeout) return "翻译请求超时，请检查网络和 API 状态";
            if (error is EndOfStreamException) return "翻译服务在返回译图前中断了连接";
            if (network != null || error is IOException) return "无法连接翻译服务，请检查地址、网络和服务状态";
            return "翻译失败，请检查漫画翻译设置";
        }

        private static byte[] ReadBounded(Stream stream, int limit)
        {
            using (MemoryStream output = new MemoryStream())
            {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = stream.Read(buffer, 0, buffer.Length)) > 0)
                {
                    if (output.Length + count > limit) throw new TranslationFailure("翻译服务响应超过大小限制");
                    output.Write(buffer, 0, count);
                }
                return output.ToArray();
            }
        }

        internal static byte[] ReadTranslatedFrame(Stream stream)
        {
            using (BinaryReader reader = new BinaryReader(stream, Encoding.UTF8, true))
            {
                for (int index = 0; index < 4096; index++)
                {
                    int status = reader.ReadByte();
                    int length = System.Net.IPAddress.NetworkToHostOrder(reader.ReadInt32());
                    if (status > 4 || length < 0 || length > Limit || (status != 0 && length > 256 * 1024))
                        throw new TranslationFailure("翻译服务的响应格式不正确");
                    if (status == 2) throw new TranslationFailure("后端处理失败，请检查翻译模型、权限和服务日志");
                    byte[] data = reader.ReadBytes(length);
                    if (data.Length != length) throw new EndOfStreamException();
                    if (status == 0) return data;
                }
            }
            throw new TranslationFailure("翻译服务返回了过多进度消息");
        }

        private static BitmapImage Decode(byte[] bytes)
        {
            using (MemoryStream stream = new MemoryStream(bytes))
            {
                BitmapImage bitmap = new BitmapImage();
                bitmap.BeginInit();
                bitmap.CacheOption = BitmapCacheOption.OnLoad;
                bitmap.StreamSource = stream;
                bitmap.EndInit();
                bitmap.Freeze();
                return bitmap;
            }
        }
    }

    internal sealed class VeneraJsRunner : IDisposable
    {
        private static readonly Regex ClassRegex = new Regex(@"class\s+([A-Za-z_$][A-Za-z0-9_$]*)\s+extends\s+ComicSource\b");
        private readonly AppServices _services;
        private readonly Dispatcher _uiDispatcher;
        private Microsoft.Web.WebView2.WinForms.WebView2 _webView;
        private readonly TaskCompletionSource<bool> _readyTcs = new TaskCompletionSource<bool>();
        private readonly Dictionary<string, TaskCompletionSource<string>> _pendingCalls = new Dictionary<string, TaskCompletionSource<string>>(StringComparer.Ordinal);
        private readonly Dictionary<string, Task> _sourceLoads = new Dictionary<string, Task>(StringComparer.Ordinal);
        private readonly object _lock = new object();
        private string _tempProfileDir;

        public VeneraJsRunner(AppServices services, Dispatcher uiDispatcher)
        {
            _services = services;
            _uiDispatcher = uiDispatcher;
        }

        public System.Windows.Forms.Integration.WindowsFormsHost CreateHiddenHost()
        {
            _webView = new Microsoft.Web.WebView2.WinForms.WebView2();
            _webView.Size = new System.Drawing.Size(1, 1);
            System.Windows.Forms.Integration.WindowsFormsHost host = new System.Windows.Forms.Integration.WindowsFormsHost();
            host.Width = 1;
            host.Height = 1;
            host.Opacity = 0.01;
            host.IsHitTestVisible = false;
            host.HorizontalAlignment = HorizontalAlignment.Left;
            host.VerticalAlignment = VerticalAlignment.Top;
            host.Child = _webView;
            return host;
        }

        public async void InitializeAsync()
        {
            try
            {
                string profileDir = Path.Combine(_services.DataDir, "webview2_worker");
                Directory.CreateDirectory(profileDir);
                bool primaryFailed = false;
                try
                {
                    CoreWebView2Environment env = await CoreWebView2Environment.CreateAsync(null, profileDir, null);
                    await _webView.EnsureCoreWebView2Async(env);
                }
                catch (Exception)
                {
                    primaryFailed = true;
                }
                if (primaryFailed)
                {
                    // Fallback to an isolated per-process profile when webview2_worker is locked (e.g. HRESULT 0x800700AA)
                    string fallback = Path.Combine(Path.GetTempPath(),
                        "KomaComic_wv2_" + Process.GetCurrentProcess().Id + "_" + Guid.NewGuid().ToString("N").Substring(0, 6));
                    Directory.CreateDirectory(fallback);
                    _tempProfileDir = fallback;
                    CoreWebView2Environment fallbackEnv = await CoreWebView2Environment.CreateAsync(null, fallback, null);
                    await _webView.EnsureCoreWebView2Async(fallbackEnv);
                }
                _webView.CoreWebView2.WebMessageReceived += OnWebMessageReceived;
                _webView.CoreWebView2.NavigationCompleted += delegate (object s, CoreWebView2NavigationCompletedEventArgs e)
                {
                    _readyTcs.TrySetResult(e.IsSuccess);
                };
                _webView.CoreWebView2.Navigate(_services.HostUrl);
            }
            catch (Exception ex)
            {
                _readyTcs.TrySetException(ex);
            }
        }

        public void InvalidateSource(string key)
        {
            lock (_sourceLoads) { _sourceLoads.Remove(key); }
        }

        private void OnWebMessageReceived(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            try
            {
                string json = e.TryGetWebMessageAsString();
                Dictionary<string, object> msg = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(json);
                if (msg == null) return;
                string type = JsonUtil.GetStr(msg, "type", "");
                string callId = JsonUtil.GetStr(msg, "callId", "");
                TaskCompletionSource<string> tcs = null;
                lock (_lock)
                {
                    if (_pendingCalls.TryGetValue(callId, out tcs)) _pendingCalls.Remove(callId);
                }
                if (tcs == null) return;
                if (type == "resolve") tcs.TrySetResult(JsonUtil.GetStr(msg, "result", "null"));
                else tcs.TrySetException(new InvalidOperationException(JsonUtil.GetStr(msg, "error", "JS error")));
            }
            catch { }
        }

        private async Task EnsureSourceLoadedAsync(SourceItem item)
        {
            bool ok = await _readyTcs.Task;
            if (!ok) throw new InvalidOperationException("JS 引擎初始化失败");

            Task load;
            lock (_sourceLoads)
            {
                if (!_sourceLoads.TryGetValue(item.Key, out load))
                {
                    load = LoadSourceAsync(item);
                    _sourceLoads[item.Key] = load;
                }
            }

            try { await load; }
            catch
            {
                lock (_sourceLoads)
                {
                    Task current;
                    if (_sourceLoads.TryGetValue(item.Key, out current) && ReferenceEquals(current, load))
                        _sourceLoads.Remove(item.Key);
                }
                throw;
            }
        }

        private async Task LoadSourceAsync(SourceItem item)
        {
            string script = _services.ReadSourceScript(item);
            if (string.IsNullOrWhiteSpace(script)) throw new InvalidOperationException("源脚本为空: " + item.Name);
            Match m = ClassRegex.Match(script);
            if (!m.Success) throw new InvalidOperationException("脚本格式不支持 (未找到 extends ComicSource): " + item.Name);
            string className = m.Groups[1].Value;

            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            string qKey = ser.Serialize(item.Key);
            string callId = Guid.NewGuid().ToString("N");
            string js =
                "window.__veneraInvoke(" + ser.Serialize(callId) + ", async function() {\n" +
                "  window.__currentSourceKey = " + qKey + ";\n" +
                script + "\n" +
                "  const inst = new " + className + "();\n" +
                "  inst.key = inst.key || " + qKey + ";\n" +
                "  try { inst.__settingsSchema = inst.settings ? JSON.parse(JSON.stringify(inst.settings)) : {}; }\n" +
                "  catch (_) { inst.__settingsSchema = inst.settings || {}; }\n" +
                "  window.__veneraSources[" + qKey + "] = inst;\n" +
                "  if (typeof inst.init === 'function') { try { await Promise.resolve(inst.init()); } catch (_) {} }\n" +
                "  return true;\n" +
                "});";

            await ExecuteCallAsync(js, callId);
        }

        private async Task<string> ExecuteCallAsync(string wrappedJs, string explicitCallId, int timeoutMs = 35000)
        {
            string callId = explicitCallId ?? Guid.NewGuid().ToString("N");
            TaskCompletionSource<string> tcs = new TaskCompletionSource<string>();
            lock (_lock) { _pendingCalls[callId] = tcs; }

            await _uiDispatcher.InvokeAsync(delegate ()
            {
                _webView.CoreWebView2.ExecuteScriptAsync(wrappedJs);
            });

            Task timeoutTask = Task.Delay(timeoutMs);
            Task completed = await Task.WhenAny(tcs.Task, timeoutTask);
            if (completed == timeoutTask)
            {
                lock (_lock) { _pendingCalls.Remove(callId); }
                throw new TimeoutException("漫画源请求超时");
            }
            return await tcs.Task;
        }

        private async Task<string> InvokeSourceAsync(SourceItem item, string bodyJs, int timeoutMs = 35000)
        {
            await EnsureSourceLoadedAsync(item);
            string callId = Guid.NewGuid().ToString("N");
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            string wrapped =
                "window.__veneraInvoke(" + ser.Serialize(callId) + ", async function() {\n" +
                "  const src = window.__veneraSources[" + ser.Serialize(item.Key) + "];\n" +
                "  if (!src) throw new Error('Source not initialized');\n" +
                bodyJs + "\n" +
                "});";
            return await ExecuteCallAsync(wrapped, callId, timeoutMs);
        }

        private async Task<T> LoadBrowseDataAsync<T>(SourceItem item, string js)
        {
            string raw = await InvokeSourceAsync(item, js);
            return JsonUtil.CreateSerializer().Deserialize<T>(raw);
        }

        public Task<SourceBrowseMenu> GetBrowseMenuAsync(SourceItem item)
        {
            return LoadBrowseDataAsync<SourceBrowseMenu>(item, "return window.__sourceBrowseMenu(src);");
        }

        public Task<List<SourceBrowseTarget>> GetBrowseCategoriesAsync(SourceItem item, int groupIndex)
        {
            return LoadBrowseDataAsync<List<SourceBrowseTarget>>(item, "return window.__sourceBrowseCategories(src, " + groupIndex + ");");
        }

        public Task<SourceBrowseFilters> GetBrowseFiltersAsync(SourceItem item, SourceBrowseTarget target)
        {
            string arg = JsonUtil.CreateSerializer().Serialize(target.AsScriptArgument());
            return LoadBrowseDataAsync<SourceBrowseFilters>(item, "return window.__sourceBrowseFilters(src, " + arg + ");");
        }

        public async Task<SourceBrowsePage> LoadBrowsePageAsync(
            SourceItem item, SourceBrowseTarget target, List<string> options, int page, string cursor)
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            string js = "return window.__sourceBrowseFeed(src, " + ser.Serialize(target.AsScriptArgument()) +
                ", " + page + ", " + ser.Serialize(cursor) + ", " + ser.Serialize(options) + ");";
            SourceBrowsePage result = await LoadBrowseDataAsync<SourceBrowsePage>(item, js);
            foreach (SourceFeedSection section in result.Sections)
            {
                section.Cards = new List<ComicCardModel>();
                foreach (Dictionary<string, object> comic in section.Comics)
                {
                    string id = JsonUtil.GetStr(comic, "id", "");
                    if (string.IsNullOrEmpty(id)) continue;
                    section.Cards.Add(new ComicCardModel
                    {
                        Uid = item.Key + "::" + id,
                        Id = id,
                        SourceKey = item.Key,
                        IsLocal = false,
                        Title = JsonUtil.GetStr(comic, "title", id),
                        SubTitle = JsonUtil.GetStr(comic, "subTitle", item.Name),
                        CoverUrl = JsonUtil.GetStr(comic, "cover", ""),
                        Description = JsonUtil.GetStr(comic, "description", ""),
                        Tags = ComicSourceMetadata.ReadTags(comic),
                        Badge = item.Name,
                        Headers = ExtractHeadersMap(comic)
                    });
                }
            }
            return result;
        }

        public async Task<ComicDetailModel> LoadComicDetailAsync(SourceItem item, string remoteId, Dictionary<string, string> fallbackCardHeaders)
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            string raw = await InvokeSourceAsync(item, "return window.__sourceComicDetails(src, " + ser.Serialize(remoteId) + ");");
            Dictionary<string, object> d = ser.Deserialize<Dictionary<string, object>>(raw);
            ComicDetailModel detail = new ComicDetailModel
            {
                Title = JsonUtil.GetStr(d, "title", remoteId),
                SubTitle = JsonUtil.GetStr(d, "subTitle", item.Name),
                CoverUrl = JsonUtil.GetStr(d, "cover", ""),
                Description = JsonUtil.GetStr(d, "description", ""),
                OfficialUrl = JsonUtil.GetStr(d, "url", ""),
                Tags = ComicSourceMetadata.ReadTags(d),
                Chapters = new List<ChapterItem>(),
                Headers = ExtractHeadersMap(d)
            };
            if (detail.Headers.Count == 0 && fallbackCardHeaders != null) detail.Headers = fallbackCardHeaders;

            object chObj = null;
            if (d != null && d.TryGetValue("chapters", out chObj) && chObj is Dictionary<string, object>)
            {
                ExtractChapters((Dictionary<string, object>)chObj, "", detail.Chapters);
            }
            if (detail.Chapters.Count == 0 && JsonUtil.GetBool(d, "wholeBook", false))
                detail.Chapters.Add(new ChapterItem { Id = null, Title = "全本" });
            return detail;
        }

        public async Task<List<PageImageModel>> LoadChapterPagesAsync(SourceItem item, string remoteId, string epId)
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            string js = "return window.__sourceChapterPages(src, " + ser.Serialize(remoteId) + ", " + ser.Serialize(epId) + ");";
            string raw = await InvokeSourceAsync(item, js);
            ArrayList arr = ser.Deserialize<ArrayList>(raw);
            List<PageImageModel> pages = new List<PageImageModel>();
            if (arr != null)
            {
                foreach (object el in arr)
                {
                    Dictionary<string, object> pd = el as Dictionary<string, object>;
                    if (pd == null) continue;
                    string u = JsonUtil.GetStr(pd, "url", "");
                    if (!string.IsNullOrEmpty(u))
                    {
                        pages.Add(new PageImageModel
                        {
                            Url = u,
                            Headers = ExtractHeadersMap(pd),
                            UseSourceLoader = JsonUtil.GetBool(pd, "useSourceLoader", false)
                        });
                    }
                }
            }
            return pages;
        }

        public async Task<byte[]> LoadPageBytesAsync(SourceItem item, string comicId, string episode, PageImageModel page)
        {
            JavaScriptSerializer serializer = JsonUtil.CreateSerializer();
            string args = string.Join(", ", serializer.Serialize(comicId), serializer.Serialize(episode),
                serializer.Serialize(page.Url), serializer.Serialize(page.Headers));
            string raw = await InvokeSourceAsync(item, "return window.__sourcePageBytes(src, " + args + ", true);", 80000);
            return Convert.FromBase64String(serializer.Deserialize<string>(raw));
        }

        public async Task<AccountCapModel> GetAccountCapabilitiesAsync(SourceItem item)
        {
            string js =
                "const acc = src.account || {};\n" +
                "const cf = (acc.loginWithCookies && Array.isArray(acc.loginWithCookies.fields)) ? acc.loginWithCookies.fields.map(String) : [];\n" +
                "let logged = false; try { logged = !!src.isLogged; } catch (_) {}\n" +
                "return { hasPasswordLogin: typeof acc.login === 'function', cookieFields: cf, registerWebsite: typeof acc.registerWebsite === 'string' ? acc.registerWebsite : '', isLoggedIn: logged };";
            string raw = await InvokeSourceAsync(item, js);
            Dictionary<string, object> d = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(raw);
            AccountCapModel cap = new AccountCapModel
            {
                HasPasswordLogin = JsonUtil.GetBool(d, "hasPasswordLogin", false),
                CookieFields = new List<string>(),
                RegisterWebsite = JsonUtil.GetStr(d, "registerWebsite", ""),
                IsLoggedIn = JsonUtil.GetBool(d, "isLoggedIn", false)
            };
            object cfObj = null;
            if (d != null && d.TryGetValue("cookieFields", out cfObj) && cfObj is ArrayList)
            {
                foreach (object o in (ArrayList)cfObj) cap.CookieFields.Add(Convert.ToString(o));
            }
            return cap;
        }

        public async Task LoginWithPasswordAsync(SourceItem item, string user, string pass)
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            string qU = ser.Serialize(user);
            string qP = ser.Serialize(pass);
            string js =
                "if (!src.account || typeof src.account.login !== 'function') throw new Error('不支持账号密码登录');\n" +
                "const ok = await src.account.login(" + qU + ", " + qP + ");\n" +
                "if (ok === false) throw new Error('登录失败');\n" +
                "try { src.saveData('__logged_in__', true); src.saveData('account', [" + qU + ", " + qP + "]); } catch (_) {}\n" +
                "return true;";
            await InvokeSourceAsync(item, js);
        }

        public async Task<bool> LoginWithCookiesAsync(SourceItem item, List<string> values)
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            string js =
                "const lwc = src.account && src.account.loginWithCookies;\n" +
                "if (!lwc || typeof lwc.validate !== 'function') throw new Error('不支持 Cookie 登录');\n" +
                "const ok = await lwc.validate(" + ser.Serialize(values) + ");\n" +
                "if (ok) src.saveData('__logged_in__', true);\n" +
                "return !!ok;";
            string raw = await InvokeSourceAsync(item, js);
            return string.Equals(raw.Trim(), "true", StringComparison.OrdinalIgnoreCase);
        }

        public async Task LogoutAsync(SourceItem item)
        {
            string js =
                "if (src.account && typeof src.account.logout === 'function') await Promise.resolve(src.account.logout());\n" +
                "src.deleteData('__logged_in__'); src.deleteData('account'); return true;";
            await InvokeSourceAsync(item, js);
        }

        public async Task<List<SourceSettingItem>> GetSourceSettingsAsync(SourceItem item)
        {
            string js =
                "const schema = src.__settingsSchema || src.settings || {};\n" +
                "const out = [];\n" +
                "for (const k of Object.keys(schema)) {\n" +
                "  const f = schema[k]; if (!f || typeof f !== 'object') continue;\n" +
                "  out.push({ key: k, title: f.title || k, type: f.type || 'input', buttonText: f.buttonText || '', value: src.loadSetting(k), options: f.options || [] });\n" +
                "}\n" +
                "return out;";
            string raw = await InvokeSourceAsync(item, js);
            ArrayList arr = JsonUtil.CreateSerializer().Deserialize<ArrayList>(raw);
            List<SourceSettingItem> list = new List<SourceSettingItem>();
            if (arr != null)
            {
                foreach (object o in arr)
                {
                    Dictionary<string, object> d = o as Dictionary<string, object>;
                    if (d == null) continue;
                    SourceSettingItem s = new SourceSettingItem
                    {
                        Key = JsonUtil.GetStr(d, "key", ""),
                        Title = JsonUtil.GetStr(d, "title", ""),
                        Type = JsonUtil.GetStr(d, "type", "input"),
                        ButtonText = JsonUtil.GetStr(d, "buttonText", ""),
                        Options = new List<KeyValuePair<string, string>>()
                    };
                    object v = null;
                    d.TryGetValue("value", out v);
                    s.Value = v;
                    object optsObj = null;
                    if (d.TryGetValue("options", out optsObj) && optsObj is ArrayList)
                    {
                        foreach (object oo in (ArrayList)optsObj)
                        {
                            Dictionary<string, object> od = oo as Dictionary<string, object>;
                            if (od != null) s.Options.Add(new KeyValuePair<string, string>(JsonUtil.GetStr(od, "value", ""), JsonUtil.GetStr(od, "text", "")));
                        }
                    }
                    list.Add(s);
                }
            }
            return list;
        }

        public async Task SaveSourceSettingAsync(SourceItem item, string key, object val)
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            await InvokeSourceAsync(item, "src.saveSetting(" + ser.Serialize(key) + ", " + ser.Serialize(val) + "); return true;");
        }

        public async Task InvokeSettingCallbackAsync(SourceItem item, string key)
        {
            JavaScriptSerializer ser = JsonUtil.CreateSerializer();
            await InvokeSourceAsync(item, "const f = src.settings && src.settings[" + ser.Serialize(key) + "]; if (f && typeof f.callback === 'function') await f.callback(); return true;");
        }

        private static Dictionary<string, string> ExtractHeadersMap(Dictionary<string, object> d)
        {
            Dictionary<string, string> h = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            if (d == null) return h;
            object raw = null;
            if (d.TryGetValue("headers", out raw) && raw is Dictionary<string, object>)
            {
                foreach (KeyValuePair<string, object> kv in (Dictionary<string, object>)raw)
                {
                    if (kv.Value != null) h[kv.Key] = Convert.ToString(kv.Value);
                }
            }
            return h;
        }

        private static void ExtractChapters(Dictionary<string, object> node, string prefix, List<ChapterItem> outList)
        {
            object entriesObj = null;
            if (node.TryGetValue("__mapEntries__", out entriesObj) && entriesObj is ArrayList)
            {
                foreach (object pairObj in (ArrayList)entriesObj)
                {
                    ArrayList pair = pairObj as ArrayList;
                    if (pair == null || pair.Count < 2) continue;
                    string k = Convert.ToString(pair[0]);
                    if (pair[1] is Dictionary<string, object>)
                    {
                        ExtractChapters((Dictionary<string, object>)pair[1], string.IsNullOrEmpty(prefix) ? k : (prefix + " · " + k), outList);
                    }
                    else
                    {
                        string title = Convert.ToString(pair[1] ?? k);
                        string full = (!string.IsNullOrEmpty(prefix) && prefix != "默认") ? (prefix + " - " + title) : title;
                        outList.Add(new ChapterItem { Id = k, Title = full });
                    }
                }
                return;
            }
            foreach (KeyValuePair<string, object> kv in node)
            {
                if (kv.Value is Dictionary<string, object>)
                {
                    ExtractChapters((Dictionary<string, object>)kv.Value, string.IsNullOrEmpty(prefix) ? kv.Key : (prefix + " · " + kv.Key), outList);
                }
                else
                {
                    string title = Convert.ToString(kv.Value ?? kv.Key);
                    string full = (!string.IsNullOrEmpty(prefix) && prefix != "默认") ? (prefix + " - " + title) : title;
                    outList.Add(new ChapterItem { Id = kv.Key, Title = full });
                }
            }
        }

        public void Dispose()
        {
            try
            {
                if (_webView != null) _webView.Dispose();
            }
            catch { }
            if (!string.IsNullOrEmpty(_tempProfileDir))
            {
                try { if (Directory.Exists(_tempProfileDir)) Directory.Delete(_tempProfileDir, true); }
                catch { }
            }
        }
    }

    internal sealed partial class KomaMainWindow : Window
    {
        private readonly AppServices _services;
        private readonly VeneraJsRunner _jsRunner;

        private Button[] _railButtons;
        private Grid _viewHome;
        private Grid _viewCollections;
        private Grid _viewWarehouse;
        private Grid _viewSettings;

        // Home UI
        private TextBox _searchBox;
        private Button _btnSearch;
        private StackPanel _chipsPanel;
        private LiquidGlassSurface _browseBar;
        private TextBlock _sourceHeading;
        private StackPanel _channelTabs;
        private Button _sourceActionsButton;
        private Grid _viewProfile;
        private StackPanel _profileBody;
        private Button _profileDockButton;
        private Grid _backdropStage;
        private Grid _ambientOrbLayer;
        private Grid _homeAmbientOrbLayer;
        private Image _homeWallpaper;
        private DispatcherTimer _readingTimer;
        private readonly Stopwatch _readingStopwatch = new Stopwatch();
        private DateTimeOffset _readingStartedAt;
        private bool _readerPageReady;
        private TextBlock _searchHint;
        private readonly Dictionary<string, SourceFeedSnapshot> _sourceFeedMemory = new Dictionary<string, SourceFeedSnapshot>(StringComparer.Ordinal);
        private readonly LinkedList<string> _sourceFeedOrder = new LinkedList<string>();
        private readonly List<SourceFeedSection> _sourceFeedSections = new List<SourceFeedSection>();
        private int _completedFeedPage;
        private bool _completedCanAdvance;
        private bool _restoringSourceFeed;
        private string _loadedFeedQuery;

        private sealed class SourceFeedSnapshot
        {
            internal SourceBrowseMenu Menu;
            internal SourceBrowseTarget Target;
            internal SourceBrowseTarget FilterTarget;
            internal SourceBrowseFilters Filters;
            internal string Query;
            internal int Page;
            internal int RetryPage;
            internal bool HasMore;
            internal double Offset;
            internal Dictionary<int, string> Cursors;
            internal List<SourceFeedSection> Sections;
        }

        private Image _wallpaper;
        private bool _feedBusy;
        private bool _feedHasMore;
        private bool _feedPageFailed;
        private readonly HashSet<string> _seenFeedItems = new HashSet<string>(StringComparer.Ordinal);
        private Button _categoriesButton;
        private Button _sourceFiltersButton;
        private TextBlock _statusBanner;
        private Button _retryFeedButton;
        private ScrollViewer _homeFeedScroll;
        private StackPanel _comicGrid;
        private TextBlock _emptyFeedText;
        private Grid _paginationBar;
        private int _currentPage = 1;
        private int _feedRequestId;
        private int _categoryRequestId;
        private string _homeQuery = "";
        private string _browseSourceKey;
        private SourceBrowseMenu _browseMenu;
        private SourceBrowseTarget _browseTarget;
        private SourceBrowseTarget _filterTarget;
        private SourceBrowseFilters _browseFilters;
        private readonly Dictionary<int, string> _feedCursors = new Dictionary<int, string>();

        // Collections UI
        private StackPanel _collectionChipsPanel;
        private WrapPanel _collectionGrid;
        private StackPanel _collectionLockedPanel;
        private TextBlock _collectionLockedTitle;
        private TextBlock _collectionEmptyText;

        // Warehouse UI
        private TextBlock _warehouseCountBadge;
        private StackPanel _warehousePathsPanel;
        private TextBox _webdavUrlBox;
        private TextBox _webdavUserBox;
        private PasswordBox _webdavPassBox;
        private TextBlock _webdavStatusText;

        // Reader Overlay UI
        private Grid _readerOverlay;
        private Grid _readerViewport;
        private ScrollViewer _readerScrollViewer;
        private StackPanel _readerScrollStack;
        private Grid _readerSpreadGrid;
        private Grid _readerGhostGrid;
        private ColumnDefinition _readerSpreadGapCol;
        private Image _readerSingleImg;
        private Image _readerLeftImg;
        private Image _readerRightImg;
        private Image _readerGhostSingleImg;
        private Image _readerGhostLeftImg;
        private Image _readerGhostRightImg;
        private Rectangle _readerAnimShadow;
        private Rectangle _readerBrightnessScrim;
        private ScaleTransform _readerZoomTransform;
        private TransformGroup _readerSpreadTransformGroup;
        private TranslateTransform _readerSpreadSlideTransform;
        private ScaleTransform _readerSpreadAnimScale;
        private TransformGroup _readerGhostTransformGroup;
        private TranslateTransform _readerGhostSlideTransform;
        private ScaleTransform _readerGhostAnimScale;
        private Border _readerTopBar;
        private TranslateTransform _readerTopTranslate;
        private TranslateTransform _readerBottomTranslate;
        private TranslateTransform _readerDrawerTranslate;
        private int _chromeAnimGen;
        private int _drawerAnimGen;
        private int _pageAnimGen;
        private TextBlock _readerTranslationStatus;
        private Button _readerTranslationButton;
        private Button _readerFavButton;
        private bool _readerTranslationEnabled;
        private int _readerTranslationEpoch;
        private bool _translationDispatchPending;

        private sealed class ReaderImageState
        {
            public BitmapImage Original;
            public int PageIndex;
            public int TranslationEpoch = -1;
        }
        private TextBlock _readerTitleText;
        private TextBlock _readerChapterText;
        private Button _btnSpreadPairShift;
        private Button _btnDrawerSpreadEven;
        private Button _btnDrawerSpreadOdd;
        private LiquidGlassSurface _readerBottomCapsule;
        private Slider _readerSeekSlider;
        private TextBlock _readerSeekText;
        private LiquidGlassSurface _readerPageBadge;
        private TextBlock _readerPageBadgeText;
        private LiquidGlassSurface _readerLoadingBox;
        private TextBlock _readerLoadMessage;
        private Button _readerRetryButton;
        private Action _readerRetry;
        private int _readerChapterRequest;
        private int _readerRenderRequest;
        private LiquidGlassSurface _readerDrawer;
        private Slider _drawerBrightnessSlider;
        private TextBlock _drawerBrightnessText;
        private Button _btnModeLtr, _btnModeRtl, _btnModeScroll;
        private Button _btnQUltra, _btnQHigh, _btnQSmooth;
        private Button _btnSpreadSingle, _btnSpreadDouble, _btnSpreadSplit;
        private readonly Dictionary<string, Button> _animButtons = new Dictionary<string, Button>(StringComparer.Ordinal);
        private readonly Dictionary<int, Button> _prefetchButtons = new Dictionary<int, Button>();
        private CheckBox _chkApplyGlobal, _chkTrimMargins, _chkDoubleZoom, _chkShowComment, _chkShowPageNum, _chkWheelTurn, _chkSplitAll, _chkSpreadGap;

        // Reader Runtime State
        private bool _readerOpen;
        private bool _readerIsLocal;
        private ComicCardModel _readerComic;
        private List<ChapterItem> _readerChapters = new List<ChapterItem>();
        private int _readerChapterIdx;
        private List<PageImageModel> _readerPages = new List<PageImageModel>();
        private int _readerPageIdx;
        private int _spreadShift = 0;
        private int _splitHalf = 0;
        private bool _currentPageIsSplit = false;
        private int _pendingAnimDir = 0;
        private long _lastWheelTurnTick = 0;
        private double _zoomScale = 1.0;
        private bool _chromeVisible = false;
        private readonly Dictionary<int, bool> _widePageFlags = new Dictionary<int, bool>();
        private readonly Dictionary<string, BitmapImage> _readerPageCache = new Dictionary<string, BitmapImage>(StringComparer.Ordinal);
        private readonly LinkedList<string> _readerPageCacheOrder = new LinkedList<string>();
        private readonly Dictionary<string, Task<BitmapImage>> _readerPageInFlight = new Dictionary<string, Task<BitmapImage>>(StringComparer.Ordinal);
        private readonly object _readerCacheLock = new object();
        private readonly SemaphoreSlim _prefetchSemaphore = new SemaphoreSlim(2, 2);
        private const int MaxReaderMemoryCachePages = 24;
        private bool _isFullscreen;
        private WindowState _prevWinState = WindowState.Normal;
        private WindowStyle _prevWinStyle = WindowStyle.SingleBorderWindow;

        // Toast
        private LiquidGlassSurface _toastBorder;
        private TextBlock _toastText;
        private System.Windows.Threading.DispatcherTimer _toastTimer;

        public KomaMainWindow(AppServices services)
        {
            _services = services;
            _jsRunner = new VeneraJsRunner(services, Dispatcher);

            Title = "页光 Koma v" + Program.AppVersion;
            Width = 1360;
            Height = 860;
            MinWidth = 960;
            MinHeight = 620;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            UseLayoutRounding = true;
            SnapsToDevicePixels = true;
            FontFamily = new FontFamily("Microsoft YaHei UI, Segoe UI");
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Ideal);
            TextOptions.SetTextRenderingMode(this, TextRenderingMode.ClearType);
            RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.HighQuality);
            SetResourceReference(Control.BackgroundProperty, "Brush.Bg");
            SetResourceReference(Control.ForegroundProperty, "Brush.Text");

            BitmapImage iconBmp = _services.LoadAssetImage("ic_koma_logo.png", 128);
            if (iconBmp != null) Icon = iconBmp;

            _services.OnToast += delegate (string msg) { Dispatcher.InvokeAsync(delegate () { ShowToast(msg); }); };

            BuildRootLayout();
            _readingTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
            _readingTimer.Tick += delegate { FlushReadingTime(); };
            Activated += delegate { StartReadingClock(); };
            Deactivated += delegate { StopReadingClock(); };
            PreviewKeyDown += OnMainPreviewKeyDown;
            Loaded += OnMainWindowLoaded;
            Closing += delegate (object s, System.ComponentModel.CancelEventArgs e)
            {
                StopReadingClock();
                _services.SaveState();
                _jsRunner.Dispose();
            };
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            IntPtr hwnd = new WindowInteropHelper(this).Handle;
            NativeMethods.ApplyDarkTitleBar(hwnd, UiTheme.IsDarkActive);
        }

        private void OnMainWindowLoaded(object sender, RoutedEventArgs e)
        {
            // Render Home UI immediately from local/built-in data, then start headless JS worker in background
            _jsRunner.InitializeAsync();
            ApplyLibraryMaterial();
            SwitchMainTab(_services.ActiveHomeMode == "fav" ? 2 : 0);
        }

        private void BuildRootLayout()
        {
            Grid outer = new Grid();

            // Stage 1: Backdrop Layer (Background + Ambient Luminous Orbs + Wallpaper)
            // Sampled directly (depth-1, zero nested VisualBrush) by Floating Left Dock & Toast.
            _backdropStage = new Grid { IsHitTestVisible = false };
            _backdropStage.SetResourceReference(Panel.BackgroundProperty, "Brush.Bg");

            _ambientOrbLayer = BuildAmbientOrbLayer();
            _backdropStage.Children.Add(_ambientOrbLayer);

            _wallpaper = new Image { Stretch = Stretch.UniformToFill, IsHitTestVisible = false };
            _backdropStage.Children.Add(_wallpaper);

            outer.Children.Add(_backdropStage);
            outer.Children.Add(_jsRunner.CreateHiddenHost());

            // Stage 2: Right Content Host
            Grid contentHost = new Grid { Margin = new Thickness(96, 0, 0, 0) };
            _viewHome = BuildHomeView();
            _viewCollections = BuildCollectionsView();
            _viewWarehouse = BuildWarehouseView();
            _viewSettings = BuildSettingsView();
            _viewProfile = BuildReadingProfile();

            contentHost.Children.Add(_viewHome);
            contentHost.Children.Add(_viewCollections);
            contentHost.Children.Add(_viewWarehouse);
            contentHost.Children.Add(_viewSettings);
            contentHost.Children.Add(_viewProfile);
            outer.Children.Add(contentHost);

            // Stage 3: Floating Left Rail Overlay (samples _backdropStage directly — depth-1 GPU Liquid Glass)
            Grid railOverlay = new Grid { HorizontalAlignment = HorizontalAlignment.Left, Width = 96 };
            railOverlay.Children.Add(BuildLeftRail());
            outer.Children.Add(railOverlay);

            // Stage 4: Reader Overlay
            _readerOverlay = BuildReaderOverlay();
            _readerOverlay.Visibility = Visibility.Collapsed;
            outer.Children.Add(_readerOverlay);

            // Stage 5: Liquid Glass Toast Notification
            _toastText = new TextBlock { Foreground = Brushes.White, FontSize = 13 };
            _toastBorder = new LiquidGlassSurface(_backdropStage, LiquidGlassPreset.SubtlePill, 14, true, false)
            {
                ContentPadding = new Thickness(16, 10, 16, 10),
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(0, 0, 26, 26),
                Visibility = Visibility.Collapsed,
                Child = _toastText
            };
            outer.Children.Add(_toastBorder);

            Content = outer;
        }

        private static Grid BuildAmbientOrbLayer()
        {
            Grid g = new Grid { IsHitTestVisible = false, ClipToBounds = true };
            // Subtle luminous ambient gradients so Liquid Glass refraction always has soft light transitions even without custom wallpaper
            RadialGradientBrush orb1 = new RadialGradientBrush(Color.FromArgb(68, 56, 152, 255), Color.FromArgb(0, 56, 152, 255));
            orb1.Freeze();
            g.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Width = 460, Height = 460, Fill = orb1,
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(-140, -110, 0, 0)
            });

            RadialGradientBrush orb2 = new RadialGradientBrush(Color.FromArgb(52, 134, 108, 255), Color.FromArgb(0, 134, 108, 255));
            orb2.Freeze();
            g.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Width = 420, Height = 420, Fill = orb2,
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(-90, 0, 0, -130)
            });

            RadialGradientBrush orb3 = new RadialGradientBrush(Color.FromArgb(46, 56, 189, 248), Color.FromArgb(0, 56, 189, 248));
            orb3.Freeze();
            g.Children.Add(new System.Windows.Shapes.Ellipse
            {
                Width = 520, Height = 520, Fill = orb3,
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, -160, -120, 0)
            });
            return g;
        }

        private UIElement BuildLeftRail()
        {
            Grid rail = new Grid { Margin = new Thickness(8, 14, 8, 12) };
            _profileDockButton = new Button
            {
                Width = 48, Height = 48,
                Background = Brushes.Transparent, BorderThickness = new Thickness(0), Padding = new Thickness(0),
                ToolTip = "个人", Cursor = Cursors.Hand, FocusVisualStyle = null,
                Template = UiTheme.CreateRoundedButtonTemplate(24, "Brush.NavGlassSelected")
            };
            System.Windows.Automation.AutomationProperties.SetName(_profileDockButton, "打开个人页");
            _profileDockButton.Content = BuildAvatar(44);
            _profileDockButton.Click += delegate { SwitchMainTab(5); };
            LiquidGlassSurface profilePlate = MakeDockPlate(_profileDockButton, 24, true);
            profilePlate.Width = 50;
            profilePlate.Height = 50;
            profilePlate.VerticalAlignment = VerticalAlignment.Top;
            profilePlate.HorizontalAlignment = HorizontalAlignment.Center;
            rail.Children.Add(profilePlate);

            StackPanel dock = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            StackPanel destinations = new StackPanel();
            LiquidGlassSurface capsule = MakeDockPlate(destinations, 34, false);
            capsule.ContentPadding = new Thickness(6, 7, 6, 7);
            dock.Children.Add(capsule);

            string[] icons = new string[] { "\uE80F", "\uE8A5", "\uEB51", "\uE713" };
            string[] labels = new string[] { "首页", "本地书架", "在线收藏", "设置" };
            _railButtons = new Button[4];
            for (int index = 0; index < labels.Length; index++)
            {
                int tab = index;
                bool settings = index == 3;
                StackPanel stack = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
                stack.Children.Add(new TextBlock
                {
                    Text = icons[index], FontFamily = new FontFamily("Segoe MDL2 Assets"), FontSize = settings ? 20 : 18,
                    HorizontalAlignment = HorizontalAlignment.Center
                });
                if (!settings)
                    stack.Children.Add(new TextBlock { Text = labels[index], FontSize = 11.5, FontWeight = FontWeights.Medium,
                        HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 4, 0, 0) });
                Button button = new Button
                {
                    Width = settings ? 54 : 62, Height = settings ? 54 : 57,
                    Content = stack, Background = Brushes.Transparent, BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
                    FocusVisualStyle = null,
                    HorizontalContentAlignment = HorizontalAlignment.Center, VerticalContentAlignment = VerticalAlignment.Center,
                    Template = UiTheme.CreateRoundedButtonTemplate(settings ? 28 : 24, "Brush.NavGlassSelected"),
                    ToolTip = labels[index]
                };
                System.Windows.Automation.AutomationProperties.SetName(button, labels[index]);
                button.Click += delegate { SwitchMainTab(tab); };
                _railButtons[index] = button;
                if (settings)
                {
                    LiquidGlassSurface plate = MakeDockPlate(button, 28, true);
                    plate.HorizontalAlignment = HorizontalAlignment.Center;
                    plate.Margin = new Thickness(0, 12, 0, 0);
                    dock.Children.Add(plate);
                }
                else destinations.Children.Add(button);
            }
            rail.Children.Add(dock);
            return rail;
        }

        private LiquidGlassSurface MakeDockPlate(UIElement content, double radius, bool elastic)
        {
            return new LiquidGlassSurface(_backdropStage, LiquidGlassPreset.FloatingDock, radius, false, elastic)
            {
                Child = content
            };
        }

        private void SwitchMainTab(int idx)
        {
            _viewHome.Visibility = idx == 0 || idx == 2 ? Visibility.Visible : Visibility.Collapsed;
            _viewCollections.Visibility = idx == 1 ? Visibility.Visible : Visibility.Collapsed;
            _viewWarehouse.Visibility = idx == 4 ? Visibility.Visible : Visibility.Collapsed;
            _viewSettings.Visibility = idx == 3 ? Visibility.Visible : Visibility.Collapsed;
            _viewProfile.Visibility = idx == 5 ? Visibility.Visible : Visibility.Collapsed;
            if (idx == 5) RefreshReadingProfile();

            for (int i = 0; i < _railButtons.Length; i++)
            {
                Button b = _railButtons[i];
                if (i == idx || idx == 4 && i == 3)
                {
                    b.SetResourceReference(Control.BackgroundProperty, "Brush.NavGlassSelected");
                    b.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
                }
                else
                {
                    b.Background = Brushes.Transparent;
                    b.SetResourceReference(Control.ForegroundProperty, "Brush.TextMuted");
                }
            }
            if (idx == 0)
            {
                string source = _services.ActiveHomeMode == "history" ? "history" : LastEnabledSource();
                SwitchHomeMode(source);
            }
            if (idx == 1) RenderCollectionsView();
            if (idx == 2) SwitchHomeMode("fav");
            if (idx == 4) RenderWarehouseView();
            if (SystemParameters.ClientAreaAnimation)
            {
                Grid selected = idx == 1 ? _viewCollections : idx == 3 ? _viewSettings : idx == 4 ? _viewWarehouse : idx == 5 ? _viewProfile : _viewHome;
                selected.BeginAnimation(UIElement.OpacityProperty, new System.Windows.Media.Animation.DoubleAnimation(
                    0.76, 1, TimeSpan.FromMilliseconds(180)) { EasingFunction = new System.Windows.Media.Animation.CubicEase() });
            }
        }

        private Grid BuildHomeView()
        {
            Grid g = new Grid();

            // Layer 0: Full-bleed Home Backdrop Stage (direct background + shared frozen wallpaper + live scrolling comic feed)
            // Zero nested VisualBrush: floating Search Bar & Unified Browse Bar sample this stage directly (depth-1).
            Grid homeBackdrop = new Grid();
            homeBackdrop.SetResourceReference(Panel.BackgroundProperty, "Brush.Bg");
            _homeAmbientOrbLayer = BuildAmbientOrbLayer();
            homeBackdrop.Children.Add(_homeAmbientOrbLayer);
            _homeWallpaper = new Image { Stretch = Stretch.UniformToFill, IsHitTestVisible = false };
            homeBackdrop.Children.Add(_homeWallpaper);

            _homeFeedScroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(20, 6, 20, 24) };
            StackPanel feedStack = new StackPanel { Margin = new Thickness(0, 126, 0, 0) };

            _statusBanner = new TextBlock
            {
                FontSize = 13,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 0, 0, 10),
                Visibility = Visibility.Collapsed
            };
            _statusBanner.SetResourceReference(TextBlock.ForegroundProperty, "Brush.Accent");
            feedStack.Children.Add(_statusBanner);
            _retryFeedButton = UiTheme.MakeButton("重试", false, false);
            _retryFeedButton.HorizontalAlignment = HorizontalAlignment.Left;
            _retryFeedButton.Visibility = Visibility.Collapsed;
            _retryFeedButton.Click += delegate { LoadActiveFeedAsync(_currentPage); };
            feedStack.Children.Add(_retryFeedButton);

            _comicGrid = new StackPanel();
            feedStack.Children.Add(_comicGrid);

            _emptyFeedText = new TextBlock
            {
                TextWrapping = TextWrapping.Wrap,
                FontSize = 14,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 64, 0, 0),
                Visibility = Visibility.Collapsed
            };
            _emptyFeedText.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            feedStack.Children.Add(_emptyFeedText);

            _paginationBar = new Grid { Margin = new Thickness(0, 14, 0, 8), HorizontalAlignment = HorizontalAlignment.Center, Visibility = Visibility.Collapsed };
            ProgressBar more = new ProgressBar { Width = 96, Height = 3, IsIndeterminate = true };
            _paginationBar.Children.Add(more);
            feedStack.Children.Add(_paginationBar);
            _homeFeedScroll.ScrollChanged += delegate { QueueNextSourcePage(); };

            _homeFeedScroll.Content = feedStack;
            homeBackdrop.Children.Add(_homeFeedScroll);
            g.Children.Add(homeBackdrop);

            // Layer 1: Floating Liquid Glass Header (Search Bar + Unified Browse Bar)
            StackPanel floatingHeader = new StackPanel { VerticalAlignment = VerticalAlignment.Top };
            floatingHeader.SizeChanged += delegate
            {
                if (floatingHeader.ActualHeight > 20)
                {
                    feedStack.Margin = new Thickness(0, floatingHeader.ActualHeight + 2, 0, 0);
                }
            };

            // Row 0: Search Toolbar
            Grid toolbar = new Grid { Margin = new Thickness(20, 14, 20, 10) };
            toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            LiquidGlassSurface searchBorder = new LiquidGlassSurface(homeBackdrop, LiquidGlassPreset.FrostedBar, 22, false, false)
            {
                ContentPadding = new Thickness(14, 4, 6, 4)
            };

            DockPanel searchDock = new DockPanel();
            _btnSearch = UiTheme.MakeButton("搜索", true, false);
            _btnSearch.Padding = new Thickness(16, 5, 16, 5);
            DockPanel.SetDock(_btnSearch, Dock.Right);
            searchDock.Children.Add(_btnSearch);

            _searchBox = new TextBox
            {
                BorderThickness = new Thickness(0),
                Background = Brushes.Transparent,
                VerticalContentAlignment = VerticalAlignment.Center,
                FontSize = 13.5,
                Padding = new Thickness(4, 2, 8, 2)
            };
            _searchBox.SetResourceReference(TextBox.ForegroundProperty, "Brush.Text");
            _searchBox.SetResourceReference(TextBox.CaretBrushProperty, "Brush.Text");
            _searchBox.KeyDown += delegate (object s, KeyEventArgs e)
            {
                if (e.Key == Key.Enter) SubmitHomeSearch();
            };
            Grid searchField = new Grid();
            searchField.Children.Add(_searchBox);
            _searchHint = new TextBlock
            {
                Text = "搜索当前漫画源", VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(4, 0, 0, 0), IsHitTestVisible = false
            };
            _searchHint.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            searchField.Children.Add(_searchHint);
            _searchBox.TextChanged += delegate { _searchHint.Visibility = _searchBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed; };
            searchDock.Children.Add(searchField);
            searchBorder.Child = searchDock;
            toolbar.Children.Add(searchBorder);

            _btnSearch.Click += delegate (object s, RoutedEventArgs e) { SubmitHomeSearch(); };

            StackPanel rightBtns = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(12, 0, 0, 0) };
            Grid.SetColumn(rightBtns, 1);
            Button btnRefresh = UiTheme.MakeButton("↻ 刷新", false, false);
            btnRefresh.Margin = new Thickness(0, 0, 8, 0);
            btnRefresh.Click += delegate (object s, RoutedEventArgs e) { LoadActiveFeedAsync(1); };
            Button btnDensity = UiTheme.MakeButton("⊞ 网格", false, false);
            btnDensity.Click += delegate (object s, RoutedEventArgs e)
            {
                _services.GridCompact = !_services.GridCompact;
                _services.SaveState();
                LoadActiveFeedAsync(1);
            };
            rightBtns.Children.Add(btnRefresh);
            rightBtns.Children.Add(btnDensity);
            toolbar.Children.Add(rightBtns);
            floatingHeader.Children.Add(toolbar);

            // Row 1: Unified Single-Row Liquid Glass Source + Channel + Category Bar
            _browseBar = new LiquidGlassSurface(homeBackdrop, LiquidGlassPreset.FrostedBar, 20, false, false)
            {
                ContentPadding = new Thickness(10, 6, 10, 6),
                Margin = new Thickness(20, 0, 20, 10)
            };

            DockPanel unifiedRow = new DockPanel { LastChildFill = true };

            // Left: Source switcher pill & mode title
            _chipsPanel = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            DockPanel.SetDock(_chipsPanel, Dock.Left);
            unifiedRow.Children.Add(_chipsPanel);

            // Right: Category, Filter, More actions
            StackPanel tools = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 0, 0) };
            DockPanel.SetDock(tools, Dock.Right);
            _categoriesButton = AddBrowseButton(tools, "分类 ▾", OpenBrowseCategoryGroups);
            _categoriesButton.Margin = new Thickness(0, 0, 6, 0);
            _sourceFiltersButton = AddBrowseButton(tools, "筛选", OpenBrowseFilters);
            _sourceFiltersButton.Margin = new Thickness(0, 0, 6, 0);
            _sourceActionsButton = AddBrowseButton(tools, "更多 ⋯", OpenBrowseActions);
            _sourceActionsButton.Margin = new Thickness(0);
            unifiedRow.Children.Add(tools);

            // Center: Scrollable channel tabs + active category indicator
            _sourceHeading = new TextBlock { Visibility = Visibility.Collapsed };
            _channelTabs = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            ScrollViewer channelScroll = new ScrollViewer
            {
                Content = _channelTabs,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
                VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
                VerticalAlignment = VerticalAlignment.Center
            };
            unifiedRow.Children.Add(channelScroll);

            _browseBar.Child = unifiedRow;
            floatingHeader.Children.Add(_browseBar);
            g.Children.Add(floatingHeader);

            return g;
        }

        private void RebuildHomeChips()
        {
            _chipsPanel.Children.Clear();
            if (_services.ActiveHomeMode == "fav")
            {
                TextBlock favTitle = new TextBlock
                {
                    Text = "★ 在线收藏",
                    FontSize = 15,
                    FontWeight = FontWeights.Bold,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(6, 2, 10, 2)
                };
                favTitle.SetResourceReference(TextBlock.ForegroundProperty, "Brush.Text");
                _chipsPanel.Children.Add(favTitle);
                if (_categoriesButton != null) _categoriesButton.Visibility = Visibility.Collapsed;
                if (_sourceFiltersButton != null) _sourceFiltersButton.Visibility = Visibility.Collapsed;
                if (_sourceActionsButton != null) _sourceActionsButton.Visibility = Visibility.Visible;
                return;
            }
            SourceItem active = _services.Sources.Find(source => source.Key == LastEnabledSource());
            if (active != null)
            {
                AddChipButton(active.Name + " ▾", _services.ActiveHomeMode == active.Key, false, OpenQuickSourcePicker);
            }
            else
            {
                AddChipButton("选择漫画源 ▾", false, true, OpenQuickSourcePicker);
            }
            if (_services.ActiveHomeMode == "history")
            {
                AddChipButton("最近阅读", true, false, delegate { SwitchHomeMode("history"); });
                if (active != null)
                {
                    AddChipButton("返回 " + active.Name, false, true, delegate { SwitchHomeMode(active.Key); });
                }
            }
            if (!IsLocalHomeMode(_services.ActiveHomeMode))
            {
                Border divider = new Border
                {
                    Width = 1,
                    Height = 18,
                    Margin = new Thickness(2, 0, 10, 0),
                    VerticalAlignment = VerticalAlignment.Center
                };
                divider.SetResourceReference(Border.BackgroundProperty, "Brush.Border");
                _chipsPanel.Children.Add(divider);
            }
        }

        private string LastEnabledSource()
        {
            SourceItem selected = _services.Sources.Find(source => source.Enabled && source.Key == _services.LastSource)
                ?? _services.Sources.Find(source => source.Enabled);
            return selected == null ? "local" : selected.Key;
        }

        private void QueueNextSourcePage()
        {
            if (_restoringSourceFeed || !_feedHasMore || _feedBusy || _feedPageFailed || !_viewHome.IsVisible ||
                _homeFeedScroll.ScrollableHeight - _homeFeedScroll.VerticalOffset > 420) return;
            Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(delegate
            {
                if (!_restoringSourceFeed && _feedHasMore && !_feedBusy && !_feedPageFailed && _viewHome.IsVisible &&
                    _homeFeedScroll.ScrollableHeight - _homeFeedScroll.VerticalOffset <= 420)
                    LoadActiveFeedAsync(_currentPage + 1);
            }));
        }

        private void AddChipButton(string label, bool active, bool actionStyle, Action onClick)
        {
            Button b = new Button
            {
                Content = label,
                Margin = new Thickness(0, 0, 8, 0),
                Padding = new Thickness(13, 6, 13, 6),
                FontSize = 12.5,
                FontWeight = active ? FontWeights.Bold : FontWeights.SemiBold,
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand,
                FocusVisualStyle = null,
                Template = UiTheme.CreateRoundedButtonTemplate(16, active ? null : "Brush.SurfaceHover")
            };
            if (active)
            {
                b.SetResourceReference(Control.BackgroundProperty, "Brush.AccentSoft");
                b.SetResourceReference(Control.BorderBrushProperty, "Brush.AccentBorder");
                b.SetResourceReference(Control.ForegroundProperty, "Brush.Accent");
            }
            else if (actionStyle)
            {
                b.SetResourceReference(Control.BackgroundProperty, "Brush.Surface");
                b.SetResourceReference(Control.BorderBrushProperty, "Brush.Border");
                b.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            }
            else
            {
                b.SetResourceReference(Control.BackgroundProperty, "Brush.BgElevated");
                b.SetResourceReference(Control.BorderBrushProperty, "Brush.Border");
                b.SetResourceReference(Control.ForegroundProperty, "Brush.TextMuted");
            }
            b.Click += delegate (object s, RoutedEventArgs e) { onClick(); };
            _chipsPanel.Children.Add(b);
        }

        private void SwitchHomeMode(string mode)
        {
            bool changed = mode != _services.ActiveHomeMode;
            if (!changed && !IsLocalHomeMode(mode) && (_completedFeedPage > 0 || _feedBusy)) return;
            if (changed)
            {
                RememberSourceFeed(_services.ActiveHomeMode);
                _feedRequestId++;
                _categoryRequestId++;
                _browseSourceKey = null;
                _browseMenu = null;
                _browseTarget = null;
                _filterTarget = null;
                _browseFilters = null;
                _completedFeedPage = 0;
                _homeQuery = "";
                _searchBox.Clear();
            }
            _services.ActiveHomeMode = mode;
            if (!IsLocalHomeMode(mode)) _services.LastSource = mode;
            _services.SaveState();
            RebuildHomeChips();
            if (!RestoreSourceFeed(mode)) LoadActiveFeedAsync(1);
        }

        private void RememberSourceFeed(string key)
        {
            if (IsLocalHomeMode(key) || _browseSourceKey != key || _browseMenu == null || _completedFeedPage == 0 ||
                _homeQuery != _loadedFeedQuery) return;
            _sourceFeedMemory[key] = new SourceFeedSnapshot
            {
                Menu = _browseMenu, Target = _browseTarget, FilterTarget = _filterTarget, Filters = _browseFilters,
                Query = _homeQuery, Page = _completedFeedPage, RetryPage = _feedPageFailed ? _currentPage : 0,
                HasMore = _completedCanAdvance, Offset = _homeFeedScroll.VerticalOffset,
                Cursors = new Dictionary<int, string>(_feedCursors), Sections = new List<SourceFeedSection>(_sourceFeedSections)
            };
            _sourceFeedOrder.Remove(key);
            _sourceFeedOrder.AddLast(key);
            if (_sourceFeedOrder.Count <= 6) return;
            _sourceFeedMemory.Remove(_sourceFeedOrder.First.Value);
            _sourceFeedOrder.RemoveFirst();
        }

        private bool RestoreSourceFeed(string key)
        {
            SourceFeedSnapshot snapshot;
            if (IsLocalHomeMode(key) || !_sourceFeedMemory.TryGetValue(key, out snapshot)) return false;
            _sourceFeedOrder.Remove(key);
            _sourceFeedOrder.AddLast(key);
            _restoringSourceFeed = true;
            _browseSourceKey = key;
            _browseMenu = snapshot.Menu;
            _browseTarget = snapshot.Target;
            _filterTarget = snapshot.FilterTarget;
            _browseFilters = snapshot.Filters;
            _homeQuery = snapshot.Query;
            _loadedFeedQuery = snapshot.Query;
            _searchBox.Text = snapshot.Query;
            _completedFeedPage = snapshot.Page;
            _completedCanAdvance = snapshot.HasMore;
            _currentPage = snapshot.RetryPage > 0 ? snapshot.RetryPage : snapshot.Page;
            _feedHasMore = snapshot.HasMore;
            _feedPageFailed = snapshot.RetryPage > 0;
            _feedBusy = false;
            _feedCursors.Clear();
            foreach (KeyValuePair<int, string> cursor in snapshot.Cursors) _feedCursors.Add(cursor.Key, cursor.Value);
            _seenFeedItems.Clear();
            _comicGrid.Children.Clear();
            _sourceFeedSections.Clear();
            _sourceFeedSections.AddRange(snapshot.Sections);
            foreach (SourceFeedSection section in snapshot.Sections)
            {
                foreach (ComicCardModel card in section.Cards) _seenFeedItems.Add(card.Uid);
                if (!string.IsNullOrWhiteSpace(section.Title) || section.Target != null) RenderSectionHeader(section);
                AppendFeedCards(section.Cards);
            }
            _browseBar.Visibility = Visibility.Visible;
            _sourceHeading.Text = snapshot.FilterTarget == null ? key : snapshot.FilterTarget.Title;
            _paginationBar.Visibility = Visibility.Collapsed;
            _statusBanner.Text = "续页加载失败";
            _statusBanner.Visibility = _retryFeedButton.Visibility = _feedPageFailed ? Visibility.Visible : Visibility.Collapsed;
            _emptyFeedText.Visibility = Visibility.Collapsed;
            if (_seenFeedItems.Count == 0 && !_feedHasMore)
                ShowEmptyFeed(snapshot.Query.Length > 0 ? "没有找到匹配的漫画" : "这个栏目暂无漫画");
            RenderBrowseControls(snapshot.Menu, snapshot.FilterTarget, snapshot.Filters != null && snapshot.Filters.Fields.Count > 0);
            _homeFeedScroll.UpdateLayout();
            _homeFeedScroll.ScrollToVerticalOffset(snapshot.Offset);
            Dispatcher.BeginInvoke(DispatcherPriority.ContextIdle, new Action(delegate { _restoringSourceFeed = false; }));
            return true;
        }

        private static bool IsLocalHomeMode(string mode)
        {
            return mode == "local" || mode == "fav" || mode == "history";
        }

        private void SubmitHomeSearch()
        {
            _homeQuery = (_searchBox.Text ?? "").Trim();
            LoadActiveFeedAsync(1);
        }

        private void SelectBrowseTarget(SourceBrowseTarget target)
        {
            _categoryRequestId++;
            _homeQuery = "";
            _searchBox.Clear();
            _browseTarget = target;
            LoadActiveFeedAsync(1);
        }

        private Button AddBrowseButton(Panel panel, string text, Action onClick)
        {
            Button button = UiTheme.MakeButton(text, false, false);
            button.Margin = new Thickness(0, 0, 8, 4);
            button.Click += delegate { onClick(); };
            panel.Children.Add(button);
            return button;
        }

        private void OpenComicWebsite(string url)
        {
            Uri uri;
            if (!Uri.TryCreate(url, UriKind.Absolute, out uri) || (uri.Scheme != "https" && uri.Scheme != "http"))
            {
                ShowToast("这个漫画源未提供官方网站");
                return;
            }
            try { Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true }); }
            catch (Exception ex) { ShowToast("无法打开网址：" + ex.Message); }
        }

        private void RenderBrowseControls(SourceBrowseMenu menu, SourceBrowseTarget target, bool filters)
        {
            _searchBox.IsEnabled = _btnSearch.IsEnabled = menu != null && menu.CanSearch;
            _searchBox.ToolTip = menu == null ? "正在读取漫画源" : menu.CanSearch ? "搜索当前漫画源" : "当前源不支持搜索";
            System.Windows.Automation.AutomationProperties.SetName(_searchBox, (string)_searchBox.ToolTip);
            _channelTabs.Children.Clear();
            SourceItem activeSrc = _services.Sources.Find(s => s.Key == _services.ActiveHomeMode);
            string activeSrcName = activeSrc != null ? activeSrc.Name : "";

            if (menu != null)
            {
                List<SourceBrowseTarget> channels = new List<SourceBrowseTarget>(menu.Channels);
                if (menu.Ranking != null) channels.Add(menu.Ranking);

                // Smart deduplication: if there is only 1 channel and its title matches the source name,
                // don't repeat it redundantly next to the source selector pill (only show a compact "全部" return chip when in category/search).
                bool singleDuplicate = channels.Count == 1 &&
                    string.Equals(channels[0].Title, activeSrcName, StringComparison.OrdinalIgnoreCase);

                if (singleDuplicate)
                {
                    if (target != null && (target.Kind == "category" || target.Kind == "search"))
                    {
                        SourceBrowseTarget homeCh = channels[0];
                        AddChipTo(_channelTabs, "首页推荐", false, delegate { SelectBrowseTarget(homeCh); });
                    }
                }
                else
                {
                    foreach (SourceBrowseTarget channel in channels)
                        AddChipTo(_channelTabs, channel.Title, SameBrowseTarget(target, channel), delegate { SelectBrowseTarget(channel); });
                }

                if (target != null && target.Kind == "category")
                {
                    SourceBrowseTarget defaultTarget = channels.Count > 0 ? channels[0] : null;
                    string catLabel = "分类：" + target.Title + (defaultTarget != null ? "  ✕" : "");
                    Button catChip = UiTheme.MakeButton(catLabel, true, false);
                    catChip.Margin = new Thickness(0, 0, 6, 0);
                    catChip.Padding = new Thickness(12, 5, 12, 5);
                    catChip.FontSize = 12.5;
                    catChip.ToolTip = defaultTarget != null ? "点击退出当前分类" : target.Title;
                    catChip.Click += delegate
                    {
                        if (defaultTarget != null) SelectBrowseTarget(defaultTarget);
                        else OpenBrowseCategoryGroups();
                    };
                    _channelTabs.Children.Add(catChip);
                }
                else if (target != null && target.Kind == "search" && !string.IsNullOrEmpty(target.Keyword))
                {
                    Button searchChip = UiTheme.MakeButton("搜索：" + target.Keyword + "  ✕", true, false);
                    searchChip.Margin = new Thickness(0, 0, 6, 0);
                    searchChip.Padding = new Thickness(12, 5, 12, 5);
                    searchChip.FontSize = 12.5;
                    searchChip.Click += delegate { _searchBox.Clear(); SubmitHomeSearch(); };
                    _channelTabs.Children.Add(searchChip);
                }
            }

            _categoriesButton.IsEnabled = true;
            _categoriesButton.Content = (target != null && target.Kind == "category") ? "切换分类 ▾" : "分类 ▾";
            _categoriesButton.Visibility = menu != null && menu.Groups.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            _sourceFiltersButton.Visibility = filters ? Visibility.Visible : Visibility.Collapsed;
            _sourceHeading.Visibility = Visibility.Collapsed;
            _searchHint.Text = menu == null || menu.CanSearch ? "搜索当前漫画源" : "当前源不支持搜索";
        }

        private void AddChipTo(Panel panel, string label, bool selected, Action action)
        {
            Button button = UiTheme.MakeButton(label, selected, false);
            button.Margin = new Thickness(0, 0, 6, 0);
            button.Padding = new Thickness(12, 5, 12, 5);
            button.FontSize = 12.5;
            button.Click += delegate { action(); };
            panel.Children.Add(button);
        }

        private void OpenBrowseActions()
        {
            ContextMenu menu = new ContextMenu { PlacementTarget = _sourceActionsButton, Placement = PlacementMode.Bottom };
            Action<string, Action> add = delegate (string title, Action action)
            {
                MenuItem item = new MenuItem { Header = title };
                item.Click += delegate { action(); };
                menu.Items.Add(item);
            };
            add("最近阅读", delegate { SwitchHomeMode("history"); });
            if (_homeQuery.Length > 0) add("清除搜索", delegate { _searchBox.Clear(); SubmitHomeSearch(); });
            if (!IsLocalHomeMode(_services.ActiveHomeMode) && _browseMenu != null && !string.IsNullOrEmpty(_browseMenu.Website))
                add(_browseMenu.Website == _browseMenu.AppWebsite ? "官网 / 官方 App" : "官方网站",
                    delegate { OpenComicWebsite(_browseMenu.Website); });
            if (!IsLocalHomeMode(_services.ActiveHomeMode) && _browseMenu != null && !string.IsNullOrEmpty(_browseMenu.AppWebsite) && _browseMenu.AppWebsite != _browseMenu.Website)
                add("官方 App", delegate { OpenComicWebsite(_browseMenu.AppWebsite); });
            menu.IsOpen = true;
        }

        private async void LoadActiveFeedAsync(int page)
        {
            int request = ++_feedRequestId;
            _currentPage = Math.Max(1, page);
            page = _currentPage;
            bool reset = page == 1;
            _feedBusy = true;
            _feedHasMore = false;
            _feedPageFailed = false;
            if (reset)
            {
                _sourceFeedMemory.Remove(_services.ActiveHomeMode);
                _sourceFeedOrder.Remove(_services.ActiveHomeMode);
                _completedFeedPage = 0;
                _completedCanAdvance = false;
                _loadedFeedQuery = _homeQuery;
                _sourceFeedSections.Clear();
                _feedCursors.Clear();
                _seenFeedItems.Clear();
                _homeFeedScroll.ScrollToTop();
                _comicGrid.Children.Clear();
            }
            string mode = _services.ActiveHomeMode;
            string kw = _homeQuery;

            _emptyFeedText.Visibility = Visibility.Collapsed;
            _paginationBar.Visibility = Visibility.Collapsed;
            _statusBanner.Visibility = Visibility.Collapsed;
            _retryFeedButton.Visibility = Visibility.Collapsed;

            if (IsLocalHomeMode(mode))
            {
                _browseBar.Visibility = Visibility.Visible;
                _channelTabs.Children.Clear();
                _categoriesButton.Visibility = Visibility.Collapsed;
                _sourceFiltersButton.Visibility = Visibility.Collapsed;
                _searchBox.IsEnabled = _btnSearch.IsEnabled = true;
                _searchBox.ToolTip = "搜索当前书库";
                _searchHint.Text = mode == "fav" ? "搜索在线收藏" : "搜索当前书库";
                System.Windows.Automation.AutomationProperties.SetName(_searchBox, "搜索当前书库");
                List<ComicCardModel> list;
                if (mode == "local")
                {
                    HashSet<string> hidden = new HashSet<string>(StringComparer.Ordinal);
                    foreach (CollectionModel col in _services.Collections)
                    {
                        if (col.HideFromAll && col.Items != null)
                        {
                            foreach (ComicCardModel it in col.Items) hidden.Add(it.Uid);
                        }
                    }
                    list = _services.GetLocalComicCards().FindAll(delegate (ComicCardModel m) { return !hidden.Contains(m.Uid); });
                }
                else
                {
                    list = mode == "fav" ? _services.Favorites.FindAll(card => !card.IsLocal && !HiddenFromOnlineFavorites(card))
                        : new List<ComicCardModel>(_services.History);
                }

                if (!string.IsNullOrEmpty(kw))
                {
                    string lq = kw.ToLowerInvariant();
                    list = list.FindAll(delegate (ComicCardModel c)
                    {
                        return (c.Title ?? "").ToLowerInvariant().Contains(lq) || (c.SubTitle ?? "").ToLowerInvariant().Contains(lq);
                    });
                }
                if (list.Count == 0) ShowEmptyFeed(kw.Length > 0 ? "没有找到匹配的漫画" : "这里暂无漫画");
                else RenderComicCards(list);
                _feedBusy = false;
                return;
            }

            SourceItem src = _services.Sources.Find(source => source.Key == mode && source.Enabled);
            if (src == null)
            {
                _browseBar.Visibility = Visibility.Visible;
                RenderBrowseControls(null, null, false);
                ShowEmptyFeed("请点击左上角切换一个可用漫画源");
                _feedBusy = false;
                return;
            }

            _browseBar.Visibility = Visibility.Visible;
            _sourceHeading.Text = src.Name;
            _sourceHeading.ToolTip = src.Name;
            SourceBrowseMenu menu = _browseSourceKey == mode ? _browseMenu : null;
            RenderBrowseControls(menu, null, false);
            _statusBanner.Text = "正在加载…";
            _statusBanner.Visibility = reset ? Visibility.Visible : Visibility.Collapsed;
            _paginationBar.Visibility = reset ? Visibility.Collapsed : Visibility.Visible;

            try
            {
                if (menu == null) menu = await _jsRunner.GetBrowseMenuAsync(src);
                if (request != _feedRequestId) return;
                _browseMenu = menu;
                _browseSourceKey = mode;
                SourceBrowseTarget target = kw.Length > 0 && menu.CanSearch
                    ? new SourceBrowseTarget { Kind = "search", Title = "搜索结果", Keyword = kw }
                    : _browseTarget ?? (menu.Channels.Count > 0 ? menu.Channels[0] : menu.Ranking);
                RenderBrowseControls(menu, target, false);
                if (target == null)
                {
                    _statusBanner.Visibility = Visibility.Collapsed;
                    ShowEmptyFeed(menu.Groups.Count > 0 ? "选择一个分类开始浏览"
                        : menu.CanSearch ? "输入关键词搜索此漫画源" : "此漫画源暂无可用的浏览或搜索入口");
                    return;
                }

                _sourceHeading.Text = src.Name == target.Title ? src.Name : src.Name + " · " + target.Title;
                _sourceHeading.ToolTip = _sourceHeading.Text;
                if (!SameBrowseTarget(_filterTarget, target))
                {
                    _browseFilters = null;
                    _filterTarget = target;
                }
                SourceBrowseFilters filters = _browseFilters ?? await _jsRunner.GetBrowseFiltersAsync(src, target);
                if (request != _feedRequestId) return;
                _browseFilters = filters;
                RenderBrowseControls(menu, target, filters.Fields.Count > 0);

                string cursor;
                _feedCursors.TryGetValue(page, out cursor);
                SourceBrowsePage feed = await _jsRunner.LoadBrowsePageAsync(src, target, filters.Values, page, cursor);
                if (request != _feedRequestId) return;
                _statusBanner.Visibility = Visibility.Collapsed;

                if (feed.NextCursor != null) _feedCursors[page + 1] = feed.NextCursor;
                else _feedCursors.Remove(page + 1);
                int added = 0;
                foreach (SourceFeedSection section in feed.Sections)
                {
                    List<ComicCardModel> novel = section.Cards.FindAll(card => _seenFeedItems.Add(card.Uid));
                    if (novel.Count == 0) continue;
                    added += novel.Count;
                    _sourceFeedSections.Add(new SourceFeedSection { Title = section.Title, Target = section.Target, Cards = novel });
                    if (!string.IsNullOrWhiteSpace(section.Title) || section.Target != null) RenderSectionHeader(section);
                    AppendFeedCards(novel);
                }
                _feedHasMore = (added > 0 || reset) && (feed.NextCursor != null || (feed.MaxPage.HasValue
                    ? page < feed.MaxPage.Value : added > 0));
                _completedFeedPage = page;
                _completedCanAdvance = _feedHasMore;
                if (_seenFeedItems.Count == 0 && !_feedHasMore)
                    ShowEmptyFeed(target.Kind == "search" ? "没有找到匹配的漫画" : "这个栏目暂无漫画");
            }
            catch (Exception ex)
            {
                if (request != _feedRequestId) return;
                _feedPageFailed = true;
                _statusBanner.Text = "加载失败\n" + BrowseError(ex);
                _statusBanner.Visibility = Visibility.Visible;
                _retryFeedButton.Visibility = Visibility.Visible;
                if (!reset) ShowToast("续页加载失败，按刷新重试");
            }
            finally
            {
                if (request == _feedRequestId)
                {
                    _feedBusy = false;
                    _paginationBar.Visibility = Visibility.Collapsed;
                    QueueNextSourcePage();
                }
            }
        }

        private void AppendFeedCards(List<ComicCardModel> cards)
        {
            WrapPanel last = _comicGrid.Children.Count == 0 ? null : _comicGrid.Children[_comicGrid.Children.Count - 1] as WrapPanel;
            if (last == null) { RenderComicCards(cards); return; }
            double width = _services.GridCompact ? 142 : 172;
            double height = _services.GridCompact ? 184 : 226;
            foreach (ComicCardModel card in cards) last.Children.Add(BuildComicCardElement(card, width, height));
        }

        private bool HiddenFromOnlineFavorites(ComicCardModel card)
        {
            foreach (CollectionModel shelf in _services.Collections)
            {
                bool unlocked;
                _services.UnlockedCollections.TryGetValue(shelf.Id, out unlocked);
                if ((shelf.HideFromAll || !string.IsNullOrEmpty(shelf.Pin) && !unlocked) && shelf.Items.Exists(item => item.Uid == card.Uid))
                    return true;
            }
            return false;
        }

        private static bool SameBrowseTarget(SourceBrowseTarget first, SourceBrowseTarget second)
        {
            if (ReferenceEquals(first, second)) return true;
            if (first == null || second == null) return false;
            if (!string.Equals(first.Kind, second.Kind, StringComparison.Ordinal)) return false;
            if (first.Kind == "explore") return first.Index == second.Index;
            if (first.Kind == "ranking") return true;
            if (first.Kind == "category")
                return string.Equals(first.Category, second.Category, StringComparison.Ordinal) &&
                    string.Equals(first.Param ?? "", second.Param ?? "", StringComparison.Ordinal);
            if (first.Kind == "search")
                return string.Equals(first.Keyword, second.Keyword, StringComparison.Ordinal) &&
                    first.Options == null && second.Options == null;
            return false;
        }

        private static string BrowseError(Exception error)
        {
            string firstLine = error.Message.Split('\n')[0].Trim();
            return firstLine.Length > 160 ? firstLine.Substring(0, 160) + "…" : firstLine;
        }

        private void ShowEmptyFeed(string message)
        {
            _emptyFeedText.Text = message;
            _emptyFeedText.Visibility = Visibility.Visible;
        }

        private void OpenBrowseCategoryGroups()
        {
            if (_browseMenu == null || _browseMenu.Groups.Count == 0) return;
            SourceItem src = _services.Sources.Find(source => source.Key == _browseSourceKey);
            if (src == null) return;
            List<SourceCategoryGroup> groups = _browseMenu.Groups;

            Window dlg = CreateStyledDialog(src.Name + " · 分类浏览", 680, 540);
            DockPanel root = new DockPanel { Margin = new Thickness(18) };

            // Top: group tabs (when multiple groups exist) + instant search filter
            StackPanel topArea = new StackPanel { Margin = new Thickness(0, 0, 0, 12) };
            DockPanel.SetDock(topArea, Dock.Top);

            WrapPanel groupTabs = new WrapPanel { Margin = new Thickness(0, 0, 0, 10) };
            if (groups.Count > 1) topArea.Children.Add(groupTabs);

            TextBox filterBox = MakeStyledTextBox();
            filterBox.Padding = new Thickness(12, 7, 12, 7);
            Grid filterGrid = new Grid();
            filterGrid.Children.Add(filterBox);
            TextBlock filterHint = new TextBlock
            {
                Text = "输入关键词快速筛选分类…",
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(12, 0, 0, 0),
                IsHitTestVisible = false
            };
            filterHint.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            filterGrid.Children.Add(filterHint);
            filterBox.TextChanged += delegate { filterHint.Visibility = filterBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed; };
            topArea.Children.Add(filterGrid);
            root.Children.Add(topArea);

            // Bottom: close button
            StackPanel footer = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
            DockPanel.SetDock(footer, Dock.Bottom);
            Button btnClose = UiTheme.MakeButton("关闭", false, false);
            btnClose.IsCancel = true;
            btnClose.Click += delegate { dlg.Close(); };
            footer.Children.Add(btnClose);
            root.Children.Add(footer);

            // Body: category tag grid + inline status/retry
            Grid body = new Grid();
            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            WrapPanel tagWrap = new WrapPanel();
            sv.Content = tagWrap;
            body.Children.Add(sv);

            StackPanel statePanel = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center, Visibility = Visibility.Collapsed };
            TextBlock stateText = new TextBlock { FontSize = 13.5, TextWrapping = TextWrapping.Wrap, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 10) };
            stateText.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            Button retryBtn = UiTheme.MakeButton("重试加载", true, false);
            retryBtn.HorizontalAlignment = HorizontalAlignment.Center;
            retryBtn.Visibility = Visibility.Collapsed;
            statePanel.Children.Add(stateText);
            statePanel.Children.Add(retryBtn);
            body.Children.Add(statePanel);
            root.Children.Add(body);

            dlg.Content = root;

            int activeGroupIdx = 0;
            int localReq = 0;
            bool dialogClosed = false;
            List<SourceBrowseTarget> loadedTargets = new List<SourceBrowseTarget>();
            Dictionary<int, List<SourceBrowseTarget>> groupCache = new Dictionary<int, List<SourceBrowseTarget>>();
            List<Button> groupBtns = new List<Button>();

            Action renderTags = delegate
            {
                tagWrap.Children.Clear();
                string kw = (filterBox.Text ?? "").Trim().ToLowerInvariant();
                List<SourceBrowseTarget> matched = string.IsNullOrEmpty(kw)
                    ? loadedTargets
                    : loadedTargets.FindAll(t => (t.Title ?? "").ToLowerInvariant().Contains(kw));
                if (matched.Count == 0)
                {
                    stateText.Text = loadedTargets.Count == 0 ? "这个分组暂无可用分类" : "没有匹配的分类";
                    retryBtn.Visibility = Visibility.Collapsed;
                    statePanel.Visibility = Visibility.Visible;
                    return;
                }
                statePanel.Visibility = Visibility.Collapsed;
                int limit = Math.Min(matched.Count, 300);
                for (int i = 0; i < limit; i++)
                {
                    SourceBrowseTarget target = matched[i];
                    bool isCur = SameBrowseTarget(_browseTarget, target);
                    Button tagBtn = UiTheme.MakeButton(target.Title, isCur, false);
                    tagBtn.Margin = new Thickness(0, 0, 8, 8);
                    tagBtn.Padding = new Thickness(13, 6, 13, 6);
                    tagBtn.FontSize = 12.5;
                    tagBtn.Click += delegate
                    {
                        dlg.Close();
                        SelectBrowseTarget(target);
                    };
                    tagWrap.Children.Add(tagBtn);
                }
                if (matched.Count > limit)
                {
                    TextBlock moreHint = new TextBlock
                    {
                        Text = string.Format("仅显示前 {0} 项（共 {1} 项），请输入关键词筛选", limit, matched.Count),
                        FontSize = 12,
                        Margin = new Thickness(4, 6, 4, 6)
                    };
                    moreHint.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
                    tagWrap.Children.Add(moreHint);
                }
            };

            Action<int> loadGroup = null;
            loadGroup = async delegate (int gIdx)
            {
                activeGroupIdx = gIdx;
                for (int i = 0; i < groupBtns.Count; i++)
                    groupBtns[i].Style = UiTheme.BuildPillButtonStyle(i == gIdx, false);

                List<SourceBrowseTarget> cached;
                if (groupCache.TryGetValue(gIdx, out cached))
                {
                    loadedTargets = cached;
                    renderTags();
                    return;
                }

                int req = ++localReq;
                loadedTargets = new List<SourceBrowseTarget>();
                tagWrap.Children.Clear();
                stateText.Text = "正在加载「" + groups[gIdx].Title + "」分类…";
                retryBtn.Visibility = Visibility.Collapsed;
                statePanel.Visibility = Visibility.Visible;

                try
                {
                    List<SourceBrowseTarget> targets = await _jsRunner.GetBrowseCategoriesAsync(src, groups[gIdx].Index);
                    if (dialogClosed || req != localReq) return;
                    groupCache[gIdx] = targets ?? new List<SourceBrowseTarget>();
                    loadedTargets = groupCache[gIdx];
                    renderTags();
                }
                catch (Exception ex)
                {
                    if (dialogClosed || req != localReq) return;
                    stateText.Text = "加载分类失败：" + BrowseError(ex);
                    retryBtn.Visibility = Visibility.Visible;
                    statePanel.Visibility = Visibility.Visible;
                }
            };

            retryBtn.Click += delegate { loadGroup(activeGroupIdx); };
            filterBox.TextChanged += delegate { if (loadedTargets.Count > 0) renderTags(); };
            dlg.Closed += delegate { dialogClosed = true; localReq++; };

            for (int i = 0; i < groups.Count; i++)
            {
                int idx = i;
                Button gb = UiTheme.MakeButton(groups[i].Title, i == 0, false);
                gb.Margin = new Thickness(0, 0, 8, 6);
                gb.Padding = new Thickness(12, 5, 12, 5);
                gb.FontSize = 12.5;
                gb.Click += delegate { loadGroup(idx); };
                groupBtns.Add(gb);
                groupTabs.Children.Add(gb);
            }

            dlg.Loaded += delegate { loadGroup(0); };
            dlg.ShowDialog();
        }

        private void OpenBrowseFilters()
        {
            SourceBrowseFilters filters = _browseFilters;
            if (filters == null || filters.Fields.Count == 0) return;
            List<string> labels = filters.Fields.ConvertAll(field =>
            {
                HashSet<string> selection = ReadFilterSelection(field, filters.Values[field.Index]);
                List<SourceFilterChoice> chosen = field.Choices.FindAll(choice => selection.Contains(choice.Value));
                return chosen.Count == 0 ? field.Title : field.Title + " · " + string.Join("、", chosen.ConvertAll(choice => choice.Title));
            });
            labels.Add("恢复默认");
            int selected = PickBrowseChoice("筛选", labels, -1);
            if (selected < 0) return;
            if (selected == filters.Fields.Count)
            {
                _browseFilters = null;
                LoadActiveFeedAsync(1);
                return;
            }
            SourceFilterField activeField = filters.Fields[selected];
            HashSet<string> values = ReadFilterSelection(activeField, filters.Values[activeField.Index]);
            List<int> checkedIndices = new List<int>();
            for (int index = 0; index < activeField.Choices.Count; index++)
                if (values.Contains(activeField.Choices[index].Value)) checkedIndices.Add(index);
            List<int> picked = PickBrowseChoices(activeField.Title, activeField.Choices.ConvertAll(choice => choice.Title),
                checkedIndices, activeField.Multiple);
            if (picked == null) return;
            filters.Values[activeField.Index] = activeField.Multiple
                ? JsonUtil.CreateSerializer().Serialize(picked.ConvertAll(index => activeField.Choices[index].Value))
                : activeField.Choices[picked[0]].Value;
            LoadActiveFeedAsync(1);
        }

        private static HashSet<string> ReadFilterSelection(SourceFilterField field, string value)
        {
            if (!field.Multiple) return new HashSet<string> { value };
            if (value == null) return new HashSet<string>();
            try { return new HashSet<string>(JsonUtil.CreateSerializer().Deserialize<List<string>>(value)); }
            catch (ArgumentException) { return new HashSet<string>(); }
            catch (InvalidOperationException) { return new HashSet<string>(); }
        }

        private int PickBrowseChoice(string title, List<string> items, int selected)
        {
            List<int> indices = selected < 0 ? new List<int>() : new List<int> { selected };
            List<int> result = PickBrowseChoices(title, items, indices, false);
            return result == null ? -1 : result[0];
        }

        private List<int> PickBrowseChoices(string title, List<string> items, List<int> selected, bool multiple)
        {
            Window dialog = CreateStyledDialog(title, 480, Math.Min(520, 130 + 36 * items.Count));
            DockPanel panel = new DockPanel { Margin = new Thickness(16) };
            StackPanel actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            DockPanel.SetDock(actions, Dock.Bottom);
            panel.Children.Add(actions);
            List<KeyValuePair<int, string>> entries = new List<KeyValuePair<int, string>>();
            for (int index = 0; index < items.Count; index++)
                entries.Add(new KeyValuePair<int, string>(index, items[index]));
            ListBox list = new ListBox
            {
                ItemsSource = entries, Margin = new Thickness(0, 0, 0, 12),
                SelectionMode = multiple ? SelectionMode.Multiple : SelectionMode.Single
            };
            System.Windows.Automation.AutomationProperties.SetName(list, title);
            list.SetResourceReference(Control.BackgroundProperty, "Brush.BgElevated");
            list.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            list.SetResourceReference(Control.BorderBrushProperty, "Brush.Border");
            ScrollViewer.SetHorizontalScrollBarVisibility(list, ScrollBarVisibility.Disabled);
            DataTemplate template = new DataTemplate();
            FrameworkElementFactory text = new FrameworkElementFactory(typeof(TextBlock));
            text.SetBinding(TextBlock.TextProperty, new Binding("Value"));
            text.SetValue(TextBlock.TextWrappingProperty, TextWrapping.Wrap);
            text.SetValue(FrameworkElement.MarginProperty, new Thickness(8, 7, 8, 7));
            template.VisualTree = text;
            list.ItemTemplate = template;
            Style itemStyle = new Style(typeof(ListBoxItem));
            itemStyle.Setters.Add(new Setter(System.Windows.Automation.AutomationProperties.NameProperty, new Binding("Value")));
            list.ItemContainerStyle = itemStyle;
            if (multiple)
            {
                foreach (int index in selected) list.SelectedItems.Add(entries[index]);
            }
            else if (selected.Count > 0) list.SelectedIndex = selected[0];
            panel.Children.Add(list);
            Button accept = AddBrowseButton(actions, multiple ? "应用" : "选择", delegate { dialog.DialogResult = true; });
            accept.IsDefault = true;
            accept.IsEnabled = multiple || list.SelectedIndex >= 0;
            list.SelectionChanged += delegate { accept.IsEnabled = multiple || list.SelectedIndex >= 0; };
            list.MouseDoubleClick += delegate { if (!multiple && list.SelectedIndex >= 0) dialog.DialogResult = true; };
            AddBrowseButton(actions, "取消", delegate { dialog.DialogResult = false; }).IsCancel = true;
            dialog.Content = panel;
            dialog.Loaded += delegate { list.Focus(); };
            if (dialog.ShowDialog() != true) return null;
            List<int> result = new List<int>();
            foreach (KeyValuePair<int, string> entry in list.SelectedItems) result.Add(entry.Key);
            result.Sort();
            return result;
        }

        private void RenderSectionHeader(SourceFeedSection section)
        {
            DockPanel row = new DockPanel { Margin = new Thickness(0, 12, 0, 7) };
            if (section.Target != null)
            {
                Button more = AddBrowseButton(row, "查看更多", delegate { SelectBrowseTarget(section.Target); });
                DockPanel.SetDock(more, Dock.Right);
                System.Windows.Automation.AutomationProperties.SetName(more, "查看更多：" + section.Title);
            }
            TextBlock title = new TextBlock
            {
                Text = section.Title, FontSize = 16, FontWeight = FontWeights.SemiBold,
                TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center
            };
            title.SetResourceReference(TextBlock.ForegroundProperty, "Brush.Text");
            row.Children.Add(title);
            _comicGrid.Children.Add(row);
        }

        private void RenderComicCards(List<ComicCardModel> list)
        {
            if (list.Count == 0) return;
            WrapPanel cards = new WrapPanel();
            double cardW = _services.GridCompact ? 138 : 172;
            double coverH = _services.GridCompact ? 182 : 226;

            foreach (ComicCardModel card in list)
                cards.Children.Add(BuildComicCardElement(card, cardW, coverH));
            _comicGrid.Children.Add(cards);
        }

        private Border BuildComicCardElement(ComicCardModel card, double cardW, double coverH)
        {
            Border outer = new Border
            {
                Width = cardW,
                Margin = new Thickness(0, 0, 14, 16),
                CornerRadius = new CornerRadius(14),
                BorderThickness = new Thickness(1),
                ClipToBounds = true,
                Cursor = Cursors.Hand
            };
            outer.SetResourceReference(Border.BackgroundProperty, "Brush.CardSurface");
            outer.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");
            outer.Effect = UiTheme.CreateGlassShadow(UiTheme.IsDarkActive, true);
            outer.MouseEnter += delegate (object s, MouseEventArgs e)
            {
                outer.SetResourceReference(Border.BorderBrushProperty, "Brush.AccentBorder");
            };
            outer.MouseLeave += delegate (object s, MouseEventArgs e)
            {
                outer.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");
            };

            StackPanel stack = new StackPanel();
            Grid imgBox = new Grid { Height = coverH, ClipToBounds = true };
            imgBox.SetResourceReference(Panel.BackgroundProperty, "Brush.Surface");
            Image coverImg = new Image { Stretch = Stretch.UniformToFill };
            RenderOptions.SetBitmapScalingMode(coverImg, BitmapScalingMode.HighQuality);
            TextBlock coverStatus = new TextBlock { Text = card.IsLocal || !string.IsNullOrEmpty(card.CoverUrl) ? "加载中" : "暂无封面",
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            coverStatus.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            imgBox.Children.Add(coverStatus);
            imgBox.Children.Add(coverImg);

            Border favBadge = new Border
            {
                CornerRadius = new CornerRadius(8),
                Background = new SolidColorBrush(Color.FromArgb(215, 245, 158, 11)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(140, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(6, 2, 6, 2),
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(7, 7, 0, 0),
                Visibility = (IsFavorite(card) && _services.ActiveHomeMode != "fav") ? Visibility.Visible : Visibility.Collapsed
            };
            favBadge.Child = new TextBlock { Text = "★", Foreground = Brushes.White, FontSize = 10.5, FontWeight = FontWeights.Bold };
            imgBox.Children.Add(favBadge);

            if (!string.IsNullOrEmpty(card.Badge) && card.SourceKey != _services.ActiveHomeMode)
            {
                Border badge = new Border
                {
                    CornerRadius = new CornerRadius(8),
                    Background = new SolidColorBrush(Color.FromArgb(190, 12, 18, 28)),
                    BorderBrush = new SolidColorBrush(Color.FromArgb(75, 255, 255, 255)),
                    BorderThickness = new Thickness(1),
                    Padding = new Thickness(7, 2, 7, 2),
                    HorizontalAlignment = HorizontalAlignment.Right,
                    VerticalAlignment = VerticalAlignment.Top,
                    Margin = new Thickness(0, 7, 7, 0)
                };
                badge.Child = new TextBlock { Text = card.Badge, Foreground = Brushes.White, FontSize = 10.5, FontWeight = FontWeights.SemiBold };
                imgBox.Children.Add(badge);
            }
            stack.Children.Add(imgBox);

            StackPanel meta = new StackPanel { Margin = new Thickness(10, 9, 10, 10) };
            TextBlock titleTb = new TextBlock
            {
                Text = card.Title,
                FontSize = 13,
                FontWeight = FontWeights.Bold,
                TextTrimming = TextTrimming.CharacterEllipsis,
                MaxHeight = 36,
                TextWrapping = TextWrapping.Wrap
            };
            titleTb.SetResourceReference(TextBlock.ForegroundProperty, "Brush.Text");
            TextBlock subTb = new TextBlock
            {
                Text = card.SubTitle ?? "",
                FontSize = 11.5,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(0, 3, 0, 0)
            };
            subTb.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            meta.Children.Add(titleTb);
            meta.Children.Add(subTb);
            stack.Children.Add(meta);
            outer.Child = stack;

            ContextMenu actions = new ContextMenu();
            MenuItem favoriteAction = new MenuItem();
            MenuItem shelfAction = new MenuItem { Header = "管理书架" };
            actions.Opened += delegate { favoriteAction.Header = IsFavorite(card) ? "取消收藏" : "加入收藏"; };
            favoriteAction.Click += delegate
            {
                ToggleFavorite(card);
                favBadge.Visibility = (IsFavorite(card) && _services.ActiveHomeMode != "fav") ? Visibility.Visible : Visibility.Collapsed;
            };
            shelfAction.Click += delegate { OpenAddToCollectionDialog(card, this); };
            actions.Items.Add(favoriteAction);
            if (card.IsLocal) actions.Items.Add(shelfAction);
            outer.ContextMenu = actions;

            // Asynchronous image load without blocking WPF UI thread
            LoadCardThumbnailAsync(card, coverImg, coverStatus);

            outer.MouseLeftButtonUp += delegate (object s, MouseButtonEventArgs e)
            {
                if (card.IsLocal) OpenLocalComicInReader(card);
                else OpenComicDetailDialog(card);
            };
            return outer;
        }

        private async void LoadCardThumbnailAsync(ComicCardModel card, Image targetImg, TextBlock status)
        {
            BitmapImage bmp = card.IsLocal
                ? await _services.LoadLocalPageBitmapAsync(card.Id, 0, 340)
                : await _services.LoadComicImageAsync(card.CoverUrl, card.Headers, 340);
            if (bmp != null)
            {
                targetImg.Source = bmp;
                status.Visibility = Visibility.Collapsed;
                if (SystemParameters.ClientAreaAnimation)
                    targetImg.BeginAnimation(UIElement.OpacityProperty, new System.Windows.Media.Animation.DoubleAnimation(0.55, 1, TimeSpan.FromMilliseconds(180)));
            }
            else status.Text = "暂无封面";
        }

        private void OpenQuickSourcePicker()
        {
            Window dlg = CreateStyledDialog("快速切换动漫源 (" + _services.Sources.Count + ")", 640, 560);
            DockPanel root = new DockPanel { Margin = new Thickness(16) };

            DockPanel topBar = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            DockPanel.SetDock(topBar, Dock.Top);
            Button btnMgr = UiTheme.MakeButton("源管理", false, false);
            DockPanel.SetDock(btnMgr, Dock.Right);
            topBar.Children.Add(btnMgr);

            TextBox filterBox = MakeStyledTextBox();
            filterBox.Margin = new Thickness(0, 0, 10, 0);
            topBar.Children.Add(filterBox);
            root.Children.Add(topBar);

            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            StackPanel listPanel = new StackPanel();
            sv.Content = listPanel;
            root.Children.Add(sv);

            Action rebuildList = delegate ()
            {
                listPanel.Children.Clear();
                string q = (filterBox.Text ?? "").Trim().ToLowerInvariant();
                foreach (SourceItem s in _services.Sources)
                {
                    if (!string.IsNullOrEmpty(q) &&
                        !s.Name.ToLowerInvariant().Contains(q) &&
                        !s.Key.ToLowerInvariant().Contains(q)) continue;

                    SourceItem capSrc = s;
                    Border row = new Border
                    {
                        CornerRadius = new CornerRadius(10),
                        BorderThickness = new Thickness(1),
                        Padding = new Thickness(12, 9, 12, 9),
                        Margin = new Thickness(0, 0, 0, 8)
                    };
                    row.SetResourceReference(Border.BackgroundProperty, "Brush.Surface");
                    row.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");

                    DockPanel rowDock = new DockPanel();
                    Button pickBtn = UiTheme.MakeButton("切换到此源", true, false);
                    pickBtn.Padding = new Thickness(12, 5, 12, 5);
                    DockPanel.SetDock(pickBtn, Dock.Right);
                    rowDock.Children.Add(pickBtn);

                    StackPanel info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                    TextBlock nameTb = new TextBlock { Text = s.Name, FontSize = 14, FontWeight = FontWeights.Bold };
                    TextBlock keyTb = new TextBlock { Text = s.Key + " · v" + s.Version + (s.Enabled ? "" : " (已停用)"), FontSize = 11.5 };
                    keyTb.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
                    info.Children.Add(nameTb);
                    info.Children.Add(keyTb);
                    rowDock.Children.Add(info);
                    row.Child = rowDock;

                    pickBtn.Click += delegate (object sender, RoutedEventArgs ev)
                    {
                        capSrc.Enabled = true;
                        dlg.Close();
                        SwitchHomeMode(capSrc.Key);
                    };
                    listPanel.Children.Add(row);
                }
            };

            btnMgr.Click += delegate (object s, RoutedEventArgs e)
            {
                dlg.Close();
                OpenSourceManagerDialog();
            };
            filterBox.TextChanged += delegate (object s, TextChangedEventArgs e) { rebuildList(); };
            rebuildList();
            dlg.Content = root;
            dlg.Loaded += delegate (object s, RoutedEventArgs e) { filterBox.Focus(); };
            dlg.ShowDialog();
        }

        private void OpenSourceManagerDialog()
        {
            Window dlg = CreateStyledDialog("在线漫画源管理 (" + _services.Sources.Count + ")", 820, 620);
            DockPanel root = new DockPanel { Margin = new Thickness(16) };

            DockPanel topBar = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            DockPanel.SetDock(topBar, Dock.Top);

            StackPanel actions = new StackPanel { Orientation = Orientation.Horizontal };
            DockPanel.SetDock(actions, Dock.Right);
            Button btnEnableAll = UiTheme.MakeButton("全启", false, false);
            Button btnDisableAll = UiTheme.MakeButton("全停", false, false);
            Button btnSubRepo = UiTheme.MakeButton("源仓库订阅", true, false);
            Button btnImportJs = UiTheme.MakeButton("导入 .js", false, false);
            btnEnableAll.Margin = new Thickness(0, 0, 6, 0);
            btnDisableAll.Margin = new Thickness(0, 0, 6, 0);
            btnSubRepo.Margin = new Thickness(0, 0, 6, 0);
            actions.Children.Add(btnEnableAll);
            actions.Children.Add(btnDisableAll);
            actions.Children.Add(btnSubRepo);
            actions.Children.Add(btnImportJs);
            topBar.Children.Add(actions);

            TextBox filterBox = MakeStyledTextBox();
            filterBox.Margin = new Thickness(0, 0, 10, 0);
            topBar.Children.Add(filterBox);
            root.Children.Add(topBar);

            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            StackPanel listPanel = new StackPanel();
            sv.Content = listPanel;
            root.Children.Add(sv);

            Action rebuildList = delegate ()
            {
                int enCount = _services.Sources.FindAll(delegate (SourceItem x) { return x.Enabled; }).Count;
                dlg.Title = string.Format("在线漫画源管理 ({0}/{1})", enCount, _services.Sources.Count);
                listPanel.Children.Clear();
                string q = (filterBox.Text ?? "").Trim().ToLowerInvariant();

                foreach (SourceItem s in _services.Sources)
                {
                    if (!string.IsNullOrEmpty(q) &&
                        !s.Name.ToLowerInvariant().Contains(q) &&
                        !s.Key.ToLowerInvariant().Contains(q)) continue;

                    SourceItem capSrc = s;
                    Border row = new Border
                    {
                        CornerRadius = new CornerRadius(10),
                        BorderThickness = new Thickness(1),
                        Padding = new Thickness(12, 9, 12, 9),
                        Margin = new Thickness(0, 0, 0, 8)
                    };
                    row.SetResourceReference(Border.BackgroundProperty, "Brush.Surface");
                    row.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");

                    DockPanel rowDock = new DockPanel();
                    StackPanel rightCtrls = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                    DockPanel.SetDock(rightCtrls, Dock.Right);

                    Button bBrowse = UiTheme.MakeButton("浏览", false, false);
                    Button bAcc = UiTheme.MakeButton("账号", false, false);
                    Button bCfg = UiTheme.MakeButton("设置", false, false);
                    CheckBox chk = UiTheme.MakeToggle(capSrc.Enabled);
                    bBrowse.Padding = new Thickness(10, 4, 10, 4);
                    bAcc.Padding = new Thickness(10, 4, 10, 4);
                    bCfg.Padding = new Thickness(10, 4, 10, 4);
                    bBrowse.Margin = new Thickness(0, 0, 6, 0);
                    bAcc.Margin = new Thickness(0, 0, 6, 0);
                    bCfg.Margin = new Thickness(0, 0, 10, 0);

                    rightCtrls.Children.Add(bBrowse);
                    rightCtrls.Children.Add(bAcc);
                    rightCtrls.Children.Add(bCfg);
                    rightCtrls.Children.Add(chk);
                    rowDock.Children.Add(rightCtrls);

                    StackPanel info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                    TextBlock nameTb = new TextBlock { Text = capSrc.Name, FontSize = 14, FontWeight = FontWeights.Bold };
                    TextBlock keyTb = new TextBlock { Text = capSrc.Key + " · v" + capSrc.Version, FontSize = 11.5 };
                    keyTb.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
                    info.Children.Add(nameTb);
                    info.Children.Add(keyTb);
                    rowDock.Children.Add(info);
                    row.Child = rowDock;

                    chk.Click += delegate (object sender, RoutedEventArgs ev)
                    {
                        capSrc.Enabled = chk.IsChecked == true;
                        _services.SaveState();
                        RebuildHomeChips();
                        int cnt = _services.Sources.FindAll(delegate (SourceItem x) { return x.Enabled; }).Count;
                        dlg.Title = string.Format("在线漫画源管理 ({0}/{1})", cnt, _services.Sources.Count);
                    };
                    bBrowse.Click += delegate (object sender, RoutedEventArgs ev)
                    {
                        capSrc.Enabled = true;
                        _services.SaveState();
                        dlg.Close();
                        SwitchHomeMode(capSrc.Key);
                    };
                    bAcc.Click += delegate (object sender, RoutedEventArgs ev) { OpenAccountLoginDialog(capSrc, dlg); };
                    bCfg.Click += delegate (object sender, RoutedEventArgs ev) { OpenSourceSettingsDialog(capSrc, dlg); };
                    listPanel.Children.Add(row);
                }
            };

            filterBox.TextChanged += delegate (object s, TextChangedEventArgs e) { rebuildList(); };
            btnEnableAll.Click += delegate (object s, RoutedEventArgs e)
            {
                foreach (SourceItem item in _services.Sources) item.Enabled = true;
                _services.SaveState();
                RebuildHomeChips();
                rebuildList();
            };
            btnDisableAll.Click += delegate (object s, RoutedEventArgs e)
            {
                foreach (SourceItem item in _services.Sources) item.Enabled = false;
                _services.SaveState();
                RebuildHomeChips();
                rebuildList();
            };
            btnSubRepo.Click += delegate (object s, RoutedEventArgs e) { OpenRepoSubscribeDialog(dlg, rebuildList); };
            btnImportJs.Click += delegate (object s, RoutedEventArgs e)
            {
                using (System.Windows.Forms.OpenFileDialog ofd = new System.Windows.Forms.OpenFileDialog())
                {
                    ofd.Filter = "Venera 漫画源脚本 (*.js)|*.js";
                    if (ofd.ShowDialog() == System.Windows.Forms.DialogResult.OK)
                    {
                        string text = File.ReadAllText(ofd.FileName, Encoding.UTF8);
                        if (ImportCustomScript(text, Path.GetFileNameWithoutExtension(ofd.FileName)))
                        {
                            rebuildList();
                        }
                    }
                }
            };

            rebuildList();
            dlg.Content = root;
            dlg.ShowDialog();
        }

        private bool ImportCustomScript(string scriptText, string fallbackKey)
        {
            Match cm = Regex.Match(scriptText, @"class\s+([A-Za-z_$][A-Za-z0-9_$]*)\s+extends\s+ComicSource\b");
            if (!cm.Success)
            {
                ShowToast("导入失败：脚本中未找到 extends ComicSource");
                return false;
            }
            Match km = Regex.Match(scriptText, @"(?:\bkey\s*[:=]|[""']key[""']\s*:)\s*[""']([^""']+)[""']");
            Match nm = Regex.Match(scriptText, @"(?:\bname\s*[:=]|[""']name[""']\s*:)\s*[""']([^""']+)[""']");
            Match vm = Regex.Match(scriptText, @"(?:\bversion\s*[:=]|[""']version[""']\s*:)\s*[""']([^""']+)[""']");

            string key = km.Success ? km.Groups[1].Value : fallbackKey;
            string name = nm.Success ? nm.Groups[1].Value : key;
            string version = vm.Success ? vm.Groups[1].Value : "1.0.0";

            SourceItem existing = _services.Sources.Find(delegate (SourceItem s) { return s.Key == key; });
            if (existing != null)
            {
                existing.Name = name;
                existing.Version = version;
                existing.CustomScript = scriptText;
                existing.Enabled = true;
            }
            else
            {
                _services.Sources.Insert(0, new SourceItem { Key = key, Name = name, Version = version, CustomScript = scriptText, Enabled = true });
            }
            _jsRunner.InvalidateSource(key);
            _services.SaveState();
            RebuildHomeChips();
            ShowToast("已导入漫画源: " + name);
            return true;
        }

        private async void OpenRepoSubscribeDialog(Window owner, Action onUpdated)
        {
            Window dlg = CreateStyledDialog("订阅 Venera 源仓库 (index.json)", 640, 520);
            dlg.Owner = owner;
            DockPanel root = new DockPanel { Margin = new Thickness(16) };

            DockPanel topBar = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
            DockPanel.SetDock(topBar, Dock.Top);
            Button btnFetch = UiTheme.MakeButton("拉取仓库", true, false);
            DockPanel.SetDock(btnFetch, Dock.Right);
            topBar.Children.Add(btnFetch);

            TextBox urlBox = MakeStyledTextBox();
            urlBox.Text = "https://raw.githubusercontent.com/zmf678/venera-sources/main/index.json";
            urlBox.Margin = new Thickness(0, 0, 10, 0);
            topBar.Children.Add(urlBox);
            root.Children.Add(topBar);

            TextBlock statusTb = new TextBlock { Text = "正在请求仓库索引...", Margin = new Thickness(0, 0, 0, 10), FontSize = 12.5 };
            statusTb.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            DockPanel.SetDock(statusTb, Dock.Top);
            root.Children.Add(statusTb);

            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            StackPanel listPanel = new StackPanel();
            sv.Content = listPanel;
            root.Children.Add(sv);
            dlg.Content = root;

            Func<Task> doFetch = async delegate ()
            {
                string target = (urlBox.Text ?? "").Trim();
                statusTb.Text = "正在拉取 " + target + " ...";
                listPanel.Children.Clear();
                try
                {
                    JavaScriptSerializer ser = JsonUtil.CreateSerializer();
                    string rawJson = await Task.Run<string>(delegate ()
                    {
                        Dictionary<string, object> reqMsg = new Dictionary<string, object> { { "http_method", "GET" }, { "url", target } };
                        string r = _services.ExecuteVeneraHttp(reqMsg, false, ser);
                        Dictionary<string, object> rd = ser.Deserialize<Dictionary<string, object>>(r);
                        return JsonUtil.GetStr(rd, "body", "[]");
                    });
                    ArrayList arr = ser.Deserialize<ArrayList>(rawJson);
                    if (arr == null) throw new InvalidOperationException("格式错误");
                    string baseUrl = target.Substring(0, target.LastIndexOf('/') + 1);
                    statusTb.Text = string.Format("共发现 {0} 个源，点击右侧按钮同步最新脚本：", arr.Count);

                    foreach (object o in arr)
                    {
                        Dictionary<string, object> d = o as Dictionary<string, object>;
                        if (d == null) continue;
                        string key = JsonUtil.GetStr(d, "key", "");
                        string name = JsonUtil.GetStr(d, "name", key);
                        string ver = JsonUtil.GetStr(d, "version", "1.0.0");
                        string fn = JsonUtil.GetStr(d, "fileName", "");
                        string scriptUrl = fn.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? fn : (baseUrl + fn.TrimStart('/'));

                        Border row = new Border { CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), Padding = new Thickness(10, 8, 10, 8), Margin = new Thickness(0, 0, 0, 6) };
                        row.SetResourceReference(Border.BackgroundProperty, "Brush.Surface");
                        row.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");
                        DockPanel rDock = new DockPanel();
                        Button bInst = UiTheme.MakeButton("同步/安装", true, false);
                        bInst.Padding = new Thickness(10, 4, 10, 4);
                        DockPanel.SetDock(bInst, Dock.Right);
                        rDock.Children.Add(bInst);
                        TextBlock tb = new TextBlock { Text = name + " (" + key + " · v" + ver + ")", VerticalAlignment = VerticalAlignment.Center };
                        rDock.Children.Add(tb);
                        row.Child = rDock;

                        bInst.Click += async delegate (object s, RoutedEventArgs ev)
                        {
                            try
                            {
                                string script = await Task.Run<string>(delegate ()
                                {
                                    Dictionary<string, object> reqMsg = new Dictionary<string, object> { { "http_method", "GET" }, { "url", scriptUrl } };
                                    string r = _services.ExecuteVeneraHttp(reqMsg, false, ser);
                                    Dictionary<string, object> rd = ser.Deserialize<Dictionary<string, object>>(r);
                                    return JsonUtil.GetStr(rd, "body", "");
                                });
                                if (ImportCustomScript(script, key) && onUpdated != null) onUpdated();
                            }
                            catch (Exception ex) { ShowToast("安装失败: " + ex.Message); }
                        };
                        listPanel.Children.Add(row);
                    }
                }
                catch (Exception ex) { statusTb.Text = "拉取失败: " + ex.Message; }
            };

            btnFetch.Click += async delegate (object s, RoutedEventArgs e) { await doFetch(); };
            dlg.Loaded += async delegate (object s, RoutedEventArgs e) { await doFetch(); };
            dlg.ShowDialog();
        }

        private async void OpenAccountLoginDialog(SourceItem src, Window owner)
        {
            try
            {
                AccountCapModel cap = await _jsRunner.GetAccountCapabilitiesAsync(src);
                Window dlg = CreateStyledDialog("源账号管理 — " + src.Name, 460, 400);
                dlg.Owner = owner;
                StackPanel stack = new StackPanel { Margin = new Thickness(18) };

                TextBlock stateTb = new TextBlock
                {
                    Text = "当前状态：" + (cap.IsLoggedIn ? "已登录 ✓" : "未登录"),
                    FontSize = 13,
                    FontWeight = FontWeights.SemiBold,
                    Margin = new Thickness(0, 0, 0, 12)
                };
                stateTb.SetResourceReference(TextBlock.ForegroundProperty, cap.IsLoggedIn ? "Brush.Accent" : "Brush.TextMuted");
                stack.Children.Add(stateTb);

                if (cap.HasPasswordLogin)
                {
                    stack.Children.Add(new TextBlock { Text = "用户名 / 邮箱", FontSize = 12, Margin = new Thickness(0, 0, 0, 4) });
                    TextBox uBox = MakeStyledTextBox();
                    uBox.Margin = new Thickness(0, 0, 0, 10);
                    stack.Children.Add(uBox);

                    stack.Children.Add(new TextBlock { Text = "密码", FontSize = 12, Margin = new Thickness(0, 0, 0, 4) });
                    PasswordBox pBox = MakeStyledPasswordBox();
                    pBox.Margin = new Thickness(0, 0, 0, 12);
                    stack.Children.Add(pBox);

                    StackPanel btnRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 12) };
                    Button btnLogin = UiTheme.MakeButton("登录", true, false);
                    btnLogin.Click += async delegate (object s, RoutedEventArgs e)
                    {
                        try
                        {
                            await _jsRunner.LoginWithPasswordAsync(src, uBox.Text.Trim(), pBox.Password);
                            ShowToast("登录成功");
                            dlg.Close();
                            LoadActiveFeedAsync(1);
                        }
                        catch (Exception ex) { ShowToast("登录失败: " + ex.Message); }
                    };
                    btnRow.Children.Add(btnLogin);

                    if (cap.IsLoggedIn)
                    {
                        Button btnOut = UiTheme.MakeButton("退出登录", false, true);
                        btnOut.Margin = new Thickness(8, 0, 0, 0);
                        btnOut.Click += async delegate (object s, RoutedEventArgs e)
                        {
                            await _jsRunner.LogoutAsync(src);
                            ShowToast("已退出登录");
                            dlg.Close();
                        };
                        btnRow.Children.Add(btnOut);
                    }
                    stack.Children.Add(btnRow);
                }

                if (cap.CookieFields.Count > 0)
                {
                    List<TextBox> cBoxes = new List<TextBox>();
                    foreach (string cf in cap.CookieFields)
                    {
                        stack.Children.Add(new TextBlock { Text = "Cookie 字段: " + cf, FontSize = 12, Margin = new Thickness(0, 4, 0, 4) });
                        TextBox cb = MakeStyledTextBox();
                        cBoxes.Add(cb);
                        stack.Children.Add(cb);
                    }
                    Button btnCk = UiTheme.MakeButton("使用 Cookie 验证登录", true, false);
                    btnCk.Margin = new Thickness(0, 10, 0, 0);
                    btnCk.HorizontalAlignment = HorizontalAlignment.Left;
                    btnCk.Click += async delegate (object s, RoutedEventArgs e)
                    {
                        List<string> vals = new List<string>();
                        foreach (TextBox tb in cBoxes) vals.Add(tb.Text.Trim());
                        try
                        {
                            bool ok = await _jsRunner.LoginWithCookiesAsync(src, vals);
                            ShowToast(ok ? "Cookie 登录成功" : "Cookie 验证无效");
                            if (ok) { dlg.Close(); LoadActiveFeedAsync(1); }
                        }
                        catch (Exception ex) { ShowToast("验证失败: " + ex.Message); }
                    };
                    stack.Children.Add(btnCk);
                }

                if (!cap.HasPasswordLogin && cap.CookieFields.Count == 0)
                {
                    TextBlock noneTb = new TextBlock { Text = "该漫画源无需账号登录，或仅支持官方网页登录后导入 Cookie。", TextWrapping = TextWrapping.Wrap };
                    noneTb.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
                    stack.Children.Add(noneTb);
                }

                dlg.Content = new ScrollViewer { Content = stack, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
                dlg.ShowDialog();
            }
            catch (Exception ex) { ShowToast("打开账号界面失败: " + ex.Message); }
        }

        private async void OpenSourceSettingsDialog(SourceItem src, Window owner)
        {
            try
            {
                List<SourceSettingItem> items = await _jsRunner.GetSourceSettingsAsync(src);
                Window dlg = CreateStyledDialog("源设置 — " + src.Name, 520, 460);
                dlg.Owner = owner;
                StackPanel stack = new StackPanel { Margin = new Thickness(18) };
                if (items.Count == 0)
                {
                    TextBlock empty = new TextBlock { Text = "该漫画源暂无可配置选项。" };
                    empty.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
                    stack.Children.Add(empty);
                }
                else
                {
                    foreach (SourceSettingItem item in items)
                    {
                        SourceSettingItem capItem = item;
                        DockPanel row = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
                        if (item.Type == "switch")
                        {
                            CheckBox chk = UiTheme.MakeToggle(item.Value != null && Convert.ToBoolean(item.Value));
                            DockPanel.SetDock(chk, Dock.Right);
                            row.Children.Add(chk);
                            chk.Click += async delegate (object s, RoutedEventArgs e)
                            {
                                await _jsRunner.SaveSourceSettingAsync(src, capItem.Key, chk.IsChecked == true);
                            };
                        }
                        else if (item.Type == "callback")
                        {
                            Button b = UiTheme.MakeButton(string.IsNullOrEmpty(item.ButtonText) ? "执行" : item.ButtonText, true, false);
                            DockPanel.SetDock(b, Dock.Right);
                            row.Children.Add(b);
                            b.Click += async delegate (object s, RoutedEventArgs e)
                            {
                                await _jsRunner.InvokeSettingCallbackAsync(src, capItem.Key);
                                ShowToast("执行完成");
                            };
                        }
                        else
                        {
                            TextBox tb = MakeStyledTextBox();
                            tb.Width = 200;
                            tb.Text = item.Value == null ? "" : Convert.ToString(item.Value);
                            DockPanel.SetDock(tb, Dock.Right);
                            row.Children.Add(tb);
                            tb.LostFocus += async delegate (object s, RoutedEventArgs e)
                            {
                                await _jsRunner.SaveSourceSettingAsync(src, capItem.Key, tb.Text);
                            };
                        }
                        row.Children.Add(new TextBlock { Text = item.Title, VerticalAlignment = VerticalAlignment.Center, FontSize = 13.5 });
                        stack.Children.Add(row);
                    }
                }
                dlg.Content = new ScrollViewer { Content = stack, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
                dlg.ShowDialog();
            }
            catch (Exception ex) { ShowToast("加载源设置失败: " + ex.Message); }
        }

        private async void OpenComicDetailDialog(ComicCardModel card)
        {
            foreach (ComicCardModel cached in _services.Favorites.Concat(_services.History).Where(entry => entry.Uid == card.Uid))
            {
                if (string.IsNullOrWhiteSpace(card.Description)) card.Description = cached.Description;
                if (card.Tags == null || card.Tags.Count == 0) card.Tags = cached.Tags;
            }
            SourceItem src = _services.Sources.Find(delegate (SourceItem s) { return s.Key == card.SourceKey; });
            Window dlg = CreateStyledDialog(card.Title, 820, 620);
            StackPanel loadingPanel = new StackPanel { Margin = new Thickness(24) };
            TextBlock status = new TextBlock { Text = "正在加载漫画详情与章节列表…", TextWrapping = TextWrapping.Wrap };
            AddComicPreviewMetadata(loadingPanel, card.Tags, card.Description, 110);
            if (!string.IsNullOrEmpty(card.OfficialUrl))
                AddBrowseButton(loadingPanel, "打开原作品", delegate { OpenComicWebsite(card.OfficialUrl); });
            if (!string.IsNullOrEmpty(card.CoverUrl))
            {
                Image cachedCover = new Image { Width = 126, Height = 164, HorizontalAlignment = HorizontalAlignment.Left, Stretch = Stretch.Uniform };
                loadingPanel.Children.Add(cachedCover);
                LoadDetailCoverAsync(card.CoverUrl, null, card.Headers, cachedCover);
            }
            loadingPanel.Children.Add(status);
            Panel libraryActions = BuildComicLibraryActions(card, dlg);
            loadingPanel.Children.Add(libraryActions);
            dlg.Content = loadingPanel;
            dlg.Show();

            try
            {
                if (src == null) throw new InvalidOperationException("找不到所属漫画源");
                ComicDetailModel detail = await _jsRunner.LoadComicDetailAsync(src, card.Id, card.Headers);
                if (!string.IsNullOrWhiteSpace(detail.Description)) card.Description = detail.Description;
                if (detail.Tags.Count > 0) card.Tags = detail.Tags;
                if (!string.IsNullOrEmpty(detail.OfficialUrl)) card.OfficialUrl = detail.OfficialUrl;
                foreach (ComicCardModel saved in _services.Favorites.Concat(_services.History))
                    if (saved.Uid == card.Uid)
                    {
                        if (!string.IsNullOrWhiteSpace(detail.Description) || string.IsNullOrWhiteSpace(saved.Description))
                            saved.Description = card.Description;
                        if (detail.Tags.Count > 0 || saved.Tags == null || saved.Tags.Count == 0)
                            saved.Tags = card.Tags;
                        if (!string.IsNullOrEmpty(detail.OfficialUrl) || string.IsNullOrEmpty(saved.OfficialUrl))
                            saved.OfficialUrl = card.OfficialUrl;
                        if (!string.IsNullOrEmpty(detail.CoverUrl)) saved.CoverUrl = detail.CoverUrl;
                    }
                if (!string.IsNullOrEmpty(detail.CoverUrl)) card.CoverUrl = detail.CoverUrl;
                _services.SaveState();
                DockPanel root = new DockPanel { Margin = new Thickness(18) };

                // Hero section
                Grid hero = new Grid { Margin = new Thickness(0, 0, 0, 14) };
                DockPanel.SetDock(hero, Dock.Top);
                hero.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(160) });
                hero.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

                Border coverBorder = new Border { Width = 146, Height = 202, CornerRadius = new CornerRadius(10), ClipToBounds = true, Background = new SolidColorBrush(Color.FromRgb(22, 27, 36)) };
                Image coverImg = new Image { Stretch = Stretch.UniformToFill };
                coverBorder.Child = coverImg;
                hero.Children.Add(coverBorder);

                LoadDetailCoverAsync(detail.CoverUrl, card.CoverUrl, detail.Headers, coverImg);

                StackPanel infoStack = new StackPanel { Margin = new Thickness(14, 0, 0, 0) };
                Grid.SetColumn(infoStack, 1);
                infoStack.Children.Add(new TextBlock { Text = detail.Title, FontSize = 19, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap });
                TextBlock subTb = new TextBlock { Text = detail.SubTitle, FontSize = 13, Margin = new Thickness(0, 4, 0, 6) };
                subTb.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
                infoStack.Children.Add(subTb);

                AddComicPreviewMetadata(infoStack, card.Tags, card.Description, 88);

                WrapPanel actionRow = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
                Button btnRead = UiTheme.MakeButton(string.Format("开始阅读 ({0} 话)", detail.Chapters.Count), true, false);
                btnRead.IsEnabled = detail.Chapters.Count > 0;
                btnRead.Click += delegate (object s, RoutedEventArgs e)
                {
                    if (detail.Chapters.Count == 0) { ShowToast("暂无可读章节"); return; }
                    ReaderPrefModel pref = null;
                    int chIdx = 0, pIdx = 0;
                    if (_services.ComicPrefs.TryGetValue(card.Uid, out pref))
                    {
                        chIdx = Math.Max(0, Math.Min(detail.Chapters.Count - 1, pref.LastChapterIdx));
                        pIdx = Math.Max(0, pref.LastPage);
                    }
                    dlg.Close();
                    StartOnlineReader(card, detail.Chapters, chIdx, pIdx);
                };
                actionRow.Children.Add(btnRead);
                loadingPanel.Children.Remove(libraryActions);
                actionRow.Children.Add(libraryActions);
                if (!string.IsNullOrEmpty(card.OfficialUrl))
                    AddBrowseButton(actionRow, "打开原作品", delegate { OpenComicWebsite(card.OfficialUrl); });
                infoStack.Children.Add(actionRow);
                hero.Children.Add(infoStack);
                root.Children.Add(hero);

                ScrollViewer chScroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
                WrapPanel chWrap = new WrapPanel();
                for (int i = 0; i < detail.Chapters.Count; i++)
                {
                    int idx = i;
                    Button cb = UiTheme.MakeButton(detail.Chapters[i].Title, false, false);
                    cb.Width = 176;
                    cb.Margin = new Thickness(0, 0, 8, 8);
                    cb.HorizontalContentAlignment = HorizontalAlignment.Left;
                    cb.Click += delegate (object s, RoutedEventArgs e)
                    {
                        dlg.Close();
                        StartOnlineReader(card, detail.Chapters, idx, 0);
                    };
                    chWrap.Children.Add(cb);
                }
                chScroll.Content = chWrap;
                root.Children.Add(chScroll);
                dlg.Content = root;
            }
            catch (Exception ex)
            {
                status.Text = "加载详情失败：" + ex.Message.Split('\n')[0];
                Button retry = UiTheme.MakeButton("重试", true, false);
                retry.HorizontalAlignment = HorizontalAlignment.Left;
                retry.Click += delegate { dlg.Close(); OpenComicDetailDialog(card); };
                loadingPanel.Children.Add(retry);
            }
        }

        private void AddComicPreviewMetadata(Panel panel, List<string> tags, string description, double descriptionHeight)
        {
            if (tags != null && tags.Count > 0)
            {
                var visibleTags = new WrapPanel { Margin = new Thickness(0, 0, 0, 6) };
                foreach (string tag in tags.Take(12))
                {
                    var label = new TextBlock { Text = tag, FontSize = 12, MaxWidth = 200, TextTrimming = TextTrimming.CharacterEllipsis };
                    label.SetResourceReference(TextBlock.ForegroundProperty, "Brush.Text");
                    var chip = new Border { Child = label, ToolTip = tag, CornerRadius = new CornerRadius(8),
                        Padding = new Thickness(8, 3, 8, 3), Margin = new Thickness(0, 0, 6, 4) };
                    chip.SetResourceReference(Border.BackgroundProperty, "Brush.Surface");
                    visibleTags.Children.Add(chip);
                }
                if (tags.Count > 12)
                    AddBrowseButton(visibleTags, "全部标签", delegate
                    {
                        Window all = CreateStyledDialog("作品标签", 450, 350);
                        var labels = new TextBlock { Text = string.Join(" · ", tags), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(18) };
                        labels.SetResourceReference(TextBlock.ForegroundProperty, "Brush.Text");
                        all.Content = new ScrollViewer { Content = labels, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
                        all.ShowDialog();
                    });
                panel.Children.Add(visibleTags);
            }
            if (!string.IsNullOrWhiteSpace(description))
            {
                var synopsis = new TextBox
                {
                    Text = description, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, BorderThickness = new Thickness(0),
                    Background = Brushes.Transparent, MaxHeight = descriptionHeight, Margin = new Thickness(0, 0, 0, 8),
                    VerticalScrollBarVisibility = ScrollBarVisibility.Auto
                };
                synopsis.SetResourceReference(TextBox.ForegroundProperty, "Brush.TextMuted");
                panel.Children.Add(synopsis);
            }
        }

        private async void LoadDetailCoverAsync(string primaryUrl, string fallbackUrl, Dictionary<string, string> headers, Image img)
        {
            string u = !string.IsNullOrEmpty(primaryUrl) ? primaryUrl : fallbackUrl;
            BitmapImage bmp = await _services.LoadComicImageAsync(u, headers, 320);
            if (bmp != null) img.Source = bmp;
        }

        private bool IsFavorite(ComicCardModel card)
        {
            return card != null && _services.Favorites.Exists(item => item.Uid == card.Uid);
        }

        private static void StyleFavoriteActionButton(Button btn, bool favorited)
        {
            if (btn == null) return;
            btn.Content = favorited ? "★ 已收藏" : "☆ 加入收藏";
            if (favorited)
            {
                btn.SetResourceReference(Control.BackgroundProperty, "Brush.FavActiveBg");
                btn.SetResourceReference(Control.BorderBrushProperty, "Brush.FavActiveBorder");
                btn.SetResourceReference(Control.ForegroundProperty, "Brush.FavActiveFg");
            }
            else
            {
                btn.SetResourceReference(Control.BackgroundProperty, "Brush.Surface");
                btn.SetResourceReference(Control.BorderBrushProperty, "Brush.Border");
                btn.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            }
        }

        private Panel BuildComicLibraryActions(ComicCardModel card, Window owner)
        {
            WrapPanel actions = new WrapPanel { Margin = new Thickness(8, 0, 0, 0) };
            Button favorite = UiTheme.MakeButton("", false, false);
            StyleFavoriteActionButton(favorite, IsFavorite(card));
            Button shelf = UiTheme.MakeButton("管理书架", false, false);
            shelf.Margin = new Thickness(8, 0, 8, 0);
            favorite.Click += delegate
            {
                ToggleFavorite(card);
                StyleFavoriteActionButton(favorite, IsFavorite(card));
            };
            shelf.Click += delegate { OpenAddToCollectionDialog(card, owner); };
            actions.Children.Add(favorite);
            if (card.IsLocal) actions.Children.Add(shelf);
            return actions;
        }

        private void ToggleFavorite(ComicCardModel card)
        {
            if (card == null) return;
            int idx = _services.Favorites.FindIndex(delegate (ComicCardModel f) { return f.Uid == card.Uid; });
            if (idx >= 0) { _services.Favorites.RemoveAt(idx); ShowToast("已取消收藏"); }
            else { _services.Favorites.Insert(0, card); ShowToast("已加入收藏"); }
            _services.SaveState();
            SyncReaderFavoriteButton();
            if (_services.ActiveHomeMode == "fav") LoadActiveFeedAsync(1);
        }

        private void PushHistory(ComicCardModel card)
        {
            _services.History.RemoveAll(delegate (ComicCardModel h) { return h.Uid == card.Uid; });
            _services.History.Insert(0, card);
            if (_services.History.Count > 120) _services.History.RemoveRange(120, _services.History.Count - 120);
            _services.SaveState();
        }

        private void AddLocalFolderAction()
        {
            using (System.Windows.Forms.FolderBrowserDialog fbd = new System.Windows.Forms.FolderBrowserDialog())
            {
                fbd.Description = "选择包含漫画图片目录或 ZIP/CBZ/EPUB 的文件夹";
                if (fbd.ShowDialog() == System.Windows.Forms.DialogResult.OK)
                {
                    _services.AddWarehousePath(fbd.SelectedPath);
                    RenderWarehouseView();
                    RenderCollectionsView();
                    if (_services.ActiveHomeMode == "local") LoadActiveFeedAsync(1);
                    ShowToast("已添加并扫描：" + _services.GetLocalComicCards().Count + " 本本地漫画");
                }
            }
        }

        private void ImportLocalArchiveAction()
        {
            using (System.Windows.Forms.OpenFileDialog ofd = new System.Windows.Forms.OpenFileDialog())
            {
                ofd.Filter = "漫画压缩包 (*.zip;*.cbz;*.epub)|*.zip;*.cbz;*.epub|所有文件 (*.*)|*.*";
                ofd.Multiselect = true;
                if (ofd.ShowDialog() == System.Windows.Forms.DialogResult.OK)
                {
                    foreach (string f in ofd.FileNames) _services.AddWarehousePath(f);
                    RenderWarehouseView();
                    RenderCollectionsView();
                    if (_services.ActiveHomeMode == "local") LoadActiveFeedAsync(1);
                    ShowToast("导入完成：" + _services.GetLocalComicCards().Count + " 本本地漫画");
                }
            }
        }

        private void RescanLocalLibraryAction()
        {
            _services.RescanLocalLibrary();
            RenderWarehouseView();
            RenderCollectionsView();
            if (_services.ActiveHomeMode == "local") LoadActiveFeedAsync(1);
            ShowToast("重新扫描完成：" + _services.GetLocalComicCards().Count + " 本本地漫画");
        }

        /* ================= COLLECTIONS VIEW ================= */
        private Grid BuildCollectionsView()
        {
            Grid g = new Grid { Margin = new Thickness(24, 18, 24, 18) };
            g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            DockPanel header = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            WrapPanel btns = new WrapPanel { VerticalAlignment = VerticalAlignment.Center };
            DockPanel.SetDock(btns, Dock.Right);

            Button btnAddFolder = UiTheme.MakeButton("+ 添加本地文件夹", true, false);
            Button btnImportArc = UiTheme.MakeButton("+ 导入压缩包", false, false);
            Button btnRescan = UiTheme.MakeButton("↻ 重新扫描", false, false);
            Button btnNew = UiTheme.MakeButton("+ 新建作品集", false, false);
            Button btnManage = UiTheme.MakeButton("管理当前作品集", false, false);
            btnAddFolder.Margin = new Thickness(0, 0, 8, 6);
            btnImportArc.Margin = new Thickness(0, 0, 8, 6);
            btnRescan.Margin = new Thickness(0, 0, 8, 6);
            btnNew.Margin = new Thickness(0, 0, 8, 6);
            btnManage.Margin = new Thickness(0, 0, 0, 6);

            btnAddFolder.Click += delegate { AddLocalFolderAction(); };
            btnImportArc.Click += delegate { ImportLocalArchiveAction(); };
            btnRescan.Click += delegate { RescanLocalLibraryAction(); };
            btnNew.Click += delegate (object s, RoutedEventArgs e)
            {
                string name = PromptStringDialog("新建作品集", "请输入新作品集名称：", "");
                if (!string.IsNullOrWhiteSpace(name))
                {
                    string id = "col_" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    _services.Collections.Add(new CollectionModel { Id = id, Name = name.Trim(), Pin = "", HideFromAll = false, Items = new List<ComicCardModel>() });
                    _services.ActiveCollectionId = id;
                    _services.SaveState();
                    RenderCollectionsView();
                }
            };
            btnManage.Click += delegate (object s, RoutedEventArgs e) { OpenManageCollectionDialog(); };
            btns.Children.Add(btnAddFolder);
            btns.Children.Add(btnImportArc);
            btns.Children.Add(btnRescan);
            btns.Children.Add(btnNew);
            btns.Children.Add(btnManage);
            header.Children.Add(btns);

            StackPanel titles = new StackPanel();
            titles.Children.Add(new TextBlock { Text = "本地书架", FontSize = 22, FontWeight = FontWeights.Bold });
            TextBlock sub = new TextBlock { Text = "导入本机漫画文件夹与压缩包，分组整理并可设置 PIN 锁", FontSize = 13, Margin = new Thickness(0, 4, 0, 0) };
            sub.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            titles.Children.Add(sub);
            header.Children.Add(titles);
            g.Children.Add(header);

            _collectionChipsPanel = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 14) };
            Grid.SetRow(_collectionChipsPanel, 1);
            g.Children.Add(_collectionChipsPanel);

            Grid body = new Grid();
            Grid.SetRow(body, 2);
            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            _collectionGrid = new WrapPanel();
            sv.Content = _collectionGrid;
            body.Children.Add(sv);

            _collectionLockedPanel = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center, Visibility = Visibility.Collapsed };
            _collectionLockedTitle = new TextBlock { FontSize = 16, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 12) };
            Button btnUnlock = UiTheme.MakeButton("输入 PIN 解锁", true, false);
            btnUnlock.Click += delegate (object s, RoutedEventArgs e)
            {
                CollectionModel cur = _services.Collections.Find(delegate (CollectionModel c) { return c.Id == _services.ActiveCollectionId; });
                if (cur == null) return;
                string pin = PromptStringDialog("解锁作品集", "请输入作品集「" + cur.Name + "」的 PIN：", "");
                if (pin == cur.Pin) { _services.UnlockedCollections[cur.Id] = true; RenderCollectionsView(); }
                else if (pin != null) ShowToast("PIN 密码错误");
            };
            _collectionLockedPanel.Children.Add(_collectionLockedTitle);
            _collectionLockedPanel.Children.Add(btnUnlock);
            body.Children.Add(_collectionLockedPanel);

            _collectionEmptyText = new TextBlock
            {
                Text = "当前书架为空。可在漫画详情或封面右键菜单中管理书架。",
                VerticalAlignment = VerticalAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Center,
                FontSize = 14,
                Visibility = Visibility.Collapsed
            };
            _collectionEmptyText.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            body.Children.Add(_collectionEmptyText);
            g.Children.Add(body);
            return g;
        }

        private void RenderCollectionsView()
        {
            _collectionChipsPanel.Children.Clear();
            AddChipTo(_collectionChipsPanel, "全部本地", _services.ActiveCollectionId == "col_all", delegate
            {
                _services.ActiveCollectionId = "col_all";
                _services.SaveState();
                RenderCollectionsView();
            });
            if (_services.ActiveCollectionId == "col_all")
            {
                HashSet<string> hidden = new HashSet<string>(StringComparer.Ordinal);
                foreach (CollectionModel shelf in _services.Collections)
                    if (shelf.HideFromAll)
                        foreach (ComicCardModel item in shelf.Items) hidden.Add(item.Uid);
                _collectionGrid.Children.Clear();
                foreach (ComicCardModel comic in _services.GetLocalComicCards())
                    if (!hidden.Contains(comic.Uid)) _collectionGrid.Children.Add(BuildComicCardElement(comic, 172, 226));
                _collectionLockedPanel.Visibility = Visibility.Collapsed;
                _collectionEmptyText.Visibility = _collectionGrid.Children.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            }
            foreach (CollectionModel col in _services.Collections)
            {
                CollectionModel capCol = col;
                bool active = col.Id == _services.ActiveCollectionId;
                string label = (string.IsNullOrEmpty(col.Pin) ? "" : "🔒 ") + col.Name + " (" + (col.Items != null ? col.Items.Count : 0) + ")";
                Button b = UiTheme.MakeButton(label, active, false);
                b.Margin = new Thickness(0, 0, 8, 0);
                b.Click += delegate (object s, RoutedEventArgs e)
                {
                    _services.ActiveCollectionId = capCol.Id;
                    _services.SaveState();
                    RenderCollectionsView();
                };
                _collectionChipsPanel.Children.Add(b);
            }

            if (_services.ActiveCollectionId == "col_all") return;
            CollectionModel cur = _services.Collections.Find(delegate (CollectionModel c) { return c.Id == _services.ActiveCollectionId; }) ?? _services.Collections[0];
            _collectionGrid.Children.Clear();
            bool unlocked = false;
            _services.UnlockedCollections.TryGetValue(cur.Id, out unlocked);
            if (!string.IsNullOrEmpty(cur.Pin) && !unlocked)
            {
                _collectionLockedTitle.Text = "🔒 作品集「" + cur.Name + "」已设定隐私 PIN 锁";
                _collectionLockedPanel.Visibility = Visibility.Visible;
                _collectionEmptyText.Visibility = Visibility.Collapsed;
                return;
            }

            _collectionLockedPanel.Visibility = Visibility.Collapsed;
            if (cur.Items == null || !cur.Items.Exists(item => item.IsLocal))
            {
                _collectionEmptyText.Visibility = Visibility.Visible;
                return;
            }
            _collectionEmptyText.Visibility = Visibility.Collapsed;
            foreach (ComicCardModel card in cur.Items.FindAll(item => item.IsLocal))
            {
                _collectionGrid.Children.Add(BuildComicCardElement(card, 172, 226));
            }
        }

        private void OpenAddToCollectionDialog(ComicCardModel card, Window owner)
        {
            Window dlg = CreateStyledDialog("管理书架", 420, 380);
            if (owner != null && owner.IsLoaded) dlg.Owner = owner;
            DockPanel body = new DockPanel { Margin = new Thickness(16) };
            WrapPanel actions = new WrapPanel { Margin = new Thickness(0, 12, 0, 0) };
            DockPanel.SetDock(actions, Dock.Bottom);
            body.Children.Add(actions);
            StackPanel list = new StackPanel();
            body.Children.Add(new ScrollViewer { Content = list, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
            Dictionary<CollectionModel, CheckBox> choices = new Dictionary<CollectionModel, CheckBox>();

            Action<CollectionModel, bool> addChoice = delegate (CollectionModel collection, bool selected)
            {
                CheckBox choice = new CheckBox { Content = collection.Name, IsChecked = selected, Margin = new Thickness(0, 0, 0, 10) };
                choice.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
                list.Children.Add(choice);
                choices.Add(collection, choice);
            };
            foreach (CollectionModel collection in _services.Collections)
                addChoice(collection, collection.Items.Exists(item => item.Uid == card.Uid));

            AddBrowseButton(actions, "新建书架", delegate
            {
                string name = PromptStringDialog("新建书架", "书架名称", "", dlg);
                if (string.IsNullOrWhiteSpace(name)) return;
                CollectionModel collection = new CollectionModel
                {
                    Id = "col_" + Guid.NewGuid().ToString("N"), Name = name.Trim(), Pin = "", Items = new List<ComicCardModel>()
                };
                addChoice(collection, true);
            });
            AddBrowseButton(actions, "保存", delegate
            {
                foreach (KeyValuePair<CollectionModel, CheckBox> entry in choices)
                {
                    CollectionModel collection = entry.Key;
                    if (!_services.Collections.Contains(collection)) _services.Collections.Add(collection);
                    bool member = collection.Items.Exists(item => item.Uid == card.Uid);
                    if (entry.Value.IsChecked == true && !member) collection.Items.Insert(0, card);
                    if (entry.Value.IsChecked != true && member) collection.Items.RemoveAll(item => item.Uid == card.Uid);
                }
                _services.SaveState();
                if (_viewCollections.Visibility == Visibility.Visible) RenderCollectionsView();
                if (_services.ActiveHomeMode == "local") LoadActiveFeedAsync(1);
                ShowToast("已更新书架");
                dlg.Close();
            });
            AddBrowseButton(actions, "取消", dlg.Close);
            dlg.Content = body;
            dlg.ShowDialog();
        }

        private void OpenManageCollectionDialog()
        {
            CollectionModel cur = _services.Collections.Find(delegate (CollectionModel c) { return c.Id == _services.ActiveCollectionId; });
            if (cur == null) return;
            Window dlg = CreateStyledDialog("管理作品集 — " + cur.Name, 440, 320);
            StackPanel stack = new StackPanel { Margin = new Thickness(18) };
            stack.Children.Add(new TextBlock { Text = "名称", Margin = new Thickness(0, 0, 0, 4) });
            TextBox nameBox = MakeStyledTextBox();
            nameBox.Text = cur.Name;
            nameBox.Margin = new Thickness(0, 0, 0, 12);
            stack.Children.Add(nameBox);

            stack.Children.Add(new TextBlock { Text = "隐私 PIN 锁（留空表示不加密）", Margin = new Thickness(0, 0, 0, 4) });
            TextBox pinBox = MakeStyledTextBox();
            pinBox.Text = cur.Pin ?? "";
            pinBox.Margin = new Thickness(0, 0, 0, 12);
            stack.Children.Add(pinBox);

            DockPanel hideRow = new DockPanel { Margin = new Thickness(0, 0, 0, 16) };
            CheckBox chkHide = UiTheme.MakeToggle(cur.HideFromAll);
            DockPanel.SetDock(chkHide, Dock.Right);
            hideRow.Children.Add(chkHide);
            hideRow.Children.Add(new TextBlock { Text = "从「本地全部」隐藏该作品集内漫画", VerticalAlignment = VerticalAlignment.Center });
            stack.Children.Add(hideRow);

            StackPanel btnRow = new StackPanel { Orientation = Orientation.Horizontal };
            Button btnSave = UiTheme.MakeButton("保存", true, false);
            btnSave.Click += delegate (object s, RoutedEventArgs e)
            {
                if (!string.IsNullOrWhiteSpace(nameBox.Text)) cur.Name = nameBox.Text.Trim();
                cur.Pin = (pinBox.Text ?? "").Trim();
                cur.HideFromAll = chkHide.IsChecked == true;
                if (string.IsNullOrEmpty(cur.Pin)) _services.UnlockedCollections.Remove(cur.Id);
                _services.SaveState();
                dlg.Close();
                RenderCollectionsView();
            };
            btnRow.Children.Add(btnSave);

            if (_services.Collections.Count > 1)
            {
                Button btnDel = UiTheme.MakeButton("删除此作品集", false, true);
                btnDel.Margin = new Thickness(10, 0, 0, 0);
                btnDel.Click += delegate (object s, RoutedEventArgs e)
                {
                    _services.Collections.Remove(cur);
                    _services.ActiveCollectionId = _services.Collections[0].Id;
                    _services.SaveState();
                    dlg.Close();
                    RenderCollectionsView();
                };
                btnRow.Children.Add(btnDel);
            }
            stack.Children.Add(btnRow);
            dlg.Content = stack;
            dlg.ShowDialog();
        }

        /* ================= WAREHOUSE VIEW (Embedded on Right) ================= */
        private Grid BuildWarehouseView()
        {
            Grid g = new Grid { Margin = new Thickness(24, 18, 24, 18) };
            g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            DockPanel header = new DockPanel { Margin = new Thickness(0, 0, 0, 16) };
            StackPanel btns = new StackPanel { Orientation = Orientation.Horizontal };
            DockPanel.SetDock(btns, Dock.Right);
            Button btnFolder = UiTheme.MakeButton("+ 添加本地文件夹", true, false);
            Button btnArchive = UiTheme.MakeButton("+ 导入压缩包", false, false);
            Button btnRescan = UiTheme.MakeButton("↻ 重新扫描", false, false);
            btnFolder.Margin = new Thickness(0, 0, 8, 0);
            btnArchive.Margin = new Thickness(0, 0, 8, 0);
            btnFolder.Click += delegate { AddLocalFolderAction(); };
            btnArchive.Click += delegate { ImportLocalArchiveAction(); };
            btnRescan.Click += delegate { RescanLocalLibraryAction(); };
            btns.Children.Add(btnFolder);
            btns.Children.Add(btnArchive);
            btns.Children.Add(btnRescan);
            header.Children.Add(btns);

            StackPanel titles = new StackPanel();
            titles.Children.Add(new TextBlock { Text = "漫画仓库", FontSize = 22, FontWeight = FontWeights.Bold });
            TextBlock sub = new TextBlock { Text = "挂载本机漫画目录、ZIP / CBZ / EPUB 压缩包或配置 WebDAV 云端仓库", FontSize = 13, Margin = new Thickness(0, 4, 0, 0) };
            sub.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            titles.Children.Add(sub);
            header.Children.Add(titles);
            g.Children.Add(header);

            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            Grid.SetRow(sv, 1);
            StackPanel stack = new StackPanel();

            // Mounted paths card
            Border pathsCard = MakeCardBlock();
            StackPanel pcStack = new StackPanel();
            DockPanel pcHeader = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            _warehouseCountBadge = new TextBlock { FontSize = 12.5, FontWeight = FontWeights.Bold, VerticalAlignment = VerticalAlignment.Center };
            _warehouseCountBadge.SetResourceReference(TextBlock.ForegroundProperty, "Brush.Accent");
            DockPanel.SetDock(_warehouseCountBadge, Dock.Right);
            pcHeader.Children.Add(_warehouseCountBadge);
            pcHeader.Children.Add(new TextBlock { Text = "已挂载本机目录与压缩包", FontSize = 15, FontWeight = FontWeights.Bold });
            pcStack.Children.Add(pcHeader);
            _warehousePathsPanel = new StackPanel();
            pcStack.Children.Add(_warehousePathsPanel);
            pathsCard.Child = pcStack;
            stack.Children.Add(pathsCard);

            // WebDAV card
            Border davCard = MakeCardBlock();
            StackPanel dcStack = new StackPanel();
            DockPanel dcHeader = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            _webdavStatusText = new TextBlock { Text = "未测试", FontSize = 12.5, VerticalAlignment = VerticalAlignment.Center };
            _webdavStatusText.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            DockPanel.SetDock(_webdavStatusText, Dock.Right);
            dcHeader.Children.Add(_webdavStatusText);
            dcHeader.Children.Add(new TextBlock { Text = "WebDAV 云端仓库", FontSize = 15, FontWeight = FontWeights.Bold });
            dcStack.Children.Add(dcHeader);

            dcStack.Children.Add(new TextBlock { Text = "服务器地址 (URL)", FontSize = 12, Margin = new Thickness(0, 0, 0, 4) });
            _webdavUrlBox = MakeStyledTextBox();
            _webdavUrlBox.Margin = new Thickness(0, 0, 0, 10);
            dcStack.Children.Add(_webdavUrlBox);

            Grid credGrid = new Grid { Margin = new Thickness(0, 0, 0, 12) };
            credGrid.ColumnDefinitions.Add(new ColumnDefinition());
            credGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(12) });
            credGrid.ColumnDefinitions.Add(new ColumnDefinition());
            StackPanel uCol = new StackPanel();
            uCol.Children.Add(new TextBlock { Text = "用户名", FontSize = 12, Margin = new Thickness(0, 0, 0, 4) });
            _webdavUserBox = MakeStyledTextBox();
            uCol.Children.Add(_webdavUserBox);
            credGrid.Children.Add(uCol);

            StackPanel pCol = new StackPanel();
            Grid.SetColumn(pCol, 2);
            pCol.Children.Add(new TextBlock { Text = "密码", FontSize = 12, Margin = new Thickness(0, 0, 0, 4) });
            _webdavPassBox = MakeStyledPasswordBox();
            pCol.Children.Add(_webdavPassBox);
            credGrid.Children.Add(pCol);
            dcStack.Children.Add(credGrid);

            StackPanel davBtns = new StackPanel { Orientation = Orientation.Horizontal };
            Button btnSaveDav = UiTheme.MakeButton("保存配置", true, false);
            Button btnTestDav = UiTheme.MakeButton("测试连接", false, false);
            btnTestDav.Margin = new Thickness(10, 0, 0, 0);
            btnSaveDav.Click += delegate (object s, RoutedEventArgs e)
            {
                _services.WebDavUrl = _webdavUrlBox.Text.Trim();
                _services.WebDavUser = _webdavUserBox.Text.Trim();
                _services.WebDavPass = _webdavPassBox.Password;
                _services.SaveState();
                ShowToast("WebDAV 配置已保存");
            };
            btnTestDav.Click += async delegate (object s, RoutedEventArgs e)
            {
                _webdavStatusText.Text = "测试中...";
                string u = _webdavUrlBox.Text.Trim(), usr = _webdavUserBox.Text.Trim(), pw = _webdavPassBox.Password;
                bool ok = false;
                string msg = await Task.Run<string>(delegate () { return _services.TestWebDav(u, usr, pw, out ok); });
                _webdavStatusText.Text = ok ? "连接成功 ✓" : "连接失败";
                ShowToast(msg);
            };
            davBtns.Children.Add(btnSaveDav);
            davBtns.Children.Add(btnTestDav);
            dcStack.Children.Add(davBtns);
            davCard.Child = dcStack;
            stack.Children.Add(davCard);

            sv.Content = stack;
            g.Children.Add(sv);
            return g;
        }

        private void RenderWarehouseView()
        {
            _warehouseCountBadge.Text = _services.GetLocalComicCards().Count + " 本本地漫画";
            _webdavUrlBox.Text = _services.WebDavUrl ?? "";
            _webdavUserBox.Text = _services.WebDavUser ?? "";
            _webdavPassBox.Password = _services.WebDavPass ?? "";

            _warehousePathsPanel.Children.Clear();
            if (_services.WarehousePaths.Count == 0)
            {
                TextBlock empty = new TextBlock { Text = "尚未挂载任何目录或压缩包。点击右上角按钮添加。" };
                empty.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
                _warehousePathsPanel.Children.Add(empty);
                return;
            }
            foreach (string path in _services.WarehousePaths)
            {
                string capPath = path;
                Border row = new Border { CornerRadius = new CornerRadius(8), Padding = new Thickness(12, 8, 12, 8), Margin = new Thickness(0, 0, 0, 8) };
                row.SetResourceReference(Border.BackgroundProperty, "Brush.Surface");
                DockPanel dp = new DockPanel();
                Button bRem = UiTheme.MakeButton("移除", false, true);
                bRem.Padding = new Thickness(10, 4, 10, 4);
                DockPanel.SetDock(bRem, Dock.Right);
                dp.Children.Add(bRem);
                TextBlock tb = new TextBlock { Text = path, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
                dp.Children.Add(tb);
                row.Child = dp;
                bRem.Click += delegate (object s, RoutedEventArgs e)
                {
                    _services.RemoveWarehousePath(capPath);
                    RenderWarehouseView();
                    if (_services.ActiveHomeMode == "local") LoadActiveFeedAsync(1);
                };
                _warehousePathsPanel.Children.Add(row);
            }
        }

        /* ================= SETTINGS VIEW (Embedded on Right) ================= */
        private UIElement BuildAvatar(double size)
        {
            try
            {
                if (File.Exists(_services.ProfileAvatarPath))
                {
                    BitmapImage image = new BitmapImage();
                    image.BeginInit();
                    image.UriSource = new Uri(_services.ProfileAvatarPath);
                    image.CacheOption = BitmapCacheOption.OnLoad;
                    image.CreateOptions = BitmapCreateOptions.IgnoreImageCache;
                    image.EndInit();
                    return new Ellipse { Width = size, Height = size, Fill = new ImageBrush(image) { Stretch = Stretch.UniformToFill } };
                }
            }
            catch (Exception ex) { Trace.TraceWarning("Could not open profile avatar: " + ex.GetType().Name); }
            TextBlock person = new TextBlock { Text = "\uE77B", FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = size * 0.43, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            person.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            return person;
        }

        private Grid BuildReadingProfile()
        {
            Grid view = new Grid { Margin = new Thickness(24, 18, 24, 18), Visibility = Visibility.Collapsed };
            _profileBody = new StackPanel { MaxWidth = 840 };
            view.Children.Add(new ScrollViewer { Content = _profileBody, VerticalScrollBarVisibility = ScrollBarVisibility.Hidden });
            return view;
        }

        private static string FormatDuration(long millis)
        {
            long minutes = millis / 60000;
            if (minutes >= 60) return string.Format("{0} 小时 {1} 分", minutes / 60, minutes % 60);
            if (minutes > 0) return minutes + " 分";
            return millis > 0 ? "不足 1 分" : "0 分";
        }

        private void RefreshReadingProfile()
        {
            var total = _services.ReadingActivity.Entries.Select(row => IsPrivateReadingWork(row.ComicId)
                ? new ReadingEntry { Day = row.Day, ComicId = "", Title = "私人作品", Milliseconds = row.Milliseconds } : row).ToList();
            RenderReadingProfile(total, null, false);
        }

        private bool IsPrivateReadingWork(string uid)
        {
            return _services.Collections.Any(shelf => (shelf.HideFromAll || !string.IsNullOrEmpty(shelf.Pin)) &&
                shelf.Items.Exists(item => item.Uid == uid));
        }

        private void ChooseProfileAvatar()
        {
            var picker = new Microsoft.Win32.OpenFileDialog { Filter = "图片|*.jpg;*.jpeg;*.png;*.bmp;*.gif;*.tiff" };
            if (picker.ShowDialog(this) != true) return;
            string pending = _services.ProfileAvatarPath + ".tmp";
            try
            {
                BitmapImage source = new BitmapImage();
                source.BeginInit();
                source.UriSource = new Uri(picker.FileName);
                source.CacheOption = BitmapCacheOption.OnLoad;
                source.DecodePixelWidth = 512;
                source.EndInit();
                JpegBitmapEncoder encoder = new JpegBitmapEncoder { QualityLevel = 90 };
                encoder.Frames.Add(BitmapFrame.Create(source));
                using (FileStream stream = File.Create(pending)) encoder.Save(stream);
                if (File.Exists(_services.ProfileAvatarPath)) File.Replace(pending, _services.ProfileAvatarPath, null);
                else File.Move(pending, _services.ProfileAvatarPath);
                _profileDockButton.Content = BuildAvatar(48);
                RefreshReadingProfile();
            }
            catch { ShowToast("无法保存头像"); }
            finally { if (File.Exists(pending)) File.Delete(pending); }
        }

        private void OpenIssueFeedback()
        {
            Window dialog = CreateStyledDialog("问题反馈", 540, 535);
            StackPanel fields = new StackPanel { Margin = new Thickness(20) };
            fields.Children.Add(StatText("提交后将公开发布到 zmf678/koma-comic。", 13, true));
            TextBox title = MakeStyledTextBox();
            title.MaxLength = 100;
            TextBox body = MakeStyledTextBox();
            body.MaxLength = 600;
            body.AcceptsReturn = true;
            body.TextWrapping = TextWrapping.Wrap;
            body.Height = 112;
            body.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
            fields.Children.Add(new TextBlock { Text = "问题标题", Margin = new Thickness(0, 0, 0, 5) });
            fields.Children.Add(title);
            fields.Children.Add(new TextBlock { Text = "问题说明、复现步骤及预期结果", Margin = new Thickness(0, 12, 0, 5) });
            fields.Children.Add(body);
            CheckBox include = new CheckBox { Content = "附上应用版本和 Windows 版本", Margin = new Thickness(0, 10, 0, 5) };
            include.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            fields.Children.Add(include);
            bool hasToken = !string.IsNullOrWhiteSpace(_services.FeedbackProtectedToken);
            var token = new PasswordBox { MinHeight = 33, MaxLength = 400 };
            var caption = StatText(hasToken ? "GitHub Token 已保存，留空继续使用" : "GitHub Token（可创建 Issue）", 14);
            caption.Margin = new Thickness(0, 8, 0, 5);
            fields.Children.Add(caption);
            fields.Children.Add(token);
            var remember = new CheckBox { Content = "在本机加密保存 Token", IsChecked = hasToken, Margin = new Thickness(0, 8, 0, 7) };
            remember.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            fields.Children.Add(remember);
            TextBlock message = StatText("", 14, true);
            message.Margin = new Thickness(0, 4, 0, 8);
            fields.Children.Add(message);
            WrapPanel actions = new WrapPanel();
            var submit = UiTheme.MakeButton("提交 Issue", true, false);
            actions.Children.Add(submit);
            var close = AddBrowseButton(actions, "关闭", dialog.Close);
            bool sending = false;
            dialog.Closing += delegate (object sender, System.ComponentModel.CancelEventArgs args)
            {
                if (sending) args.Cancel = true;
            };
            submit.Click += async delegate
            {
                string subject = title.Text.Trim();
                string text = body.Text.Trim();
                if (subject.Length == 0 || text.Length == 0)
                {
                    message.Text = "请填写标题和说明";
                    return;
                }
                string secret = token.Password.Trim();
                if (secret.Length == 0 && hasToken)
                {
                    try { secret = TranslationModel.DecryptSecret(_services.FeedbackProtectedToken); }
                    catch (Exception) { message.Text = "请重新填写 GitHub Token"; return; }
                }
                if (include.IsChecked == true) text += "\n\n环境 · 页光 Koma " + Program.AppVersion + " · Windows " + Environment.OSVersion.Version;
                bool save = remember.IsChecked == true;
                sending = true;
                submit.IsEnabled = close.IsEnabled = false;
                message.Text = "正在提交…";
                try
                {
                    int number = await GithubFeedback.SubmitAsync(subject, text, secret);
                    message.Text = number > 0 ? "已提交 Issue #" + number : "GitHub 已接收反馈";
                    submit.Visibility = Visibility.Collapsed;
                    token.Password = "";
                    foreach (Control field in new Control[] { title, body, token, remember, include }) field.IsEnabled = false;
                    try
                    {
                        _services.FeedbackProtectedToken = save ? TranslationModel.EncryptKey(secret) : "";
                        _services.SaveState();
                    }
                    catch (Exception) { message.Text += "。Token 设置未保存"; }
                }
                catch (Exception error)
                {
                    message.Text = error is InvalidOperationException || error is ArgumentException ? error.Message : "提交失败，请检查 GitHub 授权";
                    submit.IsEnabled = true;
                }
                finally
                {
                    sending = false;
                    close.IsEnabled = true;
                }
            };
            fields.Children.Add(actions);
            dialog.Content = new ScrollViewer { Content = fields, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            dialog.ShowDialog();
        }

        private Grid BuildSettingsView()
        {
            Grid g = new Grid { Margin = new Thickness(24, 18, 24, 18) };
            g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            StackPanel header = new StackPanel { Margin = new Thickness(0, 0, 0, 16) };
            header.Children.Add(new TextBlock { Text = "应用设置", FontSize = 22, FontWeight = FontWeights.Bold });

            g.Children.Add(header);

            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            Grid.SetRow(sv, 1);
            StackPanel stack = new StackPanel();

            // Theme Card
            Border themeCard = MakeCardBlock();
            StackPanel tc = new StackPanel();
            tc.Children.Add(new TextBlock { Text = "外观主题", FontSize = 15, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 12) });
            StackPanel themeRow = new StackPanel { Orientation = Orientation.Horizontal };
            Button bDark = UiTheme.MakeButton("深色模式", _services.ThemeMode == "dark", false);
            Button bLight = UiTheme.MakeButton("浅色模式", _services.ThemeMode == "light", false);
            Button bSys = UiTheme.MakeButton("跟随系统", _services.ThemeMode == "system", false);
            bDark.Margin = new Thickness(0, 0, 8, 0);
            bLight.Margin = new Thickness(0, 0, 8, 0);
            bDark.Click += delegate (object s, RoutedEventArgs e) { SwitchTheme("dark"); bDark.Style = UiTheme.BuildPillButtonStyle(true, false); bLight.Style = UiTheme.BuildPillButtonStyle(false, false); bSys.Style = UiTheme.BuildPillButtonStyle(false, false); };
            bLight.Click += delegate (object s, RoutedEventArgs e) { SwitchTheme("light"); bDark.Style = UiTheme.BuildPillButtonStyle(false, false); bLight.Style = UiTheme.BuildPillButtonStyle(true, false); bSys.Style = UiTheme.BuildPillButtonStyle(false, false); };
            bSys.Click += delegate (object s, RoutedEventArgs e) { SwitchTheme("system"); bDark.Style = UiTheme.BuildPillButtonStyle(false, false); bLight.Style = UiTheme.BuildPillButtonStyle(false, false); bSys.Style = UiTheme.BuildPillButtonStyle(true, false); };
            themeRow.Children.Add(bDark);
            themeRow.Children.Add(bLight);
            themeRow.Children.Add(bSys);
            tc.Children.Add(themeRow);
            WrapPanel materials = new WrapPanel { Margin = new Thickness(0, 12, 0, 0) };
            CheckBox glass = new CheckBox { Content = "液态玻璃", IsChecked = _services.GlassMaterial, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 14, 4) };
            glass.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            glass.Click += delegate { _services.GlassMaterial = glass.IsChecked == true; _services.SaveState(); ApplyLibraryMaterial(); };
            materials.Children.Add(glass);
            AddBrowseButton(materials, "选择背景壁纸", ChooseWallpaper);
            AddBrowseButton(materials, "关闭壁纸", delegate { _services.WallpaperEnabled = false; _services.SaveState(); ApplyLibraryMaterial(); });
            tc.Children.Add(materials);
            themeCard.Child = tc;
            stack.Children.Add(themeCard);

            // Reader defaults card
            Border rdCard = MakeCardBlock();
            StackPanel rdc = new StackPanel();
            rdc.Children.Add(new TextBlock { Text = "默认阅读偏好", FontSize = 15, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 12) });

            DockPanel rowMode = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            StackPanel modeBtns = new StackPanel { Orientation = Orientation.Horizontal };
            DockPanel.SetDock(modeBtns, Dock.Right);
            Button bmLtr = UiTheme.MakeButton("普通 (L→R)", _services.ReaderDefaults.Mode == "ltr", false);
            Button bmRtl = UiTheme.MakeButton("日漫 (R→L)", _services.ReaderDefaults.Mode == "rtl", false);
            Button bmScr = UiTheme.MakeButton("滚动条漫", _services.ReaderDefaults.Mode == "scroll", false);
            bmLtr.Margin = new Thickness(0, 0, 6, 0);
            bmRtl.Margin = new Thickness(0, 0, 6, 0);
            bmLtr.Click += delegate (object s, RoutedEventArgs e) { _services.ReaderDefaults.Mode = "ltr"; _services.SaveState(); bmLtr.Style = UiTheme.BuildPillButtonStyle(true, false); bmRtl.Style = UiTheme.BuildPillButtonStyle(false, false); bmScr.Style = UiTheme.BuildPillButtonStyle(false, false); };
            bmRtl.Click += delegate (object s, RoutedEventArgs e) { _services.ReaderDefaults.Mode = "rtl"; _services.SaveState(); bmLtr.Style = UiTheme.BuildPillButtonStyle(false, false); bmRtl.Style = UiTheme.BuildPillButtonStyle(true, false); bmScr.Style = UiTheme.BuildPillButtonStyle(false, false); };
            bmScr.Click += delegate (object s, RoutedEventArgs e) { _services.ReaderDefaults.Mode = "scroll"; _services.SaveState(); bmLtr.Style = UiTheme.BuildPillButtonStyle(false, false); bmRtl.Style = UiTheme.BuildPillButtonStyle(false, false); bmScr.Style = UiTheme.BuildPillButtonStyle(true, false); };
            modeBtns.Children.Add(bmLtr);
            modeBtns.Children.Add(bmRtl);
            modeBtns.Children.Add(bmScr);
            rowMode.Children.Add(modeBtns);
            rowMode.Children.Add(new TextBlock { Text = "默认翻页模式", VerticalAlignment = VerticalAlignment.Center, FontSize = 13.5 });
            rdc.Children.Add(rowMode);

            DockPanel rowSp = new DockPanel();
            StackPanel spBtns = new StackPanel { Orientation = Orientation.Horizontal };
            DockPanel.SetDock(spBtns, Dock.Right);
            Button bsSin = UiTheme.MakeButton("单页", _services.ReaderDefaults.Spread == "single", false);
            Button bsDbl = UiTheme.MakeButton("双页合并", _services.ReaderDefaults.Spread == "double", false);
            Button bsSpl = UiTheme.MakeButton("双页分割", _services.ReaderDefaults.Spread == "split", false);
            bsSin.Margin = new Thickness(0, 0, 6, 0);
            bsDbl.Margin = new Thickness(0, 0, 6, 0);
            bsSin.Click += delegate (object s, RoutedEventArgs e) { _services.ReaderDefaults.Spread = "single"; _services.SaveState(); bsSin.Style = UiTheme.BuildPillButtonStyle(true, false); bsDbl.Style = UiTheme.BuildPillButtonStyle(false, false); bsSpl.Style = UiTheme.BuildPillButtonStyle(false, false); };
            bsDbl.Click += delegate (object s, RoutedEventArgs e) { _services.ReaderDefaults.Spread = "double"; _services.SaveState(); bsSin.Style = UiTheme.BuildPillButtonStyle(false, false); bsDbl.Style = UiTheme.BuildPillButtonStyle(true, false); bsSpl.Style = UiTheme.BuildPillButtonStyle(false, false); };
            bsSpl.Click += delegate (object s, RoutedEventArgs e) { _services.ReaderDefaults.Spread = "split"; _services.SaveState(); bsSin.Style = UiTheme.BuildPillButtonStyle(false, false); bsDbl.Style = UiTheme.BuildPillButtonStyle(false, false); bsSpl.Style = UiTheme.BuildPillButtonStyle(true, false); };
            spBtns.Children.Add(bsSin);
            spBtns.Children.Add(bsDbl);
            spBtns.Children.Add(bsSpl);
            rowSp.Children.Add(spBtns);
            rowSp.Children.Add(new TextBlock { Text = "宽屏单双页布局", VerticalAlignment = VerticalAlignment.Center, FontSize = 13.5 });
            rdc.Children.Add(rowSp);
            Button translate = AddBrowseButton(rdc, "漫画翻译设置", OpenTranslationSettings);
            translate.HorizontalAlignment = HorizontalAlignment.Left;
            translate.Margin = new Thickness(0, 12, 0, 0);
            rdCard.Child = rdc;
            stack.Children.Add(rdCard);

            // Cache & Sources card
            Border sysCard = MakeCardBlock();
            StackPanel sc = new StackPanel();
            sc.Children.Add(new TextBlock { Text = "源与缓存维护", FontSize = 15, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 12) });
            DockPanel rowMgr = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            Button bOpenMgr = UiTheme.MakeButton("打开源管理", true, false);
            DockPanel.SetDock(bOpenMgr, Dock.Right);
            rowMgr.Children.Add(bOpenMgr);
            bOpenMgr.Click += delegate (object s, RoutedEventArgs e) { OpenSourceManagerDialog(); };
            rowMgr.Children.Add(new TextBlock { Text = string.Format("漫画源与登录（{0} 个）", _services.Sources.Count), VerticalAlignment = VerticalAlignment.Center, FontSize = 13.5 });
            sc.Children.Add(rowMgr);
            Button warehouses = AddBrowseButton(sc, "本地仓库与 WebDAV", delegate { SwitchMainTab(4); });
            warehouses.HorizontalAlignment = HorizontalAlignment.Left;

            DockPanel rowCache = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            Button bClrCache = UiTheme.MakeButton("清理图片缓存", false, true);
            DockPanel.SetDock(bClrCache, Dock.Right);
            rowCache.Children.Add(bClrCache);
            bClrCache.Click += delegate (object s, RoutedEventArgs e)
            {
                int n = _services.ClearImageCache();
                ShowToast("已清理 " + n + " 个缓存图片文件");
            };
            rowCache.Children.Add(new TextBlock { Text = "本地封面与阅读缓存 (data/cache/images/)", VerticalAlignment = VerticalAlignment.Center, FontSize = 13.5 });
            sc.Children.Add(rowCache);

            DockPanel rowUpdate = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
            Button bCheckUpdate = UiTheme.MakeButton("检查并自动更新", true, false);
            DockPanel.SetDock(bCheckUpdate, Dock.Right);
            rowUpdate.Children.Add(bCheckUpdate);
            StackPanel updTextStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            updTextStack.Children.Add(new TextBlock { Text = string.Format("应用更新（当前 v{0}）", Program.AppVersion), FontSize = 13.5 });
            TextBlock updStatus = new TextBlock { Text = "从 GitHub Releases 自动检测并下载覆盖升级（保留 data 数据）", FontSize = 12, Margin = new Thickness(0, 2, 0, 0) };
            updStatus.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            updTextStack.Children.Add(updStatus);
            rowUpdate.Children.Add(updTextStack);
            bCheckUpdate.Click += delegate { CheckAndRunAutoUpdateAsync(bCheckUpdate, updStatus); };
            sc.Children.Add(rowUpdate);

            TextBlock about = new TextBlock
            {
                Text = string.Format("页光 Koma v{0} · Windows 原生 WPF DirectX 高清版 (Per-Monitor V2 DPI)", Program.AppVersion),
                FontSize = 12
            };
            about.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            sc.Children.Add(about);
            sysCard.Child = sc;
            stack.Children.Add(sysCard);

            sv.Content = stack;
            g.Children.Add(sv);
            return g;
        }

        private sealed class AppReleaseInfo
        {
            public string TagName;
            public string VersionText;
            public string Title;
            public string Notes;
            public string HtmlUrl;
            public string AssetName;
            public string DownloadUrl;
            public long AssetSize;
        }

        private static int CompareSemVer(string remoteVer, string localVer)
        {
            int[] a = ParseVersionParts(remoteVer);
            int[] b = ParseVersionParts(localVer);
            int len = Math.Max(a.Length, b.Length);
            for (int i = 0; i < len; i++)
            {
                int va = i < a.Length ? a[i] : 0;
                int vb = i < b.Length ? b[i] : 0;
                if (va != vb) return va.CompareTo(vb);
            }
            return 0;
        }

        private static int[] ParseVersionParts(string raw)
        {
            string clean = (raw ?? "").Trim().TrimStart('v', 'V');
            int dash = clean.IndexOf('-');
            if (dash >= 0) clean = clean.Substring(0, dash);
            string[] segs = clean.Split('.');
            int[] nums = new int[segs.Length];
            for (int i = 0; i < segs.Length; i++)
            {
                int v;
                if (int.TryParse(segs[i], out v)) nums[i] = v;
            }
            return nums;
        }

        private static Task<AppReleaseInfo> FetchLatestReleaseAsync()
        {
            return Task.Run<AppReleaseInfo>(delegate ()
            {
                string apiUrl = "https://api.github.com/repos/" + Program.GithubRepoSlug + "/releases/latest";
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(apiUrl);
                req.Method = "GET";
                req.UserAgent = "KomaComic-Windows/" + Program.AppVersion;
                req.Accept = "application/vnd.github+json";
                req.Timeout = 15000;
                req.ReadWriteTimeout = 15000;
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                using (StreamReader sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    string json = sr.ReadToEnd();
                    Dictionary<string, object> root = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(json);
                    if (root == null) throw new InvalidOperationException("无法解析版本响应");
                    AppReleaseInfo info = new AppReleaseInfo();
                    info.TagName = JsonUtil.GetStr(root, "tag_name", "");
                    info.VersionText = info.TagName.TrimStart('v', 'V');
                    info.Title = JsonUtil.GetStr(root, "name", info.TagName);
                    info.Notes = JsonUtil.GetStr(root, "body", "");
                    info.HtmlUrl = JsonUtil.GetStr(root, "html_url", "https://github.com/" + Program.GithubRepoSlug + "/releases");
                    object assetsObj;
                    if (root.TryGetValue("assets", out assetsObj) && assetsObj is ArrayList)
                    {
                        foreach (object el in (ArrayList)assetsObj)
                        {
                            Dictionary<string, object> asset = el as Dictionary<string, object>;
                            if (asset == null) continue;
                            string name = JsonUtil.GetStr(asset, "name", "");
                            string url = JsonUtil.GetStr(asset, "browser_download_url", "");
                            if (string.IsNullOrEmpty(url)) continue;
                            if (name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase) &&
                                (name.IndexOf("windows", StringComparison.OrdinalIgnoreCase) >= 0 || string.IsNullOrEmpty(info.DownloadUrl)))
                            {
                                info.AssetName = name;
                                info.DownloadUrl = url;
                                object szObj;
                                if (asset.TryGetValue("size", out szObj) && szObj != null)
                                {
                                    long sz;
                                    if (long.TryParse(Convert.ToString(szObj), out sz)) info.AssetSize = sz;
                                }
                                if (name.IndexOf("windows", StringComparison.OrdinalIgnoreCase) >= 0) break;
                            }
                        }
                    }
                    return info;
                }
            });
        }

        private async void CheckAndRunAutoUpdateAsync(Button triggerBtn, TextBlock statusLabel)
        {
            if (triggerBtn != null)
            {
                triggerBtn.IsEnabled = false;
                triggerBtn.Content = "正在检查…";
            }
            if (statusLabel != null) statusLabel.Text = "正在连接 GitHub 检查最新版本…";
            try
            {
                AppReleaseInfo latest = await FetchLatestReleaseAsync();
                if (string.IsNullOrEmpty(latest.VersionText))
                    throw new InvalidOperationException("未获取到有效版本号");

                int cmp = CompareSemVer(latest.VersionText, Program.AppVersion);
                if (cmp <= 0)
                {
                    if (statusLabel != null) statusLabel.Text = string.Format("当前已是最新版本 (v{0}) ✓", Program.AppVersion);
                    ShowToast(string.Format("当前已是最新版本 (v{0})", Program.AppVersion));
                    return;
                }

                if (statusLabel != null) statusLabel.Text = string.Format("发现新版本 v{0}（当前 v{1}）", latest.VersionText, Program.AppVersion);
                ShowAutoUpdateDialog(latest);
            }
            catch (Exception ex)
            {
                string err = ex.Message.Split('\n')[0];
                if (statusLabel != null) statusLabel.Text = "检查更新失败：" + err;
                ShowToast("检查更新失败：" + err);
            }
            finally
            {
                if (triggerBtn != null)
                {
                    triggerBtn.IsEnabled = true;
                    triggerBtn.Content = "检查并自动更新";
                }
            }
        }

        private void ShowAutoUpdateDialog(AppReleaseInfo info)
        {
            Window dlg = CreateStyledDialog("发现新版本 — " + info.Title, 540, 440);
            DockPanel root = new DockPanel { Margin = new Thickness(20) };

            StackPanel head = new StackPanel { Margin = new Thickness(0, 0, 0, 12) };
            DockPanel.SetDock(head, Dock.Top);
            string sizeStr = info.AssetSize > 0
                ? string.Format("  ·  {0:F2} MB", info.AssetSize / (1024.0 * 1024.0))
                : "";
            head.Children.Add(new TextBlock
            {
                Text = string.Format("最新版本 v{0}（当前 v{1}）{2}", info.VersionText, Program.AppVersion, sizeStr),
                FontSize = 16,
                FontWeight = FontWeights.Bold
            });
            TextBlock hint = new TextBlock
            {
                Text = "自动更新仅替换程序与内置源，保留 data 目录中的所有收藏、历史和配置。",
                FontSize = 12,
                Margin = new Thickness(0, 4, 0, 0)
            };
            hint.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            head.Children.Add(hint);
            root.Children.Add(head);

            StackPanel bottom = new StackPanel { Margin = new Thickness(0, 12, 0, 0) };
            DockPanel.SetDock(bottom, Dock.Bottom);
            ProgressBar bar = new ProgressBar { Height = 8, Minimum = 0, Maximum = 100, Value = 0, Visibility = Visibility.Collapsed, Margin = new Thickness(0, 0, 0, 6) };
            TextBlock progressText = new TextBlock { FontSize = 12, Visibility = Visibility.Collapsed, Margin = new Thickness(0, 0, 0, 10) };
            progressText.SetResourceReference(TextBlock.ForegroundProperty, "Brush.TextMuted");
            bottom.Children.Add(bar);
            bottom.Children.Add(progressText);

            StackPanel btnRow = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            Button bOpenPage = UiTheme.MakeButton("打开发布页", false, false);
            Button bCancel = UiTheme.MakeButton("稍后再说", false, false);
            Button bUpdate = UiTheme.MakeButton("一键下载并自动更新", true, false);
            bOpenPage.Margin = new Thickness(0, 0, 8, 0);
            bCancel.Margin = new Thickness(0, 0, 8, 0);
            bUpdate.IsEnabled = !string.IsNullOrEmpty(info.DownloadUrl);
            btnRow.Children.Add(bOpenPage);
            btnRow.Children.Add(bCancel);
            btnRow.Children.Add(bUpdate);
            bottom.Children.Add(btnRow);
            root.Children.Add(bottom);

            TextBox notesBox = MakeStyledTextBox();
            notesBox.Text = string.IsNullOrWhiteSpace(info.Notes) ? "包含最新功能改进与问题修复。" : info.Notes.Trim();
            notesBox.IsReadOnly = true;
            notesBox.TextWrapping = TextWrapping.Wrap;
            notesBox.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
            root.Children.Add(notesBox);
            dlg.Content = root;

            CancellationTokenSource cts = null;
            bOpenPage.Click += delegate { OpenComicWebsite(info.HtmlUrl); };
            bCancel.Click += delegate
            {
                if (cts != null) cts.Cancel();
                dlg.Close();
            };
            dlg.Closed += delegate { if (cts != null) cts.Cancel(); };

            bUpdate.Click += async delegate
            {
                bUpdate.IsEnabled = false;
                bOpenPage.IsEnabled = false;
                bCancel.Content = "取消下载";
                bar.Visibility = Visibility.Visible;
                progressText.Visibility = Visibility.Visible;
                progressText.Text = "正在连接下载服务器…";
                cts = new CancellationTokenSource();
                try
                {
                    await DownloadAndApplyWindowsUpdateAsync(info, cts.Token, delegate (double pct, string msg)
                    {
                        Dispatcher.InvokeAsync(delegate ()
                        {
                            bar.IsIndeterminate = pct < 0;
                            if (pct >= 0) bar.Value = Math.Max(0, Math.Min(100, pct));
                            progressText.Text = msg;
                        });
                    });
                }
                catch (OperationCanceledException)
                {
                    if (dlg.IsVisible)
                    {
                        progressText.Text = "已取消下载";
                        bUpdate.IsEnabled = true;
                        bOpenPage.IsEnabled = true;
                        bCancel.Content = "关闭";
                    }
                }
                catch (Exception ex)
                {
                    if (dlg.IsVisible)
                    {
                        progressText.Text = "更新失败：" + ex.Message.Split('\n')[0];
                        bUpdate.IsEnabled = true;
                        bOpenPage.IsEnabled = true;
                        bCancel.Content = "关闭";
                    }
                }
            };

            dlg.ShowDialog();
        }

        private async Task DownloadAndApplyWindowsUpdateAsync(AppReleaseInfo info, CancellationToken token, Action<double, string> onProgress)
        {
            Uri uri;
            if (!Uri.TryCreate(info.DownloadUrl, UriKind.Absolute, out uri) || uri.Scheme != "https")
                throw new InvalidOperationException("无效的更新下载地址");

            string tempRoot = Path.Combine(Path.GetTempPath(), "KomaComic_update_" + Guid.NewGuid().ToString("N").Substring(0, 8));
            Directory.CreateDirectory(tempRoot);
            string zipPath = Path.Combine(tempRoot, "update.zip");
            string stageDir = Path.Combine(tempRoot, "stage");

            await Task.Run(delegate ()
            {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(uri);
                req.Method = "GET";
                req.UserAgent = "KomaComic-Windows/" + Program.AppVersion;
                req.Timeout = 30000;
                req.ReadWriteTimeout = 60000;
                using (token.Register(delegate () { try { req.Abort(); } catch { } }))
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                using (Stream input = resp.GetResponseStream())
                using (FileStream output = File.Create(zipPath))
                {
                    long totalBytes = resp.ContentLength > 0 ? resp.ContentLength : info.AssetSize;
                    byte[] buffer = new byte[32768];
                    long downloaded = 0;
                    int read;
                    long lastTick = 0;
                    while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        token.ThrowIfCancellationRequested();
                        output.Write(buffer, 0, read);
                        downloaded += read;
                        long now = Environment.TickCount;
                        if (now - lastTick >= 80)
                        {
                            lastTick = now;
                            if (totalBytes > 0)
                            {
                                double pct = downloaded * 100.0 / totalBytes;
                                onProgress(pct, string.Format("正在下载更新包… {0:F1}% ({1:F2} / {2:F2} MB)",
                                    pct, downloaded / (1024.0 * 1024.0), totalBytes / (1024.0 * 1024.0)));
                            }
                            else
                            {
                                onProgress(-1, string.Format("正在下载更新包… {0:F2} MB", downloaded / (1024.0 * 1024.0)));
                            }
                        }
                    }
                }

                token.ThrowIfCancellationRequested();
                onProgress(100, "正在校验并解压更新包…");
                Directory.CreateDirectory(stageDir);
                ZipFile.ExtractToDirectory(zipPath, stageDir);

                string stagedExe = Path.Combine(stageDir, "KomaComic.exe");
                if (!File.Exists(stagedExe))
                    throw new InvalidOperationException("更新包缺少 KomaComic.exe，已中止更新");
            }, token);

            onProgress(100, "正在准备重启并应用更新…");
            _services.SaveState();

            string installDir = _services.BaseDir.TrimEnd('\\', '/');
            int currentPid = Process.GetCurrentProcess().Id;
            string scriptPath = Path.Combine(tempRoot, "apply_update.ps1");
            string escStage = stageDir.Replace("'", "''");
            string escInstall = installDir.Replace("'", "''");
            string escTemp = tempRoot.Replace("'", "''");
            string psScript =
                "$pidToWait = " + currentPid + "\r\n" +
                "$stage = '" + escStage + "'\r\n" +
                "$dst = '" + escInstall + "'\r\n" +
                "$dstExe = Join-Path $dst 'KomaComic.exe'\r\n" +
                "for ($i = 0; $i -lt 30; $i++) {\r\n" +
                "  if (-not (Get-Process -Id $pidToWait -ErrorAction SilentlyContinue)) { break }\r\n" +
                "  Start-Sleep -Milliseconds 250\r\n" +
                "}\r\n" +
                "# Stop any lingering KomaComic instances running from the same install directory so files can be replaced\r\n" +
                "Get-Process -Name KomaComic -ErrorAction SilentlyContinue | ForEach-Object {\r\n" +
                "  try {\r\n" +
                "    if ($_.Path -and [string]::Equals($_.Path, $dstExe, [System.StringComparison]::OrdinalIgnoreCase)) {\r\n" +
                "      Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue\r\n" +
                "    }\r\n" +
                "  } catch {}\r\n" +
                "}\r\n" +
                "Start-Sleep -Milliseconds 400\r\n" +
                "$copied = $false\r\n" +
                "for ($attempt = 0; $attempt -lt 15; $attempt++) {\r\n" +
                "  try {\r\n" +
                "    Get-ChildItem -Path $stage -File -Force | ForEach-Object {\r\n" +
                "      Copy-Item -Path $_.FullName -Destination (Join-Path $dst $_.Name) -Force -ErrorAction Stop\r\n" +
                "    }\r\n" +
                "    $stageAssets = Join-Path $stage 'assets'\r\n" +
                "    if (Test-Path $stageAssets) {\r\n" +
                "      $dstAssets = Join-Path $dst 'assets'\r\n" +
                "      if (-not (Test-Path $dstAssets)) { New-Item -ItemType Directory -Path $dstAssets -Force | Out-Null }\r\n" +
                "      Copy-Item -Path (Join-Path $stageAssets '*') -Destination $dstAssets -Recurse -Force -ErrorAction Stop\r\n" +
                "    }\r\n" +
                "    $copied = $true\r\n" +
                "    break\r\n" +
                "  } catch {\r\n" +
                "    Start-Sleep -Milliseconds 350\r\n" +
                "  }\r\n" +
                "}\r\n" +
                "Start-Process -FilePath $dstExe -WorkingDirectory $dst\r\n" +
                "Start-Sleep -Seconds 2\r\n" +
                "Remove-Item -Path '" + escTemp + "' -Recurse -Force -ErrorAction SilentlyContinue\r\n";
            File.WriteAllText(scriptPath, psScript, new UTF8Encoding(true));

            ProcessStartInfo psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + scriptPath + "\"",
                UseShellExecute = false,
                CreateNoWindow = true
            };
            Process.Start(psi);
            try { StopReadingClock(); _jsRunner.Dispose(); _services.Dispose(); } catch { }
            Environment.Exit(0);
        }

        private void ApplyLibraryMaterial()
        {
            UiTheme.ApplyTheme(Application.Current, _services.ThemeMode, _services.GlassMaterial);
            IntPtr hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd != IntPtr.Zero) NativeMethods.ApplyDarkTitleBar(hwnd, UiTheme.IsDarkActive);
            bool hasCustomWall = _services.WallpaperEnabled && File.Exists(_services.WallpaperPath);
            if (_ambientOrbLayer != null)
            {
                _ambientOrbLayer.Visibility = (_services.GlassMaterial && !hasCustomWall) ? Visibility.Visible : Visibility.Collapsed;
                _ambientOrbLayer.Opacity = UiTheme.IsDarkActive ? 0.85 : 0.55;
            }
            if (_homeAmbientOrbLayer != null)
            {
                _homeAmbientOrbLayer.Visibility = (_services.GlassMaterial && !hasCustomWall) ? Visibility.Visible : Visibility.Collapsed;
                _homeAmbientOrbLayer.Opacity = UiTheme.IsDarkActive ? 0.85 : 0.55;
            }
            if (_wallpaper != null)
            {
                _wallpaper.Source = null;
                _wallpaper.Effect = null;
                if (_homeWallpaper != null) { _homeWallpaper.Source = null; _homeWallpaper.Effect = null; }
                if (hasCustomWall)
                {
                    try
                    {
                        BitmapImage source = new BitmapImage();
                        source.BeginInit();
                        source.CacheOption = BitmapCacheOption.OnLoad;
                        source.UriSource = new Uri(_services.WallpaperPath);
                        source.CreateOptions = BitmapCreateOptions.IgnoreImageCache;
                        source.EndInit();
                        source.Freeze();
                        double op = UiTheme.IsDarkActive ? 0.34 : 0.42;
                        _wallpaper.Source = source;
                        _wallpaper.Opacity = op;
                        // Keep gentle softness so underlying wallpaper details still refract visibly through Liquid Glass rims
                        if (_services.GlassMaterial) _wallpaper.Effect = new System.Windows.Media.Effects.BlurEffect { Radius = 10 };
                        if (_homeWallpaper != null)
                        {
                            _homeWallpaper.Source = source;
                            _homeWallpaper.Opacity = op;
                            if (_services.GlassMaterial) _homeWallpaper.Effect = new System.Windows.Media.Effects.BlurEffect { Radius = 10 };
                        }
                    }
                    catch (Exception ex) { ShowToast("无法读取壁纸：" + ex.Message); }
                }
            }
            LiquidGlassRegistry.RefreshAll(UiTheme.IsDarkActive, _services.GlassMaterial);
        }

        private void ChooseWallpaper()
        {
            Microsoft.Win32.OpenFileDialog dialog = new Microsoft.Win32.OpenFileDialog
            {
                Filter = "图片|*.jpg;*.jpeg;*.png;*.bmp;*.gif;*.tiff|所有文件|*.*"
            };
            if (dialog.ShowDialog(this) != true) return;
            string pending = _services.WallpaperPath + ".tmp";
            try
            {
                BitmapImage picture = new BitmapImage();
                picture.BeginInit();
                picture.UriSource = new Uri(dialog.FileName);
                picture.CacheOption = BitmapCacheOption.OnLoad;
                picture.DecodePixelWidth = 2560;
                picture.EndInit();
                JpegBitmapEncoder encoder = new JpegBitmapEncoder { QualityLevel = 90 };
                encoder.Frames.Add(BitmapFrame.Create(picture));
                using (FileStream stream = File.Create(pending)) encoder.Save(stream);
                if (File.Exists(_services.WallpaperPath)) File.Replace(pending, _services.WallpaperPath, null);
                else File.Move(pending, _services.WallpaperPath);
                _services.WallpaperEnabled = true;
                _services.SaveState();
                ApplyLibraryMaterial();
            }
            catch (Exception ex) { ShowToast("无法保存壁纸：" + ex.Message); }
            finally { if (File.Exists(pending)) File.Delete(pending); }
        }

        private void OpenTranslationSettings()
        {
            Window dialog = CreateStyledDialog("漫画翻译", 550, 620);
            StackPanel fields = new StackPanel { Margin = new Thickness(22) };
            Action<Panel, string, Control> addField = delegate (Panel panel, string label, Control field)
            {
                panel.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 11, 0, 5) });
                panel.Children.Add(field);
            };
            TranslationModel saved = _services.TranslationModel;
            StackPanel serverFields = new StackPanel();
            TextBox endpoint = MakeStyledTextBox();
            endpoint.Text = _services.TranslationServer;
            addField(serverFields, "漫画翻译服务地址", endpoint);
            string newSession = null;
            string sessionServer = "";
            TextBlock loginStatus = new TextBlock
            {
                Text = string.IsNullOrEmpty(_services.TranslationSession) ? "自定义模型需登录漫画翻译服务" : "已保存翻译服务会话",
                Margin = new Thickness(0, 7, 0, 5)
            };
            serverFields.Children.Add(loginStatus);
            Button login = AddBrowseButton(serverFields, "登录翻译服务", delegate
            {
                string server = endpoint.Text.Trim();
                try { MangaTranslation.Endpoint(server); }
                catch (ArgumentException) { loginStatus.Text = "请输入有效的翻译服务地址"; return; }
                Window auth = CreateStyledDialog("登录翻译服务", 395, 275);
                auth.Owner = dialog;
                StackPanel authFields = new StackPanel { Margin = new Thickness(18) };
                TextBox user = MakeStyledTextBox();
                PasswordBox password = new PasswordBox { MinHeight = 33 };
                addField(authFields, "翻译服务用户名", user);
                addField(authFields, "密码", password);
                TextBlock authStatus = new TextBlock { Margin = new Thickness(0, 6, 0, 6) };
                authFields.Children.Add(authStatus);
                WrapPanel authActions = new WrapPanel();
                Button loginButton = UiTheme.MakeButton("登录", true, false);
                loginButton.Click += async delegate
                {
                    if (string.IsNullOrWhiteSpace(user.Text) || password.Password.Length == 0)
                    {
                        authStatus.Text = "请填写用户名和密码";
                        return;
                    }
                    loginButton.IsEnabled = false;
                    try
                    {
                        string result = await MangaTranslation.LoginAsync(server, user.Text.Trim(), password.Password);
                        if (!auth.IsVisible) return;
                        newSession = result;
                        sessionServer = server;
                        loginStatus.Text = "登录成功，保存后在阅读器使用";
                        auth.Close();
                    }
                    catch
                    {
                        authStatus.Text = "登录失败，请检查服务地址和账户";
                        loginButton.IsEnabled = true;
                    }
                };
                authActions.Children.Add(loginButton);
                AddBrowseButton(authActions, "取消", auth.Close);
                authFields.Children.Add(authActions);
                auth.Content = authFields;
                auth.ShowDialog();
            });
            login.HorizontalAlignment = HorizontalAlignment.Left;
            var modes = new[] { TranslationMode.Direct, TranslationMode.Server, TranslationMode.Text, TranslationMode.Vision };
            ComboBox mode = new ComboBox { ItemsSource = new[] { "API 直连", "服务端默认", "自定义文本模型", "自定义视觉模型" },
                SelectedIndex = Math.Max(0, Array.IndexOf(modes, saved.Mode)), MinHeight = 34 };
            addField(fields, "模型来源", mode);
            fields.Children.Add(serverFields);
            StackPanel custom = new StackPanel();
            TextBox apiBase = MakeStyledTextBox();
            apiBase.Text = saved.ApiBase ?? "";
            addField(custom, "模型 API 地址（兼容接口 /v1）", apiBase);
            TextBox model = MakeStyledTextBox();
            model.Text = saved.ModelId ?? "";
            addField(custom, "模型 ID", model);
            Button getModels = UiTheme.MakeButton("获取模型列表", false, false);
            getModels.HorizontalAlignment = HorizontalAlignment.Left;
            custom.Children.Add(getModels);
            PasswordBox key = new PasswordBox { MinHeight = 33 };
            addField(custom, string.IsNullOrEmpty(saved.ProtectedKey) ? "模型 API 密钥" : "模型 API 密钥（已保存，地址不变时留空保留）", key);
            CheckBox clear = new CheckBox { Content = "清除已保存的密钥", Margin = new Thickness(0, 8, 0, 0),
                Visibility = string.IsNullOrEmpty(saved.ProtectedKey) ? Visibility.Collapsed : Visibility.Visible };
            clear.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            custom.Children.Add(clear);
            var modelHelp = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 5) };
            custom.Children.Add(modelHelp);
            var directOptions = new StackPanel();
            var sendImage = new CheckBox { Content = "发送漫画页图片", Margin = new Thickness(0, 8, 0, 4) };
            sendImage.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            sendImage.IsChecked = saved.SendImage;
            directOptions.Children.Add(sendImage);
            var reasoning = new ComboBox
            {
                ItemsSource = new[] { "服务商默认", "关闭", "低", "中", "高" },
                SelectedIndex = Math.Max(0, Array.IndexOf(TranslationModel.ReasoningLevels, saved.ReasoningEffort ?? "")),
                MinHeight = 34
            };
            addField(directOptions, "模型思考强度", reasoning);
            Button usage = AddBrowseButton(directOptions, "查看 Token 用量", OpenTranslationUsage);
            usage.HorizontalAlignment = HorizontalAlignment.Left;
            custom.Children.Add(directOptions);
            fields.Children.Add(custom);
            Action showMode = delegate
            {
                var selected = modes[mode.SelectedIndex];
                custom.Visibility = selected == TranslationMode.Server ? Visibility.Collapsed : Visibility.Visible;
                serverFields.Visibility = selected == TranslationMode.Direct ? Visibility.Collapsed : Visibility.Visible;
                directOptions.Visibility = selected == TranslationMode.Direct ? Visibility.Visible : Visibility.Collapsed;
                modelHelp.Text = selected == TranslationMode.Direct
                    ? (sendImage.IsChecked == true ? "漫画页直接发送到支持图片输入的模型，译文在本机排版。" : "阅读时手动输入原文，API 只接收文字。")
                    : "自定义模式需要支持用户模型参数的 manga-translator-ui，模型地址和密钥会发送到翻译服务。";
            };
            mode.SelectionChanged += delegate { showMode(); };
            sendImage.Click += delegate { showMode(); };
            showMode();
            TextBox config = MakeStyledTextBox();
            config.AcceptsReturn = true;
            config.TextWrapping = TextWrapping.Wrap;
            config.Height = 62;
            config.Text = _services.TranslationConfig;
            addField(fields, "高级翻译配置 JSON", config);
            TextBlock status = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 8) };
            fields.Children.Add(status);
            Func<string, TranslationModel> readModel = server =>
            {
                var selected = new TranslationModel { Mode = modes[mode.SelectedIndex],
                    ApiBase = apiBase.Text.Trim(), ModelId = model.Text.Trim(), ProtectedKey = saved.ProtectedKey,
                    ReasoningEffort = TranslationModel.ReasoningLevels[Math.Max(0, reasoning.SelectedIndex)], SendImage = sendImage.IsChecked == true };
                if (selected.UsesCustomModel && !string.IsNullOrWhiteSpace(key.Password))
                    selected.ProtectedKey = TranslationModel.EncryptKey(key.Password);
                else if ((selected.UsesTranslationServer && server != _services.TranslationServer) || clear.IsChecked == true ||
                    selected.ApiBase != (saved.ApiBase ?? "")) selected.ProtectedKey = "";
                return selected;
            };
            WrapPanel actions = new WrapPanel();
            AddBrowseButton(actions, "保存", delegate
            {
                try
                {
                    string server = endpoint.Text.Trim();
                    TranslationModel selected = readModel(server);
                    if (selected.UsesTranslationServer && (server.Length > 0 || selected.Mode != TranslationMode.Server))
                        MangaTranslation.Endpoint(server, selected.Mode);
                    MangaTranslation.ValidateModel(selected);
                    MangaTranslation.ValidateConfig(config.Text);
                    if (newSession != null && sessionServer == server) _services.TranslationSession = TranslationModel.EncryptKey(newSession);
                    else if (server != _services.TranslationServer) _services.TranslationSession = "";
                    _services.TranslationServer = server;
                    _services.TranslationConfig = config.Text;
                    _services.TranslationModel = selected;
                    _services.SaveState();
                    dialog.Close();
                }
                catch (Exception ex) { status.Text = ex.Message; }
            });
            var testButton = UiTheme.MakeButton("测试翻译", false, false);
            actions.Children.Add(testButton);
            var testCancellation = new CancellationTokenSource();
            dialog.Closed += delegate { testCancellation.Cancel(); };
            getModels.Click += async delegate
            {
                string address = apiBase.Text.Trim();
                getModels.IsEnabled = false;
                status.Text = "正在获取模型列表…";
                try
                {
                    string entered = key.Password.Trim();
                    string secret = entered.Length == 0 && clear.IsChecked != true && address == saved.ApiBase ? saved.DecryptKey() : entered;
                    string[] models = await MangaTranslation.ListModelsAsync(address, secret, testCancellation.Token);
                    if (!dialog.IsVisible) return;
                    if (address != apiBase.Text.Trim()) { status.Text = ""; return; }
                    status.Text = models.Length == 0 ? "API 未列出模型，可手动填写模型 ID" : models.Length + " 个可选模型";
                    if (models.Length > 0)
                    {
                        string picked = PickTranslationModel(dialog, models);
                        if (picked != null) model.Text = picked;
                    }
                }
                catch (Exception error)
                {
                    if (dialog.IsVisible) status.Text = "获取模型失败 · " + MangaTranslation.Describe(error);
                }
                finally { getModels.IsEnabled = true; }
            };
            testButton.Click += async delegate
            {
                testButton.IsEnabled = false;
                status.Text = "正在测试翻译…";
                try
                {
                    string server = endpoint.Text.Trim();
                    TranslationModel selected = readModel(server);
                    string session = !selected.UsesTranslationServer ? "" : sessionServer == server && newSession != null
                        ? newSession : server == _services.TranslationServer ? TranslationModel.DecryptSecret(_services.TranslationSession) : "";
                    await MangaTranslation.TestAsync(server, config.Text, selected, session, testCancellation.Token, _services.DataDir);
                    if (dialog.IsVisible) status.Text = selected.AcceptsPageImage ? "测试成功，已生成测试译图" : "测试成功，已收到文字译文";
                }
                catch (Exception error)
                {
                    if (dialog.IsVisible) status.Text = MangaTranslation.Describe(error);
                }
                finally { testButton.IsEnabled = true; }
            };
            AddBrowseButton(actions, "取消", dialog.Close);
            fields.Children.Add(actions);
            dialog.Content = new ScrollViewer { Content = fields, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            dialog.ShowDialog();
            testCancellation.Dispose();
        }

        private string PickTranslationModel(Window owner, string[] models)
        {
            Window picker = CreateStyledDialog("选择模型", 475, 475);
            picker.Owner = owner;
            var content = new DockPanel { Margin = new Thickness(17) };
            TextBox filter = MakeStyledTextBox();
            System.Windows.Automation.AutomationProperties.SetName(filter, "搜索模型 ID");
            DockPanel.SetDock(filter, Dock.Top);
            content.Children.Add(filter);
            var footer = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 8, 0, 0) };
            DockPanel.SetDock(footer, Dock.Bottom);
            content.Children.Add(footer);
            var list = new ListBox { ItemsSource = models, Margin = new Thickness(0, 8, 0, 0) };
            list.SetResourceReference(Control.BackgroundProperty, "Brush.Surface");
            list.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            content.Children.Add(list);
            string selected = null;
            Action accept = delegate { selected = list.SelectedItem as string; if (selected != null) picker.Close(); };
            AddBrowseButton(footer, "选择", accept);
            AddBrowseButton(footer, "取消", picker.Close);
            filter.TextChanged += delegate { list.ItemsSource = models.Where(id => id.IndexOf(filter.Text.Trim(), StringComparison.OrdinalIgnoreCase) >= 0).ToArray(); };
            list.MouseDoubleClick += delegate { accept(); };
            picker.Content = content;
            picker.ShowDialog();
            return selected;
        }

        private void SwitchTheme(string mode)
        {
            _services.ThemeMode = mode;
            ApplyLibraryMaterial();
            IntPtr hwnd = new WindowInteropHelper(this).Handle;
            NativeMethods.ApplyDarkTitleBar(hwnd, UiTheme.IsDarkActive);
            _services.SaveState();
            RebuildHomeChips();
        }

        /* ================= NATIVE WPF IMMERSIVE READER OVERLAY ================= */
        private Grid BuildReaderOverlay()
        {
            Grid overlay = new Grid { Background = new SolidColorBrush(Color.FromRgb(7, 9, 13)) };

            _readerZoomTransform = new ScaleTransform(1.0, 1.0);
            _readerSpreadSlideTransform = new TranslateTransform(0, 0);
            _readerSpreadAnimScale = new ScaleTransform(1.0, 1.0);
            _readerSpreadTransformGroup = new TransformGroup();
            _readerSpreadTransformGroup.Children.Add(_readerZoomTransform);
            _readerSpreadTransformGroup.Children.Add(_readerSpreadAnimScale);
            _readerSpreadTransformGroup.Children.Add(_readerSpreadSlideTransform);

            _readerGhostSlideTransform = new TranslateTransform(0, 0);
            _readerGhostAnimScale = new ScaleTransform(1.0, 1.0);
            _readerGhostTransformGroup = new TransformGroup();
            _readerGhostTransformGroup.Children.Add(_readerGhostAnimScale);
            _readerGhostTransformGroup.Children.Add(_readerGhostSlideTransform);

            _readerViewport = new Grid { Background = new SolidColorBrush(Color.FromRgb(7, 9, 13)), ClipToBounds = true };

            // Outgoing Ghost Layer (for zero-allocation smooth page-turn transitions)
            _readerGhostGrid = new Grid
            {
                HorizontalAlignment = HorizontalAlignment.Stretch,
                VerticalAlignment = VerticalAlignment.Stretch,
                RenderTransformOrigin = new Point(0.5, 0.5),
                RenderTransform = _readerGhostTransformGroup,
                Visibility = Visibility.Collapsed,
                IsHitTestVisible = false
            };
            _readerGhostSingleImg = new Image { Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            RenderOptions.SetBitmapScalingMode(_readerGhostSingleImg, BitmapScalingMode.HighQuality);
            _readerGhostGrid.Children.Add(_readerGhostSingleImg);

            Grid ghostTwoCol = new Grid();
            ghostTwoCol.ColumnDefinitions.Add(new ColumnDefinition());
            ghostTwoCol.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(6) });
            ghostTwoCol.ColumnDefinitions.Add(new ColumnDefinition());
            _readerGhostLeftImg = new Image { Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
            _readerGhostRightImg = new Image { Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Center };
            RenderOptions.SetBitmapScalingMode(_readerGhostLeftImg, BitmapScalingMode.HighQuality);
            RenderOptions.SetBitmapScalingMode(_readerGhostRightImg, BitmapScalingMode.HighQuality);
            Grid.SetColumn(_readerGhostRightImg, 2);
            ghostTwoCol.Children.Add(_readerGhostLeftImg);
            ghostTwoCol.Children.Add(_readerGhostRightImg);
            _readerGhostGrid.Children.Add(ghostTwoCol);
            _readerViewport.Children.Add(_readerGhostGrid);

            // Single / Double Spread Canvas
            _readerSpreadGrid = new Grid
            {
                HorizontalAlignment = HorizontalAlignment.Stretch,
                VerticalAlignment = VerticalAlignment.Stretch,
                RenderTransformOrigin = new Point(0.5, 0.5),
                RenderTransform = _readerSpreadTransformGroup
            };
            _readerSingleImg = new Image { Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            RenderOptions.SetBitmapScalingMode(_readerSingleImg, BitmapScalingMode.HighQuality);
            _readerSpreadGrid.Children.Add(_readerSingleImg);

            Grid twoCol = new Grid();
            twoCol.ColumnDefinitions.Add(new ColumnDefinition());
            _readerSpreadGapCol = new ColumnDefinition { Width = new GridLength(6) };
            twoCol.ColumnDefinitions.Add(_readerSpreadGapCol);
            twoCol.ColumnDefinitions.Add(new ColumnDefinition());
            _readerLeftImg = new Image { Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
            _readerRightImg = new Image { Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Center };
            RenderOptions.SetBitmapScalingMode(_readerLeftImg, BitmapScalingMode.HighQuality);
            RenderOptions.SetBitmapScalingMode(_readerRightImg, BitmapScalingMode.HighQuality);
            Grid.SetColumn(_readerRightImg, 2);
            twoCol.Children.Add(_readerLeftImg);
            twoCol.Children.Add(_readerRightImg);
            _readerSpreadGrid.Children.Add(twoCol);
            _readerViewport.Children.Add(_readerSpreadGrid);

            // Page-turn curl/cover shadow overlay
            _readerAnimShadow = new Rectangle
            {
                Width = 48,
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Stretch,
                IsHitTestVisible = false,
                Visibility = Visibility.Collapsed
            };
            LinearGradientBrush shadowGrad = new LinearGradientBrush { StartPoint = new Point(0, 0.5), EndPoint = new Point(1, 0.5) };
            shadowGrad.GradientStops.Add(new GradientStop(Color.FromArgb(0, 0, 0, 0), 0.0));
            shadowGrad.GradientStops.Add(new GradientStop(Color.FromArgb(150, 0, 0, 0), 0.5));
            shadowGrad.GradientStops.Add(new GradientStop(Color.FromArgb(0, 0, 0, 0), 1.0));
            shadowGrad.Freeze();
            _readerAnimShadow.Fill = shadowGrad;
            _readerViewport.Children.Add(_readerAnimShadow);

            // Vertical Scroll Mode Canvas
            _readerScrollViewer = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Visibility = Visibility.Collapsed };
            _readerScrollStack = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, RenderTransformOrigin = new Point(0.5, 0), RenderTransform = _readerZoomTransform };
            _readerScrollViewer.Content = _readerScrollStack;
            _readerScrollViewer.ScrollChanged += delegate { QueueVisibleTranslation(); };
            _readerViewport.Children.Add(_readerScrollViewer);

            // Brightness Dimming Overlay (non-blocking)
            _readerBrightnessScrim = new Rectangle { Fill = Brushes.Black, Opacity = 0, IsHitTestVisible = false };
            _readerViewport.Children.Add(_readerBrightnessScrim);

            // Click zones for turn/chrome + double-click zoom + wheel page turn & Ctrl+wheel zoom
            _readerViewport.MouseLeftButtonUp += delegate (object s, MouseButtonEventArgs e)
            {
                ReaderPrefModel prof = GetActiveReaderProfile();
                if (e.ClickCount >= 2 && prof.DoubleTapZoom)
                {
                    _zoomScale = _zoomScale > 1.05 ? 1.0 : 1.75;
                    _readerZoomTransform.ScaleX = _zoomScale;
                    _readerZoomTransform.ScaleY = _zoomScale;
                    return;
                }
                if (_readerDrawer.Visibility == Visibility.Visible)
                {
                    SetReaderDrawerVisible(false);
                    return;
                }
                Point pt = e.GetPosition(_readerViewport);
                double relX = pt.X / Math.Max(1.0, _readerViewport.ActualWidth);
                if (relX > 0.30 && relX < 0.70)
                {
                    _chromeVisible = !_chromeVisible;
                    SyncReaderChromeVisibility(true);
                }
                else if (prof.Mode != "scroll")
                {
                    bool goNext = prof.Mode == "rtl" ? (relX <= 0.30) : (relX >= 0.70);
                    TurnReaderPage(goNext ? 1 : -1);
                }
            };
            _readerViewport.PreviewMouseWheel += delegate (object s, MouseWheelEventArgs e)
            {
                if ((Keyboard.Modifiers & ModifierKeys.Control) == ModifierKeys.Control)
                {
                    e.Handled = true;
                    _zoomScale = Math.Max(0.6, Math.Min(3.0, _zoomScale + (e.Delta > 0 ? 0.15 : -0.15)));
                    _readerZoomTransform.ScaleX = _zoomScale;
                    _readerZoomTransform.ScaleY = _zoomScale;
                    return;
                }
                ReaderPrefModel prof = GetActiveReaderProfile();
                if (prof.Mode != "scroll" && prof.WheelPageTurn && _zoomScale <= 1.05)
                {
                    e.Handled = true;
                    long now = Environment.TickCount;
                    if (Math.Abs(now - _lastWheelTurnTick) < 135) return;
                    _lastWheelTurnTick = now;
                    TurnReaderPage(e.Delta < 0 ? 1 : -1);
                }
            };

            overlay.Children.Add(_readerViewport);
            _readerTranslationStatus = new TextBlock
            {
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, 68, 18, 0), Padding = new Thickness(12, 6, 12, 6),
                Background = new SolidColorBrush(Color.FromArgb(220, 18, 26, 42)), Foreground = Brushes.White,
                Visibility = Visibility.Collapsed, Cursor = Cursors.Hand, TextWrapping = TextWrapping.Wrap
            };
            _readerTranslationStatus.MouseLeftButtonUp += delegate { _readerTranslationEpoch++; QueueVisibleTranslation(); };
            overlay.Children.Add(_readerTranslationStatus);
            _readerTranslationBall = MakeTranslationBall();
            overlay.Children.Add(_readerTranslationBall);

            // Loading Box
            StackPanel loading = new StackPanel();
            _readerLoadMessage = new TextBlock { Text = "正在加载章节…", Foreground = Brushes.White, FontSize = 13.5, TextWrapping = TextWrapping.Wrap, MaxWidth = 420 };
            _readerRetryButton = UiTheme.MakeButton("重试", true, false);
            _readerRetryButton.HorizontalAlignment = HorizontalAlignment.Center;
            _readerRetryButton.Margin = new Thickness(0, 8, 0, 0);
            _readerRetryButton.Click += delegate { if (_readerRetry != null) _readerRetry(); };
            loading.Children.Add(_readerLoadMessage);
            loading.Children.Add(_readerRetryButton);
            _readerLoadingBox = new LiquidGlassSurface(_readerViewport, LiquidGlassPreset.SubtlePill, 16, true, false)
            {
                ContentPadding = new Thickness(20, 13, 20, 13),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Visibility = Visibility.Collapsed,
                Child = loading
            };
            overlay.Children.Add(_readerLoadingBox);

            // Corner Page Badge
            _readerPageBadgeText = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(225, 234, 248)), FontSize = 12 };
            _readerPageBadge = new LiquidGlassSurface(_readerViewport, LiquidGlassPreset.SubtlePill, 14, true, false)
            {
                ContentPadding = new Thickness(12, 4, 12, 4),
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(0, 0, 18, 14),
                IsHitTestVisible = false,
                Child = _readerPageBadgeText
            };
            overlay.Children.Add(_readerPageBadge);

            // Top Chrome Bar (slides down smoothly)
            _readerTopTranslate = new TranslateTransform(0, -68);
            _readerTopBar = new Border
            {
                Height = 64,
                VerticalAlignment = VerticalAlignment.Top,
                Padding = new Thickness(18, 0, 18, 0),
                Background = new LinearGradientBrush(Color.FromArgb(232, 9, 13, 22), Color.FromArgb(0, 9, 13, 22), 90),
                RenderTransform = _readerTopTranslate,
                Opacity = 0,
                Visibility = Visibility.Collapsed
            };
            DockPanel topDock = new DockPanel();
            StackPanel topRight = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            DockPanel.SetDock(topRight, Dock.Right);

            _btnSpreadPairShift = MakeReaderTopPillBtn("跨页偏移", "切换双页合并的奇偶页配对（首页单页/默认双页）");
            _btnSpreadPairShift.Click += delegate (object s, RoutedEventArgs e)
            {
                ToggleSpreadPairShift();
            };
            Button btnFull = MakeReaderTopPillBtn("⛶", "切换全屏 (F11)");
            btnFull.Width = 38; btnFull.Height = 36; btnFull.Padding = new Thickness(0); btnFull.Margin = new Thickness(0);
            btnFull.Click += delegate (object s, RoutedEventArgs e) { ToggleFullscreen(); };
            topRight.Children.Add(_btnSpreadPairShift);
            topRight.Children.Add(btnFull);
            topDock.Children.Add(topRight);

            StackPanel topLeft = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            Button btnBack = new Button
            {
                Content = "←",
                Width = 38,
                Height = 38,
                Foreground = Brushes.White,
                Background = new SolidColorBrush(Color.FromArgb(186, 22, 30, 46)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(72, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand,
                FocusVisualStyle = null,
                FontSize = 17,
                Template = UiTheme.CreateRoundedButtonTemplate(19, null)
            };
            btnBack.Click += delegate (object s, RoutedEventArgs e) { CloseReader(); };
            StackPanel titleStack = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            _readerTitleText = new TextBlock { Foreground = Brushes.White, FontSize = 14.5, FontWeight = FontWeights.Bold };
            _readerChapterText = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(176, 190, 212)), FontSize = 12 };
            titleStack.Children.Add(_readerTitleText);
            titleStack.Children.Add(_readerChapterText);
            topLeft.Children.Add(btnBack);
            topLeft.Children.Add(titleStack);
            topDock.Children.Add(topLeft);
            _readerTopBar.Child = topDock;
            overlay.Children.Add(_readerTopBar);

            // Floating Liquid Glass Capsule Bottom Menu (slides smoothly from bottom)
            _readerBottomTranslate = new TranslateTransform(0, 96);
            _readerBottomCapsule = new LiquidGlassSurface(_readerViewport, LiquidGlassPreset.ReaderCapsule, 28, true, false)
            {
                Width = 600,
                VerticalAlignment = VerticalAlignment.Bottom,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 0, 0, 22),
                RenderTransform = _readerBottomTranslate,
                Opacity = 0,
                Visibility = Visibility.Collapsed
            };
            Grid hudLayers = new Grid();

            StackPanel capStack = new StackPanel { Margin = new Thickness(20, 12, 20, 10) };
            Grid progGrid = new Grid();
            progGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            progGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            progGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            progGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            Button btnPrevCh = MakeReaderCapsuleMiniBtn("⏮", "上一话");
            btnPrevCh.Click += delegate (object s, RoutedEventArgs e)
            {
                if (_readerChapterIdx > 0) LoadOnlineChapterAsync(_readerChapterIdx - 1, 0);
                else ShowToast("已是第一话");
            };
            _readerSeekSlider = new Slider
            {
                Minimum = 1, Maximum = 1, Value = 1,
                IsSnapToTickEnabled = true, TickFrequency = 1,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(12, 0, 12, 0),
                FocusVisualStyle = null,
                Template = UiTheme.CreateGlassSliderTemplate()
            };
            _readerSeekSlider.ValueChanged += delegate (object s, RoutedPropertyChangedEventArgs<double> e)
            {
                if (_readerPages.Count == 0) return;
                int target = Math.Max(0, Math.Min(_readerPages.Count - 1, (int)_readerSeekSlider.Value - 1));
                if (target != _readerPageIdx)
                {
                    _readerPageIdx = target;
                    _splitHalf = 0;
                    _pendingAnimDir = 0;
                    RenderCurrentReaderPagesAsync();
                }
            };
            _readerSeekText = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(215, 224, 238)), FontSize = 12.5, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 10, 0), Text = "1/1" };
            Button btnNextCh = MakeReaderCapsuleMiniBtn("⏭", "下一话");
            btnNextCh.Click += delegate (object s, RoutedEventArgs e)
            {
                if (_readerChapterIdx + 1 < _readerChapters.Count) LoadOnlineChapterAsync(_readerChapterIdx + 1, 0);
                else ShowToast("已是最新一话");
            };
            Grid.SetColumn(_readerSeekSlider, 1);
            Grid.SetColumn(_readerSeekText, 2);
            Grid.SetColumn(btnNextCh, 3);
            progGrid.Children.Add(btnPrevCh);
            progGrid.Children.Add(_readerSeekSlider);
            progGrid.Children.Add(_readerSeekText);
            progGrid.Children.Add(btnNextCh);
            capStack.Children.Add(progGrid);

            Grid actGrid = new Grid { Margin = new Thickness(0, 8, 0, 0) };
            actGrid.ColumnDefinitions.Add(new ColumnDefinition());
            actGrid.ColumnDefinitions.Add(new ColumnDefinition());
            actGrid.ColumnDefinitions.Add(new ColumnDefinition());
            actGrid.ColumnDefinitions.Add(new ColumnDefinition());

            Button bSet = MakeReaderCapsuleMiniBtn("⚙ 阅读设置", "阅读设置");
            _readerFavButton = MakeReaderCapsuleMiniBtn("☆ 收藏", "添加或取消收藏");
            Button bDir = MakeReaderCapsuleMiniBtn("☰ 目录", "章节目录");
            _readerTranslationButton = MakeReaderCapsuleMiniBtn("译 翻译", "切换原文与译文");
            _readerTranslationButton.Click += delegate { ToggleReaderTranslation(); };
            bSet.Click += delegate (object s, RoutedEventArgs e)
            {
                SetReaderDrawerVisible(_readerDrawer.Visibility != Visibility.Visible);
            };
            _readerFavButton.Click += delegate (object s, RoutedEventArgs e) { if (_readerComic != null) ToggleFavorite(_readerComic); };
            bDir.Click += delegate (object s, RoutedEventArgs e) { OpenReaderChaptersDialog(); };
            Grid.SetColumn(_readerFavButton, 1);
            Grid.SetColumn(bDir, 2);
            Grid.SetColumn(_readerTranslationButton, 3);
            actGrid.Children.Add(bSet);
            actGrid.Children.Add(_readerFavButton);
            actGrid.Children.Add(bDir);
            actGrid.Children.Add(_readerTranslationButton);
            capStack.Children.Add(actGrid);

            hudLayers.Children.Add(capStack);
            _readerBottomCapsule.Child = hudLayers;
            overlay.Children.Add(_readerBottomCapsule);

            // Frosted Liquid Glass Right Settings Drawer
            _readerDrawerTranslate = new TranslateTransform(360, 0);
            _readerDrawer = BuildReaderSettingsDrawer();
            _readerDrawer.RenderTransform = _readerDrawerTranslate;
            _readerDrawer.Opacity = 0;
            _readerDrawer.Visibility = Visibility.Collapsed;
            overlay.Children.Add(_readerDrawer);

            return overlay;
        }

        private static Button MakeReaderTopPillBtn(string text, string tip)
        {
            return new Button
            {
                Content = text,
                ToolTip = tip,
                Foreground = new SolidColorBrush(Color.FromRgb(232, 241, 255)),
                Background = new SolidColorBrush(Color.FromArgb(190, 22, 30, 46)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(68, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(12, 6, 12, 6),
                Margin = new Thickness(0, 0, 8, 0),
                FontSize = 12.5,
                FontWeight = FontWeights.SemiBold,
                Cursor = Cursors.Hand,
                FocusVisualStyle = null,
                Template = UiTheme.CreateRoundedButtonTemplate(16, null)
            };
        }

        private void SyncReaderFavoriteButton()
        {
            if (_readerFavButton == null) return;
            bool fav = IsFavorite(_readerComic);
            if (fav)
            {
                _readerFavButton.Content = "★ 已收藏";
                _readerFavButton.Foreground = new SolidColorBrush(Color.FromRgb(255, 212, 102));
                _readerFavButton.Background = new SolidColorBrush(Color.FromArgb(58, 245, 158, 11));
                _readerFavButton.BorderBrush = new SolidColorBrush(Color.FromArgb(165, 251, 191, 36));
                _readerFavButton.BorderThickness = new Thickness(1);
            }
            else
            {
                _readerFavButton.Content = "☆ 收藏";
                _readerFavButton.Foreground = new SolidColorBrush(Color.FromRgb(220, 228, 242));
                _readerFavButton.Background = Brushes.Transparent;
                _readerFavButton.BorderBrush = Brushes.Transparent;
                _readerFavButton.BorderThickness = new Thickness(0);
            }
        }

        private LiquidGlassSurface BuildReaderSettingsDrawer()
        {
            LiquidGlassSurface drawer = new LiquidGlassSurface(_readerViewport, LiquidGlassPreset.ReaderDrawer, 22, true, false)
            {
                Width = 356,
                HorizontalAlignment = HorizontalAlignment.Right,
                Margin = new Thickness(0, 14, 14, 14)
            };

            DockPanel dp = new DockPanel();
            Border dHeader = new Border { Padding = new Thickness(18, 15, 18, 15), BorderThickness = new Thickness(0, 0, 0, 1), BorderBrush = new SolidColorBrush(Color.FromArgb(28, 255, 255, 255)) };
            DockPanel.SetDock(dHeader, Dock.Top);
            DockPanel hd = new DockPanel();
            Button bCloseD = new Button { Content = "✕", Foreground = Brushes.White, Background = Brushes.Transparent, BorderThickness = new Thickness(0), FontSize = 15, Cursor = Cursors.Hand, FocusVisualStyle = null };
            DockPanel.SetDock(bCloseD, Dock.Right);
            bCloseD.Click += delegate (object s, RoutedEventArgs e) { SetReaderDrawerVisible(false); };
            hd.Children.Add(bCloseD);
            hd.Children.Add(new TextBlock { Text = "阅读设置", Foreground = Brushes.White, FontSize = 16, FontWeight = FontWeights.Bold });
            dHeader.Child = hd;
            dp.Children.Add(dHeader);

            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(14, 12, 14, 20) };
            StackPanel stack = new StackPanel();

            // Card 1: Brightness
            Border c1 = MakeDrawerCard();
            StackPanel c1s = new StackPanel();
            DockPanel brTop = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
            _drawerBrightnessText = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(180, 192, 212)), FontSize = 13 };
            DockPanel.SetDock(_drawerBrightnessText, Dock.Right);
            brTop.Children.Add(_drawerBrightnessText);
            brTop.Children.Add(new TextBlock { Text = "亮度", Foreground = new SolidColorBrush(Color.FromRgb(180, 192, 212)), FontSize = 13 });
            c1s.Children.Add(brTop);
            _drawerBrightnessSlider = new Slider { Minimum = 35, Maximum = 115, Value = 100, FocusVisualStyle = null, Template = UiTheme.CreateGlassSliderTemplate() };
            _drawerBrightnessSlider.ValueChanged += delegate (object s, RoutedPropertyChangedEventArgs<double> e)
            {
                if (_readerOpen) UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Brightness = (int)_drawerBrightnessSlider.Value; });
            };
            c1s.Children.Add(_drawerBrightnessSlider);
            c1.Child = c1s;
            stack.Children.Add(c1);

            // Card 2: Reading Mode
            Border c2 = MakeDrawerCard();
            StackPanel c2s = new StackPanel();
            c2s.Children.Add(new TextBlock { Text = "阅读模式", Foreground = new SolidColorBrush(Color.FromRgb(180, 192, 212)), FontSize = 13, Margin = new Thickness(0, 0, 0, 10) });
            Grid mGrid = new Grid { Margin = new Thickness(0, 0, 0, 10) };
            mGrid.ColumnDefinitions.Add(new ColumnDefinition());
            mGrid.ColumnDefinitions.Add(new ColumnDefinition());
            mGrid.ColumnDefinitions.Add(new ColumnDefinition());
            _btnModeLtr = MakeDrawerOptBtn("普通模式");
            _btnModeRtl = MakeDrawerOptBtn("日漫模式");
            _btnModeScroll = MakeDrawerOptBtn("滚动模式");
            Grid.SetColumn(_btnModeRtl, 1);
            Grid.SetColumn(_btnModeScroll, 2);
            _btnModeLtr.Click += delegate (object s, RoutedEventArgs e) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Mode = "ltr"; }); };
            _btnModeRtl.Click += delegate (object s, RoutedEventArgs e) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Mode = "rtl"; }); };
            _btnModeScroll.Click += delegate (object s, RoutedEventArgs e) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Mode = "scroll"; }); };
            mGrid.Children.Add(_btnModeLtr);
            mGrid.Children.Add(_btnModeRtl);
            mGrid.Children.Add(_btnModeScroll);
            c2s.Children.Add(mGrid);

            DockPanel globRow = new DockPanel();
            _chkApplyGlobal = UiTheme.MakeToggle(false);
            DockPanel.SetDock(_chkApplyGlobal, Dock.Right);
            globRow.Children.Add(_chkApplyGlobal);
            _chkApplyGlobal.Click += delegate (object s, RoutedEventArgs e) { _services.ReaderDefaults.ApplyGlobal = _chkApplyGlobal.IsChecked == true; _services.SaveState(); };
            globRow.Children.Add(new TextBlock { Text = "阅读模式设置全部生效", Foreground = Brushes.White, FontSize = 12.5, VerticalAlignment = VerticalAlignment.Center });
            c2s.Children.Add(globRow);
            c2.Child = c2s;
            stack.Children.Add(c2);

            // Card 3: Quality & Spread Mode (Single / Double Merge / Double Split)
            Border c3 = MakeDrawerCard();
            StackPanel c3s = new StackPanel();
            c3s.Children.Add(new TextBlock { Text = "画质", Foreground = new SolidColorBrush(Color.FromRgb(180, 192, 212)), FontSize = 13, Margin = new Thickness(0, 0, 0, 8) });
            Grid qGrid = new Grid { Margin = new Thickness(0, 0, 0, 12) };
            qGrid.ColumnDefinitions.Add(new ColumnDefinition());
            qGrid.ColumnDefinitions.Add(new ColumnDefinition());
            qGrid.ColumnDefinitions.Add(new ColumnDefinition());
            _btnQUltra = MakeDrawerOptBtn("超清");
            _btnQHigh = MakeDrawerOptBtn("高清");
            _btnQSmooth = MakeDrawerOptBtn("流畅");
            Grid.SetColumn(_btnQHigh, 1);
            Grid.SetColumn(_btnQSmooth, 2);
            _btnQUltra.Click += delegate (object s, RoutedEventArgs e) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Quality = "ultra"; }); };
            _btnQHigh.Click += delegate (object s, RoutedEventArgs e) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Quality = "high"; }); };
            _btnQSmooth.Click += delegate (object s, RoutedEventArgs e) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Quality = "smooth"; }); };
            qGrid.Children.Add(_btnQUltra);
            qGrid.Children.Add(_btnQHigh);
            qGrid.Children.Add(_btnQSmooth);
            c3s.Children.Add(qGrid);

            c3s.Children.Add(new TextBlock { Text = "单双页排版（合并 / 分割）", Foreground = new SolidColorBrush(Color.FromRgb(180, 192, 212)), FontSize = 13, Margin = new Thickness(0, 0, 0, 8) });
            Grid sGrid = new Grid { Margin = new Thickness(0, 0, 0, 8) };
            sGrid.ColumnDefinitions.Add(new ColumnDefinition());
            sGrid.ColumnDefinitions.Add(new ColumnDefinition());
            sGrid.ColumnDefinitions.Add(new ColumnDefinition());
            _btnSpreadSingle = MakeDrawerOptBtn("单页");
            _btnSpreadDouble = MakeDrawerOptBtn("双页合并");
            _btnSpreadSplit = MakeDrawerOptBtn("双页分割");
            Grid.SetColumn(_btnSpreadDouble, 1);
            Grid.SetColumn(_btnSpreadSplit, 2);
            _btnSpreadSingle.Click += delegate (object s, RoutedEventArgs e) { _splitHalf = 0; UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Spread = "single"; }); };
            _btnSpreadDouble.Click += delegate (object s, RoutedEventArgs e) { _splitHalf = 0; UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Spread = "double"; }); };
            _btnSpreadSplit.Click += delegate (object s, RoutedEventArgs e) { _splitHalf = 0; UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.Spread = "split"; }); };
            sGrid.Children.Add(_btnSpreadSingle);
            sGrid.Children.Add(_btnSpreadDouble);
            sGrid.Children.Add(_btnSpreadSplit);
            c3s.Children.Add(sGrid);

            c3s.Children.Add(new TextBlock { Text = "双页跨页偏移", Foreground = new SolidColorBrush(Color.FromRgb(180, 192, 212)), FontSize = 13, Margin = new Thickness(0, 4, 0, 8) });
            Grid shiftGrid = new Grid { Margin = new Thickness(0, 0, 0, 8) };
            shiftGrid.ColumnDefinitions.Add(new ColumnDefinition());
            shiftGrid.ColumnDefinitions.Add(new ColumnDefinition());
            _btnDrawerSpreadEven = MakeDrawerOptBtn("默认双页起");
            _btnDrawerSpreadOdd = MakeDrawerOptBtn("首页单页起");
            Grid.SetColumn(_btnDrawerSpreadOdd, 1);
            _btnDrawerSpreadEven.Click += delegate { if (_spreadShift != 0) ToggleSpreadPairShift(); };
            _btnDrawerSpreadOdd.Click += delegate { if (_spreadShift != 1) ToggleSpreadPairShift(); };
            shiftGrid.Children.Add(_btnDrawerSpreadEven);
            shiftGrid.Children.Add(_btnDrawerSpreadOdd);
            c3s.Children.Add(shiftGrid);

            _chkSpreadGap = AddDrawerToggle(c3s, "双页合并保留中缝间距", delegate (bool v) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.SpreadGap = v; }); });
            _chkSplitAll = AddDrawerToggle(c3s, "双页分割强制裁切全部页", delegate (bool v) { _splitHalf = 0; UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.SplitAllPages = v; }); });
            c3.Child = c3s;
            stack.Children.Add(c3);

            // Card 4: Page-Turn Animations
            Border cAnim = MakeDrawerCard();
            StackPanel cAnimS = new StackPanel();
            cAnimS.Children.Add(new TextBlock { Text = "翻页动画", Foreground = new SolidColorBrush(Color.FromRgb(180, 192, 212)), FontSize = 13, Margin = new Thickness(0, 0, 0, 8) });
            _animButtons.Clear();
            string[,] animOpts = new string[,]
            {
                { "none", "无动画" },
                { "fade", "柔和淡入" },
                { "slide", "平滑滑动" },
                { "cover", "覆层覆盖" },
                { "curl", "拟真翻书" },
                { "depth", "景深缩放" }
            };
            Grid aGrid = new Grid();
            aGrid.ColumnDefinitions.Add(new ColumnDefinition());
            aGrid.ColumnDefinitions.Add(new ColumnDefinition());
            aGrid.ColumnDefinitions.Add(new ColumnDefinition());
            aGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            aGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            for (int i = 0; i < 6; i++)
            {
                string key = animOpts[i, 0];
                string lbl = animOpts[i, 1];
                Button ab = MakeDrawerOptBtn(lbl);
                ab.Margin = new Thickness(3, 3, 3, 3);
                ab.Click += delegate { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.PageTurnAnim = key; }); };
                Grid.SetRow(ab, i / 3);
                Grid.SetColumn(ab, i % 3);
                aGrid.Children.Add(ab);
                _animButtons[key] = ab;
            }
            cAnimS.Children.Add(aGrid);
            cAnim.Child = cAnimS;
            stack.Children.Add(cAnim);

            // Card 5: Page Prefetch Cache
            Border cPref = MakeDrawerCard();
            StackPanel cPrefS = new StackPanel();
            cPrefS.Children.Add(new TextBlock { Text = "后续页提前预加载缓存", Foreground = new SolidColorBrush(Color.FromRgb(180, 192, 212)), FontSize = 13, Margin = new Thickness(0, 0, 0, 8) });
            _prefetchButtons.Clear();
            int[] prefCounts = new int[] { 0, 2, 4, 6, 8 };
            Grid pGrid = new Grid();
            for (int i = 0; i < prefCounts.Length; i++)
            {
                int count = prefCounts[i];
                pGrid.ColumnDefinitions.Add(new ColumnDefinition());
                Button pb = MakeDrawerOptBtn(count == 0 ? "关闭" : (count + "页"));
                pb.Click += delegate
                {
                    UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.PrefetchPages = count; });
                    if (count > 0) PrefetchNextReaderPages(_readerPageIdx, _readerChapterRequest);
                };
                Grid.SetColumn(pb, i);
                pGrid.Children.Add(pb);
                _prefetchButtons[count] = pb;
            }
            cPrefS.Children.Add(pGrid);
            cPref.Child = cPrefS;
            stack.Children.Add(cPref);

            // Card 6: Toggles
            Border c4 = MakeDrawerCard();
            StackPanel c4s = new StackPanel();
            _chkWheelTurn = AddDrawerToggle(c4s, "鼠标滚轮翻页", delegate (bool v) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.WheelPageTurn = v; }); });
            _chkTrimMargins = AddDrawerToggle(c4s, "去白边", delegate (bool v) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.TrimMargins = v; }); });
            _chkDoubleZoom = AddDrawerToggle(c4s, "允许双击缩放画面", delegate (bool v) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.DoubleTapZoom = v; }); });
            _chkShowComment = AddDrawerToggle(c4s, "展示单话评论页", delegate (bool v) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.ShowCommentPage = v; }); });
            _chkShowPageNum = AddDrawerToggle(c4s, "展示页码", delegate (bool v) { UpdateActiveReaderProfile(delegate (ReaderPrefModel p) { p.ShowPageNumber = v; }); });
            c4.Child = c4s;
            stack.Children.Add(c4);

            var translationCard = MakeDrawerCard();
            var translationPanel = new StackPanel();
            var translationSettings = AddBrowseButton(translationPanel, "翻译设置", OpenReaderTranslationSettings);
            translationSettings.HorizontalAlignment = HorizontalAlignment.Left;
            translationCard.Child = translationPanel;
            stack.Children.Add(translationCard);

            sv.Content = stack;
            dp.Children.Add(sv);
            drawer.Child = dp;
            return drawer;
        }

        private CheckBox AddDrawerToggle(StackPanel parent, string label, Action<bool> onChanged)
        {
            DockPanel dp = new DockPanel { Margin = new Thickness(0, 5, 0, 5) };
            CheckBox chk = UiTheme.MakeToggle(false);
            DockPanel.SetDock(chk, Dock.Right);
            chk.Click += delegate (object s, RoutedEventArgs e) { onChanged(chk.IsChecked == true); };
            dp.Children.Add(chk);
            dp.Children.Add(new TextBlock { Text = label, Foreground = Brushes.White, FontSize = 13, VerticalAlignment = VerticalAlignment.Center });
            parent.Children.Add(dp);
            return chk;
        }

        private static Border MakeDrawerCard()
        {
            return new Border
            {
                CornerRadius = new CornerRadius(16),
                Background = new SolidColorBrush(Color.FromArgb(185, 26, 36, 54)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(32, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(14, 12, 14, 12),
                Margin = new Thickness(0, 0, 0, 10)
            };
        }

        private static Button MakeDrawerOptBtn(string text)
        {
            return new Button
            {
                Content = text,
                Margin = new Thickness(3, 0, 3, 0),
                Padding = new Thickness(6, 7, 6, 7),
                FontSize = 12.5,
                FontWeight = FontWeights.SemiBold,
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand,
                FocusVisualStyle = null,
                Template = UiTheme.CreateRoundedButtonTemplate(10, null)
            };
        }

        private static Button MakeReaderCapsuleMiniBtn(string text, string tip)
        {
            return new Button
            {
                Content = text,
                ToolTip = tip,
                Foreground = new SolidColorBrush(Color.FromRgb(220, 228, 242)),
                Background = Brushes.Transparent,
                BorderThickness = new Thickness(0),
                FontSize = 13.5,
                FontWeight = FontWeights.Medium,
                Padding = new Thickness(10, 6, 10, 6),
                Margin = new Thickness(2, 0, 2, 0),
                Cursor = Cursors.Hand,
                FocusVisualStyle = null,
                Template = UiTheme.CreateRoundedButtonTemplate(14, null)
            };
        }

        private ReaderPrefModel GetActiveReaderProfile()
        {
            if (!_services.ReaderDefaults.ApplyGlobal && _readerComic != null)
            {
                ReaderPrefModel per = null;
                if (_services.ComicPrefs.TryGetValue(_readerComic.Uid, out per)) return per.Clone();
            }
            return _services.ReaderDefaults.Clone();
        }

        private void UpdateActiveReaderProfile(Action<ReaderPrefModel> mutate)
        {
            mutate(_services.ReaderDefaults);
            if (_readerComic != null)
            {
                ReaderPrefModel per = null;
                if (!_services.ComicPrefs.TryGetValue(_readerComic.Uid, out per))
                {
                    per = _services.ReaderDefaults.Clone();
                    _services.ComicPrefs[_readerComic.Uid] = per;
                }
                else
                {
                    mutate(per);
                }
            }
            _services.SaveState();
            SyncReaderDrawerState();
            RenderCurrentReaderPagesAsync();
        }

        private void ClearReaderPageMemoryCache()
        {
            lock (_readerCacheLock)
            {
                _readerPageCache.Clear();
                _readerPageCacheOrder.Clear();
                _readerPageInFlight.Clear();
            }
        }

        private string GetReaderCacheKey(PageImageModel page, int pageIdx)
        {
            if (page == null) return "";
            if (page.IsLocal) return "local::" + page.LocalComicId + "::" + page.LocalPageIndex;
            string chId = (_readerChapterIdx >= 0 && _readerChapterIdx < _readerChapters.Count) ? _readerChapters[_readerChapterIdx].Id : "";
            string uid = _readerComic != null ? _readerComic.Uid : "";
            return "online::" + uid + "::" + chId + "::" + pageIdx + "::" + (page.Url ?? "");
        }

        private BitmapImage TryGetCachedReaderBitmap(string key)
        {
            if (string.IsNullOrEmpty(key)) return null;
            lock (_readerCacheLock)
            {
                BitmapImage bmp;
                if (_readerPageCache.TryGetValue(key, out bmp))
                {
                    _readerPageCacheOrder.Remove(key);
                    _readerPageCacheOrder.AddLast(key);
                    return bmp;
                }
            }
            return null;
        }

        private void PutCachedReaderBitmap(string key, BitmapImage bmp)
        {
            if (string.IsNullOrEmpty(key) || bmp == null) return;
            lock (_readerCacheLock)
            {
                _readerPageCache[key] = bmp;
                _readerPageCacheOrder.Remove(key);
                _readerPageCacheOrder.AddLast(key);
                while (_readerPageCacheOrder.Count > MaxReaderMemoryCachePages)
                {
                    string oldest = _readerPageCacheOrder.First.Value;
                    _readerPageCacheOrder.RemoveFirst();
                    _readerPageCache.Remove(oldest);
                }
            }
        }

        private static BitmapSource CropBitmapHalf(BitmapImage bmp, bool leftHalf)
        {
            if (bmp == null || bmp.PixelWidth < 4 || bmp.PixelHeight < 2) return bmp;
            int halfW = bmp.PixelWidth / 2;
            Int32Rect rect = leftHalf
                ? new Int32Rect(0, 0, halfW, bmp.PixelHeight)
                : new Int32Rect(halfW, 0, bmp.PixelWidth - halfW, bmp.PixelHeight);
            CroppedBitmap cropped = new CroppedBitmap(bmp, rect);
            cropped.Freeze();
            return cropped;
        }

        private void OpenLocalComicInReader(ComicCardModel card)
        {
            ++_readerChapterRequest;
            _readerLoadingBox.Visibility = Visibility.Collapsed;
            PushHistory(card);
            _readerOpen = true;
            _readerIsLocal = true;
            _readerComic = card;
            _readerChapters = new List<ChapterItem> { new ChapterItem { Id = "local", Title = "全本 (" + card.SubTitle + ")" } };
            _readerChapterIdx = 0;

            ReaderPrefModel saved = null;
            _readerPageIdx = (_services.ComicPrefs.TryGetValue(card.Uid, out saved)) ? saved.LastPage : 0;
            _spreadShift = 0;
            _splitHalf = 0;
            _currentPageIsSplit = false;
            _pendingAnimDir = 0;
            _zoomScale = 1.0;
            _readerZoomTransform.ScaleX = 1.0;
            _readerZoomTransform.ScaleY = 1.0;
            _widePageFlags.Clear();
            ClearReaderPageMemoryCache();
            _chromeVisible = false;

            int count = _services.GetLocalPageCount(card.Id);
            CancelReaderTranslation();
            _readerPages = new List<PageImageModel>();
            for (int i = 0; i < count; i++)
            {
                _readerPages.Add(new PageImageModel { IsLocal = true, LocalComicId = card.Id, LocalPageIndex = i });
            }
            if (_readerPageIdx >= _readerPages.Count) _readerPageIdx = 0;

            _readerOverlay.Visibility = Visibility.Visible;
            SyncReaderFavoriteButton();
            SyncReaderChromeVisibility(false);
            SyncReaderDrawerState();
            RenderCurrentReaderPagesAsync();
        }

        private void StartReadingClock()
        {
            if (!_readerOpen || !_readerPageReady || !IsActive || _readerComic == null || _readingStopwatch.IsRunning) return;
            _readingStartedAt = DateTimeOffset.Now;
            _readingStopwatch.Restart();
            _readingTimer.Start();
        }

        private void FlushReadingTime()
        {
            if (!_readingStopwatch.IsRunning || _readerComic == null) return;
            long elapsed = _readingStopwatch.ElapsedMilliseconds;
            if (elapsed <= 0) return;
            _services.ReadingActivity.Add(_readerComic, _readingStartedAt, Math.Min(elapsed, 60000));
            _readingStartedAt = DateTimeOffset.Now;
            _readingStopwatch.Restart();
        }

        private void StopReadingClock()
        {
            FlushReadingTime();
            _readingStopwatch.Reset();
            if (_readingTimer != null) _readingTimer.Stop();
        }

        private void StartOnlineReader(ComicCardModel card, List<ChapterItem> chapters, int chIdx, int pageIdx)
        {
            _readerOpen = true;
            _readerIsLocal = false;
            _readerComic = card;
            _readerChapters = chapters;
            _readerChapterIdx = chIdx;
            _readerPageIdx = pageIdx;
            _spreadShift = 0;
            _splitHalf = 0;
            _currentPageIsSplit = false;
            _pendingAnimDir = 0;
            _zoomScale = 1.0;
            _readerZoomTransform.ScaleX = 1.0;
            _readerZoomTransform.ScaleY = 1.0;
            _widePageFlags.Clear();
            ClearReaderPageMemoryCache();
            _chromeVisible = false;

            _readerOverlay.Visibility = Visibility.Visible;
            SyncReaderFavoriteButton();
            SyncReaderChromeVisibility(false);
            SyncReaderDrawerState();
            LoadOnlineChapterAsync(chIdx, pageIdx);
        }

        private void ShowReaderMessage(string text, Action retry)
        {
            _readerLoadMessage.Text = text;
            _readerRetry = retry;
            _readerRetryButton.Visibility = retry == null ? Visibility.Collapsed : Visibility.Visible;
            _readerLoadingBox.Visibility = Visibility.Visible;
        }

        private async void LoadOnlineChapterAsync(int chIdx, int targetPage)
        {
            if (_readerComic == null || chIdx < 0 || chIdx >= _readerChapters.Count) return;
            StopReadingClock();
            _readerPageReady = false;
            int request = ++_readerChapterRequest;
            ++_readerRenderRequest;
            _readerChapterIdx = chIdx;
            CancelReaderTranslation();
            _readerPages = new List<PageImageModel>();
            _widePageFlags.Clear();
            ClearReaderPageMemoryCache();
            _readerPageIdx = targetPage;
            _splitHalf = 0;
            _currentPageIsSplit = false;
            _pendingAnimDir = 0;
            ShowReaderMessage("正在加载章节…", null);
            _readerSingleImg.Source = null;
            _readerLeftImg.Source = null;
            _readerRightImg.Source = null;
            _readerGhostGrid.Visibility = Visibility.Collapsed;
            _readerScrollStack.Children.Clear();

            ChapterItem chapter = _readerChapters[chIdx];
            _readerChapterText.Text = chapter.Title;
            ComicCardModel comic = _readerComic;
            try
            {
                SourceItem src = _services.Sources.Find(item => item.Key == comic.SourceKey);
                if (src == null) throw new InvalidOperationException("找不到所属漫画源");
                List<PageImageModel> pages = await _jsRunner.LoadChapterPagesAsync(src, comic.Id, chapter.Id);
                if (request != _readerChapterRequest || !_readerOpen) return;
                if (pages.Count == 0) throw new InvalidOperationException("该章节暂无可读页面");
                _readerPages = pages;
                _readerPageIdx = Math.Max(0, Math.Min(targetPage, pages.Count - 1));
                _readerLoadingBox.Visibility = Visibility.Collapsed;
                PushHistory(comic);
                SaveReaderProgress();
                RenderCurrentReaderPagesAsync();
            }
            catch (Exception ex)
            {
                if (request == _readerChapterRequest && _readerOpen)
                    ShowReaderMessage("加载章节失败：" + ex.Message.Split('\n')[0], delegate { LoadOnlineChapterAsync(chIdx, targetPage); });
            }
        }

        private void SaveReaderProgress()
        {
            if (_readerComic == null) return;
            ReaderPrefModel per = null;
            if (!_services.ComicPrefs.TryGetValue(_readerComic.Uid, out per))
            {
                per = _services.ReaderDefaults.Clone();
                _services.ComicPrefs[_readerComic.Uid] = per;
            }
            per.LastChapterIdx = _readerChapterIdx;
            per.LastPage = _readerPageIdx;
            _services.SaveState();
        }

        private List<int[]> BuildDoublePageSpreads()
        {
            List<int[]> spreads = new List<int[]>();
            int total = _readerPages != null ? _readerPages.Count : 0;
            if (total <= 0) return spreads;
            int i = 0;
            bool runStart = true;
            while (i < total)
            {
                bool curWide = false;
                _widePageFlags.TryGetValue(i, out curWide);
                if (curWide)
                {
                    spreads.Add(new int[] { i, -1 });
                    i += 1;
                    runStart = true;
                    continue;
                }
                if (runStart && _spreadShift == 1)
                {
                    spreads.Add(new int[] { i, -1 });
                    i += 1;
                    runStart = false;
                    continue;
                }
                runStart = false;
                if (i + 1 >= total)
                {
                    spreads.Add(new int[] { i, -1 });
                    i += 1;
                    continue;
                }
                bool nextWide = false;
                _widePageFlags.TryGetValue(i + 1, out nextWide);
                if (nextWide)
                {
                    spreads.Add(new int[] { i, -1 });
                    i += 1;
                }
                else
                {
                    spreads.Add(new int[] { i, i + 1 });
                    i += 2;
                }
            }
            return spreads;
        }

        private int FindSpreadIndexForPage(List<int[]> spreads, int pageIdx)
        {
            for (int s = 0; s < spreads.Count; s++)
            {
                if (spreads[s][0] == pageIdx || spreads[s][1] == pageIdx) return s;
            }
            for (int s = spreads.Count - 1; s >= 0; s--)
            {
                if (spreads[s][0] <= pageIdx) return s;
            }
            return 0;
        }

        private void ToggleSpreadPairShift()
        {
            if (_readerPages == null || _readerPages.Count == 0) return;
            List<int[]> oldSpreads = BuildDoublePageSpreads();
            int oldIdx = oldSpreads.Count > 0 ? FindSpreadIndexForPage(oldSpreads, _readerPageIdx) : 0;
            int oldFirst = oldSpreads.Count > 0 ? oldSpreads[oldIdx][0] : _readerPageIdx;
            int oldSecond = oldSpreads.Count > 0 ? oldSpreads[oldIdx][1] : -1;

            int nextShift = _spreadShift == 0 ? 1 : 0;
            _spreadShift = nextShift;

            List<int[]> newSpreads = BuildDoublePageSpreads();
            int newFirst = oldFirst;
            int newSecond = -1;
            if (newSpreads.Count > 0)
            {
                int anchor = (nextShift == 0 && oldSecond >= 0) ? oldSecond : oldFirst;
                int sIdx = FindSpreadIndexForPage(newSpreads, anchor);
                newFirst = newSpreads[sIdx][0];
                newSecond = newSpreads[sIdx][1];
                _readerPageIdx = newFirst;
            }

            _pendingAnimDir = 0;
            SyncReaderDrawerState();
            SaveReaderProgress();
            RenderCurrentReaderPagesAsync();

            bool curWide = false;
            _widePageFlags.TryGetValue(oldFirst, out curWide);
            if (curWide && newFirst == oldFirst && newSecond == oldSecond)
            {
                ShowToast(_spreadShift == 1 ? "已切换首页单页起（当前页为宽图，翻页后生效）" : "已切换默认双页起（当前页为宽图，翻页后生效）");
            }
            else
            {
                string range = newSecond >= 0
                    ? string.Format("第 {0}-{1} 页", newFirst + 1, newSecond + 1)
                    : string.Format("第 {0} 页（单页）", newFirst + 1);
                ShowToast((_spreadShift == 1 ? "跨页偏移：首页单页起 · " : "跨页偏移：默认双页起 · ") + range);
            }
        }

        private void SyncReaderChromeVisibility(bool animate = true)
        {
            int gen = ++_chromeAnimGen;
            bool smooth = animate;
            if (_chromeVisible)
            {
                _readerTopBar.Visibility = Visibility.Visible;
                _readerBottomCapsule.Visibility = Visibility.Visible;
                if (!smooth)
                {
                    _readerTopTranslate.BeginAnimation(TranslateTransform.YProperty, null);
                    _readerBottomTranslate.BeginAnimation(TranslateTransform.YProperty, null);
                    _readerTopBar.BeginAnimation(UIElement.OpacityProperty, null);
                    _readerBottomCapsule.BeginAnimation(UIElement.OpacityProperty, null);
                    _readerTopTranslate.Y = 0;
                    _readerBottomTranslate.Y = 0;
                    _readerTopBar.Opacity = 1;
                    _readerBottomCapsule.Opacity = 1;
                    return;
                }
                CubicEase easeOut = new CubicEase { EasingMode = EasingMode.EaseOut };
                _readerTopTranslate.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(-72, 0, TimeSpan.FromMilliseconds(260)) { EasingFunction = easeOut });
                _readerTopBar.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(240)) { EasingFunction = easeOut });
                _readerBottomTranslate.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(124, 0, TimeSpan.FromMilliseconds(320)) { EasingFunction = easeOut });
                _readerBottomCapsule.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(260)) { EasingFunction = easeOut });
            }
            else
            {
                if (!smooth)
                {
                    _readerTopTranslate.BeginAnimation(TranslateTransform.YProperty, null);
                    _readerBottomTranslate.BeginAnimation(TranslateTransform.YProperty, null);
                    _readerTopBar.BeginAnimation(UIElement.OpacityProperty, null);
                    _readerBottomCapsule.BeginAnimation(UIElement.OpacityProperty, null);
                    _readerTopTranslate.Y = -72;
                    _readerBottomTranslate.Y = 124;
                    _readerTopBar.Opacity = 0;
                    _readerBottomCapsule.Opacity = 0;
                    _readerTopBar.Visibility = Visibility.Collapsed;
                    _readerBottomCapsule.Visibility = Visibility.Collapsed;
                    return;
                }
                CubicEase easeIn = new CubicEase { EasingMode = EasingMode.EaseIn };
                DoubleAnimation topOut = new DoubleAnimation(_readerTopTranslate.Y, -72, TimeSpan.FromMilliseconds(200)) { EasingFunction = easeIn };
                DoubleAnimation botOut = new DoubleAnimation(_readerBottomTranslate.Y, 124, TimeSpan.FromMilliseconds(230)) { EasingFunction = easeIn };
                botOut.Completed += delegate
                {
                    if (gen == _chromeAnimGen && !_chromeVisible)
                    {
                        _readerTopBar.Visibility = Visibility.Collapsed;
                        _readerBottomCapsule.Visibility = Visibility.Collapsed;
                    }
                };
                _readerTopTranslate.BeginAnimation(TranslateTransform.YProperty, topOut);
                _readerTopBar.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(_readerTopBar.Opacity, 0, TimeSpan.FromMilliseconds(180)) { EasingFunction = easeIn });
                _readerBottomTranslate.BeginAnimation(TranslateTransform.YProperty, botOut);
                _readerBottomCapsule.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(_readerBottomCapsule.Opacity, 0, TimeSpan.FromMilliseconds(190)) { EasingFunction = easeIn });
            }
        }

        private void SetReaderDrawerVisible(bool visible, bool animate = true)
        {
            int gen = ++_drawerAnimGen;
            bool smooth = animate;
            if (visible)
            {
                _readerDrawer.Visibility = Visibility.Visible;
                if (!smooth)
                {
                    _readerDrawerTranslate.BeginAnimation(TranslateTransform.XProperty, null);
                    _readerDrawer.BeginAnimation(UIElement.OpacityProperty, null);
                    _readerDrawerTranslate.X = 0;
                    _readerDrawer.Opacity = 1;
                    return;
                }
                CubicEase easeOut = new CubicEase { EasingMode = EasingMode.EaseOut };
                _readerDrawerTranslate.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(360, 0, TimeSpan.FromMilliseconds(260)) { EasingFunction = easeOut });
                _readerDrawer.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220)) { EasingFunction = easeOut });
            }
            else
            {
                if (!smooth)
                {
                    _readerDrawerTranslate.BeginAnimation(TranslateTransform.XProperty, null);
                    _readerDrawer.BeginAnimation(UIElement.OpacityProperty, null);
                    _readerDrawerTranslate.X = 360;
                    _readerDrawer.Opacity = 0;
                    _readerDrawer.Visibility = Visibility.Collapsed;
                    return;
                }
                CubicEase easeIn = new CubicEase { EasingMode = EasingMode.EaseIn };
                DoubleAnimation slideOut = new DoubleAnimation(_readerDrawerTranslate.X, 360, TimeSpan.FromMilliseconds(200)) { EasingFunction = easeIn };
                slideOut.Completed += delegate
                {
                    if (gen == _drawerAnimGen) _readerDrawer.Visibility = Visibility.Collapsed;
                };
                _readerDrawerTranslate.BeginAnimation(TranslateTransform.XProperty, slideOut);
                _readerDrawer.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(_readerDrawer.Opacity, 0, TimeSpan.FromMilliseconds(180)) { EasingFunction = easeIn });
            }
        }

        private void SyncReaderDrawerState()
        {
            ReaderPrefModel prof = GetActiveReaderProfile();
            _readerTitleText.Text = _readerComic != null ? _readerComic.Title : "";
            _readerChapterText.Text = (_readerChapterIdx >= 0 && _readerChapterIdx < _readerChapters.Count) ? _readerChapters[_readerChapterIdx].Title : "";

            _drawerBrightnessSlider.Value = prof.Brightness;
            _drawerBrightnessText.Text = prof.Brightness + "%";
            _readerBrightnessScrim.Opacity = Math.Max(0.0, Math.Min(0.68, (100 - prof.Brightness) / 100.0));

            StyleDrawerOpt(_btnModeLtr, prof.Mode == "ltr");
            StyleDrawerOpt(_btnModeRtl, prof.Mode == "rtl");
            StyleDrawerOpt(_btnModeScroll, prof.Mode == "scroll");

            StyleDrawerOpt(_btnQUltra, prof.Quality == "ultra");
            StyleDrawerOpt(_btnQHigh, prof.Quality == "high");
            StyleDrawerOpt(_btnQSmooth, prof.Quality == "smooth");

            StyleDrawerOpt(_btnSpreadSingle, prof.Spread == "single");
            StyleDrawerOpt(_btnSpreadDouble, prof.Spread == "double");
            StyleDrawerOpt(_btnSpreadSplit, prof.Spread == "split");
            StyleDrawerOpt(_btnDrawerSpreadEven, _spreadShift == 0);
            StyleDrawerOpt(_btnDrawerSpreadOdd, _spreadShift == 1);

            string activeAnim = string.IsNullOrEmpty(prof.PageTurnAnim) ? "slide" : prof.PageTurnAnim;
            foreach (KeyValuePair<string, Button> kv in _animButtons)
                StyleDrawerOpt(kv.Value, kv.Key == activeAnim);

            foreach (KeyValuePair<int, Button> kv in _prefetchButtons)
                StyleDrawerOpt(kv.Value, kv.Key == prof.PrefetchPages);

            _chkApplyGlobal.IsChecked = _services.ReaderDefaults.ApplyGlobal;
            _chkTrimMargins.IsChecked = prof.TrimMargins;
            _chkDoubleZoom.IsChecked = prof.DoubleTapZoom;
            _chkShowComment.IsChecked = prof.ShowCommentPage;
            _chkShowPageNum.IsChecked = prof.ShowPageNumber;
            _chkWheelTurn.IsChecked = prof.WheelPageTurn;
            _chkSplitAll.IsChecked = prof.SplitAllPages;
            _chkSpreadGap.IsChecked = prof.SpreadGap;

            if (_readerSpreadGapCol != null)
                _readerSpreadGapCol.Width = new GridLength(prof.SpreadGap ? 6 : 0);

            bool paged = prof.Mode != "scroll";
            _btnSpreadPairShift.Content = _spreadShift == 1 ? "跨页偏移 · 首页单页" : "跨页偏移 · 默认双页";
            StyleTopTogglePill(_btnSpreadPairShift, _spreadShift == 1);
            _btnSpreadPairShift.Visibility = (paged && prof.Spread == "double") ? Visibility.Visible : Visibility.Collapsed;
            _readerPageBadge.Visibility = prof.ShowPageNumber ? Visibility.Visible : Visibility.Collapsed;
            SyncReaderFavoriteButton();
        }

        private static void StyleTopTogglePill(Button b, bool active)
        {
            if (b == null) return;
            if (active)
            {
                b.Background = new SolidColorBrush(Color.FromArgb(95, 56, 163, 255));
                b.BorderBrush = new SolidColorBrush(Color.FromRgb(88, 180, 255));
                b.Foreground = Brushes.White;
            }
            else
            {
                b.Background = new SolidColorBrush(Color.FromArgb(190, 22, 30, 46));
                b.BorderBrush = new SolidColorBrush(Color.FromArgb(68, 255, 255, 255));
                b.Foreground = new SolidColorBrush(Color.FromRgb(220, 232, 248));
            }
        }

        private static void StyleDrawerOpt(Button b, bool active)
        {
            if (b == null) return;
            if (active)
            {
                b.Background = new SolidColorBrush(Color.FromArgb(64, 56, 163, 255));
                b.BorderBrush = new SolidColorBrush(Color.FromRgb(72, 174, 255));
                b.Foreground = new SolidColorBrush(Color.FromRgb(125, 206, 255));
            }
            else
            {
                b.Background = new SolidColorBrush(Color.FromArgb(180, 30, 42, 62));
                b.BorderBrush = new SolidColorBrush(Color.FromArgb(38, 255, 255, 255));
                b.Foreground = new SolidColorBrush(Color.FromRgb(203, 213, 225));
            }
        }

        private void CaptureGhostSpread()
        {
            _readerGhostGrid.BeginAnimation(UIElement.OpacityProperty, null);
            _readerGhostSlideTransform.BeginAnimation(TranslateTransform.XProperty, null);
            _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);

            if (_readerSingleImg.Visibility == Visibility.Visible && _readerSingleImg.Source != null)
            {
                _readerGhostSingleImg.Source = _readerSingleImg.Source;
                _readerGhostSingleImg.Visibility = Visibility.Visible;
                _readerGhostLeftImg.Source = null;
                _readerGhostRightImg.Source = null;
            }
            else if (_readerLeftImg.Source != null || _readerRightImg.Source != null)
            {
                _readerGhostSingleImg.Source = null;
                _readerGhostSingleImg.Visibility = Visibility.Collapsed;
                _readerGhostLeftImg.Source = _readerLeftImg.Source;
                _readerGhostRightImg.Source = _readerRightImg.Source;
            }
            else
            {
                _readerGhostGrid.Visibility = Visibility.Collapsed;
                return;
            }
            _readerGhostGrid.Opacity = 1;
            _readerGhostSlideTransform.X = 0;
            _readerGhostAnimScale.ScaleX = 1;
            _readerGhostAnimScale.ScaleY = 1;
            _readerGhostGrid.RenderTransformOrigin = new Point(0.5, 0.5);
            _readerGhostGrid.Visibility = Visibility.Visible;
        }

        private void ClearSpreadAnimationClocks(bool hideGhost)
        {
            _readerSpreadGrid.BeginAnimation(UIElement.OpacityProperty, null);
            _readerSpreadSlideTransform.BeginAnimation(TranslateTransform.XProperty, null);
            _readerSpreadAnimScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            _readerSpreadAnimScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
            _readerSpreadGrid.Opacity = 1;
            _readerSpreadGrid.RenderTransformOrigin = new Point(0.5, 0.5);
            _readerSpreadSlideTransform.X = 0;
            _readerSpreadAnimScale.ScaleX = 1;
            _readerSpreadAnimScale.ScaleY = 1;

            _readerGhostGrid.BeginAnimation(UIElement.OpacityProperty, null);
            _readerGhostSlideTransform.BeginAnimation(TranslateTransform.XProperty, null);
            _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
            _readerGhostGrid.Opacity = 1;
            _readerGhostGrid.RenderTransformOrigin = new Point(0.5, 0.5);
            _readerGhostSlideTransform.X = 0;
            _readerGhostAnimScale.ScaleX = 1;
            _readerGhostAnimScale.ScaleY = 1;
            if (hideGhost) _readerGhostGrid.Visibility = Visibility.Collapsed;

            _readerAnimShadow.BeginAnimation(FrameworkElement.MarginProperty, null);
            _readerAnimShadow.Visibility = Visibility.Collapsed;
        }

        private void ResetReaderPageAnimations()
        {
            _pageAnimGen++;
            ClearSpreadAnimationClocks(true);
        }

        private void PlayPageTurnAnimation(int visualDir, string animMode)
        {
            int gen = ++_pageAnimGen;
            if (visualDir == 0 || string.IsNullOrEmpty(animMode) || animMode == "none")
            {
                ClearSpreadAnimationClocks(true);
                return;
            }
            bool hasGhost = _readerGhostGrid.Visibility == Visibility.Visible;
            ClearSpreadAnimationClocks(false);
            if (!hasGhost) _readerGhostGrid.Visibility = Visibility.Collapsed;

            double w = Math.Max(480, _readerViewport.ActualWidth);
            Panel.SetZIndex(_readerGhostGrid, 1);
            Panel.SetZIndex(_readerSpreadGrid, 2);
            Panel.SetZIndex(_readerAnimShadow, 3);

            CubicEase easeOut = new CubicEase { EasingMode = EasingMode.EaseOut };
            QuadraticEase easeInOut = new QuadraticEase { EasingMode = EasingMode.EaseInOut };

            Action cleanup = delegate
            {
                if (gen != _pageAnimGen) return;
                ClearSpreadAnimationClocks(true);
            };

            if (animMode == "fade")
            {
                TimeSpan dur = TimeSpan.FromMilliseconds(240);
                DoubleAnimation fadeIn = new DoubleAnimation(0, 1, dur) { EasingFunction = easeOut };
                fadeIn.Completed += delegate { cleanup(); };
                _readerSpreadGrid.BeginAnimation(UIElement.OpacityProperty, fadeIn);
                if (hasGhost)
                    _readerGhostGrid.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(1, 0, dur) { EasingFunction = easeOut });
            }
            else if (animMode == "slide")
            {
                TimeSpan dur = TimeSpan.FromMilliseconds(280);
                DoubleAnimation inX = new DoubleAnimation(visualDir * w, 0, dur) { EasingFunction = easeOut };
                inX.Completed += delegate { cleanup(); };
                _readerSpreadSlideTransform.BeginAnimation(TranslateTransform.XProperty, inX);
                if (hasGhost)
                    _readerGhostSlideTransform.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(0, -visualDir * w, dur) { EasingFunction = easeOut });
            }
            else if (animMode == "cover")
            {
                TimeSpan dur = TimeSpan.FromMilliseconds(300);
                DoubleAnimation inX = new DoubleAnimation(visualDir * w, 0, dur) { EasingFunction = easeOut };
                inX.Completed += delegate { cleanup(); };
                _readerSpreadSlideTransform.BeginAnimation(TranslateTransform.XProperty, inX);
                if (hasGhost)
                {
                    _readerGhostGrid.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(1, 0.55, dur) { EasingFunction = easeOut });
                    _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(1.0, 0.94, dur) { EasingFunction = easeOut });
                    _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(1.0, 0.94, dur) { EasingFunction = easeOut });
                }
                _readerAnimShadow.Visibility = Visibility.Visible;
                Thickness fromM = new Thickness(visualDir > 0 ? w - 24 : -24, 0, 0, 0);
                Thickness toM = new Thickness(visualDir > 0 ? -24 : w - 24, 0, 0, 0);
                _readerAnimShadow.BeginAnimation(FrameworkElement.MarginProperty, new ThicknessAnimation(fromM, toM, dur) { EasingFunction = easeOut });
            }
            else if (animMode == "curl")
            {
                TimeSpan dur = TimeSpan.FromMilliseconds(340);
                if (hasGhost)
                {
                    Panel.SetZIndex(_readerSpreadGrid, 1);
                    Panel.SetZIndex(_readerGhostGrid, 2);
                    _readerGhostGrid.RenderTransformOrigin = new Point(visualDir > 0 ? 0.0 : 1.0, 0.5);
                    DoubleAnimation foldX = new DoubleAnimation(1.0, 0.0, dur) { EasingFunction = easeInOut };
                    foldX.Completed += delegate { cleanup(); };
                    _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleXProperty, foldX);
                    _readerGhostGrid.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(1.0, 0.25, dur) { EasingFunction = easeInOut });
                    _readerSpreadGrid.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.7, 1.0, dur) { EasingFunction = easeOut });
                }
                else
                {
                    _readerSpreadGrid.RenderTransformOrigin = new Point(visualDir > 0 ? 1.0 : 0.0, 0.5);
                    DoubleAnimation unfoldX = new DoubleAnimation(0.15, 1.0, dur) { EasingFunction = easeOut };
                    unfoldX.Completed += delegate { cleanup(); };
                    _readerSpreadAnimScale.BeginAnimation(ScaleTransform.ScaleXProperty, unfoldX);
                }
                _readerAnimShadow.Visibility = Visibility.Visible;
                Thickness fromM = new Thickness(visualDir > 0 ? w - 24 : 0, 0, 0, 0);
                Thickness toM = new Thickness(visualDir > 0 ? 0 : w - 24, 0, 0, 0);
                _readerAnimShadow.BeginAnimation(FrameworkElement.MarginProperty, new ThicknessAnimation(fromM, toM, dur) { EasingFunction = easeInOut });
            }
            else if (animMode == "depth")
            {
                TimeSpan dur = TimeSpan.FromMilliseconds(300);
                DoubleAnimation inScale = new DoubleAnimation(1.08, 1.0, dur) { EasingFunction = easeOut };
                inScale.Completed += delegate { cleanup(); };
                _readerSpreadAnimScale.BeginAnimation(ScaleTransform.ScaleXProperty, inScale);
                _readerSpreadAnimScale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(1.08, 1.0, dur) { EasingFunction = easeOut });
                _readerSpreadGrid.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, dur) { EasingFunction = easeOut });
                if (hasGhost)
                {
                    _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(1.0, 0.88, dur) { EasingFunction = easeOut });
                    _readerGhostAnimScale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(1.0, 0.88, dur) { EasingFunction = easeOut });
                    _readerGhostGrid.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(1, 0, dur) { EasingFunction = easeOut });
                }
            }
            else
            {
                cleanup();
            }
        }

        private async void RenderCurrentReaderPagesAsync()
        {
            int request = ++_readerRenderRequest;
            int chapterReq = _readerChapterRequest;
            int animDir = _pendingAnimDir;
            _pendingAnimDir = 0;

            _readerSingleImg.Tag = _readerLeftImg.Tag = _readerRightImg.Tag = null;
            ReaderPrefModel prof = GetActiveReaderProfile();
            int total = _readerPages.Count;
            _readerSeekSlider.Maximum = Math.Max(1, total);
            _readerSeekSlider.Value = Math.Min(Math.Max(1, _readerPageIdx + 1), Math.Max(1, total));
            string txt = total > 0 ? string.Format("{0}/{1}", _readerPageIdx + 1, total) : "0/0";
            _readerSeekText.Text = txt;
            _readerPageBadgeText.Text = txt;

            Thickness trimPad = prof.TrimMargins ? new Thickness(-14) : new Thickness(0);
            _readerSingleImg.Margin = trimPad;
            _readerLeftImg.Margin = trimPad;
            _readerRightImg.Margin = trimPad;
            if (_readerSpreadGapCol != null) _readerSpreadGapCol.Width = new GridLength(prof.SpreadGap ? 6 : 0);

            if (total == 0)
            {
                _readerSingleImg.Source = null;
                _readerLeftImg.Source = null;
                _readerRightImg.Source = null;
                _readerScrollStack.Children.Clear();
                return;
            }

            _readerLoadingBox.Visibility = Visibility.Collapsed;
            if (prof.Mode == "scroll")
            {
                ResetReaderPageAnimations();
                _readerSpreadGrid.Visibility = Visibility.Collapsed;
                _readerScrollViewer.Visibility = Visibility.Visible;
                _readerScrollStack.Children.Clear();
                for (int i = 0; i < total; i++)
                {
                    int idx = i;
                    Image im = new Image { MaxWidth = 980, Stretch = Stretch.Uniform, Margin = new Thickness(0, 0, 0, 4) };
                    RenderOptions.SetBitmapScalingMode(im, BitmapScalingMode.HighQuality);
                    _readerScrollStack.Children.Add(im);
                    LoadFullPageIntoImageAsync(_readerPages[idx], idx, im, false, request);
                }
                return;
            }

            _readerScrollViewer.Visibility = Visibility.Collapsed;
            _readerSpreadGrid.Visibility = Visibility.Visible;

            int cur = _readerPageIdx;
            if (prof.Spread == "split")
            {
                _readerLeftImg.Source = null;
                _readerRightImg.Source = null;
                _readerSingleImg.Visibility = Visibility.Visible;
                BitmapImage bmp = null;
                string failure = "";
                try { bmp = await LoadReaderBitmapAsync(_readerPages[cur], cur, _readerPages); }
                catch (Exception error) { failure = "：" + error.Message.Split('\n')[0]; }
                if (request != _readerRenderRequest || !_readerOpen) return;
                if (bmp == null)
                {
                    ShowReaderMessage("第 " + (cur + 1) + " 页图片加载失败" + failure, RenderCurrentReaderPagesAsync);
                    return;
                }
                bool isWide = bmp.PixelWidth > bmp.PixelHeight * 1.15;
                _widePageFlags[cur] = isWide;
                _currentPageIsSplit = prof.SplitAllPages || isWide;
                if (_currentPageIsSplit)
                {
                    bool rtl = prof.Mode == "rtl";
                    bool leftHalf = rtl ? (_splitHalf == 1) : (_splitHalf == 0);
                    _readerSingleImg.Source = CropBitmapHalf(bmp, leftHalf);
                    string halfTag = leftHalf ? "左半页" : "右半页";
                    _readerPageBadgeText.Text = string.Format("{0}/{1} · {2}", cur + 1, total, halfTag);
                }
                else
                {
                    _splitHalf = 0;
                    _readerSingleImg.Source = bmp;
                }
                if (!_readerPageReady)
                {
                    _readerPageReady = true;
                    StartReadingClock();
                }
                _readerSingleImg.Tag = new ReaderImageState { Original = bmp, PageIndex = cur };
                QueueVisibleTranslation();
                PlayPageTurnAnimation(animDir, prof.PageTurnAnim);
                PrefetchNextReaderPages(cur, chapterReq);
                return;
            }

            _currentPageIsSplit = false;
            _splitHalf = 0;
            bool isDouble = prof.Spread == "double";
            int firstIdx = cur;
            int secondIdx = -1;
            if (isDouble)
            {
                List<int[]> spreads = BuildDoublePageSpreads();
                if (spreads.Count > 0)
                {
                    int sIdx = FindSpreadIndexForPage(spreads, cur);
                    firstIdx = spreads[sIdx][0];
                    secondIdx = spreads[sIdx][1];
                    _readerPageIdx = firstIdx;
                }
            }

            if (secondIdx < 0)
            {
                _readerLeftImg.Source = null;
                _readerRightImg.Source = null;
                _readerSingleImg.Visibility = Visibility.Visible;
                await LoadFullPageIntoImageAsync(_readerPages[firstIdx], firstIdx, _readerSingleImg, true, request);
                if (request == _readerRenderRequest && _readerOpen)
                {
                    _readerSeekSlider.Value = Math.Min(Math.Max(1, firstIdx + 1), Math.Max(1, total));
                    string singleTxt = string.Format("{0}/{1}", firstIdx + 1, total);
                    _readerSeekText.Text = singleTxt;
                    _readerPageBadgeText.Text = singleTxt;
                    PlayPageTurnAnimation(animDir, prof.PageTurnAnim);
                    PrefetchNextReaderPages(firstIdx, chapterReq);
                }
            }
            else
            {
                _readerSingleImg.Source = null;
                _readerSingleImg.Visibility = Visibility.Collapsed;
                Image firstTarget = prof.Mode == "rtl" ? _readerRightImg : _readerLeftImg;
                Image secondTarget = prof.Mode == "rtl" ? _readerLeftImg : _readerRightImg;
                await Task.WhenAll(
                    LoadFullPageIntoImageAsync(_readerPages[firstIdx], firstIdx, firstTarget, true, request),
                    LoadFullPageIntoImageAsync(_readerPages[secondIdx], secondIdx, secondTarget, true, request)
                );
                if (request == _readerRenderRequest && _readerOpen)
                {
                    _readerSeekSlider.Value = Math.Min(Math.Max(1, firstIdx + 1), Math.Max(1, total));
                    string spreadTxt = string.Format("{0}-{1}/{2}", firstIdx + 1, secondIdx + 1, total);
                    _readerSeekText.Text = spreadTxt;
                    _readerPageBadgeText.Text = spreadTxt;
                    PlayPageTurnAnimation(animDir, prof.PageTurnAnim);
                    PrefetchNextReaderPages(secondIdx, chapterReq);
                }
            }
        }

        private Task<BitmapImage> LoadReaderBitmapAsync(PageImageModel page, int pageIdx, List<PageImageModel> pages)
        {
            string memKey = GetReaderCacheKey(page, pageIdx);
            BitmapImage cached = TryGetCachedReaderBitmap(memKey);
            if (cached != null) return Task.FromResult(cached);

            lock (_readerCacheLock)
            {
                Task<BitmapImage> existing;
                if (!string.IsNullOrEmpty(memKey) && _readerPageInFlight.TryGetValue(memKey, out existing))
                    return existing;
                Task<BitmapImage> created = LoadReaderBitmapCoreAsync(page, pageIdx, pages, memKey);
                if (!string.IsNullOrEmpty(memKey)) _readerPageInFlight[memKey] = created;
                return created;
            }
        }

        private async Task<BitmapImage> LoadReaderBitmapCoreAsync(PageImageModel page, int pageIdx, List<PageImageModel> pages, string memKey)
        {
            try
            {
                BitmapImage bitmap = null;
                if (page.IsLocal)
                {
                    bitmap = await _services.LoadLocalPageBitmapAsync(page.LocalComicId, page.LocalPageIndex, 0);
                }
                else
                {
                    Uri pageUri;
                    bool browserFormat = Uri.TryCreate(page.Url, UriKind.Absolute, out pageUri) &&
                        (pageUri.AbsolutePath.EndsWith(".webp", StringComparison.OrdinalIgnoreCase) ||
                         pageUri.AbsolutePath.EndsWith(".avif", StringComparison.OrdinalIgnoreCase));
                    if (!page.UseSourceLoader && !browserFormat)
                        bitmap = await _services.LoadComicImageAsync(page.Url, page.Headers, 0);
                    if (bitmap == null && ReferenceEquals(pages, _readerPages) && _readerOpen)
                    {
                        ComicCardModel comic = _readerComic;
                        ChapterItem chapter = _readerChapters[_readerChapterIdx];
                        SourceItem source = _services.Sources.Find(item => item.Key == comic.SourceKey);
                        string cacheKey = JsonUtil.CreateSerializer().Serialize(new object[] { comic.Uid, chapter.Id, pageIdx, page.Url, page.Headers });
                        bitmap = await _services.LoadSourcePageBitmapAsync(cacheKey,
                            () => _jsRunner.LoadPageBytesAsync(source, comic.Id, chapter.Id, page));
                    }
                }
                if (bitmap != null) PutCachedReaderBitmap(memKey, bitmap);
                return bitmap;
            }
            finally
            {
                if (!string.IsNullOrEmpty(memKey))
                {
                    lock (_readerCacheLock) { _readerPageInFlight.Remove(memKey); }
                }
            }
        }

        private async void PrefetchNextReaderPages(int anchorPage, int chapterReq)
        {
            ReaderPrefModel prof = GetActiveReaderProfile();
            int count = prof.PrefetchPages;
            List<PageImageModel> pages = _readerPages;
            if (!_readerOpen || count <= 0 || pages == null || pages.Count <= 1 || chapterReq != _readerChapterRequest) return;

            List<int> targets = new List<int>();
            for (int offset = 1; offset <= count; offset++)
            {
                int idx = anchorPage + offset;
                if (idx >= 0 && idx < pages.Count) targets.Add(idx);
            }
            if (anchorPage - 1 >= 0) targets.Add(anchorPage - 1);

            foreach (int idx in targets)
            {
                if (!_readerOpen || chapterReq != _readerChapterRequest || !ReferenceEquals(pages, _readerPages)) return;
                string key = GetReaderCacheKey(pages[idx], idx);
                if (TryGetCachedReaderBitmap(key) != null) continue;

                await _prefetchSemaphore.WaitAsync();
                try
                {
                    if (!_readerOpen || chapterReq != _readerChapterRequest || !ReferenceEquals(pages, _readerPages)) return;
                    BitmapImage bmp = await LoadReaderBitmapAsync(pages[idx], idx, pages);
                    if (bmp != null && _readerOpen && chapterReq == _readerChapterRequest && !_widePageFlags.ContainsKey(idx))
                    {
                        _widePageFlags[idx] = bmp.PixelWidth > bmp.PixelHeight * 1.15;
                    }
                }
                catch { }
                finally { _prefetchSemaphore.Release(); }
            }
        }

        private async Task LoadFullPageIntoImageAsync(PageImageModel page, int pageIdx, Image target, bool detectWide, int request)
        {
            BitmapImage bitmap = null;
            string failure = "";
            try { bitmap = await LoadReaderBitmapAsync(page, pageIdx, _readerPages); }
            catch (Exception error) { failure = "：" + error.Message.Split('\n')[0]; }

            if (request != _readerRenderRequest || !_readerOpen) return;
            if (bitmap == null)
            {
                if (detectWide || pageIdx == _readerPageIdx)
                    ShowReaderMessage("第 " + (pageIdx + 1) + " 页图片加载失败" + failure, RenderCurrentReaderPagesAsync);
                return;
            }
            target.Source = bitmap;
            if (!_readerPageReady)
            {
                _readerPageReady = true;
                StartReadingClock();
            }
            target.Tag = new ReaderImageState { Original = bitmap, PageIndex = pageIdx };
            QueueVisibleTranslation();
            if (detectWide && bitmap.PixelWidth > bitmap.PixelHeight * 1.15 && !_widePageFlags.ContainsKey(pageIdx))
            {
                _widePageFlags[pageIdx] = true;
                ReaderPrefModel profile = GetActiveReaderProfile();
                if (profile.Spread == "double" && profile.Mode != "scroll")
                    RenderCurrentReaderPagesAsync();
            }
        }

        private IEnumerable<Image> CurrentReaderImages()
        {
            if (_readerScrollViewer.Visibility == Visibility.Visible)
            {
                foreach (UIElement child in _readerScrollStack.Children)
                {
                    Image image = child as Image;
                    if (image != null) yield return image;
                }
            }
            else
            {
                if (_readerSingleImg.Visibility == Visibility.Visible) yield return _readerSingleImg;
                else { yield return _readerLeftImg; yield return _readerRightImg; }
            }
        }

        private void CancelReaderTranslation()
        {
            _translationPrefetchVersion++;
            _translationVisibleFirst = _translationVisibleLast = _translationPrefetchAnchor = -1;
            PruneReaderTranslations(1, 0);
        }

        private void ToggleReaderTranslation()
        {
            TranslationModel model = _services.TranslationModel;
            bool setupRequired = model.UsesTranslationServer ? string.IsNullOrWhiteSpace(_services.TranslationServer) :
                string.IsNullOrWhiteSpace(model.ApiBase) || string.IsNullOrWhiteSpace(model.ModelId) || string.IsNullOrWhiteSpace(model.ProtectedKey);
            if (!_readerTranslationEnabled && setupRequired)
            {
                ShowToast("请先在设置中完成漫画翻译配置");
                return;
            }
            _readerTranslationEnabled = !_readerTranslationEnabled;
            CancelReaderTranslation();
            _readerTranslationEpoch++;
            _readerTranslationButton.Content = _readerTranslationEnabled ? "文 原文" : "译 翻译";
            _readerTranslationStatus.Visibility = _readerTranslationEnabled ? Visibility.Visible : Visibility.Collapsed;
            _readerTranslationBall.Visibility = _readerTranslationStatus.Visibility;
            _readerTranslationStatus.Text = model.AcceptsPageImage ? "等待页面…" : "点击译球输入原文";
            if (_readerTranslationEnabled && model.AcceptsPageImage) QueueVisibleTranslation();
            else if (_readerTranslationEnabled) OpenTextTranslation();
            else foreach (Image view in CurrentReaderImages())
            {
                ReaderImageState state = view.Tag as ReaderImageState;
                if (state != null) view.Source = state.Original;
            }
        }

        private void QueueVisibleTranslation()
        {
            if (!_readerOpen || !_readerTranslationEnabled || !_services.TranslationModel.AcceptsPageImage || _translationDispatchPending) return;
            _translationDispatchPending = true;
            Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(delegate
            {
                _translationDispatchPending = false;
                if (!_readerOpen || !_readerTranslationEnabled) return;
                var visible = new List<Image>();
                foreach (Image view in CurrentReaderImages())
                {
                    if (view.Source == null || !(view.Tag is ReaderImageState)) continue;
                    if (_readerScrollViewer.Visibility == Visibility.Visible)
                    {
                        Point corner;
                        try { corner = view.TransformToAncestor(_readerScrollViewer).Transform(new Point(0, 0)); }
                        catch (InvalidOperationException) { continue; }
                        if (corner.Y >= _readerScrollViewer.ViewportHeight || corner.Y + view.ActualHeight <= 0) continue;
                    }
                    visible.Add(view);
                }
                if (visible.Count == 0) return;
                int first = visible.Min(view => ((ReaderImageState)view.Tag).PageIndex);
                int last = visible.Max(view => ((ReaderImageState)view.Tag).PageIndex);
                UpdateTranslationWindow(first, last);
                foreach (Image view in visible) StartReaderTranslation(view);
            }));
        }

        private async void StartReaderTranslation(Image view)
        {
            ReaderImageState state = view.Tag as ReaderImageState;
            int epoch = _readerTranslationEpoch;
            int request = _readerRenderRequest;
            if (state == null || state.TranslationEpoch == epoch) return;
            state.TranslationEpoch = epoch;
            _readerTranslationStatus.Text = "翻译中…";
            try
            {
                ReaderTranslationResult translated = await GetReaderTranslation(state.PageIndex, state.Original).Task;
                if (epoch != _readerTranslationEpoch || request != _readerRenderRequest || !_readerTranslationEnabled || view.Tag != state) return;
                view.Source = translated.Image;
                _readerTranslationStatus.Text = translated.Changed ? "已显示译文" : "未找到可安全排版的译文";
                PrefetchReaderTranslations();
            }
            catch (OperationCanceledException) { }
            catch (Exception error)
            {
                if (epoch == _readerTranslationEpoch && request == _readerRenderRequest && _readerTranslationEnabled && view.Tag == state)
                    _readerTranslationStatus.Text = MangaTranslation.Describe(error) + " · 点击重试";
            }
        }

        private void TurnReaderPage(int delta)
        {
            ReaderPrefModel prof = GetActiveReaderProfile();
            int total = _readerPages.Count;
            if (total == 0) return;
            if (prof.Mode == "scroll")
            {
                _readerScrollViewer.ScrollToVerticalOffset(_readerScrollViewer.VerticalOffset + delta * _readerViewport.ActualHeight * 0.8);
                return;
            }

            int visualDir = prof.Mode == "rtl" ? (delta > 0 ? -1 : 1) : (delta > 0 ? 1 : -1);

            if (prof.Spread == "split")
            {
                if (delta > 0 && _currentPageIsSplit && _splitHalf == 0)
                {
                    CaptureGhostSpread();
                    _pendingAnimDir = visualDir;
                    _splitHalf = 1;
                    RenderCurrentReaderPagesAsync();
                    return;
                }
                if (delta < 0 && _currentPageIsSplit && _splitHalf == 1)
                {
                    CaptureGhostSpread();
                    _pendingAnimDir = visualDir;
                    _splitHalf = 0;
                    RenderCurrentReaderPagesAsync();
                    return;
                }
                int nextSplitPage = _readerPageIdx + (delta > 0 ? 1 : -1);
                if (nextSplitPage < 0)
                {
                    if (_readerChapterIdx > 0) LoadOnlineChapterAsync(_readerChapterIdx - 1, 0);
                    else ShowToast("已经是第一页");
                    return;
                }
                if (nextSplitPage >= total)
                {
                    if (_readerChapterIdx + 1 < _readerChapters.Count) LoadOnlineChapterAsync(_readerChapterIdx + 1, 0);
                    else ShowToast("已是最后一页");
                    return;
                }
                CaptureGhostSpread();
                _pendingAnimDir = visualDir;
                _readerPageIdx = nextSplitPage;
                _splitHalf = delta > 0 ? 0 : 1;
                _zoomScale = 1.0;
                _readerZoomTransform.ScaleX = 1.0;
                _readerZoomTransform.ScaleY = 1.0;
                SaveReaderProgress();
                RenderCurrentReaderPagesAsync();
                return;
            }

            int next;
            if (prof.Spread == "double")
            {
                List<int[]> spreads = BuildDoublePageSpreads();
                int curSpreadIdx = FindSpreadIndexForPage(spreads, _readerPageIdx);
                int nextSpreadIdx = curSpreadIdx + (delta > 0 ? 1 : -1);
                if (nextSpreadIdx < 0)
                {
                    if (_readerChapterIdx > 0) LoadOnlineChapterAsync(_readerChapterIdx - 1, 0);
                    else ShowToast("已经是第一页");
                    return;
                }
                if (nextSpreadIdx >= spreads.Count)
                {
                    if (_readerChapterIdx + 1 < _readerChapters.Count) LoadOnlineChapterAsync(_readerChapterIdx + 1, 0);
                    else ShowToast("已是最后一页");
                    return;
                }
                next = spreads[nextSpreadIdx][0];
            }
            else
            {
                next = _readerPageIdx + (delta > 0 ? 1 : -1);
                if (next < 0)
                {
                    if (_readerChapterIdx > 0) LoadOnlineChapterAsync(_readerChapterIdx - 1, 0);
                    else ShowToast("已经是第一页");
                    return;
                }
                if (next >= total)
                {
                    if (_readerChapterIdx + 1 < _readerChapters.Count) LoadOnlineChapterAsync(_readerChapterIdx + 1, 0);
                    else ShowToast("已是最后一页");
                    return;
                }
            }

            CaptureGhostSpread();
            _pendingAnimDir = visualDir;
            _readerPageIdx = next;
            _zoomScale = 1.0;
            _readerZoomTransform.ScaleX = 1.0;
            _readerZoomTransform.ScaleY = 1.0;
            SaveReaderProgress();
            RenderCurrentReaderPagesAsync();
        }

        private void OpenReaderChaptersDialog()
        {
            Window dlg = CreateStyledDialog("章节目录 (" + _readerChapters.Count + ")", 560, 480);
            ScrollViewer sv = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Margin = new Thickness(16) };
            WrapPanel wp = new WrapPanel();
            for (int i = 0; i < _readerChapters.Count; i++)
            {
                int idx = i;
                bool isCur = i == _readerChapterIdx;
                Button b = UiTheme.MakeButton((isCur ? "▶ " : "") + _readerChapters[i].Title, isCur, false);
                b.Width = 162;
                b.Margin = new Thickness(0, 0, 8, 8);
                b.Click += delegate (object s, RoutedEventArgs e)
                {
                    dlg.Close();
                    if (!_readerIsLocal) LoadOnlineChapterAsync(idx, 0);
                };
                wp.Children.Add(b);
            }
            sv.Content = wp;
            dlg.Content = sv;
            dlg.ShowDialog();
        }

        private void CloseReader()
        {
            StopReadingClock();
            _readerPageReady = false;
            _readerOpen = false;
            _readerTranslationEnabled = false;
            CancelReaderTranslation();
            _readerTranslationEpoch++;
            _readerTranslationButton.Content = "译 翻译";
            _readerTranslationStatus.Visibility = Visibility.Collapsed;
            _readerTranslationBall.Visibility = Visibility.Collapsed;
            ++_readerChapterRequest;
            ++_readerRenderRequest;
            ClearReaderPageMemoryCache();
            ResetReaderPageAnimations();
            _readerLoadingBox.Visibility = Visibility.Collapsed;
            SetReaderDrawerVisible(false, false);
            _chromeVisible = false;
            SyncReaderChromeVisibility(false);
            _readerOverlay.Visibility = Visibility.Collapsed;
            _readerSingleImg.Source = null;
            _readerLeftImg.Source = null;
            _readerRightImg.Source = null;
            _readerGhostSingleImg.Source = null;
            _readerGhostLeftImg.Source = null;
            _readerGhostRightImg.Source = null;
            _readerScrollStack.Children.Clear();
        }

        private void ToggleFullscreen()
        {
            if (!_isFullscreen)
            {
                _prevWinState = WindowState;
                _prevWinStyle = WindowStyle;
                WindowStyle = WindowStyle.None;
                WindowState = WindowState.Normal;
                WindowState = WindowState.Maximized;
                _isFullscreen = true;
            }
            else
            {
                WindowStyle = _prevWinStyle;
                WindowState = _prevWinState;
                _isFullscreen = false;
            }
        }

        private void OnMainPreviewKeyDown(object sender, KeyEventArgs e)
        {
            if (!_readerOpen) return;
            ReaderPrefModel prof = GetActiveReaderProfile();
            if (e.Key == Key.Escape)
            {
                if (_readerDrawer.Visibility == Visibility.Visible) SetReaderDrawerVisible(false);
                else CloseReader();
                e.Handled = true;
            }
            else if (e.Key == Key.F11)
            {
                ToggleFullscreen();
                e.Handled = true;
            }
            else if (e.Key == Key.Left)
            {
                TurnReaderPage(prof.Mode == "rtl" ? 1 : -1);
                e.Handled = true;
            }
            else if (e.Key == Key.Right || e.Key == Key.Space)
            {
                TurnReaderPage(prof.Mode == "rtl" ? -1 : 1);
                e.Handled = true;
            }
        }

        /* ================= UI HELPERS ================= */
        private Window CreateStyledDialog(string title, double w, double h)
        {
            Window dlg = new Window
            {
                Title = title,
                Width = w,
                Height = h,
                Owner = this,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                UseLayoutRounding = true,
                SnapsToDevicePixels = true,
                FontFamily = FontFamily,
                Icon = Icon
            };
            TextOptions.SetTextFormattingMode(dlg, TextFormattingMode.Ideal);
            TextOptions.SetTextRenderingMode(dlg, TextRenderingMode.ClearType);
            dlg.SetResourceReference(Control.BackgroundProperty, "Brush.Bg");
            dlg.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            dlg.SourceInitialized += delegate (object s, EventArgs e)
            {
                NativeMethods.ApplyDarkTitleBar(new WindowInteropHelper(dlg).Handle, UiTheme.IsDarkActive);
            };
            return dlg;
        }

        private string PromptStringDialog(string title, string label, string initialValue, Window owner = null)
        {
            Window dlg = CreateStyledDialog(title, 420, 200);
            if (owner != null && owner.IsLoaded) dlg.Owner = owner;
            dlg.ResizeMode = ResizeMode.NoResize;
            StackPanel stack = new StackPanel { Margin = new Thickness(18) };
            stack.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 0, 0, 8), FontSize = 13 });
            TextBox tb = MakeStyledTextBox();
            tb.Text = initialValue ?? "";
            tb.Margin = new Thickness(0, 0, 0, 14);
            stack.Children.Add(tb);

            string result = null;
            StackPanel btns = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            Button bCancel = UiTheme.MakeButton("取消", false, false);
            Button bOk = UiTheme.MakeButton("确定", true, false);
            bOk.Margin = new Thickness(8, 0, 0, 0);
            bCancel.Click += delegate (object s, RoutedEventArgs e) { dlg.Close(); };
            bOk.Click += delegate (object s, RoutedEventArgs e) { result = tb.Text; dlg.Close(); };
            btns.Children.Add(bCancel);
            btns.Children.Add(bOk);
            stack.Children.Add(btns);
            dlg.Content = stack;
            dlg.Loaded += delegate (object s, RoutedEventArgs e) { tb.Focus(); tb.SelectAll(); };
            dlg.ShowDialog();
            return result;
        }

        private static Border MakeCardBlock()
        {
            Border b = new Border
            {
                CornerRadius = new CornerRadius(18),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(18, 16, 18, 16),
                Margin = new Thickness(0, 0, 0, 14)
            };
            b.SetResourceReference(Border.BackgroundProperty, "Brush.BgElevated");
            b.SetResourceReference(Border.BorderBrushProperty, "Brush.Border");
            b.Effect = UiTheme.CreateGlassShadow(UiTheme.IsDarkActive, true);
            return b;
        }

        private static TextBox MakeStyledTextBox()
        {
            TextBox tb = new TextBox
            {
                Padding = new Thickness(10, 7, 10, 7),
                FontSize = 13,
                BorderThickness = new Thickness(1)
            };
            tb.SetResourceReference(Control.BackgroundProperty, "Brush.InputBg");
            tb.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            tb.SetResourceReference(TextBox.CaretBrushProperty, "Brush.Text");
            tb.SetResourceReference(Control.BorderBrushProperty, "Brush.Border");
            return tb;
        }

        private static PasswordBox MakeStyledPasswordBox()
        {
            PasswordBox pb = new PasswordBox
            {
                Padding = new Thickness(10, 7, 10, 7),
                FontSize = 13,
                BorderThickness = new Thickness(1)
            };
            pb.SetResourceReference(Control.BackgroundProperty, "Brush.InputBg");
            pb.SetResourceReference(Control.ForegroundProperty, "Brush.Text");
            pb.SetResourceReference(Control.BorderBrushProperty, "Brush.Border");
            return pb;
        }

        private void ShowToast(string msg)
        {
            if (string.IsNullOrEmpty(msg)) return;
            _toastText.Text = msg;
            _toastBorder.Visibility = Visibility.Visible;
            if (_toastTimer != null) _toastTimer.Stop();
            _toastTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
            _toastTimer.Tick += delegate (object s, EventArgs e)
            {
                _toastTimer.Stop();
                _toastBorder.Visibility = Visibility.Collapsed;
            };
            _toastTimer.Start();
        }
    }
}
