using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace KomaComic.Windows
{
    internal static class Program
    {
        internal const string AppVersion = "1.2.0";

        [STAThread]
        private static int Main(string[] args)
        {
            ConfigureNetworking();

            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            string wwwRoot = Path.Combine(baseDir, "wwwroot");
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
                return RunSmokeTest(baseDir, wwwRoot, assetsDir, dataDir);
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            LocalBackendServer server = new LocalBackendServer(baseDir, wwwRoot, assetsDir, dataDir);
            int port = server.Start(preferredPort: 47820);
            string baseUrl = string.Format("http://127.0.0.1:{0}/", port);

            MainWindow form = new MainWindow(baseDir, baseUrl, server);
            server.AttachForm(form);
            Application.Run(form);

            server.Stop();
            return 0;
        }

        private static void ConfigureNetworking()
        {
            try
            {
                ServicePointManager.DefaultConnectionLimit = 64;
                ServicePointManager.Expect100Continue = false;
                SecurityProtocolType proto = SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11 | SecurityProtocolType.Tls;
                try
                {
                    // SecurityProtocolType.Tls13 = 12288 on Windows 11 / .NET 4.8
                    proto |= (SecurityProtocolType)12288;
                }
                catch
                {
                    // Ignore on older OS builds without TLS 1.3 enum support
                }
                ServicePointManager.SecurityProtocol = proto;
            }
            catch
            {
                // Ignore if restricted by policy
            }
        }

        private static int RunSmokeTest(string baseDir, string wwwRoot, string assetsDir, string dataDir)
        {
            LocalBackendServer server = null;
            try
            {
                server = new LocalBackendServer(baseDir, wwwRoot, assetsDir, dataDir);
                int port = server.Start(preferredPort: 0);
                string rootUrl = string.Format("http://127.0.0.1:{0}/", port);

                JavaScriptSerializer json = new JavaScriptSerializer();
                json.MaxJsonLength = int.MaxValue;

                string healthBody = FetchStringSync(rootUrl + "api/health");
                Dictionary<string, object> health = json.Deserialize<Dictionary<string, object>>(healthBody);
                if (health == null || !Convert.ToBoolean(health["ok"]))
                {
                    Console.Error.WriteLine("SMOKE_TEST_FAIL: /api/health did not return ok=true");
                    return 2;
                }
                int sourceCount = Convert.ToInt32(health["sourceCount"]);
                if (sourceCount < 148)
                {
                    Console.Error.WriteLine("SMOKE_TEST_FAIL: expected >= 148 built-in sources, got " + sourceCount);
                    return 3;
                }

                string builtinBody = FetchStringSync(rootUrl + "assets/sources/builtin_sources.json");
                ArrayList builtinList = json.Deserialize<ArrayList>(builtinBody);
                if (builtinList == null || builtinList.Count < 148)
                {
                    Console.Error.WriteLine("SMOKE_TEST_FAIL: builtin_sources.json count invalid");
                    return 4;
                }

                string[] staticPaths = new string[] { "index.html", "styles.css", "app.js", "assets/venera_shim.js" };
                foreach (string p in staticPaths)
                {
                    string body = FetchStringSync(rootUrl + p);
                    if (string.IsNullOrEmpty(body))
                    {
                        Console.Error.WriteLine("SMOKE_TEST_FAIL: empty asset " + p);
                        return 5;
                    }
                }

                Console.WriteLine(string.Format("SMOKE_TEST_OK (port={0}, sources={1}, version={2})", port, sourceCount, AppVersion));
                return 0;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SMOKE_TEST_FAIL: " + ex.ToString());
                return 1;
            }
            finally
            {
                if (server != null)
                {
                    server.Stop();
                }
            }
        }

        private static string FetchStringSync(string url)
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
            req.Timeout = 5000;
            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            {
                if ((int)resp.StatusCode != 200)
                {
                    throw new InvalidOperationException("Unexpected status " + resp.StatusCode + " for " + url);
                }
                using (StreamReader sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    return sr.ReadToEnd();
                }
            }
        }
    }

    internal sealed class MainWindow : Form
    {
        private readonly string _baseDir;
        private readonly string _baseUrl;
        private readonly LocalBackendServer _server;
        private WebView2 _webView;
        private bool _isFullscreen;
        private FormWindowState _prevWindowState = FormWindowState.Normal;
        private Rectangle _prevBounds;

        public MainWindow(string baseDir, string baseUrl, LocalBackendServer server)
        {
            _baseDir = baseDir;
            _baseUrl = baseUrl;
            _server = server;

            this.Text = "KomaComic (漫格漫画) v" + Program.AppVersion;
            this.Size = new Size(1360, 860);
            this.MinimumSize = new Size(960, 620);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.BackColor = Color.FromArgb(18, 20, 26);

            try
            {
                this.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            }
            catch
            {
                // Ignore if icon cannot be extracted
            }

            InitializeWebView();
        }

        private async void InitializeWebView()
        {
            try
            {
                _webView = new WebView2();
                _webView.Dock = DockStyle.Fill;
                _webView.DefaultBackgroundColor = Color.FromArgb(18, 20, 26);
                this.Controls.Add(_webView);

                string profileDir = Path.Combine(_baseDir, "data", "webview2_profile");
                Directory.CreateDirectory(profileDir);
                CoreWebView2Environment env = await CoreWebView2Environment.CreateAsync(null, profileDir, null);
                await _webView.EnsureCoreWebView2Async(env);

                _webView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = true;
                _webView.CoreWebView2.Settings.IsStatusBarEnabled = false;
                _webView.CoreWebView2.Settings.AreDevToolsEnabled = true;
                _webView.CoreWebView2.NewWindowRequested += OnWebViewNewWindowRequested;
                _webView.CoreWebView2.Navigate(_baseUrl + "index.html");
            }
            catch (Exception ex)
            {
                LaunchEdgeFallback(ex);
            }
        }

        private void OnWebViewNewWindowRequested(object sender, CoreWebView2NewWindowRequestedEventArgs e)
        {
            try
            {
                e.Handled = true;
                if (!string.IsNullOrEmpty(e.Uri) &&
                    (e.Uri.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                     e.Uri.StartsWith("https://", StringComparison.OrdinalIgnoreCase)))
                {
                    if (e.Uri.StartsWith(_baseUrl, StringComparison.OrdinalIgnoreCase))
                    {
                        _webView.CoreWebView2.Navigate(e.Uri);
                    }
                    else
                    {
                        Process.Start(new ProcessStartInfo(e.Uri) { UseShellExecute = true });
                    }
                }
            }
            catch
            {
                // Ignore external launch errors
            }
        }

        private void LaunchEdgeFallback(Exception webViewError)
        {
            string edgePath = FindEdgeExecutable();
            if (!string.IsNullOrEmpty(edgePath))
            {
                try
                {
                    this.WindowState = FormWindowState.Minimized;
                    this.ShowInTaskbar = true;
                    Process proc = Process.Start(new ProcessStartInfo
                    {
                        FileName = edgePath,
                        Arguments = string.Format("--app=\"{0}index.html\" --user-data-dir=\"{1}\"", _baseUrl, Path.Combine(_baseDir, "data", "edge_app_profile")),
                        UseShellExecute = false
                    });
                    if (proc != null)
                    {
                        Thread waiter = new Thread(delegate ()
                        {
                            try
                            {
                                proc.WaitForExit();
                            }
                            catch { }
                            try
                            {
                                this.BeginInvoke(new MethodInvoker(delegate () { this.Close(); }));
                            }
                            catch { }
                        });
                        waiter.IsBackground = true;
                        waiter.Start();
                        return;
                    }
                }
                catch
                {
                    // Fall through to dialog
                }
            }

            MessageBox.Show(
                this,
                "未检测到 Microsoft Edge WebView2 运行时，无法在桌面窗口中渲染界面。\n请安装 WebView2 Runtime 后重试。\n\n详细错误: " + webViewError.Message,
                "KomaComic",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning
            );
            this.Close();
        }

        private static string FindEdgeExecutable()
        {
            string[] candidates = new string[]
            {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Microsoft\Edge\Application\msedge.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Microsoft\Edge\Application\msedge.exe")
            };
            foreach (string path in candidates)
            {
                if (File.Exists(path)) return path;
            }
            return null;
        }

        public bool ToggleFullscreen()
        {
            if (!_isFullscreen)
            {
                _prevWindowState = this.WindowState;
                _prevBounds = this.Bounds;
                this.FormBorderStyle = FormBorderStyle.None;
                this.WindowState = FormWindowState.Normal;
                this.WindowState = FormWindowState.Maximized;
                _isFullscreen = true;
            }
            else
            {
                this.FormBorderStyle = FormBorderStyle.Sizable;
                this.WindowState = _prevWindowState;
                if (_prevWindowState == FormWindowState.Normal && !_prevBounds.IsEmpty)
                {
                    this.Bounds = _prevBounds;
                }
                _isFullscreen = false;
            }
            return _isFullscreen;
        }
    }

    internal sealed class LocalBackendServer
    {
        private static readonly string[] ImageExtensions = new string[]
        {
            ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif"
        };

        private static readonly string[] ArchiveExtensions = new string[]
        {
            ".zip", ".cbz", ".epub"
        };

        private readonly string _baseDir;
        private readonly string _wwwRoot;
        private readonly string _assetsDir;
        private readonly string _dataDir;
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

        private readonly Dictionary<string, LocalComicRecord> _localComicsById = new Dictionary<string, LocalComicRecord>(StringComparer.Ordinal);
        private Dictionary<string, object> _veneraStorage;
        private List<CookieEntry> _cookies;
        private List<string> _warehousePaths;

        private HttpListener _listener;
        private Thread _acceptThread;
        private volatile bool _running;
        private MainWindow _form;

        public LocalBackendServer(string baseDir, string wwwRoot, string assetsDir, string dataDir)
        {
            _baseDir = baseDir;
            _wwwRoot = wwwRoot;
            _assetsDir = assetsDir;
            _dataDir = dataDir;
            _imageCacheDir = Path.Combine(dataDir, "cache", "images");
            Directory.CreateDirectory(_imageCacheDir);

            _stateFile = Path.Combine(dataDir, "app_state.json");
            _veneraStorageFile = Path.Combine(dataDir, "venera_storage.json");
            _cookiesFile = Path.Combine(dataDir, "cookies.json");
            _warehouseFile = Path.Combine(dataDir, "warehouse_paths.json");
            _localLibFile = Path.Combine(dataDir, "local_library.json");

            LoadPersistentStores();
        }

        public void AttachForm(MainWindow form)
        {
            _form = form;
        }

        public int Start(int preferredPort)
        {
            int port = ResolvePort(preferredPort);
            _listener = new HttpListener();
            _listener.Prefixes.Add(string.Format("http://127.0.0.1:{0}/", port));
            _listener.Start();
            _running = true;

            _acceptThread = new Thread(AcceptLoop);
            _acceptThread.IsBackground = true;
            _acceptThread.Start();
            return port;
        }

        public void Stop()
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
            catch
            {
                // Ignore shutdown errors
            }
        }

        private static int ResolvePort(int preferredPort)
        {
            if (preferredPort > 0)
            {
                try
                {
                    TcpListener probe = new TcpListener(IPAddress.Loopback, preferredPort);
                    probe.Start();
                    probe.Stop();
                    return preferredPort;
                }
                catch
                {
                    // Fall back to ephemeral port
                }
            }
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
                    ThreadPool.QueueUserWorkItem(delegate (object state)
                    {
                        HandleContext((HttpListenerContext)state);
                    }, ctx);
                }
                catch
                {
                    if (!_running) break;
                }
            }
        }

        private static JavaScriptSerializer CreateSerializer()
        {
            JavaScriptSerializer ser = new JavaScriptSerializer();
            ser.MaxJsonLength = int.MaxValue;
            ser.RecursionLimit = 256;
            return ser;
        }

        private void HandleContext(HttpListenerContext ctx)
        {
            HttpListenerRequest req = ctx.Request;
            HttpListenerResponse resp = ctx.Response;
            try
            {
                string path = (req.Url.AbsolutePath ?? "/");
                if (path.Equals("/api/health", StringComparison.OrdinalIgnoreCase))
                {
                    HandleHealth(resp);
                    return;
                }
                if (path.Equals("/api/venera/dispatch", StringComparison.OrdinalIgnoreCase) ||
                    path.Equals("/api/venera/async", StringComparison.OrdinalIgnoreCase))
                {
                    HandleVeneraDispatch(req, resp);
                    return;
                }
                if (path.Equals("/api/image", StringComparison.OrdinalIgnoreCase))
                {
                    HandleImageProxy(req, resp);
                    return;
                }
                if (path.Equals("/api/cache/clear", StringComparison.OrdinalIgnoreCase))
                {
                    HandleCacheClear(resp);
                    return;
                }
                if (path.Equals("/api/state", StringComparison.OrdinalIgnoreCase))
                {
                    HandleState(req, resp);
                    return;
                }
                if (path.Equals("/api/warehouse/pick-folder", StringComparison.OrdinalIgnoreCase))
                {
                    HandlePickFolder(resp);
                    return;
                }
                if (path.Equals("/api/warehouse/pick-archive", StringComparison.OrdinalIgnoreCase))
                {
                    HandlePickArchive(resp);
                    return;
                }
                if (path.Equals("/api/warehouse/remove", StringComparison.OrdinalIgnoreCase))
                {
                    HandleRemoveWarehousePath(req, resp);
                    return;
                }
                if (path.Equals("/api/warehouse/rescan", StringComparison.OrdinalIgnoreCase))
                {
                    HandleRescanWarehouse(resp);
                    return;
                }
                if (path.Equals("/api/local/comic", StringComparison.OrdinalIgnoreCase))
                {
                    HandleLocalComicInfo(req, resp);
                    return;
                }
                if (path.Equals("/api/local/page", StringComparison.OrdinalIgnoreCase))
                {
                    HandleLocalComicPage(req, resp);
                    return;
                }
                if (path.Equals("/api/webdav/test", StringComparison.OrdinalIgnoreCase))
                {
                    HandleWebDavTest(req, resp);
                    return;
                }
                if (path.Equals("/api/window/fullscreen", StringComparison.OrdinalIgnoreCase))
                {
                    HandleFullscreen(resp);
                    return;
                }

                HandleStaticFile(path, resp);
            }
            catch (Exception ex)
            {
                try
                {
                    resp.StatusCode = 500;
                    WriteJson(resp, new Dictionary<string, object> { { "error", ex.Message } });
                }
                catch
                {
                    // Ignore broken pipe
                }
            }
            finally
            {
                try { resp.OutputStream.Close(); } catch { }
            }
        }

        private void HandleHealth(HttpListenerResponse resp)
        {
            int sourceCount = 0;
            try
            {
                string manifestPath = Path.Combine(_assetsDir, "sources", "builtin_sources.json");
                if (File.Exists(manifestPath))
                {
                    string raw = File.ReadAllText(manifestPath, Encoding.UTF8);
                    ArrayList arr = CreateSerializer().Deserialize<ArrayList>(raw);
                    if (arr != null) sourceCount = arr.Count;
                }
            }
            catch
            {
                // Ignore
            }
            Dictionary<string, object> payload = new Dictionary<string, object>();
            payload["ok"] = true;
            payload["version"] = Program.AppVersion;
            payload["platform"] = "windows-x64";
            payload["sourceCount"] = sourceCount;
            payload["dataDir"] = _dataDir;
            WriteJson(resp, payload);
        }

        private void HandleStaticFile(string urlPath, HttpListenerResponse resp)
        {
            string clean = urlPath.TrimStart('/');
            if (string.IsNullOrEmpty(clean)) clean = "index.html";

            string baseFolder;
            string relative;
            if (clean.StartsWith("assets/", StringComparison.OrdinalIgnoreCase))
            {
                baseFolder = _assetsDir;
                relative = clean.Substring("assets/".Length);
            }
            else
            {
                baseFolder = _wwwRoot;
                relative = clean;
            }

            relative = relative.Replace('/', Path.DirectorySeparatorChar);
            string fullRoot = Path.GetFullPath(baseFolder);
            string fullTarget = Path.GetFullPath(Path.Combine(fullRoot, relative));

            if (!fullTarget.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase) || !File.Exists(fullTarget))
            {
                resp.StatusCode = 404;
                WriteText(resp, "Not Found", "text/plain; charset=utf-8");
                return;
            }

            byte[] bytes = File.ReadAllBytes(fullTarget);
            resp.StatusCode = 200;
            resp.ContentType = GetMimeType(fullTarget);
            resp.AddHeader("Cache-Control", "no-cache");
            resp.ContentLength64 = bytes.Length;
            resp.OutputStream.Write(bytes, 0, bytes.Length);
        }

        private static string GetMimeType(string filePath)
        {
            string ext = Path.GetExtension(filePath).ToLowerInvariant();
            switch (ext)
            {
                case ".html": return "text/html; charset=utf-8";
                case ".css": return "text/css; charset=utf-8";
                case ".js": return "application/javascript; charset=utf-8";
                case ".json": return "application/json; charset=utf-8";
                case ".png": return "image/png";
                case ".jpg":
                case ".jpeg": return "image/jpeg";
                case ".webp": return "image/webp";
                case ".gif": return "image/gif";
                case ".bmp": return "image/bmp";
                case ".svg": return "image/svg+xml";
                case ".ico": return "image/x-icon";
                default: return "application/octet-stream";
            }
        }

        private void HandleVeneraDispatch(HttpListenerRequest req, HttpListenerResponse resp)
        {
            string body = ReadRequestBody(req);
            JavaScriptSerializer ser = CreateSerializer();
            Dictionary<string, object> msg = ser.Deserialize<Dictionary<string, object>>(body);
            if (msg == null)
            {
                WriteRawJson(resp, "null");
                return;
            }

            string method = GetString(msg, "method");
            string resultJson;
            try
            {
                switch (method)
                {
                    case "http":
                        resultJson = ExecuteVeneraHttp(msg, false, ser);
                        break;
                    case "http_bytes":
                        resultJson = ExecuteVeneraHttp(msg, true, ser);
                        break;
                    case "convert":
                        resultJson = ExecuteVeneraConvert(msg, ser);
                        break;
                    case "cookie":
                        resultJson = ExecuteVeneraCookie(msg, ser);
                        break;
                    case "storage":
                        resultJson = ExecuteVeneraStorage(msg, ser);
                        break;
                    case "ui":
                        resultJson = ExecuteVeneraUi(msg);
                        break;
                    case "ui_dialog":
                        resultJson = ExecuteUiDialog(msg, ser);
                        break;
                    case "ui_input":
                        resultJson = ExecuteUiInput(msg, ser);
                        break;
                    case "ui_select":
                        resultJson = ExecuteUiSelect(msg, ser);
                        break;
                    default:
                        resultJson = "null";
                        break;
                }
            }
            catch (Exception ex)
            {
                Dictionary<string, object> errObj = new Dictionary<string, object>();
                errObj["error"] = ex.Message;
                resultJson = ser.Serialize(errObj);
            }
            WriteRawJson(resp, resultJson);
        }

        private string ExecuteVeneraHttp(Dictionary<string, object> msg, bool asBytes, JavaScriptSerializer ser)
        {
            string httpMethod = GetString(msg, "http_method");
            if (string.IsNullOrEmpty(httpMethod)) httpMethod = "GET";
            httpMethod = httpMethod.ToUpperInvariant();

            string urlStr = GetString(msg, "url");
            if (string.IsNullOrEmpty(urlStr))
            {
                throw new ArgumentException("Missing request URL");
            }

            object headersRaw = null;
            msg.TryGetValue("headers", out headersRaw);
            Dictionary<string, object> headersDict = headersRaw as Dictionary<string, object>;

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
            if (!string.IsNullOrEmpty(jarCookie))
            {
                webReq.Headers["Cookie"] = jarCookie;
            }

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
                    if (dataObj is Dictionary<string, object> || dataObj is ArrayList)
                    {
                        webReq.ContentType = "application/json; charset=utf-8";
                    }
                    else
                    {
                        webReq.ContentType = "application/x-www-form-urlencoded";
                    }
                }
                webReq.ContentLength = bodyBytes.Length;
                using (Stream reqStream = webReq.GetRequestStream())
                {
                    reqStream.Write(bodyBytes, 0, bodyBytes.Length);
                }
            }

            int status = 0;
            Dictionary<string, object> respHeaders = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            byte[] rawBytes = new byte[0];

            HttpWebResponse webResp = null;
            try
            {
                webResp = (HttpWebResponse)webReq.GetResponse();
            }
            catch (WebException wex)
            {
                if (wex.Response is HttpWebResponse)
                {
                    webResp = (HttpWebResponse)wex.Response;
                }
                else
                {
                    throw new InvalidOperationException(wex.Message, wex);
                }
            }

            using (webResp)
            {
                status = (int)webResp.StatusCode;
                List<string> setCookies = new List<string>();
                for (int i = 0; i < webResp.Headers.Count; i++)
                {
                    string hk = webResp.Headers.GetKey(i);
                    string[] hvals = webResp.Headers.GetValues(i);
                    if (string.IsNullOrEmpty(hk) || hvals == null || hvals.Length == 0) continue;
                    if (string.Equals(hk, "Set-Cookie", StringComparison.OrdinalIgnoreCase))
                    {
                        foreach (string sc in hvals)
                        {
                            setCookies.Add(sc);
                            StoreSetCookieHeader(urlStr, sc);
                        }
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
                    using (MemoryStream ms = new MemoryStream())
                    {
                        rs.CopyTo(ms);
                        rawBytes = ms.ToArray();
                    }
                }
            }

            Dictionary<string, object> outObj = new Dictionary<string, object>();
            outObj["status"] = status;
            outObj["headers"] = respHeaders;
            if (asBytes)
            {
                outObj["bodyBase64"] = Convert.ToBase64String(rawBytes);
            }
            else
            {
                outObj["body"] = Encoding.UTF8.GetString(rawBytes);
            }
            return ser.Serialize(outObj);
        }

        private static byte[] ExtractHttpBodyBytes(object dataObj, JavaScriptSerializer ser)
        {
            if (dataObj == null) return null;
            Dictionary<string, object> dict = dataObj as Dictionary<string, object>;
            if (dict != null)
            {
                object abVal = null;
                if (dict.TryGetValue("__ab__", out abVal) && abVal != null)
                {
                    return Convert.FromBase64String(Convert.ToString(abVal));
                }
                return Encoding.UTF8.GetBytes(ser.Serialize(dict));
            }
            ArrayList arr = dataObj as ArrayList;
            if (arr != null)
            {
                return Encoding.UTF8.GetBytes(ser.Serialize(arr));
            }
            return Encoding.UTF8.GetBytes(Convert.ToString(dataObj));
        }

        private static void ApplyHeaderSafe(HttpWebRequest webReq, string key, string value, string jarCookie)
        {
            if (string.IsNullOrEmpty(key)) return;
            string lk = key.ToLowerInvariant();
            if (lk == "content-length" || lk == "connection" || lk == "transfer-encoding" || lk == "expect" || lk == "accept-encoding")
            {
                return;
            }
            if (lk == "user-agent")
            {
                if (!string.IsNullOrEmpty(value)) webReq.UserAgent = value;
                return;
            }
            if (lk == "referer")
            {
                webReq.Referer = value;
                return;
            }
            if (lk == "accept")
            {
                webReq.Accept = value;
                return;
            }
            if (lk == "content-type")
            {
                webReq.ContentType = value;
                return;
            }
            if (lk == "host")
            {
                if (!string.IsNullOrEmpty(value)) webReq.Host = value;
                return;
            }
            if (lk == "cookie")
            {
                if (!string.IsNullOrEmpty(value))
                {
                    webReq.Headers["Cookie"] = string.IsNullOrEmpty(jarCookie) ? value : (jarCookie + "; " + value);
                }
                return;
            }
            try
            {
                webReq.Headers[key] = value;
            }
            catch
            {
                // Ignore restricted headers
            }
        }

        private string ExecuteVeneraConvert(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            string type = GetString(msg, "type");
            bool isEncode = true;
            object isEncObj = null;
            if (msg.TryGetValue("isEncode", out isEncObj) && isEncObj != null)
            {
                isEncode = Convert.ToBoolean(isEncObj);
            }

            object valObj = null;
            msg.TryGetValue("value", out valObj);
            byte[] inputBytes = ExtractBinaryField(valObj);

            if (type == "gbk")
            {
                Encoding gbk = Encoding.GetEncoding(936);
                if (isEncode)
                {
                    byte[] enc = gbk.GetBytes(valObj == null ? "" : Convert.ToString(valObj));
                    return WrapAb(enc, ser);
                }
                else
                {
                    return ser.Serialize(gbk.GetString(inputBytes));
                }
            }

            if (type == "md5" || type == "sha1" || type == "sha256" || type == "sha512")
            {
                byte[] hashBytes;
                using (HashAlgorithm alg = CreateHashAlgorithm(type))
                {
                    hashBytes = alg.ComputeHash(inputBytes);
                }
                return WrapAb(hashBytes, ser);
            }

            if (type == "hmac")
            {
                string hashName = GetString(msg, "hash").ToLowerInvariant();
                object keyObj = null;
                msg.TryGetValue("key", out keyObj);
                byte[] keyBytes = ExtractBinaryField(keyObj);

                byte[] macBytes;
                using (HMAC hmac = CreateHmacAlgorithm(hashName, keyBytes))
                {
                    macBytes = hmac.ComputeHash(inputBytes);
                }

                bool isString = false;
                object isStrObj = null;
                if (msg.TryGetValue("isString", out isStrObj) && isStrObj != null)
                {
                    isString = Convert.ToBoolean(isStrObj);
                }
                if (isString)
                {
                    StringBuilder sb = new StringBuilder(macBytes.Length * 2);
                    foreach (byte b in macBytes) sb.Append(b.ToString("x2"));
                    return ser.Serialize(sb.ToString());
                }
                return WrapAb(macBytes, ser);
            }

            if (type == "aes-ecb")
            {
                object keyObj = null;
                msg.TryGetValue("key", out keyObj);
                byte[] keyBytes = ExtractBinaryField(keyObj);
                byte[] decrypted = DecryptAes(inputBytes, keyBytes, null, CipherMode.ECB);
                return WrapAb(decrypted, ser);
            }

            if (type == "aes-cbc")
            {
                object keyObj = null;
                msg.TryGetValue("key", out keyObj);
                byte[] keyBytes = ExtractBinaryField(keyObj);

                object ivObj = null;
                msg.TryGetValue("iv", out ivObj);
                byte[] ivBytes = ExtractBinaryField(ivObj);

                byte[] decrypted = DecryptAes(inputBytes, keyBytes, ivBytes, CipherMode.CBC);
                return WrapAb(decrypted, ser);
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
                if (dict.TryGetValue("__ab__", out ab) && ab != null)
                {
                    return Convert.FromBase64String(Convert.ToString(ab));
                }
                return new byte[0];
            }
            string s = field as string;
            if (s != null) return Encoding.UTF8.GetBytes(s);
            return new byte[0];
        }

        private static HashAlgorithm CreateHashAlgorithm(string type)
        {
            switch (type)
            {
                case "md5": return MD5.Create();
                case "sha1": return SHA1.Create();
                case "sha512": return SHA512.Create();
                default: return SHA256.Create();
            }
        }

        private static HMAC CreateHmacAlgorithm(string hashName, byte[] key)
        {
            switch (hashName)
            {
                case "md5": return new HMACMD5(key);
                case "sha1": return new HMACSHA1(key);
                case "sha512": return new HMACSHA512(key);
                default: return new HMACSHA256(key);
            }
        }

        private static byte[] DecryptAes(byte[] cipherBytes, byte[] keyBytes, byte[] ivBytes, CipherMode mode)
        {
            using (AesCryptoServiceProvider aes = new AesCryptoServiceProvider())
            {
                aes.Mode = mode;
                aes.Padding = PaddingMode.PKCS7;
                aes.Key = keyBytes;
                if (ivBytes != null && ivBytes.Length > 0)
                {
                    aes.IV = ivBytes;
                }
                using (ICryptoTransform decryptor = aes.CreateDecryptor())
                {
                    return decryptor.TransformFinalBlock(cipherBytes, 0, cipherBytes.Length);
                }
            }
        }

        private static string WrapAb(byte[] bytes, JavaScriptSerializer ser)
        {
            Dictionary<string, object> obj = new Dictionary<string, object>();
            obj["__ab__"] = Convert.ToBase64String(bytes);
            return ser.Serialize(obj);
        }

        private string ExecuteVeneraCookie(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            string fn = GetString(msg, "function");
            string url = GetString(msg, "url");
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
                            string name = GetString(c, "name");
                            if (string.IsNullOrEmpty(name)) continue;
                            string val = GetString(c, "value");
                            string domain = GetString(c, "domain");
                            if (string.IsNullOrEmpty(domain)) domain = host;
                            string path = GetString(c, "path");
                            if (string.IsNullOrEmpty(path)) path = "/";
                            UpsertCookie(name, val, domain, path);
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
                            Dictionary<string, object> item = new Dictionary<string, object>();
                            item["name"] = ce.Name;
                            item["value"] = ce.Value;
                            item["domain"] = ce.Domain;
                            item["path"] = ce.Path;
                            result.Add(item);
                        }
                    }
                    return ser.Serialize(result);
                }
                if (fn == "delete")
                {
                    if (string.IsNullOrEmpty(host))
                    {
                        _cookies.Clear();
                    }
                    else
                    {
                        _cookies.RemoveAll(delegate (CookieEntry ce) { return CookieMatchesHost(ce.Domain, host); });
                    }
                    SaveCookies();
                    return "null";
                }
            }
            return "null";
        }

        private string ExecuteVeneraStorage(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            string fn = GetString(msg, "function");
            string sourceKey = GetString(msg, "sourceKey");
            string key = GetString(msg, "key");
            object valObj = null;
            msg.TryGetValue("value", out valObj);

            lock (_veneraLock)
            {
                Dictionary<string, object> bucket = GetOrCreateStorageBucket(fn.EndsWith("Setting", StringComparison.Ordinal) ? "settings" : "data", sourceKey);
                switch (fn)
                {
                    case "loadSetting":
                    case "loadData":
                        object found = null;
                        if (!bucket.TryGetValue(key, out found) || found == null) return "null";
                        return ser.Serialize(found);
                    case "saveSetting":
                    case "saveData":
                        if (valObj == null)
                        {
                            bucket.Remove(key);
                        }
                        else
                        {
                            bucket[key] = valObj;
                        }
                        SaveVeneraStorage();
                        return "null";
                    case "deleteData":
                        bucket.Remove(key);
                        SaveVeneraStorage();
                        return "null";
                    default:
                        return "null";
                }
            }
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

        private static string ExecuteVeneraUi(Dictionary<string, object> msg)
        {
            string fn = GetString(msg, "function");
            if (fn == "launchUrl")
            {
                string url = GetString(msg, "url");
                if (url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                    url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                {
                    try
                    {
                        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
                    }
                    catch { }
                }
            }
            return "null";
        }

        private string ExecuteUiDialog(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            int clicked = -1;
            if (_form != null && !_form.IsDisposed)
            {
                string title = GetString(msg, "title");
                string content = GetString(msg, "message");
                DialogResult dr = (DialogResult)_form.Invoke(new Func<DialogResult>(delegate ()
                {
                    return MessageBox.Show(_form, content, string.IsNullOrEmpty(title) ? "提示" : title, MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
                }));
                clicked = (dr == DialogResult.OK) ? 0 : -1;
            }
            Dictionary<string, object> res = new Dictionary<string, object>();
            res["clickedIndex"] = clicked;
            return ser.Serialize(res);
        }

        private string ExecuteUiInput(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            Dictionary<string, object> res = new Dictionary<string, object>();
            if (_form == null || _form.IsDisposed)
            {
                res["cancelled"] = true;
                return ser.Serialize(res);
            }

            string title = GetString(msg, "title");
            string entered = (string)_form.Invoke(new Func<string>(delegate ()
            {
                return PromptDialog.ShowInput(_form, string.IsNullOrEmpty(title) ? "输入" : title);
            }));
            if (entered == null)
            {
                res["cancelled"] = true;
            }
            else
            {
                res["cancelled"] = false;
                res["value"] = entered;
            }
            return ser.Serialize(res);
        }

        private string ExecuteUiSelect(Dictionary<string, object> msg, JavaScriptSerializer ser)
        {
            Dictionary<string, object> res = new Dictionary<string, object>();
            if (_form == null || _form.IsDisposed)
            {
                res["index"] = null;
                return ser.Serialize(res);
            }

            string title = GetString(msg, "title");
            object optsRaw = null;
            msg.TryGetValue("options", out optsRaw);
            ArrayList optsArr = optsRaw as ArrayList;
            List<string> options = new List<string>();
            if (optsArr != null)
            {
                foreach (object o in optsArr) options.Add(Convert.ToString(o));
            }
            int selected = (int)_form.Invoke(new Func<int>(delegate ()
            {
                return PromptDialog.ShowSelect(_form, string.IsNullOrEmpty(title) ? "选择" : title, options);
            }));
            res["index"] = selected >= 0 ? (object)selected : null;
            return ser.Serialize(res);
        }

        private void HandleImageProxy(HttpListenerRequest req, HttpListenerResponse resp)
        {
            string targetUrl = req.QueryString["url"];
            if (string.IsNullOrEmpty(targetUrl))
            {
                resp.StatusCode = 400;
                return;
            }

            string headersParam = req.QueryString["headers"];
            string cacheKey = ComputeSha256Hex(targetUrl + "|" + (headersParam ?? ""));
            string cachedPath = Path.Combine(_imageCacheDir, cacheKey);
            if (File.Exists(cachedPath))
            {
                byte[] cachedBytes = File.ReadAllBytes(cachedPath);
                if (cachedBytes.Length > 0)
                {
                    resp.StatusCode = 200;
                    resp.ContentType = DetectImageContentType(cachedBytes, targetUrl);
                    resp.AddHeader("Cache-Control", "public, max-age=604800");
                    resp.ContentLength64 = cachedBytes.Length;
                    resp.OutputStream.Write(cachedBytes, 0, cachedBytes.Length);
                    return;
                }
            }

            JavaScriptSerializer ser = CreateSerializer();
            Dictionary<string, object> customHeaders = null;
            if (!string.IsNullOrEmpty(headersParam))
            {
                try { customHeaders = ser.Deserialize<Dictionary<string, object>>(headersParam); } catch { }
            }

            HttpWebRequest imgReq = (HttpWebRequest)WebRequest.Create(targetUrl);
            imgReq.Method = "GET";
            imgReq.Timeout = 18000;
            imgReq.AllowAutoRedirect = true;
            imgReq.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            imgReq.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

            string jarCookie = GetCookieHeaderForUrl(targetUrl);
            if (!string.IsNullOrEmpty(jarCookie))
            {
                imgReq.Headers["Cookie"] = jarCookie;
            }
            if (customHeaders != null)
            {
                foreach (KeyValuePair<string, object> kv in customHeaders)
                {
                    ApplyHeaderSafe(imgReq, kv.Key, kv.Value == null ? "" : Convert.ToString(kv.Value), jarCookie);
                }
            }

            using (HttpWebResponse imgResp = (HttpWebResponse)imgReq.GetResponse())
            {
                byte[] imgBytes;
                using (Stream rs = imgResp.GetResponseStream())
                using (MemoryStream ms = new MemoryStream())
                {
                    if (rs != null) rs.CopyTo(ms);
                    imgBytes = ms.ToArray();
                }

                string ct = !string.IsNullOrEmpty(imgResp.ContentType) && imgResp.ContentType.StartsWith("image/", StringComparison.OrdinalIgnoreCase)
                    ? imgResp.ContentType
                    : DetectImageContentType(imgBytes, targetUrl);

                if (imgBytes.Length > 32)
                {
                    try { File.WriteAllBytes(cachedPath, imgBytes); } catch { }
                }

                resp.StatusCode = 200;
                resp.ContentType = ct;
                resp.AddHeader("Cache-Control", "public, max-age=604800");
                resp.ContentLength64 = imgBytes.Length;
                resp.OutputStream.Write(imgBytes, 0, imgBytes.Length);
            }
        }

        private void HandleCacheClear(HttpListenerResponse resp)
        {
            long clearedBytes = 0;
            int clearedFiles = 0;
            if (Directory.Exists(_imageCacheDir))
            {
                string[] files = Directory.GetFiles(_imageCacheDir);
                foreach (string f in files)
                {
                    try
                    {
                        FileInfo fi = new FileInfo(f);
                        clearedBytes += fi.Length;
                        File.Delete(f);
                        clearedFiles++;
                    }
                    catch { }
                }
            }
            Dictionary<string, object> outObj = new Dictionary<string, object>();
            outObj["clearedBytes"] = clearedBytes;
            outObj["clearedFiles"] = clearedFiles;
            WriteJson(resp, outObj);
        }

        private void HandleState(HttpListenerRequest req, HttpListenerResponse resp)
        {
            lock (_stateLock)
            {
                if (string.Equals(req.HttpMethod, "GET", StringComparison.OrdinalIgnoreCase))
                {
                    string raw = File.Exists(_stateFile) ? File.ReadAllText(_stateFile, Encoding.UTF8) : "{}";
                    if (string.IsNullOrWhiteSpace(raw)) raw = "{}";
                    WriteRawJson(resp, raw);
                    return;
                }
                if (string.Equals(req.HttpMethod, "POST", StringComparison.OrdinalIgnoreCase))
                {
                    string body = ReadRequestBody(req);
                    if (string.IsNullOrWhiteSpace(body)) body = "{}";
                    File.WriteAllText(_stateFile, body, Encoding.UTF8);
                    WriteRawJson(resp, "{\"ok\":true}");
                    return;
                }
            }
            resp.StatusCode = 405;
        }

        private void HandlePickFolder(HttpListenerResponse resp)
        {
            if (_form == null || _form.IsDisposed)
            {
                WriteJson(resp, BuildWarehousePayload(false));
                return;
            }

            string picked = (string)_form.Invoke(new Func<string>(delegate ()
            {
                using (FolderBrowserDialog dlg = new FolderBrowserDialog())
                {
                    dlg.Description = "选择包含漫画文件夹或 ZIP/CBZ/EPUB 的本地目录";
                    dlg.ShowNewFolderButton = false;
                    if (dlg.ShowDialog(_form) == DialogResult.OK)
                    {
                        return dlg.SelectedPath;
                    }
                    return null;
                }
            }));

            if (!string.IsNullOrEmpty(picked))
            {
                lock (_warehouseLock)
                {
                    AddWarehousePathIfMissing(picked);
                    RescanLocalLibraryLocked();
                }
                WriteJson(resp, BuildWarehousePayload(false));
            }
            else
            {
                WriteJson(resp, BuildWarehousePayload(true));
            }
        }

        private void HandlePickArchive(HttpListenerResponse resp)
        {
            if (_form == null || _form.IsDisposed)
            {
                WriteJson(resp, BuildWarehousePayload(false));
                return;
            }

            string[] pickedFiles = (string[])_form.Invoke(new Func<string[]>(delegate ()
            {
                using (OpenFileDialog dlg = new OpenFileDialog())
                {
                    dlg.Title = "选择漫画压缩包 (ZIP / CBZ / EPUB)";
                    dlg.Filter = "漫画压缩包 (*.zip;*.cbz;*.epub)|*.zip;*.cbz;*.epub|所有文件 (*.*)|*.*";
                    dlg.Multiselect = true;
                    if (dlg.ShowDialog(_form) == DialogResult.OK)
                    {
                        return dlg.FileNames;
                    }
                    return new string[0];
                }
            }));

            if (pickedFiles != null && pickedFiles.Length > 0)
            {
                lock (_warehouseLock)
                {
                    foreach (string f in pickedFiles) AddWarehousePathIfMissing(f);
                    RescanLocalLibraryLocked();
                }
                WriteJson(resp, BuildWarehousePayload(false));
            }
            else
            {
                WriteJson(resp, BuildWarehousePayload(true));
            }
        }

        private void HandleRemoveWarehousePath(HttpListenerRequest req, HttpListenerResponse resp)
        {
            string body = ReadRequestBody(req);
            Dictionary<string, object> obj = CreateSerializer().Deserialize<Dictionary<string, object>>(body);
            string target = GetString(obj, "path");
            lock (_warehouseLock)
            {
                _warehousePaths.RemoveAll(delegate (string p)
                {
                    return string.Equals(p, target, StringComparison.OrdinalIgnoreCase);
                });
                SaveWarehousePaths();
                RescanLocalLibraryLocked();
            }
            WriteJson(resp, BuildWarehousePayload(false));
        }

        private void HandleRescanWarehouse(HttpListenerResponse resp)
        {
            lock (_warehouseLock)
            {
                RescanLocalLibraryLocked();
            }
            WriteJson(resp, BuildWarehousePayload(false));
        }

        private void HandleLocalComicInfo(HttpListenerRequest req, HttpListenerResponse resp)
        {
            string id = req.QueryString["id"];
            LocalComicRecord rec = FindLocalComic(id);
            if (rec == null)
            {
                resp.StatusCode = 404;
                WriteJson(resp, new Dictionary<string, object> { { "error", "未找到本地漫画" } });
                return;
            }

            List<string> entries = GetLocalPageEntries(rec);
            Dictionary<string, object> outObj = new Dictionary<string, object>();
            outObj["id"] = rec.Id;
            outObj["title"] = rec.Title;
            outObj["format"] = rec.Format;
            outObj["path"] = rec.Path;
            outObj["pageCount"] = entries.Count;
            WriteJson(resp, outObj);
        }

        private void HandleLocalComicPage(HttpListenerRequest req, HttpListenerResponse resp)
        {
            string id = req.QueryString["id"];
            int pageIdx = 0;
            int.TryParse(req.QueryString["page"], out pageIdx);
            LocalComicRecord rec = FindLocalComic(id);
            if (rec == null)
            {
                resp.StatusCode = 404;
                return;
            }

            List<string> entries = GetLocalPageEntries(rec);
            if (pageIdx < 0 || pageIdx >= entries.Count)
            {
                resp.StatusCode = 404;
                return;
            }

            string targetEntry = entries[pageIdx];
            byte[] pageBytes;
            if (rec.Format == "FOLDER")
            {
                pageBytes = File.ReadAllBytes(targetEntry);
            }
            else
            {
                using (ZipArchive zip = ZipFile.OpenRead(rec.Path))
                {
                    ZipArchiveEntry ze = zip.GetEntry(targetEntry);
                    if (ze == null)
                    {
                        resp.StatusCode = 404;
                        return;
                    }
                    using (Stream s = ze.Open())
                    using (MemoryStream ms = new MemoryStream())
                    {
                        s.CopyTo(ms);
                        pageBytes = ms.ToArray();
                    }
                }
            }

            resp.StatusCode = 200;
            resp.ContentType = DetectImageContentType(pageBytes, targetEntry);
            resp.ContentLength64 = pageBytes.Length;
            resp.OutputStream.Write(pageBytes, 0, pageBytes.Length);
        }

        private void HandleWebDavTest(HttpListenerRequest req, HttpListenerResponse resp)
        {
            string body = ReadRequestBody(req);
            Dictionary<string, object> cfg = CreateSerializer().Deserialize<Dictionary<string, object>>(body);
            string url = GetString(cfg, "url");
            string user = GetString(cfg, "username");
            string pass = GetString(cfg, "password");

            if (string.IsNullOrWhiteSpace(url))
            {
                WriteJson(resp, new Dictionary<string, object>
                {
                    { "ok", false },
                    { "status", 0 },
                    { "message", "请输入 WebDAV 服务器地址" }
                });
                return;
            }

            try
            {
                HttpWebRequest webReq = (HttpWebRequest)WebRequest.Create(url);
                webReq.Method = "PROPFIND";
                webReq.Headers["Depth"] = "0";
                webReq.Timeout = 10000;
                if (!string.IsNullOrEmpty(user) || !string.IsNullOrEmpty(pass))
                {
                    string token = Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + pass));
                    webReq.Headers["Authorization"] = "Basic " + token;
                }
                using (HttpWebResponse webResp = (HttpWebResponse)webReq.GetResponse())
                {
                    int code = (int)webResp.StatusCode;
                    WriteJson(resp, new Dictionary<string, object>
                    {
                        { "ok", code >= 200 && code < 400 },
                        { "status", code },
                        { "message", "连接成功 (HTTP " + code + ")" }
                    });
                }
            }
            catch (WebException wex)
            {
                int code = wex.Response is HttpWebResponse ? (int)((HttpWebResponse)wex.Response).StatusCode : 0;
                WriteJson(resp, new Dictionary<string, object>
                {
                    { "ok", false },
                    { "status", code },
                    { "message", "连接失败: " + wex.Message }
                });
            }
        }

        private void HandleFullscreen(HttpListenerResponse resp)
        {
            bool full = false;
            if (_form != null && !_form.IsDisposed)
            {
                full = (bool)_form.Invoke(new Func<bool>(delegate () { return _form.ToggleFullscreen(); }));
            }
            WriteJson(resp, new Dictionary<string, object> { { "fullscreen", full } });
        }

        private LocalComicRecord FindLocalComic(string id)
        {
            if (string.IsNullOrEmpty(id)) return null;
            lock (_warehouseLock)
            {
                LocalComicRecord rec = null;
                _localComicsById.TryGetValue(id, out rec);
                return rec;
            }
        }

        private List<string> GetLocalPageEntries(LocalComicRecord rec)
        {
            List<string> list = new List<string>();
            if (rec.Format == "FOLDER")
            {
                if (Directory.Exists(rec.Path))
                {
                    foreach (string f in Directory.GetFiles(rec.Path))
                    {
                        if (IsImageFile(f)) list.Add(f);
                    }
                    list.Sort(NaturalComparer.Instance);
                }
            }
            else if (File.Exists(rec.Path))
            {
                using (ZipArchive zip = ZipFile.OpenRead(rec.Path))
                {
                    foreach (ZipArchiveEntry entry in zip.Entries)
                    {
                        if (!string.IsNullOrEmpty(entry.Name) && IsImageFile(entry.FullName))
                        {
                            list.Add(entry.FullName);
                        }
                    }
                }
                list.Sort(NaturalComparer.Instance);
            }
            return list;
        }

        private void AddWarehousePathIfMissing(string path)
        {
            foreach (string existing in _warehousePaths)
            {
                if (string.Equals(existing, path, StringComparison.OrdinalIgnoreCase)) return;
            }
            _warehousePaths.Add(path);
            SaveWarehousePaths();
        }

        private void RescanLocalLibraryLocked()
        {
            _localComicsById.Clear();
            foreach (string root in _warehousePaths)
            {
                if (File.Exists(root) && IsArchiveFile(root))
                {
                    TryIndexArchive(root);
                }
                else if (Directory.Exists(root))
                {
                    ScanDirectoryForComics(root, depth: 0);
                }
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
                    string id = "local_" + ComputeSha256Hex("FOLDER|" + Path.GetFullPath(dir)).Substring(0, 16);
                    LocalComicRecord rec = new LocalComicRecord();
                    rec.Id = id;
                    rec.Title = Path.GetFileName(dir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
                    if (string.IsNullOrEmpty(rec.Title)) rec.Title = dir;
                    rec.Format = "FOLDER";
                    rec.Path = Path.GetFullPath(dir);
                    rec.PageCount = imgCount;
                    _localComicsById[id] = rec;
                }

                string[] subDirs = Directory.GetDirectories(dir);
                foreach (string sub in subDirs)
                {
                    string name = Path.GetFileName(sub);
                    if (name.StartsWith(".")) continue;
                    ScanDirectoryForComics(sub, depth + 1);
                }
            }
            catch
            {
                // Skip inaccessible folders
            }
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
                        if (!string.IsNullOrEmpty(entry.Name) && IsImageFile(entry.FullName))
                        {
                            pageCount++;
                        }
                    }
                }
                if (pageCount > 0)
                {
                    string full = Path.GetFullPath(archivePath);
                    string id = "local_" + ComputeSha256Hex("ARCHIVE|" + full).Substring(0, 16);
                    LocalComicRecord rec = new LocalComicRecord();
                    rec.Id = id;
                    rec.Title = Path.GetFileNameWithoutExtension(full);
                    rec.Format = "ARCHIVE";
                    rec.Path = full;
                    rec.PageCount = pageCount;
                    _localComicsById[id] = rec;
                }
            }
            catch
            {
                // Ignore corrupt archives
            }
        }

        private Dictionary<string, object> BuildWarehousePayload(bool cancelled)
        {
            lock (_warehouseLock)
            {
                List<Dictionary<string, object>> comics = new List<Dictionary<string, object>>();
                foreach (LocalComicRecord rec in _localComicsById.Values)
                {
                    Dictionary<string, object> item = new Dictionary<string, object>();
                    item["id"] = rec.Id;
                    item["title"] = rec.Title;
                    item["format"] = rec.Format;
                    item["path"] = rec.Path;
                    item["pageCount"] = rec.PageCount;
                    item["coverUrl"] = string.Format("/api/local/page?id={0}&page=0", Uri.EscapeDataString(rec.Id));
                    comics.Add(item);
                }
                Dictionary<string, object> payload = new Dictionary<string, object>();
                payload["cancelled"] = cancelled;
                payload["paths"] = _warehousePaths.ToArray();
                payload["comics"] = comics;
                return payload;
            }
        }

        private void LoadPersistentStores()
        {
            JavaScriptSerializer ser = CreateSerializer();
            _veneraStorage = new Dictionary<string, object>(StringComparer.Ordinal);
            if (File.Exists(_veneraStorageFile))
            {
                try
                {
                    Dictionary<string, object> loaded = ser.Deserialize<Dictionary<string, object>>(File.ReadAllText(_veneraStorageFile, Encoding.UTF8));
                    if (loaded != null) _veneraStorage = loaded;
                }
                catch { }
            }

            _cookies = new List<CookieEntry>();
            if (File.Exists(_cookiesFile))
            {
                try
                {
                    List<CookieEntry> loadedCookies = ser.Deserialize<List<CookieEntry>>(File.ReadAllText(_cookiesFile, Encoding.UTF8));
                    if (loadedCookies != null) _cookies = loadedCookies;
                }
                catch { }
            }

            _warehousePaths = new List<string>();
            if (File.Exists(_warehouseFile))
            {
                try
                {
                    List<string> loadedPaths = ser.Deserialize<List<string>>(File.ReadAllText(_warehouseFile, Encoding.UTF8));
                    if (loadedPaths != null) _warehousePaths = loadedPaths;
                }
                catch { }
            }

            if (File.Exists(_localLibFile))
            {
                try
                {
                    List<LocalComicRecord> loadedLib = ser.Deserialize<List<LocalComicRecord>>(File.ReadAllText(_localLibFile, Encoding.UTF8));
                    if (loadedLib != null)
                    {
                        foreach (LocalComicRecord r in loadedLib)
                        {
                            if (!string.IsNullOrEmpty(r.Id)) _localComicsById[r.Id] = r;
                        }
                    }
                }
                catch { }
            }
        }

        private void SaveVeneraStorage()
        {
            try
            {
                File.WriteAllText(_veneraStorageFile, CreateSerializer().Serialize(_veneraStorage), Encoding.UTF8);
            }
            catch { }
        }

        private void SaveCookies()
        {
            try
            {
                File.WriteAllText(_cookiesFile, CreateSerializer().Serialize(_cookies), Encoding.UTF8);
            }
            catch { }
        }

        private void SaveWarehousePaths()
        {
            try
            {
                File.WriteAllText(_warehouseFile, CreateSerializer().Serialize(_warehousePaths), Encoding.UTF8);
            }
            catch { }
        }

        private void SaveLocalLibrary()
        {
            try
            {
                List<LocalComicRecord> list = new List<LocalComicRecord>(_localComicsById.Values);
                File.WriteAllText(_localLibFile, CreateSerializer().Serialize(list), Encoding.UTF8);
            }
            catch { }
        }

        private string GetCookieHeaderForUrl(string url)
        {
            Uri uri = null;
            try { uri = new Uri(url); } catch { return ""; }
            string host = uri.Host;
            lock (_cookieLock)
            {
                List<string> pairs = new List<string>();
                foreach (CookieEntry ce in _cookies)
                {
                    if (CookieMatchesHost(ce.Domain, host))
                    {
                        pairs.Add(ce.Name + "=" + ce.Value);
                    }
                }
                return string.Join("; ", pairs.ToArray());
            }
        }

        private void StoreSetCookieHeader(string url, string setCookieHeader)
        {
            if (string.IsNullOrEmpty(setCookieHeader)) return;
            Uri uri = null;
            try { uri = new Uri(url); } catch { }
            string defaultHost = uri != null ? uri.Host : "";

            string[] parts = setCookieHeader.Split(';');
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
                if (attr.StartsWith("domain=", StringComparison.OrdinalIgnoreCase))
                {
                    domain = attr.Substring(7).Trim().TrimStart('.');
                }
                else if (attr.StartsWith("path=", StringComparison.OrdinalIgnoreCase))
                {
                    path = attr.Substring(5).Trim();
                }
            }

            lock (_cookieLock)
            {
                UpsertCookie(name, value, domain, path);
            }
        }

        private void UpsertCookie(string name, string value, string domain, string path)
        {
            for (int i = 0; i < _cookies.Count; i++)
            {
                CookieEntry ce = _cookies[i];
                if (string.Equals(ce.Name, name, StringComparison.Ordinal) &&
                    string.Equals(ce.Domain, domain, StringComparison.OrdinalIgnoreCase))
                {
                    ce.Value = value;
                    ce.Path = path;
                    return;
                }
            }
            CookieEntry created = new CookieEntry();
            created.Name = name;
            created.Value = value;
            created.Domain = domain;
            created.Path = path;
            _cookies.Add(created);
        }

        private static bool CookieMatchesHost(string cookieDomain, string requestHost)
        {
            if (string.IsNullOrEmpty(cookieDomain) || string.IsNullOrEmpty(requestHost)) return false;
            string cd = cookieDomain.TrimStart('.').ToLowerInvariant();
            string rh = requestHost.ToLowerInvariant();
            return rh == cd || rh.EndsWith("." + cd);
        }

        private static bool IsImageFile(string filePath)
        {
            string ext = Path.GetExtension(filePath).ToLowerInvariant();
            foreach (string candidate in ImageExtensions)
            {
                if (ext == candidate) return true;
            }
            return false;
        }

        private static bool IsArchiveFile(string filePath)
        {
            string ext = Path.GetExtension(filePath).ToLowerInvariant();
            foreach (string candidate in ArchiveExtensions)
            {
                if (ext == candidate) return true;
            }
            return false;
        }

        private static string DetectImageContentType(byte[] data, string fallbackUrl)
        {
            if (data != null && data.Length >= 12)
            {
                if (data[0] == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) return "image/png";
                if (data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF) return "image/jpeg";
                if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46) return "image/gif";
                if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46 &&
                    data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50) return "image/webp";
                if (data[0] == 0x42 && data[1] == 0x4D) return "image/bmp";
            }
            string lower = (fallbackUrl ?? "").ToLowerInvariant();
            if (lower.Contains(".png")) return "image/png";
            if (lower.Contains(".webp")) return "image/webp";
            if (lower.Contains(".gif")) return "image/gif";
            if (lower.Contains(".bmp")) return "image/bmp";
            return "image/jpeg";
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

        private static string ReadRequestBody(HttpListenerRequest req)
        {
            using (StreamReader sr = new StreamReader(req.InputStream, req.ContentEncoding ?? Encoding.UTF8))
            {
                return sr.ReadToEnd();
            }
        }

        private static string GetString(Dictionary<string, object> dict, string key)
        {
            if (dict == null) return "";
            object v = null;
            if (dict.TryGetValue(key, out v) && v != null)
            {
                return Convert.ToString(v);
            }
            return "";
        }

        private static void WriteJson(HttpListenerResponse resp, object obj)
        {
            WriteRawJson(resp, CreateSerializer().Serialize(obj));
        }

        private static void WriteRawJson(HttpListenerResponse resp, string json)
        {
            WriteText(resp, json, "application/json; charset=utf-8");
        }

        private static void WriteText(HttpListenerResponse resp, string text, string contentType)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(text ?? "");
            resp.StatusCode = resp.StatusCode == 0 ? 200 : resp.StatusCode;
            resp.ContentType = contentType;
            resp.ContentLength64 = bytes.Length;
            resp.OutputStream.Write(bytes, 0, bytes.Length);
        }
    }

    internal sealed class CookieEntry
    {
        public string Name { get; set; }
        public string Value { get; set; }
        public string Domain { get; set; }
        public string Path { get; set; }
    }

    internal sealed class LocalComicRecord
    {
        public string Id { get; set; }
        public string Title { get; set; }
        public string Format { get; set; }
        public string Path { get; set; }
        public int PageCount { get; set; }
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

    internal static class PromptDialog
    {
        public static string ShowInput(IWin32Window owner, string title)
        {
            using (Form dlg = new Form())
            {
                dlg.Text = title;
                dlg.Size = new Size(400, 155);
                dlg.FormBorderStyle = FormBorderStyle.FixedDialog;
                dlg.MaximizeBox = false;
                dlg.MinimizeBox = false;
                dlg.StartPosition = FormStartPosition.CenterParent;

                TextBox tb = new TextBox { Left = 16, Top = 18, Width = 350 };
                Button ok = new Button { Text = "确定", Left = 200, Width = 78, Top = 62, DialogResult = DialogResult.OK };
                Button cancel = new Button { Text = "取消", Left = 288, Width = 78, Top = 62, DialogResult = DialogResult.Cancel };
                dlg.Controls.Add(tb);
                dlg.Controls.Add(ok);
                dlg.Controls.Add(cancel);
                dlg.AcceptButton = ok;
                dlg.CancelButton = cancel;

                return dlg.ShowDialog(owner) == DialogResult.OK ? tb.Text : null;
            }
        }

        public static int ShowSelect(IWin32Window owner, string title, List<string> options)
        {
            using (Form dlg = new Form())
            {
                dlg.Text = title;
                dlg.Size = new Size(380, 320);
                dlg.FormBorderStyle = FormBorderStyle.FixedDialog;
                dlg.MaximizeBox = false;
                dlg.MinimizeBox = false;
                dlg.StartPosition = FormStartPosition.CenterParent;

                ListBox lb = new ListBox { Left = 14, Top = 12, Width = 336, Height = 210 };
                foreach (string opt in options) lb.Items.Add(opt);
                if (lb.Items.Count > 0) lb.SelectedIndex = 0;

                Button ok = new Button { Text = "确定", Left = 184, Width = 78, Top = 236, DialogResult = DialogResult.OK };
                Button cancel = new Button { Text = "取消", Left = 272, Width = 78, Top = 236, DialogResult = DialogResult.Cancel };
                dlg.Controls.Add(lb);
                dlg.Controls.Add(ok);
                dlg.Controls.Add(cancel);
                dlg.AcceptButton = ok;
                dlg.CancelButton = cancel;

                return dlg.ShowDialog(owner) == DialogResult.OK ? lb.SelectedIndex : -1;
            }
        }
    }
}
